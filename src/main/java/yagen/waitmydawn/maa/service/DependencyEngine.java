package yagen.waitmydawn.maa.service;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import yagen.waitmydawn.maa.model.DependencyGraph;
import yagen.waitmydawn.maa.model.KnowledgeRule;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
public class DependencyEngine {

    private final ModrinthApiClient apiClient;
    private final KnowledgeDb knowledgeDb;

    private final Semaphore rateLimiter = new Semaphore(5);

    // 开发者/调试工具类模组黑名单 — 它们会严重修改游戏本体，不适合玩家整合包
    private static final Set<String> BLOCKED_DEV_MODS = Set.of(
            "preloading-tricks",
            "sinytra-connector"
    );

    // 信雅互联生态链 project ID (它们出现即为信雅互联伪装模组)
    private static final Set<String> SINYTRA_ECOSYSTEM_IDS = Set.of(
            "sinytra-connector",
            "forgified-fabric-api"
    );

    public DependencyEngine(ModrinthApiClient apiClient, KnowledgeDb knowledgeDb) {
        this.apiClient = apiClient;
        this.knowledgeDb = knowledgeDb;
    }

    /**
     * 深度依赖穿透 — 返回扁平 slug 集合 (保持向后兼容)
     */
    public Set<String> resolveFullDependencies(Set<String> initialSlugs, String loader, String mcVersion) {
        return resolveFullDependenciesWithGraph(initialSlugs, loader, mcVersion).getOrderedSlugs();
    }

    /**
     * 深度依赖穿透 — 返回带 DAG 图结构的 DependencyGraph
     */
    public DependencyGraph resolveFullDependenciesWithGraph(Set<String> initialSlugs, String loader, String mcVersion) {
        Set<String> processedProjectIds = ConcurrentHashMap.newKeySet();
        Queue<String> queue = new ConcurrentLinkedQueue<>(initialSlugs);
        DependencyGraph graph = new DependencyGraph();

        String loadersParam = "[\"" + loader.toLowerCase() + "\"]";
        String altLoader = getAltLoader(loader.toLowerCase());
        List<KnowledgeRule> activeRules = knowledgeDb.getActiveRules(loader.toLowerCase() + "-" + mcVersion);

        System.out.println("🕸️ 深度依赖穿透引擎启动！初始模组数: " + initialSlugs.size());

        Map<String, String> idToSlugCache = new ConcurrentHashMap<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!queue.isEmpty()) {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                Queue<String> nextLevelQueue = new ConcurrentLinkedQueue<>();

                for (String slugOrId : queue) {
                    if (slugOrId == null || slugOrId.trim().isEmpty()) continue;

                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            rateLimiter.acquire();

                            JsonNode projectInfo = apiClient.getProjectInfo(slugOrId);
                            if (projectInfo == null) {
                                System.err.println("⚠️ 剔除: 找不到基本信息 -> " + slugOrId);
                                return;
                            }

                            String projectId = projectInfo.path("id").asText();
                            String realSlug = projectInfo.path("slug").asText();

                            // === 黑名单检查 ===
                            if (BLOCKED_DEV_MODS.contains(realSlug)) {
                                System.err.println("🚫 剔除开发者工具模组: [" + realSlug + "] (会修改游戏本体/不适合玩家整合包)");
                                return;
                            }

                            if (!processedProjectIds.add(projectId)) return;
                            idToSlugCache.put(projectId, realSlug);

                            JsonNode latestVersion = apiClient.getLatestVersion(projectId, mcVersion, loadersParam);
                            if (latestVersion == null) {
                                // 尝试替代 loader (neoforge ↔ forge)
                                if (altLoader != null) {
                                    String altLoadersParam = "[\"" + altLoader + "\"]";
                                    latestVersion = apiClient.getLatestVersion(projectId, mcVersion, altLoadersParam);
                                }
                                if (latestVersion == null) {
                                    System.err.println("🛑 剔除: [" + realSlug + "] 缺乏完美匹配 " + mcVersion + " " + loadersParam + " 的官方包！");
                                    return;
                                }
                            }

                            // === 信雅互联检测: 依赖 sinytra-connector 或 forgified-fabric-api ===
                            if (dependsOnSinytraEcosystem(latestVersion, realSlug)) {
                                return;
                            }

                            graph.allSlugs.add(realSlug);

                            // 冲突检查
                            for (KnowledgeRule rule : activeRules) {
                                if ("CONFLICTS_WITH".equals(rule.relationType) &&
                                        ((rule.modA.equals(realSlug) && graph.allSlugs.contains(rule.modB)) ||
                                                (rule.modB.equals(realSlug) && graph.allSlugs.contains(rule.modA)))) {
                                    System.out.println("⚔️ 引擎拦截恶性冲突: 剔除 [" + realSlug + "]");
                                    graph.allSlugs.remove(realSlug);
                                    return;
                                }
                            }

                            // 处理官方声明依赖
                            JsonNode deps = latestVersion.path("dependencies");
                            if (deps.isArray()) {
                                for (JsonNode dep : deps) {
                                    if ("required".equals(dep.path("dependency_type").asText())) {
                                        String depId = dep.path("project_id").asText();
                                        if (depId != null && !depId.isEmpty() && !"null".equals(depId)
                                                && !SINYTRA_ECOSYSTEM_IDS.contains(depId)) {
                                            System.out.println("🔗 官方连线: [" + realSlug + "] 要求前置 -> [" + depId + "]");
                                            graph.addEdge(realSlug, depId);
                                            nextLevelQueue.add(depId);
                                        }
                                    }
                                }
                            }

                            // 处理知识库依赖
                            for (KnowledgeRule rule : activeRules) {
                                if ("DEPENDS_ON".equals(rule.relationType) && rule.modA.equals(realSlug)) {
                                    System.out.println("🔗 知识库连线: [" + realSlug + "] 强制要求前置 -> [" + rule.modB + "]");
                                    graph.addEdge(realSlug, rule.modB);
                                    nextLevelQueue.add(rule.modB);
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("依赖解析中断 (跳过): " + slugOrId + " - " + e.getMessage());
                        } finally {
                            rateLimiter.release();
                        }
                    }, executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                queue = nextLevelQueue;
            }
        }

