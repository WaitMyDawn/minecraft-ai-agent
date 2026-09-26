package yagen.waitmydawn.maa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import yagen.waitmydawn.maa.runtime.RequestScope;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Modrinth 出网层：运行时路径上<b>唯一</b>真正发出 HTTP 的地方。
 *
 * <p>为什么从 {@code ModrinthApiClient} 里拆出来：{@code @Cacheable} 的写回挡不住——只要方法被注解，
 * Spring 就一定会把它的返回值塞进共享缓存，而调用方拿到的值有可能来自不可信通道（用户浏览器回传的
 * 委派数据）。把"带缓存的出网"单独放一层，`ModrinthApiClient` 就能在<b>进入这一层之前</b>决定
 * 用客户端的数据还是自己抓，于是"只有服务器自己抓到的才写缓存"对两层缓存同时成立。
 *
 * <p>本层同时保留 {@code sync = true} 的防击穿：同一个 key 只放一个线程出去查，
 * 其余等在缓存锁上——这条性质不能因为引入委派而丢掉。
 *
 * <p>所有出网都经过 {@link ModrinthThrottle} 的全局令牌闸与 429 指数退避（两道闸分工见该类注释）。
 */
@Service
public class ModrinthFetcher {

    private final RestClient restClient;
    /** 全局出网令牌闸：运行时所有 Modrinth 请求都从这里取许可（详见 ModrinthThrottle 类注释） */
    private final ModrinthThrottle throttle;
    private final CacheManager cacheManager;
    /** 批量预取总开关：怀疑批量端点出问题时可置 false，立刻退回逐条查询 */
    private final boolean batchPrefetch;

    /** 批量端点单次最多带多少个 id。实测 100 个一次返回（622KB / URL 约 1.5KB），留足余量 */
    private static final int BATCH_LIMIT = 100;

    public ModrinthFetcher(RestClient restClient, ModrinthThrottle throttle, CacheManager cacheManager,
                           @Value("${maa.modrinth.batch-prefetch:true}") boolean batchPrefetch) {
        this.restClient = restClient;
        this.throttle = throttle;
        this.cacheManager = cacheManager;
        this.batchPrefetch = batchPrefetch;
    }

    // 🔥 严格的防击穿缓存锁
    @Cacheable(value = "modVersions", key = "#projectId + '_' + #mcVersion + '_' + #loaders", sync = true)
    public JsonNode getLatestVersion(String projectId, String mcVersion, String loaders) {
        return executeWithSmartRetry("https://api.modrinth.com/v2/project/{id}/version?game_versions=[\"{v}\"]&loaders={l}", projectId, mcVersion, loaders);
    }

    /**
     * 方案 A 优先：直接按 slug 查最新兼容版本；若该 slug 路由 404/无结果，
     * 走方案 B：projectInfo 解析出稳定 project_id 后用 id 再查一次。
     * 返回 null 表示该 slug 在当前 loader+mc 下没有可用版本（或项目已不存在）。
     */
    @Cacheable(value = "modVersionsBySlug", key = "#slug + '|' + #mcVersion + '|' + #loader", sync = true)
    public JsonNode getLatestCompatibleVersionBySlug(String slug, String mcVersion, String loader) {
        String loaders = "[\"" + loader + "\"]";
        JsonNode version = executeWithSmartRetry(
                "https://api.modrinth.com/v2/project/{slug}/version?game_versions=[\"{v}\"]&loaders={l}",
                slug, mcVersion, loaders);
        if (version != null) {
            return version;
        }
        JsonNode info = getProjectInfo(slug);
        if (info == null || !info.has("id")) {
            return null;
        }
        return getLatestVersion(info.path("id").asText(), mcVersion, loaders);
    }

    @Cacheable(value = "projectInfo", key = "#slugOrId", sync = true)
    public JsonNode getProjectInfo(String slugOrId) {
        return executeWithSmartRetry("https://api.modrinth.com/v2/project/{id}", slugOrId);
    }

