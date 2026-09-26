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
import yagen.waitmydawn.maa.runtime.ScopedExecutors;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/modpack")
@CrossOrigin(origins = "*")
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

    public ModpackController(DependencyEngine dependencyEngine, ModrinthApiClient apiClient, ObjectMapper objectMapper,
                             KnowledgeDb knowledgeDb, RestClient restClient, MrpackParser mrpackParser,
                             LoaderVersionService loaderVersionService, PackManifestService manifestService) {
        this.dependencyEngine = dependencyEngine;
        this.apiClient = apiClient;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.knowledgeDb = knowledgeDb;
        this.mrpackParser = mrpackParser;
        this.loaderVersionService = loaderVersionService;
        this.manifestService = manifestService;
    }

    public static class ModpackRequest {
        public String name;
        public String mcVersion;
        public String loader;
        public List<String> modSlugs;
        /** 用户在图谱里显式删除的模组（P2）：解析后一律剔除，并报告由此产生的断链 */
        public List<String> excludedSlugs;
        public boolean excludeUserFeedbackRules;
    }

    public static class BuildRequest {
        public String name;
        public String mcVersion;
        public String loader;
        /** P6-C：preview 返回的清单快照 id（推荐路径，保证导出 = 用户看到的那一份） */
        public String manifestId;
        /** 勾选的节点 id（= projectId）；为空表示全选 */
        public List<String> selectedIds;
        /** 兼容旧前端的直传路径（无 manifestId 时才使用） */
        public List<JsonNode> selectedFiles;
    }

    public static class SuggestionRequest {
        public String loader;
        public String mcVersion;
        public List<String> categories;
        public int minDownloads;
        public int maxDownloads;
        public int page; // 新增：当前页码
        public String sortMethod; // "relevance" 顺序, "random" 随机
        public List<String> currentSlugs; // 用于排重
    }

    @PostMapping("/preview")
    public ResponseEntity<JsonNode> previewPack(@RequestBody ModpackRequest request) {
        long startTime = System.currentTimeMillis();

        ObjectNode responseJson = objectMapper.createObjectNode();
        ArrayNode nodesArray = responseJson.putArray("nodes");
        ArrayNode edgesArray = responseJson.putArray("edges");

        String loadersParam = "[\"" + request.loader.toLowerCase() + "\"]";
        KnowledgeRule.SourceType exclude = request.excludeUserFeedbackRules
                ? KnowledgeRule.SourceType.USER_FEEDBACK : null;
        List<KnowledgeRule> activeRules = knowledgeDb.getActiveRules(
                request.loader.toLowerCase() + "-" + request.mcVersion, exclude);

        // 🔥 核心修复：就算是从手动添加来的孤儿模组，这里也要走一次深度依赖穿透，把它们的前置全部挖出来！
        Set<String> initialSlugs = new LinkedHashSet<>(request.modSlugs);
        Set<String> fullResolvedSlugs = dependencyEngine.resolveFullDependencies(initialSlugs, request.loader.toLowerCase(), request.mcVersion);

        // 🚀 批量预取：把整张图的项目元数据一次取回（100 个/请求），下面的逐节点循环随即变成缓存命中。
        // 依赖引擎 BFS 已经把大部分灌进缓存了，这里只补它没覆盖到的（例如用户手动加的孤立模组）。
        apiClient.prefetchProjects(new ArrayList<>(fullResolvedSlugs));

        Set<String> processedProjectIds = ConcurrentHashMap.newKeySet();

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

                        JsonNode latestVersion = apiClient.getLatestVersion(projectId, request.mcVersion, loadersParam);
                        if (latestVersion == null || !latestVersion.has("version_number") || !latestVersion.has("files"))
                            return;

                        ObjectNode node = objectMapper.createObjectNode();
                        node.put("id", projectId).put("slug", slug)
                                .put("title", projectInfo.path("title").asText())
                                .put("icon", projectInfo.path("icon_url").asText())
                                .put("description", projectInfo.path("description").asText())
                                // P6-C：versionId 必须是 Modrinth 的唯一版本 id（以前填的是 version_number 展示号，
                                // 两者语义不同：同一个展示号在不同 loader 下会有多个版本 id）
                                .put("versionId", latestVersion.path("id").asText())
                                .put("versionNumber", latestVersion.path("version_number").asText())
                                .set("fileInfo", pickPrimaryFile(latestVersion.path("files")));
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

        // 🗑️ 应用用户在图谱里的显式删除，并回填断链诊断（P2）
        ArrayNode conflictsArray = responseJson.putArray("conflicts");
        int conflictCount = applyExcludedSlugs(nodesArray, edgesArray, conflictsArray, request.excludedSlugs);
        responseJson.put("excludedCount", request.excludedSlugs == null ? 0 : request.excludedSlugs.size());
        if (conflictCount > 0) {
            System.out.println("🗑️ 删除诊断: " + conflictCount + " 项保留模组缺少必需前置（已被用户删除）");
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
        String manifestId = manifestService.register(nodeIds, nodeFiles,
                request.loader.toLowerCase(), request.mcVersion);
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
     * 只读：当前生效的加载器版本表。
     * 排查"某个 MC 版本为什么打包失败"时先看这里（表里没有 = 上游没有可用版本，或键名不一致）。
     */
    @GetMapping("/loader-versions")
    public ResponseEntity<JsonNode> loaderVersions() {
        return ResponseEntity.ok(loaderVersionService.snapshot());
    }

    @PostMapping("/build")
    public ResponseEntity<byte[]> buildPack(@RequestBody BuildRequest request) {
        try {
            // P6-C：优先按服务端快照取件；快照不存在/过期时才回退到前端直传（兼容旧前端）
            List<JsonNode> files;
            if (request.manifestId != null && !request.manifestId.isBlank()) {
                files = manifestService.filesFor(request.manifestId, request.selectedIds);
                if (files == null) {
                    System.err.println("buildPack: 清单快照已过期或不存在 -> " + request.manifestId);
                    return textBadRequest("清单快照已过期或不存在，请重新打开构筑台再打包。");
                }
                System.out.println("📦 buildPack 使用服务端快照 " + request.manifestId
                        + "，取件 " + files.size() + " 个");
            } else {
                files = request.selectedFiles == null ? List.of() : request.selectedFiles;
                System.out.println("📦 buildPack 使用前端直传文件（兼容路径），共 " + files.size() + " 个");
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
                dependencies.put(
                        loaderVersionService.dependencyKey(request.loader),
                        loaderVersionService.resolve(request.loader, request.mcVersion));
            } catch (IllegalArgumentException e) {
                System.err.println("buildPack: " + e.getMessage());
                // 以前是空体 400，前端只能显示"打包失败（HTTP 400），请重试"——用户完全不知道
                // 是"这个 MC 版本没维护加载器版本"。把原因带回响应体，前端会优先展示它。
                return textBadRequest(e.getMessage());
            }

            ArrayNode filesArray = indexJson.putArray("files");

            for (JsonNode fileInfo : files) {
                ObjectNode fileNode = objectMapper.createObjectNode();
                fileNode.put("path", "mods/" + fileInfo.path("filename").asText());
                fileNode.put("fileSize", fileInfo.path("size").asLong());
                ObjectNode hashesNode = fileNode.putObject("hashes");
                hashesNode.put("sha1", fileInfo.path("hashes").path("sha1").asText());
                hashesNode.put("sha512", fileInfo.path("hashes").path("sha512").asText());
                ArrayNode downloadsArray = fileNode.putArray("downloads");
                downloadsArray.add(fileInfo.path("url").asText());
                filesArray.add(fileNode);
            }

            String finalJsonString = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(indexJson);
            ZipEntry entry = new ZipEntry("modrinth.index.json");
            zos.putNextEntry(entry);
            zos.write(finalJsonString.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.close();

            String encodedPackName = URLEncoder.encode(packName, StandardCharsets.UTF_8.toString()).replaceAll("\\+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/x-mrpack"));
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"MAA-Modpack.mrpack\"; filename*=UTF-8''" + encodedPackName + ".mrpack");

            return ResponseEntity.ok().headers(headers).body(baos.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // 🔥 智能衍生过滤引擎 (Python 爬虫建池算法 Java 版)
    @PostMapping("/suggest")
    public ResponseEntity<List<JsonNode>> suggestMods(@RequestBody SuggestionRequest req) {
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
            // 例如：前端请求第 2 页 (下标为2，即第3页)，我们需要 3 * 15 = 45 个有效模组
            int targetTotalNeeded = isRandom ? itemsPerPage : (req.page + 1) * itemsPerPage;

            // 最多往深处挖多少页？随机盲抽挖 30 页(3000个)，顺序查最多挖 60 页(6000个)防止死循环
            int maxApiRequests = isRandom ? 30 : 60;
            int apiLimit = 100; // Modrinth 单次最大获取量

            System.out.println("====== 启动模组过滤池建造引擎 ======");
            System.out.println("目标页码: " + req.page + " | 需要有效模组数: " + targetTotalNeeded);

            for (int i = 0; i < maxApiRequests; i++) {
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
                // 🎲 随机模式：在刚刚建好的庞大合格池子里，尽情洗牌，切出 15 个！
                Collections.shuffle(validPool);
                finalResults = validPool.subList(0, Math.min(itemsPerPage, validPool.size()));
            } else {
                // 🌟 顺序模式：精准切割出前端请求的那一页
                int startIndex = req.page * itemsPerPage;
                if (startIndex < validPool.size()) {
                    finalResults = validPool.subList(startIndex, Math.min(startIndex + itemsPerPage, validPool.size()));
                }
            }

            return ResponseEntity.ok(finalResults);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }

    /** 解析上传的 .mrpack 文件, 返回其中的 slug 列表 */
    @PostMapping("/parse-mrpack")
    public ResponseEntity<Map<String, Object>> parseMrpack(@RequestParam("file") MultipartFile file) {
        try {
            Set<String> slugs = mrpackParser.extractProjectSlugs(file);
            return ResponseEntity.ok(Map.of("slugs", new ArrayList<>(slugs), "count", slugs.size()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }
}
