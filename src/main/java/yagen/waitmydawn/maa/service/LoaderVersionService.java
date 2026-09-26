package yagen.waitmydawn.maa.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加载器版本维护服务。
 *
 * <p>版本数据唯一存放在外部可编辑文件 {@code maa_db/loader-versions.json}：
 * 启动时读取，应用就绪后与每天 00:00 拉取上游最新正式版并原地写回该文件，
 * 后续只改 JSON、不用重新编译。
 *
 * <p><b>刷新语义 = 发现 + 合并</b>（2026-09-26 重做）：过去只遍历表里已有的 MC 版本，
 * 于是新版本（例如 Forge 的 26.3）永远不会进表；现在改为"从上游把 MC 版本集合发现出来"，
 * 只增不删、已有键更新值、标了 {@code manual} 的键不覆盖。
 *
 * <p><b>版本挑选策略</b>（按用户决策）：正式版优先；没有正式版就用最新 beta；再没有就用最新 alpha ——
 * 总之该 MC 版本必须有一个可用加载器版本。非正式版会在 {@code prerelease} 里标注（release/beta/alpha），
 * 供排查与前端提示使用；{@code gameVersions} 本身仍是"MC 版本 -> 版本号字符串"的扁平映射，消费方无需改动。
 *
 * <p>若某个上游源不可用，仅保留文件/内存中上一次可用版本，不影响构建流程。
 */
@Service
public class LoaderVersionService {

    private static final Logger log = LoggerFactory.getLogger(LoaderVersionService.class);

    /** NeoForge 没有公开的 promotions JSON，只能从 Maven 元数据里按“版本族”取最新版。 */
    private static final String NEOFORGE_METADATA_URL =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    /**
     * MC 1.20.1 时代的 NeoForge 挂在另一个 artifact 下（版本形如 {@code 1.20.1-47.1.106}）。
     * 只查上面那个源的话，1.20.1 + neoforge 这个组合永远解析不到 —— 而它确实存在。
     */
    private static final String NEOFORGE_LEGACY_METADATA_URL =
            "https://maven.neoforged.net/releases/net/neoforged/forge/maven-metadata.xml";
    private static final String FORGE_PROMOS_URL =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String FABRIC_LOADER_URL =
            "https://meta.fabricmc.net/v2/versions/loader";

    private static final Pattern MAVEN_VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    /** 剩余延迟重试次数；任何一次全成功就恢复成 {@link #MAX_RETRIES}。 */
    private final AtomicInteger retriesLeft = new AtomicInteger(MAX_RETRIES);

    /** 单个源的最多尝试次数（首次 + 2 次重试）。 */
    private static final int FETCH_ATTEMPTS = 3;
    /** 失败后的延迟重试计划（秒）；用完就等下次启动或每日 00:00。 */
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_SEC = {60, 300, 900};

    @Value("${maa.loader-versions.path:maa_db/loader-versions.json}")
    private String versionsPath;

    /** 单次拉取失败后的退避基数（毫秒）；实际等待 = base × 2^(尝试次数-1) + 抖动。测试里设 0 可跳过等待。 */
    @Value("${maa.loader-versions.retry-backoff-base-ms:1000}")
    private long retryBackoffBaseMs = 1000;

    /** 当前生效的版本快照（从 loader-versions.json 加载，刷新后原地更新）。 */
    private volatile JsonNode current;

    public LoaderVersionService(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        Path file = Paths.get(versionsPath);
        try {
            if (!Files.exists(file)) {
                throw new IllegalStateException("缺少加载器版本文件: " + file.toAbsolutePath()
                        + "（请提供该文件或检查 maa.loader-versions.path 配置）");
            }
            current = objectMapper.readTree(Files.readAllBytes(file));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取加载器版本文件失败: " + file.toAbsolutePath(), e);
        }
        log.info("LoaderVersionService 初始化完成: {}", describeSource(current));
    }