    /**
     * 按 Modrinth 版本 id 精确取版本（P6：处理「只给 version_id 的必需依赖」）。
     *
     * <p>Modrinth 的依赖有两种形态：只给 project_id（不限版本）或给 version_id（锁定到具体版本）。
     * 旧实现只读 project_id，导致 version_id 型依赖被整条跳过。
     *
     * @return 版本 JSON；查不到返回 null
     */
    @Cacheable(value = "versionById", key = "#versionId", sync = true)
    public JsonNode getVersionById(String versionId) {
        return executeForPlainObject("https://api.modrinth.com/v2/version/{id}", versionId);
    }

    /**
     * 关键词搜索（收口版）。
     *
     * <p>为什么要收口：原先有 4 处 {@code /v2/search} 散落在 ChatController 与 ModpackController
     * 里用 restClient 裸发——既享受不到 429 退避，也绕过了全局令牌闸。而搜索恰恰是最容易连发
     * 打爆限额的一类请求（盲盒抽卡一次能翻 30~60 页，且旧实现只 Thread.sleep(100) 就继续）。
     *
     * <p>返回的是搜索响应<b>整体</b>（含 {@code hits}/{@code total_hits}），不是单条命中——
     * 调用方自己读 {@code hits}。
     *
     * @param query  原始关键词，未编码
     * @param facets 原始 facets JSON，未编码
     */
    public JsonNode search(String query, int limit, String facets) {
        return searchPage(query, limit, 0, null, facets);
    }

    /**
     * 带翻页的搜索。
     *
     * <p>刻意<b>不加</b>缓存：调用方（盲盒抽卡）本来就不缓存，加缓存会悄悄改变它的行为。
     * 这里只保证它进入令牌闸与退避体系。
     *
     * @param index 排序方式；为 null/空时省略该参数
     */
    public JsonNode searchPage(String query, int limit, int offset, String index, String facets) {
        return searchByUrl(buildSearchUrl(query, limit, offset, index, facets));
    }

    /**
     * 搜索 URL 的构造。
     *
     * <p>公开静态是因为门面层也要用它：委派给浏览器时，"要什么"就是"在哪查"，
     * 两边必须拼出<b>完全一样</b>的 URL，否则委派回来的结果和服务器自抓的结果对不上号。
     */
    public static String buildSearchUrl(String query, int limit, int offset, String index, String facets) {
        StringBuilder url = new StringBuilder("https://api.modrinth.com/v2/search?limit=")
                .append(limit)
                .append("&offset=").append(offset);
        // query 为空时整个参数都不发（盲盒抽卡就是"不带关键词、只按 facets 浏览"）
        if (query != null && !query.isBlank()) {
            url.append("&query=").append(encode(query));
        }
        if (index != null && !index.isBlank()) {
            url.append("&index=").append(encode(index));
        }
        if (facets != null && !facets.isBlank()) {
            url.append("&facets=").append(encode(facets));
        }
        return url.toString();
    }

    /** 按完整 URL 搜（委派与自抓共用同一条路径，避免两条口径） */
    public JsonNode searchByUrl(String url) {
        return withSmartRetry(url, true, () -> fetchByUrl(url));
    }

    // ==========================================
    // 批量端点（一次请求取多个模组的信息）
    // ==========================================

    /**
     * 批量预取项目元数据，把整批灌进 {@code projectInfo} 缓存（id 与 slug 双键）。
     *
     * <p>为什么是"预取"而不是改调用方签名：调用方（BFS 每一层的每个节点、preview 的每个节点）
     * 本来就是按单个 key 取用的。只要提前把整层灌进缓存，它们一行都不用改，后面的 per-item
     * {@link #getProjectInfo(String)} 自然变成缓存命中。批量的收益因此不需要渗透进业务逻辑。
     *
     * <p>未知 id 会被 Modrinth 静默丢弃（实测：传 3 个回来 2 个），所以拿不到的那几个后续仍会
     * 各发一次单条请求——这是保证正确性必要的一步，不是浪费。
     *
     * <p>失败不抛异常：预取只是加速手段，它挂了就退回原来的逐条查询，行为与改造前一致。
     */
    public void prefetchProjects(Collection<String> idsOrSlugs) {
        if (!batchPrefetch) return;
        Cache cache = cache("projectInfo");
        if (cache == null) return;
        List<String> missing = idsOrSlugs == null ? List.of() : idsOrSlugs.stream()
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .filter(k -> cache.get(k, JsonNode.class) == null)
                .toList();
        for (int i = 0; i < missing.size(); i += BATCH_LIMIT) {
            List<String> chunk = missing.subList(i, Math.min(missing.size(), i + BATCH_LIMIT));
            JsonNode arr = fetchBatchSafely(
                    "https://api.modrinth.com/v2/projects?ids=" + encode(toJsonArray(chunk)),
                    "projects?ids[" + chunk.size() + "]");
            if (arr == null || !arr.isArray()) continue;
            for (JsonNode project : arr) {
                putIfPresent(cache, project.path("id").asText(""), project);
                putIfPresent(cache, project.path("slug").asText(""), project);
            }
        }
    }

