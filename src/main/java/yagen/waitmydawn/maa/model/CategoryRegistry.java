package yagen.waitmydawn.maa.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模组类别唯一权威源（19 类）。
 *
 * <p>此前类别清单散落在三处，改一处必漏两处，而且 LLM 幻觉出的类别名会被静默当成"全库检索"：
 * <ol>
 *   <li>Architect 提示词里的自然语言枚举</li>
 *   <li>{@code ModrinthCacheService.CATEGORIES}（校验用）</li>
 *   <li>前端 {@code availableCategories}（抽卡面板用）</li>
 * </ol>
 * 现在统一到这里，三者都改为引用本类：后端校验用 {@link #normalize(String)}，
 * 提示词与前端分别取 {@link #promptBlock()} 与 {@link #CATEGORIES}。
 *
 * <p>约定：未知类别一律返回 {@code null}，调用方必须丢弃并上报，绝不回退成全库检索。
 */
public final class CategoryRegistry {

    private CategoryRegistry() {
    }

    /** 19 个规范类别，顺序即前端展示顺序（与 Modrinth API 的 category slug 完全一致） */
    public static final List<String> CATEGORIES = List.of(
            "adventure", "cursed", "decoration", "economy", "equipment", "food",
            "game-mechanics", "library", "magic", "management", "minigame", "mobs",
            "optimization", "social", "storage", "technology", "transportation",
            "utility", "worldgen");

    /** 规范类别集合（O(1) 校验） */
    public static final Set<String> CATEGORY_SET = Set.copyOf(CATEGORIES);

    /**
     * 常见别名 → 规范名（键必须小写）。
     *
     * <p>只收录大模型确实会写出来的口语/变体写法，宁可让冷门别名走"非法并上报"，
     * 也不要为了兜底而扩大语义范围（例如不把 "building" 之外的大量建筑词都塞进来）。
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("tech", "technology"),
            Map.entry("technic", "technology"),
            Map.entry("automation", "technology"),
            Map.entry("combat", "adventure"),
            Map.entry("fighting", "adventure"),
            Map.entry("exploration", "adventure"),
            Map.entry("dungeons", "adventure"),
            Map.entry("spells", "magic"),
            Map.entry("magical", "magic"),
            Map.entry("building", "decoration"),
            Map.entry("furniture", "decoration"),
            Map.entry("world-gen", "worldgen"),
            Map.entry("world-generation", "worldgen"),
            Map.entry("terrain", "worldgen"),
            Map.entry("gameplay", "game-mechanics"),
            Map.entry("gamemechanics", "game-mechanics"),
            Map.entry("mechanics", "game-mechanics"),
            Map.entry("lib", "library"),
            Map.entry("libraries", "library"),
            Map.entry("optimisation", "optimization"),
            Map.entry("performance", "optimization"),
            Map.entry("perf", "optimization"),
            Map.entry("qol", "utility"),
            Map.entry("quality-of-life", "utility"),
            Map.entry("tools", "utility"),
            Map.entry("storage-system", "storage"),
            Map.entry("logistics", "storage"),
            Map.entry("gear", "equipment"),
            Map.entry("weapons", "equipment"),
            Map.entry("creatures", "mobs"),
            Map.entry("monsters", "mobs"),
            Map.entry("trade", "economy"),
            Map.entry("shop", "economy"),
            Map.entry("transport", "transportation"),
            Map.entry("vehicles", "transportation"),
            Map.entry("admin", "management"),
            Map.entry("permissions", "management"),
            Map.entry("mini-game", "minigame"),
            Map.entry("mini-games", "minigame"),
            Map.entry("cursed-mods", "cursed"));

    /** 中文释义：注入对话上下文，帮助大模型把口语需求落到正确类别 */
    private static final Map<String, String> GLOSS = buildGloss();

    /**
     * 每类推荐英文检索词。
     *
     * <p>两个用途：① {@link #promptBlock()} 里取前 {@value #PROMPT_HINT_LIMIT} 个当"类别边界示例"；
     * ② Tool 调整了某类别但 XML 里没给该组意图时（{@code mergeCategoryTargets}），用整份列表兜底召回。
     *
     * <p>从每类 3 个扩到 10 个的原因：旧列表就是提示词里给的示例，模型几乎照抄，等于整个召回只有
     * 3 个词在撑；且本地检索是"命中标题/简介里的词元"口径，同义写法（spell / enchanting / wizardry）
     * 不写进列表就永远捞不到。多词短语是允许的（会被拆成词元、要求全部命中）。
     */
    private static final Map<String, List<String>> HINTS = buildHints();

    /** promptBlock 里每类展示几个示例词（不是全部，避免模型照抄整份列表） */
    private static final int PROMPT_HINT_LIMIT = 5;

    /**
     * 归一化为规范类别名。
     *
     * @return 规范名；{@code null} 表示这不是合法类别（调用方必须丢弃并上报，禁止回退全库检索）
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        if (CATEGORY_SET.contains(t)) return t;
        return ALIASES.get(t);
    }

    public static boolean isValid(String raw) {
        return normalize(raw) != null;
    }

    public static String glossOf(String category) {
        return GLOSS.getOrDefault(category, "");
    }

    public static List<String> hintsOf(String category) {
        return HINTS.getOrDefault(category, List.of());
    }

    /**
     * 生成注入对话上下文的【可用类别】区块。
     *
     * <p>把"只能使用规范名"和中文释义放在一起，既是约束也是提示：
     * 用户说"加个能造机器的"要能落到 technology，而不是自造一个 machines 类别。
     */
    public static String promptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("【可用类别】（search_intents 的 category 字段必须严格使用下列规范名，禁止自造类别或使用同义词）\n");
        sb.append("（每类后面括起来的是【示例检索词】，只是用来划定该类别的边界，不是标准答案；")
                .append("你必须自己补充不同角度的词，禁止照抄）\n");
        for (String c : CATEGORIES) {
            sb.append("- ").append(c).append("：").append(glossOf(c))
                    .append("（示例检索词: ").append(exampleHints(c)).append("）\n");
        }
        return sb.toString();
    }

    /** promptBlock 用的示例词（前 {@value #PROMPT_HINT_LIMIT} 个） */
    private static String exampleHints(String category) {
        List<String> hints = hintsOf(category);
        return String.join(", ", hints.subList(0, Math.min(PROMPT_HINT_LIMIT, hints.size())));
    }

    private static Map<String, String> buildGloss() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("adventure", "冒险/地牢/战斗/探索");
        m.put("cursed", "整活/诅咒/混乱");
        m.put("decoration", "建筑装饰/家具/灯具");
        m.put("economy", "经济/商店/货币");
        m.put("equipment", "装备/武器/工具/饰品");
        m.put("food", "食物/农业/烹饪");
        m.put("game-mechanics", "玩法机制/难度/进度");
        m.put("library", "前置库/框架/API");
        m.put("magic", "魔法/法术/仪式");
        m.put("management", "管理/权限/领地");
        m.put("minigame", "小游戏/派对玩法");
        m.put("mobs", "生物/怪物/刷怪");
        m.put("optimization", "性能优化/帧数/内存");
        m.put("social", "社交/聊天/多人");
        m.put("storage", "存储/物流/自动化容器");
        m.put("technology", "科技/机器/自动化/能源");
        m.put("transportation", "交通/载具/道路");
        m.put("utility", "实用工具/信息显示");
        m.put("worldgen", "世界生成/地形/群系/结构");
        return Map.copyOf(m);
    }

    private static Map<String, List<String>> buildHints() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("adventure", List.of("dungeon", "boss", "loot",
                "ruins", "temple", "quest", "raid", "treasure", "bandit", "exploration"));
        m.put("cursed", List.of("cursed", "chaos", "funny",
                "meme", "joke", "weird", "silly", "random", "absurd", "cursed image"));
        m.put("decoration", List.of("furniture", "building", "roof",
                "decor", "lamp", "chair", "table", "window", "painting", "fence"));
        m.put("economy", List.of("shop", "currency", "trade",
                "villager", "market", "coin", "bank", "price", "auction", "money"));
        m.put("equipment", List.of("weapon", "armor", "tool",
                "sword", "bow", "shield", "accessory", "artifact", "backpack", "trinket"));
        m.put("food", List.of("cooking", "farming", "crop",
                "food", "recipe", "kitchen", "meal", "fish", "drink", "harvest"));
        m.put("game-mechanics", List.of("mechanic", "difficulty", "progression",
                "skill", "leveling", "quest", "hardcore", "balance", "hunger", "respawn"));
        m.put("library", List.of("api", "core", "framework",
                "library", "dependency", "config", "runtime", "abstraction", "compatibility", "bridge"));
        m.put("magic", List.of("spellbook", "rituals", "mana",
                "spell", "enchanting", "wand", "rune", "wizard", "alchemy", "arcane"));
        m.put("management", List.of("permission", "claim", "admin",
                "region", "protection", "moderation", "grief", "ban", "sethome", "warp"));
        m.put("minigame", List.of("minigame", "party", "arena",
                "parkour", "puzzle", "trivia", "challenge", "scoreboard", "round", "lobby"));
        m.put("mobs", List.of("creature", "monster", "spawn",
                "mob", "animal", "hostile", "tame", "pet", "boss", "wildlife"));
        m.put("optimization", List.of("performance", "fps", "memory",
                "optimization", "render", "chunk", "lag", "culling", "smooth", "shader"));
        m.put("social", List.of("chat", "multiplayer", "voice",
                "emote", "friend", "mention", "proximity", "nickname", "emoji", "broadcast"));
        m.put("storage", List.of("barrel", "drawer", "logistics",
                "storage", "container", "crate", "chest", "network", "sorting", "item transport"));
        m.put("technology", List.of("machine", "automation", "energy",
                "power", "electricity", "factory", "generator", "pipe", "reactor", "industrial"));
        m.put("transportation", List.of("vehicle", "transport", "road",
                "car", "boat", "train", "plane", "elevator", "rail", "mount"));
        m.put("utility", List.of("qol", "tool", "information",
                "hud", "inventory", "minimap", "tooltip", "recipe viewer", "keybind", "overlay"));
        m.put("worldgen", List.of("biome", "terrain", "structure",
                "generation", "dimension", "cave", "island", "ore", "vegetation", "floating island"));
        return Map.copyOf(m);
    }
}
