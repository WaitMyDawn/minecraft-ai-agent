package yagen.waitmydawn.maa.controller;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.AgentContractCodec;
import yagen.waitmydawn.maa.model.agent.BuildIntent;
import yagen.waitmydawn.maa.model.agent.BuildExecutionPlan;
import yagen.waitmydawn.maa.model.agent.ReviewDecision;
import yagen.waitmydawn.maa.model.agent.BuildEnvironment;
import yagen.waitmydawn.maa.model.agent.ReviewResponse;
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
public class ChatController {
    private static final AgentContractCodec AGENT_CODEC = new AgentContractCodec();

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
    /** 加载器版本表：用于"用户点名的环境是否有维护版本"以及给提示词注入支持清单 */
    private final LoaderVersionService loaderVersionService;

    /** 自动评测专用系统 Key；生产构筑不会使用它作为个人 Key 的兜底。 */
    @Value("${ai.api.key:}")
    private String systemDefaultApiKey;

    /** 测试 Key 覆盖开关；仅与 eval profile 同时启用时生效。 */
    @Value("${maa.eval.allow-key-override:false}")
    private boolean allowKeyOverride;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    private boolean evaluationKeysAllowed() {
        return allowKeyOverride && yagen.waitmydawn.maa.runtime.EvaluationAccess.allowed(environment);
    }

    private String effectiveKey(String authToken, String override) {
        if (evaluationKeysAllowed() && override != null && !override.isBlank()) return override;
        String personal = userController.getUserApiKey(authToken);
        if (personal != null && !personal.isBlank()) return personal;
        return evaluationKeysAllowed() ? systemDefaultApiKey : null;
    }

    /**
     * 评测专用：强制截断审核员 JSON；旧 XML 路径去掉 approved_mods 的闭合标签。
     * 稳定复现"输出被 token 上限截断"的退化路径（坏例 B16）。默认关闭，生产不要打开。
     *
     * <p>为什么需要它：这条路径取决于模型是否复读，属于概率事件；只有能确定性注入，
     * 才能证明"退化时由 Java 补位兜底 + 如实告知"真的生效，而不是等下一次偶发。
     */
    @Value("${maa.eval.force-critic-degraded:false}")
    private boolean forceCriticDegraded;

    private boolean criticDegradationForced() {
        return forceCriticDegraded && yagen.waitmydawn.maa.runtime.EvaluationAccess.allowed(environment);
    }

    // 使用 volatile 标记替代 Thread.interrupt() 实现即时终止
    private final Map<String, Boolean> abortedSessions = new ConcurrentHashMap<>();
    /**
     * 同一会话同时只跑一轮。TTL 25 分钟（比单轮等待上限 20 分钟留点余量）：
     * 超过就抢占，避免进程被杀/异常卡住后把这个 uuid 永久锁死。
     */
    private final yagen.waitmydawn.maa.runtime.RoundGuard roundGuard =
            new yagen.waitmydawn.maa.runtime.RoundGuard(25 * 60 * 1000L);

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

    /** 标注这条调整来自用户已有的包，还是本轮新增的 */
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
        java.time.Instant published, updated;
        yagen.waitmydawn.maa.model.agent.SelectionPreferences preferences = yagen.waitmydawn.maa.model.agent.SelectionPreferences.DEFAULT;
        void dates(JsonNode facts) {
            var now = java.time.Instant.now();
            var created = PreferenceRanking.date(facts.path("date_created").asText(facts.path("published").asText()), now);
            var modified = PreferenceRanking.date(facts.path("date_modified").asText(facts.path("updated").asText()), now);
            if (created != null) published = created;
            if (modified != null) updated = modified;
            if (facts.path("downloads").isNumber()) downloads = facts.path("downloads").asLong();
        }
        Map<String, Object> metadataFacts() {
            return Map.of("slug", slug, "downloads", downloads, "published", published == null ? "UNKNOWN" : published.toString(),
                    "updated", updated == null ? "UNKNOWN" : updated.toString());
        }
        String preferenceFacts() {
            return preferences.equals(yagen.waitmydawn.maa.model.agent.SelectionPreferences.DEFAULT) ? ""
                    : " | 局部偏好=" + preferences + " | 发布=" + (published == null ? "未知" : published)
                    + " | 更新=" + (updated == null ? "未知" : updated) + " | 下载=" + downloads;
        }
        /** 召回它的意图类别（用于 Critic 上下文标注与类别配额核对，可能为 null） */
        String category;
        String addonEvidence;
        JsonNode addonVersion;
        final Map<String, String> addonCores = new LinkedHashMap<>();
        Map<String, String> addonVersionProjects = Map.of();
        final Map<yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability, String> goalEvidence =
                new EnumMap<>(yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability.class);
        final Set<String> goalFacets = new LinkedHashSet<>();
        /** 文本相关度：字段权重 × 命中词元数 × 词长权重（在线召回路径由多个虚拟线程累加，必须并发安全） */
        final DoubleAdder textScore = new DoubleAdder();
        /** 命中的词元（去重、并发安全）：写进 Critic 上下文，让审核员看得见"依据是什么" */
        final Set<String> hitTerms = ConcurrentHashMap.newKeySet();
        /** 热度归一系数 ∈ [POP_FLOOR, 1]，全局分母确定后写入 */
        double popNorm = 1.0;
        /** 排序分由文本分与热度形成；附属组合阶段在单线程中一次应用联动系数。 */
        double finalScore;

