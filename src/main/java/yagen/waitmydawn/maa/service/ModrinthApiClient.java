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

    // 🔥 严格的防击穿缓存锁
    @Cacheable(value = "modVersions", key = "#projectId + '_' + #mcVersion + '_' + #loaders", sync = true)
    public JsonNode getLatestVersion(String projectId, String mcVersion, String loaders) {
        return executeWithSmartRetry("https://api.modrinth.com/v2/project/{id}/version?game_versions=[\"{v}\"]&loaders={l}", projectId, mcVersion, loaders);
    }

    @Cacheable(value = "projectInfo", key = "#slugOrId", sync = true)
    public JsonNode getProjectInfo(String slugOrId) {
        return executeWithSmartRetry("https://api.modrinth.com/v2/project/{id}", slugOrId);
    }

    @Cacheable(value = "modSearch", key = "#query + '_' + #limit", sync = true)
    public JsonNode searchProjects(String query, int limit) {
        return restClient.get()
                .uri("https://api.modrinth.com/v2/search?query={q}&limit={l}", query, limit)
                .retrieve().body(JsonNode.class);
    }

    // 🔥 终极防爆网关：引入动态指数退避算法 (Exponential Backoff)
    private JsonNode executeWithSmartRetry(String urlTemplate, Object... uriVariables) {
        int baseWaitSeconds = 2; // 基础等待时间

        for (int i = 0; i < 5; i++) {
            try {
                JsonNode result = restClient.get()
                        .uri(urlTemplate, uriVariables)
                        .retrieve().body(JsonNode.class);

                if (result != null && result.isArray() && !result.isEmpty()) return result.get(0);
                if (result != null && result.has("slug")) return result;

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
        throw new RuntimeException("达到最大重试次数，获取数据失败！");
    }
}