        DependencyGraph resolvedGraph = resolveGraphIds(graph, idToSlugCache);

        System.out.println("✅ 深度依赖穿透完成！最终安全模组数: " + resolvedGraph.allSlugs.size() + "\n");
        return resolvedGraph;
    }

    // ===== 信雅互联 (Sinytra Connector) 检测 =====

    /**
     * 检查版本依赖中是否引用了信雅互联生态链 (sinytra-connector / forgified-fabric-api)
     * 这些项目 ID 出现在依赖中 → 该模组是 Fabric 原生模组经信雅互联包装
     */
    private boolean dependsOnSinytraEcosystem(JsonNode version, String slug) {
        JsonNode deps = version.path("dependencies");
        if (!deps.isArray()) return false;

        for (JsonNode dep : deps) {
            String depId = dep.path("project_id").asText();
            if (SINYTRA_ECOSYSTEM_IDS.contains(depId)) {
                System.err.println("🚫 剔除信雅互联相关模组: [" + slug + "] (依赖 " + depId + ")");
                return true;
            }
        }
        return false;
    }

    /**
     * 获取替代 loader (neoforge ↔ forge)
     */
    private String getAltLoader(String loader) {
        if ("neoforge".equals(loader)) return "forge";
        if ("forge".equals(loader)) return "neoforge";
        return null;
    }

    /**
     * 将图边中的 projectId 替换为 slug
     */
    private DependencyGraph resolveGraphIds(DependencyGraph raw, Map<String, String> idToSlug) {
        DependencyGraph resolved = new DependencyGraph();
        resolved.allSlugs.addAll(raw.allSlugs);

        for (var entry : raw.depsOf.entrySet()) {
            String srcSlug = idToSlug.getOrDefault(entry.getKey(), entry.getKey());
            for (String targetId : entry.getValue()) {
                String targetSlug = idToSlug.getOrDefault(targetId, targetId);
                resolved.addEdge(srcSlug, targetSlug);
            }
        }

        return resolved;
    }
}
