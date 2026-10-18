package yagen.waitmydawn.maa.controller;

import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import yagen.waitmydawn.maa.service.*;
import yagen.waitmydawn.maa.model.KnowledgeRule;
import yagen.waitmydawn.maa.model.ResolutionResult;
import yagen.waitmydawn.maa.runtime.ScopedExecutors;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/modpack")
public class ModpackController {

    private final DependencyEngine dependencyEngine;
    private final ModrinthApiClient apiClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeDb knowledgeDb;
    private final MrpackParser mrpackParser;
    private final LoaderVersionService loaderVersionService;
    /** P6-C：preview 与 build 之间的包清单快照 */
    private final PackManifestService manifestService;
    /** 判定"这次要不要参考用户反馈规则"：口径只此一处，依赖穿透与预览共用 */
    private final UserController userController;

    /**
     * 正在跑的"侦测/建池"任务：key = 登录用户 id，未登录用前端传的 clientId。
     *
     * <p>值是取消标志：前端把按钮变成"取消"后置位，建池循环在每页之间检查它并提前收工。
     * 条目在任务结束时移除，所以同一用户随后还能再侦测。
     *
     * <p>为什么服务端也要拦：前端禁用按钮只挡得住当前标签页。连点（同一 tick 内发出多个请求）、
     * 多标签页、刷新后重发都会绕过前端守卫，而每次建池都要往上挖几十页 Modrinth。
     */
    private final Map<String, AtomicBoolean> suggestTasks = new ConcurrentHashMap<>();

    public ModpackController(DependencyEngine dependencyEngine, ModrinthApiClient apiClient, ObjectMapper objectMapper,
                             KnowledgeDb knowledgeDb, RestClient restClient, MrpackParser mrpackParser,
                             LoaderVersionService loaderVersionService, PackManifestService manifestService,
                             UserController userController) {
        this.dependencyEngine = dependencyEngine;
        this.apiClient = apiClient;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.knowledgeDb = knowledgeDb;
        this.mrpackParser = mrpackParser;
        this.loaderVersionService = loaderVersionService;
        this.manifestService = manifestService;
        this.userController = userController;
    }

    public static class ModpackRequest {
        public yagen.waitmydawn.maa.model.AssemblySnapshot assembly;
        public yagen.waitmydawn.maa.model.ImportedPack importedPack;
        public String name;
        public String mcVersion;
        public String loader;
        public List<String> modSlugs;
        /** 用户在图谱里显式删除的模组（P2）：解析后一律剔除，并报告由此产生的断链 */
        public List<String> excludedSlugs;
        /**
         * @deprecated 服务端<b>不再采信</b>这个字段。参考不参考用户反馈规则，只看登录用户
         *     设置里的开关（{@code User#enableUserFeedbackRules}）——客户端这个值是 localStorage
         *     里的旧快照，改了设置它就是过期的；更糟的是它只作用于预览画边、不作用于依赖穿透，
         *     于是"规则把节点拉进来了，却不画那条线"。字段保留仅为兼容旧前端，改由
         *     {@link UserController#excludesUserFeedbackRules(String)} 统一判定。
         */
        @Deprecated
        public boolean excludeUserFeedbackRules;
    }

    public static class BuildRequest {
        /** 明确选择导出存在静态缺口的清单；诊断始终随归档保留。 */
        public boolean allowIncomplete;
        public String name;
        public String mcVersion;
        public String loader;
        /** P6-C：preview 返回的清单快照 id（推荐路径，保证导出 = 用户看到的那一份） */
        public String manifestId;
        /** 勾选的节点 id（= projectId）；为空表示全选 */
        public List<String> selectedIds;
        /** 旧客户端字段，保留反序列化兼容性；导出不采信其中的文件对象。 */
        @Deprecated
        public List<JsonNode> selectedFiles;
    }

    public static class SuggestionRequest {
        public String loader;
        public String mcVersion;
        public List<String> categories;
        public int minDownloads;
        public int maxDownloads;
        public int page; // 当前页码
        public String sortMethod; // "relevance" 顺序, "random" 随机
        public List<String> currentSlugs; // 用于排重
        /** 未登录时的建池任务归属键（前端用每标签页一个的 sessionUuid），避免两个匿名请求互相挡 */
        public String clientId;
    }

