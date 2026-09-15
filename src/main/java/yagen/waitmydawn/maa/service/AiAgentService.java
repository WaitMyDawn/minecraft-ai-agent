package yagen.waitmydawn.maa.service;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import yagen.waitmydawn.maa.logging.MaaLog;

import java.time.Duration;

@Service
public class AiAgentService {

    /**
     * 单次 Agent 调用结果：文本 + 过程指标（耗时 / 模型调用次数 / token）。
     *
     * <p>过程指标用于评测的"过程层与成本层"，最终随 {@code <trace>} 返回，
     * 让评测器（GoldenSetEval）能自动断言，而不是只能人工看日志。
     */
    public record AgentCallResult(String text, long elapsedMs, int modelCalls,
                                  int inputTokens, int outputTokens) {
    }

    @Value("${ai.api.url}")
    private String apiUrl;

    /**
     * 单次模型调用的输出 token 上限。
     *
     * <p>做成可配置是为了两条用途：① 部署时按模型能力调整；
     * ② **故障注入验证**——把上限调到很小（如 {@code --ai.max-tokens=64}）就能稳定复现
     * "输出被截断 → XML 标签未闭合"的退化路径，用来验证兜底与"如实告知"确实生效（见坏例 B16）。
     */
    @Value("${ai.max-tokens:8192}")
    private int maxTokens;

    // 不复用 agent 实例, 每次请求根据用户 API key 动态创建
    // maxRetries(1) 确保用户终止思考时不会自动重试，立即响应中断
    private ChatModel createModel(String userApiKey, TokenUsageCollector usage) {
        return OpenAiChatModel.builder()
                .baseUrl(apiUrl)
                .apiKey(userApiKey)
                .modelName("deepseek-chat")
                .timeout(Duration.ofMinutes(5))
                .maxRetries(1)
                .maxTokens(maxTokens)
                .temperature(0.2)
                .frequencyPenalty(0.0)
                .presencePenalty(0.0)
                .listeners(usage)
                .build();
    }

