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

    public ModpackController(DependencyEngine dependencyEngine, ModrinthApiClient apiClient, ObjectMapper objectMapper, KnowledgeDb knowledgeDb, RestClient restClient, MrpackParser mrpackParser) {
        this.dependencyEngine = dependencyEngine;
        this.apiClient = apiClient;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.knowledgeDb = knowledgeDb;
        this.mrpackParser = mrpackParser;
    }

    public static class ModpackRequest {
        public String name;
        public String mcVersion;
        public String loader;
        public List<String> modSlugs;
        public boolean excludeUserFeedbackRules;
    }

    public static class BuildRequest {
        public String name;
        public String mcVersion;
        public String loader;
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

        Set<String> processedProjectIds = ConcurrentHashMap.newKeySet();

        // 🔥 将图谱渲染的并发限流也压低，确保极度稳定
        Semaphore rateLimiter = new Semaphore(10);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String slug : fullResolvedSlugs) {
                if (slug == null || slug.trim().isEmpty()) continue;

                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        rateLimiter.acquire();

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
                                .put("versionId", latestVersion.path("version_number").asText())
                                .set("fileInfo", latestVersion.path("files").get(0));
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
                    } finally {
                        rateLimiter.release();
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        responseJson.put("timeMs", System.currentTimeMillis() - startTime);
        return ResponseEntity.ok(responseJson);
    }

    @PostMapping("/build")
    public ResponseEntity<byte[]> buildPack(@RequestBody BuildRequest request) {
        try {
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
            if ("neoforge".equalsIgnoreCase(request.loader)) dependencies.put("neoforge", "21.1.231");
            else if ("fabric".equalsIgnoreCase(request.loader)) dependencies.put("fabric-loader", "0.16.9");
            else if ("forge".equalsIgnoreCase(request.loader)) dependencies.put("forge", "51.0.32");

            ArrayNode filesArray = indexJson.putArray("files");

            for (JsonNode fileInfo : request.selectedFiles) {
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
            String encodedFacets = java.net.URLEncoder.encode(facetsRaw, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");

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
                String urlString = String.format("https://api.modrinth.com/v2/search?limit=%d&offset=%d&index=%s&facets=%s",
                        apiLimit, currentOffset, index, encodedFacets);

                // 像爬虫一样礼貌休眠，防止触发 429
                if (i > 0) Thread.sleep(100);

                JsonNode response = restClient.get().uri(java.net.URI.create(urlString)).retrieve().body(JsonNode.class);

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