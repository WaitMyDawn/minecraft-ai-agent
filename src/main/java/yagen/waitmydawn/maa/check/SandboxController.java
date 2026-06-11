package yagen.waitmydawn.maa.check;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import yagen.waitmydawn.maa.service.ModrinthApiClient;
import yagen.waitmydawn.maa.service.MrpackParser;
import yagen.waitmydawn.maa.controller.UserController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/sandbox")
@CrossOrigin(origins = "*")
public class SandboxController {

    private final SandboxTesterService testerService;
    private final CrashLogParser crashLogParser;
    private final SelfHealingEngine selfHealingEngine;
    private final MrpackParser mrpackParser;
    private final ModrinthApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final UserController userController;

    @Value("${ai.api.key:}")
    private String systemDefaultApiKey;

    public SandboxController(SandboxTesterService testerService, CrashLogParser crashLogParser,
                             SelfHealingEngine selfHealingEngine, MrpackParser mrpackParser,
                             ModrinthApiClient apiClient, ObjectMapper objectMapper,
                             UserController userController) {
        this.testerService = testerService;
        this.crashLogParser = crashLogParser;
        this.selfHealingEngine = selfHealingEngine;
        this.mrpackParser = mrpackParser;
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
        this.userController = userController;
    }

    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> runTest() {
        Path mockModsFolder = Paths.get("D:\\test-mods");
        if (!Files.exists(mockModsFolder)) {
            return ResponseEntity.ok(Map.of("status", "ERROR", "message",
                    "请先创建测试模组文件夹并放几个 jar 进去：" + mockModsFolder));
        }

        String testId = UUID.randomUUID().toString().substring(0, 8);
        Path sandboxDir = Paths.get(System.getProperty("java.io.tmpdir"), "maa-sandbox-" + testId);

        try {
            List<Path> modFiles;
            try (Stream<Path> paths = Files.list(mockModsFolder)) {
                modFiles = paths.filter(p -> p.toString().endsWith(".jar")).toList();
            }
            if (modFiles.isEmpty()) {
                return ResponseEntity.ok(Map.of("status", "ERROR", "message", "测试文件夹中没有 jar 文件"));
            }

            testerService.prepareSandboxEnvironment(sandboxDir);
            Path modsDir = sandboxDir.resolve("mods");
            for (Path jar : modFiles) {
                Files.copy(jar, modsDir.resolve(jar.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }

            boolean success = testerService.runSandboxTest(sandboxDir);
            if (success) {
                return ResponseEntity.ok(Map.of("status", "PASS", "message", "无冲突启动成功！"));
            } else {
                String log = crashLogParser.extractCriticalLog(sandboxDir);
                return ResponseEntity.ok(Map.of("status", "FAIL", "crashLog", log));
            }
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "ERROR", "message", "系统错误: " + e.getMessage()));
        } finally {
            testerService.destroySandbox(sandboxDir);
        }
    }

