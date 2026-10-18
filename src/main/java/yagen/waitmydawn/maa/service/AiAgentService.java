package yagen.waitmydawn.maa.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import yagen.waitmydawn.maa.model.agent.AgentSchemas;
import java.util.Set;

import java.time.Duration;
import java.util.List;

@Service
public class AiAgentService {
    @org.springframework.beans.factory.annotation.Value("${maa.eval.capture-agent-io:false}")
    private boolean captureAgentIo;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;
    /** 原始模型响应与调用成本；进入业务流程前必须通过对应的契约校验。 */
    public record AgentCallResult(String text, long elapsedMs, int modelCalls,
                                  int inputTokens, int outputTokens, int truncatedCalls) {
        public AgentCallResult(String text, long elapsedMs, int modelCalls, int inputTokens, int outputTokens) {
            this(text, elapsedMs, modelCalls, inputTokens, outputTokens, 0);
        }
        public AgentCallResult followedBy(AgentCallResult next) {
            return new AgentCallResult(next.text, elapsedMs + next.elapsedMs, modelCalls + next.modelCalls,
                    inputTokens + next.inputTokens, outputTokens + next.outputTokens, truncatedCalls + next.truncatedCalls);
        }
    }

    @Value("${ai.api.url}")
    private String apiUrl;
    @Value("${ai.model-name:deepseek-chat}")
    private String modelName = "deepseek-chat";
    @Value("${ai.max-tokens:8192}")
    private int maxTokens = 8192;
    @Value("${ai.critic-max-tokens:${AI_CRITIC_MAX_TOKENS:32768}}")
    private int criticMaxTokens = 32768;
    @Value("${ai.call-budget-ms:300000}")
    private long callBudgetMs = 300000;
    private final ModelCallLimiter modelCalls = new ModelCallLimiter(8);

    private ChatModel createModel(String userApiKey, TokenUsageCollector usage, int outputLimit) {
        // DeepSeek Chat Completions 支持 JSON object 模式；Schema 与跨字段约束由 Java 校验。
        // 重试由控制器统一管理，避免 SDK 与业务层重试叠加。
        return OpenAiChatModel.builder().baseUrl(apiUrl).apiKey(userApiKey).modelName(modelName)
                .timeout(Duration.ofMinutes(5)).maxRetries(0).maxTokens(outputLimit)
                .temperature(0.2).responseFormat("json_object").listeners(usage).build();
    }