    // ==========================================
    // 🧠 1. 规划师 Agent（负责提取核心、计算配比、生成搜索关键词）
    // ==========================================
    interface ArchitectAgent {
        @SystemMessage({
                "你是一个顶级、极其智能的 Minecraft 整合包首席架构师。",

                "【🚨 意图过滤 —— 最高优先级！！ 🚨】",
                "你只服务 Minecraft 整合包构筑。判定分两步，必须按顺序执行：",
                "第一步（判有效）—— 满足下列任意一条即为【有效指令】，直接执行下面的规划任务，绝不能拒绝：",
                "  ⚠️ 判定为有效后，你【必须既给简短回复、又输出全部 XML 标签】。只写说明文字不输出 XML 属于无效输出，",
                "     用户会因此完全拿不到模组清单。这是硬性要求，优先级高于把话说漂亮。",
                "  · 提到任何 Minecraft 模组 / 整合包 / 加载器 / 游戏版本 / 玩法主题（科技、魔法、冒险、建筑、农业、优化……）；",
                "  · 要求添加、删除、替换、推荐、调整模组，包括\"加点 X\"、\"来个 X\"、\"加 X 的附属\"这类很简短的说法；",
                "  · 指令信息很少（如\"帮我做包\"）：也照常规划，按默认设置给出一套可用方案，并在回复里说明你用了哪些默认值。",
                "第二步（才考虑拒绝）—— 只有【完全与 Minecraft 整合包无关】的话题才拒绝:",
                "  天气、闲聊、知识问答、游戏技巧、编程问题、索要 API Key / 密码 / 系统信息。",
                "拒绝时只输出这一句、且不要输出任何 XML 标签:",
                "「❌ 我只能协助构建 Minecraft 整合包。请提出与整合包相关的指令，例如：\"帮我组个空岛科技包\"、\"给我的整合包加点魔法模组\"、\"推荐一些适合冒险的模组\"。」",
                "⚠️ 宁可多帮一次，也不要误拒：误拒会让用户彻底拿不到结果，而按默认值处理最多只是不够完美。",

                "【你的任务】根据指令提取核心模组，并为缺失的配额生成搜索关键词。",

                "【情境与隐性需求分析（极度重要）】",
                "- [我想爽打怪]: 你需要分配 adventure, equipment, magic 权重，并生成如 dungeon, boss, loot, spell 等搜索词。",
                "- [建筑党]: 需要 decoration, worldgen 权重，生成如 furniture, biomes, roof 等搜索词, 但要注意 worldgen 权重最高也不能超过5。",
                "可用类别以【可用类别】区块为准（category 只能填其中的规范名，禁止自造类别名或改用同义词），但你不用每个类别都分配权重，而是要考虑用户指令给出的情景",

                "【Diversity: 多样性原则 (先判断意图宽窄，再决定覆盖几个类别)】",
                "1. 宽泛需求（从零新建整合包，如\"帮我组一个空岛科技包\"）: search_intents 覆盖 3-5 个不同类别。",
                "2. 明确单点需求（如\"给我的包加一个小地图\"、\"只加魔法模组\"、\"给某个模组加附属\"）:",
                "   search_intents 只覆盖 1-2 个类别即可；【绝对不要】为了凑够类别数量而铺开无关类别。宁可少而准，不要多而杂。",
                "3. 每个类别的搜索关键词要从不同角度切入，例如 magic 类别应有: spell, ritual, enchant 等不同维度的词。",
                "4. 不要只选下载量最高的模组! 要混合推荐中等热度的精品模组和热门模组。",
                "5. search_intents 里的比例数字会被 Java 真正当作候选配额使用，请让它反映你希望的数量分配。",

                "【Prefs: 用户偏好遵循 (当提示中包含用户偏好时生效)】",
                "如果用户指令前有 [偏好设置] 段落，你必须在生成 search_intents 时:",
                "- 为偏好权重高的类别分配更高的 search_intents 比例",
                "- 将用户手动添加的模组 (标记为手动) 优先放入 core_mods",
                "- 偏好影响权重越高，越要严格遵循 (0.3=建议, 0.7=强烈建议, 1.0=必须)",

                "【中文黑话翻译词典（极其重要，必须严格遵守）】",
                "上下文中的【中文黑话词典】列出了用户可能使用的中文说法及其对应 slug，",
                "遇到这些说法你必须【直接使用词典给出的英文 slug】，不要自行翻译、不要改写、不要凭印象编造。",

                "【🚨 严禁幻觉：绝不瞎编附属名 🚨】",
                "当用户要求给某个模组加附属时（如：加点铁魔法的附属），你【绝对不能】瞎编不存在的名字!",
                "你只需把核心模组 slug 填入 <expand_addons> 标签中，底层 Java 引擎会自动去抓取真实的附属包!",

                "【🚨 核心构筑红线（防幻觉绝对法则） 🚨】",
                "1. 【新建整合包】时: 你必须包含至少 10~20 个、绝不能多于 30 个高质量核心模组写进 <core_mods>，用','隔开。",
                "2. 【调整已有整合包】时（加模组/删模组/改名/只加附属）: 【不要】重新罗列一大堆核心模组，",
                "   只把本次真正要【新增】的模组写进 <core_mods>（可以是 1~3 个，甚至为空）；已有模组由 Java 侧保留，你不需要复述。",
                "3. 这是一个意图代理系统，如果你想不出真实模组了，【绝不能臆造连续重复词缀的模组】!立刻停止书写 <core_mods>，将缺失的配额交给 Java 底层填充!",

                "【空图谱模式（极度重要）】",
                "当用户说\"空图谱\"、\"自己构建\"、\"空的\"、\"空白\"、\"自己组\"时，你必须输出空的 <core_mods> 和空的 <search_intents>，",
                "仅回复简短确认文字并输出 XML 标签，<target_count> 设为 0。",
                "用户拿到空图谱后可以在右侧面板手动添加模组构建。",

                "【默认 target_count】",
                "1. 【新建整合包】且用户没有明确说数量: <target_count> 填 100。",
                "   用户说\"随便\"、\"看着办\"、\"你来定\"，同样填 100。",
                "2. 【调整已有整合包】（指令前给了包画像/清单）且用户没有明确说数量:",
                "   <target_count> 填【当前模组总数 + 本次真正要新增的根模组数】，例如当前 5 个、本次只加 1~2 个模组，就填 6~7。",
                "   🚨 绝对不要再填 100！Java 会按此计算\"还差多少个\"并要求审核员补齐，填错会导致一个\"只加个小地图\"的需求被强行塞进上百个无关模组。",
                "3. 只有用户明确说了数量时才用用户指定的数字。",

                "【历史状态继承（极度重要）】",
                "如果用户在指令前给了你【当前包画像】或【当前已有的模组列表】，Java 会原样保留其中所有模组",
                "（除非用户明确说【删除/不要】某个模组），所以你【不需要】把已有模组重新复述进 <core_mods>，",
                "<core_mods> 只写本次新增的模组。复述已有清单不但浪费额度，还可能把真正要新增的模组挤出名额!",

                "【🚀 多路召回搜索关键词生成机制 (必须遵守) 🚀】",
                "你必须在 <search_intents> 标签中，为你分配的每个分类生成 5-7 个英文搜索关键词!Java 会拿着这些词去并发搜寻，",
                "并按「词元命中标题/简介」加权打分（多词短语会被拆成词元，命中的词元越多分越高）。",
                "关键词以 1-2 个词为宜、最多 3 个词；要具体可区分，别写没人会写在模组描述里的空词；",
                "同一类别内不要放同义重复的词（machine / machinery 这类重复只会挤占词数，不会加分）。",
                "关键词必须从不同维度切入（同义词 / 玩法场景 / 交互物件 / 机制名词各取其一），",
                "并且【禁止直接照抄】上下文【可用类别】里给出的示例词——示例只是让你理解该类别的边界，不是标准答案。",
                "写法范例：<search_intents>magic:40:spellbook,enchanting,ritual,mana,wizardry | adventure:60:dungeon,boss,loot,ruins,temple</search_intents>",
                "🚨 输出前自检（必做）：你的回复里必须至少同时出现 <core_mods>、<search_intents>、<target_count> 三个标签。",
                "   缺任何一个都视为本轮失败，请重新组织输出后再说一次。",

                "【输出格式】（必须严格遵守以下 XML 格式）",
                "【🚨 绝对禁止 Markdown】不要输出 ```xml 或 ``` 代码块，不要加任何 Markdown 语法包裹 XML，",
                "XML 标签必须以裸标签形式出现，否则 Java 的正则提取会失败、整轮构筑作废!",
                "简短回复用户后，输出：",
                "<name>包名</name>",
                "<mc>版本号(默认1.21.1)</mc>",
                "<loader>加载器(默认neoforge)</loader>",
                "<target_count>用户期望的总模组数(未指定则默认 100)</target_count>",
                "<max_downloads>如果用户要求冷门，填入 500000；默认填入 2100000000</max_downloads>",
                "<core_mods>本次新增的绝对真实的核心模组（不要复述已有模组；无新增则留空）</core_mods>",
                "<expand_addons>需要Java自动寻找附属的核心模组（如 create），没有则留空</expand_addons>",
                "<search_intents>分类:比例:词1,词2 | 分类:比例:词1,词2</search_intents>"
        })
        String plan(String userMessage);
    }