    /**
     * 启动完成后异步刷新一次，避免阻塞应用就绪。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        retriesLeft.set(MAX_RETRIES);          // 每次触发都有自己的重试额度
        Thread.startVirtualThread(() -> {
            log.info("启动后开始刷新加载器版本...");
            refreshAll();
        });
    }

    /**
     * 每天 00:00 刷新一次（服务器本地时区）。
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void scheduledDailyRefresh() {
        // 交给虚拟线程：调度线程是单线程的，别让网络等待（含重试）占着它
        retriesLeft.set(MAX_RETRIES);
        Thread.startVirtualThread(this::refreshAll);
    }

    /**
     * 解析指定加载器在指定 MC 版本下应写入 mrpack 的加载器版本号。
     *
     * @param loader    用户侧加载器名：neoforge / forge / fabric
     * @param mcVersion 如 1.20.1、1.21.1、1.21.5、26.2
     * @return 加载器版本号，如 21.1.249 / 0.19.5
     * @throws IllegalArgumentException 加载器不支持，或该 MC 版本没有维护的加载器版本
     */
    public String resolve(String loader, String mcVersion) {
        String loaderKey = normalizeLoader(loader);
        if (loaderKey == null) {
            throw new IllegalArgumentException("不支持的加载器: " + loader + "（仅支持 neoforge / forge / fabric）");
        }
        JsonNode root = current;
        JsonNode loaderNode = root == null ? null : root.path("loaders").path(loaderKey);
        if (loaderNode == null || loaderNode.isMissingNode()) {
            throw new IllegalArgumentException("加载器版本表中缺少配置: " + loaderKey);
        }

        JsonNode gameVersions = loaderNode.path("gameVersions");
        String mcKey = mcVersion == null ? "" : mcVersion.trim();
        JsonNode hit = gameVersions.path(mcKey);
        if (hit.isMissingNode()) {
            // Fabric Loader 与 MC 小版本无关，用 "*" 通配项兜底。
            hit = gameVersions.path("*");
        }
        if (hit.isMissingNode() || hit.asText().isBlank()) {
            // 报错要能直接指导下一步：把"这个 loader 目前支持哪些 MC 版本"一起给出来
            List<String> known = new ArrayList<>(gameVersions.propertyNames());
            java.util.Collections.sort(known);
            throw new IllegalArgumentException("没有 " + loaderKey + " 在 MC " + mcKey
                    + " 下已维护的加载器版本（当前已维护 " + known.size() + " 个 MC 版本："
                    + String.join(", ", known) + "）");
        }
        return hit.asText();
    }

    /**
     * 返回 mrpack dependencies 中使用的依赖键（fabric 用户侧叫 fabric，依赖键是 fabric-loader）。
     */
    public String dependencyKey(String loader) {
        String key = normalizeLoader(loader);
        if (key == null) {
            throw new IllegalArgumentException("不支持的加载器: " + loader + "（仅支持 neoforge / forge / fabric）");
        }
        return key;
    }

    /** 当前生效的版本表快照（只读排查用，例如 {@code GET /api/modpack/loader-versions}）。 */
    public JsonNode snapshot() {
        return current;
    }