        public CandidateMod(String slug, String title, String desc, long downloads) {
            this.slug = slug;
            this.title = title == null ? "" : title.substring(0, Math.min(200, title.length()));
            this.desc = desc == null ? "" : desc.substring(0, Math.min(600, desc.length()));
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
     * <p>所有构筑经有界任务队列执行；委派开关仅决定 Modrinth 取数是否交给浏览器。
     * 等待浏览器的线程必须活过当前 HTTP 请求，并在任务期限或取消时结束。
     */
    private final ReplacementSelections replacementSelections = new ReplacementSelections();
    private final OverlapSelections overlapSelections = new OverlapSelections();
    private final PlanningClarifications planningClarifications = new PlanningClarifications();

    private String doChat(Map<String, String> payload, String authToken,
                          String apiKeyOverride, Long convId) {
        return doChat(payload, authToken, apiKeyOverride, convId, null);
    }
    private String doChat(Map<String, String> payload, String authToken,
                          String apiKeyOverride, Long convId, ReplacementSelections.Resumed admittedResume) {
        return doChat(payload, authToken, apiKeyOverride, convId, admittedResume, null, false);
    }
    private String doChat(Map<String, String> payload, String authToken,
                          String apiKeyOverride, Long convId, ReplacementSelections.Resumed admittedResume,
                          String clarificationQuestion, boolean basicParametersOffered) {
        String prompt = payload.get("prompt");
        if (prompt != null && prompt.trim().matches("(?:取消构建|放弃本次需求)[。！!]?"))
            return "已取消本次待确认构建，整合包清单保持不变。";
        String currentMods = payload.get("currentMods");
        String uuid = payload.getOrDefault("uuid", "default-user");
        // 🗑️ 用户在图谱里显式删除的模组（P2）：本回合不允许再被复述/召回/依赖引擎补回
        Set<String> excludedSlugs = parseSlugCsv(payload.get("excludedSlugs"));
        final yagen.waitmydawn.maa.model.agent.BuildConstraints previousConstraints;
        final yagen.waitmydawn.maa.model.ImportedPack importedPack;
        final AssemblySnapshot assembly;
        try {
            String rawAssembly = payload.get("assembly");
            assembly = rawAssembly == null || rawAssembly.isBlank() ? null
                    : new tools.jackson.databind.ObjectMapper().readValue(rawAssembly, AssemblySnapshot.class);
            String rawImported = payload.get("importedPack");
            importedPack = rawImported == null || rawImported.isBlank() ? null
                    : new tools.jackson.databind.ObjectMapper().readValue(rawImported, yagen.waitmydawn.maa.model.ImportedPack.class);
            if (importedPack != null) importedPack.requireEnvironment(payload.get("loader"), payload.get("mcVersion"));
            String rawConstraints = payload.get("constraints");
            previousConstraints = rawConstraints == null || rawConstraints.isBlank()
                    ? yagen.waitmydawn.maa.model.agent.BuildConstraints.empty() : AGENT_CODEC.readConstraints(rawConstraints);
        } catch (RuntimeException invalid) {
            return "⚠️ 当前包约束无效或导入版本信息无效，本轮未修改清单。请重新打开已保存的包版本。";
        }

        // 从 token 获取用户 API Key (后端加密存储, 不暴露给前端)
        String effectiveApiKey = effectiveKey(authToken, apiKeyOverride);

        // 仍未找到 → 返回友好提示
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            if (authToken != null && userController.validateToken(authToken) != null) {
                return "请先在右上角 ⚙️ 设置 中配置你的 DeepSeek API Key (格式: sk-...)，配置后即可对话。";
            }
            return "请先登录，并在设置页配置个人 API Key。";
        }
        // 供 lambda 捕获（effectiveApiKey 上面被赋值过，不是 effectively final）
        final String apiKeyForCall = effectiveApiKey;

        // 获取用户 ID (用于保存对话历史)
        Long userId = userIdFor(authToken);
        // 🧠 本次构筑参考哪一类知识库规则：与预览（ModpackController.previewPack）共用同一处判定。
        //    两边必须一致 —— 否则会出现"引擎按一份规则集把节点拉进包、预览按另一份不画那条线"。
        KnowledgeRule.SourceType excludeRuleSource = userController.excludesUserFeedbackRules(authToken)
                ? KnowledgeRule.SourceType.USER_FEEDBACK : null;
        String userKey = userLogKey(userId, uuid);
        MaaLog.setUserKey(userKey);
        MaaLog.app("请求开始 user=" + userKey
                + " promptLen=" + (prompt == null ? 0 : prompt.length())
                + " currentModsCount=" + (currentMods == null ? 0 : currentMods.split(",").length));

        StringBuilder sb = new StringBuilder();
        if (assembly != null) sb.append("【构建来源口径】\n已保存文件数 ").append(assembly.versions().size())
                .append("；声明根 ").append(assembly.roots()).append("；根来源已核实：").append(assembly.rootsKnown())
                .append("。依赖不重复计作根，版本协调由 Java 负责。\n\n");
        sb.append("【当前包的持久约束（EDIT 默认继承，NEW/EMPTY 重置）】：\n")
                .append(AGENT_CODEC.write(previousConstraints)).append("\n");
        var criterionKeys = new LinkedHashMap<String, String>();
        previousConstraints.requirements().forEach(r -> r.criteria().forEach(c -> criterionKeys.put(c.key(r.id()), c.source())));
        sb.append("【已有子目标解除标识】releaseRequirements 可引用明确放弃的子目标键，保留同项其它目标：\n")
                .append(new tools.jackson.databind.ObjectMapper().writeValueAsString(criterionKeys)).append('\n');
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
                    sb.append("  请在生成 searchIntents 时为偏好类别分配更高权重，并参考偏好模组的类别方向。\n\n");
                }
            });
        }

        // 🏷️ P3-1：注入类别唯一权威源（19 个规范名 + 中文释义 + 推荐检索词）。
        // 既约束"只能填规范名"，也给中文口语需求提供落点，提升"自然语言 → 类别"的命中率。
        sb.append(CategoryRegistry.promptBlock()).append("\n");
        // 📖 P3-5：注入别名词典（来自 maa_db/aliases.json，与 ArchitectPackTools 共用同一份）
        String aliasBlock = aliasRegistry.promptBlock();
        if (!aliasBlock.isEmpty()) sb.append(aliasBlock).append("\n");

        // 缺失字段按默认值补齐，使模型上下文与执行器具有一致的继承环境。
        String reqMc = payload.get("mcVersion");
        String reqLoader = payload.get("loader");
        final BuildIntent.Environment currentEnvironment;
        try {
            currentEnvironment = BuildEnvironment.current(reqMc, reqLoader);
        } catch (IllegalArgumentException invalid) {
            return "⚠️ 当前整合包环境格式无效，本轮未修改清单。请重新选择版本与加载器。";
        }
        sb.append("【当前整合包环境】（这是权威值）\n")
                .append("Minecraft 版本: ").append(currentEnvironment.mcVersion())
                .append("，加载器: ").append(currentEnvironment.loader()).append("\n")
                .append("除非用户【明确要求更换环境】，否则 environment 原样继承，changeRequested=false。\n\n");

        // 规划只表达环境意图；加载器支持事实由 Java 查维护表校验。
        sb.append("【环境与版本号】MC 新版本用 xx.y.z 命名：xx=年份后缀（2026→26）、y=季度、z=补丁；"
                + "例如 26.2 = 2026 年第 2 季度，26.1.2 = 26.1 的第 2 个补丁。不要把 26.x 当成 1.21.x 的加载器号。\n")
                .append("用户明确要求切换 MC 版本或加载器时，environment 填完整目标环境，changeRequested=true，由 Java 校验；")
                .append("**不要凭记忆判断某个版本号存不存在**，也不要因为\"当前环境\"不同就拒绝切换。")
                .append("未点名的环境字段继承当前环境；没有当前环境时默认 NEOFORGE/1.21.1。\n\n");
        sb.append("【用户指令】：\n").append(prompt).append("\n");
        if (clarificationQuestion != null) sb.append("【上一轮待确认说明】（用于解释用户答复，不是新增内容要求）：\n")
                .append(clarificationQuestion).append("\n");
        try {
            sb.append("【Java 原文需求片段】requirements 必须逐条保留下列 id/source；映射项目和能力，不宣称事实满足：\n")
                    .append(new tools.jackson.databind.ObjectMapper().writeValueAsString(RequirementPlanning.anchors(prompt))).append('\n');
            sb.append("【Java 子目标原文片段】criteria.source 使用各片段内的精确连续子串，独立用途按语义分解；完整主题名和项目名不拆词。纯项目与上下文项可为空：\n")
                    .append(new tools.jackson.databind.ObjectMapper().writeValueAsString(RequirementPlanning.criterionAnchors(prompt))).append('\n');
        } catch (IllegalArgumentException invalid) {
            return "⚠️ 需求片段过长或过多，请分轮描述；当前清单未修改。";
        }

        // 🚦 同一会话 + 同一账号同时只跑一轮：一轮要跑几十秒到几分钟，而候选池/勾选/清单快照都是按轮算的，
        // 并发两轮会让后到的回复覆盖先到的状态。注意**只按 uuid 拦是不够的**：前端那个 uuid 每次打开页面
        // 都重新生成（store.js 的 sessionUuid 没持久化），多开标签页/换浏览器/刷新一下就是新的 uuid。
        // 所以登录用户还要占住"账号"这一个名额 —— 同一个人同时只能跑一轮，不管开几个窗口。
        // 放在 try 之前、prompt 构建之后：这里抛异常的概率极低，且 TTL 能兜住万一没释放的情况。
        if (!roundGuard.begin(uuid, userId)) {
            MaaLog.user("已有轮次在跑，拒绝并发轮次");
            return "⏳ 你上一轮构筑还在进行中（同一账号同时只能跑一轮）。"
                    + "可以点「终止思考」结束它，或等它跑完再发指令。";
        }

        try {
            System.out.println("\n=======================================================");
            System.out.println("🤖 [阶段 1] 呼叫规划师 (Architect Agent) 分析意图与蓝图...");
            PackSessionState state = new PackSessionState();
            state.setImportedPack(importedPack);
            state.setAssembly(assembly);
            ReplacementSelections.Resumed resumed = admittedResume;
            if (resumed == null && payload.containsKey("replacementTicket")) {
                try { resumed = replacementSelections.resume(payload.get("replacementTicket"), userId, payload); }
                catch (IllegalArgumentException invalid) { return "⚠️ " + invalid.getMessage() + "，本轮未修改清单。"; }
            }
            yagen.waitmydawn.maa.runtime.BuildProgress.stage(resumed == null ? "解析需求与规划构建" : "恢复已确认的候选方案");
            AiAgentService.AgentCallResult architectCall = resumed == null
                    ? callAgentWithRetry("规划师(Architect)", uuid, () -> aiAgentService.planBlueprint(sb.toString(), apiKeyForCall))
                    : resumed.pending().architect();
            BuildIntent intent;
            List<AgentContractCodec.Failure> plannerFailures = new ArrayList<>(resumed == null ? List.of() : resumed.pending().failures());
            AgentContractCodec codec = AGENT_CODEC;
            try {
                intent = resumed == null ? RequirementPlanning.bind(prompt, codec.readBuildIntent(architectCall.text()), aliasRegistry.all()) : resumed.intent();
                if (basicParametersOffered) RequirementPlanning.validateConfirmedBasics(prompt, intent, clarificationQuestion);
            } catch (IllegalArgumentException invalid) {
                if (invalid instanceof AgentContractCodec.ValidationException validation) plannerFailures.add(validation.failure());
                if (invalid instanceof RequirementPlanning.InvalidPlan validation) plannerFailures.add(
                        new AgentContractCodec.Failure("BuildIntent", AgentContractCodec.FailureKind.SEMANTIC, validation.field()));
                MaaLog.app("Architect 契约校验失败: " + invalid.getMessage());
                if (isAborted(uuid)) return "⛔ 思考已手动终止。";
                String repairInput = RequirementPlanning.repairInput(sb.toString(), architectCall.text(), invalid);
                var repair = callAgentWithRetry("规划师格式修正", uuid,
                        () -> aiAgentService.planBlueprint(repairInput,
                                apiKeyForCall));
                architectCall = architectCall.followedBy(repair);
                try {
                    intent = RequirementPlanning.bind(prompt, codec.readBuildIntent(architectCall.text()), aliasRegistry.all());
                    if (basicParametersOffered) RequirementPlanning.validateConfirmedBasics(prompt, intent, clarificationQuestion);
                } catch (IllegalArgumentException stillInvalid) {
                    if (stillInvalid instanceof AgentContractCodec.ValidationException validation) plannerFailures.add(validation.failure());
                    if (stillInvalid instanceof RequirementPlanning.InvalidPlan validation) plannerFailures.add(
                            new AgentContractCodec.Failure("BuildIntent", AgentContractCodec.FailureKind.SEMANTIC, validation.field()));
                    MaaLog.app("Architect 修正仍未通过: " + stillInvalid.getMessage());
                    TraceData failed = new TraceData();
                    failed.architect = architectCall;
                    failed.formatViolation = true;
                    failed.resolutionStatus = "NOT_STARTED";
                    failed.plannerFailures = List.copyOf(plannerFailures);
                    return retainFailedPlanning("⚠️ 规划结果未通过结构化校验，本轮未修改模组清单。可回复“重试”，或补充修改需求。\n<trace>"
                            + buildTrace(failed) + "</trace>", payload, userId, convId, clarificationQuestion, basicParametersOffered);
                }
            }
            state.setPlannerFailures(plannerFailures);
            intent = BuildEnvironment.resolve(intent, reqMc, reqLoader);
            String basicQuestion = BasicBuildInputs.confirmation(prompt, intent);
            if (userId != null && resumed == null && (intent.mode() == BuildIntent.Mode.CLARIFY
                    || (!basicParametersOffered && !basicQuestion.isBlank()))) {
                String question = intent.mode() == BuildIntent.Mode.CLARIFY ? intent.reply() : basicQuestion;
                String clarification = planningClarifications.put(userId, convId, payload, question, intent.mode() != BuildIntent.Mode.CLARIFY);
                TraceData waiting = new TraceData();
                waiting.architect = architectCall;
                waiting.intent = intent;
                waiting.awaitingInput = true;
                waiting.resolutionStatus = "NOT_STARTED";
                waiting.plannerFailures = state.getPlannerFailures();
                return BuildExecutionPlan.escape(question) + "\n<clarification_context>"
                        + new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("schemaVersion", 1, "ticket", clarification))
                        + "</clarification_context>\n<trace>" + buildTrace(waiting) + "</trace>";
            }
            final yagen.waitmydawn.maa.model.agent.BuildConstraints.Applied appliedConstraints;
            try {
                appliedConstraints = resumed == null ? previousConstraints.apply(intent, excludedSlugs)
                        : new yagen.waitmydawn.maa.model.agent.BuildConstraints.Applied(intent, resumed.constraints());
            } catch (IllegalArgumentException conflict) {
                return "⚠️ 本轮计划与已有约束（包括排除约束）不一致，本轮未修改清单。若要解除限制，请明确说明要放弃的要求或重新允许的模组、类别。";
            }
            intent = appliedConstraints.intent();
            excludedSlugs = new LinkedHashSet<>(appliedConstraints.constraints().exclude());
            String aiBlueprint = codec.write(intent);
            if (intent.mode() == BuildIntent.Mode.CLARIFY || intent.mode() == BuildIntent.Mode.REJECT) {
                TraceData noBuild = new TraceData();
                noBuild.architect = architectCall;
                noBuild.intent = intent;
                noBuild.resolutionStatus = "NOT_STARTED";
                return BuildExecutionPlan.display(intent) + "\n<trace>" + buildTrace(noBuild) + "</trace>";
            }
            String plannedLoader = intent.environment().loader().name().toLowerCase(Locale.ROOT);
            String plannedMc = intent.environment().mcVersion();
            if (!loaderVersionService.supports(plannedLoader, plannedMc))
                return "⚠️ 当前维护的加载器版本表不支持 " + plannedLoader + "/" + plannedMc + "，本轮未修改清单。";
            var requestedProjects = new LinkedHashSet<>(intent.mustHave());
            requestedProjects.addAll(intent.add());
            if (requestedProjects.size() > 500) return "⚠️ 本轮指定项目超过上限，请减少点名模组数量；当前清单未修改。";
            if (!requestedProjects.isEmpty()) {
                yagen.waitmydawn.maa.runtime.BuildProgress.stage("核实必选项目及兼容候选");
                var requirements = RequiredModPreflight.check(apiClient, intent, importedPack, requestedProjects);
                state.setRequiredMods(requirements);
                if (isAborted(uuid)) return "⛔ 思考已手动终止。";
                if (requirements.status().equals("BLOCKED")) {
                    List<RequiredModAlternatives.Group> groups = new ArrayList<>();
                    for (var entry : requirements.entries()) {
                        if (isAborted(uuid)) return "⛔ 思考已手动终止。";
                        if (entry.availability() == RequiredModsReport.Availability.NO_COMPATIBLE_VERSION)
                            groups.add(RequiredModAlternatives.discover(apiClient, entry.slug(), intent.environment(), excludedSlugs));
                        else if (entry.availability() == RequiredModsReport.Availability.UNVERIFIED)
                            groups.add(new RequiredModAlternatives.Group(entry.slug(), List.of(), false));
                    }
                    if (isAborted(uuid)) return "⛔ 思考已手动终止。";
                    TraceData blocked = new TraceData();
                    blocked.architect = architectCall; blocked.intent = intent;
                    blocked.plannerFailures = state.getPlannerFailures(); blocked.requiredMods = requirements;
                    blocked.missingMustHave = requirements.unavailable(); blocked.resolutionStatus = "NOT_STARTED";
                    String ticket = replacementSelections.put(new ReplacementSelections.Pending(userId,
                            ReplacementSelections.snapshot(payload), intent, appliedConstraints.constraints(), architectCall,
                            List.copyOf(plannerFailures), List.copyOf(groups)));
                    return (groups.stream().anyMatch(g -> !g.verified())
                            ? "部分取数失败，不能据此判断不兼容；可取消后重试或补充准确 slug。\n" : "")
                            + "已检查原项目及兼容候选，请选择替代项目后继续构建；当前清单尚未修改。"
                            + "\n<replacement_options>" + MessageJson.write(
                                    Map.of("ticket", ticket, "groups", groups, "loader", plannedLoader, "mcVersion", plannedMc))
                            + "</replacement_options>\n<trace>" + buildTrace(blocked) + "</trace>";
                }
            }
            var generation = resumed == null ? null : resumed.pending().generation();
            var retrievalBudget = generation == null ? new RetrievalBudget(intent.count() == null ? 0 : intent.count().value()) : generation.budget();
            if (intent.requirements().stream().anyMatch(r -> !r.criteria().isEmpty())) retrievalBudget.reserveForRepair();
            state.setRetrievalBudget(retrievalBudget);
            if (generation != null) {
                state.setConfirmedGeneration(generation.selected());
                var retainedFacts = new LinkedHashMap<String, String>();
                generation.groups().forEach(g -> g.candidates().stream().filter(c -> generation.selected().getOrDefault(g.original(), List.of()).contains(c.slug()))
                        .forEach(c -> retainedFacts.merge(c.slug(), c.description() + "\n" + c.evidence(), (a, b) -> a + "\n" + b)));
                state.setGenerationFacts(retainedFacts);
            }
            else if (!RecommendationPolicy.namedOnly(prompt) && (intent.mode() == BuildIntent.Mode.NEW || intent.mode() == BuildIntent.Mode.EDIT)) {
                var protectedProjects = new LinkedHashSet<>(intent.mustHave()); protectedProjects.addAll(intent.add());
                if (assembly != null) assembly.origins().forEach((slug, roles) -> {
                    if (!Collections.disjoint(roles, Set.of("CORE", "EXPLICIT_ADD", "IMPORTED"))) protectedProjects.add(slug);
                });
                var freshIds = RequirementPlanning.anchors(prompt).keySet();
                boolean generationEdit = intent.mode() == BuildIntent.Mode.EDIT;
                var discoveryIntent = new BuildIntent(intent.schemaVersion(), intent.mode(), intent.reply(), intent.name(), intent.environment(), intent.count(),
                        intent.mustHave(), intent.add(), intent.remove(), intent.exclude(), intent.expandAddons(), intent.replacementPolicy(),
                        intent.searchIntents(), intent.excludedCategories(), intent.maxDownloads(), intent.constraintChanges(), intent.requirements().stream()
                            .filter(r -> !generationEdit || freshIds.contains(r.id())).toList());
                var generationGroups = List.<ContentOverlap.Group>of();
                if (!DimensionGeneration.targets(discoveryIntent.requirements()).isEmpty()) {
                    yagen.waitmydawn.maa.runtime.BuildProgress.stage("核实维度核心生成候选");
                    generationGroups = GenerationChoices.discover(apiClient, discoveryIntent, protectedProjects, retrievalBudget, () -> isAborted(uuid));
                }
                if (!generationGroups.isEmpty()) {
                    var validationGroups = generationGroups.stream().map(g -> new RequiredModAlternatives.Group(g.original(), g.candidates().stream()
                            .map(c -> new RequiredModAlternatives.Candidate(c.slug(), c.title(), c.description(), c.iconUrl(), c.downloads(), 100, c.relation())).toList(), true)).toList();
                    var context = new ReplacementSelections.GenerationContext(generationGroups, retrievalBudget,
                            resumed == null ? Map.of() : resumed.replacements(), Map.of());
                    String ticket = replacementSelections.put(new ReplacementSelections.Pending(userId, ReplacementSelections.snapshot(payload), intent,
                            appliedConstraints.constraints(), architectCall, List.copyOf(plannerFailures), validationGroups, context));
                    return "请选择各维度的核心世界生成项目；当前清单尚未修改，等待不占用构建名额。\n<replacement_options>"
                            + MessageJson.write(Map.of("kind", "GENERATION", "ticket", ticket, "groups", generationGroups, "loader", plannedLoader, "mcVersion", plannedMc))
                            + "</replacement_options><trace>" + MessageJson.write(Map.of("branch", "AWAITING_INPUT", "awaitingInput", true,
                                    "retrieval", retrievalBudget.snapshot())) + "</trace>";
                }
            }
            if (intent.environment().changeRequested()) {
                state.setEnvChange(currentEnvironment.mcVersion() + " + "
                        + currentEnvironment.loader().name().toLowerCase(Locale.ROOT)
                        + " → " + plannedMc + " + " + plannedLoader);
            }
            if (isAborted(uuid)) {
                System.out.println("🛑 用户终止 — 放弃后续蓝图组装。");
                return "⛔ 思考已手动终止。";
            }

            if (resumed != null && currentMods != null) {
                var changed = new LinkedHashSet<String>();
                for (String slug : parseSlugCsv(currentMods)) changed.addAll(resumed.replacements().getOrDefault(slug, List.of(slug)));
                currentMods = String.join(",", changed);
            }
            String assembled = processAndAssembleBlueprint(aiBlueprint, currentMods, prompt, effectiveApiKey, uuid, state,
                    excludedSlugs, excludeRuleSource, architectCall,
                    payload.get("mcVersion"), payload.get("loader"), null);
            // 仅有实际清单输出才提交新约束；失败或取消不能改写工作区状态。
            if (assembled.contains("<mods>") && resumed != null && !resumed.replacements().isEmpty()) {
                assembled = "<replacement_choices>" + new tools.jackson.databind.ObjectMapper().writeValueAsString(
                        Map.of("schemaVersion", 1, "choices", resumed.replacements())) + "</replacement_choices>\n" + assembled;
            }
            if (!assembled.contains("<mods>")) return assembled;
            assembled += "\n<constraints>" + AGENT_CODEC.write(appliedConstraints.constraints()) + "</constraints>";
            yagen.waitmydawn.maa.runtime.BuildProgress.stage("核查内容重叠与确认选项");
            return overlapSelections.prepare(userId, convId, payload, assembled, state.getFinishedAssembly(),
                    state.getFinishedClosure(), apiClient, () -> isAborted(uuid), state.getConfirmedGeneration());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            System.err.println("AI 调用异常: " + e.getClass().getSimpleName());
            MaaLog.error("AI 调用异常", e);

            // 先用**状态码**判定（准），判不出来再退回下面的文本匹配（旧逻辑，保留兜底）
            String llmNote = llmErrorNote(e);
            if (llmNote != null) {
                MaaLog.user("LLM 错误按状态码归类: " + e.getClass().getSimpleName());
                return retainFailedPlanning(llmNote, payload, userId, convId, clarificationQuestion, basicParametersOffered);
            }
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
            return "AI 调用未能完成，请稍后重试。";
        } finally {
            MaaLog.clearUserKey();
            abortedSessions.remove(uuid);
            roundGuard.end(uuid);        // 必须在 finally：否则这个 uuid 要等 TTL 才解锁
        }
    }

    private String retainFailedPlanning(String result, Map<String, String> payload, Long owner, Long conversation,
                                         String priorQuestion, boolean basicParametersOffered) {
        if (owner == null) return result;
        String question = priorQuestion == null ? "上一轮规划未完成。可以回复“重试”继续原始需求，或补充修改；不必重新描述全部主题。" : priorQuestion;
        try {
            String ticket = planningClarifications.put(owner, conversation, payload, question, basicParametersOffered);
            return result + "\n<clarification_context>" + new tools.jackson.databind.ObjectMapper().writeValueAsString(
                    Map.of("schemaVersion", 1, "ticket", ticket)) + "</clarification_context>";
        } catch (IllegalArgumentException full) { return result; }
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

    /** LLM 调用最多试几次（首次 + 2 次重试）。 */
    private static final int LLM_MAX_ATTEMPTS = 3;

    /**
     * 把 LLM 侧的失败翻译成"用户能照着做的提示"。
     *
     * <p><b>判定只用 DeepSeek 返回的 HTTP 状态码</b>：langchain4j 1.18.1 把它映射成了带状态码的异常
     * （{@code HttpException.statusCode()}），并对 429/5xx 打了 {@code RetriableException} 标记、
     * 对 401 打了 {@code NonRetriableException}。旧实现按 {@code msg.contains("401")} 这类**文本**猜，
     * 换个报错措辞就失效。
     *
     * @return 面向用户的文案；{@code null} 表示"不是能识别的 LLM 错误"，交给上层旧逻辑
     */
    static String llmErrorNote(Throwable e) {
        if (e instanceof yagen.waitmydawn.maa.service.ModelCallLimiter.DeadlineExceeded)
            return "⏱️ 模型调用超过本次等待上限，本轮已停止等待，请稍后重试。";
        if (e instanceof yagen.waitmydawn.maa.service.ModelCallLimiter.CapacityExceeded)
            return "⏳ 模型调用暂时繁忙，请稍后重试。";
        if (e == null) return null;
        int code = (e instanceof dev.langchain4j.exception.HttpException http) ? http.statusCode() : -1;
        if (e instanceof dev.langchain4j.exception.AuthenticationException || code == 401) {
            return "❌ DeepSeek 拒绝了这次请求：API Key 无效或已过期（HTTP 401）。"
                    + "请到右上角 ⚙️ 设置 更新你的 Key。";
        }
        if (code == 402) return "❌ 你的 DeepSeek 账户余额不足（HTTP 402），请充值后再试。";
        if (code == 403) return "❌ 你的 API Key 没有调用该模型的权限（HTTP 403）。";
        if (e instanceof dev.langchain4j.exception.RateLimitException || code == 429) {
            return "⏳ 你的 API Key 触发了 DeepSeek 的速率限制（HTTP 429，通常是并发或频率超限）。"
                    + "已自动重试仍失败，请等几十秒再试；同时开多个标签页对话也会触发。";
        }
        if (e instanceof dev.langchain4j.exception.TimeoutException) {
            return "⏱️ DeepSeek 响应超时（单次上限 5 分钟）。请稍后重试，或把需求拆小一点。";
        }
        if (e instanceof dev.langchain4j.exception.ContentFilteredException) {
            return "🚫 这次提问被 DeepSeek 的内容安全策略拦截了，换个说法再试。";
        }
        if (e instanceof dev.langchain4j.exception.InvalidRequestException) {
            return "❌ DeepSeek 拒绝了这次请求（HTTP 400）。若反复出现，请把这条发我排查。";
        }
        if (e instanceof dev.langchain4j.exception.InternalServerException || code >= 500) {
            return "🛠️ DeepSeek 服务端故障（HTTP " + (code > 0 ? code : "5xx")
                    + "）。已自动重试仍失败，请稍后再试。";
        }
        return null;
    }

    /**
     * 这次失败值不值得重试：优先信 langchain4j 的 {@code RetriableException} 标记（429/5xx/超时），
     * 其次看状态码兜底；401/400 这类明确不该重试的直接放弃。
     */
    static boolean llmRetriable(Throwable e) {
        if (e instanceof dev.langchain4j.exception.RetriableException) return true;
        return e instanceof dev.langchain4j.exception.HttpException http
                && (http.statusCode() == 429 || http.statusCode() >= 500);
    }

    /**
     * 带退避的 LLM 调用：只对"值得重试"的失败重试（最多 {@link #LLM_MAX_ATTEMPTS} 次），
     * 且用户已点"终止思考"就立刻放弃 —— 不能用自动重试违背用户的终止意图
     * （langchain4j 的 maxRetries 设为 0，重试统一由此处控制）。
     */
    private AiAgentService.AgentCallResult callAgentWithRetry(String label, String uuid,
                                                              java.util.function.Supplier<AiAgentService.AgentCallResult> call) {
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                if (attempt >= LLM_MAX_ATTEMPTS || !llmRetriable(e) || isAborted(uuid)) throw e;
                long waitMs = 1000L * attempt + java.util.concurrent.ThreadLocalRandom.current().nextLong(400);
                String note = "🔁 " + label + " 调用失败（" + e.getClass().getSimpleName() + "），"
                        + waitMs + "ms 后重试（第 " + attempt + "/" + (LLM_MAX_ATTEMPTS - 1) + " 次）";
                System.out.println(note);
                MaaLog.user(note);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
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
    /** 等任务出结果/出需求的上限。任务本身也有相同的硬期限，防止 HTTP 线程无限期挂住 */
    private static final long TASK_WAIT_LIMIT_MS = 20 * 60 * 1000L;

    @PostMapping
    public ResponseEntity<Object> chat(@RequestBody Map<String, String> payload,
                                       @RequestHeader(value = "X-Auth-Token", required = false) String authToken,
                                       @RequestHeader(value = "X-LLM-Api-Key", required = false) String apiKeyOverride,
                                       @RequestHeader(value = "X-Conversation-Id", required = false) Long convId) {
        if (userIdFor(authToken) == null) return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        if (payload.values().stream().anyMatch(Objects::isNull)) {
            return ResponseEntity.badRequest().body(Map.of("error", "请求字段不能为 null"));
        }
        String prompt = payload.get("prompt");
        if (prompt == null || prompt.isBlank() || prompt.length() > 8000
                || payload.getOrDefault("currentMods", "").length() > 64000
                || payload.getOrDefault("excludedSlugs", "").length() > 64000
                || !payload.getOrDefault("uuid", "default-user").matches("[A-Za-z0-9._-]{1,128}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "指令为空或超过输入限额"));
        }
        // 委派开启：整个流程挪到长生命周期的虚拟线程上——挂起等浏览器的线程必须活得比这次请求长。
        // RequestScope 在这里（提交时）捕获，否则任务里发出的服务器请求统计不到本轮 <trace>。
        // 用和 doChat 完全一样的推导规则，否则委派那几行会写进另一个日志文件
        Long uid = userIdFor(authToken);
        String userKey = userLogKey(uid, payload.getOrDefault("uuid", "default-user"));
        String ticket = payload.get("replacementTicket");
        DelegationTasks.Task task;
        try {
            String clarification = payload.get("clarificationTicket");
            if (ticket != null && clarification != null) throw new IllegalArgumentException("不能同时提交两类待确认选择");
            task = ticket != null && "OVERLAP".equals(payload.get("selectionKind"))
                    ? overlapSelections.admit(ticket, uid, convId, payload, selected -> delegationTasks.submit(null, uid, userKey,
                        RequestScope.current(), selected::complete))
                    : clarification != null
                    ? planningClarifications.admit(clarification, uid, convId, payload, prepared -> delegationTasks.submit(null, uid, userKey,
                        RequestScope.current(), () -> doChat(prepared, authToken, apiKeyOverride, convId, null, prepared.get("clarificationQuestion"),
                            Boolean.parseBoolean(prepared.get("basicParametersOffered")))))
                    : ticket == null
                    ? delegationTasks.submit(null, uid, userKey, RequestScope.current(), () -> doChat(payload, authToken, apiKeyOverride, convId))
                    : replacementSelections.admit(ticket, uid, payload, resumed -> delegationTasks.submit(null, uid, userKey,
                        RequestScope.current(), () -> doChat(payload, authToken, apiKeyOverride, convId, resumed)));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        }
        return continueOrReturn(task);
    }

    @PostMapping("/overlaps/{ticket}/cancel")
    public ResponseEntity<Object> cancelOverlap(@PathVariable String ticket,
            @RequestHeader("X-Auth-Token") String token) {
        Long owner = userIdFor(token);
        if (owner == null) return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        return ResponseEntity.ok(Map.of("cancelled", overlapSelections.cancel(ticket, owner)));
    }

    @PostMapping("/replacements/{ticket}/cancel")
    public ResponseEntity<Object> cancelReplacement(@PathVariable String ticket,
            @RequestHeader("X-Auth-Token") String token) {
        Long owner = userIdFor(token);
        if (owner == null) return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        replacementSelections.cancel(ticket, owner);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 浏览器取完数据回传。返回的仍是"下一份需求清单或最终回复"，所以前端不需要轮询。
     *
     * <p>校验三件事：键必须是服务器<b>此刻真的在等</b>的、结构必须自洽（id/slug 对得上、
     * 版本的环境要匹配请求的环境）、条数有上限。过了这几关才唤醒挂起的线程。
     */
    @PostMapping("/task/{taskId}/facts")
    public ResponseEntity<Object> submitFacts(@PathVariable String taskId, @RequestBody JsonNode body,
                                              @RequestHeader(value = "X-Auth-Token", required = false) String authToken) {
        DelegationTasks.Task task = delegationTasks.find(taskId);
        if (task == null) {
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "任务不存在或已过期", "taskId", taskId));
        }
        // 随机 taskId 不能替代账号归属。无主内部任务也不经公开接口交付；
        // 他人任务与不存在的任务统一返回 404，且必须在已完成结果读取之前校验。
        Long uid = userIdFor(authToken);
        if (uid == null || !uid.equals(task.ownerId())) {
            return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "任务不存在或已过期", "taskId", taskId));
        }
        if (task.isDone()) {
            return continueOrReturn(task);
        }
        task.received();
        logReceipt(task, body, applyFacts(task.context(), body));
        return continueOrReturn(task);
    }

    /**
     * 回执日志：要让"浏览器代做了多少、服务端自己做了多少"一眼看得见。
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
    public String abortChat(@RequestBody Map<String, String> payload,
                            @RequestHeader(value = "X-Auth-Token", required = false) String authToken) {
        String uuid = payload.getOrDefault("uuid", "default-user");
        // token 优先取请求头；页面卸载时前端用 sendBeacon 发的请求带不了自定义头，所以 body 里也放了一份
        Long uid = userIdFor(authToken != null ? authToken : payload.get("token"));
        // 只在这个会话**真的在跑**时才置终止标记：不然会留一个没人消费的 true 在表里
        // （典型场景：用户在上一轮刚结束的瞬间点了"终止思考"），下一次正常请求一进来就被它秒杀，
        // 白烧一次规划师调用才回"已手动终止"。顺便也挡住了"拿别人 uuid 乱终止"。
        if (uid == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "未登录");
        boolean running = roundGuard.isRunning(uuid) && uid.equals(roundGuard.ownerOf(uuid));
        if (running) abortedSessions.put(uuid, true);
        // 刷新过页面时 uuid 已经换成新的了：把该账号**所有**在跑的轮次都标记上，
        // 否则用户点了终止，老那一轮看不到标记，会带着残缺数据一路跑完（只有新 uuid 被标记）。
        for (String u : roundGuard.activeUuidsOf(uid)) abortedSessions.put(u, true);
        // 挂起的线程最长要等一个委派预算（默认 180 秒）才自己醒来，用户点了终止不该再等——直接放行
        // 按账号找而不是按 userKey：userKey 里带 uuid，而 uuid 每次刷新都会换，
        // 刷新页面后点终止就永远打不中原来那个任务（这是修之前真实存在的问题）。
        int woken = uid != null
                ? delegationTasks.cancelByOwner(uid)
                : delegationTasks.cancelByUser("anon-" + sanitizeUuid(uuid));
        System.out.println("🛑 收到用户终止指令 — uuid=" + uuid + "，running=" + running
                + (woken > 0 ? "，已唤醒 " + woken + " 个等数据的任务" : ""));
        return "ok";
    }

    /** 任务跑完 → 返回最终文本；跑到缺口 → 返回需求清单 */
    private ResponseEntity<Object> continueOrReturn(DelegationTasks.Task task) {
        task.received();
        if (delegationTasks.awaitFirstDemand(task, 1000)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(needDataEnvelope(task));
        }
        if (!task.isDone()) return ResponseEntity.accepted().body(Map.of(
                "taskId", task.taskId(), "stage", task.hasStarted() ? "running" : "queued",
                "queuePosition", delegationTasks.queuePosition(task), "progress", task.context().progress().snapshot()));
        delegationTasks.resultDelivered(task);
        try {
            String text = task.result().get(TASK_WAIT_LIMIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            logSettlement(task);
            task.context().progress().finish();
            return textResponse(text + "\n<build_progress>" + MessageJson.write(Map.of("taskId", task.taskId(),
                    "entries", task.context().progress().snapshot())) + "</build_progress>");
        } catch (Exception e) {
            delegationTasks.cancel(task);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            MaaLog.error("委派任务异常", cause);
            return textResponse("AI 调用未能完成，请稍后重试。");
        }
    }

    /**
     * 结算日志：一轮构筑结束后，把"外包多少 / 浏览器完成多少 / 服务端自抓多少"一次报清。
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
        List<DelegationContext.Want> pending = task.context().dispatchWants();
        task.dispatched();
        int round = task.context().countDispatched(pending.size());
        logDispatch(task, pending, round);

        List<Map<String, Object>> wanted = new ArrayList<>();
        for (DelegationContext.Want want : pending) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", want.wantId());
            item.put("kind", want.kind().name().toLowerCase());
            item.put("key", want.key());
            if (want.kind() == DelegationContext.Kind.ENV_VERSION || want.kind() == DelegationContext.Kind.ENV_VERSIONS) {
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
        envelope.put("stage", pending.isEmpty() ? "running" : "need-data");
        envelope.put("perCallBudgetMs", delegationTasks.callBudgetMs());
        envelope.put("wanted", wanted);
        envelope.put("progress", task.context().progress().snapshot());
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
        ctx.recordClientMetrics(body.path("client"));
        FactsTally tally = new FactsTally();
        acceptFactList(ctx, body.path("answered"), Outcome.GOT, tally);
        acceptFactList(ctx, body.path("missing"), Outcome.ABSENT, tally);
        // 第三类：客户端没抓到（网络不通/被拦/批量端点整批失败）。
        // 这一类和 missing 必须分开——当成"上游确实没有"会让服务器得出"该模组不存在"的错误结论。
        acceptFactList(ctx, body.path("unavailable"), Outcome.TIMEOUT, tally);
        return tally;
    }

    void acceptTaskFacts(DelegationContext ctx, JsonNode body) { applyFacts(ctx, body); }

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
                boolean structured = kind == DelegationContext.Kind.ENV_VERSIONS ? data.isArray() : data.isObject();
                if (!structured || !selfConsistent(kind, key, data)) {
                    tally.shapeRejected(key);
                    // 同一响应重取不会修复结构错误；终止此键的委派，交由服务端核验。
                    ctx.handBack(kind, key, "RECEIPT_REJECTED");
                    continue;
                }
                ctx.deliver(kind, key, data);
            } else if (mode == Outcome.ABSENT) {
                ctx.deliver(kind, key, null);
            } else {
                // 立刻放行等待线程让它自己抓，而不是干等到预算耗尽
                ctx.handBack(kind, key, fact.path("reason").asText("UNSPECIFIED"));
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
     * 免得脏数据把流程带偏。真伪这一层本来就不防：数据只在本次任务里用，既不进共享缓存
     * 也不会传播给别人，最坏结果是发起者自己拿到一个错包。
     */
    static boolean selfConsistent(DelegationContext.Kind kind, String key, JsonNode data) {
        // 跨版本数据包的单个发布也可能支持数百个快照；上限用于约束载荷而非推断兼容性。
        if ((kind == DelegationContext.Kind.VERSION || kind == DelegationContext.Kind.ENV_VERSION)
                && (data.path("dependencies").size() > 128 || data.path("files").size() > 32
                || data.path("game_versions").size() > 2048 || data.path("loaders").size() > 32)) return false;
        if (kind == DelegationContext.Kind.SEARCH && data.path("hits").size() > 100) return false;
        if (kind == DelegationContext.Kind.ENV_VERSIONS) {
            if (!data.isArray() || data.size() > 4) return false;
            Set<String> ids = new HashSet<>();
            for (var version : data) if (!ids.add(version.path("id").asText())
                    || !selfConsistent(DelegationContext.Kind.ENV_VERSION, key, version)) return false;
            return true;
        }
        return switch (kind) {
            case PROJECT -> key.equals(data.path("id").asText("")) || key.equals(data.path("slug").asText(""));
            case VERSION -> key.equals(data.path("id").asText(""));
            case ENV_VERSION -> matchesEnv(key, data);
            case ENV_VERSIONS -> false;
            case SEARCH -> data.path("hits").isArray();
        };
    }

    /** 版本对象必须真的声明支持请求里的 mc + loader，否则这次的"这个模组能装"就是空口无凭 */
    private static boolean matchesEnv(String key, JsonNode version) {
        String[] parts = key.split("\\|", 3);
        if (parts.length != 3) return false;
        if (!version.path("id").asText("").matches("[A-Za-z0-9_-]{1,128}")) return false;
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

    /** 检查是否已终止：用户手动终止，或这一轮已被判定为"客户端已离开" */
    private boolean isAborted(String uuid) {
        // 浏览器跑了（刷新/关标签/崩溃/断网）之后这一轮没必要再跑：它只会挨个等预算超时、
        // 回源去挤那把全局令牌闸，还占着"同一账号同时只跑一轮"的名额不放。
        // 标记打在上下文上，而这里的调用点都发生在该轮自己的上下文里（DelegationContext.runWith）。
        DelegationContext ctx = DelegationContext.current();
        if (ctx != null && ctx.abandoned()) return true;
        return Boolean.TRUE.equals(abortedSessions.getOrDefault(uuid, false));
    }

    /** 轮询心跳中断后回收任务，包括尚在队列中的请求。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void reapAbandonedRounds() {
        delegationTasks.expireDisconnected();
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
                if (modrinthCacheService.isAvailable() && DelegationContext.current() == null) {
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
        if (DelegationContext.current() == null) rememberVersionNegative(slug, loader, mcVersion);
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
        Map<String, Integer> weights = new LinkedHashMap<>();
        intents.forEach(it -> weights.merge(it.category(), it.ratio(), Integer::sum));
        return yagen.waitmydawn.maa.model.WeightedAllocation.allocate(weights, rootBudget);
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

    /** 契约使用相对权重，旧配额引擎使用百分比；保留关键词数组，避免分隔符二次解析。 */
    static List<SearchIntent> structuredIntents(List<BuildIntent.SearchIntent> planned) {
        int weightSum = planned.stream().mapToInt(BuildIntent.SearchIntent::weight).sum();
        return planned.stream().map(intent -> new SearchIntent(intent.category(),
                Math.max(1, (int) Math.round(100.0 * intent.weight() / weightSum)), intent.keywords())).toList();
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
            return yagen.waitmydawn.maa.model.WeightedAllocation.allocate(budget, POOL_CAP);
        }
        return budget;
    }

    /** 在召回预算内校验候选池；网络并发受委派波次和访问层限额控制。 */
    private List<CandidateMod> verifyPoolCompatibility(List<CandidateMod> pool,
                                                       String loader, String mcVersion) {
        return verifyPoolCompatibility(pool, loader, mcVersion, new yagen.waitmydawn.maa.model.RetrievalBudget());
    }
    private List<CandidateMod> verifyPoolCompatibility(List<CandidateMod> pool,
                                                       String loader, String mcVersion, yagen.waitmydawn.maa.model.RetrievalBudget budget) {
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
                if (!budget.version(c.slug)) continue;
                futures.add(ScopedExecutors.runAsync(() -> {
                    MaaLog.runWithUser(ctxKey, () -> {
                        try {
                            Compat compat = probeCompatibility(c.slug, loader, mcVersion);
                            if (compat == Compat.OK) verified.add(c);
                            else if (compat == Compat.UNVERIFIED) { unverified.incrementAndGet(); budget.unknown(); }
                        } catch (Exception ignored) { budget.unknown(); }
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
            c.finalScore = c.textScore.sum() * PreferenceRanking.factor(c.preferences, c.downloads, max, c.published, java.time.Instant.now());
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
        return buildFillerPoolLocal(intents, budget, loader, mcVersion, existing, maxDownloads, new RetrievalBudget());
    }
    private List<CandidateMod> buildFillerPoolLocal(List<SearchIntent> intents, Map<String, Integer> budget,
                                                    String loader, String mcVersion,
                                                    Set<String> existing, long maxDownloads, RetrievalBudget resources) {
        Map<String, List<CandidateMod>> byCategory = new LinkedHashMap<>();
        for (SearchIntent intent : intents) {
            if (budget.getOrDefault(intent.category(), 0) <= 0) continue;
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
        return finalizePool(byCategory, budget, loader, mcVersion, "本地", resources);
    }

    /**
     * 建池收口（本地与在线两条召回路径共用）：定全池热度分母 → 算最终分 → 每类排序截预算 →
     * 有界过采样并校验兼容性 → 每类截预算 → 合并按 slug 去重 → 全局截 {@link #POOL_CAP}。
     */
    private List<CandidateMod> finalizePool(Map<String, List<CandidateMod>> byCategory,
                                            Map<String, Integer> budget,
                                            String loader, String mcVersion, String tag) {
        return finalizePool(byCategory, budget, loader, mcVersion, tag, new RetrievalBudget());
    }
    private List<CandidateMod> finalizePool(Map<String, List<CandidateMod>> byCategory,
                                            Map<String, Integer> budget,
                                            String loader, String mcVersion, String tag, RetrievalBudget resources) {
        long poolMaxDownloads = applyGlobalScores(byCategory.values());
        // 每类最多核查两倍候选，先剔除不兼容项再占用候选额度，避免高分旧版本挤掉可用项。
        Map<String, CandidateMod> checks = new LinkedHashMap<>();
        for (var entry : byCategory.entrySet()) {
            entry.getValue().sort(SCORE_THEN_DOWNLOADS);
            for (var candidate : entry.getValue().stream().limit(Math.max(0, budget.getOrDefault(entry.getKey(), 0)) * 2L).toList())
                checks.merge(candidate.slug, candidate, (a, b) -> SCORE_THEN_DOWNLOADS.compare(a, b) <= 0 ? a : b);
        }
        List<CandidateMod> toVerify = checks.values().stream().sorted(SCORE_THEN_DOWNLOADS).limit(RetrievalBudget.MAX_VERSIONS).toList();
        Set<String> compatible = verifyPoolCompatibility(toVerify, loader, mcVersion, resources).stream()
                .map(c -> c.slug).collect(Collectors.toSet());
        List<CandidateMod> merged = new ArrayList<>();
        for (Map.Entry<String, List<CandidateMod>> e : byCategory.entrySet()) {
            List<CandidateMod> catPool = e.getValue().stream().filter(c -> compatible.contains(c.slug)).sorted(SCORE_THEN_DOWNLOADS).toList();
            int cap = budget.getOrDefault(e.getKey(), 0);
            int taken = Math.min(Math.max(0, cap), catPool.size());
            System.out.println("   📊 类别配额 [" + e.getKey() + "] 预算=" + cap
                    + " → 命中 " + catPool.size() + "，取 " + taken);
            merged.addAll(catPool.subList(0, taken));
        }

        long distinctSlugs = merged.stream().map(c -> c.slug).distinct().count();
        List<CandidateMod> out = dedupeAndCap(merged);
        System.out.println("   🧮 " + tag + "建池: 类别命中合计 " + merged.size() + " → 跨类去重 "
                + (merged.size() - distinctSlugs)
                + " → 入池 " + out.size() + " 个 (全池最高下载量=" + poolMaxDownloads + ")");
        return out;
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
     * <p>类别归属优先取本地元数据的完整类别列表；缺失时才使用候选池标签。
     * 都取不到的归入 {@code 未分类}，**不计入缺口**
     * —— 不能把"查不到"说成"包里没有"。
     */
    static Map<String, Integer> inPackCategoryCounts(Collection<String> packSlugs,
                                                     Map<String, Set<String>> poolCategories,
                                                     ModrinthCacheService cache) {
        Map<String, Integer> counts = new TreeMap<>();
        int unclassified = 0;
        for (String slug : packSlugs) {
            Set<String> cats = new LinkedHashSet<>();
            if (cache != null && cache.isAvailable()) {
                ModrinthCacheService.ModEntry entry = cache.find(slug);
                if (entry != null && entry.categories() != null) {
                    entry.categories().stream()
                            .filter(yagen.waitmydawn.maa.model.CategoryRegistry.CATEGORY_SET::contains)
                            .forEach(cats::add);
                }
            }
            if (cats.isEmpty() && poolCategories != null) cats.addAll(poolCategories.getOrDefault(slug, Set.of()));
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
     * <p>为什么要有它：平替/剔除以前只写日志——用户看不到"X 被换成了 Y""Z 被丢弃"，
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
        return sanitizePackInitialMods(slugs, loader, mcVersion, unverifiedOut, deltasOut, userPackNorms,
                BuildIntent.ReplacementPolicy.FUNCTIONAL_ALTERNATIVES);
    }

    private Set<String> sanitizePackInitialMods(Set<String> slugs, String loader, String mcVersion,
                                                Set<String> unverifiedOut, List<ModDelta> deltasOut,
                                                Set<String> userPackNorms, BuildIntent.ReplacementPolicy policy) {
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
            Compat existence = probeProjectExistence(slug);
            if (existence == Compat.UNVERIFIED) {
                sanitized.add(slug);
                existingNorms.add(norm);
                unverifiedOut.add(slug);
                continue;
            }
            if (existence == Compat.UNSUPPORTED) {
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
            if (policy != BuildIntent.ReplacementPolicy.FUNCTIONAL_ALTERNATIVES) {
                deltasOut.add(new ModDelta(u.slug(), "DROPPED", "未找到兼容版本；替代策略未允许基于名称的功能平替",
                        userPackNorms.contains(normKey(u.slug()))));
                continue;
            }
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

    private Compat probeProjectExistence(String slug) {
        if (modrinthCacheService.isAvailable() && modrinthCacheService.find(slug) != null) return Compat.OK;
        try {
            JsonNode info = apiClient.getProjectInfo(slug);
            return info != null && info.has("id") ? Compat.OK : Compat.UNSUPPORTED;
        } catch (Exception e) {
            // 上游不可用不构成项目不存在的证据，不能因此替换或删除用户点名项。
            return Compat.UNVERIFIED;
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
     *       循环会一路扫到"下载量最高又恰好在包里"的那个模组，把它当成"用户要的其实已经在包里了"
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
            Compat existence = probeProjectExistence(core);
            if (existence == Compat.UNVERIFIED) {
                result.add(core);
                unverifiedOut.add(core);
                continue;
            }
            boolean real = existence == Compat.OK;
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

    private List<CandidateMod> filterPlanCandidates(List<CandidateMod> candidates, BuildExecutionPlan plan) {
        if (plan == null) return candidates;
        List<CandidateMod> allowed = new ArrayList<>();
        for (CandidateMod candidate : candidates) {
            String request = String.join("\n", plan.intent().requirements().stream().map(yagen.waitmydawn.maa.model.agent.BuildRequirement::source).toList());
            if (!WorldGenerationPolicy.blocked(request, candidate.slug, candidate.desc).isEmpty()) continue;
            if (plan.blocked().contains(candidate.slug) || candidate.downloads > plan.intent().maxDownloads()) continue;
            if (!plan.intent().excludedCategories().isEmpty()) {
                ModEntry cached = modrinthCacheService.find(candidate.slug);
                Set<String> actualCategories = new HashSet<>();
                if (cached != null) actualCategories.addAll(cached.categories());
                else {
                    JsonNode info = apiClient.getProjectInfo(candidate.slug);
                    if (info == null || !info.path("categories").isArray()) continue;
                    info.path("categories").forEach(category -> actualCategories.add(category.asText()));
                }
                if (plan.intent().excludedCategories().stream().anyMatch(actualCategories::contains)) continue;
            }
            allowed.add(candidate);
        }
        return allowed;
    }

    private String processAndAssembleBlueprint(String aiBlueprint, String currentMods, String prompt,
                                               String effectiveApiKey, String uuid, PackSessionState state,
                                                Set<String> excludedSlugs,
                                                KnowledgeRule.SourceType excludeRuleSource,
                                                AiAgentService.AgentCallResult architectCall,
                                                String defaultMc, String defaultLoader,
                                                yagen.waitmydawn.maa.model.EnvIntent envIntent) {
        try {
            BuildIntent parsedIntent = aiBlueprint != null && aiBlueprint.stripLeading().startsWith("{")
                    ? AGENT_CODEC.readBuildIntent(aiBlueprint) : null;
            final BuildIntent structured = parsedIntent == null ? null
                    : BuildEnvironment.resolve(parsedIntent, defaultMc, defaultLoader);
            RetrievalBudget retrieval = state.getRetrievalBudget() != null ? state.getRetrievalBudget() : structured == null ? new RetrievalBudget() : new RetrievalBudget(
                    structured.count() == null ? 0 : structured.count().value());
            if (structured != null && structured.requirements().stream().anyMatch(r -> !r.criteria().isEmpty())) retrieval.reserveForRepair();
            var originalFiles = new LinkedHashSet<>(cleanSlugs(currentMods == null ? "" : currentMods));
            BuildExecutionPlan execution = structured == null ? null : new BuildExecutionPlan(structured,
                    state.getAssembly() == null ? originalFiles : state.getAssembly().rootsIn(originalFiles), excludedSlugs);
            if (execution != null) {
                excludedSlugs = execution.blocked();
                aiBlueprint = BuildExecutionPlan.display(structured);
                for (String removed : structured.remove()) {
                    if (structured.mode() == BuildIntent.Mode.EDIT && execution.original().contains(removed))
                        state.removeSlug(removed);
                }
                if (!structured.name().isBlank()) state.setPackName(BuildExecutionPlan.escape(structured.name()));
            }
            // EnvIntent 仅兼容旧 XML 路径；结构化计划的环境已在统一边界解析。
            String loader = envIntent != null && envIntent.loader() != null
                    ? envIntent.loader()
                    : extractTag(aiBlueprint, "loader",
                        defaultLoader == null || defaultLoader.isBlank() ? "neoforge" : defaultLoader.trim());
            String mcVersion = envIntent != null
                    ? envIntent.mcVersion()
                    : extractTag(aiBlueprint, "mc",
                        defaultMc == null || defaultMc.isBlank() ? "1.21.1" : defaultMc.trim());
            boolean namedOnly = RecommendationPolicy.namedOnly(prompt);
            int targetCount = Integer.parseInt(extractTag(aiBlueprint, "target_count", "100"));
            long maxDownloads = Long.parseLong(extractTag(aiBlueprint, "max_downloads", "2100000000"));
            if (structured != null) {
                loader = structured.environment().loader().name().toLowerCase(Locale.ROOT);
                mcVersion = structured.environment().mcVersion();
                maxDownloads = structured.maxDownloads();
            }

            String coreModsStr = extractTag(aiBlueprint, "core_mods", "");
            String expandAddonsStr = extractTag(aiBlueprint, "expand_addons", "");
            String searchIntentsStr = extractTag(aiBlueprint, "search_intents", "");
            if (structured != null) {
                expandAddonsStr = String.join(",", structured.expandAddons());
                searchIntentsStr = structured.searchIntents().stream()
                        .map(it -> it.category() + ":" + it.weight() + ":" + String.join(",", it.keywords()))
                        .collect(Collectors.joining(" | "));
            }
            // 🏷️ P3-2：类别合法性闸门 —— 归一化别名，未知类别整组丢弃（绝不回退成全库检索）
            Set<String> invalidCategories = new LinkedHashSet<>();
            if (structured == null) searchIntentsStr = normalizeSearchIntents(searchIntentsStr, invalidCategories);
            // 🧮 P3-3：把 ratio 解析成结构化的类别配额（旧实现解析后直接丢弃 parts[1]，比例纯装饰）
            List<SearchIntent> intents = structured == null ? parseIntents(searchIntentsStr)
                    : structuredIntents(structured.searchIntents());
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
            // 用户已有包的规范化集合：只用于标注"这条调整动的是包里原有的东西，还是本轮新增的"
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
            if (execution != null) {
                initialMods = execution.initial();
                targetCount = execution.targetCount(initialMods.size());
            }
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
                Set<String> requiredVerified = state.getRequiredMods() == null ? Set.of()
                        : state.getRequiredMods().entries().stream().filter(e -> e.availability() == RequiredModsReport.Availability.AVAILABLE)
                                .map(RequiredModsReport.Entry::slug).collect(Collectors.toSet());
                Set<String> toSanitize = new LinkedHashSet<>(initialMods);
                toSanitize.removeAll(requiredVerified);
                Set<String> checked = sanitizePackInitialMods(toSanitize, loader, mcVersion, unverifiedMods,
                        modDeltas, userPackNorms, structured == null ? BuildIntent.ReplacementPolicy.FUNCTIONAL_ALTERNATIVES
                                : structured.replacementPolicy());
                initialMods.removeIf(s -> !requiredVerified.contains(s) && !checked.contains(s));
                initialMods.addAll(checked);
            }

            yagen.waitmydawn.maa.runtime.BuildProgress.stage("召回内容与核实兼容版本");
            String generationRequest = prompt + "\n" + (structured == null ? "" : String.join("\n",
                    structured.requirements().stream().map(yagen.waitmydawn.maa.model.agent.BuildRequirement::source).toList()));
            Map<String, String> admissionFacts = new LinkedHashMap<>();
            Set<String> preservedGeneration = structured != null && structured.mode() == BuildIntent.Mode.EDIT
                    ? Set.copyOf(originalFiles) : Set.of();
            var initialBlocked = generationAdmission(initialMods.stream().filter(s -> !preservedGeneration.contains(s)).toList(),
                    generationRequest, admissionFacts);
            initialMods.removeAll(initialBlocked.keySet());
            int needed = targetCount - initialMods.size();
            if (structured != null && structured.mode() == BuildIntent.Mode.EDIT && structured.count() != null
                    && structured.count().scope() == BuildIntent.CountScope.TOTAL_FILES && state.getAssembly() != null)
                needed = targetCount - (originalFiles.size() - (int) originalFiles.stream().filter(excludedSlugs::contains).count()
                        + (int) initialMods.stream().filter(s -> !originalFiles.contains(s)).count());
            List<GoalCatalog.Profile> goalProfiles = namedOnly ? List.of() : GoalCatalog.active(structured, targetCount);
            if (structured != null && structured.mode() == BuildIntent.Mode.EDIT) {
                Set<String> currentRequirementIds = RequirementPlanning.anchors(prompt).keySet();
                var freshCapabilities = structured.requirements().stream().filter(r -> currentRequirementIds.contains(r.id()))
                        .filter(r -> r.kind() != yagen.waitmydawn.maa.model.agent.BuildRequirement.Kind.CHANGE)
                        .flatMap(r -> r.capabilities().stream()).collect(Collectors.toSet());
                goalProfiles = goalProfiles.stream().filter(p -> freshCapabilities.contains(p.capability())).toList();
            }
            var optimizationGoals = namedOnly ? List.<OptimizationPortfolio.Goal>of() : OptimizationPortfolio.goals(structured, RequirementPlanning.anchors(prompt).keySet(), true);
            var reportOptimizationGoals = OptimizationPortfolio.goals(structured, Set.of(), false);
            Map<String, OptimizationPortfolio.Fact> optimizationFacts = new LinkedHashMap<>();
            final List<GoalCatalog.Profile> activeGoals = goalProfiles.stream()
                    .filter(p -> optimizationGoals.isEmpty() || p.capability() != yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability.PERFORMANCE).toList();
            var genericGoals = namedOnly ? List.<yagen.waitmydawn.maa.model.agent.BuildRequirement>of() : RequirementRetrieval.active(structured, RequirementPlanning.anchors(prompt).keySet());
            Map<String, Set<String>> requirementHints = new LinkedHashMap<>();
            Map<String, CandidateMod> requirementCandidates = new LinkedHashMap<>();
            Map<String, Set<yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability>> goalRoles = new LinkedHashMap<>();
            Map<String, Set<String>> goalFacets = new LinkedHashMap<>();
            Map<yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability, Integer> goalTargets = new EnumMap<>(yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability.class);
            activeGoals.forEach(p -> goalTargets.put(p.capability(), p.target()));
            // 🧮 P3-6：target_count 是"最终总数"，但发给 Critic 的必须是"根模组数"——
            // 中间隔着依赖穿透（实测平均膨胀 1.3~2.1 倍）。这里按历史观测折算，避免"要 100 出 177"。
            double expansionFactor = expansionTracker.factor(loader, mcVersion);
            int rootBudget = rootBudgetOf(needed, expansionFactor);
            if (execution != null) rootBudget = execution.rootBudget(initialMods.size(), expansionFactor);
            if (state.getAssembly() != null && structured != null && structured.mode() == BuildIntent.Mode.EDIT
                    && structured.count() != null && structured.count().scope() == BuildIntent.CountScope.TOTAL_FILES)
                rootBudget = rootBudgetOf(needed, expansionFactor);
            // 本轮闸门上界：补量轮 = EF 折算后的预算；纯附属轮（target_count 已达标）= 附属额度（不能是 0）
            int addonCores = structured != null ? structured.expandAddons().size() : expandAddonsStr.isEmpty() ? 0
                    : (int) Arrays.stream(expandAddonsStr.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).count();
            if (namedOnly) { rootBudget = 0; intents = List.of(); }
            int roundBudget = roundBudgetOf(rootBudget, addonCores);
            if (structured != null && structured.count() != null
                    && structured.count().precision() == BuildIntent.Precision.EXACT) roundBudget = rootBudget;
            if (!namedOnly && (!activeGoals.isEmpty() || !genericGoals.isEmpty()) && structured.mode() == BuildIntent.Mode.EDIT) {
                int requested = activeGoals.stream().mapToInt(GoalCatalog.Profile::target).sum() + genericGoals.size();
                int room = structured.count() == null ? requested : Math.max(0,
                        (structured.count().range() || structured.count().precision() == BuildIntent.Precision.EXACT
                                ? structured.count().max() : (int) Math.ceil(structured.count().value() * 1.5)) - initialMods.size());
                roundBudget = Math.max(roundBudget, Math.min(requested, room));
            }
            final boolean goalMode = (!activeGoals.isEmpty() || !genericGoals.isEmpty()) && roundBudget > 0;
            if (goalMode && !prompt.matches("(?is).*\\blibrary\\b.*"))
                intents = intents.stream().filter(i -> !i.category().equals("library")).toList();
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
            /** Critic 输出是否未通过协议校验；确定性补位必须同时标记降级。 */
            boolean criticDegraded = false;
            boolean criticCoverageDegraded = false;
            RequirementCoverage.VerificationCounts coverageVerification = null;
            Map<String, RequirementCoverage.EvidenceCheck> coverageChecks = new LinkedHashMap<>();
            int criticOverlapRemoved = 0, criticExcessApproved = 0, criticRejectedCount = 0;
            boolean criticBackfillAllowed = true;
            int candidatePoolSize = 0;
            // P3-6 审核结果闸门的过程计数
            int approvedKept = 0, droppedNotInPool = 0, droppedOverQuota = 0, droppedDuplicate = 0, backfilledCount = 0;
            // 本轮是否真的召回过"意图候选池"：没召回过的类别谈不上"配比未兑现"，不能对用户虚报
            boolean fillerFetched = false;
            Map<String, CandidateMod> addonCandidates = new LinkedHashMap<>();
            Set<String> addonEligible = new LinkedHashSet<>(), addonVetoes = new LinkedHashSet<>(), addonApproved = new LinkedHashSet<>();
            Set<String> requiredIntegrations = new LinkedHashSet<>();
            List<Map<String, Object>> addonTrace = new ArrayList<>();
            Map<String, PackAddonPolicy.Project> addonProjects = new LinkedHashMap<>();
            Map<String, PackAddonPolicy.Project> addonMetadata = new LinkedHashMap<>();
            int selectionBaseSize = initialMods.size();
            Set<String> protectedRoots = new LinkedHashSet<>(initialMods);
            Map<String, Double> selectionScores = new HashMap<>();
            List<yagen.waitmydawn.maa.model.agent.RequirementReview> coverageReviews = List.of();
            Map<String, String> coverageFacts = new LinkedHashMap<>();
            Set<String> coverageEligible = new LinkedHashSet<>();

            // ★ 空图谱模式: 无核心模组 + 无搜索意图 + 无已有模组 → 直接返回空图谱
            if (!goalMode && initialMods.isEmpty() && searchIntentsStr.isEmpty() && expandAddonsStr.isEmpty()) {
                // 区分两种完全不同的情况：用户要空图谱 vs 模型格式违规（只写文字没输出 XML）
                boolean formatViolation = structured == null && aiBlueprint.indexOf("<core_mods>") < 0
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
                if (!formatViolation) emptyTrace.assembly = new AssemblySnapshot(echoLoader, echoMc, List.of(), Map.of(), Map.of(), true);
                emptyTrace.plannerFailures = state.getPlannerFailures();
                emptyTrace.architect = architectCall;
                emptyTrace.intent = structured;
                emptyTrace.toolCalls = state.getToolCalls();
                emptyTrace.formatViolation = formatViolation;
                emptyReply = emptyReply + "\n<trace>" + buildTrace(emptyTrace) + "</trace>";
                MaaLog.user("结果: 空图谱/拒绝分支，未添加模组");
                return emptyReply;
            }

            // ==========================================
            // 🚀 多智能体重排机制：构建供 Critic 审核的上下文
            // ==========================================
            if ((needed > 0 && !intents.isEmpty()) || !expandAddonsStr.isEmpty() || goalMode) {
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
                    List<String> cores = structured == null
                            ? Arrays.stream(expandAddonsStr.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                            : structured.expandAddons();
                    cores = cores.stream().filter(retrieval::core).toList();
                    if (structured == null || structured.replacementPolicy() == BuildIntent.ReplacementPolicy.FUNCTIONAL_ALTERNATIVES)
                        cores = sanitizeExpandCores(cores, loader, mcVersion, initialMods, unverifiedMods,
                                modDeltas, userPackNorms);
                    else {
                        List<String> checkedCores = new ArrayList<>();
                        for (String core : cores) {
                            if (state.getRequiredMods() != null && state.getRequiredMods().entries().stream()
                                    .anyMatch(e -> e.slug().equals(core) && e.availability() == RequiredModsReport.Availability.AVAILABLE)) {
                                checkedCores.add(core);
                                continue;
                            }
                            Compat compatibility = probeCompatibility(core, loader, mcVersion);
                            if (compatibility != Compat.UNSUPPORTED) checkedCores.add(core);
                            if (compatibility == Compat.UNVERIFIED) unverifiedMods.add(core);
                        }
                        cores = checkedCores;
                    }
                    boolean hasOtherGoals = !genericGoals.isEmpty() || !optimizationGoals.isEmpty() || !activeGoals.isEmpty()
                            || (needed > 0 && !intents.isEmpty());
                    int addonPoolLimit = hasOtherGoals ? POOL_CAP / 2 : POOL_CAP;
                    int remainingCores = cores.size();
                    for (String core : cores) {
                        int coreLimit = Math.max(1, (addonPoolLimit - poolIndex.size()) / remainingCores--);
                        if (poolIndex.size() >= addonPoolLimit) {
                            quotaShortfalls.add("附属候选已达 " + addonPoolLimit + " 项上限，剩余核心未继续召回");
                            break;
                        }
                        List<CandidateMod> addons = buildAddonPool(core, loader, mcVersion, initialMods, retrieval,
                                addonPreferences(structured.requirements(), core));
                        addons = filterPlanCandidates(addons, execution);
                        addons = addons.stream().limit(Math.min(coreLimit, addonPoolLimit - poolIndex.size())).toList();
                        criticContext.append("--> ").append(core).append(" 的附属候选：\n");
                        for (CandidateMod c : addons) {
                            var previous = addonCandidates.putIfAbsent(c.slug, c);
                            if (previous != null) {
                                previous.addonCores.putAll(c.addonCores);
                                previous.finalScore = Math.max(previous.finalScore, c.finalScore);
                                continue;
                            }
                            rankedPool.removeIf(p -> p.slug.equals(c.slug));
                            criticContext.append(String.format("- %s | 核心:%s | 版本关系:%s | Final:%.2f | 下载量:%dW | 描述:%s\n",
                                    c.slug, core, c.addonEvidence, c.finalScore, c.downloads / 10000, c.desc + c.preferenceFacts()));
                            poolIndex.put(c.slug, new PoolEntry("附属(" + core + ")", null));
                        }
                        // 附属候选同样进补位池：用户明确要求"加某个模组的附属"时，附属就是最该优先补上的东西，
                        // 若只把意图池放进 rankedPool，纯附属请求（search_intents 为空）将无从补位。
                        addons.stream().map(c -> addonCandidates.get(c.slug)).distinct()
                                .filter(c -> rankedPool.stream().noneMatch(p -> p.slug.equals(c.slug))).forEach(rankedPool::add);
                        addons.forEach(c -> poolReserved.add(c.slug));
                    }
                    criticContext.append("\n");
                    requiresCritic = true;
                }

                // 点名核心附属先使用同一轮预算；普通召回只补剩余容量，不能挤掉关系候选。
                if (goalMode) {
                    criticContext.append("【逐项需求目标】以下数值是内容召回参考，不是玩法满足证明。优先覆盖缺失目标，不能用类别标签代替用途。\n")
                            .append(new tools.jackson.databind.ObjectMapper().writeValueAsString(structured.requirements())).append('\n');
                    var optimizationPool = buildOptimizationPool(optimizationGoals, initialMods, loader, mcVersion,
                            maxDownloads, execution, retrieval, uuid, requirementHints, optimizationFacts, targetCount);
                    var goalPool = new ArrayList<>(buildRequirementPool(genericGoals, initialMods, loader, mcVersion,
                            maxDownloads, execution, retrieval, uuid, requirementHints));
                    Set<String> recalled = goalPool.stream().map(c -> c.slug).collect(Collectors.toSet());
                    optimizationPool.stream().filter(c -> !recalled.contains(c.slug)).limit(Math.max(0, POOL_CAP - goalPool.size())).forEach(goalPool::add);
                    if (!optimizationGoals.isEmpty()) criticContext.append("【优化组合参考】仅根据介绍提供用途线索；宽泛需求以总文件数5%～10%为软参考，不是额度或性能承诺。优先互补用途，保留点名核心和已选渲染器；客户端和Minecraft服务端分开判断。\n")
                            .append(MessageJson.write(optimizationGoals)).append('\n');
                    var specialized = buildGoalPool(activeGoals, initialMods, loader, mcVersion, maxDownloads, execution, retrieval, uuid, goalRoles, goalFacets);
                    Set<String> genericSlugs = goalPool.stream().map(c -> c.slug).collect(Collectors.toSet());
                    specialized.stream().filter(c -> !genericSlugs.contains(c.slug)).limit(Math.max(0, POOL_CAP - goalPool.size())).forEach(goalPool::add);
                    for (var profile : activeGoals) criticContext.append(profile.capability()).append(" 参考 ").append(profile.target())
                            .append(" 项，用途方面：").append(GoalCatalog.desiredFacets(profile)).append('\n');
                    for (var c : goalPool) {
                        boolean alreadySubmitted = poolIndex.containsKey(c.slug);
                        if (!alreadySubmitted && poolIndex.size() >= POOL_CAP) continue;
                        CandidateMod addon = addonCandidates.get(c.slug);
                        if (addon != null) {
                            addon.goalEvidence.putAll(c.goalEvidence);
                            requirementCandidates.put(c.slug, addon);
                        } else requirementCandidates.put(c.slug, c);
                        criticContext.append("- ").append(c.slug).append(c.preferenceFacts()).append(" | 召回需求:").append(requirementHints.getOrDefault(c.slug, Set.of()))
                                .append("（检索线索，须结合原文和描述审核，不代表满足） | 用途证据:").append(c.goalEvidence)
                                .append(" | 描述:").append(c.desc).append('\n');
                        if (!alreadySubmitted) {
                            poolIndex.put(c.slug, new PoolEntry("需求用途(" + c.goalEvidence.keySet() + ")", null));
                            rankedPool.add(c);
                        }
                        poolReserved.add(c.slug);
                        if (c.category != null) poolCategoryOfSlug.put(c.slug, c.category);
                    }
                    criticContext.append("已在包内的用途证据：");
                    for (var e : goalRoles.entrySet()) if (initialMods.contains(e.getKey())) criticContext.append(e).append(' ');
                    criticContext.append('\n');
                    requiresCritic |= !goalPool.isEmpty();
                }

                // 2. 构建意图搜索多路召回池
                if (needed > 0 && !intents.isEmpty() && poolIndex.size() < POOL_CAP) {
                    System.out.println("   🔍 捕获到 AI 关键词矩阵: [" + searchIntentsStr + "]");
                    criticContext.append("【待审核意图候选池】（Final = 文本重合度 × 热度系数，越高的越相关；")
                            .append("「命中」列是它命中的检索词元；请筛除与核心存在冲突的）\n");

                    // 🧮 P3-3/P3-6：按 ratio 把"根模组预算"分配到各类，并把这本账明确告诉 Critic
                    intentBudgets = computeCategoryBudget(intents, rootBudget);
                    if (!intentBudgets.isEmpty()) {
                        criticContext.append(goalMode
                                ? "【主题候选容量】类别比例只指导主题召回；最终优先逐项目标与总额度，不用类别上限否决用途缺口候选。\n"
                                : "【类别配额】按 ratio 分配，每类上限不是下限；某类池子不足可补位，但不超过总额度。\n");
                        for (SearchIntent it : intents) {
                            criticContext.append("- ").append(it.category()).append("：")
                                    .append(it.ratio()).append("% → 目标约 ")
                                    .append(computeCategoryQuota(intents, rootBudget).getOrDefault(it.category(), 0))
                                    .append(" 个（本轮提供 ").append(intentBudgets.getOrDefault(it.category(), 0))
                                    .append(" 个候选）\n");
                        }
                        criticContext.append("\n");
                    }

                    List<CandidateMod> fillers = buildFillerPoolByKeywords(intents, intentBudgets, loader, mcVersion,
                            poolReserved, maxDownloads, uuid, retrieval);
                    fillers = filterPlanCandidates(fillers, execution);
                    if (goalMode) for (var c : fillers) {
                        for (var p : activeGoals) {
                            String evidence = GoalCatalog.evidence(p, c.slug, c.title, c.desc);
                            if (evidence != null) {
                                c.goalEvidence.put(p.capability(), evidence);
                                c.goalFacets.addAll(GoalCatalog.facets(p, c.slug, c.title, c.desc));
                            }
                        }
                        if (!c.goalEvidence.isEmpty()) goalRoles.put(c.slug, Set.copyOf(c.goalEvidence.keySet()));
                        if (!c.goalFacets.isEmpty()) goalFacets.put(c.slug, Set.copyOf(c.goalFacets));
                    }
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
                    int limit = Math.min(fillers.size(), POOL_CAP - poolIndex.size());
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
                rankedPool.forEach(c -> selectionScores.put(c.slug, c.finalScore));
                if (requiresCritic) {
                    if (structured != null) {
                        for (String slug : initialMods) {
                            var entry = modrinthCacheService.find(slug);
                            if (entry != null) coverageFacts.put(slug, entry.title() + " " + entry.description());
                        }
                        for (var fact : state.getGenerationFacts().entrySet()) if (initialMods.contains(fact.getKey())) coverageFacts.put(fact.getKey(), fact.getValue());
                        if (!optimizationGoals.isEmpty()) {
                            var possible = rankedPool.stream().filter(c -> !OptimizationPortfolio.classify(c.desc).isEmpty()).map(c -> c.slug).limit(48).toList();
                            try {
                                var facts = apiClient.getProjectsBatch(possible);
                                facts.forEach((slug, info) -> optimizationFacts.put(slug, OptimizationPortfolio.fact(slug, info)));
                            } catch (RuntimeException unavailable) { retrieval.unknown(); }
                            for (var candidate : rankedPool) if (!optimizationFacts.containsKey(candidate.slug))
                                optimizationFacts.put(candidate.slug, new OptimizationPortfolio.Fact(candidate.slug, OptimizationPortfolio.Side.UNKNOWN,
                                        OptimizationPortfolio.classify(candidate.desc), PackAddonPolicy.infrastructure(candidate.slug, candidate.title, Set.of())));
                        }
                        rankedPool.forEach(c -> coverageFacts.merge(c.slug, c.title + " " + c.desc,
                                (prior, fresh) -> prior.contains(fresh) ? prior : prior + "\n" + fresh));
                        var generationTargets = DimensionGeneration.targets(structured.requirements());
                        var generationSources = rankedPool.stream().filter(c -> requirementHints.getOrDefault(c.slug, Set.of()).stream()
                                .anyMatch(generationTargets::containsKey)).map(c -> c.slug).limit(24).toList();
                        if (!generationSources.isEmpty()) {
                            try {
                                apiClient.getProjectsBatch(generationSources).forEach((slug, info) -> {
                                    String body = info.path("body").asText();
                                    if (!body.isBlank() && coverageFacts.containsKey(slug)) coverageFacts.put(slug,
                                            coverageFacts.get(slug) + "\n" + body.substring(0, Math.min(3200, body.length())));
                                });
                            } catch (RuntimeException unavailable) { retrieval.unknown(); }
                        }
                        optimizationFacts.forEach((slug, fact) -> {
                            if (coverageFacts.containsKey(slug)) coverageFacts.put(slug, coverageFacts.get(slug) + "\n"
                                    + String.join("\n", new LinkedHashSet<>(fact.roles().values())));
                        });
                        coverageFacts.replaceAll((slug, text) -> {
                            int limit = generationSources.contains(slug) || state.getGenerationFacts().containsKey(slug) ? 4000 : optimizationFacts.containsKey(slug) ? 2400 : 800;
                            return text.length() > limit ? text.substring(0, limit) : text;
                        });
                        criticContext.append("\n【完整有效需求账本】\n")
                                .append(new tools.jackson.databind.ObjectMapper().writeValueAsString(structured.requirements()))
                                .append("\n【审核子目标结构骨架】以下只固定父项id和子目标source的对应关系，不表示已满足。"
                                        + "每个source逐字保留，恰好审核一次，允许调整顺序；证据和缺口填在子目标，父项evidence=[]、gap=空字符串。"
                                        + "缺少依据的子目标必须填写真实gap，不能直接复制空白骨架当作完整审核。其它无子目标需求仍按需求账本审核。\n")
                                .append(MessageJson.write(RequirementCoverage.reviewSkeleton(structured.requirements())))
                                .append("\n【项目用途事实】quote 只能逐字摘录下列值，不能摘录召回标签或相关性分数：\n")
                                .append(new tools.jackson.databind.ObjectMapper().writeValueAsString(coverageFacts));
                        if (!generationTargets.isEmpty()) criticContext.append("\n【维度核心生成目标】每个目标应优先选择一个有明确对应维度地貌/大规模生成依据的兼容候选。"
                                + "结构、生物、跨维度泛称群系不能替代核心生成；quote须包含对应维度及大规模生成改动的原文。无依据时保留真实缺口，不猜测。\n")
                                .append(MessageJson.write(generationTargets));
                        if (!optimizationGoals.isEmpty()) criticContext.append("\n【优化用途与侧别事实】UNKNOWN不能证明支持仅客户端或仅服务端要求；用途引用来自同一项目介绍，不以模组名或optimization标签判定。\n")
                                .append(MessageJson.write(optimizationFacts));
                    }
                    if (goalMode) candidatePoolSize = poolIndex.size();
                    System.out.println("\n=======================================================");
                    System.out.println("🕵️‍♂️ [阶段 3] 海选池建立完毕！提交给 Critic Agent 审核员...");
                    System.out.println("   ⏳ 等待审核员结合描述和评分进行最终裁决...");

                    if (isAborted(uuid)) return "⛔ 思考已手动终止。";

                    {
                        var admissionBlocked = generationAdmission(rankedPool.stream().map(c -> c.slug).toList(), generationRequest, admissionFacts);
                        var declinedGeneration = rankedPool.stream().filter(c -> admissionBlocked.containsKey(c.slug)
                                || !WorldGenerationPolicy.blocked(generationRequest, c.slug, c.desc).isEmpty()
                                || !GenerationChoices.allows(state.getConfirmedGeneration(), c.slug,
                                coverageFacts.getOrDefault(c.slug, c.desc))).map(c -> c.slug).collect(Collectors.toSet());
                        rankedPool.removeIf(c -> declinedGeneration.contains(c.slug));
                        poolIndex.keySet().removeAll(declinedGeneration);
                        coverageEligible.removeAll(declinedGeneration);
                        for (String slug : declinedGeneration) if (!initialMods.contains(slug)) coverageFacts.remove(slug);
                        criticContext.append("\n【已确认的维度生成选择】后续推荐不得追加同维度其它核心生成项目；局部结构、生物等互补内容仍可审核。\n")
                                .append(MessageJson.write(state.getConfirmedGeneration()))
                                .append("\n【世界生成准入排除及项目原文】以下内容未获原始需求授权，不得用于数量补位或用途证据：\n")
                                .append(MessageJson.write(admissionBlocked))
                                .append("\n下列项目已移出本次候选，不得写入approved/rejected或用途引用：").append(MessageJson.write(declinedGeneration));
                        if (goalMode) candidatePoolSize = poolIndex.size();
                    }
                    final Set<String> submittedCandidates = Collections.unmodifiableSet(new LinkedHashSet<>(poolIndex.keySet()));
                    final int approvalLimit = Math.min(roundBudget, submittedCandidates.size());
                    var evidenceSources = new LinkedHashSet<>(submittedCandidates); evidenceSources.addAll(initialMods);
                    if (structured != null) criticContext.append("\n【Java审核范围】\n")
                            .append(MessageJson.write(yagen.waitmydawn.maa.model.agent.ReviewInputScope.of(
                                    yagen.waitmydawn.maa.model.agent.ReviewInputScope.Mode.SELECT_AND_REVIEW,
                                    loader, mcVersion, submittedCandidates, initialMods)));
                    yagen.waitmydawn.maa.runtime.BuildProgress.stage("审核候选用途与组合");
                    criticCall = callAgentWithRetry("审核员(Critic)", uuid,
                            () -> aiAgentService.criticPools(criticContext.toString(), effectiveApiKey,
                                    submittedCandidates, approvalLimit));
                    String criticReply = criticCall.text();
                    if (criticDegradationForced() && structured != null) {
                        criticReply = "{\"schemaVersion\":1,\"approved\":[";
                    } else if (criticDegradationForced()) {
                        // 🧪 评测故障注入：模拟"输出打满 token 上限被截断"，把闭合标签切掉
                        int close = criticReply.indexOf("</approved_mods>");
                        if (close > 0) criticReply = criticReply.substring(0, close);
                        System.out.println("   🧪 [评测注入] 已截断审核员输出，模拟 token 上限导致的标签未闭合");
                        MaaLog.user("评测注入: 已截断审核员输出（模拟退化）");
                    }

                    String approvedStr = extractTag(criticReply, "approved_mods", "");
                    List<String> decisionApproved = cleanSlugs(approvedStr);
                    Set<String> decisionRejected = new HashSet<>();
                    boolean allowBackfill = true;
                    Set<String> validatedVetoes = new HashSet<>();
                    boolean validatedBackfillOptOut = false;
                    ReviewResponse.Selection validatedSelection = null;
                    boolean structuredReview = criticReply != null && criticReply.stripLeading().startsWith("{");
                    if (structured != null || structuredReview) {
                        try {
                            ReviewDecision decision;
                            try {
                                var selectionOnly = readValidatedSelection(criticReply, submittedCandidates, approvalLimit);
                                if (selectionOnly != null) {
                                    validatedSelection = selectionOnly;
                                    selectionOnly.decision().rejected().forEach(r -> validatedVetoes.add(r.slug()));
                                    if (!selectionOnly.decision().allowBackfill()) validatedBackfillOptOut = true;
                                }
                                var response = AGENT_CODEC.readReviewResponse(criticReply);
                                if (response.rejected().stream().allMatch(r -> submittedCandidates.contains(r.slug()))) {
                                    response.rejected().forEach(r -> validatedVetoes.add(r.slug()));
                                    if (!response.allowBackfill()) validatedBackfillOptOut = true;
                                }
                                var selected = response.select(submittedCandidates, approvalLimit, evidenceSources);
                                decision = selected.decision();
                                if (structured != null) RequirementCoverage.validateReviews(structured.requirements(), decision.requirements());
                                criticOverlapRemoved = selected.overlapRemoved();
                                criticExcessApproved = selected.excessApproved();
                            } catch (IllegalArgumentException invalid) {
                                MaaLog.app("Critic 契约校验失败: " + invalid.getMessage());
                                if (criticDegradationForced()) throw invalid;
                                if (isAborted(uuid)) return "⛔ 思考已手动终止。";
                                final String invalidDecision = criticReply;
                                final boolean reportOnlyRepair = invalid instanceof RequirementCoverage.ReviewAlignmentException;
                                final String correctionReason = invalid.getMessage() + (criticCall.truncatedCalls() > 0
                                        ? "；上游报告finish_reason=length，JSON因输出额度截断。每个子目标只保留1～2条代表性引用，缩短quote/purpose及否决说明，不丢失目标和必填字段"
                                        : "");
                                final String repairInstruction = reportOnlyRepair
                                        ? "\n【用途报告定向修正】只修正requirements的父子对应关系及对应证据/缺口，"
                                            + "approved、rejected、allowBackfill必须与上一响应逐项相同（包括顺序和否决说明），不得重新选模。"
                                            + "对照审核子目标结构骨架逐项核对，保留未受影响的证据；不要将父项证据无依据地复制到所有子目标。"
                                            + "缺少依据时写明gap，不编造引用。错误列表中的criterionIndex是规划criteria的从0开始索引；pointer指向上一响应。\n"
                                            + MessageJson.write(((RequirementCoverage.ReviewAlignmentException) invalid).issues())
                                        : "。请重新裁决，批准最多 " + approvalLimit
                                            + " 项，检查候选范围、重复和批准/拒绝互斥。"
                                            + "先确定明确拒绝项，再从其余候选中选取 approved；不要输出每个未选项的解释。";
                                var repair = callAgentWithRetry("审核员格式修正", uuid,
                                        () -> aiAgentService.criticPools(criticContext + "\n【裁决修正】上一响应未通过校验："
                                                + correctionReason + repairInstruction
                                                + "修正以下上一响应，返回完整 JSON 对象：\n" + invalidDecision, effectiveApiKey,
                                                submittedCandidates, approvalLimit));
                                criticCall = criticCall.followedBy(repair);
                                criticReply = repair.text();
                                if (reportOnlyRepair) AGENT_CODEC.requireSameReviewSelection(invalidDecision, criticReply);
                                var selectionOnly = readValidatedSelection(criticReply, submittedCandidates, approvalLimit);
                                if (selectionOnly != null) {
                                    validatedSelection = selectionOnly;
                                    selectionOnly.decision().rejected().forEach(r -> validatedVetoes.add(r.slug()));
                                    if (!selectionOnly.decision().allowBackfill()) validatedBackfillOptOut = true;
                                }
                                var response = AGENT_CODEC.readReviewResponse(criticReply);
                                if (response.rejected().stream().allMatch(r -> submittedCandidates.contains(r.slug()))) {
                                    response.rejected().forEach(r -> validatedVetoes.add(r.slug()));
                                    if (!response.allowBackfill()) validatedBackfillOptOut = true;
                                }
                                var selected = response.select(submittedCandidates, approvalLimit, evidenceSources);
                                decision = selected.decision();
                                if (structured != null) RequirementCoverage.validateReviews(structured.requirements(), decision.requirements());
                                criticOverlapRemoved = selected.overlapRemoved();
                                criticExcessApproved = selected.excessApproved();
                            }
                            decisionApproved = decision.approved();
                            var verification = RequirementCoverage.verify(structured == null ? List.of() : structured.requirements(),
                                    decision.requirements(), coverageFacts,
                                    decision.rejected().stream().map(ReviewDecision.Rejection::slug).collect(Collectors.toSet()));
                            coverageReviews = verification.reviews();
                            coverageVerification = verification.counts();
                            coverageChecks.putAll(verification.checks());
                            MaaLog.user("用途证据校验摘要：" + MessageJson.write(coverageVerification));
                            decision.rejected().forEach(item -> decisionRejected.add(item.slug()));
                            allowBackfill = decision.allowBackfill();
                            criticRejectedCount = decisionRejected.size();
                            criticBackfillAllowed = allowBackfill;
                            Map<ReviewDecision.Reason, Long> rejectionReasons = decision.rejected().stream().collect(
                                    Collectors.groupingBy(ReviewDecision.Rejection::reason, Collectors.counting()));
                            MaaLog.user("Critic 裁决摘要：批准 " + decisionApproved.size() + "，明确否决 " + decisionRejected.size()
                                    + "，批准/否决重叠移除 " + criticOverlapRemoved + "，允许补位 " + allowBackfill
                                    + "，否决原因分布 " + rejectionReasons);
                            criticDegraded = false;
                        } catch (IllegalArgumentException invalid) {
                            MaaLog.app("Critic 修正后契约校验失败: " + invalid.getMessage());
                            // 修正失败不能扩大权限；累计否决和补位禁令优先于任何已批准项。
                            decisionRejected.addAll(validatedVetoes);
                            allowBackfill = !validatedBackfillOptOut;
                            if (validatedSelection != null && !criticDegradationForced()) {
                                decisionApproved = validatedSelection.decision().approved().stream()
                                        .filter(slug -> !decisionRejected.contains(slug)).toList();
                                criticOverlapRemoved = validatedSelection.overlapRemoved();
                                criticExcessApproved = validatedSelection.excessApproved();
                                criticDegraded = false;
                                criticCoverageDegraded = true;
                                MaaLog.user("用途报告未通过校验：保留合法批准 " + decisionApproved.size()
                                        + "，已核实否决 " + decisionRejected.size() + "，允许补位 " + allowBackfill
                                        + "；用途证据仍待核实，不计作满足");
                            } else {
                                criticDegraded = true;
                                decisionApproved = List.of();
                                MaaLog.user("审核降级边界：保留已核实否决 " + decisionRejected.size() + "，允许补位 " + allowBackfill);
                            }
                            criticRejectedCount = decisionRejected.size();
                        }
                    } else criticDegraded = looksLikeCriticDegeneration(criticReply);
                    criticBackfillAllowed = allowBackfill;

                    coverageEligible.addAll(decisionApproved);
                    if (allowBackfill && !criticDegraded) coverageEligible.addAll(poolIndex.keySet());
                    coverageEligible.removeAll(decisionRejected);
                    coverageEligible.removeAll(addonCandidates.keySet());

                    // Critic 输出退化（没给标签 / 标签未闭合 = 多半被 token 上限截断）：不能像旧实现那样
                    // 当成"审核员一个都没挑"就把整条兜底链跳过——那会让用户拿到 0.25 倍的包且毫不知情（坏例 B16）。
                    if (criticDegraded) {
                        String note = "⚠️ 审核员输出未通过契约校验（结构或候选引用不合法），"
                                + (allowBackfill ? "本轮改由 Java 在未否决候选中按相关性补位" : "本轮未获补位许可，保留已有清单");
                        System.out.println("   " + note);
                        MaaLog.user(note);
                    }
                    addonVetoes.addAll(decisionRejected);
                    addonEligible.addAll(decisionApproved.stream().filter(addonCandidates::containsKey).toList());
                    addonApproved.addAll(addonEligible);
                    if (allowBackfill) addonCandidates.keySet().stream()
                            .filter(s -> !addonVetoes.contains(s)).forEach(addonEligible::add);
                    var currentRoots = Set.copyOf(initialMods);
                    int presentCoreAddons = (int) addonEligible.stream().map(addonCandidates::get)
                            .filter(c -> c.addonCores.keySet().stream().anyMatch(currentRoots::contains)).count();
                    int addonReserve = Math.min(roundBudget, Math.min(presentCoreAddons, addonCores * ADDON_QUOTA_PER_CORE));
                    boolean hasContentChoice = decisionApproved.stream().anyMatch(s -> !addonCandidates.containsKey(s))
                            || (allowBackfill && rankedPool.stream().anyMatch(c -> !addonCandidates.containsKey(c.slug) && !decisionRejected.contains(c.slug)));
                    if (hasContentChoice) addonReserve = Math.min(addonReserve, Math.max(0, roundBudget - 1));
                    int contentBudget = roundBudget - addonReserve;
                    decisionApproved = decisionApproved.stream().filter(s -> !addonCandidates.containsKey(s)).toList();

                    // 有效裁决控制补位权限；明确禁止补位的空裁决不能被解释为输出异常。
                    if (!decisionApproved.isEmpty() || criticDegraded || (structuredReview && allowBackfill)) {
                        // 审核只决定资格；最终分和热度决定限额内的采纳顺序，避免模型列表顺序挤掉高相关候选。
                        rankedPool.sort(SCORE_THEN_DOWNLOADS.thenComparing(candidate -> candidate.slug));
                        Map<String, Integer> rank = new HashMap<>();
                        for (int i = 0; i < rankedPool.size(); i++) rank.putIfAbsent(rankedPool.get(i).slug, i);
                        List<String> approvedMods = decisionApproved.stream()
                                .sorted(Comparator.comparingInt(slug -> rank.getOrDefault(slug, Integer.MAX_VALUE))).toList();
                        if (goalMode) {
                            approvedMods = GoalCatalog.order(approvedMods, goalRoles, goalTargets, initialMods, goalFacets);
                            mergeRequirementHints(structured, goalRoles, requirementHints);
                            approvedMods = RequirementRetrieval.order(approvedMods, requirementHints, initialMods);
                            approvedMods = OptimizationPortfolio.order(approvedMods, optimizationOrderingHints(requirementHints, goalRoles, optimizationFacts, optimizationGoals), initialMods, optimizationFacts, optimizationGoals, targetCount);
                        }
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
                            if (kept.size() >= contentBudget) {
                                logModOut(slug, "超出本轮新增额度(" + roundBudget + ")，按低相关性收敛");
                                droppedOverQuota++;
                                continue;
                            }
                            if (!goalMode && entry.category() != null) {
                                int cap = categoryQuota.getOrDefault(entry.category(), POOL_MIN_PER_CATEGORY);
                                if (approvedPerCat.getOrDefault(entry.category(), 0) >= cap) {
                                    logModOut(slug, "超出类别[" + entry.category() + "]配额上限(" + cap + ")，按低相关性收敛");
                                    droppedOverQuota++;
                                    continue;
                                }
                            }
                            var selectedSoFar = new LinkedHashSet<>(initialMods); selectedSoFar.addAll(kept);
                            if (!optimizationCandidateInScope(slug, optimizationFacts, optimizationGoals, requirementHints, goalRoles)) {
                                logModOut(slug, "未取得符合所需优化用途或Minecraft侧别的介绍证据");
                                continue;
                            }
                            if (!OptimizationPortfolio.canAdd(slug, optimizationFacts, selectedSoFar)) {
                                logModOut(slug, "已有基础渲染器，优先保留其它优化用途；未推定运行冲突");
                                continue;
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
                        if (allowBackfill && kept.size() < contentBudget && !rankedPool.isEmpty()) {
                            int before = kept.size();
                            List<String> backfillOrder = rankedPool.stream().map(c -> c.slug).filter(s -> !addonCandidates.containsKey(s)).toList();
                            var retained = new LinkedHashSet<>(initialMods); retained.addAll(kept);
                            if (goalMode) {
                                backfillOrder = GoalCatalog.order(backfillOrder, goalRoles, goalTargets, retained, goalFacets);
                                backfillOrder = RequirementRetrieval.order(backfillOrder, requirementHints, retained);
                                backfillOrder = OptimizationPortfolio.order(backfillOrder, optimizationOrderingHints(requirementHints, goalRoles, optimizationFacts, optimizationGoals), retained, optimizationFacts, optimizationGoals, targetCount);
                            }
                            Map<String, CandidateMod> bySlug = new HashMap<>();
                            rankedPool.forEach(c -> bySlug.putIfAbsent(c.slug, c));
                            for (String candidateSlug : backfillOrder) {
                                CandidateMod c = bySlug.get(candidateSlug);
                                if (kept.size() >= contentBudget) break;
                                if (decisionRejected.contains(c.slug) || !poolIndex.containsKey(c.slug)) continue;
                                if (initialMods.contains(c.slug) || kept.contains(c.slug)) continue;
                                var selectedSoFar = new LinkedHashSet<>(initialMods); selectedSoFar.addAll(kept);
                                if (!OptimizationPortfolio.canAdd(c.slug, optimizationFacts, selectedSoFar)
                                        || !optimizationCandidateInScope(c.slug, optimizationFacts, optimizationGoals, requirementHints, goalRoles)) continue;
                                if (!goalMode && c.category != null) {
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

            if (!addonCandidates.isEmpty()) {
                var projectIdentities = new LinkedHashSet<>(initialMods);
                projectIdentities.addAll(addonCandidates.keySet());
                addonMetadata.putAll(loadAddonProjects(projectIdentities, retrieval));
                var contentRoots = Set.copyOf(initialMods);
                addonMetadata.forEach((identity, project) -> { if (contentRoots.contains(identity)) addonProjects.put(identity, project); });
                var assessments = new LinkedHashMap<String, PackAddonPolicy.Assessment>();
                for (var c : addonCandidates.values()) {
                    var project = addonMetadata.get(c.slug);
                    boolean infrastructure = PackAddonPolicy.infrastructure(c.slug, c.title, project == null ? Set.of() : project.categories());
                    var assessment = PackAddonPolicy.assess(c.slug, c.addonVersion, c.addonVersionProjects, c.addonCores, addonProjects.values(), false, true);
                    assessments.put(c.slug, assessment);
                    boolean mandatory = !infrastructure && structured != null && structured.requirements().stream()
                            .filter(r -> r.kind() == yagen.waitmydawn.maa.model.agent.BuildRequirement.Kind.PACK_INTEGRATIONS)
                            .anyMatch(r -> assessment.evidence().stream().anyMatch(e -> !e.partners().isEmpty() && r.addonCores().contains(e.core())));
                    double base = c.finalScore;
                    c.finalScore = base * assessment.coefficient();
                    selectionScores.put(c.slug, c.finalScore);
                    MaaLog.user("附属组合 " + c.slug + "：关系 " + assessment.evidence() + "，基础分 " + base
                            + "，系数 " + assessment.coefficient() + "，必选联动 " + mandatory);
                    addonTrace.add(Map.of("slug", c.slug, "baseScore", base, "score", c.finalScore,
                            "coefficient", assessment.coefficient(), "evidence", assessment.evidence(), "mandatory", mandatory,
                            "versionId", c.addonVersion.path("id").asText(),
                            "requirementIds", structured == null ? List.of() : structured.requirements().stream()
                                    .filter(r -> !Collections.disjoint(r.addonCores(), c.addonCores.keySet())).map(yagen.waitmydawn.maa.model.agent.BuildRequirement::id).toList(),
                            "status", infrastructure ? "INFRASTRUCTURE_CANDIDATE" : assessment.conflict() ? "CONFLICT" : !assessment.corePresent() ? "CORE_OR_METADATA_NOT_RETAINED"
                                    : addonVetoes.contains(c.slug) ? "VETOED" : assessment.integration() ? "CONFIRMED_RELATION" : "NO_CONTENT_PARTNER"));
                    if (mandatory) {
                        if (addonVetoes.contains(c.slug) || assessment.conflict())
                            return integrationPause("联动附属 " + c.slug + " 已核实关联，但" + (assessment.conflict() ? "存在冲突声明或未核实的冲突引用" : "被审核明确否决")
                                    + "。请指定接受的替代，或明确放弃该联动要求。", addonTrace);
                        requiredIntegrations.add(c.slug);
                    }
                    if (infrastructure) addonEligible.remove(c.slug);
                }
                int slots = Math.max(0, roundBudget - (initialMods.size() - selectionBaseSize));
                if (requiredIntegrations.size() > slots) {
                    var count = structured == null ? null : structured.count();
                    int maximum = count == null ? 500 : count.precision() == BuildIntent.Precision.EXACT || count.range()
                            ? count.max() : (int) Math.ceil(count.value() * 1.5);
                    int plannedSize = count != null && count.scope() == BuildIntent.CountScope.ADDED_ROOTS
                            ? initialMods.size() - selectionBaseSize : initialMods.size();
                    if ((count != null && count.precision() == BuildIntent.Precision.EXACT)
                            || plannedSize + requiredIntegrations.size() > maximum)
                        return integrationPause("已核实的包内联动附属有 " + requiredIntegrations.size()
                                + " 项，当前新增额度剩余 " + slots + " 项，无法同时满足数量和联动要求。请扩大数量上限，或明确放弃部分联动要求。", addonTrace);
                    slots = requiredIntegrations.size();
                }
                var selectedAddons = addonCandidates.values().stream()
                        .filter(c -> requiredIntegrations.contains(c.slug) || addonEligible.contains(c.slug))
                        .filter(c -> !addonVetoes.contains(c.slug) && assessments.get(c.slug).corePresent() && !assessments.get(c.slug).conflict())
                        .sorted(Comparator.comparing((CandidateMod c) -> !requiredIntegrations.contains(c.slug))
                                .thenComparing(SCORE_THEN_DOWNLOADS).thenComparing(c -> c.slug)).limit(slots).toList();
                for (var c : selectedAddons) if (initialMods.add(c.slug)) {
                    if (addonApproved.contains(c.slug)) approvedKept++;
                    else if (!requiredIntegrations.contains(c.slug)) backfilledCount++;
                    logModIn(c.slug, requiredIntegrations.contains(c.slug) ? "已核实的包内联动要求" : "附属组合筛选；联动系数 " + assessments.get(c.slug).coefficient());
                }
            }

            // ==========================================
            // 🛡️ 图谱引擎（真实性/版本预检已在阶段 0 完成）
            // ==========================================
            System.out.println("\n=======================================================");
            System.out.println("🛠️ [阶段 4] 启动图谱引擎与深度依赖穿透...");
            System.out.println("🕸️ 开始将所有拼合模组 (" + initialMods.size() + "个) 送入深度依赖穿透引擎...");
            // 取带 DAG 结构的结果（与 preview 共享同一份缓存），用于删除后的断链诊断
            var addonApplicability = auditAddonApplicability(initialMods, initialMods, addonCandidates, Map.of(), addonMetadata, retrieval, loader, mcVersion);
            for (var row : addonApplicability.entrySet()) if (row.getValue().blocks() && !preservedGeneration.contains(row.getKey())) {
                if (requiredIntegrations.contains(row.getKey()) || structured != null && (structured.mustHave().contains(row.getKey()) || structured.add().contains(row.getKey())))
                    return integrationPause("附属 " + row.getKey() + " 的适用对象尚未完整核实、未在包内，或会额外引入内容项目。"
                            + (row.getValue().missingPartners().isEmpty() ? "" : "缺少对象：" + row.getValue().missingPartners() + "。")
                            + (row.getValue().introducedContent().isEmpty() ? "" : "新增内容：" + row.getValue().introducedContent() + "。")
                            + "请补充联动双方的准确项目，或换一个附属再继续。", addonTrace);
                initialMods.remove(row.getKey()); coverageEligible.remove(row.getKey());
                logModOut(row.getKey(), "附属适用范围核查：" + MessageJson.write(row.getValue()));
            }
            var rootAdmission = generationAdmission(initialMods.stream().filter(s -> !preservedGeneration.contains(s)).toList(),
                    generationRequest, admissionFacts);
            if (!Collections.disjoint(rootAdmission.keySet(), requiredIntegrations))
                return generationAdmissionPause(rootAdmission);
            initialMods.removeAll(rootAdmission.keySet());
            coverageEligible.removeAll(rootAdmission.keySet());
            long depT0 = System.currentTimeMillis();
            // P6：取带完整状态与诊断的解析结果（未解析项 / 被剔除项 / 是否触顶）
            Set<String> resolutionSeeds = state.getImportedPack() != null && (structured == null || structured.mode() == BuildIntent.Mode.EDIT)
                            && state.getImportedPack().loader().equals(loader) && state.getImportedPack().mcVersion().equals(mcVersion)
                            ? state.getImportedPack().seeds(initialMods) : initialMods;
            yagen.waitmydawn.maa.runtime.BuildProgress.stage("解析依赖与协调版本");
            ResolutionResult resolution = state.getAssembly() != null && structured != null && structured.mode() == BuildIntent.Mode.EDIT
                    && state.getAssembly().matches(loader, mcVersion)
                    ? dependencyEngine.resolvePreferred(resolutionSeeds, loader, mcVersion, excludeRuleSource, state.getAssembly().versions())
                    : dependencyEngine.resolveCachedResult(resolutionSeeds, loader, mcVersion, excludeRuleSource);
            if (!initialMods.isEmpty() && resolution.graph().allSlugs.isEmpty() && !resolution.isComplete()
                    && resolution.unresolved().stream().anyMatch(item -> item.reason() == ResolutionResult.Reason.UPSTREAM_UNAVAILABLE)) {
                // 取数失败不能发布空清单；前端仅在收到 mods 协议后提交工作区变更。
                TraceData unavailable = new TraceData();
                unavailable.architect = architectCall; unavailable.critic = criticCall; unavailable.intent = structured;
                unavailable.plannerFailures = state.getPlannerFailures(); unavailable.requiredMods = state.getRequiredMods();
                unavailable.resolutionStatus = "INCOMPLETE"; unavailable.unresolvedCount = resolution.unresolved().size();
                unavailable.dependencyMs = System.currentTimeMillis() - depT0; unavailable.inputRoots = initialMods.size();
                unavailable.retrieval = retrieval.snapshot(); unavailable.coverageVerification = coverageVerification;
                MaaLog.user("依赖取数失败，最终未取得任何可留存项目，本轮不提交清单");
                return "⚠️ 依赖取数失败，尚未取得可用的项目或版本资料，本轮未修改整合包清单。请稍后重试。\n<trace>"
                        + buildTrace(unavailable) + "</trace>";
            }
            yagen.waitmydawn.maa.runtime.BuildProgress.stage("核对需求覆盖与数量");
            var canonicalIdentities = new HashMap<String, String>();
            resolution.graph().selectedVersions.forEach((slug, version) -> {
                String id = version.path("project_id").asText();
                if (!id.isBlank()) canonicalIdentities.put(id, slug);
            });
            initialMods = initialMods.stream().map(s -> canonicalIdentities.getOrDefault(s, s)).collect(Collectors.toCollection(LinkedHashSet::new));
            var canonicalProtected = protectedRoots.stream().map(s -> canonicalIdentities.getOrDefault(s, s)).collect(Collectors.toSet());
            protectedRoots.clear(); protectedRoots.addAll(canonicalProtected);
            protectedRoots.addAll(requiredIntegrations);
            requirementHints.forEach((slug, hints) -> { if (!hints.isEmpty()) protectedRoots.add(slug); });
            for (var row : addonTrace) if (Boolean.TRUE.equals(row.get("mandatory"))) {
                @SuppressWarnings("unchecked") var evidence = (List<PackAddonPolicy.Evidence>) row.get("evidence");
                evidence.forEach(e -> protectedRoots.addAll(e.partners()));
            }
            var removable = initialMods.stream().filter(s -> !protectedRoots.contains(s))
                    .sorted(Comparator.comparingDouble((String s) -> selectionScores.getOrDefault(s, 0.0)).thenComparing(s -> s)).toList();
            var quantity = PackQuantityPolicy.limit(resolution, initialMods, protectedRoots, structured == null ? null : structured.count(), removable);
            resolution = quantity.resolution();
            initialMods.retainAll(quantity.roots());
            for (String removed : quantity.removed()) logModOut(removed, "实际文件数超过数量策略上限，裁剪可选新增及孤立前置");
            var rawOptimizationReviews = coverageReviews;
            coverageReviews = OptimizationPortfolio.filterReviews(structured == null ? List.of() : structured.requirements(), coverageReviews, optimizationFacts);
            var optimizationBaseline = Set.copyOf(initialMods);
            coverageEligible.removeIf(slug -> !OptimizationPortfolio.canAdd(slug, optimizationFacts, optimizationBaseline)
                    || !optimizationCandidateInScope(slug, optimizationFacts, optimizationGoals, requirementHints, goalRoles));
            String coverageRepairStatus = "NOT_REQUESTED";
            List<String> coverageRepaired = List.of();
            var coverageProofFacts = new LinkedHashMap<>(coverageFacts);
            // 确认票据的原文独立于送审截断视图保留；无须调用审核员的轮次也可复用已核实事实。
            state.getGenerationFacts().forEach((slug, text) -> coverageProofFacts.merge(slug, text,
                    (prior, confirmed) -> prior.contains(confirmed) ? prior : confirmed + "\n" + prior));
            coverageProofFacts.keySet().removeAll(addonVetoes);
            if (!namedOnly && structured != null && !structured.requirements().isEmpty()) {
                var beforeCoverage = RequirementCoverage.assess(structured.requirements(), coverageReviews,
                        resolution.graph(), resolution.graph().allSlugs, canonicalIdentities, addonMetadata, "PENDING", List.of(), coverageChecks, coverageProofFacts);
                var freshIds = RequirementPlanning.anchors(prompt).keySet();
                var repairRequirements = structured.requirements().stream()
                        .filter(r -> structured.mode() != BuildIntent.Mode.EDIT || freshIds.contains(r.id())).toList();
                CoverageFollowup followup = null;
                if (resolution.isComplete() && !RequirementCoverage.missingTargets(repairRequirements, beforeCoverage).isEmpty()) {
                    yagen.waitmydawn.maa.runtime.BuildProgress.stage("定向召回与复核未覆盖目标");
                    followup = reviewMissingCriteria(repairRequirements, beforeCoverage, coverageReviews, coverageFacts, coverageEligible,
                            resolution.graph().allSlugs, addonVetoes, execution, retrieval, loader, mcVersion, maxDownloads, uuid, effectiveApiKey);
                    if (!optimizationGoals.isEmpty()) {
                        var unclassified = followup.eligible().stream().filter(s -> !optimizationFacts.containsKey(s)).limit(12).toList();
                        try { apiClient.getProjectsBatch(unclassified).forEach((slug, info) -> optimizationFacts.put(slug, OptimizationPortfolio.fact(slug, info))); }
                        catch (RuntimeException unavailable) { retrieval.unknown(); }
                    }
                    rawOptimizationReviews = RequirementCoverage.merge(rawOptimizationReviews, followup.updates());
                    coverageChecks.putAll(followup.checks());
                    coverageReviews = OptimizationPortfolio.filterReviews(structured.requirements(), rawOptimizationReviews, optimizationFacts);
                    coverageEligible = new LinkedHashSet<>(followup.eligible());
                    coverageEligible.removeIf(slug -> !OptimizationPortfolio.canAdd(slug, optimizationFacts, optimizationBaseline)
                    || !optimizationCandidateInScope(slug, optimizationFacts, optimizationGoals, requirementHints, goalRoles));
                    if (followup.call() != null) criticCall = criticCall == null ? followup.call() : criticCall.followedBy(followup.call());
                }
                var proposed = RequirementCoverage.repairs(beforeCoverage, coverageReviews, coverageEligible, resolution.graph().allSlugs);
                var baselineGraph = resolution.graph();
                var preferredVersions = new HashMap<String, String>();
                baselineGraph.selectedVersions.forEach((s, v) -> preferredVersions.put(s, v.path("id").asText()));
                var blockedRepair = new HashSet<>(excludedSlugs);
                var repairAdmission = generationAdmission(proposed, generationRequest, admissionFacts);
                blockedRepair.addAll(repairAdmission.keySet());
                proposed = proposed.stream().filter(s -> !repairAdmission.containsKey(s)
                        && GenerationChoices.allows(state.getConfirmedGeneration(), s, admissionFacts.getOrDefault(s, ""))).toList();
                if (execution != null) blockedRepair.addAll(execution.blocked());
                var previousRoots = execution == null ? Set.<String>of() : execution.original().stream()
                        .map(s -> canonicalIdentities.getOrDefault(s, s)).collect(Collectors.toSet());
                final String repairLoader = loader, repairMc = mcVersion;
                if (!proposed.isEmpty()) yagen.waitmydawn.maa.runtime.BuildProgress.stage("协调补缺版本与依赖");
                var repair = RequirementRepair.attempt(resolution, initialMods, proposed, blockedRepair,
                        structured.count(), previousRoots, roots -> {
                            if (isAborted(uuid)) throw new java.util.concurrent.CancellationException();
                            var seeds = roots.stream().map(s -> baselineGraph.pinnedSlugs.contains(s)
                                    ? "vid:" + preferredVersions.get(s) : s).collect(Collectors.toSet());
                            return dependencyEngine.resolveUsingFacts(seeds, repairLoader, repairMc, excludeRuleSource, baselineGraph.selectedVersions);
                        });
                resolution = repair.resolution();
                initialMods = new LinkedHashSet<>(repair.roots());
                coverageRepairStatus = repair.status(); coverageRepaired = repair.added();
                if (proposed.isEmpty() && followup != null) coverageRepairStatus = followup.status();
                for (String added : coverageRepaired) logModIn(added, "最终需求复核：已有审核元数据证据，定向补缺入包");
                MaaLog.user("最终需求补缺: " + coverageRepairStatus + "，新增 " + coverageRepaired);
            }
            yagen.waitmydawn.maa.runtime.BuildProgress.stage("复核最终附属与生成准入");
            DependencyGraph resolvedGraph = resolution.graph();
            var finalApplicability = auditAddonApplicability(initialMods, resolvedGraph.allSlugs, addonCandidates, resolvedGraph.selectedVersions, addonMetadata,
                    retrieval, loader, mcVersion);
            addonApplicability.putAll(finalApplicability);
            var blockedAddons = finalApplicability.entrySet().stream().filter(e -> e.getValue().blocks() && !preservedGeneration.contains(e.getKey())).toList();
            if (!blockedAddons.isEmpty()) return integrationPause("最终附属适用范围仍有待确认项：" + MessageJson.write(blockedAddons.stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))) + "。本轮未修改清单，请确认联动对象或更换附属。", addonTrace);
            var finalAdmission = generationAdmission(resolvedGraph.allSlugs.stream().filter(s -> !preservedGeneration.contains(s)).toList(),
                    generationRequest, admissionFacts);
            if (!finalAdmission.isEmpty()) return generationAdmissionPause(finalAdmission);
            if (state.getImportedPack() != null && structured != null && structured.mode() == BuildIntent.Mode.EDIT
                    && state.getImportedPack().loader().equals(loader) && state.getImportedPack().mcVersion().equals(mcVersion)) {
                for (var file : state.getImportedPack().files()) {
                    if (!initialMods.contains(file.slug()) || excludedSlugs.contains(file.slug())) continue;
                    JsonNode version = resolvedGraph.selectedVersions.get(file.slug());
                    if (version == null || !file.versionId().equals(version.path("id").asText()))
                        return "⚠️ 原包锁定版本未保留：" + file.slug() + "。本轮未修改清单，请检查依赖冲突或重新取数。";
                }
            }
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
            var finalVersionProjects = new HashMap<String, String>();
            resolvedGraph.selectedVersions.values().forEach(v -> finalVersionProjects.put(v.path("id").asText(), v.path("project_id").asText()));
            var retainedAddonProjects = addonMetadata.values().stream().filter(p -> finalPerfectMods.contains(p.slug()) || finalPerfectMods.contains(p.id()))
                    .map(p -> p.withVersion(resolvedGraph.selectedVersions.containsKey(p.slug()) ? resolvedGraph.selectedVersions.get(p.slug()).path("id").asText() : "")).toList();
            for (String slug : requiredIntegrations) {
                var c = addonCandidates.get(slug);
                var references = new HashMap<>(c.addonVersionProjects); references.putAll(finalVersionProjects);
                var finalEvidence = PackAddonPolicy.assess(slug, resolvedGraph.selectedVersions.get(slug), references,
                        c.addonCores, retainedAddonProjects, true);
                if (!finalPerfectMods.contains(slug) || !finalEvidence.integration() || finalEvidence.conflict())
                    return integrationPause("已要求的联动附属 " + slug + " 在依赖解析后未能保留完整关系。请更换可用版本或替代，或明确放弃该联动要求。", addonTrace);
            }
            var finalClosure = yagen.waitmydawn.maa.model.DependencyClosure.capture(resolvedGraph, resolution.unresolved(), Map.of())
                    .inspect(finalPerfectMods);
            String resultName = extractTag(aiBlueprint, "name", "整合包");
            String cleanReply = "已生成「" + resultName + "」，共 " + finalPerfectMods.size()
                    + " 个模组文件（含前置），环境为 " + loader + " / " + mcVersion + "。"
                    + "\n<name>" + resultName + "</name>\n<mc>" + mcVersion + "</mc>\n<loader>" + loader + "</loader>";
            cleanReply += "\n<dependency_audit>" + new tools.jackson.databind.ObjectMapper().writeValueAsString(finalClosure) + "</dependency_audit>";

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
                cleanReply = cleanReply + "\n\n⚠️ 本轮审核员（Critic）的输出未通过校验（结构或候选引用不合法），"
                        + (backfilledCount > 0
                            ? "系统已按相关性顺序补足本轮额度（补位 " + backfilledCount + " 个）。"
                            : criticBackfillAllowed ? "本轮未能从可用候选中补足数量。" : "本轮未获补位许可，保留已有清单。")
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
            modDeltas.removeIf(d -> "DROPPED".equals(d.action()) && finalPerfectMods.contains(canonicalIdentities.getOrDefault(d.slug(), d.slug())));
            String modDeltaNote = buildModDeltaNote(modDeltas);
            if (!modDeltaNote.isEmpty()) {
                cleanReply = cleanReply + modDeltaNote;
                MaaLog.user("模组调整: " + modDeltas.size() + " 项（平替/剔除/去重，已在回复中告知）");
            }

            yagen.waitmydawn.maa.runtime.BuildProgress.stage("整理类别与依赖问题");
            Map<String, Set<String>> poolCategories = new HashMap<>();
            poolCategoryOfSlug.forEach((slug, cat) -> poolCategories.put(slug, Set.of(cat)));
            Map<String, Integer> inPack = inPackCategoryCounts(finalPerfectMods, poolCategories, modrinthCacheService);
            var presentation = BuildPresentation.create(apiClient, resolvedGraph, resolution, finalClosure, finalPerfectMods, inPack);
            if (!reportOptimizationGoals.isEmpty()) {
                yagen.waitmydawn.maa.runtime.BuildProgress.stage("补全优化用途资料");
                try {
                    var missingFacts = resolution.roots().stream().filter(finalPerfectMods::contains).filter(slug -> !optimizationFacts.containsKey(slug)).toList();
                    var retainedFacts = apiClient.getProjectsBatch(missingFacts);
                    retainedFacts.forEach((slug, info) -> optimizationFacts.put(slug, OptimizationPortfolio.fact(slug, info)));
                } catch (RuntimeException unavailable) { retrieval.unknown(); }
                var selectedVersions = new TreeMap<String, String>(); resolvedGraph.selectedVersions.forEach((slug, version) -> selectedVersions.put(slug, version.path("id").asText()));
                var selectedRoots = resolution.roots().stream().filter(finalPerfectMods::contains).collect(Collectors.toSet());
                boolean known = structured.mode() == BuildIntent.Mode.NEW || state.getAssembly() != null && state.getAssembly().rootsKnown();
                var portfolio = OptimizationPortfolio.report(reportOptimizationGoals, optimizationFacts, selectedVersions, selectedRoots, finalPerfectMods.size(), known);
                presentation = new BuildPresentation.Report(presentation.schemaVersion(), presentation.total(), presentation.categories(), presentation.issues(), portfolio);
            }
            cleanReply += "\n<build_report>" + MessageJson.write(presentation) + "</build_report>";
            yagen.waitmydawn.maa.runtime.BuildProgress.stage("核对逐项目标与用途证据");
            coverageReviews = OptimizationPortfolio.filterReviews(structured == null ? List.of() : structured.requirements(), rawOptimizationReviews, optimizationFacts);
            var requirementCoverage = structured == null ? null : RequirementCoverage.assess(structured.requirements(),
                    coverageReviews, resolvedGraph, finalPerfectMods, canonicalIdentities, addonMetadata, coverageRepairStatus, coverageRepaired, coverageChecks, coverageProofFacts);
            if (requirementCoverage != null && !requirementCoverage.requirements().isEmpty()) {
                cleanReply += "\n<requirement_report>" + MessageJson.write(requirementCoverage) + "</requirement_report>";
                for (var row : requirementCoverage.requirements()) MaaLog.user("最终要求 " + row.id() + ": " + row.status()
                        + "；入包 " + row.mods().stream().map(RequirementCoverage.Item::slug).toList() + "；" + row.note());
            }
            if (!quotaShortfalls.isEmpty()) MaaLog.user("类别检索预算缺口: " + String.join("、", quotaShortfalls));
            if (!resolution.isComplete()) MaaLog.user("依赖解析不完整: " + resolution.summary());
            if (!retrieval.diagnostics().isEmpty()) MaaLog.user("召回诊断: " + String.join("；", retrieval.diagnostics()));

            // ==========================================
            // 📐 数量口径：期望区间 0.8~1.5 倍目标（用户确认的口径）
            // 超出上限时不自行删除（那会破坏依赖闭包），而是给出"建议用户删除哪些模组 + 它们的前置"
            // ==========================================
            double countRatio = targetCount > 0 ? (double) finalPerfectMods.size() / targetCount : 0;
            List<String> trimSuggestions = List.of();
            boolean totalFileGoal = structured == null || (structured.count() != null
                    && structured.count().scope() == BuildIntent.CountScope.TOTAL_FILES);
            var shortfall = PackQuantityPolicy.shortfall(structured == null ? null : structured.count(), finalPerfectMods.size());
            if (shortfall.isPresent()) {
                cleanReply += "\n\n" + shortfall.get();
                MaaLog.user(shortfall.get());
            }
            if (totalFileGoal && targetCount > 0 && countRatio > 1.5) {
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
            yagen.waitmydawn.maa.runtime.BuildProgress.stage("汇总构建来源与诊断");
            TraceData trace = new TraceData();
            trace.plannerFailures = state.getPlannerFailures();
            trace.requiredMods = state.getRequiredMods();
            trace.architect = architectCall;
            trace.critic = criticCall;
            trace.criticDegraded = criticDegraded;
            trace.criticCoverageDegraded = criticCoverageDegraded;
            trace.coverageVerification = coverageVerification;
            trace.criticOverlapRemoved = criticOverlapRemoved;
            trace.criticExcessApproved = criticExcessApproved;
            trace.criticRejectedCount = criticRejectedCount;
            trace.criticBackfillAllowed = criticBackfillAllowed;
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
            trace.retrieval = retrieval.snapshot();
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
            trace.droppedCount = (int) resolution.dropped().stream().filter(d -> !finalPerfectMods.contains(d.slug())).count();
            trace.dependencyDecisions = List.copyOf(resolution.dropped());
            trace.addonApplicability = Map.copyOf(addonApplicability);
            trace.coordination = resolution.coordination();
            trace.requirementCoverage = requirementCoverage;
            var selectedIds = new TreeMap<String, String>();
            resolvedGraph.selectedVersions.forEach((slug, version) -> {
                if (finalPerfectMods.contains(slug)) selectedIds.put(slug, version.path("id").asText());
            });
            var origins = new TreeMap<String, Set<String>>();
            for (String slug : finalPerfectMods) {
                var roles = new TreeSet<String>();
                if (!initialMods.contains(slug) && !resolution.roots().contains(slug)) roles.add("DEPENDENCY");
                else {
                    if (structured != null && structured.mustHave().stream().anyMatch(s -> canonicalIdentities.getOrDefault(s, s).equals(slug))) roles.add("CORE");
                    if (structured != null && structured.add().stream().anyMatch(s -> canonicalIdentities.getOrDefault(s, s).equals(slug))) roles.add("EXPLICIT_ADD");
                    if (addonCandidates.containsKey(slug)) roles.add("ADDON");
                    if (requiredIntegrations.contains(slug)) roles.add("REQUIRED_INTEGRATION");
                    if (state.getAssembly() != null && structured != null && structured.mode() == BuildIntent.Mode.EDIT)
                        roles.addAll(state.getAssembly().origins().getOrDefault(slug, Set.of()));
                    if (state.getImportedPack() != null && (structured == null || structured.mode() == BuildIntent.Mode.EDIT)
                            && state.getImportedPack().loader().equals(loader) && state.getImportedPack().mcVersion().equals(mcVersion)
                            && state.getImportedPack().files().stream().anyMatch(f -> f.slug().equals(slug))) roles.add("IMPORTED");
                    if (roles.isEmpty()) roles.add(execution != null && execution.original().contains(slug) ? "RETAINED" : "CONTENT");
                }
                origins.put(slug, roles);
            }
            boolean rootsKnown = structured != null && (structured.mode() == BuildIntent.Mode.NEW
                    || state.getImportedPack() == null && state.getAssembly() != null && state.getAssembly().rootsKnown());
            long keptRootCount = resolution.roots().stream().filter(finalPerfectMods::contains).count();
            trace.quantity = Map.of("status", quantity.status(), "totalFiles", finalPerfectMods.size(), "roots", keptRootCount,
                    "dependencies", Math.max(0, finalPerfectMods.size() - keptRootCount), "trimmed", quantity.removed(), "rootsKnown", rootsKnown);
            trace.assembly = new AssemblySnapshot(loader, mcVersion, resolution.roots().stream().filter(finalPerfectMods::contains).sorted().toList(), selectedIds, origins, rootsKnown);
            for (var row : addonTrace) {
                var item = new LinkedHashMap<String, Object>(row);
                item.put("retained", finalPerfectMods.contains((String) row.get("slug")));
                String slug = (String) row.get("slug");
                item.put("selectionSource", !initialMods.contains(slug) ? "NOT_SELECTED" : requiredIntegrations.contains(slug)
                        ? "EXPLICIT_INTEGRATION" : addonApproved.contains(slug) ? "CRITIC_APPROVED" : "ALLOWED_BACKFILL");
                var c = addonCandidates.get(slug);
                var references = new HashMap<>(c.addonVersionProjects); references.putAll(finalVersionProjects);
                var finalEvidence = PackAddonPolicy.assess(slug, resolvedGraph.selectedVersions.get(slug), references, c.addonCores, retainedAddonProjects, true);
                item.put("finalEvidence", finalPerfectMods.contains(slug) ? finalEvidence.evidence() : List.of());
                boolean finalVersionVerified = finalPerfectMods.contains(slug) && resolvedGraph.selectedVersions.containsKey(slug)
                        && !resolvedGraph.selectedVersions.get(slug).path("id").asText().isBlank();
                item.put("finalVersionVerified", finalVersionVerified);
                item.put("finalEvidenceStatus", !finalPerfectMods.contains(slug) ? "NOT_RETAINED"
                        : finalEvidence.conflict() ? "CONFLICT_DECLARATION" : finalEvidence.evidence().isEmpty() ? "RELATION_UNVERIFIED"
                        : finalEvidence.integration() ? "IN_PACK_INTEGRATION" : "ADDON_RELATION");
                item.put("preferences", c.preferences);
                item.put("projectFacts", c.metadataFacts());
                trace.addonEvidence.add(item);
            }
            for (var requirement : genericGoals) {
                var selected = requirementHints.entrySet().stream().filter(e -> finalPerfectMods.contains(e.getKey()))
                        .filter(e -> e.getValue().contains(requirement.id())).map(Map.Entry::getKey).sorted().toList();
                trace.goalEvidence.add(Map.of("requirementId", requirement.id(), "source", requirement.source(),
                        "searches", requirement.searches(), "selected", selected, "preferences", requirement.preferences(),
                        "selectedProjectFacts", selected.stream().map(requirementCandidates::get).filter(Objects::nonNull).map(CandidateMod::metadataFacts).toList(),
                        "status", selected.isEmpty() ? "NO_RETAINED_RETRIEVAL_HINT" : "RETAINED_RETRIEVAL_HINT",
                        "evidenceType", "RETRIEVAL_ASSOCIATION_NOT_SATISFACTION"));
            }
            for (var profile : activeGoals) {
                var selected = goalRoles.entrySet().stream().filter(e -> finalPerfectMods.contains(e.getKey()))
                        .filter(e -> e.getValue().contains(profile.capability())).map(Map.Entry::getKey).sorted().toList();
                var facets = selected.stream().flatMap(s -> goalFacets.getOrDefault(s, Set.of()).stream()).collect(Collectors.toSet());
                var missingFacets = GoalCatalog.desiredFacets(profile).stream().filter(f -> !facets.contains(f)).sorted().toList();
                trace.goalEvidence.add(Map.of("capability", profile.capability(), "referenceCount", profile.target(),
                        "selected", selected, "status", selected.isEmpty() ? "NO_EVIDENCE" : missingFacets.isEmpty() ? "EVIDENCE_PRESENT" : "PARTIAL_FACET_EVIDENCE",
                        "referenceReached", selected.size() >= profile.target(),
                        "facets", facets.stream().sorted().toList(), "missingFacetEvidence", missingFacets,
                        "requirementIds", structured.requirements().stream().filter(r -> r.capabilities().contains(profile.capability()))
                                .map(yagen.waitmydawn.maa.model.agent.BuildRequirement::id).toList()));
            }
            if (execution != null) {
                trace.intent = structured;
                trace.constraintActual = execution.actualCount(initialMods, finalPerfectMods);
                trace.missingMustHave = structured.mustHave().stream().filter(s -> !finalPerfectMods.contains(canonicalIdentities.getOrDefault(s, s))).toList();
                for (String diagnostic : execution.diagnostics(initialMods, finalPerfectMods, rootsKnown, canonicalIdentities))
                    cleanReply += "\n\n⚠️ " + diagnostic;
            }
            yagen.waitmydawn.maa.runtime.BuildProgress.stage("序列化构建结果");
            cleanReply = cleanReply + "\n<trace>" + buildTrace(trace) + "</trace>";

            System.out.println("📦 草案装配完成，待发布文件数：" + finalPerfectMods.size());
            state.setFinishedAssembly(trace.assembly);
            state.setFinishedClosure(DependencyClosure.capture(resolvedGraph, resolution.unresolved(), canonicalIdentities));
            String finalReply = appendOpsAndMods(cleanReply, state,
                    "\n<mods>" + String.join(",", finalPerfectMods) + "</mods>");
            MaaLog.user("草案: 已装配模组 " + finalPerfectMods.size()
                    + " 个 -> " + String.join(",", finalPerfectMods));
            return finalReply;

        } catch (Exception e) {
            System.err.println("组装蓝图失败: " + e.getClass().getSimpleName());
            // 🐛 旧实现直接 `return aiBlueprint`：评论员那一步的 LLM 失败会被静默吞掉，
            // 用户拿到的是**带 XML 标签的规划师原文**（`<core_mods>` 都在里面），既没有图谱也不知道出了什么事。
            // 能识别的 LLM 失败就如实说；其余保留"尽力返回原文"的旧行为，但要留下日志线索。
            MaaLog.error("组装蓝图失败: " + e.getClass().getSimpleName(), e);
            String note = llmErrorNote(e);
            if (note != null) return note;
            if (aiBlueprint != null && aiBlueprint.stripLeading().startsWith("{"))
                return "⚠️ 结构化计划未能执行，本轮未产出模组清单。";
            return aiBlueprint + "\n\n⚠️ （本轮组装过程中出错，上面是规划阶段的原文，图谱可能不完整；"
                    + "错误类型：" + e.getClass().getSimpleName() + "）";
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
        final List<Map<String, Object>> goalEvidence = new ArrayList<>();
        final List<Map<String, Object>> addonEvidence = new ArrayList<>();
        Map<String, AddonApplicability.Check> addonApplicability = Map.of();
        AssemblySnapshot assembly;
        ResolutionResult.Coordination coordination;
        Map<String, Object> quantity = Map.of();
        Map<String, Integer> retrieval = Map.of();
        AiAgentService.AgentCallResult architect;
        AiAgentService.AgentCallResult critic;
        /** 从初次依赖解析至最终准入的墙钟区间，包含可选定向复核及补缺，不等于纯解析CPU时间。 */
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
        boolean awaitingInput;
        List<AgentContractCodec.Failure> plannerFailures = List.of();
        RequiredModsReport requiredMods;
        /** 协议校验失败时标记审核降级，供评测识别确定性补位。 */
        boolean criticDegraded;
        boolean criticCoverageDegraded;
        RequirementCoverage.VerificationCounts coverageVerification;
        int criticOverlapRemoved, criticExcessApproved, criticRejectedCount;
        boolean criticBackfillAllowed;
        String resolutionStatus = "COMPLETE";
        int unresolvedCount, droppedCount;
        List<ResolutionResult.Dropped> dependencyDecisions = List.of();
        /** 本轮有多少个类别权重是 Tool adjustCategoryTargets 施加的（P3-7） */
        int categoryWeightApplied;
        BuildIntent intent;
        RequirementCoverage.Report requirementCoverage;
        int constraintActual;
        List<String> missingMustHave = List.of();
    }

    private static ReviewResponse.Selection readValidatedSelection(String reply, Set<String> candidates, int limit) {
        try { return AGENT_CODEC.readReviewSelection(reply).select(candidates, limit); }
        catch (IllegalArgumentException invalid) { return null; }
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
        sb.append("\"branch\":\"").append(d.formatViolation ? "FORMAT_VIOLATION" : d.awaitingInput ? "AWAITING_INPUT" : "NORMAL").append("\"");
        sb.append(",\"awaitingInput\":").append(d.awaitingInput);
        sb.append(",\"criticDegraded\":").append(d.criticDegraded);
        sb.append(",\"criticCoverageDegraded\":").append(d.criticCoverageDegraded);
        if (d.coverageVerification != null) sb.append(",\"coverageVerification\":").append(MessageJson.write(d.coverageVerification));
        sb.append(",\"plannerValidation\":{\"status\":\"").append(d.formatViolation ? "FAILED" : "VALID")
                .append("\",\"failures\":").append(new tools.jackson.databind.ObjectMapper().writeValueAsString(d.plannerFailures)).append('}');
        sb.append(",\"retrieval\":").append(new tools.jackson.databind.ObjectMapper().writeValueAsString(d.retrieval));
        sb.append(",\"goalEvidence\":").append(new tools.jackson.databind.ObjectMapper().writeValueAsString(d.goalEvidence));
        sb.append(",\"addonEvidence\":").append(new tools.jackson.databind.ObjectMapper().writeValueAsString(d.addonEvidence));
        sb.append(",\"addonApplicability\":").append(MessageJson.write(d.addonApplicability));
        sb.append(",\"assembly\":").append(new tools.jackson.databind.ObjectMapper().writeValueAsString(d.assembly));
        sb.append(",\"coordination\":").append(new tools.jackson.databind.ObjectMapper().writeValueAsString(d.coordination));
        sb.append(",\"quantity\":").append(new tools.jackson.databind.ObjectMapper().writeValueAsString(d.quantity));
        sb.append(",\"requirementCoverage\":").append(new tools.jackson.databind.ObjectMapper().writeValueAsString(d.requirementCoverage));
        if (d.requiredMods != null) sb.append(",\"requirements\":{\"status\":\"" + d.requiredMods.status() + "\",\"report\":")
                .append(new tools.jackson.databind.ObjectMapper().writeValueAsString(d.requiredMods)).append('}');
        if (d.critic != null) sb.append(",\"review\":{\"overlapRemoved\":").append(d.criticOverlapRemoved)
                .append(",\"excessApproved\":").append(d.criticExcessApproved)
                .append(",\"rejected\":").append(d.criticRejectedCount)
                .append(",\"allowBackfill\":").append(d.criticBackfillAllowed).append('}');
        if (d.intent != null) {
            sb.append(",\"contract\":{\"schemaVersion\":").append(d.intent.schemaVersion()).append(",\"mode\":\"").append(d.intent.mode()).append("\"");
            sb.append(",\"requirementLedger\":").append(new tools.jackson.databind.ObjectMapper().writeValueAsString(d.intent.requirements()));
            sb.append(",\"missingMustHave\":[");
            appendJsonStrings(sb, d.missingMustHave);
            sb.append("],\"quantity\":");
            if (d.intent.count() == null) sb.append("null");
            else {
                var count = d.intent.count();
                boolean authoritative = !(d.intent.mode() == BuildIntent.Mode.EDIT && count.scope() == BuildIntent.CountScope.ROOT_MODS);
                sb.append("{\"scope\":\"").append(count.scope()).append("\",\"precision\":\"").append(count.precision())
                        .append("\",\"target\":").append(count.value()).append(",\"min\":").append(count.min())
                        .append(",\"max\":").append(count.max()).append(",\"actual\":")
                        .append(d.resolutionStatus.equals("NOT_STARTED") ? "null" : Integer.toString(d.constraintActual))
                        .append(",\"met\":").append(!authoritative || d.resolutionStatus.equals("NOT_STARTED") ? "null" : Boolean.toString(count.accepts(d.constraintActual)))
                        .append("}");
            }
            sb.append("}");
        }
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
        sb.append("],\"dependencyDecisions\":").append(MessageJson.write(d.dependencyDecisions));
        sb.append(",\"dropped\":[");
        appendJsonStrings(sb, d.droppedMods);
        sb.append("],\"deduped\":[");
        appendJsonStrings(sb, d.dedupedMods);
        sb.append("]}");
        sb.append(",\"resolution\":{\"status\":\"").append(d.resolutionStatus)
                .append("\",\"unresolved\":").append(d.unresolvedCount)
                .append(",\"dropped\":").append(d.droppedCount).append("}");
        return MessageJson.escape(sb.append("}").toString());
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
                + ",\"in\":" + r.inputTokens() + ",\"out\":" + r.outputTokens()
                + ",\"truncatedCalls\":" + r.truncatedCalls();
    }

    // 🚀 建池器 1：获取附属候选池
    private List<CandidateMod> buildAddonPool(String coreSlug, String loader, String mcVersion, Set<String> existing) {
        return buildAddonPool(coreSlug, loader, mcVersion, existing, new RetrievalBudget());
    }
    private List<CandidateMod> buildAddonPool(String coreSlug, String loader, String mcVersion, Set<String> existing, RetrievalBudget resources) {
        return buildAddonPool(coreSlug, loader, mcVersion, existing, resources, yagen.waitmydawn.maa.model.agent.SelectionPreferences.DEFAULT);
    }
    private static yagen.waitmydawn.maa.model.agent.SelectionPreferences addonPreferences(
            List<yagen.waitmydawn.maa.model.agent.BuildRequirement> requirements, String core) {
        var popularity = yagen.waitmydawn.maa.model.agent.SelectionPreferences.Popularity.DEFAULT;
        var freshness = yagen.waitmydawn.maa.model.agent.SelectionPreferences.Freshness.DEFAULT;
        var targets = requirements.stream().flatMap(r -> r.criteria().isEmpty() ? java.util.stream.Stream.of(r)
                : r.criteria().stream().map(c -> c.target(r))).filter(r -> r.addonCores().contains(core)).toList();
        // EDIT 账本按历史到本轮排列；仅覆盖明确声明的轴，保持另一轴及其它核心的偏好。
        for (var target : targets) {
            var next = target.preferences();
            if (next.popularity() != yagen.waitmydawn.maa.model.agent.SelectionPreferences.Popularity.DEFAULT) popularity = next.popularity();
            if (next.freshness() != yagen.waitmydawn.maa.model.agent.SelectionPreferences.Freshness.DEFAULT) freshness = next.freshness();
        }
        return new yagen.waitmydawn.maa.model.agent.SelectionPreferences(popularity, freshness);
    }
    private List<CandidateMod> buildAddonPool(String coreSlug, String loader, String mcVersion, Set<String> existing,
            RetrievalBudget resources, yagen.waitmydawn.maa.model.agent.SelectionPreferences preferences) {
        JsonNode core;
        try { core = apiClient.getProjectInfo(coreSlug); }
        catch (Exception unavailable) { resources.unknown(); return List.of(); }
        if (core == null || core.path("id").asText().isBlank()) { resources.unknown(); return List.of(); }
        String coreId = core.path("id").asText();
        Set<String> attempted = new LinkedHashSet<>(existing);
        List<CandidateMod> local = List.of();
        if (modrinthCacheService.isAvailable()) {
            var candidates = new ArrayList<CandidateMod>();
            for (ModEntry entry : modrinthCacheService.findAddons(coreSlug, loader, 25)) {
                if (attempted.add(entry.slug())) candidates.add(new CandidateMod(entry.slug(), entry.title(), entry.description(), entry.downloads()));
            }
            local = verifyAddonRelations(coreSlug, coreId, candidates, loader, mcVersion, resources);
        }
        List<CandidateMod> pool = new ArrayList<>();
        try {
            String facetsRaw = String.format("[[\"project_type:mod\"], [\"loaders:%s\"], [\"versions:%s\"]]", loader, mcVersion);
            String query = core.path("title").asText(coreSlug.replace("-", " "));
            if (!resources.search(query)) {
                local.forEach(c -> c.preferences = preferences);
                return scoreAddonPool(new ArrayList<>(local)).stream().sorted(SCORE_THEN_DOWNLOADS).toList();
            }
            for (var channel : PreferenceRanking.channels(preferences)) {
                if (!channel.equals(PreferenceRanking.channels(preferences).getFirst())
                        && !resources.search("addon:" + coreSlug + ":" + channel)) continue;
                JsonNode res = channel.index().equals("relevance") && !channel.longTail()
                        ? apiClient.search(query, 25, facetsRaw)
                        : apiClient.searchPage(query, 25, 0, channel.index(), PreferenceRanking.facets(facetsRaw, channel));
                if (res != null && res.has("hits")) {
                    int count = 0;
                    for (JsonNode hit : res.path("hits")) {
                        String slug = hit.path("slug").asText();
                        for (var cached : local) if (cached.slug.equals(slug)) cached.dates(hit);
                        if (!slug.equals(coreSlug) && attempted.add(slug)) {
                            CandidateMod c = new CandidateMod(slug, hit.path("title").asText(),
                                    hit.path("description").asText(), hit.path("downloads").asLong());
                            c.dates(hit); c.preferences = preferences; pool.add(c);
                            count++;
                        }
                    }
                    System.out.println("   📦 核心 [" + coreSlug + "] 附属召回: 抓取到 " + count + " 个候选包");
                }
            }
        } catch (Exception unavailable) { resources.unknown(); }
        var merged = new ArrayList<>(local);
        merged.addAll(verifyAddonRelations(coreSlug, coreId, pool, loader, mcVersion, resources));
        merged.forEach(c -> c.preferences = preferences);
        return scoreAddonPool(merged).stream().sorted(SCORE_THEN_DOWNLOADS).toList();
    }

    private List<CandidateMod> verifyAddonRelations(String coreSlug, String coreId, List<CandidateMod> pool, String loader,
                                                   String mc, RetrievalBudget resources) {
        Map<String, JsonNode> versions = new ConcurrentHashMap<>();
        long poolMax = pool.stream().mapToLong(c -> c.downloads).max().orElse(1);
        List<CandidateMod> candidates = pool.stream().sorted(Comparator.comparingDouble((CandidateMod c) -> c.preferences.equals(yagen.waitmydawn.maa.model.agent.SelectionPreferences.DEFAULT)
                        ? c.downloads : PreferenceRanking.factor(c.preferences, c.downloads, poolMax, c.published, java.time.Instant.now()))
                .reversed().thenComparing(c -> c.slug))
                .filter(c -> !PackAddonPolicy.infrastructure(c.slug, c.title, Set.of()))
                .limit(25).filter(c -> resources.version(c.slug)).toList();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (var candidate : candidates) pending.add(ScopedExecutors.runAsync(() -> {
                try {
                    JsonNode version = apiClient.getLatestCompatibleVersionBySlug(candidate.slug, mc, loader);
                    if (version != null && version.path("dependencies").isArray()) versions.put(candidate.slug, version);
                    else if (version != null) resources.unknown();
                } catch (Exception unavailable) { resources.unknown(); }
            }, executor));
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).join();
        }
        Set<String> refs = new TreeSet<>();
        for (JsonNode version : versions.values()) for (JsonNode dep : version.path("dependencies")) {
            String id = dep.path("version_id").asText("");
            if (dep.path("project_id").asText("").isBlank() && !id.isBlank()
                    && Set.of("required", "optional", "incompatible").contains(dep.path("dependency_type").asText())) refs.add(id);
        }
        refs.removeIf(id -> !resources.version("vid:" + id));
        Map<String, String> refProjects = new HashMap<>();
        try {
            if (!refs.isEmpty()) apiClient.prefetchVersions(new ArrayList<>(refs));
            for (String id : refs) {
                JsonNode version = apiClient.getVersionById(id);
                if (version != null) refProjects.put(id, version.path("project_id").asText());
            }
        } catch (Exception unavailable) { resources.unknown(); }
        List<CandidateMod> verified = new ArrayList<>();
        for (var candidate : candidates) {
            JsonNode version = versions.get(candidate.slug);
            if (version == null) continue;
            // 未解析的冲突引用可能指向核心，不能仅凭另一条正向依赖放行。
            boolean unresolvedConflict = false;
            for (JsonNode dep : version.path("dependencies")) {
                if ("incompatible".equals(dep.path("dependency_type").asText())
                        && dep.path("project_id").asText("").isBlank()
                        && !refProjects.containsKey(dep.path("version_id").asText(""))) unresolvedConflict = true;
            }
            if (unresolvedConflict) { resources.unknown(); continue; }
            String evidence = AddonEvidence.relationship(coreId, version, refProjects, true);
            if (evidence == null) continue;
            candidate.addonEvidence = evidence;
            candidate.addonVersion = version;
            candidate.addonVersionProjects = Map.copyOf(refProjects);
            candidate.addonCores.put(coreSlug, coreId);
            candidate.textScore.add("REQUIRED_CORE".equals(evidence) ? 2.0 : 1.0);
            verified.add(candidate);
        }
        return scoreAddonPool(verified).stream().sorted(SCORE_THEN_DOWNLOADS).toList();
    }

    /** 复用选版事实核对适用对象；新增身份补全有界，不能从项目名猜测缺席的联动核心。 */
    private Map<String, AddonApplicability.Check> auditAddonApplicability(Set<String> roots, Set<String> retained,
            Map<String, CandidateMod> addons, Map<String, JsonNode> selectedVersions, Map<String, PackAddonPolicy.Project> projectFacts,
            RetrievalBudget resources, String loader, String mc) {
        var result = new LinkedHashMap<String, AddonApplicability.Check>();
        var identities = new LinkedHashSet<>(retained); identities.addAll(roots);
        if (identities.isEmpty()) return result;
        var metadata = new LinkedHashMap<String, PackAddonPolicy.Project>(projectFacts);
        var versions = new LinkedHashMap<String, JsonNode>();
        var versionProjects = new HashMap<String, String>();
        addons.values().forEach(c -> versionProjects.putAll(c.addonVersionProjects));
        selectedVersions.values().forEach(v -> versionProjects.put(v.path("id").asText(), v.path("project_id").asText()));
        try {
            var missingIdentities = identities.stream().filter(ref -> !metadata.containsKey(ref)).toList();
            if (!missingIdentities.isEmpty()) apiClient.getProjectsBatch(missingIdentities).forEach((ref, info) -> metadata.put(ref, PackAddonPolicy.Project.from(info)));
            for (String slug : roots) {
                if (!retained.contains(slug)) continue;
                var project = metadata.get(slug);
                if (project == null) {
                    resources.unknown();
                    if (addons.containsKey(slug)) result.put(slug, new AddonApplicability.Check("PROJECT_UNVERIFIED", List.of(), List.of(), ""));
                    continue;
                }
                if (!addons.containsKey(slug) && !AddonApplicability.addon(project.description())) continue;
                var version = selectedVersions.get(slug);
                if (version == null && addons.containsKey(slug)) version = addons.get(slug).addonVersion;
                if (version == null && resources.version(slug)) version = apiClient.getLatestCompatibleVersionBySlug(slug, mc, loader);
                if (version == null) { resources.unknown(); result.put(slug, new AddonApplicability.Check("VERSION_UNVERIFIED", List.of(), List.of(), project.description())); }
                else versions.put(slug, version);
            }
            var refs = new TreeSet<String>(); var exact = new TreeSet<String>();
            for (var version : versions.values()) for (var dependency : version.path("dependencies")) {
                if (!Set.of("required", "optional").contains(dependency.path("dependency_type").asText())) continue;
                String project = dependency.path("project_id").asText(""); String id = dependency.path("version_id").asText("");
                if (!project.isBlank()) refs.add(project);
                else if (!id.isBlank() && !versionProjects.containsKey(id)) exact.add(id);
            }
            var toLoad = exact.stream().limit(24).filter(id -> resources.version("vid:" + id)).toList();
            if (!toLoad.isEmpty()) apiClient.prefetchVersions(toLoad);
            for (String id : toLoad) {
                var version = apiClient.getVersionById(id);
                if (version != null && !version.path("project_id").asText().isBlank()) versionProjects.put(id, version.path("project_id").asText());
            }
            refs.addAll(versionProjects.values());
            var knownIds = metadata.values().stream().map(PackAddonPolicy.Project::id).collect(Collectors.toSet());
            var extra = refs.stream().filter(id -> !id.isBlank() && !knownIds.contains(id)).limit(64).toList();
            if (!extra.isEmpty()) apiClient.getProjectsBatch(extra).forEach((ref, info) -> metadata.put(ref, PackAddonPolicy.Project.from(info)));
            var kept = new HashSet<>(retained); var permitted = new HashSet<>(roots);
            metadata.forEach((ref, project) -> {
                if (retained.contains(ref) || retained.contains(project.slug())) { kept.add(project.id()); kept.add(project.slug()); }
                if (roots.contains(ref) || roots.contains(project.slug())) { permitted.add(project.id()); permitted.add(project.slug()); }
            });
            for (var entry : versions.entrySet()) result.put(entry.getKey(), AddonApplicability.assess(metadata.get(entry.getKey()), entry.getValue(),
                    versionProjects, metadata.values(), kept, permitted, addons.containsKey(entry.getKey())));
            if (extra.size() < refs.stream().filter(id -> !id.isBlank() && !knownIds.contains(id)).count() || toLoad.size() < exact.size()) resources.unknown();
        } catch (java.util.concurrent.CancellationException canceled) { throw canceled; }
        catch (RuntimeException unavailable) { resources.unknown(); MaaLog.user("附属适用范围：部分版本或对象资料未取得，保留未核实状态"); }
        projectFacts.putAll(metadata);
        result.forEach((slug, check) -> MaaLog.user("附属适用范围 " + slug + "：" + MessageJson.write(check)));
        return result;
    }

    /** 同一轮复用项目原文；取数走委派门面，不额外建立服务器 HTTP 通道。 */
    private Map<String, List<WorldGenerationPolicy.Finding>> generationAdmission(Collection<String> slugs, String request,
            Map<String, String> facts) {
        var missing = slugs.stream().filter(s -> !facts.containsKey(s)).distinct().toList();
        Map<String, JsonNode> available = Map.of();
        if (!missing.isEmpty()) try { available = apiClient.getProjectsBatch(missing); }
        catch (ModrinthApiClient.PartialProjectBatchException partial) { available = partial.projects(); }
        catch (java.util.concurrent.CancellationException canceled) { throw canceled; }
        catch (RuntimeException unavailable) { MaaLog.user("生成准入：部分项目介绍未取得，不能据此认定无极端生成行为"); }
        available.forEach((ref, info) -> {
            String text = info.path("description").asText() + "\n" + info.path("body").asText();
            text = text.substring(0, Math.min(text.length(), 16000));
            if (!text.isBlank()) {
                facts.put(ref, text);
                facts.put(info.path("slug").asText(ref), text);
                if (!info.path("id").asText().isBlank()) facts.put(info.path("id").asText(), text);
            }
        });
        long unknown = slugs.stream().filter(s -> !facts.containsKey(s)).count();
        if (unknown > 0) MaaLog.user("生成准入：" + unknown + " 项未取得可核对介绍，未授予无极端改造的验证结论");
        var blocked = new LinkedHashMap<String, List<WorldGenerationPolicy.Finding>>();
        for (String slug : slugs) {
            var reasons = WorldGenerationPolicy.blocked(request, slug, facts.get(slug));
            if (!reasons.isEmpty()) {
                blocked.put(slug, reasons);
                MaaLog.user("生成准入排除 " + slug + "：" + WorldGenerationPolicy.reason(reasons) + "，原文 " + reasons);
            }
        }
        return blocked;
    }
    private static String generationAdmissionPause(Map<String, List<WorldGenerationPolicy.Finding>> blocked) {
        return "⚠️ 依赖或必选联动带入了未经要求的世界改造，本轮未修改清单。请确认是否接受这些改造，或更换引入它们的模组：\n"
                + String.join("\n", blocked.entrySet().stream().map(e -> "· " + e.getKey() + "：" + WorldGenerationPolicy.reason(e.getValue())).toList());
    }

    private Map<String, PackAddonPolicy.Project> loadAddonProjects(Set<String> identities, RetrievalBudget resources) {
        var facts = new LinkedHashMap<String, PackAddonPolicy.Project>();
        try { apiClient.prefetchProjects(identities); }
        catch (Exception unavailable) { resources.unknown(); }
        for (String identity : identities) try {
            var info = apiClient.getProjectInfo(identity);
            if (info == null || info.path("id").asText().isBlank()) { resources.unknown(); continue; }
            var p = PackAddonPolicy.Project.from(info);
            facts.put(identity, p);
        } catch (Exception unavailable) { resources.unknown(); }
        return facts;
    }

    private static String integrationPause(String reason, List<Map<String, Object>> evidence) {
        MaaLog.user("联动要求待确认：" + reason);
        return "⚠️ " + reason + "\n本轮未修改清单。\n<trace>"
                + new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("addonEvidence", evidence,
                        "integrationStatus", "NEEDS_USER_DECISION")) + "</trace>";
    }

    private static boolean optimizationCandidateInScope(String slug, Map<String, OptimizationPortfolio.Fact> facts,
            List<OptimizationPortfolio.Goal> goals, Map<String, Set<String>> hints,
            Map<String, Set<yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability>> roles) {
        return OptimizationPortfolio.withinScope(slug, facts, goals, hints) || roles.getOrDefault(slug, Set.of()).stream()
                .anyMatch(c -> c != yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability.PERFORMANCE);
    }

    private static Map<String, Set<String>> optimizationOrderingHints(Map<String, Set<String>> hints,
            Map<String, Set<yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability>> roles,
            Map<String, OptimizationPortfolio.Fact> facts, List<OptimizationPortfolio.Goal> goals) {
        var ordering = new HashMap<String, Set<String>>();
        hints.forEach((slug, ids) -> ordering.put(slug, new LinkedHashSet<>(ids)));
        for (var entry : ordering.entrySet()) {
            var ids = List.copyOf(entry.getValue());
            for (String id : ids) {
                roles.getOrDefault(entry.getKey(), Set.of()).stream()
                        .filter(c -> c != yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability.PERFORMANCE)
                        .forEach(c -> entry.getValue().add(id + ":" + c));
                var fact = facts.get(entry.getKey());
                if (fact != null && goals.stream().filter(g -> g.id().equals(id)).anyMatch(fact::supports)) entry.getValue().add(id + ":optimization");
            }
        }
        return ordering;
    }

    private static void mergeRequirementHints(BuildIntent intent,
            Map<String, Set<yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability>> roles,
            Map<String, Set<String>> hints) {
        for (var entry : roles.entrySet()) for (var requirement : intent.requirements())
            if (!Collections.disjoint(entry.getValue(), requirement.capabilities()))
                hints.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>()).add(requirement.id());
    }

    private record CoverageFollowup(List<yagen.waitmydawn.maa.model.agent.RequirementReview> updates, Set<String> eligible,
                                    AiAgentService.AgentCallResult call, String status, Map<String, RequirementCoverage.EvidenceCheck> checks) {}

    /** 一次有界定向复核；已保留清单仅提供证据，候选批准不授予改变既有版本或否决核心的权限。 */
    private CoverageFollowup reviewMissingCriteria(List<yagen.waitmydawn.maa.model.agent.BuildRequirement> requirements, RequirementCoverage.Report report,
            List<yagen.waitmydawn.maa.model.agent.RequirementReview> reviews, Map<String, String> priorFacts, Set<String> priorEligible,
            Set<String> selected, Set<String> vetoes, BuildExecutionPlan execution, RetrievalBudget resources,
            String loader, String mc, long maxDownloads, String uuid, String key) {
        var targets = RequirementCoverage.missingTargets(requirements, report);
        var targetIds = targets.stream().map(yagen.waitmydawn.maa.model.agent.BuildRequirement::id).collect(Collectors.toSet());
        var scoped = requirements.stream().filter(r -> r.criteria().stream().anyMatch(c -> targetIds.contains(c.key(r.id()))))
                .map(r -> new yagen.waitmydawn.maa.model.agent.BuildRequirement(r.id(), r.source(), r.kind(), r.projects(), r.capabilities(),
                        r.addonCores(), r.searches(), r.preferences(), r.criteria().stream().filter(c -> targetIds.contains(c.key(r.id()))).toList())).toList();
        var candidates = new LinkedHashSet<>(RequirementCoverage.repairs(report, reviews, priorEligible, selected));
        var facts = new LinkedHashMap<>(priorFacts);
        var existingEvidence = new HashMap<String, List<yagen.waitmydawn.maa.model.agent.RequirementReview.Contribution>>();
        for (var parent : scoped) for (var criterion : parent.criteria()) reviews.stream().filter(r -> r.id().equals(parent.id()))
                .flatMap(r -> r.criteria().stream()).filter(c -> c.source().equals(criterion.source()))
                .forEach(c -> existingEvidence.put(criterion.key(parent.id()), c.evidence()));
        var generationGaps = report.requirements().stream().flatMap(r -> r.criteria().stream())
                .filter(c -> c.evidenceState().generation().stream().anyMatch(g -> g.projects().isEmpty()))
                .map(RequirementCoverage.CriterionRow::id).collect(Collectors.toSet());
        var searchable = targets.stream().filter(target -> generationGaps.contains(target.id()) || existingEvidence.getOrDefault(target.id(), List.of()).stream()
                .noneMatch(e -> priorEligible.contains(e.slug()) && !selected.contains(e.slug()))).toList();
        resources.beginRepair();
        var fresh = buildRequirementPool(searchable, selected, loader, mc, maxDownloads, execution, resources, uuid, new HashMap<>(), false);
        fresh.stream().filter(c -> !vetoes.contains(c.slug)).limit(12 - candidates.size()).forEach(c -> {
            candidates.add(c.slug);
            String text = c.title + " " + c.desc;
            facts.put(c.slug, text.length() > 800 ? text.substring(0, 800) : text);
        });
        candidates.removeAll(vetoes); candidates.removeAll(selected);
        String admissionRequest = String.join("\n", execution.intent().requirements().stream()
                .map(yagen.waitmydawn.maa.model.agent.BuildRequirement::source).toList());
        var admissionBlocked = generationAdmission(candidates, admissionRequest, new LinkedHashMap<>());
        candidates.removeAll(admissionBlocked.keySet());
        var neededFacts = new LinkedHashMap<String, String>();
        facts.forEach((slug, text) -> { if (selected.contains(slug) || candidates.contains(slug)) neededFacts.put(slug, text); });
        if (candidates.isEmpty() && neededFacts.isEmpty())
            return new CoverageFollowup(List.of(), priorEligible, null, "NO_PROJECT_FACTS", Map.of());
        var mapper = new tools.jackson.databind.ObjectMapper();
        String context = "【定向补缺复核】只审核给出的缺失子目标；最多批准3个候选，不改动或否决已有清单。"
                + "gap 说明即使采纳有证据候选仍缺失的内容；候选尚未入包由Java追踪，不当作用途缺口。"
                + "\n【完整有效需求账本（本次仅列待补子目标）】\n" + mapper.writeValueAsString(scoped)
                + "\n【已有项目（仅可作为证据）】\n" + mapper.writeValueAsString(selected)
                + "\n【候选池】\n" + mapper.writeValueAsString(candidates)
                + "\n【Java审核范围】\n" + mapper.writeValueAsString(yagen.waitmydawn.maa.model.agent.ReviewInputScope.of(
                        yagen.waitmydawn.maa.model.agent.ReviewInputScope.Mode.REVIEW_MISSING_CONTENT,
                        loader, mc, candidates, selected))
                + "\n【项目用途事实】\n" + mapper.writeValueAsString(neededFacts);
        var generationTargets = DimensionGeneration.targets(execution.intent().requirements());
        context += "\n【维度核心生成目标】大量群系、丰富地形或大规模结构可提供依据；普通局部结构和生物不能消除核心生成缺口。\n" + mapper.writeValueAsString(
                generationTargets.entrySet().stream().filter(e -> targetIds.contains(e.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        final String reviewContext = context;
        AiAgentService.AgentCallResult call = null;
        try {
            if (isAborted(uuid)) throw new java.util.concurrent.CancellationException();
            call = callAgentWithRetry("审核员定向补缺", uuid, () -> aiAgentService.criticPools(reviewContext, key, candidates, Math.min(3, candidates.size())));
            var evidenceSources = new LinkedHashSet<>(selected); evidenceSources.addAll(candidates);
            var decision = AGENT_CODEC.readReviewResponse(call.text()).select(candidates, 3, evidenceSources).decision();
            RequirementCoverage.validateReviews(scoped, decision.requirements());
            var blocked = new HashSet<>(vetoes); decision.rejected().forEach(r -> blocked.add(r.slug()));
            var verification = RequirementCoverage.verify(scoped, decision.requirements(), neededFacts, blocked, generationTargets);
            var verified = verification.reviews();
            var eligible = new HashSet<>(priorEligible); eligible.removeAll(blocked);
            var hints = new HashMap<String, Set<String>>();
            for (var r : scoped) for (var criterion : r.criteria()) verified.stream().filter(row -> row.id().equals(r.id()))
                    .flatMap(row -> row.criteria().stream()).filter(c -> c.source().equals(criterion.source()))
                    .flatMap(c -> c.evidence().stream()).forEach(e -> hints.computeIfAbsent(e.slug(), ignored -> new HashSet<>()).add(criterion.key(r.id())));
            eligible.addAll(RequirementRetrieval.order(decision.approved(), hints, selected).stream().filter(s -> !blocked.contains(s)).limit(3).toList());
            MaaLog.user("子目标定向复核：目标 " + targets.stream().map(yagen.waitmydawn.maa.model.agent.BuildRequirement::source).toList()
                    + "，候选 " + candidates.size() + "，允许试解 " + decision.approved());
            return new CoverageFollowup(verified, Set.copyOf(eligible), call, "REVIEWED_ONCE", verification.checks());
        } catch (java.util.concurrent.CancellationException canceled) { throw canceled; }
        catch (RuntimeException unavailable) {
            if (isAborted(uuid)) throw new java.util.concurrent.CancellationException();
            MaaLog.user("子目标定向复核未核实，保留原清单与原用途证据");
            return new CoverageFollowup(List.of(), priorEligible, call, "REPAIR_REVIEW_UNVERIFIED", Map.of());
        }
    }

    /** 优化种子和补缺查询共用现有预算；项目身份不能代替具体用途或目标环境版本。 */
    private List<CandidateMod> buildOptimizationPool(List<OptimizationPortfolio.Goal> goals, Set<String> existing,
            String loader, String mc, long maxDownloads, BuildExecutionPlan execution, RetrievalBudget resources, String uuid,
            Map<String, Set<String>> hints, Map<String, OptimizationPortfolio.Fact> facts, int targetFiles) {
        if (goals.isEmpty() || execution.intent().excludedCategories().contains("optimization")) return List.of();
        var references = new LinkedHashSet<>(existing); references.addAll(OptimizationPortfolio.seeds(goals));
        references.removeAll(execution.blocked());
        var raw = new LinkedHashMap<String, CandidateMod>();
        try {
            var metadata = apiClient.getProjectsBatch(references);
            var existingCanonical = new HashSet<>(existing);
            metadata.forEach((ref, info) -> { if (existing.contains(ref)) existingCanonical.add(info.path("slug").asText(ref)); });
            for (var row : metadata.entrySet()) {
                var info = row.getValue(); String slug = info.path("slug").asText(row.getKey());
                var fact = OptimizationPortfolio.fact(slug, info); facts.put(slug, fact); facts.put(row.getKey(), fact);
                if (existingCanonical.contains(slug)) continue;
                if (info.path("downloads").asLong() > maxDownloads || goals.stream().noneMatch(fact::supports)) continue;
                var candidate = new CandidateMod(slug, info.path("title").asText(), info.path("description").asText(), info.path("downloads").asLong());
                candidate.dates(info); candidate.category = "optimization"; candidate.textScore.add(2);
                candidate.preferences = goals.stream().filter(fact::supports).map(OptimizationPortfolio.Goal::preferences).findFirst().orElse(yagen.waitmydawn.maa.model.agent.SelectionPreferences.DEFAULT);
                goals.stream().filter(fact::supports).forEach(g -> hints.computeIfAbsent(slug, ignored -> new LinkedHashSet<>()).add(g.id()));
                raw.put(slug, candidate);
            }
        } catch (RuntimeException unavailable) { resources.unknown(); }
        var verified = new LinkedHashSet<String>();
        verifyPoolCompatibility(filterPlanCandidates(new ArrayList<>(raw.values()), execution), loader, mc, resources)
                .forEach(c -> verified.add(c.slug));
        int searches = 0;
        for (var role : goals.stream().flatMap(g -> g.desired().stream()).distinct().sorted().toList()) {
            if (isAborted(uuid) || searches >= 4) break;
            // 无目标环境版本的种子不能阻止补充召回；客户端证据也不能填补服务端用途。
            if (goals.stream().filter(g -> g.desired().contains(role)).allMatch(g ->
                    java.util.stream.Stream.concat(existing.stream(), verified.stream()).map(facts::get)
                            .filter(Objects::nonNull).anyMatch(f -> f.matching(g).contains(role)))) continue;
            if (!resources.search("optimization:" + role.id + ":" + loader + ":" + mc)) continue;
            searches++;
            try {
                String facets = String.format("[[\"project_type:mod\"],[\"loaders:%s\"],[\"versions:%s\"],[\"categories:optimization\"]]", loader, mc);
                var response = apiClient.searchPage(role.query, 15, 0, null, facets);
                if (response == null) { resources.unknown(); continue; }
                var candidates = new LinkedHashMap<String, JsonNode>();
                for (var hit : response.path("hits")) {
                    String slug = hit.path("slug").asText();
                    if (!slug.isBlank() && !existing.contains(slug) && hit.path("downloads").asLong() <= maxDownloads
                            && !OptimizationPortfolio.classify(hit.path("description").asText()).isEmpty()) candidates.put(slug, hit);
                }
                var metadata = apiClient.getProjectsBatch(candidates.keySet());
                var newCandidates = new ArrayList<CandidateMod>();
                for (var row : candidates.entrySet()) {
                    var info = metadata.getOrDefault(row.getKey(), row.getValue()); var fact = OptimizationPortfolio.fact(row.getKey(), info);
                    facts.put(row.getKey(), fact);
                    if (goals.stream().noneMatch(fact::supports)) continue;
                    var candidate = new CandidateMod(row.getKey(), info.path("title").asText(), info.path("description").asText(), info.path("downloads").asLong());
                    candidate.dates(info); candidate.category = "optimization"; candidate.textScore.add(2);
                    candidate.preferences = goals.stream().filter(fact::supports).map(OptimizationPortfolio.Goal::preferences).findFirst().orElse(yagen.waitmydawn.maa.model.agent.SelectionPreferences.DEFAULT);
                    goals.stream().filter(fact::supports).forEach(g -> hints.computeIfAbsent(row.getKey(), ignored -> new LinkedHashSet<>()).add(g.id()));
                    if (raw.size() < 32 && !raw.containsKey(row.getKey())) {
                        raw.put(row.getKey(), candidate); newCandidates.add(candidate);
                    }
                }
                verifyPoolCompatibility(filterPlanCandidates(newCandidates, execution), loader, mc, resources)
                        .forEach(c -> verified.add(c.slug));
            } catch (RuntimeException unavailable) {
                if (isAborted(uuid)) throw new java.util.concurrent.CancellationException();
                resources.unknown();
            }
        }
        var ordered = scoreAddonPool(new ArrayList<>(raw.values())).stream().sorted(SCORE_THEN_DOWNLOADS).toList();
        var order = OptimizationPortfolio.order(ordered.stream().map(c -> c.slug).toList(), hints, existing, facts, goals, targetFiles);
        ordered = order.stream().map(raw::get).toList();
        int cap = Math.min(16, Math.max(4, OptimizationPortfolio.referenceMax(targetFiles) + 2));
        var result = filterPlanCandidates(ordered, execution).stream().filter(c -> verified.contains(c.slug)).limit(cap).toList();
        MaaLog.user("优化用途召回：目标 " + goals.stream().map(g -> g.source() + " / " + g.side()).toList()
                + "；兼容候选 " + result.stream().map(c -> c.slug).toList() + "；补充查询 " + searches + "/4（仅召回，不代表入包）");
        return result;
    }

    /** 原文关联的召回池；文本命中仅保留检索出处，满足判断交给内容审核和最终需求复核。 */
    private List<CandidateMod> buildRequirementPool(List<yagen.waitmydawn.maa.model.agent.BuildRequirement> requirements,
            Set<String> existing, String loader, String mc, long maxDownloads, BuildExecutionPlan execution,
            RetrievalBudget resources, String uuid, Map<String, Set<String>> hints) {
        return buildRequirementPool(requirements, existing, loader, mc, maxDownloads, execution, resources, uuid, hints, true);
    }
    private List<CandidateMod> buildRequirementPool(List<yagen.waitmydawn.maa.model.agent.BuildRequirement> requirements,
            Set<String> existing, String loader, String mc, long maxDownloads, BuildExecutionPlan execution,
            RetrievalBudget resources, String uuid, Map<String, Set<String>> hints, boolean readExistingFacts) {
        var result = new LinkedHashMap<String, CandidateMod>();
        var rows = new HashMap<String, List<CandidateMod>>();
        var preferencesById = requirements.stream().collect(Collectors.toMap(
                yagen.waitmydawn.maa.model.agent.BuildRequirement::id,
                yagen.waitmydawn.maa.model.agent.BuildRequirement::preferences, (a, b) -> a));
        var perRequirement = new HashMap<String, Integer>();
        var existingFacts = new ArrayList<CandidateMod>();
        if (requirements.isEmpty()) return List.of();
        var generationTargets = DimensionGeneration.targets(execution.intent().requirements());
        var queries = RequirementRetrieval.queries(requirements, Set.copyOf(execution.intent().excludedCategories()), generationTargets);
        var queryCounts = queries.stream().collect(Collectors.groupingBy(RequirementRetrieval.Query::requirementId, Collectors.counting()));
        try {
            Map<String, JsonNode> metadata = Map.of();
            if (readExistingFacts) {
                try { metadata = apiClient.getProjectsBatch(existing); }
                catch (ModrinthApiClient.PartialProjectBatchException incomplete) {
                    resources.unknown();
                    metadata = incomplete.projects();
                }
            }
            for (var entry : metadata.entrySet()) {
                String id = entry.getKey();
                var info = entry.getValue();
                if (info != null && !info.path("slug").asText().isBlank()) {
                    var c = new CandidateMod(info.path("slug").asText(), info.path("title").asText(), info.path("description").asText(), info.path("downloads").asLong());
                    existingFacts.add(c);
                    if (!id.equals(c.slug)) hints.put(id, hints.computeIfAbsent(c.slug, ignored -> new LinkedHashSet<>()));
                }
            }
        } catch (Exception unavailable) { resources.unknown(); }
        var existingIds = new HashSet<>(existing);
        existingFacts.forEach(c -> existingIds.add(c.slug));
        for (var query : queries) {
            if (isAborted(uuid) || result.size() >= POOL_CAP) break;
            var preferences = preferencesById.getOrDefault(query.requirementId(), yagen.waitmydawn.maa.model.agent.SelectionPreferences.DEFAULT);
            String key = query.category() + ":" + query.keyword() + ":" + preferences;
            var terms = RetrievalScorer.tokenize(query.keyword());
            if (terms.isEmpty()) continue;
            for (var c : existingFacts) if (requirementMatch(c, terms, query.category()) > 0)
                hints.computeIfAbsent(c.slug, ignored -> new LinkedHashSet<>()).add(query.requirementId());
            if (perRequirement.getOrDefault(query.requirementId(), 0) >= 8) continue;
            if (!rows.containsKey(key)) {
                if (!resources.search("requirement:" + key)) continue;
                var raw = new LinkedHashMap<String, CandidateMod>();
                if (modrinthCacheService.isAvailable()) for (var hit : modrinthCacheService.matchByKeyword(query.category(), terms, loader, maxDownloads, 40)) {
                    var e = hit.entry();
                    var c = new CandidateMod(e.slug(), e.title(), e.description(), e.downloads());
                    if (requirementMatch(c, terms, query.category()) > 0) { c.category = query.category(); c.addMatch(hit.score(), hit.terms()); raw.put(c.slug, c); }
                }
                try {
                    String facets = String.format("[[\"project_type:mod\"],[\"loaders:%s\"],[\"versions:%s\"],[\"categories:%s\"]]", loader, mc, query.category());
                    for (var channel : PreferenceRanking.channels(preferences)) {
                        if (!channel.equals(PreferenceRanking.channels(preferences).getFirst())
                                && !resources.search("requirement:" + key + ":" + channel)) continue;
                        var response = apiClient.searchPage(query.keyword(), 15, 0,
                                channel.index().equals("relevance") ? null : channel.index(), PreferenceRanking.facets(facets, channel));
                        if (response == null) resources.unknown();
                        else for (var hit : response.path("hits")) {
                            var c = new CandidateMod(hit.path("slug").asText(), hit.path("title").asText(), hit.path("description").asText(), hit.path("downloads").asLong());
                            double score = requirementMatch(c, terms, query.category());
                            if (!c.slug.isBlank() && score > 0) {
                                c.dates(hit); c.category = query.category(); c.textScore.add(score);
                                var prior = raw.putIfAbsent(c.slug, c);
                                if (prior != null) prior.dates(hit);
                            }
                        }
                    }
                } catch (Exception unavailable) { resources.unknown(); }
                raw.values().forEach(c -> c.preferences = preferences);
                var generationGoals = generationTargets.getOrDefault(query.requirementId(), List.of());
                boolean generationQuery = generationGoals.stream().anyMatch(g -> g.query().equals(query.keyword()));
                var generationMatches = generationQuery ? raw.values().stream()
                        .filter(c -> generationGoals.stream().anyMatch(g -> DimensionGeneration.supports(g, c.desc)))
                        .map(c -> c.slug).collect(Collectors.toSet()) : Set.<String>of();
                var ranked = Comparator.<CandidateMod, Boolean>comparing(c -> generationMatches.contains(c.slug)).reversed()
                        .thenComparing(SCORE_THEN_DOWNLOADS).thenComparing(c -> c.slug);
                var ordered = scoreAddonPool(new ArrayList<>(raw.values())).stream().filter(c -> c.downloads <= maxDownloads)
                        .filter(c -> !existingIds.contains(c.slug)).sorted(ranked).toList();
                ordered = filterPlanCandidates(ordered, execution).stream().limit(16).toList();
                rows.put(key, verifyPoolCompatibility(ordered, loader, mc, resources).stream().sorted(ranked).limit(8).toList());
            }
            int retained = perRequirement.getOrDefault(query.requirementId(), 0);
            int queryAllowance = queryCounts.getOrDefault(query.requirementId(), 1L) == 1 ? 8
                    : Math.max(1, Math.min(4, 8 / queryCounts.get(query.requirementId()).intValue()));
            int offered = 0;
            for (var c : rows.get(key)) {
                if (retained >= 8 || offered >= queryAllowance || result.size() >= POOL_CAP) break;
                var ids = hints.computeIfAbsent(c.slug, ignored -> new LinkedHashSet<>());
                if (ids.add(query.requirementId())) { retained++; offered++; }
                for (var goal : generationTargets.getOrDefault(query.requirementId(), List.of()))
                    if (DimensionGeneration.supports(goal, c.desc)) ids.add(query.requirementId() + "::generation:" + goal.dimension());
                result.putIfAbsent(c.slug, c);
            }
            perRequirement.put(query.requirementId(), retained);
        }
        for (var r : requirements) MaaLog.user("逐项召回 " + r.id() + "：兼容候选 " + perRequirement.getOrDefault(r.id(), 0) + "；关联是检索线索，满足情况待审核");
        return new ArrayList<>(result.values());
    }

    private static double requirementMatch(CandidateMod c, List<String> terms, String category) {
        String heading = (c.slug + " " + c.title).toLowerCase(Locale.ROOT);
        if (!category.equals("library") && heading.matches(".*\\b(?:library|api)\\b.*")) return 0;
        return RetrievalScorer.match(terms, c.slug.toLowerCase(Locale.ROOT), c.title.toLowerCase(Locale.ROOT), c.desc.toLowerCase(Locale.ROOT)).score();
    }

    /** 各能力独立召回并预留候选位；用途过滤在版本核实前完成，避免宽泛命中耗尽后续目标预算。 */
    private List<CandidateMod> buildGoalPool(List<GoalCatalog.Profile> profiles, Set<String> existing,
            String loader, String mc, long maxDownloads, BuildExecutionPlan execution, RetrievalBudget resources,
            String uuid, Map<String, Set<yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability>> roles, Map<String, Set<String>> facetsByProject) {
        var facts = new LinkedHashMap<String, CandidateMod>();
        var identities = new LinkedHashSet<>(existing);
        profiles.forEach(p -> identities.addAll(p.seeds()));
        for (var requirement : execution.intent().requirements())
            if (!Collections.disjoint(requirement.capabilities(), profiles.stream().map(GoalCatalog.Profile::capability).toList()))
                identities.addAll(requirement.projects());
        identities.removeAll(execution.blocked());
        var existingIdentities = new HashSet<>(existing);
        try { apiClient.prefetchProjects(identities); }
        catch (Exception unavailable) { resources.unknown(); }
        for (String slug : identities) {
            if (isAborted(uuid)) break;
            try {
                var info = apiClient.getProjectInfo(slug);
                if (info == null || info.path("slug").asText().isBlank()) continue;
                var c = new CandidateMod(info.path("slug").asText(), info.path("title").asText(),
                        info.path("description").asText(), info.path("downloads").asLong());
                for (var p : profiles) {
                    String evidence = GoalCatalog.evidence(p, c.slug, c.title, c.desc);
                    if (evidence != null) {
                        c.goalEvidence.put(p.capability(), evidence);
                        c.goalFacets.addAll(GoalCatalog.facets(p, c.slug, c.title, c.desc));
                        if (c.category == null) c.category = p.category();
                    }
                }
                if (!c.goalEvidence.isEmpty()) {
                    c.textScore.add(2); facts.put(c.slug, c);
                    if (existing.contains(slug)) {
                        existingIdentities.add(c.slug);
                        roles.put(slug, Set.copyOf(c.goalEvidence.keySet())); roles.put(c.slug, Set.copyOf(c.goalEvidence.keySet()));
                        facetsByProject.put(slug, Set.copyOf(c.goalFacets)); facetsByProject.put(c.slug, Set.copyOf(c.goalFacets));
                    }
                }
            } catch (Exception unavailable) { resources.unknown(); }
        }
        var result = new LinkedHashMap<String, CandidateMod>();
        for (var p : profiles) {
            if (isAborted(uuid)) break;
            long present = existing.stream().filter(s -> roles.getOrDefault(s, Set.of()).contains(p.capability())).count();
            var presentFacets = existing.stream().flatMap(s -> facetsByProject.getOrDefault(s, Set.of()).stream()).collect(Collectors.toSet());
            if (present >= p.target() && presentFacets.containsAll(GoalCatalog.desiredFacets(p))) continue;
            int cap = Math.min(12, Math.max(4, p.target() * 3));
            var raw = new LinkedHashMap<String, CandidateMod>();
            facts.values().stream().filter(c -> c.goalEvidence.containsKey(p.capability()))
                    .filter(c -> !existingIdentities.contains(c.slug) && c.downloads <= maxDownloads).forEach(c -> raw.put(c.slug, c));
            int queryIndex = 0;
            for (String query : p.queries()) {
                String queryCategory = GoalCatalog.queryCategory(p, query);
                if (modrinthCacheService.isAvailable()) {
                    for (var hit : modrinthCacheService.matchByKeyword(queryCategory, RetrievalScorer.tokenize(query), loader, maxDownloads, 60)) {
                        var entry = hit.entry();
                        var c = new CandidateMod(entry.slug(), entry.title(), entry.description(), entry.downloads());
                        String evidence = GoalCatalog.evidence(p, c.slug, c.title, c.desc);
                        if (existingIdentities.contains(c.slug) || evidence == null) continue;
                        c.category = queryCategory; c.goalEvidence.put(p.capability(), evidence);
                        c.goalFacets.addAll(GoalCatalog.facets(p, c.slug, c.title, c.desc));
                        c.addMatch(hit.score(), hit.terms()); raw.putIfAbsent(c.slug, c);
                    }
                }
                boolean diversityQuery = Set.of(yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability.NETHER,
                        yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability.END,
                        yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability.CREATURE_ECOLOGY).contains(p.capability());
                if (queryIndex++ > 0 && raw.size() >= cap && !diversityQuery) continue;
                if (!resources.search("goal:" + queryCategory + ":" + query)) continue;
                try {
                    String facets = String.format("[[\"project_type:mod\"],[\"loaders:%s\"],[\"versions:%s\"],[\"categories:%s\"]]", loader, mc, queryCategory);
                    var hits = apiClient.searchPage(query, 15, 0, null, facets);
                    if (hits == null) { resources.unknown(); continue; }
                    for (var hit : hits.path("hits")) {
                        var c = new CandidateMod(hit.path("slug").asText(), hit.path("title").asText(), hit.path("description").asText(), hit.path("downloads").asLong());
                        String evidence = GoalCatalog.evidence(p, c.slug, c.title, c.desc);
                        if (c.slug.isBlank() || existingIdentities.contains(c.slug) || c.downloads > maxDownloads || evidence == null) continue;
                        c.category = queryCategory; c.goalEvidence.put(p.capability(), evidence);
                        c.goalFacets.addAll(GoalCatalog.facets(p, c.slug, c.title, c.desc));
                        var match = RetrievalScorer.match(RetrievalScorer.tokenize(query), c.slug.toLowerCase(Locale.ROOT),
                                c.title.toLowerCase(Locale.ROOT), c.desc.toLowerCase(Locale.ROOT));
                        c.addMatch(Math.max(1, match.score()), match.terms()); raw.putIfAbsent(c.slug, c);
                    }
                } catch (Exception unavailable) { resources.unknown(); }
            }
            List<CandidateMod> ordered = scoreAddonPool(new ArrayList<>(raw.values())).stream().sorted(SCORE_THEN_DOWNLOADS).toList();
            var roleHints = new HashMap<String, Set<yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability>>();
            var facetHints = new HashMap<String, Set<String>>();
            raw.values().forEach(c -> { roleHints.put(c.slug, Set.copyOf(c.goalEvidence.keySet())); facetHints.put(c.slug, Set.copyOf(c.goalFacets)); });
            var goalOrder = GoalCatalog.order(ordered.stream().map(c -> c.slug).toList(), roleHints, Map.of(p.capability(), p.target()), existingIdentities, facetHints);
            ordered = goalOrder.stream().map(raw::get).toList();
            ordered = filterPlanCandidates(ordered, execution).stream().limit(cap * 2L).toList();
            var compatible = verifyPoolCompatibility(ordered, loader, mc, resources).stream()
                    .sorted(Comparator.comparingInt(c -> goalOrder.indexOf(c.slug))).limit(cap).toList();
            for (var c : compatible) {
                var kept = result.get(c.slug);
                if (kept == null) result.put(c.slug, c); else { kept.goalEvidence.putAll(c.goalEvidence); kept.goalFacets.addAll(c.goalFacets); }
            }
            MaaLog.user("需求召回 " + p.capability() + "：已有用途证据 " + present + "，兼容候选 " + compatible.size() + "，参考 " + p.target());
        }
        result.values().forEach(c -> roles.put(c.slug, Set.copyOf(c.goalEvidence.keySet())));
        result.values().forEach(c -> facetsByProject.put(c.slug, Set.copyOf(c.goalFacets)));
        return new ArrayList<>(result.values());
    }

    // 🚀 建池器 2：多路召回重排引擎
    private List<CandidateMod> buildFillerPoolByKeywords(List<SearchIntent> intents, Map<String, Integer> budget,
                                                         String loader, String mcVersion, Set<String> existing,
                                                         long maxDownloads, String uuid) {
        return buildFillerPoolByKeywords(intents, budget, loader, mcVersion, existing, maxDownloads, uuid, new RetrievalBudget());
    }
    private List<CandidateMod> buildFillerPoolByKeywords(List<SearchIntent> intents, Map<String, Integer> budget,
                                                         String loader, String mcVersion, Set<String> existing,
                                                         long maxDownloads, String uuid, RetrievalBudget resources) {
        List<CandidateMod> local = List.of();
        if (modrinthCacheService.isAvailable()) {
            local = buildFillerPoolLocal(intents, budget, loader, mcVersion, existing, maxDownloads, resources);
            int desired = Math.min(POOL_CAP, budget.values().stream().mapToInt(Integer::intValue).sum() / POOL_OVERSAMPLE);
            if (local.size() >= desired) return local;
        }
        String facetsRaw = String.format("[[\"project_type:mod\"], [\"loaders:%s\"], [\"versions:%s\"]]", loader, mcVersion);
        // 在线兜底路径同样"按类别分别召回、分别截断"，并用与本地一致的打分口径
        Map<String, List<CandidateMod>> byCategory = new LinkedHashMap<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 429 保护：一个关键词 = 一次 /v2/search，关键词数量上去以后必须有闸门（本地路径无网络请求，不需要）
            Semaphore gate = new Semaphore(5);
            for (SearchIntent intent : intents) {
                if (budget.getOrDefault(intent.category(), 0) <= 0) continue;
                Map<String, CandidateMod> hitMap = new ConcurrentHashMap<>();
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (String kw : intent.keywords()) {
                    final String safeKw = kw;
                    if (!resources.search(intent.category() + ":" + kw)) continue;
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
                        } catch (Exception unavailable) {
                            resources.unknown();
                        } finally {
                            if (acquired) gate.release();
                        }
                    }, executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                byCategory.put(intent.category(), new ArrayList<>(hitMap.values()));
            }
        }
        var merged = new ArrayList<>(local);
        merged.addAll(finalizePool(byCategory, budget, loader, mcVersion, "在线补充", resources));
        return dedupeAndCap(merged);
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
