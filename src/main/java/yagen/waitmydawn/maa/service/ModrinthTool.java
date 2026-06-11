package yagen.waitmydawn.maa.service;

import tools.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Component
public class ModrinthTool {

    private final RestClient restClient;

    public ModrinthTool(RestClient restClient) {
        this.restClient = restClient;
    }

    @Tool("精确搜索验证模组是否真实存在。注意：不要用它来凑数量。")
    public String searchModsBatch(
            @P("要搜索的模组英文名称列表，例如：[\"irons-spells-n-spellbooks\", \"farmers-delight\"]")
            List<String> modNames) {

        if (modNames == null || modNames.isEmpty()) return "未提供模组名称。";

        int maxLimit = Math.min(modNames.size(), 30);
        List<String> safeList = modNames.subList(0, maxLimit);

        StringBuilder resultBuilder = new StringBuilder();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (String query : safeList) {
                String cleanQuery = query.trim();
                if (cleanQuery.isEmpty()) continue;
                futures.add(CompletableFuture.supplyAsync(() -> performSearch(cleanQuery), executor));
            }
            for (CompletableFuture<String> future : futures) {
                resultBuilder.append(future.join());
            }
        }
        return resultBuilder.toString();
    }

    @Cacheable(value = "modSearch", key = "#query")
    public String performSearch(String query) {
        try {
            JsonNode root = restClient.get()
                    .uri("https://api.modrinth.com/v2/search?query={q}&limit=2", query)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode hits = root.path("hits");
            if (hits.isEmpty()) return "";

            StringBuilder res = new StringBuilder();
            for (JsonNode hit : hits) {
                res.append(hit.path("slug").asText()).append(",");
            }
            return "真实存在: " + res.toString() + "\n";
        } catch (Exception e) {
            return "❌ 搜索 '" + query + "' 失败\n";
        }
    }

    // 🔥 解放的附属追踪雷达：放弃人工愚蠢过滤，将筛选大权交还给 AI！
    @Tool("【极端重要】当用户希望围绕某个核心模组丰富玩法，或者点名要求『多加一些附属』时，你必须调用此工具。它会返回该核心模组在官方真正存在的优秀附属包 slug 列表。你可以直接把返回的 slug 抄写进 <core_mods> 中！")
    public String findAddonsForCore(
            @P("核心模组的准确英文 slug，如: irons-spells-n-spellbooks, create") String coreSlug,
            @P("游戏加载器，只能是：neoforge, forge, fabric") String loader,
            @P("游戏版本，如 1.21.1") String mcVersion
    ) {

        System.out.println("🔭 AI 触发附属追踪雷达: 寻找 [" + coreSlug + "] 的附属包...");

        try {
            String facetsRaw = String.format("[[\"project_type:mod\"], [\"categories:%s\"], [\"versions:%s\"]]", loader, mcVersion);
            String encodedFacets = java.net.URLEncoder.encode(facetsRaw, java.nio.charset.StandardCharsets.UTF_8);

            // 用空格替换连字符，加上 addon 关键字
            String cleanCoreName = coreSlug.replace("-", " ");
            String queryUrl = String.format("https://api.modrinth.com/v2/search?query=%s&limit=15&facets=%s",
                    java.net.URLEncoder.encode(cleanCoreName + " addon", java.nio.charset.StandardCharsets.UTF_8),
                    encodedFacets);

            java.net.URI uri = java.net.URI.create(queryUrl);
            JsonNode searchResult = restClient.get().uri(uri).retrieve().body(JsonNode.class);

            if (searchResult == null || !searchResult.has("hits") || searchResult.path("hits").isEmpty()) {
                return "没有找到该核心的附属。";
            }

            // 直接将官方返回的热门前 10 个全部扔给大模型，让大模型读描述决定要不要加！
            StringBuilder res = new StringBuilder("找到以下真实附属包（请阅读描述，如果真的是附属，请挑选合适的加入 <core_mods> 中）：\n");
            int count = 0;
            for (JsonNode hit : searchResult.path("hits")) {
                String foundSlug = hit.path("slug").asText();
                if (!foundSlug.equals(coreSlug)) {
                    res.append("- slug: ").append(foundSlug)
                            .append(" (描述: ").append(hit.path("description").asText()).append(")\n");
                    count++;
                }
                if (count >= 10) break; // 最多喂给 AI 10 个，防 Token 溢出
            }
            return res.toString();
        } catch (Exception e) {
            return "附属追踪失败: " + e.getMessage();
        }
    }
}