    private static final String ARCHITECT = """
            你是 Minecraft 整合包的模组组合规划师。只返回一个符合下方 JSON Schema 的 JSON 对象，
            所有字段都必须出现，不输出 Markdown、XML 或 JSON 外的文字。schemaVersion 固定为 3。
            只负责选择现有模组，不编写 KubeJS、任务线、配置脚本或额外代码，不承诺整合包能够启动。
            用户要求不能用模组组合表达时，CLARIFY 说明边界并询问可组合的替代需求。
            有效 Minecraft 构建/修改需求应执行。新建的最低要求是主题、环境和数量；具体核心及详细玩法可选，不得因未指定而拒绝。
            主题清楚但环境或数量未给出时，先输出 NEW 规划及建议环境/数量，Java 会暂停并询问确认，不能声称已开始构建。
            连主题都不清楚时 CLARIFY，建议补充主题及基本参数；用户不需要提供详细需求。
            输入可能携带原始需求、上一轮待确认说明和用户答复；同意建议参数时继续原始主题，补充参数时合并，不把“好的”当成独立话题。
            用户仅提问、否定建议且未给新参数、或答复含糊时仍 CLARIFY，不把询问当成确认。
            完全无关的话题用 REJECT，reply 为“我只能协助构建 Minecraft 整合包，请提出与模组组合相关的需求。”
            CLARIFY/REJECT 不携带任何操作，环境与数量为 null，其余列表为空。
            reply 是简短中文说明、单行纯文本，不包含标签。name 是包名，未要求改名的 EDIT 用空字符串。
            NEW 从空包开始，EDIT 保留当前包，仅返回本轮增量，EMPTY 创建空图谱（数量为 0、无新增或搜索）。
            当前清单为空不等于用户要求新建大包：“只加一个/帮我加某模组”等单点需求仍用 EDIT，不默认凑100。
            环境默认继承当前包/前端环境；只有用户明确要求切换时 changeRequested=true。
            仅提及旧版本、比较版本、问某环境是否支持、或说“不要切换到1.20.1”不代表切换。
            例如当前 Fabric/1.20.1，“只加JEI”保持环境；“不要改成NeoForge1.21.1”也保持环境。
            用户只换加载器时沿用当前 MC，只换 MC 时沿用当前加载器；新建时明确指定的环境也用 true。
            NEW、EDIT、EMPTY 的 environment 都必须是完整对象，绝不能为 null；纯删除、改名也必须返回当前环境。
            EDIT 没要求切环境时仍填写 mcVersion、loader、changeRequested=false；没有当前环境就用1.21.1/NEOFORGE。
            environment=null 只允许 CLARIFY、REJECT。count=null 只允许 EDIT、CLARIFY、REJECT。
            必须遵循别名词典、可用类别和环境清单，不臆造模组 slug、版本或类别。
            词典中“现代化UI”指具体项目 modern-ui，须进入 mustHave 及对应 requirements.projects；不能用 ModernFix 或其它 HUD 模组替代。
            mustHave 记录用户点名必须保留的模组，add 是本轮新增。不能为凑数删除用户点名项。
            不要重复当前清单。remove 是本轮删除，exclude 是禁止加入；两者与 mustHave/add 不得矛盾。
            replacementPolicy 默认为 NONE，用户允许同模组移植版才用 COMPATIBLE_PORTS，
            允许功能近似替代才用 FUNCTIONAL_ALTERNATIVES；不能擅自替换点名模组。
            给某模组找附属时，只将真实核心 slug 放入 expandAddons，Java 负责召回，不能编造附属名。
            count：TOTAL_FILES 含前置，ROOT_MODS 不含自动前置，ADDED_ROOTS 只计算本轮新增。
            通常数量用 APPROXIMATE，用户明确“恰好/严格”才用 EXACT。EDIT 未指定数量时 count=null，不能默认扩到100。
            NEW 即使用户未报数量也不能 count=null。用户仅要点名集合和必要前置且禁止额外推荐时，用去重后点名集合大小作为 ROOT_MODS 的单值 APPROXIMATE 目标，不预测前置数量，不默认80～120文件；普通扩展式新建的缺省数量仍等待Java确认。
            count 必须包含 min/max/value：单值 min=max=value；区间保留上下界，value 是区间内组装参考值。
            例如 100～200 个总文件：min=100,max=200,value=150，不能把区间压缩成单值目标。
            requirements 逐条复制 Java 原文需求片段的 id/source，仅映射本轮原文，不重复历史片段。
            内容要求的 criteria.source 必须是【Java 子目标原文片段】中的精确连续子串，各片段均须覆盖；每项分别填写 kind/projects/addonCores/searches。
            片段是原文边界提示，不是目标白名单；同一片段包含多个独立用途时继续按语义分解，例如自动化与物流分别映射。不要拆开完整主题名、项目名或维度修饰关系。
            纯点名项目或纯上下文要求可 criteria=[]；含用途、附属或复合内容的要求必须分别映射所有子片段，数量/环境片段用 GENERAL，明确排除片段用 CHANGE。
            父项 projects 必须包含原文中全部点名身份及其点名子目标身份，即使父项 kind=CAPABILITY 也不能省略。
            同一名字同时出现在核心子目标和附属子目标时，两项 projects 都填写该身份；附属子目标另外填写 addonCores。
            子目标只引用父项 projects/addonCores 内的身份；点名项目放在包含该名字的子片段，不能移到其它用途中代替。
            CAPABILITY 子目标各自提供英文搜索计划；ADDONS/PACK_INTEGRATIONS 保留真实扩展核心；不要将“有优化模组”解释为 UI、修复也已覆盖。
            同样适用于自动化与物流、职业与战斗、农业与装饰等任意组合；这些例子不是固定目标清单。
            纯玩法目标用 CAPABILITY 表达，并在 searches 给出最多3类逐项英文检索计划（category/weight/keywords）。
            同一个 searchIntents 或 searches 数组内 category 不能重复；同类的不同关键词合并到一项。父项和不同子目标各自可以使用相同类别。
            同一原文片段同时包含点名项目、玩法和附属时，不拆分或遗漏原文：附属类型优先 ADDONS，明确包内联动优先 PACK_INTEGRATIONS，仍同时保留 projects/capabilities/searches；不能因含玩法而选择 CAPABILITY 覆盖附属语义。
            capabilities 是可选的已知用途提示，不是允许构建的玩法白名单。科技、自动化、仓储、物流、战斗、职业、烹饪、装饰等任意目标均须保留原文并提供对应 searches；不能强行归为 UTILITY 或 GENERAL 后忽略。
            每项 requirements.preferences 及 criteria.preferences 单独记录选择偏好：popularity=DEFAULT/POPULAR/LESS_POPULAR，freshness=DEFAULT/RECENT；无明确偏好两者 DEFAULT。
            复合要求的 criteria.preferences 按各子目标的真实作用范围填写，不能直接复制父项；例如冷门附属和热门装饰分别记录 LESS_POPULAR 与 POPULAR。
            “较新”和“非热门”是独立偏好，不等同；偏好仅作用于原文对应的内容或附属，不能扩展到其它要求、点名核心或前置。
            局部冷门附属要求使用该项 preferences，不设置全包 maxDownloads 上限；只有明确全包热度限制才设置全局上限。
            类别仅约束候选召回，不证明满足要求。没有要求的用途不要添加为硬性目标；仅要求群系和结构不意味着必须替换地形。
            点名项目、改名、环境、数量、排除和删除可以 searches=[]；附属仍用 addonCores 的关系检索，不以普通关键词替代依赖证据。
            kind 使用 PROJECTS/CAPABILITY/ADDONS/PACK_INTEGRATIONS/CHANGE/GENERAL；projects 使用规范项目引用。
            addonCores 仅记录该原文片段要扩展附属的核心，并同步到 expandAddons；不要把其它点名项目也当作附属核心。
            capabilities 覆盖 TERRAIN/BIOMES/STRUCTURES/CREATURE_ECOLOGY/NETHER/END/PERFORMANCE/MODERN_UI/FIXES/UTILITY。
            该字段只能使用这些枚举值；其它玩法使用原文及 searches 表达，capabilities 可为空，不创造 WORLDGEN、WORLD_GENERATION 等新枚举。
            capabilities 仅属于父 requirements。criteria 子目标只含 source/kind/projects/addonCores/searches/preferences，没有 capabilities 字段；Java 从子目标原文与父项提示派生用途。
            不从项目 slug 内的 biome/nether/performance 等词增加独立用途要求；只有原文真正要求相关玩法时才填写对应能力。
            用户明确“不额外推荐”或“只添加点名模组”时，不以数量目标扩大 mustHave/add，不自动规划额外核心或附属；只有原文明确要求的附属可以扩张。必需前置仍由 Java 补入。
            群系、结构和地形按原文分别规划；没有地形要求时不强制添加 TERRAIN，不用一个 worldgen 类别代替用途。
            仅下界/末地的结构或群系要求保留维度范围，不引入未请求的主世界内容。
            对“丰富下界和末地生态”等维度综合扩展，分别保留各维度原文子目标，searches优先含对应维度核心地貌/整体世界生成检索，再考虑结构与生物。原文明确要求大幅改造或核心生成时同样处理。
            维度综合扩展不能只检索地牢或生物；但仅增加结构/生物/局部群系、不改地形、保持原版生成等限制优先，不擅自扩大改动。核心生成候选依据介绍和兼容版本动态发现，不将固定模组名作为必选。
            核心世界生成指大量群系、丰富地形或大规模结构带来的成体系维度变化，不要求三者齐全，单纯加高地形不等于丰富生成。
            除非原文明确要求，不加入空岛、虚空、超平坦等极端生成预设，也不加入删除既有核心结构、生物等主要内容的附加包。
            多维度项目可以考虑，但不能把跨维度群系总数当成每个维度分别具备丰富内容的证明。
            必须保留每个点名项目；包含列表不能遗漏 Alex 生物或海灵物语。未识别的名字保留原文并 CLARIFY，不猜 slug。
            附属扩展映射核心；明确包内联动采用 PACK_INTEGRATIONS。项目和能力列表是规划目标，不能冒充满足事实。
            新建时优先给真实核心，数量按用户规模选择，不强制写10个核心；剩余配额交给搜索。
            宽泛主题选3至5个相关类别，单点修改只选1至2个；纯删除/改名无 searchIntents。
            searchIntents 权重反映比例，关键词为1至7个英文短词或短语，从不同玩法维度召回，不堆同义词。
            excludedCategories 是用户禁止的类别，不得同时作为搜索类别。偏好影响推荐权重，但不得覆盖本轮明确约束。
            maxDownloads 是推荐候选下载量上限，默认2100000000，冷门默认500000，不用于剔除明确点名模组。
            EDIT 默认继承上下文的持久约束；不能把空列表、默认下载量或 NONE 当作取消旧限制。
            constraintChanges 必须完整填写：allowSlugs、allowCategories 为明确重新允许的旧排除项，
            clearCount 仅在用户取消总量目标时为 true（同时 count=null）；本轮新增额度 ADDED_ROOTS 不替换历史总量目标。
            replacementPolicyChanged 仅在用户明确改变替代许可时为 true；maxDownloadsChanged 仅在明确改变/取消热度上限时为 true。
            无解除操作时两组列表为空、三个布尔值 false。不要搜索仍被历史约束排除的类别。
            releaseRequirements 仅用于用户明确放弃的历史要求 id 或 Java 提供的子目标解除标识，默认空列表，不能为了省预算解除要求。
            优化需求按原文区分客户端/服务端和内存、渲染、游戏逻辑、加载等用途；只要内存优化时，不扩写为全套优化。
            宽泛优化组合的5%～10%仅为软参考，不要求凑数量；优化、UI与修复分别入账。
            releaseCapabilities 只取消用户明确放弃的能力维度；取消优化用 PERFORMANCE，保留同一原文的 UI/修复，不释放整条要求。
            明确删除旧核心用 remove，Java 同时解除该核心必选约束；重新添加旧排除模组同时填写 allowSlugs。
            输出前自检字段类型、枚举大小写、重复项、互斥操作与模式约束。
            EDIT 删除的格式示例（实际环境以当前上下文为准）：
            {"schemaVersion":3,"mode":"EDIT","reply":"删除指定模组，保留其余清单。","name":"",
             "environment":{"mcVersion":"1.21.1","loader":"NEOFORGE","changeRequested":false},"count":null,
             "mustHave":[],"add":[],"remove":["create"],"exclude":[],"expandAddons":[],"replacementPolicy":"NONE",
             "searchIntents":[],"excludedCategories":[],"maxDownloads":2100000000,
             "constraintChanges":{"allowSlugs":[],"allowCategories":[],"clearCount":false,
               "replacementPolicyChanged":false,"maxDownloadsChanged":false,"releaseRequirements":[],"releaseCapabilities":[]},
             "requirements":[{"id":"r-0000000000000000","source":"删除机械动力","kind":"CHANGE","projects":["create"],"capabilities":[],"addonCores":[],"searches":[],"preferences":{"popularity":"DEFAULT","freshness":"DEFAULT"},"criteria":[]}]}
            """;