    /** 这个环境我们有没有维护？（识别用户点名环境后，用它决定要不要真的切） */
    public boolean supports(String loader, String mcVersion) {
        try {
            resolve(loader, mcVersion);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 某个加载器已维护的 MC 版本（升序）。工具被拒绝时把它回给模型，让模型能如实告诉用户有哪些可选。
     * 刻意不把整份清单塞进提示词（77 个 forge 版本 ≈ 1KB，每次请求都要付），改由 setEnvironment 工具按需查。
     */
    public List<String> availableVersions(String loader) {
        String loaderKey = normalizeLoader(loader);
        if (loaderKey == null || current == null) return List.of();
        JsonNode gv = current.path("loaders").path(loaderKey).path("gameVersions");
        List<String> keys = new ArrayList<>(gv.propertyNames());
        keys.sort(LoaderVersionService::compareMcVersions);
        return keys;
    }

    /** 该环境是否只有预发布版（返回 "" / "beta" / "alpha"），用于提示"仅有 beta" */
    public String prereleaseTag(String loader, String mcVersion) {
        String loaderKey = normalizeLoader(loader);
        if (loaderKey == null || current == null || mcVersion == null) return "";
        return current.path("loaders").path(loaderKey).path("prerelease").path(mcVersion.trim()).asText("");
    }

    /** MC 版本排序：1.x 段按数字比，新的 26.x 排在 1.x 之后。 */
    static int compareMcVersions(String a, String b) {
        boolean aOld = a.startsWith("1."), bOld = b.startsWith("1.");
        if (aOld != bOld) return aOld ? -1 : 1;
        return compareVersions(a, b);
    }

    /**
     * 拉取所有上游源并刷新内存快照；内容有变就写回 loader-versions.json。
     *
     * <p><b>每个源独立容错</b>：某一个源挂了只丢它自己那份数据，其它源照常合并、照常落盘
     * （旧实现是"全有或全无"：neoforge 第一个跑，它一挂 forge / fabric 根本不会执行）。
     * 只要还有源没拿到，就安排一次延迟重试 —— 启动瞬间网络没热是这类超时的常见原因。
     */
    public void refreshAll() {
        if (!refreshing.compareAndSet(false, true)) {
            log.info("加载器版本刷新已在执行中，跳过本次触发。");
            return;
        }
        long t0 = System.currentTimeMillis();
        List<SourceResult> results = new ArrayList<>();
        try {
            ObjectNode root = ((ObjectNode) current).deepCopy();
            ObjectNode loaders = root.withObject("loaders");

            refreshNeoForge(loaders, results);
            refreshForge(loaders, results);
            refreshFabric(loaders, results);

            // 内容没变就不写回：updatedAt 每次都变会让这个受版本控制的文件常年显示 "M"
            boolean unchanged = contentUnchanged(root, current);
            if (!unchanged) {
                root.put("updatedAt", Instant.now().toString());
                current = root;
                persist(root);
            }
            log.info("加载器版本刷新{}（耗时 {} ms）｜{}", unchanged ? "无变化，跳过写回" : "完成",
                    System.currentTimeMillis() - t0, summarize(results));
        } catch (Exception e) {
            // 走到这里说明连"合并/落盘"都出问题了（单个源失败已经在内部吃掉）
            log.warn("加载器版本刷新异常: {}", describe(e));
            results.add(new SourceResult("整体", false, describe(e)));
        } finally {
            refreshing.set(false);
        }

        if (results.stream().anyMatch(r -> !r.ok())) {
            scheduleRetry(summarize(results));
        } else {
            retriesLeft.set(MAX_RETRIES);   // 全部成功 → 重试额度恢复
        }
    }

    /** 某个上游源的拉取结果（用于日志汇总与"是否需要重试"判断）。 */
    record SourceResult(String source, boolean ok, String detail) {}

    private static String summarize(List<SourceResult> results) {
        if (results.isEmpty()) return "（无源结果）";
        List<String> parts = new ArrayList<>();
        for (SourceResult r : results) {
            parts.add(r.source() + (r.ok() ? "=OK(" + r.detail() + ")" : "=失败(" + r.detail() + ")"));
        }
        return String.join(" ", parts);
    }

    /**
     * 失败后延迟重试：1min / 5min / 15min，最多 {@link #MAX_RETRIES} 次；任何一次全成功即重置额度。
     * 为什么需要：启动瞬间（刚开机、刚连 VPN）网络没热，超时往往是"等一下就好"，
     * 而原来的下一次机会要等到次日 00:00。
     */
    private void scheduleRetry(String reason) {
        int left = retriesLeft.getAndDecrement();
        if (left <= 0) {
            log.warn("加载器版本刷新连续失败，已放弃延迟重试（下次由每日 00:00 或重启触发）: {}", reason);
            return;
        }
        long delaySec = RETRY_DELAYS_SEC[Math.min(MAX_RETRIES - left, RETRY_DELAYS_SEC.length - 1)];
        log.info("加载器版本刷新有源未成功，{} 秒后自动重试（剩余 {} 次）｜{}", delaySec, left, reason);
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(delaySec * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            refreshAll();
        });
    }

    // ==================== 上游刷新（发现 + 合并）====================
    //
    // 关键区别：**MC 版本集合是从上游发现出来的**，不再依赖表里已有的键。
    // 旧实现遍历 gameVersions.propertyNames()，所以新版本（如 Forge 26.3）永远进不了表。

    /**
     * NeoForge：版本号本身编码了目标 MC 版本（20.4.251 → MC 1.20.4），因此可以从 maven 元数据反向发现。
     * 注意要查两个 artifact：新的是 {@code net.neoforged:neoforge}，MC 1.20.1 那批在 {@code net.neoforged:forge}。
     */
    private void refreshNeoForge(ObjectNode loaders, List<SourceResult> results) {
        ObjectNode entry = loaders.withObject("neoforge");
        ObjectNode gameVersions = entry.withObject("gameVersions");
        ObjectNode prerelease = entry.withObject("prerelease");

        // 两个源各自容错：旧源挂了只丢 MC 1.20.1 那一条，不影响主源那一大批
        Set<String> mainVersions = fetchMavenVersions(NEOFORGE_METADATA_URL, "neoforge主源", results);
        Set<String> legacyVersions = fetchMavenVersions(NEOFORGE_LEGACY_METADATA_URL, "neoforge旧源(1.20.1)", results);
        if (mainVersions.isEmpty() && legacyVersions.isEmpty()) {
            return;     // 两个源都没拿到：跳过合并（mergeInto 只增不删，合并空集也没意义）
        }

        Discovery discovered = discoverNeoForge(mainVersions, legacyVersions);
        MergeResult r = mergeInto(gameVersions, prerelease, discovered, entry.path("manual"));
        log.info("neoforge 合并: 发现 {} 个 MC 版本 → 新增 {} / 更新 {} / 预发布 {}",
                discovered.best.size(), r.added, r.updated, r.prerelease);
    }

    /** Forge：promotions 的<b>键</b>就是 MC 版本（{@code 1.7.10-latest} / {@code 26.3-latest} …），天然可全量发现。 */
    private void refreshForge(ObjectNode loaders, List<SourceResult> results) {
        ObjectNode entry = loaders.withObject("forge");
        ObjectNode gameVersions = entry.withObject("gameVersions");
        ObjectNode prerelease = entry.withObject("prerelease");

        JsonNode promos;
        try {
            promos = objectMapper.readTree(fetchWithRetry(FORGE_PROMOS_URL)).path("promos");
            if (!promos.isObject()) {
                throw new IllegalStateException("响应缺少 promos 字段");
            }
        } catch (Exception e) {
            results.add(new SourceResult("forge", false, describe(e)));
            return;
        }
        Discovery discovered = discoverForge(promos);
        results.add(new SourceResult("forge", true, discovered.best.size() + " 个 MC 版本"));

        MergeResult r = mergeInto(gameVersions, prerelease, discovered, entry.path("manual"));
        log.info("forge 合并: 发现 {} 个 MC 版本 → 新增 {} / 更新 {} / 预发布 {}",
                discovered.best.size(), r.added, r.updated, r.prerelease);
    }

    private void refreshFabric(ObjectNode loaders, List<SourceResult> results) {
        ObjectNode entry = loaders.withObject("fabric-loader");
        ObjectNode gameVersions = entry.withObject("gameVersions");
        JsonNode list;
        try {
            list = objectMapper.readTree(fetchWithRetry(FABRIC_LOADER_URL));
        } catch (Exception e) {
            results.add(new SourceResult("fabric", false, describe(e)));
            return;
        }

        String best = null;
        for (JsonNode item : list) {
            boolean stable = item.path("stable").asBoolean(false);
            String version = item.path("version").asText("");
            if (!stable || version.isBlank()) {
                continue;
            }
            if (best == null || compareVersions(version, best) > 0) {
                best = version;
            }
        }
        if (best == null) {
            results.add(new SourceResult("fabric", false, "上游未返回任何 stable 版本"));
            return;
        }
        gameVersions.put("*", best);
        results.add(new SourceResult("fabric", true, "loader " + best));
        log.info("fabric-loader 合并: 最新稳定版 {}", best);
    }

    // ==================== 工具方法 ====================

    private String fetch(String url) {
        String body = restClient.get()
                .uri(url)
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("上游返回空内容: " + url);
        }
        return body;
    }

