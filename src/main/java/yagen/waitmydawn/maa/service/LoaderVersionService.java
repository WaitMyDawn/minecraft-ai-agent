package yagen.waitmydawn.maa.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加载器版本维护服务。
 *
 * <p>版本数据唯一存放在外部可编辑文件 {@code maa_db/loader-versions.json}：
 * 启动时读取，应用就绪后与每天 00:00 拉取上游最新正式版并原地写回该文件，
 * 后续只改 JSON、不用重新编译。
 *
 * <p>若某个上游源不可用，仅保留文件/内存中上一次可用版本，不影响构建流程。
 */
@Service
public class LoaderVersionService {

    private static final Logger log = LoggerFactory.getLogger(LoaderVersionService.class);

    /** NeoForge 没有公开的 promotions JSON，只能从 Maven 元数据里按“版本族”取最新正式版。 */
    private static final String NEOFORGE_METADATA_URL =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    private static final String FORGE_PROMOS_URL =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String FABRIC_LOADER_URL =
            "https://meta.fabricmc.net/v2/versions/loader";

    private static final Pattern MAVEN_VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    @Value("${maa.loader-versions.path:maa_db/loader-versions.json}")
    private String versionsPath;

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
        refreshAll();
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
            throw new IllegalArgumentException(
                    "没有 " + loaderKey + " 在 MC " + mcKey + " 下已维护的加载器正式版本");
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

    /**
     * 拉取所有上游源并刷新内存快照；成功后直接写回 loader-versions.json。
     * 任一源失败不会影响内存中已有版本。
     */
    public void refreshAll() {
        if (!refreshing.compareAndSet(false, true)) {
            log.info("加载器版本刷新已在执行中，跳过本次触发。");
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            JsonNode base = current;
            ObjectNode root = ((ObjectNode) base).deepCopy();
            root.put("updatedAt", Instant.now().toString());
            ObjectNode loaders = root.withObject("loaders");

            refreshNeoForge(loaders);
            refreshForge(loaders);
            refreshFabric(loaders);

            current = root;
            persist(root);
            log.info("加载器版本刷新完成（耗时 {} ms）", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.warn("加载器版本刷新失败，继续使用上一次可用版本: {}", e.getMessage());
        } finally {
            refreshing.set(false);
        }
    }

    // ==================== 上游刷新 ====================

    private void refreshNeoForge(ObjectNode loaders) throws Exception {
        ObjectNode entry = loaders.withObject("neoforge");
        ObjectNode gameVersions = entry.withObject("gameVersions");
        if (gameVersions.propertyNames().isEmpty()) {
            return;
        }

        Set<String> stableVersions = parseMavenVersions(fetch(NEOFORGE_METADATA_URL));
        int updated = 0;
        for (String mc : gameVersions.propertyNames()) {
            String family = neoforgeFamilyFor(mc);
            String best = latestStableInFamily(stableVersions, family);
            if (best != null) {
                gameVersions.put(mc, best);
                updated++;
            }
        }
        log.info("neoforge 版本刷新: 更新 {} 个 MC 版本", updated);
    }

    private void refreshForge(ObjectNode loaders) throws Exception {
        ObjectNode entry = loaders.withObject("forge");
        ObjectNode gameVersions = entry.withObject("gameVersions");
        JsonNode promos = objectMapper.readTree(fetch(FORGE_PROMOS_URL)).path("promos");
        if (!promos.isObject()) {
            throw new IllegalStateException("Forge promotions 响应缺少 promos 字段");
        }

        int updated = 0;
        for (String mc : gameVersions.propertyNames()) {
            JsonNode latest = promos.path(mc + "-latest");
            if (latest.isMissingNode()) {
                latest = promos.path(mc + "-recommended");
            }
            if (!latest.isMissingNode() && !latest.asText().isBlank()) {
                gameVersions.put(mc, latest.asText());
                updated++;
            }
        }
        log.info("forge 版本刷新: 更新 {} 个 MC 版本", updated);
    }

    private void refreshFabric(ObjectNode loaders) throws Exception {
        ObjectNode entry = loaders.withObject("fabric-loader");
        ObjectNode gameVersions = entry.withObject("gameVersions");
        JsonNode list = objectMapper.readTree(fetch(FABRIC_LOADER_URL));
        String best = null;
        if (list != null && list.isArray()) {
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
        }
        if (best != null) {
            gameVersions.put("*", best);
            log.info("fabric-loader 版本刷新: 最新稳定版 {}", best);
        } else {
            throw new IllegalStateException("Fabric meta 未返回任何 stable 版本");
        }
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

    private static Set<String> parseMavenVersions(String xml) {
        Matcher matcher = MAVEN_VERSION_TAG.matcher(xml);
        Set<String> versions = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        return versions;
    }

    /**
     * NeoForge 没有官方 promotions 文件，只能通过版本族前缀从 Maven 元数据推断。
     * MC 1.21.1 -> 族 21.1；MC 1.21 -> 族 21.0；MC 26.2 -> 族 26.2（实际形如 26.2.0.x）。
     */
    private static String neoforgeFamilyFor(String mcVersion) {
        String mc = mcVersion.trim();
        if (mc.startsWith("1.")) {
            String rest = mc.substring(2);
            return rest.contains(".") ? rest : rest + ".0";
        }
        return mc;
    }

    private static String latestStableInFamily(Set<String> versions, String familyPrefix) {
        String best = null;
        for (String version : versions) {
            if (version.contains("-")) {
                continue; // 排除 -beta / -alpha / -rc 等预发布
            }
            if (!version.startsWith(familyPrefix + ".")) {
                continue;
            }
            if (best == null || compareVersions(version, best) > 0) {
                best = version;
            }
        }
        return best;
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