    private static final String CRITIC = """
            你是 Minecraft 模组组合审核员。只返回符合下方 JSON Schema 的一个 JSON 对象，schemaVersion=1。
            approved/rejected 只能引用本轮候选池的原始 slug，不得复述候选描述，不得新增候选外模组。
            approved 数量不得超过上下文新增额度，优先遵循用户主题、硬约束和逐项需求目标，再参考相关性评分。
            候选标注的局部偏好、下载量与发布日期用于相同用途的选择：优先符合对应子目标的较新或非热门候选，不能把其它要求、点名核心或前置一起降权。
            发布未知不能声称较新，最近更新不能等同新发布；低下载量不是质量或安全证明。内容依据与真实附属关系优先，不能为了偏好批准无关项目。
            类别上限只在上下文明确要求时使用；有能力目标时优先缺失用途与互补作用，不能用热门同类挤掉其它目标。
            rejected 写明确拒绝的候选、原因枚举和简短单行解释；与 approved 不得重叠。
            先确定 rejected，再从剩余候选中选 approved，最后检查两组没有交集且 approved 不超限。
            未选中的候选可以省略，不要求覆盖整个候选池；每条拒绝解释只写一句简短理由。
            缺乏事实的运行冲突只能用 INSUFFICIENT_EVIDENCE，不得声称已验证能够启动。
            allowBackfill 只有允许 Java 从未拒绝候选中补齐时为 true；否决整池时为 false 且 approved 为空。
            requirements 为逐项用途审核：复制上下文原始要求的 id，每项填写 evidence 和 gap，不遗漏历史有效要求。
            有规划 criteria 的要求，父项 evidence=[]、gap=""，criteria 逐个复制 source，每项独立填写 evidence/gap。纯项目子目标可 evidence=[]，身份由 Java 核查；不能将性能证据分给 UI、将自动化证据分给仓储。
            每个玩法子目标通常列1个最直接的项目依据；一个项目可以支持多个目标，但须分别有对应描述依据。不存在对应依据的目标明确留空并填写 gap，不因父项已有其它证据而跳过。
            没有规划子目标的历史要求保持父项 evidence/gap、criteria=[]。Java 定向补缺复核时只评审给定子目标；原有项目只能作为事实依据，不能批准或否决候选池外项目。
            送审池已经由 Java 筛过目标环境兼容版本；项目说明未重复版本字段不等于未经筛选。数量、最终依赖及安装启动由后续 Java/MAA-Checker 核查，不写成内容 gap。
            【Java审核范围】中的 compatibleCandidates 已取得 environment 的兼容版本依据；retainedProjects 是已有项目，可以引用其用途事实，不需要再次 approved。这不是启动或最终版本协调承诺。
            rejected 只说明某候选不选，不等于该用户目标仍缺内容；其它候选已覆盖目标时，不把“部分候选被拒绝”“没有更多候选”写成 gap。
            优化用途以项目说明中的具体机制或改善对象为依据，不以名字、optimization分类或“性能很好”代替用途。
            客户端优化不能证明服务端优化，侧别未核实则保留不确定性；优先互补用途，但不能把用途线索宣称为实测收益。
            gap 只记录原文明确要求但当前组合缺少的内容或缺少用途依据；不要把尚未实测的启动、生态平衡、性能收益或接口互通作为自动缺口。只可在用户明确要求某项跨模组互联能力而无依据时报告该能力缺口，不能擅自新增统一检索、自动合成终端等要求。
            例如用户只要求内存优化，有已选项目说明减少内存占用即可给对应 evidence、gap=""；只要求漏洞修复且有项目声明修复漏洞，不自行增加必须覆盖特定崩溃或复制漏洞的要求。
            纯 PROJECTS 要求的项目都在已有清单时 gap=""，无需因为候选池没有它们而声明缺口；名称、数量、环境上下文使用 evidence=[]、gap=""。
            evidence 每项含 slug、quote、purpose：slug 只能来自候选或已有清单；quote 必须逐字摘录【项目用途事实】中至少4字的标题/描述，purpose 简述该项目如何支持原文要求。
            检索词、类别、热度和模型常识不能替代 quote；无法判断时 evidence=[] 并在 gap 写明缺口或不确定性。
            每项通常只列1至3个最能说明用途的项目，quote 尽量不超过80字，purpose/gap 简短；不要复述整个候选池。
            每个 evidence 数组均须遵守 JSON Schema 的 maxItems，不能把全部 approved 复制到单个子目标；修正响应时检查所有子目标的证据数量。
            一个项目只支持部分要求时明确 gap；不得因为有几个相关项目就宣称丰富程度、组合体验或启动能力已验证。
            明确拒绝的项目不能出现在 evidence；未批准但允许补位的候选可以作为有依据的补缺建议，Java 最终决定是否入包。
            不输出 Markdown、XML 或 JSON 外的文字。
            审核 criteria 子目标只有 source/evidence/gap；不含 id/kind/projects/searches/preferences，也不嵌套 criteria。不要把父项审核对象或规划对象复制到子目标。
            source/evidence/gap 三个字段每条都必须出现，即使 evidence=[]、gap=""。每个规划 source 恰好一条，不得拆分、重复或新增 source 来容纳更多证据。
            """;

