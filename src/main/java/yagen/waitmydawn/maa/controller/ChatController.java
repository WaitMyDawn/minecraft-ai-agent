package yagen.waitmydawn.maa.controller;

import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.cache.ModrinthCacheService;
import yagen.waitmydawn.maa.cache.ModrinthCacheService.ModEntry;
import yagen.waitmydawn.maa.logging.MaaLog;
import yagen.waitmydawn.maa.service.*;

import java.net.URLEncoder;
import java.time.Duration;
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
    private final ModrinthCacheService modrinthCacheService;
    private final RestClient restClient;
    private final DependencyEngine dependencyEngine;
    private final ModrinthApiClient apiClient;
    private final ConversationRepository convRepo;
    private final ChatMessageRepository msgRepo;
    private final UserController userController;
    private final UserRepository userRepo;
    private final CategoryPreferenceRepository catRepo;
    private final ModPreferenceRepository modRepo;
    // 使用 volatile 标记替代 Thread.interrupt() 实现即时终止
    private final Map<String, Boolean> abortedSessions = new ConcurrentHashMap<>();

    /** 版本负面记录: key = loader|mc, 值 = 已确认不兼容的 slug 集合（TTL 60 分钟） */
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(60);
    private final Map<String, NegativeBucket> negativeVersionCache = new ConcurrentHashMap<>();

    private static final class NegativeBucket {
        final long since = System.currentTimeMillis();
        final Set<String> slugs = ConcurrentHashMap.newKeySet();
    }

    /** 平替候选的社区化词缀（lib/addon 不参与平替，留给附属匹配） */
    private static final Set<String> REPLACE_AFFIXES = Set.of(
            "port", "unofficial", "continued", "ce", "community", "remake", "reborn", "rebirth");

    /** 分词时剔除的停用词（保证"强词"有意义，避免 config/api/port 之类的弱词命中） */
    private static final Set<String> REPLACE_STOPWORDS = Set.of(
            "the", "of", "and", "for", "in", "on", "to", "a", "an", "is", "x",
            "mod", "mods", "minecraft", "mc", "forge", "neoforge", "fabric", "quilt",
            "reloaded", "edition", "api", "lib", "library", "addon", "config", "core",
            "compat", "compatibility", "integration", "loader", "support", "modded");

    /** 平替候选下载量下限，过低视为无人维护/无意义，回落到正常匹配或放弃 */
    private static final long MIN_REPLACEMENT_DOWNLOADS = 1000L;

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

    public ChatController(AiAgentService aiAgentService, ModrinthCacheService modrinthCacheService,
                          RestClient restClient, DependencyEngine dependencyEngine,
                          ModrinthApiClient apiClient, ConversationRepository convRepo, ChatMessageRepository msgRepo,
                          UserController userController, UserRepository userRepo,
                          CategoryPreferenceRepository catRepo, ModPreferenceRepository modRepo) {
        this.aiAgentService = aiAgentService;
        this.modrinthCacheService = modrinthCacheService;
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
        String userKey = userId != null ? "user-" + userId : "anon-" + sanitizeUuid(uuid);
        MaaLog.setUserKey(userKey);
        MaaLog.app("请求开始 user=" + userKey
                + " promptLen=" + (prompt == null ? 0 : prompt.length())
                + " currentModsCount=" + (currentMods == null ? 0 : currentMods.split(",").length));

        StringBuilder sb = new StringBuilder();
        if (currentMods != null && !currentMods.trim().isEmpty()) {
            // 🔥 M1a: 缓存可用时用类别画像(current_intents)替代全量 slug 列表；
            // 完整清单由 Java 全量保留(initialMods 并入)，LLM 只需做增量判断。
            String profile = modrinthCacheService.summarizePack(currentMods);
            if (profile != null) {
                sb.append("【当前包画像】（系统会保留全部模组，你无需复述已有清单，只做增量判断）：\n")
                        .append(profile).append("\n\n");
            } else {
                // 🔥 兜底(缓存不可用): 模组太多时只传数量和前 30 个, 节省 LLM token
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
                        cats.forEach(c -> sb.append(c.getCategory()).append("(偏好第").append(c.getRank()).append(") "));
                        sb.append("（数字越小越喜欢，1=最喜欢的类别）\n");
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
            System.out.println("\n=======================================================");
            System.out.println("🤖 [阶段 1] 呼叫规划师 (Architect Agent) 分析意图与蓝图...");
            // M2: 每回合新建包状态与 Tool 集，Architect 可通过 Tool 下发删除/配额等变更
            PackSessionState state = new PackSessionState();
            ArchitectPackTools packTools = new ArchitectPackTools(state, modrinthCacheService, apiClient);
            String aiBlueprint = aiAgentService.planBlueprint(sb.toString(), effectiveApiKey, packTools);

            if (isAborted(uuid)) {
                System.out.println("🛑 用户终止 — 放弃后续蓝图组装。");
                return "⛔ 思考已手动终止。";
            }

            return processAndAssembleBlueprint(aiBlueprint, currentMods, prompt, effectiveApiKey, uuid, state);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            System.err.println("AI 调用异常: " + msg);
            MaaLog.error("AI 调用异常: " + msg, e);

            // 区分不同错误类型给出友好提示
            if (msg.contains("service_unavailable") || msg.contains("too busy")) {
                return "DeepSeek 官方服务繁忙，请稍等片刻后重试。\n\n建议：稍等 1-2 分钟后重试，或者更换 API Key 对应的账户。";
            }
            if (msg.contains("Interrupted") || isAborted(uuid)) {
                return "⛔ 思考已手动终止。";
            }
            if (msg.contains("timeout") || msg.contains("Timeout")) {
                return "大模型响应超时，请稍等片刻重试。";
            }
            if (msg.contains("401") || msg.contains("403") || msg.contains("unauthorized")) {
                return "API Key 无效或已过期，请在设置中更新你的 DeepSeek API Key。";
            }
            if (msg.contains("insufficient") || msg.contains("quota") || msg.contains("balance")) {
                return "API 额度不足，请检查 DeepSeek 账户余额。";
            }
            return "AI 调用异常: " + msg.substring(0, Math.min(200, msg.length())) + "\n请稍后重试。";
        } finally {
            MaaLog.clearUserKey();
            abortedSessions.remove(uuid);
        }
    }

    private static String sanitizeUuid(String uuid) {
        if (uuid == null) return "unknown";
        String s = uuid.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    @PostMapping("/abort")
    public String abortChat(@RequestBody Map<String, String> payload) {
        String uuid = payload.getOrDefault("uuid", "default-user");
        abortedSessions.put(uuid, true);
        System.out.println("🛑 收到用户终止指令 (volatile flag) — uuid=" + uuid);
        return "ok";
    }

    /** 检查是否已终止 */
    private boolean isAborted(String uuid) {
        return Boolean.TRUE.equals(abortedSessions.getOrDefault(uuid, false));
    }

    // ==========================================
    // 🔎 M1b: 版本兼容性校验（本地 versions 命中 -> live -> 负面记录闭环）
    // ==========================================

    private static String envKey(String loader, String mcVersion) {
        return loader.toLowerCase() + "|" + mcVersion;
    }

    private boolean isVersionNegative(String slug, String loader, String mcVersion) {
        NegativeBucket bucket = negativeVersionCache.get(envKey(loader, mcVersion));
        if (bucket == null) return false;
        if (System.currentTimeMillis() - bucket.since > NEGATIVE_TTL.toMillis()) {
            negativeVersionCache.remove(envKey(loader, mcVersion), bucket);
            return false;
        }
        return bucket.slugs.contains(slug);
    }

    private void rememberVersionNegative(String slug, String loader, String mcVersion) {
        negativeVersionCache
                .computeIfAbsent(envKey(loader, mcVersion), k -> new NegativeBucket())
                .slugs.add(slug);
    }

    /** 校验单个候选: 先看负面/本地 versions，再走 live；通过则回填 versions */
    private boolean isCandidateCompatible(String slug, String loader, String mcVersion) {
        if (isVersionNegative(slug, loader, mcVersion)) return false;
        if (modrinthCacheService.isAvailable()
                && modrinthCacheService.hasKnownVersion(slug, loader, mcVersion)) {
            return true;
        }
        try {
            JsonNode v = apiClient.getLatestCompatibleVersionBySlug(slug, mcVersion, loader.toLowerCase());
            if (v != null) {
                if (modrinthCacheService.isAvailable()) {
                    modrinthCacheService.recordVersion(slug, loader, mcVersion);
                }
                return true;
            }
        } catch (Exception ignored) {
            // 网络异常按不兼容处理并计入负面，避免同环境反复重试
        }
        rememberVersionNegative(slug, loader, mcVersion);
        return false;
    }

    /** 并发(≤5)校验候选池，只保留兼容模组；负面与 versions 命中不会产生 live 请求 */
    private List<CandidateMod> verifyPoolCompatibility(List<CandidateMod> pool,
                                                       String loader, String mcVersion) {
        if (pool.isEmpty()) return pool;
        String ctxKey = MaaLog.userKey();
        List<CandidateMod> verified = java.util.Collections.synchronizedList(new ArrayList<>());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(5);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (CandidateMod c : pool) {
                futures.add(CompletableFuture.runAsync(() -> {
                    MaaLog.runWithUser(ctxKey, () -> {
                        try {
                            gate.acquire();
                            if (isCandidateCompatible(c.slug, loader, mcVersion)) {
                                verified.add(c);
                            }
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        } finally {
                            gate.release();
                        }
                    });
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        System.out.println("   ✔ 版本校验: 候选 " + pool.size() + " 个 → 兼容 " + verified.size()
                + " 个 (loader=" + loader + ", mc=" + mcVersion + ")");
        MaaLog.user("版本校验: 候选 " + pool.size() + " → 兼容 " + verified.size()
                + " (loader=" + loader + ", mc=" + mcVersion + ")");
        return verified;
    }

    // ==========================================
    // 🔎 M1b: 本地缓存建池（缓存不可用时回落 live 旧逻辑）
    // ==========================================

    private List<CandidateMod> buildAddonPoolLocal(String coreSlug, String loader,
                                                   String mcVersion, Set<String> existing) {
        List<CandidateMod> pool = new ArrayList<>();
        for (ModEntry e : modrinthCacheService.findAddons(coreSlug, loader, 25)) {
            if (e.slug().equals(coreSlug) || existing.contains(e.slug())) continue;
            pool.add(new CandidateMod(e.slug(), e.title(), e.description(), e.downloads()));
        }
        return verifyPoolCompatibility(pool, loader, mcVersion);
    }

    private List<CandidateMod> buildFillerPoolLocal(String intentsStr, String loader, String mcVersion,
                                                    Set<String> existing, long maxDownloads) {
        Map<String, CandidateMod> hitMap = new ConcurrentHashMap<>();
        String[] intentGroups = intentsStr.split("\\|");
        for (String group : intentGroups) {
            String[] parts = group.split(":");
            if (parts.length < 3) continue;
            String category = parts[0].trim();
            if (!ModrinthCacheService.CATEGORIES.contains(category)) {
                System.out.println("   ⚠️ 忽略未知类别: " + category + "（不在 19 类内）");
                MaaLog.user("忽略未知类别: " + category);
            }
            for (String kw : parts[2].split(",")) {
                String safeKw = kw.trim();
                if (safeKw.isEmpty()) continue;
                for (ModEntry e : modrinthCacheService.matchByKeyword(category, safeKw, maxDownloads, 120)) {
                    if (existing.contains(e.slug())) continue;
                    if (!e.loaders().contains(loader.toLowerCase())) continue;
                    hitMap.compute(e.slug(), (k, v) -> {
                        if (v != null) {
                            v.hitScore.incrementAndGet();
                            return v;
                        }
                        return new CandidateMod(e.slug(), e.title(), e.description(), e.downloads());
                    });
                }
            }
        }
        List<CandidateMod> finalPool = new ArrayList<>(hitMap.values());
        finalPool.sort((a, b) -> {
            int scoreCompare = Integer.compare(b.hitScore.get(), a.hitScore.get());
            return scoreCompare != 0 ? scoreCompare : Long.compare(b.downloads, a.downloads);
        });
        for (int i = 0; i + 30 < finalPool.size(); i += 30) {
            List<CandidateMod> slice = finalPool.subList(i, Math.min(i + 30, finalPool.size()));
            java.util.Collections.shuffle(slice, new java.util.Random(System.nanoTime()));
        }
        if (finalPool.size() > 150) {
            finalPool = new ArrayList<>(finalPool.subList(0, 150));
        }
        return verifyPoolCompatibility(finalPool, loader, mcVersion);
    }

    // ==========================================
    // 🛡️ core 真实性闸门 + 版本预检前移 + 平替守卫（无别名表：重复覆盖语义）
    // ==========================================

    private static final class RescueResult {
        String replacement;
        boolean duplicate;
        String duplicateWith;   // 命中的"已保留模组"slug（用于可读日志）
    }

    private record UnresolvedMod(String slug, boolean needCommunity) {
    }

    /**
     * 真实性/兼容性统一闸门：
     * 兼容 → 保留；假名或"真名无版本" → 一律进入平替守卫；仍无结果才剔除。
     */
    private Set<String> sanitizePackInitialMods(Set<String> slugs, String loader, String mcVersion) {
        Set<String> sanitized = new LinkedHashSet<>();
        Set<String> existingNorms = new HashSet<>();
        Set<String> seenInput = new HashSet<>();
        List<UnresolvedMod> unresolved = new ArrayList<>();
        for (String slug : slugs) {
            String norm = normKey(slug);
            // existingNorms 只装"真正保留"的 slug；输入内去重用独立的 seenInput，
            // 避免 unresolved 项被误判成"与自身重复"而跳过平替
            if (!seenInput.add(norm)) {
                logDroppedMod(slug, "与本次输入中规范化重复的模组重复，剔除变体");
                continue;
            }
            if (!isRealSlug(slug)) {
                unresolved.add(new UnresolvedMod(slug, false));
                continue;
            }
            if (isCandidateCompatible(slug, loader, mcVersion)) {
                sanitized.add(slug);
                existingNorms.add(norm);
            } else {
                unresolved.add(new UnresolvedMod(slug, true)); // 真名无版本 → 找社区/移植版
            }
        }
        for (UnresolvedMod u : unresolved) {
            RescueResult r = rescueUnknownSlug(u.slug(), u.needCommunity(), loader, mcVersion, existingNorms);
            if (r.duplicate) {
                logDroppedMod(u.slug(), r.duplicateWith != null
                        ? "与已保留模组 [" + r.duplicateWith + "] 规范化重复，保留现有项、剔除别名"
                        : "经平替判定为包内已有真实模组的重复/别名，原项剔除");
            } else if (r.replacement != null) {
                if (existingNorms.add(normKey(r.replacement))) {
                    sanitized.add(r.replacement);
                    System.out.println("🔄 平替 [" + u.slug() + "] -> [" + r.replacement + "]");
                    MaaLog.user("平替: " + u.slug() + " -> " + r.replacement);
                } else {
                    logDroppedMod(u.slug(), "候选 " + r.replacement + " 已在包内，按重复处理");
                }
            } else {
                logDroppedMod(u.slug(), u.needCommunity()
                        ? "真实模组无 " + loader + "/" + mcVersion + " 版本且无合适社区/移植版，剔除"
                        : "LLM 产出名称在 Modrinth 不存在且无合理平替，剔除");
            }
        }
        return sanitized;
    }

    private boolean isRealSlug(String slug) {
        if (modrinthCacheService.isAvailable() && modrinthCacheService.find(slug) != null) return true;
        try {
            JsonNode info = apiClient.getProjectInfo(slug);
            return info != null && info.has("id");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 平替守卫：候选按 Modrinth 相关性/下载量顺序；若首个合理候选已在本包 → 判定重复（原项剔除、不加新项）；
     * 否则做食物类防串味过滤后取第一个；无结果返回 null。
     */
    private record ReplacementCandidate(String slug, String title, long downloads,
                                        String categoriesCsv, int strongHits, boolean affixHit) {
    }

    /**
     * 平替守卫（复合打分，无别名表）：
     * 0) 规范化去重：候选在已保留集合内 → 判定重复；
     * 1) 强词淘汰：标题/slug 未命中任何强词直接丢弃；
     * 2) 词缀分层：原词含社区化词缀，或"真名无版本" → 优先命中同词缀候选；
     *    词缀仅限 port/unofficial/continued/ce/community 等，lib/addon 不参与；
     * 3) 档内排序：强词命中数 → 下载量；
     * 4) 低于 1000 下载量的候选不选（宁可放弃）。
     */
    private RescueResult rescueUnknownSlug(String original, boolean needCommunity, String loader,
                                           String mcVersion, Set<String> existingNorms) {
        RescueResult result = new RescueResult();
        String origNorm = normKey(original);
        if (existingNorms.contains(origNorm)) {
            result.duplicate = true;
            result.duplicateWith = original;
            return result;
        }

        Set<String> originalAffixes = presentAffixes(original);
        List<ReplacementCandidate> candidates = new ArrayList<>();
        if (modrinthCacheService.isAvailable()) {
            for (ModEntry e : modrinthCacheService.candidatesByLoader(loader)) {
                candidates.add(scoreCandidate(original, originalAffixes, needCommunity,
                        e.slug(), e.title(), e.downloads(), String.join(",", e.categories())));
            }
        } else {
            for (String[] hit : liveSearchCandidates(original, loader, mcVersion)) {
                try {
                    candidates.add(scoreCandidate(original, originalAffixes, needCommunity,
                            hit[0], hit[1], Long.parseLong(hit[3]), hit[2]));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        candidates.sort((a, b) -> {
            int affix = Boolean.compare(b.affixHit(), a.affixHit());
            if (affix != 0) return affix;
            int strong = Integer.compare(b.strongHits(), a.strongHits());
            if (strong != 0) return strong;
            return Long.compare(b.downloads(), a.downloads());
        });

        int skippedSelf = 0;
        int skippedDuplicate = 0;
        int skippedLowDownloads = 0;
        int skippedFood = 0;
        int strongCandidates = 0;
        for (ReplacementCandidate c : candidates) {
            if (normKey(c.slug()).equals(origNorm)) continue;      // 跳过自身
            if (existingNorms.contains(normKey(c.slug()))) {        // 规范化真身已在包内
                result.duplicate = true;
                result.duplicateWith = c.slug();
                return result;
            }
            if (c.strongHits() <= 0) {
                skippedSelf++;
                continue;
            }
            strongCandidates++;
            if (c.downloads() < MIN_REPLACEMENT_DOWNLOADS) {
                skippedLowDownloads++;
                continue;
            }
            if (!looksFoodish(original) && isFoodishCandidate(c.title(), c.categoriesCsv())) {
                skippedFood++;
                continue;
            }
            result.replacement = c.slug();
            return result;
        }
        if (result.replacement == null && !result.duplicate) {
            String debug = "平替无结果: " + original + " | 候选总数=" + candidates.size()
                    + " 强词候选=" + strongCandidates
                    + " 弱词跳过=" + skippedSelf
                    + " 低下载跳过=" + skippedLowDownloads
                    + " 食物类跳过=" + skippedFood
                    + " 规范化重复=" + skippedDuplicate;
            System.out.println("   🔎 " + debug);
            MaaLog.user(debug);
        }
        return result;
    }

    private ReplacementCandidate scoreCandidate(String original, Set<String> originalAffixes,
                                                boolean needCommunity, String slug, String title,
                                                long downloads, String categoriesCsv) {
        Set<String> candidateWords = wordSet(slug + " " + title);
        int strong = 0;
        for (String t : strongTokens(original)) {
            if (candidateWords.contains(t)) {
                strong++;
                continue;
            }
            // 兼容"版本号粘连"写法：mobs1211 应能匹配候选词 mobs（mobs(1.21.1) 类）
            String base = t.replaceAll("\\d+$", "");
            if (!base.equals(t) && base.length() >= 3 && candidateWords.contains(base)) {
                strong++;
            }
        }
        boolean affixHit = false;
        if (!originalAffixes.isEmpty()) {
            for (String aff : originalAffixes) {
                if (candidateWords.contains(aff)) affixHit = true;
            }
        } else if (needCommunity) {
            for (String aff : REPLACE_AFFIXES) {
                if (candidateWords.contains(aff)) affixHit = true;
            }
        }
        return new ReplacementCandidate(slug, title, downloads, categoriesCsv, strong, affixHit);
    }

    private List<String[]> liveSearchCandidates(String name, String loader, String mcVersion) {
        List<String[]> hits = new ArrayList<>();
        try {
            String facetsRaw = String.format("[[\"project_type:mod\"], [\"loaders:%s\"], [\"versions:%s\"]]",
                    loader, mcVersion);
            String queryUrl = "https://api.modrinth.com/v2/search?query="
                    + URLEncoder.encode(name, StandardCharsets.UTF_8)
                    + "&limit=10&facets=" + URLEncoder.encode(facetsRaw, StandardCharsets.UTF_8);
            JsonNode res = restClient.get().uri(java.net.URI.create(queryUrl)).retrieve().body(JsonNode.class);
            if (res != null && res.has("hits")) {
                for (JsonNode hit : res.path("hits")) {
                    StringBuilder cats = new StringBuilder();
                    JsonNode arr = hit.path("categories");
                    if (arr.isArray()) {
                        arr.forEach(c -> cats.append(c.asText()).append(','));
                    }
                    hits.add(new String[]{hit.path("slug").asText(),
                            hit.path("title").asText(), cats.toString(),
                            String.valueOf(hit.path("downloads").asLong(0))});
                }
            }
        } catch (Exception ignored) {
        }
        return hits;
    }

    private static String normKey(String slug) {
        return slug.toLowerCase().replaceAll("[^a-z0-9]+", "");
    }

    /** 强词 = 分词后长度≥3 且非停用词、非平替词缀（lib/addon 亦不参与） */
    private static Set<String> strongTokens(String name) {
        Set<String> result = new HashSet<>();
        String[] parts = name.toLowerCase().split("[^a-z0-9]+");
        for (String p : parts) {
            if (p.length() < 3) continue;
            if (REPLACE_STOPWORDS.contains(p) || REPLACE_AFFIXES.contains(p)) continue;
            result.add(p);
        }
        return result;
    }

    private static Set<String> presentAffixes(String text) {
        Set<String> found = new HashSet<>();
        for (String aff : REPLACE_AFFIXES) {
            if (wordSet(text).contains(aff)) found.add(aff);
        }
        return found;
    }

    private static Set<String> wordSet(String text) {
        Set<String> words = new HashSet<>();
        for (String p : text.toLowerCase().split("[^a-z0-9]+")) {
            if (!p.isEmpty()) words.add(p);
        }
        return words;
    }

    private static boolean looksFoodish(String name) {
        String n = name.toLowerCase();
        return n.contains("food") || n.contains("delight") || n.contains("farmers");
    }

    private static boolean isFoodishCandidate(String title, String categories) {
        String t = title.toLowerCase();
        String c = categories.toLowerCase();
        return (c.contains("food") || t.contains("delight")) && !t.contains("spell") && !t.contains("magic");
    }

    private void logDroppedMod(String slug, String reason) {
        System.out.println("🗑️ 剔除 [" + slug + "]：" + reason);
        MaaLog.user("剔除 " + slug + "：" + reason);
    }

    /** expand_addons 里的核心也必须真实且兼容；LLM 编造名走同一平替守卫 */
    private List<String> sanitizeExpandCores(List<String> cores, String loader, String mcVersion,
                                             Set<String> existing) {
        List<String> result = new ArrayList<>();
        Set<String> existingNorms = new HashSet<>();
        for (String s : existing) existingNorms.add(normKey(s));
        for (String core : cores) {
            boolean real = isRealSlug(core);
            boolean needCommunity = false;
            if (real && isCandidateCompatible(core, loader, mcVersion)) {
                result.add(core);
                continue;
            }
            needCommunity = real; // 真名无版本 → 找社区/移植版；假名走通用强词匹配
            RescueResult r = rescueUnknownSlug(core, needCommunity, loader, mcVersion, existingNorms);
            if (r.duplicate) {
                logDroppedMod(core, r.duplicateWith != null
                        ? "expand 核心与已保留模组 [" + r.duplicateWith + "] 规范化重复"
                        : "expand 核心为包内已有模组的重复/别名");
            } else if (r.replacement != null) {
                if (existingNorms.add(normKey(r.replacement))) {
                    System.out.println("🔄 expand 平替 [" + core + "] -> [" + r.replacement + "]");
                    MaaLog.user("expand 平替: " + core + " -> " + r.replacement);
                    result.add(r.replacement);
                } else {
                    logDroppedMod(core, "expand 候选已在包内，按重复处理");
                }
            } else {
                logDroppedMod(core, real
                        ? "expand 核心无 " + loader + "/" + mcVersion + " 版本且无合适社区/移植版"
                        : "expand 核心不存在且无合理平替");
            }
        }
        return result;
    }

    private String processAndAssembleBlueprint(String aiBlueprint, String currentMods, String prompt,
                                               String effectiveApiKey, String uuid, PackSessionState state) {
        try {
            String loader = extractTag(aiBlueprint, "loader", "neoforge");
            String mcVersion = extractTag(aiBlueprint, "mc", "1.21.1");
            int targetCount = Integer.parseInt(extractTag(aiBlueprint, "target_count", "100"));
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

            // M2: 应用 Tool 变更 —— 删除必须在 core 合并之后执行，避免被 Architect 复述加回
            if (!state.getRemovedSlugs().isEmpty()) {
                initialMods.removeAll(state.getRemovedSlugs());
                System.out.println("🗑️ Tool 删除生效，移除 " + state.getRemovedSlugs().size()
                        + " 个模组，剩余 " + initialMods.size() + " 个");
            }
            if (state.getTargetCount() != null) targetCount = state.getTargetCount();
            if (state.getMaxDownloads() != null) maxDownloads = state.getMaxDownloads();

            // 真实性闸门 + 版本预检前移（AI 生成流才执行；mrpack 直传跳过，保持旧语义）
            boolean needFallback = !searchIntentsStr.isEmpty() || !expandAddonsStr.isEmpty();
            if (needFallback) {
                initialMods = sanitizePackInitialMods(initialMods, loader, mcVersion);
            }

            int needed = targetCount - initialMods.size();

            // ★ 空图谱模式: 无核心模组 + 无搜索意图 + 无已有模组 → 直接返回空图谱
            if (initialMods.isEmpty() && searchIntentsStr.isEmpty() && expandAddonsStr.isEmpty()) {
                System.out.println("📋 空图谱模式 — 用户自行构建，跳过阶段2-4");
                String cleanReply = aiBlueprint.replaceAll("<target_count>[\\s\\S]*?</target_count>", "")
                        .replaceAll("<max_downloads>[\\s\\S]*?</max_downloads>", "")
                        .replaceAll("<core_mods>[\\s\\S]*?</core_mods>", "")
                        .replaceAll("<expand_addons>[\\s\\S]*?</expand_addons>", "")
                        .replaceAll("<search_intents>[\\s\\S]*?</search_intents>", "");
                String emptyReply = appendOpsAndMods(cleanReply, state, "\n<mods></mods>");
                MaaLog.user("结果: 空图谱/拒绝分支，未添加模组");
                return emptyReply;
            }

            // ==========================================
            // 🚀 多智能体重排机制：构建供 Critic 审核的上下文
            // ==========================================
            if ((needed > 0 && !searchIntentsStr.isEmpty()) || !expandAddonsStr.isEmpty()) {
                StringBuilder criticContext = new StringBuilder();
                criticContext.append("【用户原始需求】\n").append(prompt).append("\n\n");
                criticContext.append("【已有核心模组】\n").append(initialMods).append("\n\n");
                criticContext.append("【你需要挑选的目标总数】\n由于还需要补齐配额，你大约需要从以下候选池中挑选 ").append(needed).append(" 个模组。\n\n");

                boolean requiresCritic = false;
                Set<String> poolReserved = new HashSet<>(initialMods);

                System.out.println("\n=======================================================");
                System.out.println("🌐 [阶段 2] Java 捕手启动多路召回，开始建立候选池...");

                // 1. 构建附属候选池
                if (!expandAddonsStr.isEmpty()) {
                    System.out.println("   🔭 捕获到附属扩张请求: [" + expandAddonsStr + "]");
                    criticContext.append("【待审核附属池】（请挑选优秀的，剔除垃圾或与用户需求无关的）\n");
                    List<String> cores = Arrays.stream(expandAddonsStr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                    cores = sanitizeExpandCores(cores, loader, mcVersion, initialMods);
                    for (String core : cores) {
                        List<CandidateMod> addons = buildAddonPool(core, loader, mcVersion, initialMods);
                        criticContext.append("--> ").append(core).append(" 的附属候选：\n");
                        for (CandidateMod c : addons) {
                            criticContext.append(String.format("- %s | 下载量:%dW | 描述:%s\n", c.slug, c.downloads/10000, c.desc));
                        }
                        addons.forEach(c -> poolReserved.add(c.slug));
                    }
                    criticContext.append("\n");
                    requiresCritic = true;
                }

                // 2. 构建意图搜索多路召回池
                if (needed > 0 && !searchIntentsStr.isEmpty()) {
                    System.out.println("   🔍 捕获到 AI 关键词矩阵: [" + searchIntentsStr + "]");
                    criticContext.append("【待审核意图候选池】（请优先选择 HitScore 分数高的模组，并筛除与核心存在冲突的）\n");

                    List<CandidateMod> fillers = buildFillerPoolByKeywords(searchIntentsStr, loader, mcVersion, poolReserved, maxDownloads, uuid);

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

                    if (isAborted(uuid)) return "⛔ 思考已手动终止。";

                    String criticReply = aiAgentService.criticPools(criticContext.toString(), effectiveApiKey);

                    System.out.println("   📜 审核员原始裁决报告:");
                    System.out.println("-------------------------------------------------------");
                    System.out.println(criticReply.trim());
                    System.out.println("-------------------------------------------------------");

                    String approvedStr = extractTag(criticReply, "approved_mods", "");

                    if (!approvedStr.isEmpty()) {
                        List<String> approvedMods = cleanSlugs(approvedStr);
                        int beforeApproved = initialMods.size();
                        initialMods.addAll(approvedMods);
                        int addedCount = initialMods.size() - beforeApproved;
                        System.out.println("✅ 审核员最终通过 " + approvedMods.size() + " 个模组 (新增 "
                                + addedCount + ", 已在包内 " + (approvedMods.size() - addedCount) + "): " + approvedMods);
                    } else {
                        System.out.println("⚠️ 警告：审核员未通过任何模组或未输出正确的 XML 标签。");
                    }
                }
            }

            // ==========================================
            // 🛡️ 图谱引擎（真实性/版本预检已在阶段 0 完成）
            // ==========================================
            System.out.println("\n=======================================================");
            System.out.println("🛠️ [阶段 4] 启动图谱引擎与深度依赖穿透...");
            System.out.println("🕸️ 开始将所有拼合模组 (" + initialMods.size() + "个) 送入深度依赖穿透引擎...");
            Set<String> finalPerfectMods = dependencyEngine.resolveFullDependencies(initialMods, loader, mcVersion);

            // 清理 Architect 输出的多余标签
            String cleanReply = aiBlueprint.replaceAll("<target_count>[\\s\\S]*?</target_count>", "")
                    .replaceAll("<max_downloads>[\\s\\S]*?</max_downloads>", "")
                    .replaceAll("<core_mods>[\\s\\S]*?</core_mods>", "")
                    .replaceAll("<expand_addons>[\\s\\S]*?</expand_addons>", "")
                    .replaceAll("<search_intents>[\\s\\S]*?</search_intents>", "");

            System.out.println("🎉 构筑完成！最终打包模组数：" + finalPerfectMods.size());
            String finalReply = appendOpsAndMods(cleanReply, state,
                    "\n<mods>" + String.join(",", finalPerfectMods) + "</mods>");
            MaaLog.user("结果: 最终模组 " + finalPerfectMods.size()
                    + " 个 -> " + String.join(",", finalPerfectMods));
            return finalReply;

        } catch (Exception e) {
            System.err.println("组装蓝图失败: " + e.getMessage());
            return aiBlueprint;
        }
    }

    /** 追加 M2 变更摘要(<ops_summary>)与最终模组列表；Tool 改名时替换 <name> */
    private String appendOpsAndMods(String cleanReply, PackSessionState state, String modsXml) {
        String reply = cleanReply;
        if (state.getPackName() != null) {
            reply = reply.replaceAll("(?s)<name>.*?</name>", "<name>" + state.getPackName() + "</name>");
        }
        if (state.hasOps()) {
            reply += "\n<ops_summary>" + String.join("; ", state.getOps()) + "</ops_summary>";
        }
        return reply + modsXml;
    }

    // 🚀 建池器 1：获取附属候选池
    private List<CandidateMod> buildAddonPool(String coreSlug, String loader, String mcVersion, Set<String> existing) {
        if (modrinthCacheService.isAvailable()) {
            return buildAddonPoolLocal(coreSlug, loader, mcVersion, existing);
        }
        List<CandidateMod> pool = new ArrayList<>();
        try {
            String facetsRaw = String.format("[[\"project_type:mod\"], [\"loaders:%s\"], [\"versions:%s\"]]", loader, mcVersion);
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
    private List<CandidateMod> buildFillerPoolByKeywords(String intentsStr, String loader, String mcVersion, Set<String> existing, long maxDownloads, String uuid) {
        if (modrinthCacheService.isAvailable()) {
            return buildFillerPoolLocal(intentsStr, loader, mcVersion, existing, maxDownloads);
        }
        Map<String, CandidateMod> hitMap = new ConcurrentHashMap<>();
        String facetsRaw = String.format("[[\"project_type:mod\"], [\"loaders:%s\"], [\"versions:%s\"]]", loader, mcVersion);
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
                        if (isAborted(uuid)) return;
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
            String facetsRaw = String.format("[[\"project_type:mod\"], [\"loaders:%s\"], [\"versions:%s\"]]", loader, mcVersion);
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
                // Modrinth slug 允许括号与点号（如 alexs-caves-(unofficial-port)、xxx(1.21.1)），必须保留
                .map(s -> s.replaceAll(".*#.*?\n?", "").replaceAll("[^a-zA-Z0-9_().-]", ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private String extractTag(String text, String tag, String defaultValue) {
        Pattern pattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : defaultValue;
    }
}