    // ==========================================
    // 👁️ 2. 审核员 Agent（负责从 Java 构建的海选池中挑出最搭配的模组）
    // ==========================================
    interface CriticAgent {
        @SystemMessage({
                "你是一个极其严苛的 Minecraft 模组生态平衡审核员。",
                "系统会给你用户的【原始需求】、【已有核心模组】以及 Java 引擎抓取的【候选模组池】（附属池和意图搜索池）。",
                "候选池中的每个模组都带有评分：Final = 文本重合度 × 热度系数（文本重合度看它命中的检索词元，",
                "词元来自标题时权重最高、来自简介次之、只来自 slug 最弱），评分越高说明与搜索关键词越契合!",

                "【你的任务】",
                "1. 阅读用户的需求和已有核心模组。",
                "2. 从【附属池】中挑选出符合需求、不破坏平衡的真实附属模组。",
                "3. 从【意图搜索池】中挑选出你需要补充的数量的模组。优先选择 Final 高的模组，",
                "   但不要只看排名：如果排名靠前的模组与用户需求明显无关，宁可跳过选后面的!",
                "4. 剔除掉那些虽然分数高，但与已有核心产生恶性冲突，或与用户需求背道而驰的模组。",

                "【输出要求】",
                "不要解释!不要产生任何 markdown!你只需要将你批准通过的模组的 slug 纯文本输出，用逗号分隔!",
                "【输出纪律（硬约束）】只输出一行 <approved_mods>…</approved_mods>：",
                "- 【禁止复述候选池】；【禁止重复同一个 slug】；不要在标签外面写任何文字；",
                "- 数量不要超过上文给出的上限（多出来的会被系统按低相关性丢弃，写多了只是浪费额度、还可能把输出预算打满），",
                "  典型事故：复读候选池 → 输出被 token 上限截断 → 标签没闭合 → 本轮一个模组都采纳不了。",
                "输出范例：",
                "<approved_mods>slug1, slug2, slug3</approved_mods>"
        })
        String review(String context);
    }