    /**
     * 带重试的拉取：最多 {@link #FETCH_ATTEMPTS} 次，退避 1s / 2s + 抖动。
     *
     * <p>只对"可能自己好起来"的失败重试（超时 / 连接失败 / 5xx）；4xx 立刻放弃 —— 重试也不会变好。
     * <p><b>刻意不接 {@code ModrinthThrottle} 的全局令牌闸</b>：那是给 Modrinth API 的 429 限流用的，
     * 而这两个是静态文件站（maven 元数据 / promotions / fabric meta），接上去只会白白排队。
     */
    private String fetchWithRetry(String url) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
            try {
                return fetch(url);
            } catch (Exception e) {
                last = e;
                if (!retryable(e) || attempt == FETCH_ATTEMPTS) break;
                long waitMs = (long) Math.pow(2, attempt - 1) * Math.max(0, retryBackoffBaseMs)
                        + (retryBackoffBaseMs > 0
                            ? java.util.concurrent.ThreadLocalRandom.current().nextLong(250) : 0);
                log.info("拉取失败将重试（第 {}/{} 次，等 {}ms）: {} —— {}",
                        attempt, FETCH_ATTEMPTS, waitMs, url, describe(e));
                Thread.sleep(waitMs);
            }
        }
        throw last;
    }

    /** 这个异常值得重试吗？（4xx 不值得，其余网络类问题值得） */
    static boolean retryable(Exception e) {
        if (e instanceof HttpClientErrorException clientError) {
            return clientError.getStatusCode().is5xxServerError();
        }
        return e instanceof RestClientException || e instanceof java.io.IOException;
    }

    /** 日志里用的简短原因（别把整段堆栈刷到控制台）。 */
    private static String describe(Exception e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().replaceAll("\\s+", " ");
        if (msg.length() > 160) msg = msg.substring(0, 160) + "…";
        return e.getClass().getSimpleName() + (msg.isBlank() ? "" : ": " + msg);
    }

    /** 拉一个 maven 源并解析版本号；失败记进 results 并返回空集（调用方"能拿多少算多少"）。 */
    private Set<String> fetchMavenVersions(String url, String label, List<SourceResult> results) {
        try {
            Set<String> versions = parseMavenVersions(fetchWithRetry(url));
            results.add(new SourceResult(label, true, versions.size() + " 个版本"));
            return versions;
        } catch (Exception e) {
            results.add(new SourceResult(label, false, describe(e)));
            return Set.of();
        }
    }

    static Set<String> parseMavenVersions(String xml) {
        Matcher matcher = MAVEN_VERSION_TAG.matcher(xml);
        Set<String> versions = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        return versions;
    }

    // ---------- 发布通道优先级 ----------
    // 用户定的策略：正式版优先；没有正式版用最新 beta；再没有用最新 alpha —— 必须有可用版本。
    private static final int TIER_ALPHA = 1;
    private static final int TIER_BETA = 2;
    private static final int TIER_RELEASE = 3;
    private static final int TIER_RELEASE_LATEST = 4;   // Forge promotions 里 -latest 比 -recommended 新

    private static int tierOf(String version) {
        if (!version.contains("-")) return TIER_RELEASE;
        String v = version.toLowerCase();
        if (v.contains("beta")) return TIER_BETA;
        return TIER_ALPHA;                              // alpha / rc / pre / snapshot 一律按最低档
    }

    private static String tierName(int tier) {
        if (tier >= TIER_RELEASE) return "release";
        return tier == TIER_BETA ? "beta" : "alpha";
    }

    /**
     * 一次刷新"发现"出来的结果：MC 版本 →（该环境下最合适的加载器版本, 它的发布通道）。
     * 挑选规则：通道优先级高者胜；同通道比版本号，大者胜。
     */
    static final class Discovery {
        final Map<String, String> best = new TreeMap<>();
        final Map<String, Integer> tier = new LinkedHashMap<>();

        void offer(String mc, String version, int rank) {
            Integer current = tier.get(mc);
            if (current == null || rank > current
                    || (rank == current && compareVersions(version, best.get(mc)) > 0)) {
                best.put(mc, version);
                tier.put(mc, rank);
            }
        }
    }

    /**
     * 从两个 maven 源推导 NeoForge 的 MC → 版本表（纯函数，便于离线测试）。
     *
     * @param neoforgeVersions {@code net.neoforged:neoforge} 的版本（20.4.251 / 26.2.0.88 …）
     * @param legacyVersions   {@code net.neoforged:forge} 的版本（1.20.1-47.1.106 …，MC 1.20.1 那批）
     */
    static Discovery discoverNeoForge(Set<String> neoforgeVersions, Set<String> legacyVersions) {
        Discovery discovered = new Discovery();
        for (String v : neoforgeVersions) {
            String mc = mcVersionOfNeoForge(v);
            if (mc != null) {
                discovered.offer(mc, v, tierOf(v));
            }
        }
        for (String v : legacyVersions) {
            // 形如 1.20.1-47.1.106：MC 版本在前缀里，写进 mrpack 的是 47.1.106
            int dash = v.indexOf('-');
            if (dash <= 0 || dash == v.length() - 1) continue;
            String mc = v.substring(0, dash);
            String loaderVersion = v.substring(dash + 1);
            if (!mc.startsWith("1.") || loaderVersion.isEmpty()
                    || !Character.isDigit(loaderVersion.charAt(0))) continue;
            if (loaderVersion.contains("-")) continue;      // 旧 artifact 只收正式版
            discovered.offer(mc, loaderVersion, TIER_RELEASE);
        }
        return discovered;
    }

    /**
     * 从 Forge promotions 推导 MC → 版本表（纯函数）。
     * 键形如 {@code 1.20.1-latest} / {@code 1.7.10-recommended}：键的前缀就是 MC 版本，latest 比 recommended 新。
     */
    static Discovery discoverForge(JsonNode promos) {
        Discovery discovered = new Discovery();
        if (promos == null || !promos.isObject()) return discovered;
        for (String key : promos.propertyNames()) {
            int dash = key.lastIndexOf('-');
            if (dash <= 0) continue;
            String mc = key.substring(0, dash);
            String kind = key.substring(dash + 1);
            String version = promos.path(key).asText("");
            if (version.isBlank()) continue;
            discovered.offer(mc, version, "latest".equals(kind) ? TIER_RELEASE_LATEST : TIER_RELEASE);
        }
        return discovered;
    }

    record MergeResult(int added, int updated, int prerelease) {}

    /**
     * 把发现结果合并进表：<b>只增不删</b>；{@code manual} 里列出的 MC 版本不覆盖；
     * {@code prerelease} 每次重建（某个环境从 beta 升到正式版时要能退出该表）。
     */
    static MergeResult mergeInto(ObjectNode gameVersions, ObjectNode prerelease,
                                 Discovery discovered, JsonNode manual) {
        Set<String> manualKeys = new java.util.HashSet<>();
        if (manual != null && manual.isArray()) {
            for (JsonNode n : manual) manualKeys.add(n.asText(""));
        }
        prerelease.removeAll();
        int added = 0, updated = 0, pre = 0;
        for (Map.Entry<String, String> e : discovered.best.entrySet()) {
            String mc = e.getKey(), version = e.getValue();
            if (manualKeys.contains(mc)) continue;
            String old = gameVersions.path(mc).asText(null);
            if (old == null) {
                gameVersions.put(mc, version);
                added++;
            } else if (!old.equals(version)) {
                gameVersions.put(mc, version);
                updated++;
            }
            int rank = discovered.tier.getOrDefault(mc, TIER_RELEASE);
            if (rank < TIER_RELEASE) {
                prerelease.put(mc, tierName(rank));
                pre++;
            }
        }
        return new MergeResult(added, updated, pre);
    }

    /**
     * NeoForge 版本号 → 目标 MC 版本。
     *
     * <p>已验证（2026-09-26，取 jar 的 MANIFEST 看 Specification-Version）：{@code 20.4.251}
     * 的 Specification-Version 就是 {@code 1.20.4} —— 即"版本族与 MC 版本 1:1"，不需要别名表
     * （20.3 / 20.5 各自有独立族，只是目前只有预发布，正好走"回退 beta/alpha"的策略）。
     *
     * <p>26.x 起改成日期式版本号，第三段为 0 时省略：{@code 26.2.0.88 → 26.2}、
     * {@code 26.1.2.109 → 26.1.2}、{@code 26.3.0.22-beta → 26.3}。
     * 这一段是<b>推断</b>：26.x 的 jar 已不再声明 MC 版本，依据是 Forge promotions 里的 MC 命名
     * （26.1 / 26.1.1 / 26.1.2 / 26.2 / 26.3）加上面那条 1:1 惯例。
     *
     * @return MC 版本；识别不了返回 null（该版本被忽略）
     */
    static String mcVersionOfNeoForge(String version) {
        String core = version.split("-")[0].split("\\+")[0];
        String[] p = core.split("\\.");
        if (p.length < 2) return null;
        int major = num(p[0]);
        if (major == 20 || major == 21) {          // 1.20.2 ← 20.2.93；1.21 ← 21.0.167；1.21.1 ← 21.1.251
            int minor = num(p[1]);
            return minor == 0 ? "1." + major : "1." + major + "." + minor;
        }
        if (major >= 22) {                         // 26.2 ← 26.2.0.88；26.1.2 ← 26.1.2.109
            int third = p.length > 2 ? num(p[2]) : 0;
            return third == 0 ? major + "." + p[1] : major + "." + p[1] + "." + third;
        }
        return null;
    }

    private static int num(String segment) {
        String digits = segment.replaceAll("\\D.*$", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 两份 JSON 是否只差 updatedAt（真正内容没变就不写文件）。 */
    private static boolean contentUnchanged(JsonNode a, JsonNode b) {
        ObjectNode x = ((ObjectNode) a).deepCopy();
        ObjectNode y = ((ObjectNode) b).deepCopy();
        x.remove("updatedAt");
        y.remove("updatedAt");
        return x.equals(y);
    }

    /** 纯数字段逐位比较（每段按数字大小比较，缺失段视为 0）。 */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int max = Math.max(pa.length, pb.length);
        for (int i = 0; i < max; i++) {
            int na = i < pa.length ? parseSegment(pa[i]) : 0;
            int nb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }
        return 0;
    }

    private static int parseSegment(String segment) {
        String digits = segment.replaceAll("\\D.*$", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 用户侧加载器名 -> mrpack 依赖键。
     * fabric 在 mrpack 里的依赖键是 fabric-loader。
     */
    private static String normalizeLoader(String loader) {
        if (loader == null) {
            return null;
        }
        return switch (loader.trim().toLowerCase()) {
            case "neoforge" -> "neoforge";
            case "forge" -> "forge";
            case "fabric" -> "fabric-loader";
            default -> null;
        };
    }

    /** 原子写回唯一数据文件 loader-versions.json。 */
    private void persist(JsonNode data) {
        try {
            Path target = Paths.get(versionsPath).toAbsolutePath();
            Path dir = target.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = Files.createTempFile(dir, "loader-versions-", ".tmp");
            Files.writeString(tmp, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data),
                    StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("加载器版本已写回: {}", target);
        } catch (Exception e) {
            log.warn("写入加载器版本文件失败（不影响本次内存使用）: {}", e.getMessage());
        }
    }

    private static String describeSource(JsonNode node) {
        JsonNode updatedAt = node.path("updatedAt");
        return updatedAt.isMissingNode() ? "空结构" : "updatedAt=" + updatedAt.asText();
    }
}
