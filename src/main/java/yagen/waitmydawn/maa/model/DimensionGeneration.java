package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import java.util.regex.Pattern;

/** 维度整体改造与局部内容分别核对；搜索命中和模型用途说明不授予生成覆盖。 */
public final class DimensionGeneration {
    private DimensionGeneration() {}
    public record Goal(String dimension, String label, String query) {}
    public record Proof(String dimension, String slug, String quote) {}
    public record Coverage(String dimension, String label, List<String> projects) {
        public Coverage { projects = List.copyOf(projects); }
    }
    private static Pattern pattern(String value) { return Pattern.compile(value, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
    private static final Map<String, String> REALMS = Map.of(
            "nether", "下界|\\bnether\\b", "end", "末地|\\bend\\b(?![- ](?:game|of|user))",
            "overworld", "主世界|\\boverworld\\b");
    private static final Map<String, String> LABELS = Map.of("nether", "下界", "end", "末地", "overworld", "主世界");
    private static final Pattern BROAD = pattern("丰富|生态|重塑|大幅|大改|大规模|大量|众多|整体|全面|改造|扩展|地貌|地形|世界生成|overhaul|revamp|enrich|ecology|terrain|worldgen|world generation|many|dozens|hundreds");
    private static final Pattern LOCAL = pattern("只(?:加|要|添加|丰富|改).{0,12}(?:生物|结构|建筑|地牢|群系)|仅.{0,12}(?:生物|结构|建筑|地牢|群系)|(?:only|just)\\b.{0,30}(?:mobs?|creatures?|structures?|dungeons?|biomes?)");
    private static final Pattern SPECIFIC = pattern("生物|动物|怪物|结构|建筑|地牢|群系|\\b(?:mobs?|creatures?|animals?|structures?|dungeons?|biomes?)\\b");
    private static final Pattern OTHER_FEATURE = pattern("界面|音效|音乐|配方|掉落|性能|修复|\\b(?:ui|hud|music|sounds?|recipes?|performance|fixes)\\b");
    private static final Pattern LARGE = pattern("大幅|大改|整体|全面|重塑|大规模|大量|众多|overhaul|revamp|massive|major|many|dozens|hundreds");
    private static final Pattern NO_TERRAIN = pattern("(?:不(?!断|仅|但|只)|(?<!分)别|无需|禁止).{0,8}(?:改|动|重塑|替换).{0,8}(?:地形|地貌|生成)|保持.{0,8}(?:原版|原有).{0,8}(?:地形|生成)|(?:without|do not|don't|no)\\b.{0,25}(?:chang|alter|overhaul|terrain generation)|vanilla terrain");
    private static final Pattern EXTERNAL = pattern("compatible with|compatibility|integration|support for|unlike|\\bnot\\b|\\bno\\b|doesn't|without|不添加|不改变|不改|不是|并非|不会|没有|不重塑|不影响|兼容|联动");
    private static final Pattern SHAPE = pattern("terrain|landscape|worldgen|world generation|(?:nether|end|overworld)\\s+generation|地形|地貌|世界生成|biomes?|群系|structures?|结构");
    private static final Pattern MAJOR = pattern("overhaul\\w*|revamp\\w*|reshape\\w*|transform\\w*|custom terrain|new terrain generation|brand new terrain|大幅|重塑|重做|全面改造|(?:[2-9]\\d+|1\\d{2,})\\+?\\s+(?:(?:brand new|new|unique)\\s+)*(?:[\\p{L}-]+\\s+)?(?:biomes|structures)|(?:many|dozens of|hundreds of|plethora of)[^.!?\\n]{0,30}(?:biomes|structures)|大量.{0,10}(?:群系|结构)");
    private static final Pattern HEIGHT_ONLY = pattern("amplified terrain|doubled height|(?:build|logical|world) height|加高|高度翻倍");
    private static final Pattern RICH_CONTENT = pattern("biomes?|structures?|landscapes?|群系|结构|地貌");

    private static Set<String> realms(String text) {
        var result = new LinkedHashSet<String>();
        REALMS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            if (pattern(e.getValue()).matcher(text).find()) result.add(e.getKey());
        });
        // 自定义维度仅接受原文明示的名称，不从项目 slug 猜测维度身份。
        var custom = pattern("([\\p{L}][\\p{L}\\p{N}_-]{1,24})维度").matcher(text);
        while (custom.find()) {
            String name = custom.group(1).replaceFirst("^(?:丰富|重塑|改造|扩展|大幅|整体|全面|大改)+", "");
            if (!name.isBlank() && !LABELS.containsValue(name) && !Set.of("这个", "对应", "所有", "各个", "多个", "不同").contains(name)) result.add(name.toLowerCase(Locale.ROOT));
        }
        var named = pattern("\\b([a-z][a-z0-9_-]{1,24})\\s+dimension\\b").matcher(text);
        while (named.find()) {
            String name = named.group(1).toLowerCase(Locale.ROOT);
            if (!Set.of("the", "this", "each", "every", "any", "alien", "custom", "new", "another").contains(name)) result.add(name);
        }
        return result;
    }
    private static Goal goal(String realm) {
        return new Goal(realm, LABELS.getOrDefault(realm, realm + "维度"), realm + " terrain overhaul");
    }
    private static boolean blocks(String text, String realm) {
        for (String clause : text.split("[，,；;。\\n]|但|而|\\bbut\\b")) {
            var scopes = realms(clause);
            if ((scopes.isEmpty() || scopes.contains(realm)) && (NO_TERRAIN.matcher(clause).find() || LOCAL.matcher(clause).find())) return true;
        }
        return false;
    }
    private static List<Goal> goals(BuildRequirement target, String parent, String restrictions) {
        if (Set.of(BuildRequirement.Kind.PROJECTS, BuildRequirement.Kind.CHANGE, BuildRequirement.Kind.ADDONS,
                BuildRequirement.Kind.PACK_INTEGRATIONS).contains(target.kind())) return List.of();
        String source = target.source();
        if (OTHER_FEATURE.matcher(source).find()) return List.of();
        boolean plannedGeneration = target.searches().stream().flatMap(s -> s.keywords().stream())
                .anyMatch(k -> pattern("terrain|overhaul|worldgen|world generation").matcher(k).find());
        if (!BROAD.matcher(source).find() && !pattern("生态|enrich|ecology|丰富").matcher(parent).find() && !plannedGeneration) return List.of();
        if (SPECIFIC.matcher(source).find() && !LARGE.matcher(source).find()
                && !pattern("地形|地貌|世界生成|terrain|worldgen|world generation").matcher(source).find()) return List.of();
        return realms(source).stream().filter(r -> !blocks(parent, r) && !blocks(restrictions, r)).map(r -> {
            var base = goal(r);
            if (!pattern("地形|地貌|世界生成|terrain|landscape|worldgen|world generation").matcher(source).find()) {
                if (pattern("结构|建筑|structures?").matcher(source).find()) return new Goal(r, base.label(), r + " large scale structures");
                if (pattern("群系|biomes?").matcher(source).find()) return new Goal(r, base.label(), r + " biome expansion");
            }
            return base;
        }).toList();
    }
    public static Map<String, List<Goal>> targets(List<BuildRequirement> requirements) {
        String restrictions = String.join("\n", requirements.stream().map(BuildRequirement::source).toList());
        var result = new LinkedHashMap<String, List<Goal>>();
        for (var parent : requirements) {
            if (parent.criteria().isEmpty()) {
                var goals = goals(parent, parent.source(), restrictions);
                if (!goals.isEmpty()) result.put(parent.id(), goals);
            } else for (var criterion : parent.criteria()) {
                var target = criterion.target(parent);
                var goals = goals(target, parent.source(), restrictions);
                if (!goals.isEmpty()) result.put(target.id(), goals);
            }
        }
        return Collections.unmodifiableMap(result);
    }
    public static List<Goal> goals(BuildRequirement target) { return goals(target, target.source(), target.source()); }
    public static List<Goal> describedGoals(String text) { return realms(text).stream().map(DimensionGeneration::goal).toList(); }
    public static boolean supports(Goal goal, String quote) {
        return !evidence(goal, quote).isEmpty();
    }
    public static String evidence(Goal goal, String quote) {
        if (quote == null || !WorldGenerationPolicy.findings(quote).isEmpty()) return "";
        for (String sentence : quote.split("[.!?。！？;；\\n]+|[,，](?=\\s*(?i:add|introduc|overhaul|revamp|reshape|transform|新增|添加|增加|重塑))|\\s+(?i:and|while|but|whereas)\\s+(?=(?i:add|introduc|overhaul|revamp|reshape|transform|featur|includ))|并且|但是|并(?=新增|添加|增加|提供|改造|重塑)|同时")) {
            if (!richSentence(sentence) || !realmMention(goal.dimension(), sentence)) continue;
            String primary = sentence.split("(?i)\\b(?:featuring|including|with)\\b", 2)[0];
            if (!realms(primary).isEmpty() && !realmMention(goal.dimension(), primary)) continue;
            // 群系数量不能分摊为每个被提及维度的大规模改造；局部建筑重制也不是维度生成。
            if (realms(sentence).size() > 1 && !pattern("(?:overhaul|revamp|reshape|transform)\\w*|(?:each|both|every)\\b|分别|各个|每个").matcher(sentence).find()) continue;
            return sentence.trim();
        }
        return "";
    }
    /** 总体丰富度用于候选准入；跨维度总数仍不能证明各维度分别达到核心覆盖。 */
    public static String richEvidence(String facts) {
        if (facts == null || !WorldGenerationPolicy.findings(facts).isEmpty()) return "";
        for (String sentence : facts.split("[.!?。！？;；\\n]+")) if (richSentence(sentence)) return sentence.trim();
        return "";
    }
    private static boolean richSentence(String sentence) {
        boolean wholeRealm = pattern("(?:overhauls?|overhauling|revamps?|revamping|reshapes?|reshaping|transforms?|transforming)\\s+(?:(?:the|boring|vanilla)\\s+){0,4}(?:nether|end|overworld)\\b").matcher(sentence).find();
        if (sentence.length() > 1000 || EXTERNAL.matcher(sentence).find() || (!SHAPE.matcher(sentence).find() && !wholeRealm)
                || !MAJOR.matcher(sentence).find() || !WorldGenerationPolicy.findings(sentence).isEmpty()) return false;
        if (HEIGHT_ONLY.matcher(sentence).find() && !RICH_CONTENT.matcher(sentence).find()) return false;
        if (pattern("structures?|结构").matcher(sentence).find()
                && !pattern("biomes?|terrain|landscape|群系|地形|地貌").matcher(sentence).find()
                && !pattern("throughout|across|every biome|all biomes|成体系|遍布|各地|大规模").matcher(sentence).find()) return false;
        if (pattern("\\bmain\\s+(?:end\\s+)?island\\b|主岛|中央岛").matcher(sentence).find()) return false;
        return !pattern("(?:overhaul\\w*|revamp\\w*)\\s+(?:(?:the|vanilla|nether|end)\\s+){0,3}(?:fortress(?:es)?|structures?|dungeons?|villages?|towers?)").matcher(sentence).find()
                || pattern("(?:[2-9]\\d+|1\\d{2,})\\+?\\s+(?:new\\s+)?structures|hundreds of|大规模|大量").matcher(sentence).find();
    }
    private static boolean realmMention(String realm, String text) {
        String name = realm.matches("[a-z0-9_-]+") ? "\\b" + Pattern.quote(realm) + "\\b" : Pattern.quote(realm);
        return pattern(REALMS.getOrDefault(realm, name)).matcher(text).find();
    }
    public static List<Proof> proofs(List<Goal> goals, List<RequirementReview.Contribution> accepted) {
        return goals.stream().flatMap(g -> accepted.stream().filter(e -> supports(g, e.quote()))
                .map(e -> new Proof(g.dimension(), e.slug(), e.quote()))).distinct().toList();
    }
    public static List<Coverage> coverage(List<Goal> goals, List<Proof> proofs, Set<String> retained) {
        return goals.stream().map(g -> new Coverage(g.dimension(), g.label(), proofs.stream()
                .filter(p -> p.dimension().equals(g.dimension()) && retained.contains(p.slug())).map(Proof::slug).distinct().sorted().toList())).toList();
    }
}