    /**
     * 批量预取精确版本（version_id 型依赖），灌进 {@code versionById} 缓存。
     * 语义与 {@link #prefetchProjects} 一致：只加速，失败静默退回逐条查询。
     */
    public void prefetchVersions(Collection<String> versionIds) {
        if (!batchPrefetch) return;
        Cache cache = cache("versionById");
        if (cache == null) return;
        List<String> missing = versionIds == null ? List.of() : versionIds.stream()
                .filter(k -> k != null && !k.isBlank())
                .distinct()
                .filter(k -> cache.get(k, JsonNode.class) == null)
                .toList();
        for (int i = 0; i < missing.size(); i += BATCH_LIMIT) {
            List<String> chunk = missing.subList(i, Math.min(missing.size(), i + BATCH_LIMIT));
            JsonNode arr = fetchBatchSafely(
                    "https://api.modrinth.com/v2/versions?ids=" + encode(toJsonArray(chunk)),
                    "versions?ids[" + chunk.size() + "]");
            if (arr == null || !arr.isArray()) continue;
            for (JsonNode version : arr) {
                putIfPresent(cache, version.path("id").asText(""), version);
            }
        }
    }

    /**
     * 批量取项目元数据，返回"请求键 → 项目 JSON"（键与传入的一致；查不到的不会出现在结果里）。
     *
     * <p>实现上就是先 {@link #prefetchProjects} 再读缓存，所以批量与单条走的是同一份缓存，
     * 不存在两套口径。查不到 ≠ 请求失败：Modrinth 对不存在的 id 是静默丢的，
     * 调用方需要自行区分"确实没有"和"没抓到"。
     */
    public Map<String, JsonNode> getProjectsBatch(Collection<String> idsOrSlugs) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (idsOrSlugs == null || idsOrSlugs.isEmpty()) return out;
        prefetchProjects(idsOrSlugs);
        Cache cache = cache("projectInfo");
        if (cache == null) return out;
        for (String key : idsOrSlugs) {
            JsonNode v = key == null ? null : cache.get(key, JsonNode.class);
            if (v != null) out.put(key, v);
        }
        return out;
    }

    private Cache cache(String name) {
        return cacheManager.getCache(name);
    }

    private static void putIfPresent(Cache cache, String key, JsonNode value) {
        if (key != null && !key.isBlank()) cache.put(key, value);
    }

    /**
     * 拼 {@code ["a","b"]} 形式的 JSON 数组。
     *
     * <p>不引 ObjectMapper：Modrinth 的 id 只含 {@code [A-Za-z0-9]}、slug 只含 {@code [a-z0-9-]}，
     * 手拼足够且少一个依赖。仍然把越界字符滤掉，避免拼出坏 JSON。
     */
    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replaceAll("[^A-Za-z0-9_.-]", "")).append('"');
        }
        return sb.append(']').toString();
    }

    /** 批量取数：失败只告警不抛，让调用方继续走逐条路径 */
    private JsonNode fetchBatchSafely(String url, String label) {
        try {
            return withSmartRetryRaw(label, () -> fetchByUrl(url));
        } catch (ModrinthApiClient.UnavailableException e) {
            System.err.println("⚠️ 批量预取失败，退回逐条查询（" + label + "）: " + e.getMessage());
            return null;
        }
    }

    // 🔥 终极防爆网关：引入动态指数退避算法 (Exponential Backoff)
    private JsonNode executeWithSmartRetry(String urlTemplate, Object... uriVariables) {
        return executeWithSmartRetry(urlTemplate, false, uriVariables);
    }

    /** 取"既不是数组、也不带 slug 字段"的普通对象（如 /version/{id}） */
    private JsonNode executeForPlainObject(String urlTemplate, Object... uriVariables) {
        return executeWithSmartRetry(urlTemplate, true, uriVariables);
    }

    /**
     * @param acceptPlainObject 是否接受"既不是数组、也不带 slug 字段"的普通对象
     *                          （/version/{id} 这类接口返回的对象没有 slug，只有 id/project_id）
     */
    private JsonNode executeWithSmartRetry(String urlTemplate, boolean acceptPlainObject, Object... uriVariables) {
        return withSmartRetry(urlTemplate, acceptPlainObject,
                () -> restClient.get().uri(urlTemplate, uriVariables).retrieve().body(JsonNode.class));
    }

    /**
     * 直接按完整 URL 取。
     *
     * <p>必须走 {@link URI} 形态：把已编码的 URL 当模板传给 RestClient 会把 {@code %} 二次转义成
     * {@code %25}，请求直接失效。旧代码在 controller 里就是这么绕开的，收口后要保留这个细节。
     */
    private JsonNode fetchByUrl(String url) {
        return restClient.get().uri(URI.create(url)).retrieve().body(JsonNode.class);
    }

    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 统一的「令牌闸 + 退避重试」外壳。
     *
     * <p>两道闸的分工：令牌闸负责<b>不冲出去</b>，这里负责万一冲出去了<b>怎么回来</b>。
     * 改造前只有后者，所以并发一上来必然吃 429。
     *
     * @param label 仅用于异常信息与日志定位
     */
    private JsonNode withSmartRetry(String label, boolean acceptPlainObject, Supplier<JsonNode> call) {
        return withSmartRetry(label, acceptPlainObject, false, call);
    }

    /** 不解包：批量端点要的是整个数组，不能被下面"单条查询取第一条"的规则吃掉 */
    private JsonNode withSmartRetryRaw(String label, Supplier<JsonNode> call) {
        return withSmartRetry(label, false, true, call);
    }

    private JsonNode withSmartRetry(String label, boolean acceptPlainObject, boolean rawReturn,
                                    Supplier<JsonNode> call) {
        int baseWaitSeconds = 2; // 基础等待时间

        for (int i = 0; i < 5; i++) {
            // 全局令牌闸：排不到队就按"无法确定"退出，绝不硬闯限额
            if (!throttle.acquire()) {
                throw new ModrinthApiClient.UnavailableException(
                        "Modrinth 出网队列拥塞，本次未发出请求: " + label);
            }
            try {
                RequestScope.countUpstream();
                JsonNode result = call.get();

                if (rawReturn) return result;
                if (result != null && result.isArray() && !result.isEmpty()) return result.get(0);
                if (result != null && result.has("slug")) return result;
                if (acceptPlainObject && result != null && result.isObject()) return result;

                return null;
            } catch (HttpClientErrorException.TooManyRequests e) {
                RequestScope.countRateLimited();
                HttpHeaders headers = e.getResponseHeaders();
                int waitSeconds = -1;

                if (headers != null && headers.get("X-Ratelimit-Reset") != null) {
                    try {
                        String resetValue = headers.getFirst("X-Ratelimit-Reset");
                        if (resetValue != null) waitSeconds = Integer.parseInt(resetValue) + 1;
                    } catch (Exception ignored) {}
                }

                // 🔥 核心修复：如果官方没给恢复时间，使用指数翻倍退避！(2秒 -> 4秒 -> 8秒 -> 16秒)
                if (waitSeconds == -1) {
                    waitSeconds = baseWaitSeconds * (1 << i); // 2 * 2^i
                }

                System.err.println("⚠️ 触发限流！当前线程智能挂起 " + waitSeconds + " 秒后进行第 " + (i+1) + " 次重试...");
                RequestScope.countRetry();
                try { Thread.sleep(waitSeconds * 1000L); } catch (InterruptedException ignored) {}

            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("404")) return null;
                RequestScope.countRetry();
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        // 重试耗尽 = 无法确定结果，绝不能当成"该模组没有兼容版本"
        throw new ModrinthApiClient.UnavailableException("Modrinth 重试耗尽，无法确定结果: " + label);
    }
}
