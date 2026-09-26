package yagen.waitmydawn.maa.controller;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.cache.ModrinthCacheService;
import yagen.waitmydawn.maa.cache.ModrinthCacheService.ModEntry;
import yagen.waitmydawn.maa.logging.MaaLog;
import yagen.waitmydawn.maa.runtime.DelegationContext;
import yagen.waitmydawn.maa.runtime.DelegationContext.Outcome;
import yagen.waitmydawn.maa.runtime.RequestScope;
import yagen.waitmydawn.maa.runtime.ScopedExecutors;
import yagen.waitmydawn.maa.service.*;

import java.net.URLEncoder;
import java.time.Duration;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.DoubleAdder;
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
    /** 别名词典（中文黑话 → slug），提示词与 Tool 共用同一份（P3-5） */
    private final ModAliasRegistry aliasRegistry;
    /** 依赖膨胀系数跟踪器：把"最终目标数"折算成"根模组预算"（P3-6） */
    private final ExpansionTracker expansionTracker;
    /** 委派任务表：把"发请求"交给用户浏览器做（详见 DelegationContext 类注释） */
    private final DelegationTasks delegationTasks;
    /** 加载器版本表：用于"用户点名的环境我们是否维护"以及给提示词注入支持清单 */
    private final LoaderVersionService loaderVersionService;

    /**
     * 系统默认 API Key（来自配置 {@code ai.api.key}，通常由环境变量 DEEPSEEK_API_KEY 注入）。
     *
     * <p>修复：这里原先读的是 {@code System.getProperty("ai.api.key")}（JVM 系统属性），
     * 而 application.properties 里配的是 Spring 属性 {@code ai.api.key=${DEEPSEEK_API_KEY:}}，
     * 两者不是一回事——按文档用环境变量配置默认 Key 的部署，/api/chat 会一直报"未配置 API Key"。
     * 与启动时注入的其它配置项保持同一套取值方式（Spring 属性优先，环境变量兜底）。
     */
    @Value("${ai.api.key:}")
    private String systemDefaultApiKey;

    /**
     * 是否允许通过 {@code X-LLM-Api-Key} 请求头为本次调用指定 Key（默认关闭）。
     *
     * <p>用途：自动化评测（GoldenSetEval）需要在不写 .env、不落盘的前提下带着 Key 跑；
     * 打开后调用方只能"自带 Key"，无法借它访问服务器上的默认 Key 或他人数据，风险可控。
     */
    @Value("${maa.eval.allow-key-override:false}")
    private boolean allowKeyOverride;

    /**
     * 评测专用：强制把审核员回复截断（只保留 {@code <approved_mods>} 开标签、去掉闭合标签），
     * 稳定复现"输出被 token 上限截断"的退化路径（坏例 B16）。默认关闭，生产不要打开。
     *
     * <p>为什么需要它：这条路径取决于模型是否复读，属于概率事件；只有能确定性注入，
     * 才能证明"退化时由 Java 补位兜底 + 如实告知"真的生效，而不是等下一次偶发。
     */
    @Value("${maa.eval.force-critic-degraded:false}")
    private boolean forceCriticDegraded;

    // 使用 volatile 标记替代 Thread.interrupt() 实现即时终止
    private final Map<String, Boolean> abortedSessions = new ConcurrentHashMap<>();

    /**
     * 版本负面记录: key = loader|mc|slug, value = 记录时刻。
     *
     * <p>只存"确定不兼容"（Modrinth 明确返回空），不存网络故障。
     * TTL 60 分钟且按条计时：模组作者随时可能补发某个 MC 版本的构建，
     * 记死会永久错过新版；但 60 分钟内复用结论足以挡住重复回源。
     */
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(60);
    /** 负面记录条数上限，超限时先清理过期项，避免无限增长 */
    private static final int NEGATIVE_MAX_ENTRIES = 20000;
    private final Map<String, Long> negativeVersionCache = new ConcurrentHashMap<>();

    /** 平替候选的社区化词缀（lib/addon 不参与平替，留给附属匹配） */
    private static final Set<String> REPLACE_AFFIXES = Set.of(
            "port", "unofficial", "continued", "ce", "community", "remake", "reborn", "rebirth");

    /** 回复里最多列几条"点名模组被调整"（其余指向日志），避免把回复撑爆 */
    private static final int MOD_DELTA_NOTE_LIMIT = 8;

    /**
     * 把"点名模组被平替/剔除/去重"写成**用户可见**的说明。
     *
     * <p>为什么必须告知：这三件事都改变了用户的包——平替换了实现、剔除少了内容、去重把别名合并了；
     * 以前它们只写进日志，用户只会发现"我要的东西没进来"，却不知道为什么、也不知道该找谁。
     * 现在如实写进回复，并标明来源（你包里原有的 / 本轮新增的）。
     *
     * @return 说明文本；没有任何调整时返回空串（不打扰用户）
     */
    static String buildModDeltaNote(List<ModDelta> deltas) {
        if (deltas == null || deltas.isEmpty()) return "";
        List<ModDelta> replaced = deltas.stream().filter(d -> "REPLACED".equals(d.action())).toList();
        List<ModDelta> dropped = deltas.stream().filter(d -> "DROPPED".equals(d.action())).toList();
        List<ModDelta> deduped = deltas.stream().filter(d -> "DUPLICATE".equals(d.action())).toList();
        if (replaced.isEmpty() && dropped.isEmpty() && deduped.isEmpty()) return "";

        StringBuilder sb = new StringBuilder(
                "\n\n🔁 本轮对点名的模组做了以下调整（依据 Modrinth 的真实查询结果，不是猜测）：\n");
        int shown = 0;
        for (ModDelta d : replaced) {
            if (shown >= MOD_DELTA_NOTE_LIMIT) break;
            sb.append("· 平替：").append(d.slug()).append(" → ").append(d.detail()).append(originOf(d))
                    .append("（原名在当前 mc/loader 下没有可用版本，或 Modrinth 上没有精确同名项目）\n");
            shown++;
        }
        for (ModDelta d : dropped) {
            if (shown >= MOD_DELTA_NOTE_LIMIT) break;
            sb.append("· 剔除：").append(d.slug()).append(originOf(d)).append(" —— ").append(d.detail()).append('\n');
            shown++;
        }
        for (ModDelta d : deduped) {
            if (shown >= MOD_DELTA_NOTE_LIMIT) break;
            sb.append("· 去重：").append(d.slug()).append(originOf(d)).append(" —— ").append(d.detail()).append('\n');
            shown++;
        }
        int rest = replaced.size() + dropped.size() + deduped.size() - shown;
        if (rest > 0) sb.append("· …还有 ").append(rest).append(" 项同类调整，详见本轮日志\n");
        sb.append("（这些调整都会影响最终包；如果你要的正是被剔除的那个，可以点名要我换一个可用的替代。）");
        return sb.toString();
    }

    /** 标注这条调整是"你包里原有的"还是"本轮新增的"，让用户知道该找谁算账 */
    private static String originOf(ModDelta d) {
        return d.fromPack() ? "（你包里原有的模组）" : "（本轮新增的模组）";
    }

    /** 分词时剔除的停用词（保证"强词"有意义，避免 config/api/port 之类的弱词命中） */
    private static final Set<String> REPLACE_STOPWORDS = Set.of(
            "the", "of", "and", "for", "in", "on", "to", "a", "an", "is", "x",
            "mod", "mods", "minecraft", "mc", "forge", "neoforge", "fabric", "quilt",
            "reloaded", "edition", "api", "lib", "library", "addon", "config", "core",
            "compat", "compatibility", "integration", "loader", "support", "modded");

    /** 平替候选下载量下限，过低视为无人维护/无意义，回落到正常匹配或放弃 */
    private static final long MIN_REPLACEMENT_DOWNLOADS = 1000L;

    // 🔥 用于记录候选模组的数据结构 (含多路召回的可解释打分)
    static class CandidateMod {
        String slug;
        String title;
        String desc;
        long downloads;
        /** 召回它的意图类别（用于 Critic 上下文标注与类别配额核对，可能为 null） */
        String category;
        /** 文本相关度：字段权重 × 命中词元数 × 词长权重（在线召回路径由多个虚拟线程累加，必须并发安全） */
        final DoubleAdder textScore = new DoubleAdder();
        /** 命中的词元（去重、并发安全）：写进 Critic 上下文，让审核员看得见"依据是什么" */
        final Set<String> hitTerms = ConcurrentHashMap.newKeySet();
        /** 热度归一系数 ∈ [POP_FLOOR, 1]，全局分母确定后写入 */
        double popNorm = 1.0;
        /** 最终分 = textScore × popNorm（定完全局分母后一次性写入，此时已是单线程） */
        double finalScore;

        public CandidateMod(String slug, String title, String desc, long downloads) {
            this.slug = slug;
            this.title = title;
            this.desc = desc;
            this.downloads = downloads;
        }

        /** 累加一个关键词的命中（本地/在线两条召回路径共用同一套打分） */
        void addMatch(double score, List<String> terms) {
            textScore.add(score);
            hitTerms.addAll(terms);
        }

        /** 命中词元的可读输出（排序保证可复现） */
        String hitTermsText() {
            return hitTerms.stream().sorted().collect(Collectors.joining(","));
        }
    }

    /**
     * 解析后的检索意图（P3-3）。
     *
     * @param category 已归一化的规范类别名
     * @param ratio    归一化后的比例（同一计划内总和为 100）
     * @param keywords 该类别的英文检索词（已去重）
     */
    record SearchIntent(String category, int ratio, List<String> keywords) {
    }

    /** 每类候选池是"目标配额"的几倍，用于给 Critic 留挑选空间 */
    private static final int POOL_OVERSAMPLE = 3;
    /** 每类至少提供这么多候选，避免小比例类别被压成 0 */
    private static final int POOL_MIN_PER_CATEGORY = 5;
    /** 喂给 Critic 的候选总数上限（token 保护，与旧行为一致） */
    private static final int POOL_CAP = 150;
    /**
     * 单个关键词的扫描上限。
     *
     * <p>桶内是按下载量降序预排序的，取前缀等于"用一个词捞回来的前 N 个热门命中"。
     * 旧值 120 会把"下载量不高但匹配得更好"的长尾直接剪掉（实测本地召回全阶段只占整轮 1~3%，
     * 所以这里换成更高的上限来买召回，代价可忽略）。
     */
    private static final int PER_KEYWORD_SCAN_CAP = 300;
    /** 候选排序：最终分优先，同分按下载量（最终分已含热度系数，这里只兜底保持确定性） */
    private static final Comparator<CandidateMod> SCORE_THEN_DOWNLOADS = (a, b) -> {
        int cmp = Double.compare(b.finalScore, a.finalScore);
        return cmp != 0 ? cmp : Long.compare(b.downloads, a.downloads);
    };

    public ChatController(AiAgentService aiAgentService, ModrinthCacheService modrinthCacheService,
                          RestClient restClient, DependencyEngine dependencyEngine,
                          ModrinthApiClient apiClient, ConversationRepository convRepo, ChatMessageRepository msgRepo,
                          UserController userController, UserRepository userRepo,
                          CategoryPreferenceRepository catRepo, ModPreferenceRepository modRepo,
                          ModAliasRegistry aliasRegistry, ExpansionTracker expansionTracker,
                          DelegationTasks delegationTasks, LoaderVersionService loaderVersionService) {
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
        this.aliasRegistry = aliasRegistry;
        this.expansionTracker = expansionTracker;
        this.delegationTasks = delegationTasks;
        this.loaderVersionService = loaderVersionService;
    }

    /**
     * 原始构筑流程本体（四阶段串起来的那一大段）。
     *
     * <p>从 {@code @PostMapping} 上摘下来了，因为现在有两条路进来：委派关闭时由下面的
     * {@link #chat} 直接调用（同步、老行为），开启时由 {@link DelegationTasks} 提交到虚拟线程跑——
     * 因为挂起等浏览器的线程必须活得比这次 HTTP 请求长。方法体一行没动。
     */
    private String doChat(Map<String, String> payload, String authToken,
                          String apiKeyOverride, Long convId) {
        String prompt = payload.get("prompt");
        String currentMods = payload.get("currentMods");
        String uuid = payload.getOrDefault("uuid", "default-user");
        // 🗑️ 用户在图谱里显式删除的模组（P2）：本回合不允许再被复述/召回/依赖引擎补回
        Set<String> excludedSlugs = parseSlugCsv(payload.get("excludedSlugs"));

        // 从 token 获取用户 API Key (后端加密存储, 不暴露给前端)
        String effectiveApiKey = (allowKeyOverride && apiKeyOverride != null && !apiKeyOverride.isBlank())
                ? apiKeyOverride
                : userController.getUserApiKey(authToken);
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            // 回退到系统默认 Key（配置项 ai.api.key，由环境变量 DEEPSEEK_API_KEY 注入）
            effectiveApiKey = systemDefaultApiKey;
        }

        // 仍未找到 → 返回友好提示
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            if (authToken != null && userController.validateToken(authToken) != null) {
                return "请先在右上角 ⚙️ 设置 中配置你的 DeepSeek API Key (格式: sk-...)，配置后即可对话。";
            }
            return "未配置 API Key。请先登录后在设置页配置个人 Key，或通过环境变量 DEEPSEEK_API_KEY 设置系统默认 Key。";
        }

        // 获取用户 ID (用于保存对话历史)
        Long userId = userIdFor(authToken);
        String userKey = userLogKey(userId, uuid);
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

        // 🏷️ P3-1：注入类别唯一权威源（19 个规范名 + 中文释义 + 推荐检索词）。
        // 既约束"只能填规范名"，也给中文口语需求提供落点，提升"自然语言 → 类别"的命中率。
        sb.append(CategoryRegistry.promptBlock()).append("\n");
        // 📖 P3-5：注入别名词典（来自 maa_db/aliases.json，与 ArchitectPackTools 共用同一份）
        String aliasBlock = aliasRegistry.promptBlock();
        if (!aliasBlock.isEmpty()) sb.append(aliasBlock).append("\n");

        // 🌍 F01 修复：把前端持有的包环境传给模型。此前协议里根本没有 mc/loader，
        // 续聊时模型只能回到默认的 neoforge/1.21.1，导致"先建 Fabric 1.20.1 包、再加一个模组"会漂移环境。
        String reqMc = payload.get("mcVersion");
        String reqLoader = payload.get("loader");
        if (reqMc != null && !reqMc.isBlank() && reqLoader != null && !reqLoader.isBlank()) {
            sb.append("【当前整合包环境】（这是权威值）\n")
                    .append("Minecraft 版本: ").append(reqMc.trim())
                    .append("，加载器: ").append(reqLoader.trim()).append("\n")
                    .append("除非用户【明确要求更换环境】，否则你必须保持这个环境，并在 XML 里原样回填")
                    .append("<mc> 和 <loader>。\n\n");
        }

        // 🌍 环境切换：**不给版本清单**（100 个条目 ≈1KB，每次请求都要付，还会和表漂移），
        // 改为让模型调 setEnvironment 工具、由 Java 查表校验。这里只给命名规则与调用要求。
        sb.append("【环境与版本号】MC 新版本用 xx.y.z 命名：xx=年份后缀（2026→26）、y=季度、z=补丁；"
                + "例如 26.2 = 2026 年第 2 季度，26.1.2 = 26.1 的第 2 个补丁。不要把 26.x 当成 1.21.x 的加载器号。\n")
                .append("用户**点名**了某个 MC 版本或加载器时，调用 setEnvironment 工具，由 Java 校验该版本是否存在；")
                .append("**不要凭记忆判断某个版本号存不存在**，也不要因为\"当前环境\"不同就拒绝切换。")
                .append("工具若被拒绝，请把它给的原因与可用清单如实告诉用户。\n\n");
        yagen.waitmydawn.maa.model.EnvIntent envIntent = yagen.waitmydawn.maa.model.EnvIntent.parse(prompt);
        if (envIntent != null && !loaderVersionService.supports(
                envIntent.loader() != null ? envIntent.loader()
                        : (reqLoader == null || reqLoader.isBlank() ? "neoforge" : reqLoader.trim()),
                envIntent.mcVersion())) {
            MaaLog.user("环境意图: 用户点名 MC " + envIntent.mcVersion() + " 不在维护清单里，本轮不切换");
            envIntent = null;
        }
        // 与当前环境一致 = 不是切换诉求（挡掉"句子里顺带提到某版本号"的误报）
        if (envIntent != null
                && envIntent.mcVersion().equals(reqMc == null ? "" : reqMc.trim())
                && (envIntent.loader() == null || envIntent.loader().equals(reqLoader == null ? "" : reqLoader.trim()))) {
            envIntent = null;
        }
        if (envIntent != null) {
            MaaLog.user("环境意图: 用户点名切换 → mc=" + envIntent.mcVersion()
                    + (envIntent.loader() == null ? "（加载器沿用当前）" : " loader=" + envIntent.loader()));
            sb.append("【用户本轮明确点名了环境】Minecraft ").append(envIntent.mcVersion())
                    .append(envIntent.loader() == null ? "" : " + " + envIntent.loader())
                    .append("：请按这个环境构筑，并在 XML 里回填对应的 <mc>/<loader>。\n");
        }
        sb.append("【用户指令】：\n").append(prompt).append("\n");

        try {
            System.out.println("\n=======================================================");
            System.out.println("🤖 [阶段 1] 呼叫规划师 (Architect Agent) 分析意图与蓝图...");
            // M2: 每回合新建包状态与 Tool 集，Architect 可通过 Tool 下发删除/配额等变更
            PackSessionState state = new PackSessionState();
            ArchitectPackTools packTools = new ArchitectPackTools(state, modrinthCacheService, apiClient,
                    aliasRegistry, loaderVersionService);
            AiAgentService.AgentCallResult architectCall =
                    aiAgentService.planBlueprint(sb.toString(), effectiveApiKey, packTools);
            String aiBlueprint = architectCall.text();
            // 🌍 工具 setEnvironment 的切换**优先于**正则兜底（语义判断更准）：
            // 模型主动切了就用它；没切则看正则兜底（见上面 envIntent 的解析）。
            if (state.getEnvMc() != null) {
                envIntent = new yagen.waitmydawn.maa.model.EnvIntent(state.getEnvMc(), state.getEnvLoader());
                MaaLog.user("环境切换(工具 setEnvironment): mc=" + state.getEnvMc()
                        + (state.getEnvLoader() == null ? "（加载器沿用当前）" : " loader=" + state.getEnvLoader()));
            }
            // 📋 让"本轮变更"卡片也显示环境切换：用户点名切了环境，是比"包名变了"更重要的变更，
            // 不能只体现在回复正文里（正文可能很长，用户容易漏看）。
            if (envIntent != null) {
                String fromMc = reqMc == null ? "?" : reqMc.trim();
                String fromLoader = reqLoader == null ? "" : reqLoader.trim();
                String toLoader = envIntent.loader() != null ? envIntent.loader() : fromLoader;
                state.setEnvChange((fromLoader.isEmpty() ? fromMc : fromMc + " + " + fromLoader)
                        + " → " + (toLoader.isEmpty() ? envIntent.mcVersion()
                                                      : envIntent.mcVersion() + " + " + toLoader));
            }
            // 🔁 格式纠错重试：模型偶发只写说明文字、完全不输出 XML（实测 A12/A18 都撞过），
            // 只靠提示词压不住。这里补一次强约束重试——注意【不带 tools】，
            // 避免 removeMods/setTargetCount 这类有副作用的工具被重复执行。
            if (looksLikeFormatViolation(aiBlueprint)) {
                System.out.println("🔁 模型未输出任何构筑标签 → 触发一次格式纠正重试");
                MaaLog.user("格式违规（无 XML 标签），触发一次纠正重试");
                AiAgentService.AgentCallResult retry = aiAgentService.planBlueprint(
                        sb + "\n\n【格式纠正】你上一次的回复里没有任何 XML 标签，用户会因此完全拿不到模组清单。"
                                + "请重新输出：简短说明 + 完整 XML 标签，且必须包含 <target_count>、<core_mods>、<search_intents>。",
                        effectiveApiKey);
                if (!looksLikeFormatViolation(retry.text())) {
                    aiBlueprint = retry.text();
                    architectCall = new AiAgentService.AgentCallResult(retry.text(),
                            architectCall.elapsedMs() + retry.elapsedMs(),
                            architectCall.modelCalls() + retry.modelCalls(),
                            architectCall.inputTokens() + retry.inputTokens(),
                            architectCall.outputTokens() + retry.outputTokens());
                    MaaLog.user("格式纠正成功");
                } else {
                    MaaLog.user("格式纠正失败，仍无 XML 标签");
                }
            }

            if (isAborted(uuid)) {
                System.out.println("🛑 用户终止 — 放弃后续蓝图组装。");
                return "⛔ 思考已手动终止。";
            }

            return processAndAssembleBlueprint(aiBlueprint, currentMods, prompt, effectiveApiKey, uuid, state,
                    excludedSlugs, architectCall, payload.get("mcVersion"), payload.get("loader"), envIntent);
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

    /** 已登录用 userId、匿名用 anon-uuid —— 这是全项目日志归属的唯一口径 */
    private Long userIdFor(String authToken) {
        if (authToken == null) return null;
        try {
            return userController.validateToken(authToken);
        } catch (Exception e) {
            return null;
        }
    }

    /** 日志归属键：chat() 与 doChat() 必须用同一套规则，否则同一次请求的日志会劈成两个文件 */
    private static String userLogKey(Long userId, String uuid) {
        return userId != null ? "user-" + userId : "anon-" + sanitizeUuid(uuid);
    }

    /** 模型是否完全没输出构筑标签（= 只写了说明文字，属于格式违规，需要纠正重试） */
    private static boolean looksLikeFormatViolation(String blueprint) {
        if (blueprint == null || blueprint.isBlank()) return true;
        return blueprint.indexOf("<core_mods>") < 0
                && blueprint.indexOf("<search_intents>") < 0
                && blueprint.indexOf("<target_count>") < 0;
    }

    /**
     * Critic 输出是否"退化"：要么压根没给 {@code <approved_mods>} 标签，要么**标签开了没闭合**
     * （后者几乎一定是输出被 token 上限截断——实测坏例 B16：模型复读候选池打满 8192 token）。
     *
     * <p>为什么要单独判它：退化时 {@code extractTag} 会返回空串，旧实现把它当成"审核员一个都没挑"，
     * 于是补位、配额核对、缺口提示全被跳过，用户拿到一个 0.25 倍的包且没有任何提示。
     */
    static boolean looksLikeCriticDegeneration(String criticReply) {
        if (criticReply == null || criticReply.isBlank()) return true;
        int open = criticReply.indexOf("<approved_mods>");
        if (open < 0) return true;
        return criticReply.indexOf("</approved_mods>", open) < 0;
    }

    // ==========================================
    // 🚀 取数委派：把"向 Modrinth 发请求"交给用户浏览器
    //
    // 响应形态自己描述自己，前端只看 Content-Type 分流：
    //   text/plain  → 就是最终回复（含 <mods>/<trace> 那套 XML），前端解析逻辑一行不用改
    //   application/json → {taskId, stage:"need-data", wanted:[...]}，前端取完数据再 POST 回来
    //
    // 开关关掉时永远不会出现第二种响应，前端自然走老路径，一次往返，与改造前完全一致。
    // ==========================================

    /** 一次请求最多接收多少条回执：挡掉"塞一大堆垃圾让服务器慢慢校验"的玩法 */
    private static final int MAX_FACT_RECORDS = 2_000;
    /** 等任务出结果/出需求的上限。任务本身没有硬超时，这里只是别把 HTTP 线程无限期挂住 */
    private static final long TASK_WAIT_LIMIT_MS = 20 * 60 * 1000L;

    @PostMapping
    public ResponseEntity<Object> chat(@RequestBody Map<String, String> payload,
                                       @RequestHeader(value = "X-Auth-Token", required = false) String authToken,
                                       @RequestHeader(value = "X-LLM-Api-Key", required = false) String apiKeyOverride,
                                       @RequestHeader(value = "X-Conversation-Id", required = false) Long convId) {
        if (!delegationTasks.isEnabled()) {
            // 老路径：同步跑完再返回，与改造前逐字节一致
            return textResponse(doChat(payload, authToken, apiKeyOverride, convId));
        }
        // 委派开启：整个流程挪到长生命周期的虚拟线程上——挂起等浏览器的线程必须活得比这次请求长。
        // RequestScope 在这里（提交时）捕获，否则任务里发出的服务器请求统计不到本轮 <trace>。
        // 用和 doChat 完全一样的推导规则，否则委派那几行会写进另一个日志文件
        String userKey = userLogKey(userIdFor(authToken),
                payload.getOrDefault("uuid", "default-user"));
        DelegationTasks.Task task = delegationTasks.submit(null, userKey, RequestScope.current(),
                () -> doChat(payload, authToken, apiKeyOverride, convId));
        return continueOrReturn(task);
    }

    /**
     * 浏览器取完数据回传。返回的仍是"下一份需求清单或最终回复"，所以前端不需要轮询。
     *
     * <p>校验三件事：键必须是服务器<b>此刻真的在等</b>的、结构必须自洽（id/slug 对得上、
     * 版本的环境要匹配请求的环境）、条数有上限。过了这几关才唤醒挂起的线程。
     */
    @PostMapping("/task/{taskId}/facts")
    public ResponseEntity<Object> submitFacts(@PathVariable String taskId, @RequestBody JsonNode body) {
        DelegationTasks.Task task = delegationTasks.find(taskId);
        if (task == null) {
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "任务不存在或已过期", "taskId", taskId));
        }
        if (task.isDone()) {
            return continueOrReturn(task);
        }
        logReceipt(task, body, applyFacts(task.context(), body));
        return continueOrReturn(task);
    }

    /**
     * 回执日志：要让"用户替我们做了多少、我们自己做多少"一眼看得见。
     *
     * <p>走 {@code MaaLog.runWithUser} 而不是直接 {@code MaaLog.user}：这次回调跑在
     * /facts 的请求线程上，那里没有用户上下文，直接写会落进总日志、丢归属。
     */
    private void logReceipt(DelegationTasks.Task task, JsonNode body, FactsTally tally) {
        int answered = body.path("answered").isArray() ? body.path("answered").size() : 0;
        int missing = body.path("missing").isArray() ? body.path("missing").size() : 0;
        int unavailable = body.path("unavailable").isArray() ? body.path("unavailable").size() : 0;
        JsonNode client = body.path("client");
        // 拒收明细只在真出现拒收时才拼，平时保持这行简短。
        // 用三元表达式一次成型：下面的 lambda 要求捕获的局部变量是 final。
        final String rejected = tally.rejected() > 0
                ? "，拒收 " + tally.rejected() + " 条（键已过期 " + tally.expired
                        + " / 结构不自洽 " + tally.shapeRejected + " / 条目非法 " + tally.malformed
                        + "），例：" + String.join("、", tally.samples)
                : "";
        MaaLog.runWithUser(task.userKey(), () -> MaaLog.user(
                "📥 委派回执 [任务 " + task.taskId() + "] 采纳 " + tally.accepted + " 条"
                        + "（带回 " + answered + " / 上游没有 " + missing + " / 没取到 " + unavailable + "）"
                        + rejected
                        + "；客户端自报发往 Modrinth " + client.path("requests").asInt(0) + " 次"
                        + "（命中限流 " + client.path("rateLimited").asInt(0) + " 次，耗时 "
                        + client.path("elapsedMs").asLong(0) + "ms）"));
    }

    @PostMapping("/abort")
    public String abortChat(@RequestBody Map<String, String> payload) {
        String uuid = payload.getOrDefault("uuid", "default-user");
        abortedSessions.put(uuid, true);
        // 挂起的线程最多要等 5 秒才自己醒来，用户点了终止不该再等——直接放行
        int woken = delegationTasks.cancelByUser(sanitizeUuid(uuid));
        System.out.println("🛑 收到用户终止指令 (volatile flag) — uuid=" + uuid
                + (woken > 0 ? "，已唤醒 " + woken + " 个等数据的任务" : ""));
        return "ok";
    }

    /** 任务跑完 → 返回最终文本；跑到缺口 → 返回需求清单 */
    private ResponseEntity<Object> continueOrReturn(DelegationTasks.Task task) {
        if (delegationTasks.awaitFirstDemand(task, TASK_WAIT_LIMIT_MS)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(needDataEnvelope(task));
        }
        try {
            String text = task.result().join();
            logSettlement(task);
            return textResponse(text);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = cause.getMessage() == null ? cause.toString() : cause.getMessage();
            MaaLog.error("委派任务异常: " + msg, cause);
            return textResponse("AI 调用异常: " + msg.substring(0, Math.min(200, msg.length())) + "\n请稍后重试。");
        }
    }

    /**
     * 结算日志：一轮构筑结束后，把"外包了多少 / 用户完成多少 / 我们自己兜了多少"一次报清。
     *
     * <p>这三个数正好对应关心的三件事，而且都能和 {@code <trace>.upstream} 对上：
     * <ul>
     *   <li>{@code dispatchedCount} 下发给浏览器的需求总数（= 外包量）</li>
     *   <li>{@code servedCount} 其中真由浏览器带回结果的数量</li>
     *   <li>{@code timedOutCount} 用户没赶上的，退回服务器自抓的数量</li>
     *   <li>{@code upstream.http} 服务器本轮真实发出的请求数</li>
     * </ul>
     */
    private void logSettlement(DelegationTasks.Task task) {
        DelegationContext ctx = task.context();
        // 读任务自己的作用域，不是当前这个 /facts 请求线程的（见 RequestScope.snapshotOf 注释）
        var up = task.scope() == null
                ? new RequestScope.Snapshot(0, 0, 0, 0)
                : task.scope().snapshotOf();
        MaaLog.runWithUser(task.userKey(), () -> MaaLog.user(
                "📊 委派结算 [任务 " + task.taskId() + "] 共 " + ctx.roundCount() + " 轮：下放 "
                        + ctx.dispatchedCount() + " 项 / 用户完成 " + ctx.servedCount() + " 项 / 用户没赶上、服务器自抓 "
                        + ctx.timedOutCount() + " 项；服务器本轮真实发出请求 " + up.http() + " 次"
                        + "（429 " + up.rateLimited() + " 次，令牌闸排队 " + up.throttleWaitMs() + "ms）"));
    }

    /** 下发给浏览器的需求清单 */
    private Map<String, Object> needDataEnvelope(DelegationTasks.Task task) {
        List<DelegationContext.Want> pending = task.context().pendingWants();
        int round = task.context().countDispatched(pending.size());
        logDispatch(task, pending, round);

        List<Map<String, Object>> wanted = new ArrayList<>();
        for (DelegationContext.Want want : pending) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", want.wantId());
            item.put("kind", want.kind().name().toLowerCase());
            item.put("key", want.key());
            if (want.kind() == DelegationContext.Kind.ENV_VERSION) {
                // 把环境拆出来给前端，免得它去猜服务器内部键格式；key 仍然原样带回用于配对
                String[] parts = want.key().split("\\|", 3);
                if (parts.length == 3) {
                    item.put("project", parts[0]);
                    item.put("mc", parts[1]);
                    item.put("loader", stripLoaderParam(parts[2]));
                }
            }
            wanted.add(item);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("taskId", task.taskId());
        envelope.put("stage", "need-data");
        envelope.put("perCallBudgetMs", delegationTasks.callBudgetMs());
        envelope.put("wanted", wanted);
        return envelope;
    }

    /** 下发日志：按事实种类分组，一眼看清这一轮外包了什么 */
    private void logDispatch(DelegationTasks.Task task, List<DelegationContext.Want> pending, int round) {
        Map<String, Integer> byKind = new LinkedHashMap<>();
        for (DelegationContext.Want w : pending) {
            byKind.merge(w.kind().name().toLowerCase(), 1, Integer::sum);
        }
        StringBuilder detail = new StringBuilder();
        byKind.forEach((k, n) -> detail.append(detail.length() == 0 ? "" : "、").append(k).append("×").append(n));
        int roundNo = round;
        MaaLog.runWithUser(task.userKey(), () -> MaaLog.user(
                "📤 委派下发 [任务 " + task.taskId() + "] 第 " + roundNo + " 轮："
                        + pending.size() + " 项（" + detail + "）→ 交给用户浏览器直连 Modrinth，服务器不发"));
    }

    /**
     * 把回执喂进上下文。
     *
     * @return 采纳的条数（用于观测：这个数越大，说明搬走的服务器请求越多）
     */
    private FactsTally applyFacts(DelegationContext ctx, JsonNode body) {
        FactsTally tally = new FactsTally();
        acceptFactList(ctx, body.path("answered"), Outcome.GOT, tally);
        acceptFactList(ctx, body.path("missing"), Outcome.ABSENT, tally);
        // 第三类：客户端没抓到（网络不通/被拦/批量端点整批失败）。
        // 这一类和 missing 必须分开——当成"上游确实没有"会让服务器得出"该模组不存在"的错误结论。
        acceptFactList(ctx, body.path("unavailable"), Outcome.TIMEOUT, tally);
        return tally;
    }

    /**
     * 回执处理的计数：采纳多少、以及<b>被拒的原因分类</b>。
     *
     * <p>为什么要分原因：实测出现过"客户端带回 12 条、一条都没采纳"，而当时的日志只有
     * "采纳 0 条"——三道闸（键过期 / 结构不自洽 / 条目非法）里是哪一道拦的，完全看不出来。
     * 这几个计数器就是为那一次加的。
     */
    private static final class FactsTally {
        int accepted;
        /** 键不在等待表里：等待线程已超时自抓、或任务已被取消 */
        int expired;
        /** 结构与请求不自洽（id/slug 对不上、版本的 mc/loader 不匹配） */
        int shapeRejected;
        /** 条目本身非法（kind 未知、key 为空或超长） */
        int malformed;
        /** 留最多 3 条被拒的 key 作样本——只看数字还是不知道拦了什么 */
        final List<String> samples = new ArrayList<>();

        void malformed(String key) {
            malformed++;
            sample(key);
        }

        void expired(String key) {
            expired++;
            sample(key);
        }

        void shapeRejected(String key) {
            shapeRejected++;
            sample(key);
        }

        int rejected() {
            return expired + shapeRejected + malformed;
        }

        private void sample(String key) {
            if (samples.size() >= 3) return;
            samples.add(key.length() > 60 ? key.substring(0, 60) + "…" : key);
        }
    }

    /**
     * @param mode GOT = 带数据的采纳；ABSENT = 上游明确没有；TIMEOUT = 没抓到，服务器自己抓
     */
    private void acceptFactList(DelegationContext ctx, JsonNode list, Outcome mode, FactsTally tally) {
        if (!list.isArray()) return;
        for (JsonNode fact : list) {
            if (tally.accepted >= MAX_FACT_RECORDS) break;
            DelegationContext.Kind kind = parseKind(fact.path("kind").asText(""));
            String key = fact.path("key").asText("");
            // 搜索类需求的 key 是完整 URL，比 slug/version_id 长得多，所以上限定在 1024
            if (kind == null || key.isBlank() || key.length() > 1024) {
                tally.malformed(key);
                continue;
            }
            // 只收"此刻真的有人在等"的键。客户端没法往上下文里塞服务器没要过的数据，
            // 晚到的回执（等待线程已超时自抓）也在这里被丢掉——那份数据已经没有消费者了。
            if (!ctx.isPending(kind, key)) {
                tally.expired(key);
                continue;
            }
            if (mode == Outcome.GOT) {
                JsonNode data = fact.path("data");
                if (!data.isObject() || !selfConsistent(kind, key, data)) {
                    tally.shapeRejected(key);
                    continue;
                }
                ctx.deliver(kind, key, data);
            } else if (mode == Outcome.ABSENT) {
                ctx.deliver(kind, key, null);
            } else {
                // 立刻放行等待线程让它自己抓，而不是干等到预算耗尽
                ctx.handBack(kind, key);
            }
            tally.accepted++;
        }
    }

    /** 包级可见：评测/单测直接钉住回执解析规则（与 looksLikeCriticDegeneration 同一套做法） */
    static DelegationContext.Kind parseKind(String raw) {
        for (DelegationContext.Kind k : DelegationContext.Kind.values()) {
            if (k.name().equalsIgnoreCase(raw)) return k;
        }
        return null;
    }

    /**
     * 结构与请求自洽吗。
     *
     * <p>注意这不是"验真"——下载地址、依赖列表这类内容客户端都能编。它只是挡住明显不对的东西，
     * 免得脏数据把流程带偏。真伪这一层我们本来就不防：数据只在本次任务里用，既不进共享缓存
     * 也不会传播给别人，最坏结果是发起者自己拿到一个错包。
     */
    static boolean selfConsistent(DelegationContext.Kind kind, String key, JsonNode data) {
        return switch (kind) {
            case PROJECT -> key.equals(data.path("id").asText("")) || key.equals(data.path("slug").asText(""));
            case VERSION -> key.equals(data.path("id").asText(""));
            case ENV_VERSION -> matchesEnv(key, data);
            case SEARCH -> data.path("hits").isArray();
        };
    }

    /** 版本对象必须真的声明支持请求里的 mc + loader，否则这次的"这个模组能装"就是空口无凭 */
    private static boolean matchesEnv(String key, JsonNode version) {
        String[] parts = key.split("\\|", 3);
        if (parts.length != 3) return false;
        if (version.path("id").asText("").isBlank()) return false;
        String mc = parts[1];
        String loader = stripLoaderParam(parts[2]);
        boolean mcOk = false;
        for (JsonNode gv : version.path("game_versions")) {
            if (mc.equals(gv.asText())) { mcOk = true; break; }
        }
        boolean loaderOk = false;
        for (JsonNode l : version.path("loaders")) {
            if (loader.equalsIgnoreCase(l.asText())) { loaderOk = true; break; }
        }
        return mcOk && loaderOk;
    }

    /** {@code ["forge"]} → {@code forge} */
    private static String stripLoaderParam(String loadersParam) {
        return loadersParam.replaceAll("[\\[\\]\"]", "").trim();
    }

    /**
     * 文本响应必须显式声明 UTF-8。
     *
     * <p>直接返回 String 时 Spring 的 StringHttpMessageConverter 默认字符集在有些链路上是
     * ISO-8859-1，中文会整段变问号——这种问题在本地看不出来、上线才炸，所以写死。
     */
    private static ResponseEntity<Object> textResponse(String text) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(text);
    }

    /** 检查是否已终止 */
    private boolean isAborted(String uuid) {
        return Boolean.TRUE.equals(abortedSessions.getOrDefault(uuid, false));
    }

    // ==========================================
    // 🔎 M1b: 版本兼容性校验（本地 versions 命中 -> live -> 负面记录闭环）
    // ==========================================

    /** 负面记录 key：按 (loader, mc, slug) 三维定位，TTL 逐条计算 */
    private static String negativeKey(String slug, String loader, String mcVersion) {
        return loader.toLowerCase() + "|" + mcVersion + "|" + slug;
    }

    private boolean isVersionNegative(String slug, String loader, String mcVersion) {
        String key = negativeKey(slug, loader, mcVersion);
        Long at = negativeVersionCache.get(key);
        if (at == null) return false;
        if (System.currentTimeMillis() - at > NEGATIVE_TTL.toMillis()) {
            negativeVersionCache.remove(key, at);
            return false;
        }
        return true;
    }

    private void rememberVersionNegative(String slug, String loader, String mcVersion) {
        if (negativeVersionCache.size() > NEGATIVE_MAX_ENTRIES) evictExpiredNegatives();
        negativeVersionCache.put(negativeKey(slug, loader, mcVersion), System.currentTimeMillis());
    }

    private void evictExpiredNegatives() {
        long now = System.currentTimeMillis();
        negativeVersionCache.entrySet().removeIf(e -> now - e.getValue() > NEGATIVE_TTL.toMillis());
    }

    /** 版本兼容性三态：确定兼容 / 确定不兼容 / 无法确定（网络或限流） */
    private enum Compat { OK, UNSUPPORTED, UNVERIFIED }

    /**
     * 校验单个候选的版本兼容性。
     *
     * <p>顺序：负面记录 → 本地 versions 列 → live 查询。
     * <p>只有 Modrinth <b>明确返回空</b>才写负面记录；网络/限流异常一律返回 UNVERIFIED，
     * 不写负面、不影响调用方对用户意图的判断（避免把"没查通"写成"不支持"）。
     */
    private Compat probeCompatibility(String slug, String loader, String mcVersion) {
        if (isVersionNegative(slug, loader, mcVersion)) return Compat.UNSUPPORTED;
        if (modrinthCacheService.isAvailable()
                && modrinthCacheService.hasKnownVersion(slug, loader, mcVersion)) {
            return Compat.OK;
        }
        try {
            JsonNode v = apiClient.getLatestCompatibleVersionBySlug(slug, mcVersion, loader.toLowerCase());
            if (v != null) {
                if (modrinthCacheService.isAvailable()) {
                    modrinthCacheService.recordVersion(slug, loader, mcVersion);
                }
                return Compat.OK;
            }
        } catch (ModrinthApiClient.UnavailableException e) {
            // 限流/5xx/超时重试耗尽：无法确定，不能当成"不支持"
            return Compat.UNVERIFIED;
        } catch (Exception e) {
            // 其它异常同样属于"无法确定"
            return Compat.UNVERIFIED;
        }
        // 到达这里说明 Modrinth 明确回答"该 loader + mc 下没有版本"
        rememberVersionNegative(slug, loader, mcVersion);
        return Compat.UNSUPPORTED;
    }

    /** 布尔便利方法：仅"确定兼容"返回 true（UNVERIFIED 视为未通过） */
    private boolean isCandidateCompatible(String slug, String loader, String mcVersion) {
        return probeCompatibility(slug, loader, mcVersion) == Compat.OK;
    }

    // ==========================================
    // 🧮 本地版本预过滤（P4-D）：用建库时落库的 game_versions 先否掉"确定不支持该 MC 版本"的候选，
    // 这些候选原本每个都要花一次 Modrinth 请求才能被否定掉。只做否定、fail-open。
    // ==========================================

    private List<CandidateMod> prefilterPoolByGameVersion(List<CandidateMod> pool, String mcVersion, String tag) {
        if (pool.isEmpty() || !modrinthCacheService.isGameVersionFilterReady()) return pool;
        Set<String> slugs = pool.stream().map(c -> c.slug)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> kept = modrinthCacheService.filterByGameVersion(slugs, mcVersion);
        if (kept.size() == slugs.size()) return pool;
        List<CandidateMod> out = pool.stream().filter(c -> kept.contains(c.slug)).toList();
        String note = "🧮 本地版本预过滤(" + tag + "): " + slugs.size() + " → " + out.size()
                + "，省下 " + (slugs.size() - out.size()) + " 次 Modrinth 查询 (mc=" + mcVersion + ")";
        System.out.println("   " + note);
        MaaLog.user(note);
        return new ArrayList<>(out);
    }

    /** 本地预过滤中被否定的子集（用户点名的核心模组走这条） */
    private Set<String> localRejectedSlugs(Collection<String> slugs, String mcVersion) {
        if (slugs.isEmpty() || !modrinthCacheService.isGameVersionFilterReady()) return Set.of();
        Set<String> kept = modrinthCacheService.filterByGameVersion(slugs, mcVersion);
        Set<String> rejected = new LinkedHashSet<>(slugs);
        rejected.removeAll(kept);
        return rejected;
    }

    // ==========================================
    // 📋 模组进出流水（P3-6）：任何一个模组进包/出包都必须留下可读原因，
    // 用于回溯"这个模组为什么在这儿"。同时写 stdout 与 MaaLog（用户级日志文件）。
    // ==========================================

    private void logModIn(String slug, String reason) {
        String line = "➕ [MOD-IN] " + slug + " | 原因: " + reason;
        System.out.println("   " + line);
        MaaLog.user(line);
    }

    private void logModOut(String slug, String reason) {
        String line = "➖ [MOD-OUT] " + slug + " | 原因: " + reason;
        System.out.println("   " + line);
        MaaLog.user(line);
    }

    /**
     * 按比例把"根模组预算"分配到各意图类别（P3-6）。
     *
     * <p>与 {@link #computeCategoryBudget} 的区别：那个算的是**候选池规模**（要过采样，给 Critic 挑），
     * 这个算的是**最终采纳上限**（配额即上限，不得超配）。
     */
    private Map<String, Integer> computeCategoryQuota(List<SearchIntent> intents, int rootBudget) {
        Map<String, Integer> quota = new LinkedHashMap<>();
        if (intents.isEmpty() || rootBudget <= 0) return quota;
        for (SearchIntent it : intents) {
            quota.put(it.category(), Math.max(1, (int) Math.round(rootBudget * it.ratio() / 100.0)));
        }
        return quota;
    }

    /**
     * 把"最终目标总数"折算成"根模组预算"（P3-6 的核心）。
     *
     * <p>用户说的 target_count 是最终总数，但送给 Critic 的是根模组数；
     * 两者之间隔着依赖穿透（实测平均膨胀 1.3~2.1 倍）。这里用历史观测的膨胀系数折算，
     * 避免"要 100 个最后出 177 个"。
     *
     * <p><b>小需求不折算</b>：目标只有十几个以内时，用户要的就是那几个具体模组
     * （加附属、加小工具），这类包实测膨胀接近 1.0，折算反而会"少给"。
     * 实测 A10「给机械动力加点附属」：目标 6、还差 5，按 EF=1.45 折成 3，最终只出 4 个（0.67 倍）。
     */
    private int rootBudgetOf(int needed, double expansionFactor) {
        if (needed <= 0) return 0;
        if (needed <= NO_DISCOUNT_THRESHOLD) return needed;
        int budget = (int) Math.round(needed / Math.max(1.0, expansionFactor));
        return Math.max(1, budget);
    }

    /** 小于等于这个需求量时不做膨胀折算（见 rootBudgetOf 说明） */
    private static final int NO_DISCOUNT_THRESHOLD = 10;

    /** 用户点名"给 X 加附属"却没给数量时，每个附属核心的额度（见 roundBudgetOf 说明） */
    private static final int ADDON_QUOTA_PER_CORE = 5;

    /**
     * 本轮允许新增的根模组数（审核闸门的<b>上界</b>）。
     *
     * <p>两种轮次共用同一个上界，但来源不同：
     * <ul>
     *   <li><b>补量轮</b>（{@code fillBudget > 0}）：上界就是 EF 折算后的补量预算，保持原有收敛口径。</li>
     *   <li><b>纯附属轮</b>（{@code fillBudget <= 0}，即模型给的 target_count 已经 ≤ 当前总数）：
     *       "还差 0 个"只说明总数达标，并不等于用户没要新增——"给机械动力加点附属"本身就是增量请求。
     *       旧实现把上界直接置 0，闸门里 {@code kept.size() >= 0} 恒成立，会把审核员挑中的附属<b>全部</b>丢掉
     *       （实测 A10：审核通过 23 → 采纳 0，最终包只剩 create，附属一个没进去）。
     *       现在按"每个附属核心 {@value #ADDON_QUOTA_PER_CORE} 个"给额度：用户没给数量时取这个小默认值，
     *       单核心总量（1 + 5 + 依赖）仍在窄意图 ≤10 的口径内。</li>
     * </ul>
     *
     * <p>只在 {@code fillBudget <= 0} 时启用附属额度，是为了不动补量轮既有的数量收敛；
     * 没点名附属（{@code addonCores == 0}）时返回 0，行为与旧实现一致。
     */
    static int roundBudgetOf(int fillBudget, int addonCores) {
        if (fillBudget > 0) return fillBudget;
        return Math.max(0, addonCores) * ADDON_QUOTA_PER_CORE;
    }

    // ==========================================
    // 🧮 类别配额（P3-3）：把 search_intents 里的 ratio 真正变成召回预算
    // 旧实现解析时直接用 parts[1] 丢掉了，比例字段纯装饰。
    // ==========================================

    /**
     * 解析并归一化 search_intents。
     *
     * <p>格式 {@code 类别:比例:词1,词2 | ...}；同一类别出现多次会被合并（比例相加、关键词去重），
     * 比例归一化到总和 100（全为 0 时均分），并保证每类至少 1%。
     */
    private List<SearchIntent> parseIntents(String intentsStr) {
        if (intentsStr == null || intentsStr.isBlank()) return List.of();
        Map<String, Integer> ratioByCategory = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> kwByCategory = new LinkedHashMap<>();

        for (String group : intentsStr.split("\\|")) {
            String[] parts = group.split(":");
            if (parts.length < 3) continue;
            String category = parts[0].trim();
            if (!ModrinthCacheService.CATEGORIES.contains(category)) continue;
            int ratio = 0;
            try {
                ratio = Math.max(0, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException ignored) {
                // 比例写错不影响召回，按 0 处理，后面统一归一化
            }
            ratioByCategory.merge(category, ratio, Integer::sum);
            LinkedHashSet<String> kws = kwByCategory.computeIfAbsent(category, k -> new LinkedHashSet<>());
            for (String kw : parts[2].split(",")) {
                String t = kw.trim();
                if (!t.isEmpty()) kws.add(t);
            }
        }
        if (ratioByCategory.isEmpty()) return List.of();

        int sum = ratioByCategory.values().stream().mapToInt(Integer::intValue).sum();
        List<SearchIntent> result = new ArrayList<>();
        for (var e : ratioByCategory.entrySet()) {
            List<String> kws = new ArrayList<>(kwByCategory.getOrDefault(e.getKey(), new LinkedHashSet<>()));
            if (kws.isEmpty()) continue;
            int pct = (sum <= 0)
                    ? Math.max(1, 100 / ratioByCategory.size())
                    : Math.max(1, (int) Math.round(100.0 * e.getValue() / sum));
            result.add(new SearchIntent(e.getKey(), pct, kws));
        }
        return result;
    }

    /**
     * 计算每个类别的候选池预算：目标配额 × 过采样，再按总上限等比压缩。
     *
     * @param needed 还需要补齐的模组数量（&gt;0）
     */
    /**
     * 把 Architect Tool（{@code adjustCategoryTargets}）下发的类别权重增量并入意图列表（P3-7）。
     *
     * <p><b>为什么需要这一步</b>：配额只决定"从已有候选池里最多采纳几个"，而候选池是按
     * {@code search_intents} 的关键词一组一组捞出来的。如果模型调了 {@code magic:+10}，
     * 但本轮 {@code search_intents} 里没有 magic 这一组，那 10 个名额就没有候选可挑——
     * 用户要的调整会被静默丢弃（旧实现里 {@code categoryTargets} 根本没有消费者，Tool 还回"已调整"）。
     *
     * <p><b>三层处置</b>：
     * <ol>
     *   <li>类别已有意图 → 权重相加（{@code ratio = base + delta}）；</li>
     *   <li>类别合法但本轮没有意图 → 用 {@link CategoryRegistry#hintsOf} 的**权威推荐检索词**补一组，
     *       让配额真正落得下去（这是"单一权威源"的又一次复用，而不是静默丢弃）；</li>
     *   <li>类别不合法 → 忽略并记录原因。</li>
     * </ol>
     *
     * <p>合并后归一化回总和 100、每类下限 1%（与 {@link #parseIntents} 的口径一致），
     * 于是 {@code computeCategoryQuota} / {@code computeCategoryBudget} / Critic 配额表天然共享同一份数据——
     * 不存在"两个真相源"。
     *
     * @param notesOut 每个类别被兜底/忽略的原因（写入日志与 trace）
     * @return 合并后的意图列表；无有效增量时原样返回
     */
    static List<SearchIntent> mergeCategoryTargets(List<SearchIntent> intents,
                                                   Map<String, Integer> targets,
                                                   Map<String, String> notesOut) {
        if (targets == null || targets.isEmpty()) return intents;
        Map<String, Integer> ratio = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> kws = new LinkedHashMap<>();
        for (SearchIntent it : intents) {
            ratio.merge(it.category(), it.ratio(), Integer::sum);
            kws.computeIfAbsent(it.category(), k -> new LinkedHashSet<>()).addAll(it.keywords());
        }

        int applied = 0;
        for (Map.Entry<String, Integer> e : targets.entrySet()) {
            String category = CategoryRegistry.normalize(e.getKey());
            int delta = e.getValue() == null ? 0 : e.getValue();
            if (category == null) {
                notesOut.put(e.getKey(), "不是合法类别，已忽略");
                continue;
            }
            if (!kws.containsKey(category)) {
                List<String> hints = CategoryRegistry.hintsOf(category);
                if (hints.isEmpty()) {
                    notesOut.put(category, "本轮没有该类别意图、且权威词表也没有推荐检索词，已忽略");
                    continue;
                }
                kws.put(category, new LinkedHashSet<>(hints));
                ratio.putIfAbsent(category, 0);
                notesOut.put(category, "本轮无该类别意图，已用权威推荐检索词兜底：" + String.join(",", hints));
            }
            ratio.merge(category, delta, Integer::sum);
            applied++;
        }
        if (applied == 0 || ratio.isEmpty()) return intents;

        int sum = 0;
        for (Map.Entry<String, Integer> e : ratio.entrySet()) {
            int v = Math.max(1, e.getValue());   // 下限 1%：被大幅下调的类别不会直接消失
            e.setValue(v);
            sum += v;
        }
        List<SearchIntent> merged = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ratio.entrySet()) {
            int pct = Math.max(1, (int) Math.round(100.0 * e.getValue() / sum));
            merged.add(new SearchIntent(e.getKey(), pct, new ArrayList<>(kws.get(e.getKey()))));
        }
        return merged;
    }

    /**
     * 计算每个类别的候选池预算：目标配额 × 过采样，再按总上限等比压缩。
     *
     * @param needed 还需要补齐的模组数量（&gt;0）
     */
    private Map<String, Integer> computeCategoryBudget(List<SearchIntent> intents, int needed) {
        Map<String, Integer> budget = new LinkedHashMap<>();
        if (intents.isEmpty() || needed <= 0) return budget;
        int total = 0;
        for (SearchIntent it : intents) {
            int quota = Math.max(1, (int) Math.round(needed * it.ratio() / 100.0));
            int b = Math.max(POOL_MIN_PER_CATEGORY, quota * POOL_OVERSAMPLE);
            budget.put(it.category(), b);
            total += b;
        }
        if (total > POOL_CAP) {
            double scale = (double) POOL_CAP / total;
            for (Map.Entry<String, Integer> e : budget.entrySet()) {
                e.setValue(Math.max(1, (int) Math.floor(e.getValue() * scale)));
            }
        }
        return budget;
    }

    /** 并发(≤5)校验候选池，只保留兼容模组；负面与 versions 命中不会产生 live 请求 */
    private List<CandidateMod> verifyPoolCompatibility(List<CandidateMod> pool,
                                                       String loader, String mcVersion) {
        if (pool.isEmpty()) return pool;
        // 先用本地 game_versions 否掉一批，减少后面的 live 校验量
        pool = prefilterPoolByGameVersion(pool, mcVersion, "候选池");
        if (pool.isEmpty()) return pool;
        String ctxKey = MaaLog.userKey();
        List<CandidateMod> verified = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger unverified = new java.util.concurrent.atomic.AtomicInteger(0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 不用局部信号量卡并发：它保护的"服务器出网速率"现在由全局令牌闸负责，
            // 而挂着许可去等浏览器会把批量切成一次 5 个，把往返次数放大好几倍
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (CandidateMod c : pool) {
                futures.add(ScopedExecutors.runAsync(() -> {
                    MaaLog.runWithUser(ctxKey, () -> {
                        try {
                            Compat compat = probeCompatibility(c.slug, loader, mcVersion);
                            if (compat == Compat.OK) verified.add(c);
                            else if (compat == Compat.UNVERIFIED) unverified.incrementAndGet();
                        } catch (Exception ignored) {}
                    });
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        String unverifiedNote = unverified.get() > 0
                ? "，另有 " + unverified.get() + " 个因网络/限流未能校验（不记入缓存，下次会重试）" : "";
        System.out.println("   ✔ 版本校验: 候选 " + pool.size() + " 个 → 兼容 " + verified.size()
                + " 个 (loader=" + loader + ", mc=" + mcVersion + ")" + unverifiedNote);
        MaaLog.user("版本校验: 候选 " + pool.size() + " → 兼容 " + verified.size()
                + " (loader=" + loader + ", mc=" + mcVersion + ")" + unverifiedNote);
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
            CandidateMod c = new CandidateMod(e.slug(), e.title(), e.description(), e.downloads());
            c.textScore.add(ADDON_BASE_SCORE);
            pool.add(c);
        }
        return verifyPoolCompatibility(scoreAddonPool(pool), loader, mcVersion);
    }

    /**
     * 附属池的基础文本分。
     *
     * <p>附属不是靠类别关键词召回的（靠"核心名 token 出现在标题/slug 里"），所以没有词元命中可算。
     * 给一个固定基础分，让热度系数照常生效——否则整池并列，排序只能靠下载量兜底（旧行为），
     * 也说不清"为什么这个附属排在前面"。
     */
    private static final double ADDON_BASE_SCORE = 1.0;

    /**
     * 附属池打分：<b>按附属池自己的最高下载量</b>归一。
     *
     * <p>不参与意图池的全局分母，是因为两者量纲不可比：意图池受用户冷门上限约束（可能全在 50 万以下），
     * 而附属池<b>有意不设下载量上限</b>（用户点名的附属跟着核心走，核心热门则附属必然热门），
     * 混在一个分母里会用一个千万级的 max 把意图池的热度系数整体压扁。
     */
    private static List<CandidateMod> scoreAddonPool(List<CandidateMod> pool) {
        long max = 1L;
        for (CandidateMod c : pool) max = Math.max(max, c.downloads);
        for (CandidateMod c : pool) {
            c.popNorm = RetrievalScorer.popularityNorm(c.downloads, max);
            c.finalScore = c.textScore.sum() * c.popNorm;
        }
        return pool;
    }

    /**
     * 本地建池（P3-3 / P4-A）：<b>按类别分别召回 → 全池定热度分母 → 算最终分 → 每类截预算 → 合并去重</b>。
     *
     * <p>与旧实现的三点差别：
     * <ol>
     *   <li>先召回<b>不截断</b>，等拿到"本轮全池最高下载量"再统一算分——否则小类别会被自己的
     *       小分母抬到 1.0，与大类别不可比（热度归一必须全池同一分母）。</li>
     *   <li>分数不再是"命中几个关键词"，而是
     *       {@code 字段权重 × 命中词元数 × 词长权重}，术语与口径见 {@link RetrievalScorer}。</li>
     *   <li>跨类别合并时按 slug 去重：一个模组可能同时挂着多个类别标签（create = technology +
     *       decoration + utility），旧实现会让同一个 slug 在送审池里出现多次（实测最后一轮
     *       1057 行里 21 种重复），既浪费 token，也让 Critic 看到两个不同的类别标签。</li>
     * </ol>
     *
     * <p>移除了旧的"每 30 条随机打乱"：那会让同一输入产生不同结果，与可复现评测的目标冲突；
     * 多样性现在由类别配额结构性保证。
     */
    private List<CandidateMod> buildFillerPoolLocal(List<SearchIntent> intents, Map<String, Integer> budget,
                                                    String loader, String mcVersion,
                                                    Set<String> existing, long maxDownloads) {
        Map<String, List<CandidateMod>> byCategory = new LinkedHashMap<>();
        for (SearchIntent intent : intents) {
            Map<String, CandidateMod> hitMap = new ConcurrentHashMap<>();
            for (String kw : intent.keywords()) {
                List<String> terms = RetrievalScorer.tokenize(kw);
                if (terms.isEmpty()) continue;
                // loader / 下载量上限过滤已下沉到 matchByKeyword 内部，保证"先过滤再截断"
                for (ModrinthCacheService.EntryMatch m : modrinthCacheService.matchByKeyword(
                        intent.category(), terms, loader, maxDownloads, PER_KEYWORD_SCAN_CAP)) {
                    String slug = m.entry().slug();
                    if (existing.contains(slug)) continue;
                    CandidateMod c = hitMap.computeIfAbsent(slug, k -> {
                        CandidateMod n = new CandidateMod(slug, m.entry().title(),
                                m.entry().description(), m.entry().downloads());
                        n.category = intent.category();
                        return n;
                    });
                    c.addMatch(m.score(), m.terms());
                }
            }
            byCategory.put(intent.category(), new ArrayList<>(hitMap.values()));
        }
        return finalizePool(byCategory, budget, loader, mcVersion, "本地");
    }

    /**
     * 建池收口（本地与在线两条召回路径共用）：定全池热度分母 → 算最终分 → 每类排序截预算 →
     * 合并按 slug 去重 → 全局排序截 {@link #POOL_CAP} → 版本校验。
     */
    private List<CandidateMod> finalizePool(Map<String, List<CandidateMod>> byCategory,
                                            Map<String, Integer> budget,
                                            String loader, String mcVersion, String tag) {
        long poolMaxDownloads = applyGlobalScores(byCategory.values());

        List<CandidateMod> merged = new ArrayList<>();
        for (Map.Entry<String, List<CandidateMod>> e : byCategory.entrySet()) {
            List<CandidateMod> catPool = e.getValue();
            catPool.sort(SCORE_THEN_DOWNLOADS);
            int cap = budget.getOrDefault(e.getKey(), 0);
            int taken = (cap > 0) ? Math.min(cap, catPool.size()) : catPool.size();
            System.out.println("   📊 类别配额 [" + e.getKey() + "] 预算=" + cap
                    + " → 命中 " + catPool.size() + "，取 " + taken);
            merged.addAll(catPool.subList(0, taken));
        }

        long distinctSlugs = merged.stream().map(c -> c.slug).distinct().count();
        List<CandidateMod> out = dedupeAndCap(merged);
        System.out.println("   🧮 " + tag + "建池: 类别命中合计 " + merged.size() + " → 跨类去重 "
                + (merged.size() - distinctSlugs)
                + " → 入池 " + out.size() + " 个 (全池最高下载量=" + poolMaxDownloads + ")");
        return verifyPoolCompatibility(out, loader, mcVersion);
    }

    /**
     * 全池打分：用<b>本轮所有类别合并后</b>的最高下载量当热度的统一分母，再算每条候选的最终分。
     *
     * @return 本轮全池最高下载量（仅用于日志）
     */
    static long applyGlobalScores(Collection<List<CandidateMod>> pools) {
        long poolMax = 1L;
        for (List<CandidateMod> list : pools) {
            for (CandidateMod c : list) poolMax = Math.max(poolMax, c.downloads);
        }
        for (List<CandidateMod> list : pools) {
            for (CandidateMod c : list) {
                c.popNorm = RetrievalScorer.popularityNorm(c.downloads, poolMax);
                c.finalScore = c.textScore.sum() * c.popNorm;
            }
        }
        return poolMax;
    }

    /**
     * 最终包内各类别的模组数（**用户指标**：他关心"成品包里到底有没有这类东西"）。
     *
     * <p>与 {@code approvedPerCat}（本轮类别检索的兑现情况）是两个不同的问题，别混用：
     * 后者只统计"以 类别(X) 身份入包"的模组。真实事故：一份包里魔法内容有 8 个
     * （铁魔法本体 + 7 个附属），但它们以"附属(核心)"身份入包，于是配额表显示 magic 0/7，
     * 还配了句话暗示用户"这类没配上"。
     *
     * <p>类别归属离线取：① 调用方传入的本轮候选池标签（slug → categories）
     * ② 本地 modrinth 缓存的 categories 列。都取不到的归入 {@code 未分类}，**不计入缺口**
     * —— 不能把"我们查不到"说成"包里没有"。
     */
    static Map<String, Integer> inPackCategoryCounts(Collection<String> packSlugs,
                                                     Map<String, Set<String>> poolCategories,
                                                     ModrinthCacheService cache) {
        Map<String, Integer> counts = new TreeMap<>();
        int unclassified = 0;
        for (String slug : packSlugs) {
            Set<String> cats = new LinkedHashSet<>();
            if (poolCategories != null) {
                cats.addAll(poolCategories.getOrDefault(slug, Set.of()));
            }
            if (cats.isEmpty() && cache != null && cache.isAvailable()) {
                ModrinthCacheService.ModEntry entry = cache.find(slug);
                if (entry != null && entry.categories() != null) {
                    entry.categories().stream()
                            .filter(yagen.waitmydawn.maa.model.CategoryRegistry.CATEGORY_SET::contains)
                            .forEach(cats::add);
                }
            }
            if (cats.isEmpty()) {
                unclassified++;
                continue;
            }
            for (String c : cats) counts.merge(c, 1, Integer::sum);   // 多标签模组在每个类别里都算一个
        }
        if (unclassified > 0) counts.put("未分类", unclassified);
        return counts;
    }

    /**
     * 跨类合并去重 + 全局排序 + 截断。
     *
     * <p>去重是必需的：一个模组可能同时挂着多个类别标签，会在多个桶里各被召回一次；
     * 保留<b>最终分最高</b>的那一份（顺带带走那份的类别标签，让"类别配额兑现"统计口径一致）。
     */
    static List<CandidateMod> dedupeAndCap(List<CandidateMod> merged) {
        Map<String, CandidateMod> deduped = new LinkedHashMap<>();
        for (CandidateMod c : merged) {
            CandidateMod kept = deduped.get(c.slug);
            if (kept == null || c.finalScore > kept.finalScore) deduped.put(c.slug, c);
        }
        List<CandidateMod> out = new ArrayList<>(deduped.values());
        out.sort(SCORE_THEN_DOWNLOADS);
        if (out.size() > POOL_CAP) out = new ArrayList<>(out.subList(0, POOL_CAP));
        return out;
    }

    // ==========================================
    // 🛡️ core 真实性闸门 + 版本预检前移 + 平替守卫（无别名表：重复覆盖语义）
    // ==========================================

    /** 平替结果（包级可见，便于单测直接断言挑选行为） */
    static final class RescueResult {
        String replacement;
        boolean duplicate;
        String duplicateWith;   // 命中的"已保留模组"slug（用于可读日志）
    }

    private record UnresolvedMod(String slug, boolean needCommunity) {
    }

    /**
     * 阶段 0（真实性/版本闸门）对"点名模组"的处理流水，用于**给用户看得见的交代**与 trace 断言。
     *
     * <p>为什么要有它：平替/剔除以前只写日志——用户看不到"你要的 X 被换成了 Y""你的 Z 被丢了"，
     * 而这两件事都改变了用户的包（评测也无法断言"系统有没有如实告知"）。
     *
     * @param slug     被处理的模组（原名字）
     * @param action   REPLACED（平替）｜ DROPPED（剔除）｜ DUPLICATE（同一个模组的别名）
     * @param detail   平替后的 slug / 剔除原因 / 与谁重复
     * @param fromPack true = 这个模组来自用户已有的包（currentMods），false = 模型本轮新增的
     */
    record ModDelta(String slug, String action, String detail, boolean fromPack) {
    }

    /**
     * 真实性/兼容性统一闸门：
     * 兼容 → 保留；假名或"真名无版本" → 一律进入平替守卫；仍无结果才剔除。
     */
    private Set<String> sanitizePackInitialMods(Set<String> slugs, String loader, String mcVersion,
                                                Set<String> unverifiedOut, List<ModDelta> deltasOut,
                                                Set<String> userPackNorms) {
        Set<String> sanitized = new LinkedHashSet<>();
        Set<String> existingNorms = new HashSet<>();
        Set<String> seenInput = new HashSet<>();
        List<UnresolvedMod> unresolved = new ArrayList<>();
        // 本地已落库的项目级版本数据可直接否掉一批，省掉对应的 API 查询
        Set<String> localRejects = localRejectedSlugs(slugs, mcVersion);
        for (String slug : slugs) {
            String norm = normKey(slug);
            // existingNorms 只装"真正保留"的 slug；输入内去重用独立的 seenInput，
            // 避免 unresolved 项被误判成"与自身重复"而跳过平替
            if (!seenInput.add(norm)) {
                logDroppedMod(slug, "与本次输入中规范化重复的模组重复，剔除变体");
                deltasOut.add(new ModDelta(slug, "DUPLICATE", "与本次输入里同名模组重复", userPackNorms.contains(norm)));
                continue;
            }
            if (!isRealSlug(slug)) {
                unresolved.add(new UnresolvedMod(slug, false));
                continue;
            }
            if (localRejects.contains(slug)) {
                logDroppedMod(slug, "本地缓存显示该项目未声明支持 " + mcVersion + "（未发请求，直接进入平替判定）");
                unresolved.add(new UnresolvedMod(slug, true));
                continue;
            }
            Compat compat = probeCompatibility(slug, loader, mcVersion);
            if (compat == Compat.OK) {
                sanitized.add(slug);
                existingNorms.add(norm);
            } else if (compat == Compat.UNVERIFIED) {
                // 网络/限流导致"无法确定"：保留用户点名的模组，不做平替也不剔除，只记录
                sanitized.add(slug);
                existingNorms.add(norm);
                unverifiedOut.add(slug);
                logUnverifiedMod(slug, loader, mcVersion);
            } else {
                unresolved.add(new UnresolvedMod(slug, true)); // 真名无版本 → 找社区/移植版
            }
        }
        for (UnresolvedMod u : unresolved) {
            RescueResult r = rescueUnknownSlug(u.slug(), u.needCommunity(), loader, mcVersion, existingNorms);
            boolean fromPack = userPackNorms.contains(normKey(u.slug()));
            if (r.duplicate) {
                logDroppedMod(u.slug(), r.duplicateWith != null
                        ? "与已保留模组 [" + r.duplicateWith + "] 规范化重复，保留现有项、剔除别名"
                        : "经平替判定为包内已有真实模组的重复/别名，原项剔除");
                deltasOut.add(new ModDelta(u.slug(), "DUPLICATE",
                        r.duplicateWith != null ? "与包内 " + r.duplicateWith + " 是同一个模组" : "包内已有同一个模组",
                        fromPack));
            } else if (r.replacement != null) {
                if (existingNorms.add(normKey(r.replacement))) {
                    sanitized.add(r.replacement);
                    System.out.println("🔄 平替 [" + u.slug() + "] -> [" + r.replacement + "]");
                    MaaLog.user("平替: " + u.slug() + " -> " + r.replacement);
                    deltasOut.add(new ModDelta(u.slug(), "REPLACED", r.replacement, fromPack));
                } else {
                    logDroppedMod(u.slug(), "候选 " + r.replacement + " 已在包内，按重复处理");
                    deltasOut.add(new ModDelta(u.slug(), "DUPLICATE", "平替候选 " + r.replacement + " 已在包内", fromPack));
                }
            } else {
                String reason = u.needCommunity()
                        ? "在 " + loader + "/" + mcVersion + " 没有可用版本，也没找到合适的社区移植版"
                        : "Modrinth 上没有这个模组，也没找到合理的平替";
                logDroppedMod(u.slug(), u.needCommunity()
                        ? "真实模组无 " + loader + "/" + mcVersion + " 版本且无合适社区/移植版，剔除"
                        : "LLM 产出名称在 Modrinth 不存在且无合理平替，剔除");
                deltasOut.add(new ModDelta(u.slug(), "DROPPED", reason, fromPack));
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
     * 平替候选。
     *
     * <p>{@code hitTokens} 存的是"命中了哪些强词"而不是个数：判断够不够格要看命中了什么词——
     * 只命中 {@code expansion}/{@code plus} 这类通用后缀词，不足以证明"这是同一个模组"。
     */
    record ReplacementCandidate(String slug, String title, long downloads,
                                String categoriesCsv, Set<String> hitTokens, boolean affixHit) {
        int strongHits() {
            return hitTokens.size();
        }
    }

    /**
     * 通用后缀词：满大街都是，单靠它们命中不能当作"同一个模组"的证据。
     *
     * <p>来自实测（坏例 B15）：`thermal-expansion` 只命中了 `expansion`，却被判成
     * `doggy-talents-nexts-community-skin-expansion` 的平替——真正有辨识度的 `thermal` 根本没命中。
     */
    private static final Set<String> REPLACE_GENERIC_TERMS = Set.of(
            "expansion", "expanded", "extended", "extend", "addition", "additions", "plus", "extra",
            "more", "new", "next", "overhaul", "remaster", "remastered", "classic", "ultimate",
            "better", "improved", "improvements", "improvement", "patch", "patches", "tweaks",
            "essentials", "utilities", "features", "content", "stuff", "things", "edition");

    /**
     * 过度通用的"品牌前缀词"：在模组名里出现频率太高，单独命中不构成"同一个模组"的证据。
     *
     * <p>同样来自实测：`ender-io` 只命中 `ender` 就被判成 `l_enders-cataclysm` 的平替，
     * `travelers-backpack` 只命中 `travelers` 就被判成 `travelers-titles`——两个都是完全无关的模组。
     * 这类词（ender/nether/magic/travelers/simple/better…）满大街都是，命中只能说明"题材相近"，
     * 而在这个位置塞错模组的代价是**用户的包被悄悄换成一个不相关的模组**，所以宁可放弃平替。
     */
    private static final Set<String> REPLACE_OVERCOMMON_TERMS = Set.of(
            "ender", "nether", "overworld", "traveler", "travelers", "travellers", "explorer",
            "adventure", "magic", "magical", "tech", "technology", "world", "sky", "ocean",
            "simple", "easy", "better", "tiny", "mini", "mega", "ultra", "super", "epic",
            "fancy", "cool", "resource", "resources", "block", "blocks", "item", "items",
            "mystical", "fantasy", "medieval", "modern", "ancient", "wild", "lost");

    /** 平替候选排序：<b>强词命中数 → 词缀命中 → 下载量</b>（词缀只是"同等强度"时的次级优先） */
    private static final Comparator<ReplacementCandidate> REPLACEMENT_ORDER = (a, b) -> {
        int strong = Integer.compare(b.strongHits(), a.strongHits());
        if (strong != 0) return strong;
        int affix = Boolean.compare(b.affixHit(), a.affixHit());
        if (affix != 0) return affix;
        return Long.compare(b.downloads(), a.downloads());
    };

    /**
     * 平替守卫（复合打分，无别名表）：<b>预筛 → 排序 → 重复判定 → 质量闸门</b>。
     *
     * <p>规则：
     * <ol>
     *   <li><b>强词淘汰</b>：标题/slug 未命中任何强词 → 丢弃；</li>
     *   <li><b>辨识度约束</b>：命中的强词里至少有一个不是通用后缀词（挡掉"只命中 expansion"）；</li>
     *   <li><b>重复判定</b>：规范化真身已在包内 → 判定"这是重复/别名"，剔除原项、不新增；</li>
     *   <li><b>质量闸门</b>：下载量 < 1000 不选；非食物需求不选食物类候选（防串味）；</li>
     *   <li>以上都不满足 → 返回 null（宁可放弃，也不塞一个无关模组进包）。</li>
     * </ol>
     *
     * <p><b>2026-09-15 修的两个缺陷（坏例 B15，日志实锤）</b>：
     * <ol>
     *   <li>排序原本把<b>词缀命中</b>放在<b>强词命中</b>之前——只要候选名里带 community/port 之类的词，
     *       哪怕与原名毫无关系也能排第一（thermal-expansion → doggy-talents-…）；现在词缀降级为次级优先。</li>
     *   <li>重复判定原本排在质量闸门<b>之前</b>，且是在"全 loader 桶"上扫描：当原名一个强词都没命中时，
     *       循环会一路扫到"下载量最高又恰好在包里"的那个模组，把它当成"你要的其实已经在包里了"
     *       （实测 iron-chests / ferritecore / biomesoplenty 被误判成 doggy-talents / cloth-config 的重复）。
     *       现在先预筛出"有辨识度强词命中"的候选，重复判定只在预筛之后进行。</li>
     * </ol>
     *
     * @param debugOut 诊断计数（强词可选项 / 低下载跳过 / 食物类跳过），仅用于"平替无结果"日志
     */
    static RescueResult pickReplacement(String original, List<ReplacementCandidate> candidates,
                                        Set<String> existingNorms, Map<String, Integer> debugOut) {
        RescueResult result = new RescueResult();
        String origNorm = normKey(original);
        List<ReplacementCandidate> strongPool = new ArrayList<>();
        for (ReplacementCandidate c : candidates) {
            if (normKey(c.slug()).equals(origNorm)) continue;      // 跳过自身
            if (c.strongHits() <= 0) continue;                     // 强词淘汰
            if (!hasDistinctiveHit(c)) continue;                   // 只有通用词命中不算
            strongPool.add(c);
        }
        strongPool.sort(REPLACEMENT_ORDER);
        debugOut.put("强词可选项", strongPool.size());

        for (ReplacementCandidate c : strongPool) {
            if (existingNorms.contains(normKey(c.slug()))) {       // 规范化真身已在包内 → 重复/别名
                result.duplicate = true;
                result.duplicateWith = c.slug();
                return result;
            }
            if (c.downloads() < MIN_REPLACEMENT_DOWNLOADS) {
                debugOut.merge("低下载跳过", 1, Integer::sum);
                continue;
            }
            if (!looksFoodish(original) && isFoodishCandidate(c.title(), c.categoriesCsv())) {
                debugOut.merge("食物类跳过", 1, Integer::sum);
                continue;
            }
            result.replacement = c.slug();
            return result;
        }
        return result;
    }

    /**
     * 是否命中了"有辨识度"的强词：只命中通用后缀词（expansion/plus…）或过度通用的品牌词（ender/travelers…）都不算。
     * 版本号粘连时先去掉尾部数字再判（mobs1211 → mobs）。
     */
    private static boolean hasDistinctiveHit(ReplacementCandidate c) {
        for (String t : c.hitTokens()) {
            String base = t.replaceAll("\\d+$", "");
            if (!REPLACE_GENERIC_TERMS.contains(base) && !REPLACE_OVERCOMMON_TERMS.contains(base)) return true;
        }
        return false;
    }

    /**
     * 平替守卫入口：收集候选（本地缓存桶 / live 检索）→ 交给 {@link #pickReplacement} 挑选。
     */
    private RescueResult rescueUnknownSlug(String original, boolean needCommunity, String loader,
                                           String mcVersion, Set<String> existingNorms) {
        RescueResult result = new RescueResult();
        if (existingNorms.contains(normKey(original))) {
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

        Map<String, Integer> debug = new LinkedHashMap<>();
        result = pickReplacement(original, candidates, existingNorms, debug);
        if (result.replacement == null && !result.duplicate) {
            String line = "平替无结果: " + original + " | 候选总数=" + candidates.size()
                    + " 强词可选项=" + debug.getOrDefault("强词可选项", 0)
                    + " 低下载跳过=" + debug.getOrDefault("低下载跳过", 0)
                    + " 食物类跳过=" + debug.getOrDefault("食物类跳过", 0);
            System.out.println("   🔎 " + line);
            MaaLog.user(line);
        }
        return result;
    }

    /** 单条候选打分（静态纯函数，便于单测）：记录命中了哪些强词 + 是否命中社区化词缀 */
    static ReplacementCandidate scoreCandidate(String original, Set<String> originalAffixes,
                                               boolean needCommunity, String slug, String title,
                                               long downloads, String categoriesCsv) {
        Set<String> candidateWords = wordSet(slug + " " + title);
        Set<String> hitTokens = new LinkedHashSet<>();
        for (String t : strongTokens(original)) {
            if (candidateWords.contains(t)) {
                hitTokens.add(t);
                continue;
            }
            // 兼容"版本号粘连"写法：mobs1211 应能匹配候选词 mobs（mobs(1.21.1) 类）
            String base = t.replaceAll("\\d+$", "");
            if (!base.equals(t) && base.length() >= 3 && candidateWords.contains(base)) {
                hitTokens.add(t);
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
        return new ReplacementCandidate(slug, title, downloads, categoriesCsv, hitTokens, affixHit);
    }

    private List<String[]> liveSearchCandidates(String name, String loader, String mcVersion) {
        List<String[]> hits = new ArrayList<>();
        try {
            String facetsRaw = String.format("[[\"project_type:mod\"], [\"loaders:%s\"], [\"versions:%s\"]]",
                    loader, mcVersion);
            // 收口到 ModrinthApiClient：统一走全局令牌闸与 429 退避（原先这里裸发，享受不到任何保护）
            JsonNode res = apiClient.search(name, 10, facetsRaw);
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

    /** 网络/限流导致无法确定版本：既不剔除也不平替，只记录，等下一轮重试 */
    private void logUnverifiedMod(String slug, String loader, String mcVersion) {
        String reason = "无法校验 " + loader + "/" + mcVersion + " 兼容性（网络或限流），本轮保留原项，未写入缓存";
        System.out.println("❔ 存疑 [" + slug + "]：" + reason);
        MaaLog.user("存疑 " + slug + "：" + reason);
    }

    /** expand_addons 里的核心也必须真实且兼容；LLM 编造名走同一平替守卫 */
    private List<String> sanitizeExpandCores(List<String> cores, String loader, String mcVersion,
                                             Set<String> existing, Set<String> unverifiedOut,
                                             List<ModDelta> deltasOut, Set<String> userPackNorms) {
        List<String> result = new ArrayList<>();
        Set<String> existingNorms = new HashSet<>();
        for (String s : existing) existingNorms.add(normKey(s));
        Set<String> localRejects = localRejectedSlugs(cores, mcVersion);
        for (String core : cores) {
            boolean real = isRealSlug(core);
            boolean localReject = real && localRejects.contains(core);
            if (localReject) {
                logDroppedMod(core, "expand 核心未声明支持 " + mcVersion + "（本地判定，未发请求）");
            }
            // 本地已否定的项直接判 UNSUPPORTED，省掉一次 live 查询，但仍走下面的平替抢救
            Compat compat = !real ? Compat.UNSUPPORTED
                    : (localReject ? Compat.UNSUPPORTED : probeCompatibility(core, loader, mcVersion));
            if (compat == Compat.OK) {
                result.add(core);
                continue;
            }
            if (compat == Compat.UNVERIFIED) {
                // 无法确定时不改写用户意图：保留原核心，不做平替
                result.add(core);
                unverifiedOut.add(core);
                logUnverifiedMod(core, loader, mcVersion);
                continue;
            }
            boolean needCommunity = real;
            RescueResult r = rescueUnknownSlug(core, needCommunity, loader, mcVersion, existingNorms);
            boolean fromPack = userPackNorms.contains(normKey(core));
            if (r.duplicate) {
                logDroppedMod(core, r.duplicateWith != null
                        ? "expand 核心与已保留模组 [" + r.duplicateWith + "] 规范化重复"
                        : "expand 核心为包内已有模组的重复/别名");
                deltasOut.add(new ModDelta(core, "DUPLICATE",
                        r.duplicateWith != null ? "与包内 " + r.duplicateWith + " 是同一个模组" : "包内已有同一个模组",
                        fromPack));
            } else if (r.replacement != null) {
                if (existingNorms.add(normKey(r.replacement))) {
                    System.out.println("🔄 expand 平替 [" + core + "] -> [" + r.replacement + "]");
                    MaaLog.user("expand 平替: " + core + " -> " + r.replacement);
                    result.add(r.replacement);
                    deltasOut.add(new ModDelta(core, "REPLACED", r.replacement, fromPack));
                } else {
                    logDroppedMod(core, "expand 候选已在包内，按重复处理");
                    deltasOut.add(new ModDelta(core, "DUPLICATE", "平替候选 " + r.replacement + " 已在包内", fromPack));
                }
            } else {
                logDroppedMod(core, real
                        ? "expand 核心无 " + loader + "/" + mcVersion + " 版本且无合适社区/移植版"
                        : "expand 核心不存在且无合理平替");
                deltasOut.add(new ModDelta(core, "DROPPED", real
                        ? "在 " + loader + "/" + mcVersion + " 没有可用版本，也没找到合适的社区移植版"
                        : "Modrinth 上没有这个模组，也没找到合理的平替", fromPack));
            }
        }
        return result;
    }

    private String processAndAssembleBlueprint(String aiBlueprint, String currentMods, String prompt,
                                               String effectiveApiKey, String uuid, PackSessionState state,
                                                Set<String> excludedSlugs,
                                                AiAgentService.AgentCallResult architectCall,
                                                String defaultMc, String defaultLoader,
                                                yagen.waitmydawn.maa.model.EnvIntent envIntent) {
        try {
            // F01：环境优先级 = 用户本轮明确点名 > 模型回填 > 前端当前环境（而不是硬编码 neoforge/1.21.1）。
            // 用户点名的必须压过模型：否则模型一句"我按现有环境来"就能把用户的切换要求顶掉（真实事故）。
            String loader = envIntent != null && envIntent.loader() != null
                    ? envIntent.loader()
                    : extractTag(aiBlueprint, "loader",
                        defaultLoader == null || defaultLoader.isBlank() ? "neoforge" : defaultLoader.trim());
            String mcVersion = envIntent != null
                    ? envIntent.mcVersion()
                    : extractTag(aiBlueprint, "mc",
                        defaultMc == null || defaultMc.isBlank() ? "1.21.1" : defaultMc.trim());
            int targetCount = Integer.parseInt(extractTag(aiBlueprint, "target_count", "100"));
            long maxDownloads = Long.parseLong(extractTag(aiBlueprint, "max_downloads", "2100000000"));

            String coreModsStr = extractTag(aiBlueprint, "core_mods", "");
            String expandAddonsStr = extractTag(aiBlueprint, "expand_addons", "");
            String searchIntentsStr = extractTag(aiBlueprint, "search_intents", "");
            // 🏷️ P3-2：类别合法性闸门 —— 归一化别名，未知类别整组丢弃（绝不回退成全库检索）
            Set<String> invalidCategories = new LinkedHashSet<>();
            searchIntentsStr = normalizeSearchIntents(searchIntentsStr, invalidCategories);
            // 🧮 P3-3：把 ratio 解析成结构化的类别配额（旧实现解析后直接丢弃 parts[1]，比例纯装饰）
            List<SearchIntent> intents = parseIntents(searchIntentsStr);
            // 🧮 P3-7：把 Tool 下发的类别权重增量并入**同一份**配额（单一真相源）。
            // 此后 computeCategoryQuota / computeCategoryBudget / Critic 配额表全部吃这份 intents。
            Map<String, String> categoryWeightNotes = new LinkedHashMap<>();
            int intentsBeforeMerge = intents.size();
            intents = mergeCategoryTargets(intents, state.getCategoryTargets(), categoryWeightNotes);
            int categoryWeightApplied = intents.size() - intentsBeforeMerge;
            if (!categoryWeightNotes.isEmpty()) {
                String note = "🧮 类别权重（Tool adjustCategoryTargets）已并入配额: " + categoryWeightNotes;
                System.out.println(note);
                MaaLog.user(note);
            }

            Set<String> initialMods = new LinkedHashSet<>();
            // 用户已有包的规范化集合：只用于标注"这条调整动的是你包里的东西，还是本轮新增的"
            Set<String> userPackNorms = new HashSet<>();
            if (currentMods != null && !currentMods.trim().isEmpty()) {
                List<String> packSlugs = cleanSlugs(currentMods);
                initialMods.addAll(packSlugs);
                for (String s : packSlugs) userPackNorms.add(normKey(s));
            }
            // 已被用户在图谱中删除的模组，不应再作为已有模组进入本轮构筑
            initialMods.removeAll(excludedSlugs);

            if (!coreModsStr.isEmpty()) {
                List<String> aiCoreMods = cleanSlugs(coreModsStr);
                // 已有模组由 currentMods 带入并已被 Java 保留，这里只补"本次新增"的核心；
                // 上限 30 也只作用于新增项——旧实现直接取前 30 个，LLM 复述已有清单时会把真正的新模组挤出配额丢掉
                List<String> novelCoreMods = new ArrayList<>();
                for (String s : aiCoreMods) {
                    if (initialMods.contains(s) || novelCoreMods.contains(s)) continue;
                    novelCoreMods.add(s);
                }
                int limit = Math.min(novelCoreMods.size(), 30);
                initialMods.addAll(novelCoreMods.subList(0, limit));
                System.out.println("🎯 规划师新增核心模组 " + limit + " 个 (core_mods 共 " + aiCoreMods.size()
                        + " 个，其中 " + (aiCoreMods.size() - novelCoreMods.size()) + " 个已在包内或重复)；"
                        + "当前总计 " + initialMods.size() + " 个");
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
            // 网关卡在三态上：只有"确定不兼容"才走平替/剔除，"无法确定"保留原项
            Set<String> unverifiedMods = new LinkedHashSet<>();
            // 点名模组的调整流水：既要写进用户回复（诚实上报），也要进 <trace>（评测可断言）
            List<ModDelta> modDeltas = new ArrayList<>();
            // 注意用 intents 而不是 searchIntentsStr：Tool 可能兜底引入了原 XML 里没有的类别
            boolean needFallback = !intents.isEmpty() || !expandAddonsStr.isEmpty();
            if (needFallback) {
                initialMods = sanitizePackInitialMods(initialMods, loader, mcVersion, unverifiedMods,
                        modDeltas, userPackNorms);
            }

            int needed = targetCount - initialMods.size();
            // 🧮 P3-6：target_count 是"最终总数"，但发给 Critic 的必须是"根模组数"——
            // 中间隔着依赖穿透（实测平均膨胀 1.3~2.1 倍）。这里按历史观测折算，避免"要 100 出 177"。
            double expansionFactor = expansionTracker.factor(loader, mcVersion);
            int rootBudget = rootBudgetOf(needed, expansionFactor);
            // 本轮闸门上界：补量轮 = EF 折算后的预算；纯附属轮（target_count 已达标）= 附属额度（不能是 0）
            int addonCores = expandAddonsStr.isEmpty() ? 0
                    : (int) Arrays.stream(expandAddonsStr.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).count();
            int roundBudget = roundBudgetOf(rootBudget, addonCores);
            if (needed > 0) {
                String note = "📐 数量预算: 目标总数 " + targetCount + "，当前 " + initialMods.size()
                        + "，还差 " + needed + " → 折算根模组预算 " + rootBudget
                        + " (膨胀系数 EF=" + String.format("%.2f", expansionFactor) + ")";
                System.out.println(note);
                MaaLog.user(note);
            } else if (roundBudget > 0) {
                // 纯附属轮：模型给的 target_count ≤ 当前总数，但用户明确点了附属，本轮额度来自附属而非补量
                String note = "🧩 纯附属轮: 目标总数 " + targetCount + " 已达标（当前 " + initialMods.size()
                        + "），按 " + addonCores + " 个附属核心 × " + ADDON_QUOTA_PER_CORE
                        + " 给出本轮新增额度 " + roundBudget;
                System.out.println(note);
                MaaLog.user(note);
            }
            // P3-3：类别配比未兑现的项（在 Critic 之后填充，用于结果说明）
            List<String> quotaShortfalls = new ArrayList<>();
            /**
             * slug → 本轮候选池给的类别标签（外层持有，供"包内类别构成"统计使用）。
             * 为什么不能等统计时再查池：{@code poolIndex} 声明在更内层的块里，出了块就拿不到；
             * 而"包内构成"要在回复拼装阶段（更外层）才算，所以在这里留一份。
             */
            Map<String, String> poolCategoryOfSlug = new HashMap<>();
            // 过程指标：Critic 调用结果（可能为 null，例如本轮不需要审核）
            AiAgentService.AgentCallResult criticCall = null;
            /** Critic 输出是否退化（标签缺失/未闭合）：退化时必须由补位兜底 + 如实告知（坏例 B16） */
            boolean criticDegraded = false;
            int candidatePoolSize = 0;
            // P3-6 审核结果闸门的过程计数
            int approvedKept = 0, droppedNotInPool = 0, droppedOverQuota = 0, droppedDuplicate = 0, backfilledCount = 0;
            // 本轮是否真的召回过"意图候选池"：没召回过的类别谈不上"配比未兑现"，不能对用户虚报
            boolean fillerFetched = false;

            // ★ 空图谱模式: 无核心模组 + 无搜索意图 + 无已有模组 → 直接返回空图谱
            if (initialMods.isEmpty() && searchIntentsStr.isEmpty() && expandAddonsStr.isEmpty()) {
                // 区分两种完全不同的情况：用户要空图谱 vs 模型格式违规（只写文字没输出 XML）
                boolean formatViolation = aiBlueprint.indexOf("<core_mods>") < 0
                        && aiBlueprint.indexOf("<search_intents>") < 0
                        && aiBlueprint.indexOf("<target_count>") < 0;
                System.out.println(formatViolation
                        ? "⚠️ 模型未输出任何构筑标签（格式违规）——本轮不会产出模组清单"
                        : "📋 空图谱模式 — 用户自行构建，跳过阶段2-4");
                String echoMc = extractTag(aiBlueprint, "mc",
                        defaultMc == null || defaultMc.isBlank() ? "1.21.1" : defaultMc.trim());
                String echoLoader = extractTag(aiBlueprint, "loader",
                        defaultLoader == null || defaultLoader.isBlank() ? "neoforge" : defaultLoader.trim());
                String cleanReply = aiBlueprint.replaceAll("<target_count>[\\s\\S]*?</target_count>", "")
                        .replaceAll("<max_downloads>[\\s\\S]*?</max_downloads>", "")
                        .replaceAll("<core_mods>[\\s\\S]*?</core_mods>", "")
                        .replaceAll("<expand_addons>[\\s\\S]*?</expand_addons>", "")
                        .replaceAll("<search_intents>[\\s\\S]*?</search_intents>", "")
                        .replaceAll("<mc>[\\s\\S]*?</mc>", "")
                        .replaceAll("<loader>[\\s\\S]*?</loader>", "")
                        + "\n<mc>" + echoMc + "</mc>\n<loader>" + echoLoader + "</loader>";
                String emptyReply = appendOpsAndMods(cleanReply, state, "\n<mods></mods>");
                if (formatViolation) {
                    emptyReply = emptyReply + "\n\n⚠️ 本轮模型只给出了说明文字，没有按要求输出结构化标签"
                            + "（<core_mods>/<search_intents>/<target_count>），所以没有生成模组清单。"
                            + "请再说一次，或把需求写得更具体一些。";
                    MaaLog.user("格式违规：模型未输出构筑标签，已如实告知用户");
                }
                // 拒绝/空图谱分支同样带上过程指标，评测才能区分"拒答"与"跑了但没结果"
                TraceData emptyTrace = new TraceData();
                emptyTrace.architect = architectCall;
                emptyTrace.toolCalls = state.getToolCalls();
                emptyTrace.formatViolation = formatViolation;
                emptyReply = emptyReply + "\n<trace>" + buildTrace(emptyTrace) + "</trace>";
                MaaLog.user("结果: 空图谱/拒绝分支，未添加模组");
                return emptyReply;
            }

            // ==========================================
            // 🚀 多智能体重排机制：构建供 Critic 审核的上下文
            // ==========================================
            if ((needed > 0 && !intents.isEmpty()) || !expandAddonsStr.isEmpty()) {
                StringBuilder criticContext = new StringBuilder();
                criticContext.append("【用户原始需求】\n").append(prompt).append("\n\n");
                criticContext.append("【已有核心模组】\n").append(initialMods).append("\n\n");
                // 避免把负数配额喂给 Critic：依赖补全后总数可能已经超标，此时只审核附属池、不再要求补量
                if (needed > 0) {
                    criticContext.append("【你需要挑选的目标总数】\n在本轮候选池中挑选 ")
                            .append(rootBudget).append(" 个模组，这是【硬上限】：")
                            .append("多挑的部分会被系统按低相关性丢弃，不要浪费额度。\n\n");
                } else {
                    criticContext.append("【你需要挑选的目标总数】\n当前模组总数已达标，本轮不需要再补齐数量。")
                            .append("请只审查附属候选池，挑选与用户需求真正契合的附属，最多 ")
                            .append(roundBudget).append(" 个（这是【硬上限】，多挑的部分会被系统按低相关性丢弃）；")
                            .append("没有值得加的附属时可以不挑。\n\n");
                }

                boolean requiresCritic = false;
                Set<String> poolReserved = new HashSet<>(initialMods);
                // P3-3：本轮各类别的召回预算（也用于 Critic 之后核对配比兑现情况）
                Map<String, Integer> intentBudgets = new LinkedHashMap<>();
                // P3-6：候选池索引（slug → 进池原因 + 类别），用于审核结果的白名单与配额收口
                record PoolEntry(String reason, String category) {
                }
                Map<String, PoolEntry> poolIndex = new LinkedHashMap<>();
                /** 意图候选池（已按最终分 → 下载量排序），用于审核不足时的确定性补位 */
                List<CandidateMod> rankedPool = new ArrayList<>();

                System.out.println("\n=======================================================");
                System.out.println("🌐 [阶段 2] Java 捕手启动多路召回，开始建立候选池...");

                // 1. 构建附属候选池
                if (!expandAddonsStr.isEmpty()) {
                    System.out.println("   🔭 捕获到附属扩张请求: [" + expandAddonsStr + "]");
                    criticContext.append("【待审核附属池】（请挑选优秀的，剔除垃圾或与用户需求无关的）\n");
                    List<String> cores = Arrays.stream(expandAddonsStr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                    cores = sanitizeExpandCores(cores, loader, mcVersion, initialMods, unverifiedMods,
                            modDeltas, userPackNorms);
                    for (String core : cores) {
                        List<CandidateMod> addons = buildAddonPool(core, loader, mcVersion, initialMods);
                        criticContext.append("--> ").append(core).append(" 的附属候选：\n");
                        for (CandidateMod c : addons) {
                            criticContext.append(String.format("- %s | Final:%.2f | 下载量:%dW | 描述:%s\n",
                                    c.slug, c.finalScore, c.downloads / 10000, c.desc));
                            poolIndex.put(c.slug, new PoolEntry("附属(" + core + ")", null));
                        }
                        // 附属候选同样进补位池：用户明确要求"加某个模组的附属"时，附属就是最该优先补上的东西，
                        // 若只把意图池放进 rankedPool，纯附属请求（search_intents 为空）将无从补位。
                        rankedPool.addAll(addons);
                        addons.forEach(c -> poolReserved.add(c.slug));
                    }
                    criticContext.append("\n");
                    requiresCritic = true;
                }

                // 2. 构建意图搜索多路召回池
                if (needed > 0 && !intents.isEmpty()) {
                    System.out.println("   🔍 捕获到 AI 关键词矩阵: [" + searchIntentsStr + "]");
                    criticContext.append("【待审核意图候选池】（Final = 文本重合度 × 热度系数，越高的越相关；")
                            .append("「命中」列是它命中的检索词元；请筛除与核心存在冲突的）\n");

                    // 🧮 P3-3/P3-6：按 ratio 把"根模组预算"分配到各类，并把这本账明确告诉 Critic
                    intentBudgets = computeCategoryBudget(intents, rootBudget);
                    if (!intentBudgets.isEmpty()) {
                        criticContext.append("【类别配额】（按 ratio 分配，这是【每类上限】，不是下限：")
                                .append("某一类池子不足时可以让其它类别补位，但不要超出各类上限的总和）\n");
                        for (SearchIntent it : intents) {
                            criticContext.append("- ").append(it.category()).append("：")
                                    .append(it.ratio()).append("% → 目标约 ")
                                    .append(Math.max(1, (int) Math.round(rootBudget * it.ratio() / 100.0)))
                                    .append(" 个（本轮提供 ").append(intentBudgets.getOrDefault(it.category(), 0))
                                    .append(" 个候选）\n");
                        }
                        criticContext.append("\n");
                    }

                    List<CandidateMod> fillers = buildFillerPoolByKeywords(intents, intentBudgets, loader, mcVersion,
                            poolReserved, maxDownloads, uuid);
                    candidatePoolSize = fillers.size();
                    rankedPool.addAll(fillers);
                    fillerFetched = true;

                    // 打印前 5 名高分候选模组给开发者看
                    System.out.print("   🏆 多路召回重排 Top 5 候选: ");
                    int topDisplay = Math.min(5, fillers.size());
                    for (int i = 0; i < topDisplay; i++) {
                        System.out.print(fillers.get(i).slug + "(⭐" + String.format("%.1f", fillers.get(i).finalScore) + ") ");
                    }
                    System.out.println();

                    // 最多喂给 Critic 前 POOL_CAP 个分最高的模组防止 Token 超限（已在建池阶段截断）
                    int limit = Math.min(fillers.size(), POOL_CAP);
                    for (int i = 0; i < limit; i++) {
                        CandidateMod c = fillers.get(i);
                        criticContext.append(String.format(
                                "- [%s] | 类别:%s | Final:%.2f | 文本:%.1f | 热度:%.2f | 命中:%s | 下载:%dW | 描述:%s\n",
                                c.slug, c.category == null ? "unknown" : c.category,
                                c.finalScore, c.textScore.sum(), c.popNorm,
                                c.hitTermsText(), c.downloads / 10000, c.desc));
                        poolIndex.putIfAbsent(c.slug, new PoolEntry("类别(" + c.category + ")", c.category));
                        if (c.category != null) poolCategoryOfSlug.putIfAbsent(c.slug, c.category);
                    }
                    requiresCritic = true;
                }

                // 3. 将上下文发送给 Critic 审核员
                if (requiresCritic) {
                    System.out.println("\n=======================================================");
                    System.out.println("🕵️‍♂️ [阶段 3] 海选池建立完毕！提交给 Critic Agent 审核员...");
                    System.out.println("   ⏳ 等待审核员结合描述和评分进行最终裁决...");

                    if (isAborted(uuid)) return "⛔ 思考已手动终止。";

                    criticCall = aiAgentService.criticPools(criticContext.toString(), effectiveApiKey);
                    String criticReply = criticCall.text();
                    if (forceCriticDegraded) {
                        // 🧪 评测故障注入：模拟"输出打满 token 上限被截断"，把闭合标签切掉
                        int close = criticReply.indexOf("</approved_mods>");
                        if (close > 0) criticReply = criticReply.substring(0, close);
                        System.out.println("   🧪 [评测注入] 已截断审核员输出，模拟 token 上限导致的标签未闭合");
                        MaaLog.user("评测注入: 已截断审核员输出（模拟退化）");
                    }

                    System.out.println("   📜 审核员原始裁决报告:");
                    System.out.println("-------------------------------------------------------");
                    System.out.println(criticReply.trim());
                    System.out.println("-------------------------------------------------------");

                    String approvedStr = extractTag(criticReply, "approved_mods", "");

                    // Critic 输出退化（没给标签 / 标签未闭合 = 多半被 token 上限截断）：不能像旧实现那样
                    // 当成"审核员一个都没挑"就把整条兜底链跳过——那会让用户拿到 0.25 倍的包且毫不知情（坏例 B16）。
                    criticDegraded = looksLikeCriticDegeneration(criticReply);
                    if (criticDegraded) {
                        String note = "⚠️ 审核员输出异常（无 <approved_mods> 或标签未闭合，通常是输出被 token 上限截断），"
                                + "本轮改由 Java 按相关性顺序补足额度";
                        System.out.println("   " + note);
                        MaaLog.user(note);
                    }

                    // 只有"明确返回了标签"或"输出退化"才进入本分支：前者正常走闸门，
                    // 后者 approvedMods 为空、由确定性补位兜底；而"明确给出空清单"仍然尊重审核员的判断。
                    if (!approvedStr.isEmpty() || criticDegraded) {
                        List<String> approvedMods = cleanSlugs(approvedStr);
                        // 🧮 P3-6 审核结果闸门：① 必须在送审候选池内 ② 每类不得超配额 ③ 总数不得超根模组预算
                        // 边界说明：这里收口的是【审核员多挑的候选】（用户还没确认要它们），
                        // 不是用户已确认的包——最终清单超限仍然只提示、不自动删。
                        Map<String, Integer> categoryQuota = computeCategoryQuota(intents, rootBudget);
                        Map<String, Integer> approvedPerCat = new LinkedHashMap<>();
                        List<String> kept = new ArrayList<>();
                        for (String slug : approvedMods) {
                            PoolEntry entry = poolIndex.get(slug);
                            if (entry == null) {
                                logModOut(slug, "不在本轮送审候选池内（审核员可能臆造），剔除");
                                droppedNotInPool++;
                                continue;
                            }
                            if (initialMods.contains(slug) || kept.contains(slug)) {
                                logModOut(slug, "已在包内或本轮重复批准，跳过");
                                droppedDuplicate++;
                                continue;
                            }
                            if (kept.size() >= roundBudget) {
                                logModOut(slug, "超出本轮新增额度(" + roundBudget + ")，按低相关性收敛");
                                droppedOverQuota++;
                                continue;
                            }
                            if (entry.category() != null) {
                                int cap = categoryQuota.getOrDefault(entry.category(), POOL_MIN_PER_CATEGORY);
                                if (approvedPerCat.getOrDefault(entry.category(), 0) >= cap) {
                                    logModOut(slug, "超出类别[" + entry.category() + "]配额上限(" + cap + ")，按低相关性收敛");
                                    droppedOverQuota++;
                                    continue;
                                }
                            }
                            kept.add(slug);
                            approvedKept++;
                            if (entry.category() != null) {
                                approvedPerCat.merge(entry.category(), 1, Integer::sum);
                            }
                            logModIn(slug, entry.reason() + "；审核通过");
                        }

                        // 🧮 P3-6 补位：Critic 的类别分布经常偏离配额（实测 A12 全挑 decoration、
                        // worldgen/utility 一个没挑），超配的被上面丢掉后如果没人补，最终数量就不足。
                        // 这里由 Java 按候选池的相关性顺序（最终分 → 下载量）确定性补足，
                        // 且仍遵守每类上限——即用户确认的"某类不足时允许其它类别补位"。
                        if (kept.size() < roundBudget && !rankedPool.isEmpty()) {
                            int before = kept.size();
                            for (CandidateMod c : rankedPool) {
                                if (kept.size() >= roundBudget) break;
                                if (initialMods.contains(c.slug) || kept.contains(c.slug)) continue;
                                if (c.category != null) {
                                    int cap = categoryQuota.getOrDefault(c.category, POOL_MIN_PER_CATEGORY);
                                    if (approvedPerCat.getOrDefault(c.category, 0) >= cap) continue;
                                }
                                kept.add(c.slug);
                                approvedKept++;
                                if (c.category != null) {
                                    approvedPerCat.merge(c.category, 1, Integer::sum);
                                }
                                logModIn(c.slug, "配额补位：审核员未挑满（类别 "
                                        + (c.category == null ? "未分类" : c.category) + " 仍有名额），按相关性顺序补入");
                            }
                            String backfillNote = "🧮 配额补位: " + before + " → " + kept.size()
                                    + "（审核员挑得不够或分布偏科，由 Java 按相关性顺序补齐）";
                            System.out.println("   " + backfillNote);
                            MaaLog.user(backfillNote);
                            backfilledCount = kept.size() - before;
                        }

                        initialMods.addAll(kept);
                        String gateSummary = "✅ 审核员通过 " + approvedMods.size() + " 个 → 采纳 " + kept.size()
                                + " 个（本轮额度 " + roundBudget
                                + "，超配额丢弃 " + droppedOverQuota + "，非候选池 " + droppedNotInPool
                                + "，重复/已在包内 " + droppedDuplicate + "）";
                        System.out.println(gateSummary);
                        MaaLog.user(gateSummary);

                        // 🧮 P3-3：核对类别配额兑现（按"召回时的类别"统计，与上面的配额表口径一致）
                        if (fillerFetched && !categoryQuota.isEmpty()) {
                            StringBuilder dist = new StringBuilder();
                            for (String cat : categoryQuota.keySet()) {
                                int got = approvedPerCat.getOrDefault(cat, 0);
                                int want = categoryQuota.get(cat);
                                dist.append(cat).append(' ').append(got).append('/').append(want).append("  ");
                                if (got * 2 < want) quotaShortfalls.add(cat + "(" + got + "/" + want + ")");
                            }
                            System.out.println("   📊 类别配额兑现: " + dist.toString().trim());
                            MaaLog.user("类别配额兑现: " + dist.toString().trim());
                        }
                    } else {
                        System.out.println("⚠️ 警告：审核员明确返回了空清单（候选池里没有它认可的模组），本轮不补量。");
                    }
                }
            }

            // ==========================================
            // 🛡️ 图谱引擎（真实性/版本预检已在阶段 0 完成）
            // ==========================================
            System.out.println("\n=======================================================");
            System.out.println("🛠️ [阶段 4] 启动图谱引擎与深度依赖穿透...");
            System.out.println("🕸️ 开始将所有拼合模组 (" + initialMods.size() + "个) 送入深度依赖穿透引擎...");
            // 取带 DAG 结构的结果（与 preview 共享同一份缓存），用于删除后的断链诊断
            long depT0 = System.currentTimeMillis();
            // P6：取带完整状态与诊断的解析结果（未解析项 / 被剔除项 / 是否触顶）
            ResolutionResult resolution = dependencyEngine.resolveCachedResult(initialMods, loader, mcVersion);
            DependencyGraph resolvedGraph = resolution.graph();
            long dependencyMs = System.currentTimeMillis() - depT0;
            Set<String> finalPerfectMods = new LinkedHashSet<>(resolvedGraph.getOrderedSlugs());
            int resolvedCount = finalPerfectMods.size();

            // 📋 模组进出流水（依赖穿透部分）：说明每个"凭空多出来"的模组是因为谁被拉进来的
            int loggedDeps = 0;
            for (String m : finalPerfectMods) {
                if (initialMods.contains(m)) continue;
                if (loggedDeps++ >= 200) {
                    System.out.println("   … 其余依赖新增项省略（已达 200 行日志上限）");
                    break;
                }
                List<String> dependents = resolvedGraph.getDependentsOf(m).stream()
                        .filter(finalPerfectMods::contains).sorted().toList();
                logModIn(m, dependents.isEmpty()
                        ? "依赖穿透：作为必需前置补入"
                        : "依赖穿透：" + String.join("、", dependents) + " 需要它，作为必需前置补入");
            }
            // 🧮 P3-6：把本次真实膨胀比喂回跟踪器（用剔除用户显式删除之前的解析结果）
            expansionTracker.observe(loader, mcVersion, initialMods.size(), resolvedCount);

            // 🗑️ P2：应用用户的显式删除 —— 依赖引擎可能把被删模组当作必需前置重新补回，这里再剔除一次，
            // 并把"保留模组因此缺失前置"的事实明确告诉用户，而不是静默产出一个会崩的包。
            List<String> breakNotes = new ArrayList<>();
            for (String excluded : excludedSlugs) {
                if (!finalPerfectMods.remove(excluded)) continue;
                logModOut(excluded, "用户在图谱中显式删除，本轮剔除（依赖引擎可能把它当必需前置补回过）");
                for (String dependent : resolvedGraph.getDependentsOf(excluded)) {
                    if (finalPerfectMods.contains(dependent)) {
                        breakNotes.add(dependent + " 缺少前置 " + excluded);
                    }
                }
            }

            // 清理 Architect 输出的多余标签
            String cleanReply = aiBlueprint.replaceAll("<target_count>[\\s\\S]*?</target_count>", "")
                    .replaceAll("<max_downloads>[\\s\\S]*?</max_downloads>", "")
                    .replaceAll("<core_mods>[\\s\\S]*?</core_mods>", "")
                    .replaceAll("<expand_addons>[\\s\\S]*?</expand_addons>", "")
                    .replaceAll("<search_intents>[\\s\\S]*?</search_intents>", "");
            // F01：mc/loader 一律以 Java 解析后的最终值为准回填，保证前端与评测读到的是真正生效的环境
            cleanReply = cleanReply.replaceAll("<mc>[\\s\\S]*?</mc>", "")
                    .replaceAll("<loader>[\\s\\S]*?</loader>", "")
                    + "\n<mc>" + mcVersion + "</mc>\n<loader>" + loader + "</loader>";

            if (!breakNotes.isEmpty()) {
                cleanReply = cleanReply + "\n\n⚠️ 注意：你已删除的模组中有 "
                        + breakNotes.size() + " 项是保留模组的必需前置（"
                        + String.join("；", breakNotes)
                        + "）。如需修复，可以说\"补回对应前置\"或指定替代模组。";
                MaaLog.user("删除断链: " + String.join("; ", breakNotes));
            }

            // 故障诚实：网络/限流导致没校验成功的模组必须如实告知，不能假装全部验证通过
            if (!unverifiedMods.isEmpty()) {
                cleanReply = cleanReply + "\n\n❔ 另有 " + unverifiedMods.size()
                        + " 个模组本轮未能向 Modrinth 校验版本兼容性（网络或限流），已按原样保留："
                        + String.join(", ", unverifiedMods) + "。可稍后重新生成以再次校验。";
                MaaLog.user("版本校验存疑: " + String.join(",", unverifiedMods));
            }

            // 审核员输出退化必须让用户知道：否则"包突然小了一半"会变成无法解释的现象（坏例 B16）
            if (criticDegraded) {
                cleanReply = cleanReply + "\n\n⚠️ 本轮审核员（Critic）的输出没有正常返回（缺少 <approved_mods> 标签或标签未闭合，"
                        + "通常是输出达到 token 上限被截断）。为避免只给你一个" + finalPerfectMods.size() + "个左右的半成品，"
                        + (backfilledCount > 0
                            ? "系统已按相关性顺序补足本轮额度（补位 " + backfilledCount + " 个）。"
                            : "但候选池也已取空，本轮未能补足数量。")
                        + "建议复核这些模组，或再说一次重新生成。";
                MaaLog.user("审核员输出退化: 补位 " + backfilledCount + " 个，已告知用户");
            }

            // 类别幻觉必须让用户看见：悄悄换成"全库搜索"会让检索结果变得不可解释
            if (!invalidCategories.isEmpty()) {
                cleanReply = cleanReply + "\n\n⚠️ 规划阶段出现了无效的模组类别（"
                        + String.join(", ", invalidCategories)
                        + "），已忽略这些类别，只使用 19 个规范类别检索。";
                MaaLog.user("无效类别已忽略: " + String.join(", ", invalidCategories));
            }

            // 诚实上报：点名模组被平替/剔除/去重必须让用户知道——它们改变了用户的包
            String modDeltaNote = buildModDeltaNote(modDeltas);
            if (!modDeltaNote.isEmpty()) {
                cleanReply = cleanReply + modDeltaNote;
                MaaLog.user("模组调整: " + modDeltas.size() + " 项（平替/剔除/去重，已在回复中告知）");
            }

            // 📊 用户指标：**最终包内的类别构成**。
            //
            // 为什么不能直接用上面的 quotaShortfalls：它统计的是"本轮**类别检索预算**花得怎么样"
            // （只算以"类别(X)"身份入包的模组），而用户关心的是"成品包里到底有没有这类东西"。
            // 真实事故：一份包里魔法内容有 8 个（铁魔法本体 + 7 个附属），但它们是以"附属(核心)"入包的，
            // 于是显示 magic 0/7 并配上"候选不足或审核员未选中"——对用户说了句不实的话。
            // 现在两个指标并存：内部口径进日志/trace，用户看到的这句话只报包内实际构成。
            if (fillerFetched) {
                // 类别归属的**第一来源是本轮候选池标签**（poolIndex 就是它，2126 行写的那份）：
                // 以"附属(核心)"身份入包的模组也带着池里的类别，这样它们才不会被算成"未分类"。
                // 池里没有的（核心模组 / 依赖穿透补进来的前置）再退回本地 modrinth 缓存查。
                Map<String, Set<String>> poolCategories = new HashMap<>();
                poolCategoryOfSlug.forEach((slug, cat) -> poolCategories.put(slug, Set.of(cat)));
                Map<String, Integer> inPack = inPackCategoryCounts(finalPerfectMods, poolCategories, modrinthCacheService);
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, Integer> e : inPack.entrySet()) {
                    if ("未分类".equals(e.getKey())) continue;
                    parts.add(e.getKey() + " " + e.getValue() + " 个");
                }
                int unclassified = inPack.getOrDefault("未分类", 0);
                if (!parts.isEmpty() || unclassified > 0) {
                    cleanReply = cleanReply + "\n\n📊 包内类别构成：" + String.join("、", parts)
                            + (unclassified > 0
                                ? "；另有未分类 " + unclassified + " 个（本地查不到类别，不计入缺口）" : "")
                            + "。";
                    MaaLog.user("包内类别构成: " + String.join(" ", parts)
                            + (unclassified > 0 ? "｜未分类 " + unclassified : ""));
                }
            }
            // 📉 内部口径：本轮**类别检索额度**没打满（这是预算监控，不代表包里没有这类内容）
            if (!quotaShortfalls.isEmpty()) {
                cleanReply = cleanReply + "\n\n📉 本轮类别检索额度未打满：" + String.join("、", quotaShortfalls)
                        + "（这是「检索预算」口径：该类词条召回的候选没达到目标数，"
                        + "不等于包里没有这类模组 —— 请看上面的包内构成）。"
                        + "可以补充该类别的关键词，或直接点名具体模组。";
                MaaLog.user("类别配额缺口: " + String.join("、", quotaShortfalls));
            }

            // 🧩 P6：依赖解析不完整时如实上报（缺前置被剔除、loader 不匹配、精确版本冲突、预算触顶）
            if (!resolution.isComplete()) {
                StringBuilder note = new StringBuilder("\n\n⚠️ 依赖解析不完整，以下模组没能进入最终清单：\n");
                for (ResolutionResult.Dropped d : resolution.dropped()) {
                    note.append("· ").append(d.slug()).append("（").append(d.detail()).append("）\n");
                    logModOut(d.slug(), d.reason() + "：" + d.detail());
                }
                int shown = 0;
                for (ResolutionResult.Unresolved u : resolution.unresolved()) {
                    if (shown++ >= 10) { note.append("· …\n"); break; }
                    note.append("· ").append(u.requiredBy()).append(" 需要 ").append(u.slug())
                            .append("（").append(u.reason()).append("）\n");
                }
                if (resolution.budgetExhausted()) {
                    note.append("· 已达节点预算 ").append(resolution.nodeBudget())
                            .append(" 上限，剩余依赖未继续解析\n");
                }
                note.append("如果你需要其中某个模组，可以点名要我换一个可用的替代。");
                cleanReply = cleanReply + note;
                MaaLog.user("依赖解析不完整: " + resolution.summary());
            }

            // ==========================================
            // 📐 数量口径：期望区间 0.8~1.5 倍目标（用户确认的口径）
            // 超出上限时不自行删除（那会破坏依赖闭包），而是给出"建议用户删除哪些模组 + 它们的前置"
            // ==========================================
            double countRatio = targetCount > 0 ? (double) finalPerfectMods.size() / targetCount : 0;
            List<String> trimSuggestions = List.of();
            if (targetCount > 0 && countRatio > 1.5) {
                trimSuggestions = suggestTrimCandidates(resolvedGraph, finalPerfectMods, targetCount);
                if (!trimSuggestions.isEmpty()) {
                    cleanReply = cleanReply + "\n\n📐 当前共 " + finalPerfectMods.size()
                            + " 个模组，超过你期望的 " + targetCount + " 个较多。需要缩小规模的话，建议优先移除：\n"
                            + String.join("\n", trimSuggestions)
                            + "\n（我没有自动删除——删根模组会连带影响它的前置，交给你决定更稳妥。）";
                    MaaLog.user("数量超上限: " + finalPerfectMods.size() + "/" + targetCount
                            + " 建议移除 " + trimSuggestions.size() + " 项");
                }
            }

            // ==========================================
            // 🧾 过程指标（<trace>）：让评测的"过程层/成本层"可自动断言，而不是只能翻日志
            // ==========================================
            TraceData trace = new TraceData();
            trace.architect = architectCall;
            trace.critic = criticCall;
            trace.criticDegraded = criticDegraded;
            trace.dependencyMs = dependencyMs;
            trace.inputRoots = initialMods.size();
            trace.finalCount = finalPerfectMods.size();
            trace.targetCount = targetCount;
            trace.countRatio = countRatio;
            trace.candidatePool = candidatePoolSize;
            trace.toolCalls = state.getToolCalls();
            trace.expansionFactor = expansionFactor;
            trace.rootBudget = rootBudget;
            trace.roundBudget = roundBudget;
            trace.approvedKept = approvedKept;
            trace.droppedOverQuota = droppedOverQuota;
            trace.droppedNotInPool = droppedNotInPool;
            trace.backfilled = backfilledCount;
            trace.categoryWeightApplied = categoryWeightApplied;
            trace.dependencyAdded = Math.max(0, resolvedCount - initialMods.size());
            trace.invalidCategories = invalidCategories;
            trace.unverifiedMods = unverifiedMods;
            trace.replacedMods = modDeltas.stream().filter(d -> "REPLACED".equals(d.action()))
                    .map(d -> d.slug() + "->" + d.detail()).toList();
            trace.droppedMods = modDeltas.stream().filter(d -> "DROPPED".equals(d.action()))
                    .map(ModDelta::slug).toList();
            trace.dedupedMods = modDeltas.stream().filter(d -> "DUPLICATE".equals(d.action()))
                    .map(ModDelta::slug).toList();
            trace.quotaShortfalls = quotaShortfalls;
            trace.breakNotes = breakNotes;
            trace.trimSuggestions = trimSuggestions.size();
            trace.resolutionStatus = resolution.status().name();
            trace.unresolvedCount = resolution.unresolved().size();
            trace.droppedCount = resolution.dropped().size();
            cleanReply = cleanReply + "\n<trace>" + buildTrace(trace) + "</trace>";

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

    /**
     * 追加 M2 变更摘要({@code <ops_summary>}，JSON)与最终模组列表；Tool 改名时替换 {@code <name>}。
     *
     * <p>摘要由 {@link PackSessionState} 的状态字段直接生成，前端据此渲染"本轮变更"卡片，
     * 不依赖模型在正文里复述——模型漏说一次，用户就会以为什么都没发生。
     */
    private String appendOpsAndMods(String cleanReply, PackSessionState state, String modsXml) {
        String reply = cleanReply;
        if (state.getPackName() != null) {
            // 包名已在 Tool 入口净化（见 ArchitectPackTools.sanitizePackName）；
            // 这里再 quoteReplacement 兜一层，任何来源的名字都不会把 replaceAll 的 $ 分组引用炸掉
            reply = reply.replaceAll("(?s)<name>.*?</name>",
                    "<name>" + Matcher.quoteReplacement(state.getPackName()) + "</name>");
        }
        if (state.hasChanges()) {
            reply += "\n<ops_summary>" + state.toOpsJson() + "</ops_summary>";
        }
        return reply + modsXml;
    }

    /**
     * 数量超出期望上限时给出"建议移除清单"（只建议，绝不自动删）。
     *
     * <p>挑选原则：只从"没有任何保留模组依赖它"的根里挑（删它不会连累别人），
     * 按"删除后能减少的模组数（自身 + 前置闭包）"降序排列，并显式列出会一并移除的前置，
     * 让用户自己判断代价。用户确认的口径是：0.8~1.5 倍目标内都算正常，超出才提示。
     */
    private List<String> suggestTrimCandidates(DependencyGraph graph, Set<String> mods, int targetCount) {
        List<String> roots = new ArrayList<>();
        for (String s : mods) {
            boolean dependedOn = graph.getDependentsOf(s).stream().anyMatch(mods::contains);
            if (!dependedOn) roots.add(s);
        }
        Map<String, Set<String>> closure = new LinkedHashMap<>();
        for (String root : roots) {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>(graph.getDependenciesOf(root));
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                if (!mods.contains(cur) || !seen.add(cur)) continue;
                queue.addAll(graph.getDependenciesOf(cur));
            }
            closure.put(root, seen);
        }
        roots.sort((a, b) -> Integer.compare(closure.get(b).size(), closure.get(a).size()));
        List<String> out = new ArrayList<>();
        int projected = mods.size();
        for (String root : roots) {
            if (projected <= targetCount || out.size() >= 5) break;
            Set<String> deps = closure.get(root);
            projected -= 1 + deps.size();
            out.add("· " + root + (deps.isEmpty()
                    ? "（无前置，可直接删）"
                    : "（会一并移除前置：" + String.join("、", deps) + "）"));
        }
        return out;
    }

    /**
     * 过程指标容器：字段多且会继续增加，用对象承载避免 {@link #buildTrace} 参数爆炸。
     */
    private static class TraceData {
        AiAgentService.AgentCallResult architect;
        AiAgentService.AgentCallResult critic;
        long dependencyMs;
        int inputRoots, finalCount, targetCount, candidatePool, toolCalls;
        double countRatio, expansionFactor;
        int rootBudget, roundBudget, approvedKept, droppedOverQuota, droppedNotInPool, dependencyAdded, trimSuggestions;
        int backfilled;
        Set<String> invalidCategories = Set.of();
        Set<String> unverifiedMods = Set.of();
        List<String> replacedMods = List.of();
        List<String> droppedMods = List.of();
        List<String> dedupedMods = List.of();
        List<String> quotaShortfalls = List.of();
        List<String> breakNotes = List.of();
        boolean formatViolation;
        /** Critic 输出退化（标签缺失/未闭合）：评测用它断言"退化时必须如实告知"（坏例 B16） */
        boolean criticDegraded;
        String resolutionStatus = "COMPLETE";
        int unresolvedCount, droppedCount;
        /** 本轮有多少个类别权重是 Tool adjustCategoryTargets 施加的（P3-7） */
        int categoryWeightApplied;
    }

    /**
     * 组装 {@code <trace>} 过程指标：紧凑 JSON，前端隐藏、评测器解析断言。
     *
     * <p>{@code upstream} 是本轮向上游真实发出的请求数与限流情况（见 {@link RequestScope}）。
     * 加它的动机：此前排查限流只能靠"版本校验用了 8.7 秒"这种耗时反推，说不清是请求多、
     * 网络慢、还是退避睡掉了时间。本字段纯追加，不影响评测既有断言。
     */
    private String buildTrace(TraceData d) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"branch\":\"").append(d.formatViolation ? "FORMAT_VIOLATION" : "NORMAL").append("\"");
        sb.append(",\"criticDegraded\":").append(d.criticDegraded);
        sb.append(",\"upstream\":").append(RequestScope.toTraceJson());
        sb.append(",\"architect\":{").append(callStat(d.architect)).append("}");
        sb.append(",\"critic\":").append(d.critic == null ? "null" : "{" + callStat(d.critic) + "}");
        sb.append(",\"dependencyMs\":").append(d.dependencyMs);
        sb.append(",\"inputRoots\":").append(d.inputRoots);
        sb.append(",\"candidatePool\":").append(d.candidatePool);
        sb.append(",\"toolCalls\":").append(d.toolCalls);
        sb.append(",\"budget\":{\"rootBudget\":").append(d.rootBudget)
                .append(",\"roundBudget\":").append(d.roundBudget)
                .append(",\"expansionFactor\":").append(String.format("%.2f", d.expansionFactor))
                .append(",\"approvedKept\":").append(d.approvedKept)
                .append(",\"droppedOverQuota\":").append(d.droppedOverQuota)
                .append(",\"droppedNotInPool\":").append(d.droppedNotInPool)
                .append(",\"backfilled\":").append(d.backfilled)
                .append(",\"dependencyAdded\":").append(d.dependencyAdded)
                .append(",\"categoryWeightApplied\":").append(d.categoryWeightApplied)
                .append("}");
        sb.append(",\"count\":{\"target\":").append(d.targetCount)
                .append(",\"final\":").append(d.finalCount)
                .append(",\"ratio\":").append(String.format("%.2f", d.countRatio))
                .append(",\"inRange\":").append(d.targetCount > 0
                        && d.finalCount >= d.targetCount * 0.8 && d.finalCount <= d.targetCount * 1.5)
                .append("}");
        sb.append(",\"diagnostics\":{\"invalidCategories\":").append(d.invalidCategories.size())
                .append(",\"unverified\":").append(d.unverifiedMods.size())
                .append(",\"quotaShortfalls\":").append(d.quotaShortfalls.size())
                .append(",\"dependencyBreaks\":").append(d.breakNotes.size())
                .append(",\"trimSuggestions\":").append(d.trimSuggestions)
                .append("}");
        // 点名模组的调整流水（平替/剔除/去重）：与诊断计数分开，评测可以断言"有没有如实告知"
        sb.append(",\"modOps\":{\"replaced\":[");
        appendJsonStrings(sb, d.replacedMods);
        sb.append("],\"dropped\":[");
        appendJsonStrings(sb, d.droppedMods);
        sb.append("],\"deduped\":[");
        appendJsonStrings(sb, d.dedupedMods);
        sb.append("]}");
        sb.append(",\"resolution\":{\"status\":\"").append(d.resolutionStatus)
                .append("\",\"unresolved\":").append(d.unresolvedCount)
                .append(",\"dropped\":").append(d.droppedCount).append("}");
        return sb.append("}").toString();
    }

    /** 把字符串列表拼成 JSON 数组元素（值来自 Modrinth slug，仍做最小转义以防万一） */
    private static void appendJsonStrings(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
    }

    private static String callStat(AiAgentService.AgentCallResult r) {
        return "\"ms\":" + r.elapsedMs() + ",\"calls\":" + r.modelCalls()
                + ",\"in\":" + r.inputTokens() + ",\"out\":" + r.outputTokens();
    }

    // 🚀 建池器 1：获取附属候选池
    private List<CandidateMod> buildAddonPool(String coreSlug, String loader, String mcVersion, Set<String> existing) {
        if (modrinthCacheService.isAvailable()) {
            return buildAddonPoolLocal(coreSlug, loader, mcVersion, existing);
        }
        List<CandidateMod> pool = new ArrayList<>();
        try {
            String facetsRaw = String.format("[[\"project_type:mod\"], [\"loaders:%s\"], [\"versions:%s\"]]", loader, mcVersion);
            String cleanCoreName = coreSlug.replace("-", " ");
            JsonNode res = apiClient.search(cleanCoreName + " addon", 15, facetsRaw);
            if (res != null && res.has("hits")) {
                int count = 0;
                for (JsonNode hit : res.path("hits")) {
                    String slug = hit.path("slug").asText();
                    if (!slug.equals(coreSlug) && !existing.contains(slug)) {
                        CandidateMod c = new CandidateMod(slug, hit.path("title").asText(),
                                hit.path("description").asText(), hit.path("downloads").asLong());
                        c.textScore.add(ADDON_BASE_SCORE);
                        pool.add(c);
                        count++;
                    }
                }
                System.out.println("   📦 核心 [" + coreSlug + "] 附属召回: 抓取到 " + count + " 个候选包");
            }
        } catch (Exception ignored) {}
        return scoreAddonPool(pool);
    }

    // 🚀 建池器 2：多路召回重排引擎
    private List<CandidateMod> buildFillerPoolByKeywords(List<SearchIntent> intents, Map<String, Integer> budget,
                                                         String loader, String mcVersion, Set<String> existing,
                                                         long maxDownloads, String uuid) {
        if (modrinthCacheService.isAvailable()) {
            return buildFillerPoolLocal(intents, budget, loader, mcVersion, existing, maxDownloads);
        }
        String facetsRaw = String.format("[[\"project_type:mod\"], [\"loaders:%s\"], [\"versions:%s\"]]", loader, mcVersion);
        // 在线兜底路径同样"按类别分别召回、分别截断"，并用与本地一致的打分口径
        Map<String, List<CandidateMod>> byCategory = new LinkedHashMap<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 429 保护：一个关键词 = 一次 /v2/search，关键词数量上去以后必须有闸门（本地路径无网络请求，不需要）
            Semaphore gate = new Semaphore(5);
            for (SearchIntent intent : intents) {
                Map<String, CandidateMod> hitMap = new ConcurrentHashMap<>();
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (String kw : intent.keywords()) {
                    final String safeKw = kw;
                    final List<String> terms = RetrievalScorer.tokenize(kw);
                    if (terms.isEmpty()) continue;
                    futures.add(ScopedExecutors.runAsync(() -> {
                        if (isAborted(uuid)) return;
                        boolean acquired = false;
                        try {
                            gate.acquire();
                            acquired = true;
                            JsonNode res = apiClient.searchPage(safeKw, 30, 0, null, facetsRaw);

                            if (res != null && res.has("hits")) {
                                int hitCount = res.path("hits").size();
                                System.out.println("   🎣 召回流 [" + intent.category() + " -> " + safeKw + "]: 抓取到 " + hitCount + " 个候选");

                                for (JsonNode hit : res.path("hits")) {
                                    String slug = hit.path("slug").asText();
                                    long downloads = hit.path("downloads").asLong();

                                    if (existing.contains(slug) || downloads > maxDownloads) continue;

                                    // 命中集合由 Modrinth 的排序决定，但打分口径必须与本地一致，
                                    // 否则同一份关键词在"缓存可用/不可用"两种状态下含义不同
                                    String title = hit.path("title").asText();
                                    String desc = hit.path("description").asText().replace("\n", " ");
                                    RetrievalScorer.Match match = RetrievalScorer.match(terms,
                                            slug.toLowerCase(), title.toLowerCase(), desc.toLowerCase());
                                    if (!match.hit()) continue;
                                    CandidateMod c = hitMap.computeIfAbsent(slug, k -> {
                                        CandidateMod n = new CandidateMod(slug, title, desc, downloads);
                                        n.category = intent.category();
                                        return n;
                                    });
                                    c.addMatch(match.score(), match.terms());
                                }
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } catch (Exception ignored) {
                            // 单个关键词失败不影响整轮：候选池由其它关键词兜住（与旧行为一致）
                        } finally {
                            if (acquired) gate.release();
                        }
                    }, executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                byCategory.put(intent.category(), new ArrayList<>(hitMap.values()));
            }
        }
        return finalizePool(byCategory, budget, loader, mcVersion, "在线兜底");
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
            String cleanName = originalSlug.replace("-", " ");
            JsonNode result = apiClient.search(cleanName, 5, facetsRaw);

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

    /** 解析逗号分隔的 slug 参数（前端下发的 excludedSlugs），沿用 cleanSlugs 的字符白名单 */
    private Set<String> parseSlugCsv(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return new LinkedHashSet<>(cleanSlugs(raw));
    }

    /**
     * 归一化并过滤 search_intents（P3-2）。
     *
     * <p>格式：{@code 类别:比例:词1,词2 | 类别:比例:词1,词2}
     *
     * <p>类别先走 {@link CategoryRegistry#normalize}（规范名或已知别名）；非法类别<b>整组丢弃</b>
     * 并记入 {@code invalidOut}，由调用方上报给用户。绝不把未知类别原样传下去——
     * 旧实现会让它在本地检索里回退成"全库搜索"，等于静默接受大模型的幻觉类别。
     *
     * @return 过滤后的合法意图串（可能为空串）
     */
    private String normalizeSearchIntents(String raw, Set<String> invalidOut) {
        if (raw == null || raw.isBlank()) return "";
        List<String> kept = new ArrayList<>();
        for (String group : raw.split("\\|")) {
            String[] parts = group.split(":");
            if (parts.length < 3) continue;
            String rawCategory = parts[0].trim();
            String category = CategoryRegistry.normalize(rawCategory);
            if (category == null) {
                invalidOut.add(rawCategory);
                continue;
            }
            String keywords = parts[2].trim();
            if (keywords.isEmpty()) continue;
            kept.add(category + ":" + parts[1].trim() + ":" + keywords);
        }
        return String.join(" | ", kept);
    }

    private String extractTag(String text, String tag, String defaultValue) {
        Pattern pattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : defaultValue;
    }
}