    @PostMapping("/validate")
    public SseEmitter validateModpack(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "slugs", required = false) String slugsJson,
            @RequestParam(value = "mcVersion", defaultValue = "1.21.1") String mcVersion,
            @RequestParam(value = "loader", defaultValue = "neoforge") String loader,
            @RequestParam(value = "testMode", defaultValue = "server") String testMode,
            @RequestHeader(value = "X-Auth-Token", required = false) String authToken) {

        SseEmitter emitter = new SseEmitter(1_800_000L); // 30 min timeout (大样本需要较长时间)

        // 从后端获取用户 API Key (AES 加密存储, 前端不可见)
        String key = userController.getUserApiKey(authToken);
        String effectiveApiKey = (key != null && !key.isBlank())
                ? key : systemDefaultApiKey;

        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("message", "未配置 API Key。请在右上角设置页配置你的 DeepSeek API Key (格式: sk-...)。")));
            } catch (IOException ignored) {}
            emitter.complete();
            return emitter;
        }

        final String finalApiKey = effectiveApiKey;  // effectively final for lambda capture

        // 客户端断开标记: 设为 true 后回调不再尝试发送, 但自愈循环继续执行
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);

        // 心跳线程: 每15秒发一条注释, 防止浏览器/fetch 因长时间无数据而断开
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(() -> {
            if (clientDisconnected.get()) {
                heartbeat.shutdownNow();
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                clientDisconnected.set(true);
                heartbeat.shutdownNow();
            }
        }, 15, 15, TimeUnit.SECONDS);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(() -> {
            try {
                // --- phase: parsing ---
                emitPhaseSafe(emitter, clientDisconnected, "parsing", "正在解析整合包...");
                Set<String> coreSlugs;

                if (file != null && !file.isEmpty()) {
                    coreSlugs = mrpackParser.extractProjectSlugs(file);
                    emitPhaseSafe(emitter, clientDisconnected, "parsing",
                            "从 .mrpack 解析出 " + coreSlugs.size() + " 个模组");
                } else if (slugsJson != null && !slugsJson.isBlank()) {
                    coreSlugs = parseSlugsJson(slugsJson);
                    emitPhaseSafe(emitter, clientDisconnected, "parsing",
                            "从列表解析出 " + coreSlugs.size() + " 个模组");
                } else {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", "请上传 .mrpack 文件或提供模组 slug 列表")));
                    emitter.complete();
                    heartbeat.shutdownNow();
                    return;
                }

                if (coreSlugs.isEmpty()) {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", "未能解析出任何模组，请检查输入")));
                    emitter.complete();
                    heartbeat.shutdownNow();
                    return;
                }

                // --- 检查沙盒槽位 ---
                if (!testerService.tryAcquireSlot()) {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", "沙盒校验队列已满 (最大并发 " + testerService.maxConcurrent
                                    + "，当前可用 " + testerService.availableSlots() + ")，请稍后重试")));
                    emitter.complete();
                    heartbeat.shutdownNow();
                    return;
                }

                // --- run healing cycle ---
                // 即使客户端断开, 自愈循环也会完整执行, 只是不推送SSE事件
                Set<String> finalMods = selfHealingEngine.runAutoHealingCycle(
                        coreSlugs, mcVersion, loader, testMode,
                        (phase, data) -> {
                            if (clientDisconnected.get()) return; // 客户端已离开, 静默跳过
                            try {
                                switch (phase) {
                                    case "resolving" -> emitPhase(emitter, "resolving",
                                            "正在构建依赖图谱... (" + data[0] + " 个核心)");
                                    case "resolved" -> emitPhase(emitter, "resolved",
                                            "依赖解析完成，共 " + data[0] + " 个模组");
                                    case "downloading" -> {
                                            String slug = data.length > 2 ? " " + data[2].toString() : "";
                                            emitPhase(emitter, "downloading",
                                                    data[0] + "/" + data[1] + slug);
                                        }
                                    case "downloading-retry" -> emitPhase(emitter, "downloading",
                                            "下载重试中 (第" + data[0] + "次), 剩余 " + data[1] + " 个模组");
                                    case "summary" -> emitEvent(emitter, "summary",
                                            Map.of("text", data[0] != null ? data[0].toString() : ""));
                                    case "report" -> emitEvent(emitter, "report",
                                            Map.of("text", data[0] != null ? data[0].toString() : ""));
                                    case "filtered" -> emitEvent(emitter, "filtered",
                                                Map.of("skippedSlugs", data[0] != null ? data[0].toString() : ""));
                                    case "testing" -> emitPhase(emitter, "testing",
                                            "正在启动 Minecraft 服务端进行沙盒测试 (单操作轮最长5分钟)...");
                                    case "test-output" -> emitEvent(emitter, "test-output",
                                            Map.of("line", data[0] != null ? data[0].toString() : ""));
                                    case "crash" -> emitEvent(emitter, "crash",
                                            Map.of("message", "服务端崩溃",
                                                    "logSnippet", data[0] != null ? data[0].toString() : ""));
                                    case "healing" -> emitEvent(emitter, "healing",
                                            Map.of("cycle", data[0],
                                                    "action", data[1],
                                                    "target", data[2],
                                                    "reason", data[3]));
                                    case "complete" -> emitEvent(emitter, "result",
                                            Map.of("success", true,
                                                    "finalMods", data[0],
                                                    "skippedClient", data[1],
                                                    "cycles", data[2],
                                                    "message", data.length > 3
                                                            ? data[3].toString()
                                                            : "整合包校验通过！无冲突启动成功！",
                                                    "mcVersion", mcVersion,
                                                    "loader", loader));
                                    case "failed" -> emitEvent(emitter, "result",
                                            Map.of("success", false,
                                                    "finalMods", data[0],
                                                    "cycles", data[1],
                                                    "message", data[2]));
                                    case "aborted" -> emitEvent(emitter, "result",
                                            Map.of("success", false,
                                                    "finalMods", data[0],
                                                    "cycles", 0,
                                                    "aborted", true,
                                                    "message", "底座问题: " + data[1]));
                                    case "stuck" -> emitEvent(emitter, "result",
                                            Map.of("success", false,
                                                    "finalMods", data[0],
                                                    "cycles", data[1],
                                                    "stuck", true,
                                                    "message", data[2]));
                                }
                            } catch (Exception e) {
                                clientDisconnected.set(true);
                            }
                        }, finalApiKey);

                // 即使客户端断了, 也尝试发送最终结果
                if (!clientDisconnected.get()) {
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {}
                } else {
                    System.out.println("ℹ️ 客户端在过程中断开, 但自愈校验已完成。"
                            + "最终模组数: " + finalMods.size());
                }

            } catch (Exception e) {
                System.err.println("自愈引擎内部异常: " + e.getMessage());
                e.printStackTrace();
                if (!clientDisconnected.get()) {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(Map.of("message", "系统异常: " + e.getMessage())));
                    } catch (IOException ignored) {}
                }
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {}
            } finally {
                testerService.releaseSlot();
                heartbeat.shutdownNow();
            }
        });

        emitter.onCompletion(() -> {
            heartbeat.shutdownNow();
            clientDisconnected.set(true);   // 标记断开, 不中断后台任务
        });
        emitter.onTimeout(() -> {
            heartbeat.shutdownNow();
            clientDisconnected.set(true);   // 超时只标记断开, 让后台任务自然完成
        });
        emitter.onError(e -> {
            heartbeat.shutdownNow();
            clientDisconnected.set(true);
        });

        return emitter;
    }

    /**
     * 根据 slug 列表直接构建 mrpack, 用于沙盒校验后的下载
     */
    @PostMapping("/build-pack")
    public ResponseEntity<byte[]> buildValidatedPack(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> slugs = (List<String>) body.get("slugs");
            String mcVersion = body.getOrDefault("mcVersion", "1.21.1").toString();
            String loader = body.getOrDefault("loader", "neoforge").toString();
            String packName = body.getOrDefault("packName", "MAA-Validated-Pack").toString();

            if (slugs == null || slugs.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            String loadersParam = "[\"" + loader.toLowerCase() + "\"]";

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ZipOutputStream zos = new ZipOutputStream(baos);

            ObjectNode indexJson = objectMapper.createObjectNode();
            indexJson.put("formatVersion", 1);
            indexJson.put("game", "minecraft");
            indexJson.put("versionId", "1.0.0");
            indexJson.put("name", packName);

            ObjectNode deps = indexJson.putObject("dependencies");
            deps.put("minecraft", mcVersion);
            if ("neoforge".equalsIgnoreCase(loader)) deps.put("neoforge", "21.1.231");
            else if ("fabric".equalsIgnoreCase(loader)) deps.put("fabric-loader", "0.16.9");
            else if ("forge".equalsIgnoreCase(loader)) deps.put("forge", "51.0.32");

            ArrayNode filesArray = indexJson.putArray("files");

            for (String slug : slugs) {
                try {
                    JsonNode proj = apiClient.getProjectInfo(slug);
                    if (proj == null) {
                        System.err.println("⚠️ build-pack: 跳过 " + slug + " (无项目信息)");
                        continue;
                    }
                    String projectId = proj.path("id").asText();
                    JsonNode ver = apiClient.getLatestVersion(projectId, mcVersion, loadersParam);
                    if (ver == null || !ver.has("files")) {
                        System.err.println("⚠️ build-pack: 跳过 " + slug + " (无兼容版本)");
                        continue;
                    }
                    JsonNode fileNode = ver.path("files").get(0);
                    String filename = fileNode.path("filename").asText();
                    String url = fileNode.path("url").asText();
                    long size = fileNode.path("size").asLong();
                    String sha1 = fileNode.path("hashes").path("sha1").asText();
                    String sha512 = fileNode.path("hashes").path("sha512").asText();

                    ObjectNode f = objectMapper.createObjectNode();
                    f.put("path", "mods/" + filename);
                    f.put("fileSize", size);
                    ObjectNode hashes = f.putObject("hashes");
                    hashes.put("sha1", sha1);
                    hashes.put("sha512", sha512);
                    ArrayNode downloads = f.putArray("downloads");
                    downloads.add(url);
                    filesArray.add(f);
                } catch (Exception e) {
                    System.err.println("⚠️ build-pack: 跳过 " + slug + " (" + e.getMessage() + ")");
                }
            }

            String jsonStr = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(indexJson);
            ZipEntry entry = new ZipEntry("modrinth.index.json");
            zos.putNextEntry(entry);
            zos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.close();

            String encodedName = URLEncoder.encode(packName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/x-mrpack"));
            headers.add(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"MAA-Validated.mrpack\"; filename*=UTF-8''" + encodedName + ".mrpack");

            return ResponseEntity.ok().headers(headers).body(baos.toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }

    private Set<String> parseSlugsJson(String json) {
        Set<String> slugs = new LinkedHashSet<>();
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        for (String part : trimmed.split(",")) {
            String slug = part.trim().replaceAll("\"", "").replaceAll("'", "");
            if (!slug.isBlank()) {
                slugs.add(slug);
            }
        }
        return slugs;
    }

    // === 安全发送方法 (不抛异常, 静默标记断开) ===

    private void emitPhaseSafe(SseEmitter emitter, AtomicBoolean disconnected,
                                String stage, String message) {
        if (disconnected.get()) return;
        try {
            emitPhase(emitter, stage, message);
        } catch (IOException e) {
            disconnected.set(true);
        }
    }

    private void emitPhase(SseEmitter emitter, String stage, String message) throws IOException {
        emitter.send(SseEmitter.event().name("phase")
                .data(Map.of("stage", stage, "message", message)));
    }

    private void emitEvent(SseEmitter emitter, String eventName, Map<String, Object> data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }
}
