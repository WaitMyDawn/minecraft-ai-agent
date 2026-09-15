package yagen.waitmydawn.maa.service;

import tools.jackson.databind.JsonNode;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class ModrinthApiClient {

    private final RestClient restClient;

    public ModrinthApiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 「无法确定」异常 —— 限流 429 / 5xx / 超时重试耗尽。
     *
     * <p>语义上必须与「确定查不到」(方法返回 null) 严格区分：
     * 前者是网络故障，后者是 Modrinth 明确告诉你没有这个版本。
     * 把前者当成后者会写出错误的"该模组不支持此版本"结论。
     */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
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

    @Cacheable(value = "modSearch", key = "#query + '_' + #limit", sync = true)
    public JsonNode searchProjects(String query, int limit) {
        return restClient.get()
                .uri("https://api.modrinth.com/v2/search?query={q}&limit={l}", query, limit)
                .retrieve().body(JsonNode.class);
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
        int baseWaitSeconds = 2; // 基础等待时间

        for (int i = 0; i < 5; i++) {
            try {
                JsonNode result = restClient.get()
                        .uri(urlTemplate, uriVariables)
                        .retrieve().body(JsonNode.class);

                if (result != null && result.isArray() && !result.isEmpty()) return result.get(0);
                if (result != null && result.has("slug")) return result;
                if (acceptPlainObject && result != null && result.isObject()) return result;

                return null;
            } catch (HttpClientErrorException.TooManyRequests e) {
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
                try { Thread.sleep(waitSeconds * 1000L); } catch (InterruptedException ignored) {}

            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("404")) return null;
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        // 重试耗尽 = 无法确定结果，绝不能当成"该模组没有兼容版本"
        throw new UnavailableException("Modrinth 重试耗尽，无法确定结果: " + urlTemplate);
    }
}