    public ResponseEntity<JsonNode> previewPack(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestBody ModpackRequest request) {
        long startTime = System.currentTimeMillis();

        // 环境与清单先归一化。空清单是**合法输入**（"生成空图谱"就是拿一张空图进去手动拼模组），
        // 但环境缺失或不受支持必须当场报错：否则会安静地返回一张空图，用户以为创建成功了。
        if (request.loader == null || request.loader.isBlank()) {
            return jsonBadRequest("缺少加载器（支持 neoforge / forge / fabric）");
        }
        String loader = request.loader.trim().toLowerCase();
        String mcVersion = request.mcVersion == null ? "" : request.mcVersion.trim();
        if (mcVersion.isEmpty()) {
            return jsonBadRequest("缺少游戏版本");
        }
        String envError = validatePackEnvironment(loader, mcVersion);
        if (envError != null) {
            return jsonBadRequest(envError);
        }
        List<String> excludedSlugs = request.excludedSlugs == null ? List.of() : request.excludedSlugs;
        // 手写为 null 时按空清单处理：新建的空图谱、以及只带 excludedSlugs 的调用方都会走到这里
        Set<String> initialSlugs = request.modSlugs == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(request.modSlugs);
        if (initialSlugs.size() > 500 || excludedSlugs.size() > 500
                || initialSlugs.stream().anyMatch(s -> !ModrinthIdentifiers.isProjectReference(s))) {
            return jsonBadRequest("模组清单超过限额或包含非法标识");
        }
        Map<String, JsonNode> importedFiles = new HashMap<>();
        if (request.importedPack != null) {
            try { request.importedPack.requireEnvironment(loader, mcVersion); }
            catch (IllegalArgumentException invalid) { return jsonBadRequest(invalid.getMessage()); }
        }
        if (request.assembly != null && !request.assembly.matches(loader, mcVersion)) return jsonBadRequest("构建快照与环境不同，请先重新构建");

        ObjectNode responseJson = objectMapper.createObjectNode();
        ArrayNode nodesArray = responseJson.putArray("nodes");
        ArrayNode edgesArray = responseJson.putArray("edges");

        String loadersParam = "[\"" + loader + "\"]";
        // 「参考用户反馈规则」的开关由服务端按登录用户判定，不再看请求体（见 ModpackRequest 的注释）
        KnowledgeRule.SourceType exclude = userController.excludesUserFeedbackRules(token)
                ? KnowledgeRule.SourceType.USER_FEEDBACK : null;
        List<KnowledgeRule> activeRules = knowledgeDb.getActiveRules(
                loader + "-" + mcVersion, exclude).stream().filter(KnowledgeRule::appliesWithoutVersionScope).toList();

        // 🔥 核心修复：就算是从手动添加来的孤儿模组，这里也要走一次深度依赖穿透，把它们的前置全部挖出来！
        // ⚠️ 必须和上面 activeRules 用同一份规则集：引擎决定"谁进图"（节点），
        //    下面的循环决定"画哪条线"，两边分裂就会出现"有节点、没连线"
        // 用带诊断的那份解析结果（与 resolveFullDependencies 命中同一个缓存条目，不额外出网）：
        // 前端要靠 unresolved/dropped 告诉用户"这个 slug 在当前环境找不到"，而不是让它默默消失。
        ResolutionResult resolution = dependencyEngine.resolveCachedResult(
                request.importedPack != null ? request.importedPack.seeds(initialSlugs)
                        : request.assembly != null ? request.assembly.seeds(initialSlugs) : initialSlugs, loader, mcVersion, exclude);
        if (request.assembly != null) for (String slug : initialSlugs) {
            String expected = request.assembly.versions().get(slug);
            if (expected == null) continue;
            var selected = resolution.graph().selectedVersions.get(slug);
            if (selected == null || !expected.equals(selected.path("id").asText())) return jsonBadRequest("构建选版未保留：" + slug);
        }
        Set<String> fullResolvedSlugs = new LinkedHashSet<>(resolution.graph().getOrderedSlugs());
        if (request.importedPack != null) {
            for (var file : request.importedPack.files()) {
                if (!initialSlugs.contains(file.slug()) || excludedSlugs.contains(file.slug())) continue;
                JsonNode version = resolution.graph().selectedVersions.get(file.slug());
                if (version == null || !file.versionId().equals(version.path("id").asText())
                        || !file.projectId().equals(version.path("project_id").asText()))
                    return jsonBadRequest("导入精确版本未保留：" + file.slug() + "。请检查依赖冲突或重新取数，系统不会升级替代。");
                JsonNode matched = null;
                for (JsonNode metadata : version.path("files")) if (file.matches(metadata)) matched = metadata;
                if (matched == null) return jsonBadRequest("导入文件信息与精确版本不一致：" + file.slug());
                ObjectNode copy = (ObjectNode) matched.deepCopy();
                if (!file.env().isEmpty()) copy.set("env", objectMapper.valueToTree(file.env()));
                importedFiles.put(file.slug(), copy);
            }
        }

        // 🚀 批量预取：把整张图的项目元数据一次取回（100 个/请求），下面的逐节点循环随即变成缓存命中。
        // 依赖引擎 BFS 已经把大部分灌进缓存了，这里只补它没覆盖到的（例如用户手动加的孤立模组）。
        apiClient.prefetchProjects(new ArrayList<>(fullResolvedSlugs));

        Set<String> processedProjectIds = ConcurrentHashMap.newKeySet();
        Map<String, JsonNode> previewVersions = new ConcurrentHashMap<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String slug : fullResolvedSlugs) {
                if (slug == null || slug.trim().isEmpty()) continue;

                futures.add(ScopedExecutors.runAsync(() -> {
                    try {
                        // 这里的 getProjectInfo 已经被重构成了带智能重试的防爆方法
                        JsonNode projectInfo = apiClient.getProjectInfo(slug);
                        if (projectInfo == null || !projectInfo.has("id")) return;

                        String projectId = projectInfo.path("id").asText();
                        if (!processedProjectIds.add(projectId)) return;

                        JsonNode latestVersion = resolution.graph().selectedVersions.get(slug);
                        if (latestVersion == null) latestVersion = apiClient.getLatestVersion(projectId, mcVersion, loadersParam);
                        if (latestVersion == null || !latestVersion.has("version_number") || !latestVersion.has("files"))
                            return;
                        previewVersions.put(slug, latestVersion);

                        ObjectNode node = objectMapper.createObjectNode();
                        node.put("id", projectId).put("slug", slug)
                                .put("title", projectInfo.path("title").asText())
                                .put("icon", projectInfo.path("icon_url").asText())
                                .put("description", projectInfo.path("description").asText())
                                // P6-C：versionId 必须是 Modrinth 的唯一版本 id（以前填的是 version_number 展示号，
                                // 两者语义不同：同一个展示号在不同 loader 下会有多个版本 id）
                                .put("versionId", latestVersion.path("id").asText())
                                .put("versionNumber", latestVersion.path("version_number").asText())
                                .set("fileInfo", importedFiles.containsKey(slug) ? importedFiles.get(slug) : pickPrimaryFile(latestVersion.path("files")));
                        synchronized (nodesArray) {
                            nodesArray.add(node);
                        }

                        JsonNode deps = latestVersion.path("dependencies");
                        if (deps != null && deps.isArray()) {
                            for (JsonNode dep : deps) {
                                if ("required".equals(dep.path("dependency_type").asText())) {
                                    String depId = dep.path("project_id").asText();
                                    if (depId != null && !depId.isEmpty() && !"null".equals(depId)) {
                                        ObjectNode edge = objectMapper.createObjectNode();
                                        edge.put("from", depId).put("to", projectId);
                                        synchronized (edgesArray) {
                                            edgesArray.add(edge);
                                        }
                                    }
                                }
                            }
                        }

                        for (KnowledgeRule rule : activeRules) {
                            if ("DEPENDS_ON".equals(rule.relationType) && rule.modA.equals(slug)) {
                                JsonNode targetProj = apiClient.getProjectInfo(rule.modB);
                                if (targetProj != null && targetProj.has("id")) {
                                    ObjectNode edge = objectMapper.createObjectNode();
                                    edge.put("from", targetProj.path("id").asText()).put("to", projectId)
                                        .put("dashes", true).put("sourceType", rule.sourceType.name());
                                    synchronized (edgesArray) {
                                        edgesArray.add(edge);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("❌ 视图渲染最终放弃: " + slug + " | 错误: " + e.getMessage());
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        Map<String, String> nodeIdBySlug = new HashMap<>();
        for (JsonNode node : nodesArray) nodeIdBySlug.put(node.path("slug").asText(), node.path("id").asText());
        edgesArray.removeAll();
        for (String dependent : new TreeSet<>(resolution.graph().allSlugs)) {
            for (String dependency : new TreeSet<>(resolution.graph().getDependenciesOf(dependent))) {
                if (nodeIdBySlug.containsKey(dependent) && nodeIdBySlug.containsKey(dependency)) {
                    ObjectNode edge = edgesArray.addObject();
                    edge.put("from", nodeIdBySlug.get(dependency)).put("to", nodeIdBySlug.get(dependent));
                    for (KnowledgeRule rule : activeRules) {
                        if ("DEPENDS_ON".equals(rule.relationType) && rule.modA.equals(dependent) && rule.modB.equals(dependency)) {
                            edge.put("dashes", true).put("sourceType", rule.sourceType.name());
                        }
                    }
                }
            }
        }
        // 🗑️ 应用用户在图谱里的显式删除，并回填断链诊断（P2）
        ArrayNode conflictsArray = responseJson.putArray("conflicts");
        int conflictCount = applyExcludedSlugs(nodesArray, edgesArray, conflictsArray, excludedSlugs);
        responseJson.put("excludedCount", excludedSlugs.size());
        if (conflictCount > 0) {
            System.out.println("🗑️ 删除诊断: " + conflictCount + " 项保留模组缺少必需前置（已被用户删除）");
        }

        // 解析诊断：哪些请求的模组/前置没解析出来、哪些被剔除、为什么
        ArrayNode unresolvedArray = responseJson.putArray("unresolved");
        for (ResolutionResult.Unresolved u : resolution.unresolved()) {
            ObjectNode item = unresolvedArray.addObject();
            item.put("slug", u.slug());
            item.put("requiredBy", u.requiredBy() == null ? "" : u.requiredBy());
            item.put("reason", u.reason().name());
            item.put("detail", u.detail() == null ? "" : u.detail());
        }
        ArrayNode droppedArray = responseJson.putArray("dropped");
        for (ResolutionResult.Dropped d : resolution.dropped()) {
            ObjectNode item = droppedArray.addObject();
            item.put("slug", d.slug());
            item.put("reason", d.reason().name());
            item.put("detail", d.detail() == null ? "" : d.detail());
        }

        responseJson.put("timeMs", System.currentTimeMillis() - startTime);
        // 本轮真实发出的上游请求数 / 限流情况：和 <trace>.upstream 同一套埋点，
        // 让"批量端点到底省了多少请求"可以用同一次调用的前后对比来验，而不是靠感觉
        var upstream = yagen.waitmydawn.maa.runtime.RequestScope.snapshot();
        ObjectNode upstreamNode = responseJson.putObject("upstream");
        upstreamNode.put("http", upstream.http());
        upstreamNode.put("429", upstream.rateLimited());
        upstreamNode.put("retry", upstream.retries());
        upstreamNode.put("throttleMs", upstream.throttleWaitMs());
        // P6-C：登记本次解析的快照，build 只按 manifestId 取件 → 导出与预览必然是同一份
        List<String> nodeIds = new ArrayList<>();
        List<JsonNode> nodeFiles = new ArrayList<>();
        for (JsonNode n : nodesArray) {
            nodeIds.add(n.path("id").asText());
            nodeFiles.add(n.path("fileInfo"));
        }
        var auditGraph = new yagen.waitmydawn.maa.model.DependencyGraph();
        auditGraph.allSlugs.addAll(resolution.graph().allSlugs);
        auditGraph.selectedVersions.putAll(previewVersions);
        auditGraph.projectAliases.putAll(resolution.graph().projectAliases);
        resolution.graph().depsOf.forEach((dependent, dependencies) -> dependencies.forEach(dependency -> auditGraph.addEdge(dependent, dependency)));
        Map<String, String> slugById = new HashMap<>();
        nodeIdBySlug.forEach((slug, id) -> slugById.put(id, slug));
        var closure = yagen.waitmydawn.maa.model.DependencyClosure.capture(auditGraph, resolution.unresolved(), slugById);
        Set<String> previewSlugs = new HashSet<>();
        for (JsonNode node : nodesArray) previewSlugs.add(node.path("slug").asText());
        var declaredRoots = request.assembly == null ? initialSlugs : request.assembly.rootsIn(initialSlugs);
        var previewRoots = new TreeSet<String>();
        declaredRoots.forEach(root -> { String slug = slugById.getOrDefault(root, root); if (previewSlugs.contains(slug)) previewRoots.add(slug); });
        var pins = new TreeMap<String, String>();
        var origins = new TreeMap<String, Set<String>>();
        for (String slug : previewSlugs) {
            pins.put(slug, previewVersions.get(slug).path("id").asText());
            Set<String> roles = request.assembly == null ? Set.of() : request.assembly.origins().getOrDefault(slug, Set.of());
            if (roles.isEmpty()) roles = !previewRoots.contains(slug) ? Set.of("DEPENDENCY")
                    : request.assembly != null ? Set.of("EXPLICIT_ADD") : Set.of("RETAINED");
            origins.put(slug, roles);
        }
        responseJson.set("assembly", objectMapper.valueToTree(new yagen.waitmydawn.maa.model.AssemblySnapshot(loader, mcVersion,
                List.copyOf(previewRoots), pins, origins, request.importedPack == null && request.assembly != null && request.assembly.rootsKnown())));
        responseJson.set("closure", objectMapper.valueToTree(closure.inspect(previewSlugs)));
        responseJson.set("buildReport", objectMapper.valueToTree(yagen.waitmydawn.maa.service.BuildPresentation.create(
                apiClient, auditGraph, resolution, closure.inspect(previewSlugs), previewSlugs, Map.of())));
        String manifestId = manifestService.register(nodeIds, nodeFiles,
                loader, mcVersion, userController.validateToken(token), slugById, closure,
                request.importedPack == null ? Map.of() : request.importedPack.dependencies());
        responseJson.put("manifestId", manifestId);
        responseJson.put("manifestSize", manifestService.sizeOf(manifestId));
        return ResponseEntity.ok(responseJson);
    }

    /**
     * 应用用户的显式删除（P2）。
     *
     * <p>删除语义：勾选 = 本次导出是否包含（前端临时状态）；删除 = 从整合包清单移除（持久）。
     * 被删模组可能仍被依赖引擎当作"必需前置"重新解析出来，因此必须在解析结果上再剔除一次。
     *
     * <p>剔除时同步维护边：被删节点作为依赖方(to) → 直接丢弃；作为前置(from) → 丢弃并记一条断链诊断，
     * 让前端明确知道"保留的模组缺了哪个前置"，而不是静默导出一个会崩的包。
     *
     * @return 断链诊断条数
     */
    /**
     * 取要导出的文件（P6-C）：优先标了 {@code primary:true} 的主文件。
     *
     * <p>旧实现直接取 {@code files[0]}，当版本里同时有主文件与 sources/javadoc 等附加文件时，
     * 可能选中非主文件导致导出错件。没有 primary 标记时按 Modrinth 约定回退首个。
     */
    private JsonNode pickPrimaryFile(JsonNode files) {
        if (files == null || !files.isArray() || files.isEmpty()) return null;
        for (JsonNode f : files) {
            if (f.path("primary").asBoolean(false)) return f;
        }
        return files.get(0);
    }

    private int applyExcludedSlugs(ArrayNode nodesArray, ArrayNode edgesArray, ArrayNode conflictsArray,
                                   List<String> excludedSlugs) {
        if (excludedSlugs == null || excludedSlugs.isEmpty()) return 0;
        Set<String> excluded = new LinkedHashSet<>();
        for (String s : excludedSlugs) {
            if (s != null && !s.isBlank()) excluded.add(s.trim());
        }
        if (excluded.isEmpty()) return 0;

        Map<String, String> idToSlug = new HashMap<>();
        Map<String, String> slugToId = new HashMap<>();
        for (JsonNode n : nodesArray) {
            String id = n.path("id").asText();
            String slug = n.path("slug").asText();
            idToSlug.put(id, slug);
            slugToId.put(slug, id);
        }
        // 前端传的是 slug；同时兼容直接传 projectId 的情况
        Set<String> excludedIds = new HashSet<>();
        for (String slug : excluded) {
            String bySlug = slugToId.get(slug);
            excludedIds.add(bySlug != null ? bySlug : slug);
        }

        // 1) 剔除被排除的节点
        for (int i = nodesArray.size() - 1; i >= 0; i--) {
            if (excludedIds.contains(nodesArray.get(i).path("id").asText())) {
                nodesArray.remove(i);
            }
        }
        // 2) 维护边 + 记录断链（倒序遍历，删除安全）
        int conflictCount = 0;
        Set<String> seenConflicts = new HashSet<>();
        for (int i = edgesArray.size() - 1; i >= 0; i--) {
            JsonNode edge = edgesArray.get(i);
            String from = edge.path("from").asText();
            String to = edge.path("to").asText();
            if (excludedIds.contains(to)) {
                edgesArray.remove(i);
                continue;
            }
            if (excludedIds.contains(from)) {
                String requiredBy = idToSlug.getOrDefault(to, to);
                String missing = idToSlug.getOrDefault(from, from);
                if (seenConflicts.add(requiredBy + "|" + missing)) {
                    ObjectNode conflict = objectMapper.createObjectNode();
                    conflict.put("requiredBy", requiredBy);
                    conflict.put("missing", missing);
                    conflict.put("reason", "USER_EXCLUDED");
                    conflictsArray.add(conflict);
                    conflictCount++;
                }
                edgesArray.remove(i);
            }
        }
        return conflictCount;
    }

    /** 400 + 纯文本原因（前端会优先展示这段文字，而不是通用的"请重试"）。 */
    private static ResponseEntity<byte[]> textBadRequest(String message) {
        byte[] body = message == null ? new byte[0] : message.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(body);
    }

    /**
     * 预览/打包共用的环境校验。
     *
     * <p>以前传什么环境都照单全收：{@code neoforge-9.9.9} 会安静地返回一张空图，
     * 用户以为创建成功，之后每次都拿不到任何模组。现在按加载器版本表判定，
     * 非法环境直接 400 并把可选值写进消息里。
     *
     * <p>版本表尚未加载（快照为空）时只校验加载器名：启动瞬间或文件缺失时
     * 不该把所有请求都判死，宁可放行让上游自己暴露问题。
     *
     * @return null 表示环境合法；否则是给用户看的原因
     */
    private String validatePackEnvironment(String loader, String mcVersion) {
        List<String> supported = loaderVersionService.supportedLoaders();
        if (!supported.isEmpty() && !supported.contains(loader)) {
            return "不支持的加载器: " + loader + "（支持 " + String.join(" / ", supported) + "）";
        }
        List<String> versions = loaderVersionService.availableMcVersions(loader);
        if (!versions.isEmpty() && !versions.contains(mcVersion)) {
            List<String> sample = versions.size() <= 6 ? versions : versions.subList(0, 6);
            String tail = versions.size() > sample.size() ? " …" : "";
            return "没有 " + loader + " 在 MC " + mcVersion + " 下维护的版本（可选：" 
                    + String.join(", ", sample) + tail + "）";
        }
        return null;
    }

    /**
     * JSON 形态的 400：{@code /preview} 的调用方（前端）一律 {@code res.json()}，
     * 返回 text/plain 会让它解析失败，只能看到"未知错误"。
     */
    private ResponseEntity<JsonNode> jsonBadRequest(String message) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("error", message);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 只读：当前生效的加载器版本表。
     * 排查"某个 MC 版本为什么打包失败"时先看这里（表里没有 = 上游没有可用版本，或键名不一致）。
     */
    @GetMapping("/loader-versions")
    public ResponseEntity<JsonNode> loaderVersions() {
        return ResponseEntity.ok(loaderVersionService.snapshot());
    }

    @PostMapping("/build")
    public ResponseEntity<byte[]> buildPack(@RequestBody BuildRequest request,
            @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        try {
            if (request.selectedIds == null || request.selectedIds.isEmpty() || request.selectedIds.size() > 500
                    || (request.name != null && (request.name.length() > 128 || request.name.chars().anyMatch(Character::isISOControl)))) {
                return textBadRequest("请选择 1 至 500 个模组，并使用有效包名");
            }
            // 只从账号所属的服务端快照取件，过期时必须重新预览。
            List<JsonNode> files;
            yagen.waitmydawn.maa.model.DependencyClosure.Report closure;
            Map<String, String> importedDependencies;
            if (request.manifestId != null && !request.manifestId.isBlank()) {
                var selection = manifestService.selectionFor(request.manifestId, request.selectedIds, request.loader, request.mcVersion,
                        userController.validateToken(token));
                if (selection == null) {
                    System.err.println("buildPack: 清单快照已过期或不存在 -> " + request.manifestId);
                    return textBadRequest("清单快照已过期或不存在，请重新打开构筑台再打包。");
                }
                files = selection.files();
                closure = selection.closure();
                importedDependencies = selection.dependencies();
                if (closure.status() != yagen.waitmydawn.maa.model.DependencyClosure.Status.COMPLETE && !request.allowIncomplete) {
                    var diagnostic = objectMapper.createObjectNode().put("error", "当前选择的依赖清单不完整或尚未核实")
                            .put("canExportIncomplete", true);
                    diagnostic.set("closure", objectMapper.valueToTree(closure));
                    return ResponseEntity.status(409).contentType(MediaType.APPLICATION_JSON)
                            .body(objectMapper.writeValueAsBytes(diagnostic));
                }
                System.out.println("📦 buildPack 使用服务端快照 " + request.manifestId
                        + "，取件 " + files.size() + " 个");
            } else {
                return textBadRequest("缺少清单快照，请重新预览后打包");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);

            ObjectNode indexJson = objectMapper.createObjectNode();
            indexJson.put("formatVersion", 1);
            indexJson.put("game", "minecraft");
            indexJson.put("versionId", "1.0.0");

            String packName = (request.name == null || request.name.isEmpty()) ? "MAA-Modpack-" + request.mcVersion : request.name;
            indexJson.put("name", packName);

            ObjectNode dependencies = indexJson.putObject("dependencies");
            dependencies.put("minecraft", request.mcVersion);
            try {
                if (!importedDependencies.isEmpty()) {
                    importedDependencies.forEach(dependencies::put);
                } else {
                dependencies.put(
                        loaderVersionService.dependencyKey(request.loader),
                        loaderVersionService.resolve(request.loader, request.mcVersion));
                }
            } catch (IllegalArgumentException e) {
                System.err.println("buildPack: " + e.getMessage());
                // 以前是空体 400，前端只能显示"打包失败（HTTP 400），请重试"——用户完全不知道
                // 是"这个 MC 版本没维护加载器版本"。把原因带回响应体，前端会优先展示它。
                return textBadRequest(e.getMessage());
            }

            ArrayNode filesArray = indexJson.putArray("files");
            Set<String> exportedNames = new HashSet<>();

            for (JsonNode fileInfo : files) {
                String filename = fileInfo.path("filename").asText("");
                java.net.URI download = java.net.URI.create(fileInfo.path("url").asText(""));
                if (filename.isBlank() || filename.length() > 255 || filename.contains("..")
                        || filename.contains("/") || filename.contains("\\") || filename.contains(":")
                        || !"https".equalsIgnoreCase(download.getScheme()) || download.getHost() == null
                        || download.getUserInfo() != null
                        || !"cdn.modrinth.com".equalsIgnoreCase(download.getHost())
                        || !exportedNames.add(filename.toLowerCase(Locale.ROOT))
                        || !fileInfo.path("hashes").path("sha1").asText().matches("[a-fA-F0-9]{40}")
                        || !fileInfo.path("hashes").path("sha512").asText().matches("[a-fA-F0-9]{128}")
                        || fileInfo.path("size").asLong(-1) < 0) {
                    return textBadRequest("清单包含无效文件信息，请重新预览");
                }
                ObjectNode fileNode = objectMapper.createObjectNode();
                fileNode.put("path", "mods/" + fileInfo.path("filename").asText());
                fileNode.put("fileSize", fileInfo.path("size").asLong());
                ObjectNode hashesNode = fileNode.putObject("hashes");
                hashesNode.put("sha1", fileInfo.path("hashes").path("sha1").asText());
                hashesNode.put("sha512", fileInfo.path("hashes").path("sha512").asText());
                ArrayNode downloadsArray = fileNode.putArray("downloads");
                downloadsArray.add(fileInfo.path("url").asText());
                if (fileInfo.has("env")) fileNode.set("env", fileInfo.path("env").deepCopy());
                filesArray.add(fileNode);
            }

            String finalJsonString = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(indexJson);
            ZipEntry entry = new ZipEntry("modrinth.index.json");
            zos.putNextEntry(entry);
            zos.write(finalJsonString.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            ObjectNode diagnostics = objectMapper.createObjectNode().put("schemaVersion", 1)
                    .put("scope", "selected-metadata-and-dependency-rules").put("runtimeChecked", false)
                    .put("loader", request.loader).put("mcVersion", request.mcVersion);
            diagnostics.set("closure", objectMapper.valueToTree(closure));
            zos.putNextEntry(new ZipEntry("maa-diagnostics.json"));
            zos.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(diagnostics));
            zos.closeEntry();
            zos.close();

            String encodedPackName = URLEncoder.encode(packName, StandardCharsets.UTF_8.toString()).replaceAll("\\+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/x-mrpack"));
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"MAA-Modpack.mrpack\"; filename*=UTF-8''" + encodedPackName + ".mrpack");

            return ResponseEntity.ok().headers(headers).body(baos.toByteArray());
        } catch (IllegalArgumentException e) {
            return textBadRequest(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // 🔥 智能衍生过滤引擎 (Python 爬虫建池算法 Java 版)
    public ResponseEntity<Map<String, Object>> suggestMods(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestBody SuggestionRequest req) {
        String taskKey = suggestTaskKey(token, req);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        // 同一用户已有建池在跑：直接拒绝，不启动第二个（返回 409 而不是排队，前端据此把按钮切成取消态）
        if (suggestTasks.putIfAbsent(taskKey, cancelFlag) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "已有一个侦测任务在进行中", "busy", true));
        }
        try {
            List<String> facets = new ArrayList<>();
            facets.add("[\"project_type:mod\"]");
            facets.add("[\"versions:" + req.mcVersion + "\"]");
            facets.add("[\"categories:" + req.loader.toLowerCase() + "\"]");

            if (req.categories != null && !req.categories.isEmpty()) {
                String cats = String.join(",", req.categories.stream().map(c -> "\"categories:" + c + "\"").toList());
                facets.add("[" + cats + "]");
            }

            String facetsRaw = "[" + String.join(",", facets) + "]";

            boolean isRandom = "random".equals(req.sortMethod);
            // 🔥 强制设为 downloads 排序，这是能够快速跳过/过滤下载量的关键！
            String index = isRandom ? "relevance" : "downloads";

            Set<String> existing = new HashSet<>(req.currentSlugs == null ? Collections.emptyList() : req.currentSlugs);

            List<JsonNode> validPool = new ArrayList<>();
            int itemsPerPage = 15; // 按照 3x5=15 的标准

            // 如果是顺序翻页，目标就是要搜集到足够填充当前页的数量
            // 例如：前端请求第 2 页 (下标为2，即第3页)，需要 3 * 15 = 45 个有效模组
            int targetTotalNeeded = isRandom ? itemsPerPage : (req.page + 1) * itemsPerPage;

            // 最多往深处挖多少页？随机盲抽挖 30 页(3000个)，顺序查最多挖 60 页(6000个)防止死循环
            int maxApiRequests = isRandom ? 30 : 60;
            int apiLimit = 100; // Modrinth 单次最大获取量

            System.out.println("====== 启动模组过滤池建造引擎 ======");
            System.out.println("目标页码: " + req.page + " | 需要有效模组数: " + targetTotalNeeded);

            boolean cancelled = false;
            for (int i = 0; i < maxApiRequests; i++) {
                // 取消检查放在每页开头：取消后不再发起新的上游请求，直接把已经建好的池子交出去
                if (cancelFlag.get()) {
                    cancelled = true;
                    System.out.println("侦测已被用户取消，返回已建成的池子（" + validPool.size() + " 条）");
                    break;
                }
                int currentOffset = i * apiLimit;

                // 旧实现是 Thread.sleep(100) 的"礼貌休眠"——既挡不住 429，也不能跨调用点生效。
                // 现在统一交给 ModrinthApiClient 的全局令牌闸（默认 240 次/分钟）。
                JsonNode response;
                try {
                    response = apiClient.searchPage(null, apiLimit, currentOffset, index, facetsRaw);
                } catch (ModrinthApiClient.UnavailableException e) {
                    // 令牌闸排队超预算 / 重试耗尽：拿已经建好的池子收工，不把 500 抛给用户
                    System.out.println("上游不可用，停止抓取：" + e.getMessage());
                    break;
                }

                if (response == null || !response.has("hits") || response.path("hits").isEmpty()) {
                    System.out.println("到达 Modrinth 尽头，停止抓取。");
                    break;
                }

                for (JsonNode hit : response.path("hits")) {
                    String slug = hit.path("slug").asText();
                    long downloads = hit.path("downloads").asLong();

                    if (existing.contains(slug)) continue;
                    if (req.minDownloads > 0 && downloads < req.minDownloads) continue;
                    if (req.maxDownloads > 0 && downloads > req.maxDownloads) continue;

                    validPool.add(hit);
                }

                // 🔥 核心截断机制：只要有效池子里的数量满足了本次请求的目标，立刻停止 API 拉取！
                if (!isRandom && validPool.size() >= targetTotalNeeded) {
                    break;
                }
            }

            System.out.println("建池完成！有效模组总数：" + validPool.size());

            List<JsonNode> finalResults = new ArrayList<>();
            if (isRandom) {
                // 🎲 随机模式：在已建好的合格池子里洗牌，切出 15 个
                Collections.shuffle(validPool);
                finalResults = validPool.subList(0, Math.min(itemsPerPage, validPool.size()));
            } else {
                // 🌟 顺序模式：精准切割出前端请求的那一页
                int startIndex = req.page * itemsPerPage;
                if (startIndex < validPool.size()) {
                    finalResults = validPool.subList(startIndex, Math.min(startIndex + itemsPerPage, validPool.size()));
                }
            }

            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("mods", finalResults);
            ok.put("cancelled", cancelled);
            ok.put("poolSize", validPool.size());
            return ResponseEntity.ok(ok);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        } finally {
            // 无论正常结束、被取消还是抛异常都要摘牌：否则这个用户以后再点"开始侦测"会一直被 409 挡住
            suggestTasks.remove(taskKey, cancelFlag);
        }
    }

    /**
     * 取消正在进行的建池。没有在跑的任务也算成功（前端可能刚好错过了窗口）。
     */
    @PostMapping("/suggest/cancel")
    public ResponseEntity<Map<String, Object>> cancelSuggest(
            @RequestHeader(value = "X-Auth-Token", required = false) String token,
            @RequestBody(required = false) SuggestionRequest req) {
        AtomicBoolean flag = suggestTasks.get(suggestTaskKey(token, req));
        if (flag == null) return ResponseEntity.ok(Map.of("ok", true, "running", false));
        flag.set(true);
        return ResponseEntity.ok(Map.of("ok", true, "running", true));
    }

    /**
     * 建池任务的归属键：登录用账号，未登录退回前端生成的 clientId（每个标签页一个）。
     *
     * <p>未登录且没带 clientId 时落到同一个 "anon" 桶里：这时宁可让两个匿名用户相互挡一次
     * （点一下会被告知"已有任务在进行"），也不要放它们同时挖几十页上游。
     */
    String suggestTaskKey(String token, SuggestionRequest req) {
        // 空 token 直接走匿名分支：不要拿它去问会话表（也避免"0 号用户"这种哨兵值混进键里）
        if (token != null && !token.isBlank()) {
            try {
                Long uid = userController.validateToken(token);
                if (uid != null && uid > 0) return "u" + uid;
            } catch (Exception e) {
                // token 无效就当匿名处理，不因为鉴权异常把建池打成 500
            }
        }
        String clientId = req == null || req.clientId == null ? "" : req.clientId.replaceAll("[^A-Za-z0-9._-]", "");
        return clientId.isEmpty() ? "anon" : "a" + (clientId.length() > 32 ? clientId.substring(0, 32) : clientId);
    }

    /** 解析上传的 .mrpack 文件, 返回其中的 slug 列表 */
    public ResponseEntity<Map<String, Object>> parseMrpack(@RequestParam("file") MultipartFile file) {
        try {
            Set<String> slugs = mrpackParser.extractProjectSlugs(file);
            return ResponseEntity.ok(Map.of("slugs", new ArrayList<>(slugs), "count", slugs.size()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }
}
