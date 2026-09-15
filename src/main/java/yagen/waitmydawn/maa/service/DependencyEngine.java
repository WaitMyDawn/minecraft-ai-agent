package yagen.waitmydawn.maa.service;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import yagen.waitmydawn.maa.model.DependencyGraph;
import yagen.waitmydawn.maa.logging.MaaLog;
import yagen.waitmydawn.maa.model.KnowledgeRule;
import yagen.waitmydawn.maa.model.ResolutionResult;

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

    /**
     * 解析结果节点数上限（用户确认 500）。
     *
     * <p>替代旧的"BFS 最多 8 层"：合法依赖链可能很深（加附属后尤为明显），
     * 真正危险的是扇出爆炸。触顶时返回 INCOMPLETE 并上报，绝不假装解析完成。
     */
    private static final int DEFAULT_NODE_BUDGET = 500;
    private final int nodeBudget;

    private final Semaphore rateLimiter = new Semaphore(5);
    /** 整包解析结果缓存：key=loader|mc|sortedSlugs，TTL 10 分钟（避免 chat→preview 重复 BFS） */
    private static final long RESOLVE_CACHE_TTL_MS = 10 * 60 * 1000L;
    private final ConcurrentHashMap<String, ResolveCacheHit> resolveCache = new ConcurrentHashMap<>();

    private record ResolveCacheHit(long cachedAt, ResolutionResult result) {
    }

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

    /** Spring 走这个构造器（默认节点预算）；第二个构造器仅供单元测试注入小预算 */
    @org.springframework.beans.factory.annotation.Autowired
    public DependencyEngine(ModrinthApiClient apiClient, KnowledgeDb knowledgeDb) {
        this(apiClient, knowledgeDb, DEFAULT_NODE_BUDGET);
    }

    public DependencyEngine(ModrinthApiClient apiClient, KnowledgeDb knowledgeDb, int nodeBudget) {
        this.apiClient = apiClient;
        this.knowledgeDb = knowledgeDb;
        this.nodeBudget = nodeBudget;
    }

    /**
     * 深度依赖穿透 — 返回扁平 slug 集合 (保持向后兼容)
     */
    public Set<String> resolveFullDependencies(Set<String> initialSlugs, String loader, String mcVersion) {
        return new LinkedHashSet<>(resolveCachedGraph(initialSlugs, loader, mcVersion).getOrderedSlugs());
    }

    /**
     * 深度依赖穿透 — 返回带完整状态与诊断的解析结果（P6）。
     *
     * <p>与 {@link #resolveFullDependenciesWithGraph} 的区别：那个只返回图（旧行为），
     * 这个额外给出"哪些前置没解析出来、哪些模组被剔除、是否触顶"。
     */
    public ResolutionResult resolveWithReport(Set<String> initialSlugs, String loader, String mcVersion) {
        // ... 实现见下方（原 resolveFullDependenciesWithGraph 的主体）
        return doResolve(initialSlugs, loader, mcVersion);
    }

    /**
     * 深度依赖穿透 — 返回带 DAG 结构的解析结果（与 resolveFullDependencies 共享同一份缓存）。
     *
     * <p><b>只读约定</b>：返回的是缓存中的同一个实例，调用方不得修改
     * （如 allSlugs / depsOf / dependentsOf）。需要修改时请自行拷贝。
     *
     * <p>用途：删除模组时判定"谁依赖了它"（断链诊断），无需重新跑一次 BFS。
     */
    public DependencyGraph resolveCachedGraph(Set<String> initialSlugs, String loader, String mcVersion) {
        return resolveCachedResult(initialSlugs, loader, mcVersion).graph();
    }

    /** 带缓存的完整解析（含诊断），供 ChatController/preview 消费 */
    public ResolutionResult resolveCachedResult(Set<String> initialSlugs, String loader, String mcVersion) {
        String key = loader + "|" + mcVersion + "|"
                + new java.util.TreeSet<>(initialSlugs);
        ResolveCacheHit hit = resolveCache.get(key);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.cachedAt < RESOLVE_CACHE_TTL_MS) {
            return hit.result;
        }
        ResolutionResult result = doResolve(initialSlugs, loader, mcVersion);
        if (resolveCache.size() > 50) resolveCache.clear();
        resolveCache.put(key, new ResolveCacheHit(now, result));
        return result;
    }

    /**
     * 深度依赖穿透 — 返回带 DAG 图结构的 DependencyGraph
     */
    public DependencyGraph resolveFullDependenciesWithGraph(Set<String> initialSlugs, String loader, String mcVersion) {
        return doResolve(initialSlugs, loader, mcVersion).graph();
    }

    /**
     * 旧实现（保留待删）：只返回图、前置失败静默跳过、8 层截断、跨 loader 回退。
     * 新逻辑见 {@link #doResolve}；本方法已无调用方，待 P6 验证稳定后删除。
     */
    @Deprecated
    private DependencyGraph legacyResolveUnused(Set<String> initialSlugs, String loader, String mcVersion) {
        Set<String> processedProjectIds = ConcurrentHashMap.newKeySet();
        Queue<String> queue = new ConcurrentLinkedQueue<>(initialSlugs);
        DependencyGraph graph = new DependencyGraph();

        String loadersParam = "[\"" + loader.toLowerCase() + "\"]";
        String altLoader = getAltLoader(loader.toLowerCase());
        List<KnowledgeRule> activeRules = knowledgeDb.getActiveRules(loader.toLowerCase() + "-" + mcVersion);

        long startTime = System.currentTimeMillis();
        System.out.println("🕸️ 深度依赖穿透引擎启动！初始模组数: " + initialSlugs.size()
                + ", loader=" + loader + ", mc=" + mcVersion);

        Map<String, String> idToSlugCache = new ConcurrentHashMap<>();
        String ctxKey = MaaLog.userKey();
        int bfsLevel = 0;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!queue.isEmpty()) {
                bfsLevel++;
                int currentQueueSize = queue.size();
                long levelStart = System.currentTimeMillis();
                System.out.printf("  🔍 BFS 第 %d 层: %d 个模组待解析...%n", bfsLevel, currentQueueSize);

                List<CompletableFuture<Void>> futures = new ArrayList<>();
                Queue<String> nextLevelQueue = new ConcurrentLinkedQueue<>();
                java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger(0);

                for (String slugOrId : queue) {
                    if (slugOrId == null || slugOrId.trim().isEmpty()) continue;

                    futures.add(CompletableFuture.runAsync(() -> MaaLog.runWithUser(ctxKey, () -> {
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
                            failed.incrementAndGet();
                            System.err.println("依赖解析中断: " + slugOrId + " - " + e.getMessage());
                        } finally {
                            rateLimiter.release();
                            int done = completed.incrementAndGet();
                            if (done % 10 == 0 || done == currentQueueSize) {
                                System.out.printf("    进度: %d/%d (失败:%d)%n", done, currentQueueSize, failed.get());
                            }
                        }
                    }), executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                long levelMs = System.currentTimeMillis() - levelStart;
                System.out.printf("  ✅ BFS 第 %d 层完成: %d mods, 新增 %d 依赖, 耗时 %.1fs%n",
                        bfsLevel, currentQueueSize, nextLevelQueue.size(), levelMs / 1000.0);
                queue = nextLevelQueue;

                // 防止无限递归 — 最多 8 层
                if (bfsLevel >= 8 && !nextLevelQueue.isEmpty()) {
                    System.out.printf("  ⚠️ 达到最大 BFS 层数(8), 跳过剩余 %d 个依赖%n", nextLevelQueue.size());
                    break;
                }
            }
        }

        long totalMs = System.currentTimeMillis() - startTime;
        System.out.printf("🕸️ 依赖穿透完成: %d 个模组, BFS %d 层, 总耗时 %.1fs%n",
                graph.allSlugs.size(), bfsLevel, totalMs / 1000.0);

        DependencyGraph resolvedGraph = resolveGraphIds(graph, idToSlugCache);

        System.out.println("✅ 深度依赖穿透完成！最终安全模组数: " + resolvedGraph.allSlugs.size() + "\n");
        return resolvedGraph;
    }

    // ===== 信雅互联 (Sinytra Connector) 检测 =====

    // ==========================================
    // 🧩 P6：完整依赖解析（替代旧版 BFS）
    //
    // 与旧实现的三点根本区别：
    //  1. 只有"目标解析成功"才建边 —— 前置失败不会留下指向空气的边
    //  2. 前置失败会连带剔除依赖它的模组（传递闭包），并把原因上报
    //  3. 没有深度限制，改用节点预算（500）；触顶返回 INCOMPLETE 而不是假装成功
    // ==========================================

    /** version_id 型依赖在队列里的前缀（区分"按项目解析"与"按精确版本解析"） */
    private static final String VID_PREFIX = "vid:";

    private ResolutionResult doResolve(Set<String> initialSlugs, String loader, String mcVersion) {
        String loadersParam = "[\"" + loader.toLowerCase() + "\"]";
        List<KnowledgeRule> activeRules = knowledgeDb.getActiveRules(loader.toLowerCase() + "-" + mcVersion);
        String ctxKey = MaaLog.userKey();

        DependencyGraph graph = new DependencyGraph();
        Set<String> processedKeys = ConcurrentHashMap.newKeySet();
        Map<String, String> idToSlug = new ConcurrentHashMap<>();
        /** 解析失败的节点：请求键 → 原因 */
        Map<String, ResolutionResult.Reason> failed = new ConcurrentHashMap<>();
        Map<String, String> failedDetail = new ConcurrentHashMap<>();
        /** 依赖关系（dependent 真 slug → 被要求的 projectId/versionId），成败待事后判定 */
        List<String[]> requiredPairs = java.util.Collections.synchronizedList(new ArrayList<>());
        Set<String> rootSlugs = ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicBoolean budgetExhausted = new java.util.concurrent.atomic.AtomicBoolean(false);

        Queue<String> queue = new ConcurrentLinkedQueue<>(initialSlugs);
        boolean firstLevel = true;
        long startTime = System.currentTimeMillis();
        System.out.println("🕸️ 依赖穿透引擎(P6)启动：初始 " + initialSlugs.size()
                + " 个，loader=" + loader + ", mc=" + mcVersion + ", 节点预算=" + nodeBudget);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!queue.isEmpty()) {
                int levelSize = queue.size();
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                Queue<String> nextLevel = new ConcurrentLinkedQueue<>();

                for (String key : queue) {
                    if (key == null || key.trim().isEmpty()) continue;
                    final boolean isRootLevel = firstLevel;
                    futures.add(CompletableFuture.runAsync(() -> MaaLog.runWithUser(ctxKey, () -> {
                        boolean acquired = false;
                        try {
                            rateLimiter.acquire();
                            acquired = true;
                            if (!processedKeys.add(key)) return;   // 环安全：同一节点只解析一次
                            if (graph.allSlugs.size() >= nodeBudget) {
                                budgetExhausted.set(true);
                                failed.putIfAbsent(key, ResolutionResult.Reason.BUDGET_EXCEEDED);
                                return;
                            }

                            // 1) 取项目信息（version_id 型依赖先查精确版本再取项目）
                            String projectId;
                            String realSlug;
                            JsonNode latestVersion = null;
                            if (key.startsWith(VID_PREFIX)) {
                                String versionId = key.substring(VID_PREFIX.length());
                                JsonNode pinned = apiClient.getVersionById(versionId);
                                if (pinned == null) {
                                    failed.put(key, ResolutionResult.Reason.VERSION_CONFLICT);
                                    failedDetail.put(key, "精确版本 " + versionId + " 查不到");
                                    return;
                                }
                                if (!supportsEnv(pinned, mcVersion, loader)) {
                                    failed.put(key, ResolutionResult.Reason.VERSION_CONFLICT);
                                    failedDetail.put(key, "上游锁定的版本 " + versionId + " 不支持 " + mcVersion + "/" + loader);
                                    return;
                                }
                                projectId = pinned.path("project_id").asText();
                                latestVersion = pinned;
                            } else {
                                projectId = null;
                            }

                            JsonNode projectInfo = apiClient.getProjectInfo(
                                    projectId != null && !projectId.isEmpty() ? projectId : key);
                            if (projectInfo == null) {
                                failed.put(key, ResolutionResult.Reason.PROJECT_NOT_FOUND);
                                return;
                            }
                            projectId = projectInfo.path("id").asText();
                            realSlug = projectInfo.path("slug").asText();
                            if (realSlug.isBlank()) realSlug = key;

                            // 2) 取版本（严格 loader：不做跨加载器回退）
                            if (latestVersion == null) {
                                latestVersion = apiClient.getLatestVersion(projectId, mcVersion, loadersParam);
                            }
                            if (latestVersion == null) {
                                failed.put(key, ResolutionResult.Reason.NO_VERSION_FOR_ENV);
                                failedDetail.put(key, realSlug + " 在当前环境没有版本（严格策略：不回退其它加载器）");
                                return;
                            }

                            // 3) 内置黑名单 / 信雅互联包装检测
                            if (BLOCKED_DEV_MODS.contains(realSlug) || dependsOnSinytraEcosystem(latestVersion, realSlug)) {
                                failed.put(key, ResolutionResult.Reason.CONFLICT_WITH_KEPT);
                                failedDetail.put(key, realSlug + " 属于开发者工具或信雅互联包装，按策略不纳入玩家整合包");
                                return;
                            }

                            // 4) 成功：入图 + 收集依赖关系（先不建边，等 target 成败确定）
                            if (!idToSlug.containsKey(projectId)) idToSlug.put(projectId, realSlug);
                            graph.allSlugs.add(realSlug);
                            if (isRootLevel) rootSlugs.add(realSlug);

                            JsonNode deps = latestVersion.path("dependencies");
                            if (deps.isArray()) {
                                for (JsonNode dep : deps) {
                                    String type = dep.path("dependency_type").asText("");
                                    // optional 不装（只记录原因）；embedded 已自带，不重复当外置前置
                                    if (!"required".equals(type)) continue;
                                    String depProject = dep.path("project_id").asText("");
                                    String depVersion = dep.path("version_id").asText("");
                                    boolean hasProject = !depProject.isEmpty() && !"null".equals(depProject);
                                    boolean hasVersion = !depVersion.isEmpty() && !"null".equals(depVersion);
                                    if (SINYTRA_ECOSYSTEM_IDS.contains(depProject)) continue;
                                    if (hasProject) {
                                        requiredPairs.add(new String[]{realSlug, depProject});
                                        nextLevel.add(depProject);
                                    } else if (hasVersion) {
                                        requiredPairs.add(new String[]{realSlug, VID_PREFIX + depVersion});
                                        nextLevel.add(VID_PREFIX + depVersion);
                                    }
                                }
                            }
                            // 知识库补的强依赖
                            for (KnowledgeRule rule : activeRules) {
                                if ("DEPENDS_ON".equals(rule.relationType) && rule.modA.equals(realSlug)) {
                                    requiredPairs.add(new String[]{realSlug, rule.modB});
                                    nextLevel.add(rule.modB);
                                }
                            }
                        } catch (ModrinthApiClient.UnavailableException e) {
                            // 查不通 ≠ 不存在：标记但不当成失败剔除
                            failed.put(key, ResolutionResult.Reason.UPSTREAM_UNAVAILABLE);
                            failedDetail.put(key, "查询失败：" + e.getMessage());
                        } catch (Exception e) {
                            failed.put(key, ResolutionResult.Reason.UPSTREAM_UNAVAILABLE);
                            failedDetail.put(key, "解析异常：" + e.getMessage());
                        } finally {
                            if (acquired) rateLimiter.release();
                        }
                    }), executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                queue = nextLevel;
                firstLevel = false;
            }
        }

        // ===== 事后统一裁决（确定性：不依赖并发完成顺序）=====
        return finalizeResolution(graph, idToSlug, failed, failedDetail, requiredPairs,
                rootSlugs, initialSlugs, activeRules, budgetExhausted.get(), startTime);
    }

    /** 该版本是否满足指定 mc + loader（用于 version_id 精确依赖的环境校验） */
    private static boolean supportsEnv(JsonNode version, String mcVersion, String loader) {
        boolean mcOk = false;
        for (JsonNode gv : version.path("game_versions")) {
            if (mcVersion.equals(gv.asText())) { mcOk = true; break; }
        }
        boolean loaderOk = false;
        for (JsonNode l : version.path("loaders")) {
            if (loader.equalsIgnoreCase(l.asText())) { loaderOk = true; break; }
        }
        return mcOk && loaderOk;
    }

    /**
     * 收口：把"待定依赖关系"变成确定的边或诊断，并做级联剔除与互斥裁决。
     *
     * <p>顺序：先定成败 → 再级联剔除断链方 → 最后处理互斥（核心优先）。全程按 slug 排序遍历，保证确定性。
     */
    private ResolutionResult finalizeResolution(DependencyGraph graph, Map<String, String> idToSlug,
                                                Map<String, ResolutionResult.Reason> failed,
                                                Map<String, String> failedDetail,
                                                List<String[]> requiredPairs, Set<String> rootSlugs,
                                                Set<String> requestedRoots, List<KnowledgeRule> activeRules,
                                                boolean budgetExhausted, long startTime) {
        List<ResolutionResult.Unresolved> unresolved = new ArrayList<>();
        List<ResolutionResult.Dropped> dropped = new ArrayList<>();
        Set<String> kept = new LinkedHashSet<>(graph.allSlugs);
        // 先清空旧的（可能由 legacy 路径残留在图里的）边，重建为"只保留有效边"的图
        graph.depsOf.clear();
        graph.dependentsOf.clear();

        List<String[]> pairs = new ArrayList<>(requiredPairs);
        pairs.sort(Comparator.comparing((String[] p) -> p[0]).thenComparing(p -> p[1]));

        java.util.function.Function<String, String> toSlug = id -> {
            String s = idToSlug.get(id);
            return s != null ? s : id;
        };

        Set<String> missingRequired = new LinkedHashSet<>();   // 有未满足必需前置的依赖方
        // 用户点名的模组自己就没解析出来（不存在 / 无版本 / 查不通）也要如实上报，
        // 否则用户只会看到"少了一个"，却不知道为什么
        for (String req : new ArrayList<>(requestedRoots)) {
            if (kept.contains(toSlug.apply(req)) || !failed.containsKey(req)) continue;
            ResolutionResult.Reason reason = failed.get(req);
            unresolved.add(new ResolutionResult.Unresolved(toSlug.apply(req), "(用户点名)", reason,
                    failedDetail.getOrDefault(req, "")));
        }
        for (String[] pair : pairs) {
            String dependent = pair[0];
            String target = pair[1];
            String targetSlug = toSlug.apply(target);
            if (kept.contains(targetSlug)) {
                graph.addEdge(dependent, targetSlug);
                continue;
            }
            ResolutionResult.Reason reason = failed.getOrDefault(target, ResolutionResult.Reason.PROJECT_NOT_FOUND);
            unresolved.add(new ResolutionResult.Unresolved(targetSlug, dependent, reason,
                    failedDetail.getOrDefault(target, "")));
            if (reason != ResolutionResult.Reason.UPSTREAM_UNAVAILABLE) {
                // 确定拿不到 → 依赖方不能留（否则就是断链包）
                missingRequired.add(dependent);
            }
        }

        // 级联剔除：断链方若是别人的前置，它的依赖方也留不住（用户确认的规则 2）
        Deque<String> cascade = new ArrayDeque<>(missingRequired);
        Set<String> cascadeDropped = new LinkedHashSet<>();
        while (!cascade.isEmpty()) {
            String victim = cascade.poll();
            if (!kept.contains(victim)) continue;
            kept.remove(victim);
            cascadeDropped.add(victim);
            String reason = rootSlugs.contains(victim)
                    ? "它是用户点名的核心模组，需要你确认是换替代还是保留"
                    : "它依赖的前置在 Modrinth 拿不到，为避免产出断链包而剔除";
            dropped.add(new ResolutionResult.Dropped(victim,
                    rootSlugs.contains(victim) ? ResolutionResult.Reason.CONFLICT_BETWEEN_ROOTS
                            : ResolutionResult.Reason.DEPENDENT_OF_DROPPED, reason));
            for (String dep : new ArrayList<>(graph.dependentsOf.getOrDefault(victim, Set.of()))) {
                if (kept.contains(dep)) cascade.add(dep);
            }
        }
        // 核心模组缺前置时不静默剔除（用户确认的规则 1）：放回结果并保留诊断
        for (String root : new ArrayList<>(cascadeDropped)) {
            if (rootSlugs.contains(root)) {
                kept.add(root);
                dropped.removeIf(d -> d.slug().equals(root));
                System.out.println("⚠️ 核心模组 [" + root + "] 缺必需前置，按规则保留并已上报，等待用户确认");
            }
        }

        // 互斥裁决（确定性：按 slug 排序；核心优先；两个都是核心时报给用户）
        List<KnowledgeRule> conflicts = activeRules.stream()
                .filter(r -> "CONFLICTS_WITH".equals(r.relationType))
                .sorted(Comparator.comparing((KnowledgeRule r) -> r.modA).thenComparing(r -> r.modB))
                .toList();
        for (KnowledgeRule rule : conflicts) {
            String a = rule.modA;
            String b = rule.modB;
            if (!kept.contains(a) || !kept.contains(b)) continue;
            boolean aRoot = rootSlugs.contains(a);
            boolean bRoot = rootSlugs.contains(b);
            if (aRoot && bRoot) {
                unresolved.add(new ResolutionResult.Unresolved(b, a,
                        ResolutionResult.Reason.CONFLICT_BETWEEN_ROOTS,
                        "两个核心模组互斥，需要用户决定保留哪个"));
                continue;
            }
            String victim = aRoot ? b : (bRoot ? a : (a.compareTo(b) > 0 ? a : b));
            String keeper = victim.equals(a) ? b : a;
            kept.remove(victim);
            dropped.add(new ResolutionResult.Dropped(victim, ResolutionResult.Reason.CONFLICT_WITH_KEPT,
                    "与保留的 " + keeper + " 互斥，已剔除"));
        }

        // 重建最终图（只保留 kept 的节点与其有效边）
        DependencyGraph finalGraph = new DependencyGraph();
        finalGraph.allSlugs.addAll(kept);
        for (var e : graph.depsOf.entrySet()) {
            if (!kept.contains(e.getKey())) continue;
            for (String target : e.getValue()) {
                if (kept.contains(target)) finalGraph.addEdge(e.getKey(), target);
            }
        }

        boolean complete = unresolved.isEmpty() && dropped.isEmpty() && !budgetExhausted;
        ResolutionResult result = new ResolutionResult(finalGraph,
                complete ? ResolutionResult.Status.COMPLETE : ResolutionResult.Status.INCOMPLETE,
                rootSlugs, unresolved, dropped, nodeBudget, budgetExhausted);
        System.out.printf("🕸️ 依赖穿透完成(P6): %s，耗时 %.1fs%n", result.summary(),
                (System.currentTimeMillis() - startTime) / 1000.0);
        return result;
    }


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
