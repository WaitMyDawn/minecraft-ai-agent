package yagen.waitmydawn.maa.controller;

import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.service.*;
import yagen.waitmydawn.maa.service.*;

import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {

    private final AiAgentService aiAgentService;
    private final RestClient restClient;
    private final DependencyEngine dependencyEngine;
    private final ModrinthApiClient apiClient;
    private final ConversationRepository convRepo;
    private final ChatMessageRepository msgRepo;
    private final UserController userController;
    private final UserRepository userRepo;
    private final CategoryPreferenceRepository catRepo;
    private final ModPreferenceRepository modRepo;
    private final Map<String, Thread> activeSessions = new ConcurrentHashMap<>();

    // 🔥 用于记录候选模组的数据结构 (含多路召回重合度 HitScore)
    static class CandidateMod {
        String slug;
        String title;
        String desc;
        long downloads;
        AtomicInteger hitScore = new AtomicInteger(1);

        public CandidateMod(String slug, String title, String desc, long downloads) {
            this.slug = slug;
            this.title = title;
            this.desc = desc;
            this.downloads = downloads;
        }
    }

    public ChatController(AiAgentService aiAgentService, RestClient restClient, DependencyEngine dependencyEngine,
                          ModrinthApiClient apiClient, ConversationRepository convRepo, ChatMessageRepository msgRepo,
                          UserController userController, UserRepository userRepo,
                          CategoryPreferenceRepository catRepo, ModPreferenceRepository modRepo) {
        this.aiAgentService = aiAgentService;
        this.restClient = restClient;
        this.dependencyEngine = dependencyEngine;
        this.apiClient = apiClient;
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.userController = userController;
        this.userRepo = userRepo;
        this.catRepo = catRepo;
        this.modRepo = modRepo;
    }

    @PostMapping
    public String chat(@RequestBody Map<String, String> payload,
                       @RequestHeader(value = "X-Auth-Token", required = false) String authToken,
                       @RequestHeader(value = "X-Conversation-Id", required = false) Long convId) {
        String prompt = payload.get("prompt");
        String currentMods = payload.get("currentMods");
        String uuid = payload.getOrDefault("uuid", "default-user");

        // 从 token 获取用户 API Key (后端加密存储, 不暴露给前端)
        String effectiveApiKey = userController.getUserApiKey(authToken);
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            // 回退到系统默认 Key (环境变量 DEEPSEEK_API_KEY)
            effectiveApiKey = System.getProperty("ai.api.key", "");
        }

        // 仍未找到 → 返回友好提示
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            if (authToken != null && userController.validateToken(authToken) != null) {
                return "请先在右上角 ⚙️ 设置 中配置你的 DeepSeek API Key (格式: sk-...)，配置后即可对话。";
            }
            return "未配置 API Key。请先登录后在设置页配置个人 Key，或通过环境变量 DEEPSEEK_API_KEY 设置系统默认 Key。";
        }

        // 获取用户 ID (用于保存对话历史)
        Long userId = null;
        if (authToken != null) userId = userController.validateToken(authToken);

        StringBuilder sb = new StringBuilder();
        if (currentMods != null && !currentMods.trim().isEmpty()) {
            // 🔥 优化: 模组太多时只传数量和前 30 个, 节省 LLM token 和响应时间
            String[] mods = currentMods.split(",");
            if (mods.length > 100) {
                sb.append("【当前你已经拥有的真实模组列表 (共 ").append(mods.length).append(" 个)】：\n");
                for (int i = 0; i < Math.min(30, mods.length); i++) {
                    sb.append(mods[i].trim());
                    if (i < Math.min(30, mods.length) - 1) sb.append(", ");
                }
                sb.append("\n... 及另外 ").append(mods.length - 30).append(" 个模组\n\n");
            } else {
                sb.append("【当前你已经拥有的真实模组列表】：\n").append(currentMods).append("\n\n");
            }
        }
        // 🔥 注入用户偏好上下文 (偏好影响权重 > 0 时生效)
        if (userId != null) {
            final Long uid = userId;  // effectively final for lambda
            userRepo.findById(uid).ifPresent(user -> {
                double weight = user.getPreferenceWeight();
                if (weight > 0.0) {
                    List<CategoryPreference> cats = catRepo.findByUserIdOrderByRankAsc(uid);
                    List<ModPreference> prefs = modRepo.findByUserIdOrderByBuildCountDesc(uid);
                    List<ModPreference> userMods = prefs.stream()
                            .filter(m -> "user".equals(m.getSource())).toList();
                    List<ModPreference> topMods = prefs.stream()
                            .filter(m -> m.getBuildCount() >= 3).limit(10).toList();

                    String tone = weight >= 1.0 ? "必须严格遵循" :
                                  weight >= 0.7 ? "强烈建议遵循" : "建议参考";

                    sb.append("【你的偏好设置】（偏好影响权重: ").append(String.format("%.1f", weight))
                      .append(", ").append(tone).append("以下偏好）\n");

                    if (!cats.isEmpty()) {
                        sb.append("  - 偏好的模组类别: ");
                        cats.forEach(c -> sb.append(c.getCategory()).append("(").append(c.getRank()).append("星) "));
                        sb.append("\n");
                    }
                    if (!topMods.isEmpty()) {
                        sb.append("  - 经常使用的模组 (可参考其类别): ");
                        topMods.forEach(m -> sb.append(m.getSlug()).append("(").append(m.getBuildCount()).append("次) "));
                        sb.append("\n");
                    }
                    if (!userMods.isEmpty()) {
                        sb.append("  - 手动添加的偏好模组: ");
                        userMods.forEach(m -> sb.append(m.getSlug()).append(" "));
                        sb.append("\n");
                    }
                    sb.append("  请在生成 search_intents 时为偏好类别分配更高比例，并参考偏好模组的类别方向。\n\n");
                }
            });
        }

        sb.append("【用户指令】：\n").append(prompt).append("\n");

        try {
            activeSessions.put(uuid, Thread.currentThread());

            System.out.println("\n=======================================================");
            System.out.println("🤖 [阶段 1] 呼叫规划师 (Architect Agent) 分析意图与蓝图...");
            String aiBlueprint = aiAgentService.planBlueprint(sb.toString(), effectiveApiKey);

            if (Thread.currentThread().isInterrupted()) {
                System.out.println("🛑 发现中断标记，已放弃后续蓝图组装。");
                return "⛔ 思考已手动终止。";
            }

            return processAndAssembleBlueprint(aiBlueprint, currentMods, prompt, effectiveApiKey);
        } catch (Exception e) {
            System.err.println("AI 思考中断: " + e.getMessage());
            return "糟糕，大模型响应超时，请稍等片刻重试。";
        } finally {
            activeSessions.remove(uuid);
        }
    }

    @PostMapping("/abort")
    public String abortChat(@RequestBody Map<String, String> payload) {
        String uuid = payload.getOrDefault("uuid", "default-user");
        Thread activeThread = activeSessions.get(uuid);
        if (activeThread != null) {
            System.out.println("🛑 收到用户指令，强行终止大模型思考线程！");
            activeThread.interrupt();
            return "ok";
        }
        return "not_found";
    }

    private String processAndAssembleBlueprint(String aiBlueprint, String currentMods, String prompt, String effectiveApiKey) {
        try {
            String loader = extractTag(aiBlueprint, "loader", "neoforge");
            String mcVersion = extractTag(aiBlueprint, "mc", "1.21.1");
            int targetCount = Integer.parseInt(extractTag(aiBlueprint, "target_count", "50"));
            long maxDownloads = Long.parseLong(extractTag(aiBlueprint, "max_downloads", "2100000000"));

            String coreModsStr = extractTag(aiBlueprint, "core_mods", "");
            String expandAddonsStr = extractTag(aiBlueprint, "expand_addons", "");
            String searchIntentsStr = extractTag(aiBlueprint, "search_intents", "");

            Set<String> initialMods = new LinkedHashSet<>();
            if (currentMods != null && !currentMods.trim().isEmpty()) {
                initialMods.addAll(cleanSlugs(currentMods));
            }

            if (!coreModsStr.isEmpty()) {
                List<String> aiCoreMods = cleanSlugs(coreModsStr);
                int limit = Math.min(aiCoreMods.size(), 30);
                initialMods.addAll(aiCoreMods.subList(0, limit));
                System.out.println("🎯 规划师敲定核心/继承模组 (" + initialMods.size() + " 个): " + initialMods);
            }

            int needed = targetCount - initialMods.size();

            // ==========================================
            // 🚀 多智能体重排机制：构建供 Critic 审核的上下文
            // ==========================================
            if ((needed > 0 && !searchIntentsStr.isEmpty()) || !expandAddonsStr.isEmpty()) {
                StringBuilder criticContext = new StringBuilder();
                criticContext.append("【用户原始需求】\n").append(prompt).append("\n\n");
                criticContext.append("【已有核心模组】\n").append(initialMods).append("\n\n");
                criticContext.append("【你需要挑选的目标总数】\n由于还需要补齐配额，你大约需要从以下候选池中挑选 ").append(needed).append(" 个模组。\n\n");

                boolean requiresCritic = false;

                System.out.println("\n=======================================================");
                System.out.println("🌐 [阶段 2] Java 捕手启动多路召回，开始建立候选池...");

                // 1. 构建附属候选池
                if (!expandAddonsStr.isEmpty()) {
                    System.out.println("   🔭 捕获到附属扩张请求: [" + expandAddonsStr + "]");
                    criticContext.append("【待审核附属池】（请挑选优秀的，剔除垃圾或与用户需求无关的）\n");
                    List<String> cores = Arrays.stream(expandAddonsStr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                    for (String core : cores) {
                        List<CandidateMod> addons = buildAddonPool(core, loader, mcVersion, initialMods);
                        criticContext.append("--> ").append(core).append(" 的附属候选：\n");
                        for (CandidateMod c : addons) {
                            criticContext.append(String.format("- %s | 下载量:%dW | 描述:%s\n", c.slug, c.downloads/10000, c.desc));
                        }
                    }
                    criticContext.append("\n");
                    requiresCritic = true;
                }

                // 2. 构建意图搜索多路召回池
                if (needed > 0 && !searchIntentsStr.isEmpty()) {
                    System.out.println("   🔍 捕获到 AI 关键词矩阵: [" + searchIntentsStr + "]");
                    criticContext.append("【待审核意图候选池】（请优先选择 HitScore 分数高的模组，并筛除与核心存在冲突的）\n");

                    List<CandidateMod> fillers = buildFillerPoolByKeywords(searchIntentsStr, loader, mcVersion, initialMods, maxDownloads);

                    // 打印前 5 名高分候选模组给开发者看
                    System.out.print("   🏆 多路召回重排 Top 5 候选: ");
                    int topDisplay = Math.min(5, fillers.size());
                    for (int i = 0; i < topDisplay; i++) {
                        System.out.print(fillers.get(i).slug + "(⭐" + fillers.get(i).hitScore.get() + ") ");
                    }
                    System.out.println();

                    // 最多喂给 Critic 前 150 个分最高的模组防止 Token 超限
                    int limit = Math.min(fillers.size(), 150);
                    for (int i = 0; i < limit; i++) {
                        CandidateMod c = fillers.get(i);
                        criticContext.append(String.format("- [%s] | HitScore:%d | 下载:%dW | 描述:%s\n",
                                c.slug, c.hitScore.get(), c.downloads/10000, c.desc));
                    }
                    requiresCritic = true;
                }

                // 3. 将上下文发送给 Critic 审核员
                if (requiresCritic) {
                    System.out.println("\n=======================================================");
                    System.out.println("🕵️‍♂️ [阶段 3] 海选池建立完毕！提交给 Critic Agent 审核员...");
                    System.out.println("   ⏳ 等待审核员结合描述和 HitScore 进行最终裁决...");

                    if (Thread.currentThread().isInterrupted()) return "⛔ 思考已手动终止。";

                    String criticReply = aiAgentService.criticPools(criticContext.toString(), effectiveApiKey);

                    System.out.println("   📜 审核员原始裁决报告:");
                    System.out.println("-------------------------------------------------------");
                    System.out.println(criticReply.trim());
                    System.out.println("-------------------------------------------------------");

                    String approvedStr = extractTag(criticReply, "approved_mods", "");

                    if (!approvedStr.isEmpty()) {
                        List<String> approvedMods = cleanSlugs(approvedStr);
                        System.out.println("✅ 审核员最终通过了 " + approvedMods.size() + " 个模组: " + approvedMods);
                        initialMods.addAll(approvedMods);
                    } else {
                        System.out.println("⚠️ 警告：审核员未通过任何模组或未输出正确的 XML 标签。");
                    }
                }
            }

            // ==========================================
            // 🛡️ 图谱引擎与非官方版本抢救
            // ==========================================
            System.out.println("\n=======================================================");
            System.out.println("🛠️ [阶段 4] 启动图谱引擎与死链抢救...");
            // 🔥 优化: 如果没有搜索意图 (用户只是上传mrpack修改), 跳过 fallback 抢救
            // 因为现有模组都来自已知整合包, 逐个查 Modrinth API 太慢 (194个 × ~100ms ≈ 20秒)
            boolean needFallback = !searchIntentsStr.isEmpty() || !expandAddonsStr.isEmpty();
            Set<String> safeInitialMods = needFallback
                    ? tryFallbackForUnofficialMods(initialMods, loader, mcVersion)
                    : initialMods;
            if (!needFallback) {
                System.out.println("⚡ 跳过抢救阶段 (无搜索意图, 现有模组无需检查兼容性)");
            }

            System.out.println("🕸️ 开始将所有拼合模组 (" + safeInitialMods.size() + "个) 送入深度依赖穿透引擎...");
            Set<String> finalPerfectMods = dependencyEngine.resolveFullDependencies(safeInitialMods, loader, mcVersion);

            // 清理 Architect 输出的多余标签
            String cleanReply = aiBlueprint.replaceAll("<target_count>[\\s\\S]*?</target_count>", "")
                    .replaceAll("<max_downloads>[\\s\\S]*?</max_downloads>", "")
                    .replaceAll("<core_mods>[\\s\\S]*?</core_mods>", "")
                    .replaceAll("<expand_addons>[\\s\\S]*?</expand_addons>", "")
                    .replaceAll("<search_intents>[\\s\\S]*?</search_intents>", "");

            System.out.println("🎉 构筑完成！最终打包模组数：" + finalPerfectMods.size());
            return cleanReply + "\n<mods>" + String.join(",", finalPerfectMods) + "</mods>";

        } catch (Exception e) {
            System.err.println("组装蓝图失败: " + e.getMessage());
            return aiBlueprint;
        }
    }

    // 🚀 建池器 1：获取附属候选池
    private List<CandidateMod> buildAddonPool(String coreSlug, String loader, String mcVersion, Set<String> existing) {
        List<CandidateMod> pool = new ArrayList<>();
        try {
            String facetsRaw = String.format("[[\"project_type:mod\"], [\"categories:%s\"], [\"versions:%s\"]]", loader, mcVersion);
            String encodedFacets = URLEncoder.encode(facetsRaw, StandardCharsets.UTF_8);
            String cleanCoreName = coreSlug.replace("-", " ");
            String queryUrl = String.format("https://api.modrinth.com/v2/search?query=%s&limit=15&facets=%s",
                    URLEncoder.encode(cleanCoreName + " addon", StandardCharsets.UTF_8), encodedFacets);

            JsonNode res = restClient.get().uri(java.net.URI.create(queryUrl)).retrieve().body(JsonNode.class);
            if (res != null && res.has("hits")) {
                int count = 0;
                for (JsonNode hit : res.path("hits")) {
                    String slug = hit.path("slug").asText();
                    if (!slug.equals(coreSlug) && !existing.contains(slug)) {
                        pool.add(new CandidateMod(slug, hit.path("title").asText(), hit.path("description").asText(), hit.path("downloads").asLong()));
                        count++;
                    }
                }
                System.out.println("   📦 核心 [" + coreSlug + "] 附属召回: 抓取到 " + count + " 个候选包");
            }
        } catch (Exception ignored) {}
        return pool;
    }

    // 🚀 建池器 2：多路召回重排引擎
    private List<CandidateMod> buildFillerPoolByKeywords(String intentsStr, String loader, String mcVersion, Set<String> existing, long maxDownloads) {
        Map<String, CandidateMod> hitMap = new ConcurrentHashMap<>();
        String facetsRaw = String.format("[[\"project_type:mod\"], [\"categories:%s\"], [\"versions:%s\"]]", loader, mcVersion);
        String encodedFacets = URLEncoder.encode(facetsRaw, StandardCharsets.UTF_8);

        String[] intentGroups = intentsStr.split("\\|");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (String group : intentGroups) {
                String[] parts = group.split(":");
                if (parts.length < 3) continue;
                String category = parts[0].trim();
                String[] keywords = parts[2].split(",");

                for (String kw : keywords) {
                    final String safeKw = kw.trim();
                    futures.add(CompletableFuture.runAsync(() -> {
                        if (Thread.currentThread().isInterrupted()) return;
                        try {
                            String queryUrl = String.format("https://api.modrinth.com/v2/search?query=%s&limit=30&facets=%s",
                                    URLEncoder.encode(safeKw, StandardCharsets.UTF_8), encodedFacets);
                            JsonNode res = restClient.get().uri(java.net.URI.create(queryUrl)).retrieve().body(JsonNode.class);

                            if (res != null && res.has("hits")) {
                                int hitCount = res.path("hits").size();
                                System.out.println("   🎣 召回流 [" + category + " -> " + safeKw + "]: 抓取到 " + hitCount + " 个候选");

                                for (JsonNode hit : res.path("hits")) {
                                    String slug = hit.path("slug").asText();
                                    long downloads = hit.path("downloads").asLong();

                                    if (existing.contains(slug) || downloads > maxDownloads) continue;

                                    hitMap.compute(slug, (k, v) -> {
                                        if (v != null) {
                                            v.hitScore.incrementAndGet();
                                            return v;
                                        }
                                        return new CandidateMod(slug, hit.path("title").asText(), hit.path("description").asText().replace("\n", " "), downloads);
                                    });
                                }
                            }
                        } catch (Exception ignored) {}
                    }, executor));
                }
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        System.out.println("   🧮 联合去重并计算 HitScore 完毕！总计得到独立候选模组: " + hitMap.size() + " 个");

        // 排序规则：重合度(HitScore)最高优先，同等重合度按下载量优先
        List<CandidateMod> finalPool = new ArrayList<>(hitMap.values());
        finalPool.sort((a, b) -> {
            int scoreCompare = Integer.compare(b.hitScore.get(), a.hitScore.get());
            if (scoreCompare != 0) return scoreCompare;
            return Long.compare(b.downloads, a.downloads);
        });

        // 🔥 多样性增强: 在每 30 个一组的区间内轻度随机打乱，避免永远看到同样的排序
        for (int i = 0; i + 30 < finalPool.size(); i += 30) {
            List<CandidateMod> slice = finalPool.subList(i, Math.min(i + 30, finalPool.size()));
            java.util.Collections.shuffle(slice, new java.util.Random(System.nanoTime()));
        }

        return finalPool;
    }

    // 🚀 原有的平替抢救算法
    private Set<String> tryFallbackForUnofficialMods(Set<String> initialMods, String loader, String mcVersion) {
        Set<String> safeMods = new LinkedHashSet<>();
        String loadersParam = "[\"" + loader.toLowerCase() + "\"]";

        for (String slug : initialMods) {
            try {
                JsonNode projectInfo = apiClient.getProjectInfo(slug);
                if (projectInfo != null && projectInfo.has("id")) {
                    String projectId = projectInfo.path("id").asText();
                    JsonNode versionInfo = apiClient.getLatestVersion(projectId, mcVersion, loadersParam);
                    if (versionInfo != null) {
                        safeMods.add(slug);
                        continue;
                    }
                }

                System.out.println("🔄 [" + slug + "] 官方包不兼容或不存在，寻找平替/移植版...");
                String fallbackSlug = searchFallbackMod(slug, loader, mcVersion, safeMods);

                if (fallbackSlug != null) {
                    System.out.println("✅ 成功抢救！平替为: " + fallbackSlug);
                    safeMods.add(fallbackSlug);
                } else {
                    System.err.println("🛑 彻底放弃 [" + slug + "]：未找到兼容版本。");
                }
            } catch (Exception ignored) {}
        }
        return safeMods;
    }

    // 🚀 原有的排重 fallback 搜索
    private String searchFallbackMod(String originalSlug, String loader, String mcVersion, Set<String> existingMods) {
        try {
            String facetsRaw = String.format("[[\"project_type:mod\"], [\"categories:%s\"], [\"versions:%s\"]]", loader, mcVersion);
            String encodedFacets = URLEncoder.encode(facetsRaw, StandardCharsets.UTF_8);
            String cleanName = originalSlug.replace("-", " ");
            String queryUrl = String.format("https://api.modrinth.com/v2/search?query=%s&limit=5&facets=%s",
                    URLEncoder.encode(cleanName, StandardCharsets.UTF_8), encodedFacets);

            JsonNode result = restClient.get().uri(java.net.URI.create(queryUrl)).retrieve().body(JsonNode.class);

            if (result != null && result.has("hits")) {
                for (JsonNode hit : result.path("hits")) {
                    String foundSlug = hit.path("slug").asText();
                    if (!existingMods.contains(foundSlug)) {
                        return foundSlug;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private List<String> cleanSlugs(String rawStr) {
        return Arrays.stream(rawStr.split(","))
                .map(String::trim)
                .map(s -> s.replaceAll(".*#.*?\n?", "").replaceAll("[^a-zA-Z0-9_-]", ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String extractTag(String text, String tag, String defaultValue) {
        Pattern pattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : defaultValue;
    }
}