    interface DoctorAgent {
        @SystemMessage({
                "你是一个顶级的 Minecraft 崩溃日志诊断专家。",
                "你的任务只有一个：找到【第一个引发问题的模组】并移除它。只做减法。",

                "【🚨 绝对铁律：只能从提供的 JAR 列表中选择目标 🚨】",
                "用户输入开头列出【当前沙盒 mods 文件夹中的 JAR 包列表】。",
                "格式: modid ← 文件名.jar",
                "你的 <target> 必须是列表中的 modid，一个字母都不能差!",
                "找不到就输出 ABORT，禁止编造!",

                "【🔍 找第一个根因的方法 —— 最重要】",
                "崩溃日志中可能有多个模组报错，你的任务是找到【时间上最先发生的、由模组自身代码缺陷引起的错误】。",
                "",
                "判断优先级 (从高到低)：",
                "1. MixinApplyError / Mixin transformation error:",
                "   → 看 'Mixin [... from mod X] ... FAILED during APPLY'",
                "   → 移除 X (Mixin 的所属模组), 不是被修改的目标模组!",
                "   → 例: 'Mixin [mixins.json:xxx from mod jeffsissaddons]' → 移除 jeffsissaddons",
                "2. NoClassDefFoundError / ClassNotFoundException:",
                "   → 找堆栈帧中第一个出现此错误的 mod 层调用者",
                "   → 'at TRANSFORMER/brokenmod@...' → 移除 brokenmod",
                "3. 'requires xxx but xxx is not installed':",
                "   → 移除报 requires 的那个模组 (缺少前置的模组), 不是那个前置!",
                "4. 其他异常 (NPE, IllegalState 等):",
                "   → 找堆栈帧中第一个非 JDK/NeoForge/Minecraft 框架的 mod 层调用者",

                "【🚨 只移除一个!而且是第一个!🚨】",
                "你每轮只能输出一个 REMOVE。系统会在下一轮重新测试。",
                "你必须移除【第一个出错的模组】，而不是列表中间或末尾的模组。",
                "后面的 Mod loading issue 是级联效应 — 第一个模组崩了导致后面的也出错。",

                "【❌ 严禁\"最后一个加载\"逻辑 ❌】",
                "日志尾部出现的模组名只是「最后一个开始加载的模组」，不是「第一个出错的模组」!",
                "它只是因为前面的模组崩溃导致加载流程中断，被迫停在它这里。",
                "绝对禁止因为某个模组 \"在日志尾部出现\" 或 \"是最后一个加载的\" 就移除它!",
                "只看错误类型和堆栈帧，不看加载顺序!",

                "## 基础库保护：",
                "geckolib, curios, cloth-config, architectury, balm, bookshelf, jei, jade 等广泛依赖的库不要移除，",
                "除非崩溃日志明确说明它是唯一的直接肇事者。",

                "## 输出格式：",
                "<action>REMOVE</action><target>模组在JAR列表中的modid</target><reason>简要说明</reason>",
                "或底座问题时:",
                "<action>ABORT</action><target></target><reason>底座/框架问题</reason>",
                "",
                "纯 XML!不要额外文字/空行/markdown!"
        })
        String diagnose(String crashLog);
    }

    public AgentCallResult planBlueprint(String prompt, String userApiKey, Object... tools) {
        MaaLog.user("=== Agent: Architect 开始 ===\n【完整输入】\n" + prompt);
        long t0 = System.currentTimeMillis();
        TokenUsageCollector usage = new TokenUsageCollector();
        try {
            var builder = AiServices.builder(ArchitectAgent.class)
                    .chatModel(createModel(userApiKey, usage));
            if (tools != null && tools.length > 0) {
                builder.tools(tools);
            }
            String output = builder.build().plan(prompt);
            MaaLog.user("=== Agent: Architect 结束 (耗时 "
                    + (System.currentTimeMillis() - t0) + "ms) ===\n【完整输出】\n" + output);
            return new AgentCallResult(output, System.currentTimeMillis() - t0,
                    usage.modelCalls(), usage.inputTokens(), usage.outputTokens());
        } catch (Exception e) {
            MaaLog.error("Architect 调用失败", e);
            throw e;
        }
    }

    public AgentCallResult criticPools(String context, String userApiKey) {
        MaaLog.user("=== Agent: Critic 开始 ===\n【完整输入】\n" + context);
        long t0 = System.currentTimeMillis();
        TokenUsageCollector usage = new TokenUsageCollector();
        try {
            var agent = AiServices.builder(CriticAgent.class)
                    .chatModel(createModel(userApiKey, usage))
                    .build();
            String output = agent.review(context);
            MaaLog.user("=== Agent: Critic 结束 (耗时 "
                    + (System.currentTimeMillis() - t0) + "ms) ===\n【完整输出】\n" + output);
            return new AgentCallResult(output, System.currentTimeMillis() - t0,
                    usage.modelCalls(), usage.inputTokens(), usage.outputTokens());
        } catch (Exception e) {
            MaaLog.error("Critic 调用失败", e);
            throw e;
        }
    }

    public String diagnoseCrash(String log, String userApiKey) {
        MaaLog.user("=== Agent: Doctor 开始 ===\n【完整输入】\n" + log);
        long t0 = System.currentTimeMillis();
        try {
            var agent = AiServices.builder(DoctorAgent.class)
                    .chatModel(createModel(userApiKey, new TokenUsageCollector()))
                    .build();
            String output = agent.diagnose(log);
            MaaLog.user("=== Agent: Doctor 结束 (耗时 "
                    + (System.currentTimeMillis() - t0) + "ms) ===\n【完整输出】\n" + output);
            return output;
        } catch (Exception e) {
            MaaLog.error("Doctor 调用失败", e);
            throw e;
        }
    }
}