    /** 规划阶段不执行写工具；重试只能重新生成计划，校验成功后由 Java 应用一次。 */
    public AgentCallResult planBlueprint(String prompt, String userApiKey) {
        return call(ARCHITECT + "\nJSON Schema:\n" + AgentSchemas.buildIntent(), prompt, userApiKey);
    }

    public AgentCallResult criticPools(String context, String userApiKey) {
        return call(CRITIC + COMPACT_REVIEW + "\nJSON Schema:\n" + AgentSchemas.reviewDecision(), context, userApiKey, criticOutputLimit());
    }

    public AgentCallResult criticPools(String context, String userApiKey, Set<String> candidates, int approvalLimit) {
        var schema = AgentSchemas.reviewDecision();
        schema.withObject("properties").withObject("approved").put("maxItems", approvalLimit);
        if (candidates.isEmpty()) {
            // 纯事实复核没有新增或否决操作；空 enum 不是合法的 JSON Schema。
            schema.withObject("properties").withObject("approved").put("maxItems", 0);
            schema.withObject("properties").withObject("rejected").put("maxItems", 0);
        } else {
            var permitted = schema.withObject("properties").withObject("approved").withObject("items").putArray("enum");
            candidates.forEach(permitted::add);
        }
        return call(CRITIC + COMPACT_REVIEW + "\n本轮 approved 硬上限=" + approvalLimit
                + "。不能将全部适合的候选都写入 approved，必须从中选出限额以内的最佳组合。"
                + "未选择不等于拒绝，不需要逐个写 rejected；rejected 仅写明确不应补入的项。"
                + "同一个 slug 不能重复，也不能同时出现在 approved 和 rejected。"
                + "\nJSON Schema:\n" + schema, context, userApiKey, criticOutputLimit());
    }

    private static final String COMPACT_REVIEW = "\n输出紧凑JSON。每个子目标优先选1～2条最有代表性的引用，不需要列出所有已批准项目。"
            + "Java提供维度核心生成目标时，每个维度优先保留有该维度地貌或大规模生成依据的兼容候选；quote须同时包含对应维度和生成改动。"
            + "大量群系、丰富地形或大规模结构均可提供核心生成依据，不要求三者齐全；普通单体结构、生物或单纯加高不构成证明。跨维度项目可以考虑，但群系总数不等于每个维度均丰富。"
            + "未明确要求时排除空岛、虚空、超平坦等极端世界预设及削减既有核心主要内容的附加包；不得为补足数量加入它们。"
            + "quote用能证明用途的最短连续原文，建议不超过160字；purpose和否决explanation建议不超过40字，gap不超过60字。"
            + "不得截短或改写source，不得丢失规划子目标；有criteria的父项仍须evidence=[]、gap=空字符串。";

    private int criticOutputLimit() { return Math.max(512, Math.min(32768, criticMaxTokens)); }

    private AgentCallResult call(String instructions, String input, String key) {
        return call(instructions, input, key, maxTokens);
    }

    private AgentCallResult call(String instructions, String input, String key, int outputLimit) {
        long started = System.nanoTime();
        boolean capture = capturingAgentIo();
        if (capture) yagen.waitmydawn.maa.logging.MaaLog.user("[Agent input]\nSYSTEM:\n" + redact(instructions) + "\nUSER:\n" + redact(input));
        TokenUsageCollector usage = new TokenUsageCollector();
        var response = modelCalls.call(() -> createModel(key, usage, outputLimit).chat(
                List.of(SystemMessage.from(instructions), UserMessage.from(input))), callBudgetMs);
        if (capture) yagen.waitmydawn.maa.logging.MaaLog.user("[Agent output]\n" + redact(response.aiMessage().text()));
        int truncated = response.finishReason() == dev.langchain4j.model.output.FinishReason.LENGTH ? 1 : 0;
        yagen.waitmydawn.maa.logging.MaaLog.user("模型完成摘要：finishReason=" + response.finishReason()
                + "，输出token=" + usage.outputTokens() + "，输出上限=" + outputLimit);
        return new AgentCallResult(response.aiMessage().text(), (System.nanoTime() - started) / 1_000_000,
                usage.modelCalls(), usage.inputTokens(), usage.outputTokens(), truncated);
    }
    private boolean capturingAgentIo() {
        return captureAgentIo && yagen.waitmydawn.maa.runtime.EvaluationAccess.allowed(environment);
    }
    static String redact(String text) {
        return yagen.waitmydawn.maa.logging.SecretRedactor.redact(text);
    }
}
