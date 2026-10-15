package yagen.waitmydawn.maa.model;

import java.util.*;
import java.util.regex.*;

/** 描述证据驱动的组合确认；类别、项目名和下载量均不能独立证明功能重叠。 */
public final class ContentOverlap {
    private ContentOverlap() {}
    public record Project(String slug, String title, String description, String body, String iconUrl, long downloads) {}
    public record Candidate(String slug, String title, String description, String iconUrl, long downloads,
                            String relation, String evidence, String scope, String scopeEvidence, boolean protectedSelection, boolean recommended) {}
    public record Group(String original, String title, String explanation, List<Candidate> candidates, boolean verified) {}
    private record Rule(String id, String title, String explanation, Pattern pattern) {}
    private static Pattern pattern(String regex) { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
    private static final List<Rule> RULES = List.of(
        new Rule("real-animals", "现实生物生态", "这些模组都添加现实生物，可能出现重复生物或生态定位重叠；尚未证明具体物种重复。",
            pattern("现实(?:世界)?(?:的)?(?:生物|动物)|现实生活中的动物|real[- ](?:world|life)\\s+(?:animals|creatures)|realistic\\s+animals")),
        new Rule("major-worldgen", "大规模世界生成", "这些模组介绍体现较大范围的世界生成改动，组合可能存在兼容或风格风险。不同维度可以一起选择，这不是已确认冲突。",
            pattern("(?:overhaul\\w*|revamp\\w*|reshape\\w*)[^.!?\\n]{0,90}(?:world|terrain|nether|end\\b|overworld|generation)(?!\\s+(?:fortress(?:es)?|structures?|dungeons?|towers?|villages?|temples?|cities|ships?|ruins?))|(?:overhaul\\w*|revamp\\w*)[^.!?\\n]{0,35}all\\s+(?:vanilla\\s+)?biomes|(?:[2-9]\\d+|1\\d{2,})\\+?\\s+(?:(?:brand\\s+new|new|unique)\\s+)?biomes|(?:many|dozens of|hundreds of|plethora of|a slew of)[^.!?\\n]{0,30}biomes|expansive\\s+biome|transform\\w*\\s+(?:(?:the|boring|vanilla)\\s+){0,4}(?:end|nether|overworld|world|terrain)\\b|大量.{0,12}群系|大幅.{0,15}(?:世界生成|地形|下界|末地)|重塑.{0,15}(?:世界|下界|末地)")),
        new Rule("inventory-sort", "背包整理功能", "介绍均明确提供背包排序或整理功能，使用体验可能重复。名称中的 inventory 或类别标签本身不构成依据。",
            pattern("(?:inventory|inventories|chests?|containers?)[^.!?\\n]{0,35}(?:sort(?:ing)?|organiz(?:e|ing|ation))|(?:sort(?:ing)?|organiz(?:e|ing|ation))[^.!?\\n]{0,35}(?:inventory|inventories|chests?|containers?)|背包.{0,8}(?:整理|排序)|(?:整理|排序).{0,8}背包")),
        new Rule("recipe-viewer", "配方浏览功能", "介绍均明确提供物品配方查询或浏览功能，可能形成重复界面；可保留一个或确认一起使用。",
            pattern("(?:view|browse|look up|show|display|search)\\w*[^.!?\\n]{0,30}(?:item\\s+)?recipes|recipes?\\s+(?:viewer|browser)|(?:配方查询|配方浏览|查询配方|浏览配方)"))
    );
    private static final Pattern EXCLUDED_CONTEXT = pattern("compatible with|compatibility with|integration with|support for|unlike |does not |doesn't |without |not provided|不添加|不提供|不改变|兼容|联动|不包含");
    private static final Pattern LOGISTICS_SORT = pattern("network(?:ed)?\\s+pipes?|(?:item|transport)\\s+pipes?|logistics|item\\s+(?:routing|transport(?:ation)?)|routes?\\s+items\\s+between|自动物流|物流管道|物品路由|物品运输");
    private static final Pattern RECIPE_REFERENCE = pattern("(?:consider|recommend\\w*|suggest\\w*|requir\\w*|needs?|install\\w*|download\\w*)[^.!?\\n]{0,70}(?:recipes?[- ](?:viewer|browser)|(?:view|brows|show|look up)\\w*[^.!?\\n]{0,30}recipes)|(?:建议|推荐|需要|安装).{0,25}(?:配方查询|配方浏览|配方查看器)");
    private static final Pattern RECIPE_INTEGRATION = pattern("(?:requir\\w*|needs?|using|via)\\s+(?:\\*{1,2}|\\[)?(?:JEI|REI|EMI|Just Enough Items|Roughly Enough Items)\\b|(?:hid\\w*|hide\\w*|toggl\\w*|mov\\w*|reposition\\w*)[^.!?\\n]{0,35}recipes?[- ](?:viewer|browser)|(?:隐藏|移动|切换).{0,15}配方(?:查看器|浏览器)");
    private static final Pattern NETHER = pattern("\\bnether\\b|下界"), END = pattern("\\bend\\b|末地"), OVERWORLD = pattern("\\boverworld\\b|主世界");
    private static final Pattern WORLD_FEATURE = pattern("\\b(?:adds?|introduces?|overhauls?|overhauling|revamps?|revamping|reshapes?|reshaping|transforms?|transforming|explore|features?)\\b|with\\s+(?:over|more than)\\s+\\d+|expansive\\s+biome|new (?:and unique )?biomes|新增|添加|大幅|重塑");
    private static String evidence(Project project, Rule rule) {
        String text = project.description() + "\n" + project.body();
        if (text.length() > 16000) text = text.substring(0, 16000);
        for (String sentence : text.split("[.!?。！？\\n]+")) {
            if (sentence.length() > 600) continue;
            // 联动说明、对比说明或明确否定不作为自身新增功能的证明。
            if (EXCLUDED_CONTEXT.matcher(sentence).find()) continue;
            // 物流网络分拣不是玩家背包整理，即使介绍同时提及箱子和排序。
            if (rule.id().equals("inventory-sort") && LOGISTICS_SORT.matcher(sentence).find()) continue;
            // 安装建议或依赖说明中的查看器不构成当前项目自身的配方浏览功能。
            if (rule.id().equals("recipe-viewer") && (RECIPE_REFERENCE.matcher(sentence).find()
                    || RECIPE_INTEGRATION.matcher(sentence).find())) continue;
            if (rule.id().equals("major-worldgen") && !WORLD_FEATURE.matcher(sentence).find()) continue;
            var match = rule.pattern().matcher(sentence);
            if (match.find()) return sentence.trim();
        }
        return "";
    }
    private static String scope(String evidence) {
        var result = new ArrayList<String>();
        if (NETHER.matcher(evidence).find()) result.add("下界");
        if (END.matcher(evidence).find()) result.add("末地");
        if (OVERWORLD.matcher(evidence).find()) result.add("主世界");
        return result.isEmpty() ? "介绍未明确范围" : String.join(" / ", result);
    }
    private record Scope(String label, String evidence) {}
    private static Scope worldScope(Project project, String primary) {
        if (!scope(primary).equals("介绍未明确范围")) return new Scope(scope(primary), primary);
        String text = project.description() + "\n" + project.body();
        if (text.length() > 16000) text = text.substring(0, 16000);
        for (String sentence : text.split("[.!?。！？\\n]+")) {
            if (sentence.length() > 600 || EXCLUDED_CONTEXT.matcher(sentence).find() || !WORLD_FEATURE.matcher(sentence).find()) continue;
            if (pattern("biomes?|terrain|generation|worldgen|群系|地形|世界生成").matcher(sentence).find()
                    && !scope(sentence).equals("介绍未明确范围")) return new Scope(scope(sentence), sentence.trim());
        }
        return new Scope("介绍未明确范围", "");
    }
    public static List<Group> detect(List<Project> projects, Set<String> protectedSlugs) {
        var groups = new ArrayList<Group>();
        for (var rule : RULES) {
            var matches = new LinkedHashMap<String, String>();
            projects.stream().sorted(Comparator.comparingLong(Project::downloads).reversed().thenComparing(Project::slug))
                    .forEach(p -> { String quote = evidence(p, rule); if (!quote.isEmpty()) matches.put(p.slug(), quote); });
            if (matches.size() < 2) continue;
            var scopes = new HashMap<String, Scope>();
            projects.stream().filter(p -> matches.containsKey(p.slug())).forEach(p -> scopes.put(p.slug(),
                    rule.id().equals("major-worldgen") ? worldScope(p, matches.get(p.slug())) : new Scope("同类用途", "")));
            boolean scopeUnknown = scopes.values().stream().anyMatch(v -> v.label().equals("介绍未明确范围"));
            var recommendedScopes = new HashSet<String>();
            for (String slug : matches.keySet()) if (protectedSlugs.contains(slug))
                recommendedScopes.addAll(scopeUnknown ? Set.of("组合") : Set.of(scopes.get(slug).label().split(" / ")));
            var candidates = new ArrayList<Candidate>();
            for (var project : projects.stream().filter(p -> matches.containsKey(p.slug()))
                    .sorted(Comparator.comparingLong(Project::downloads).reversed().thenComparing(Project::slug)).toList()) {
                String quote = matches.get(project.slug()); var range = scopes.get(project.slug());
                Set<String> covered = scopeUnknown ? Set.of("组合") : Set.of(range.label().split(" / "));
                boolean locked = protectedSlugs.contains(project.slug());
                boolean recommended = locked || Collections.disjoint(recommendedScopes, covered);
                if (recommended) recommendedScopes.addAll(covered);
                candidates.add(new Candidate(project.slug(), project.title(), project.description(), project.iconUrl(), project.downloads(),
                        "描述依据", quote, range.label(), range.evidence(), locked, recommended));
            }
            groups.add(new Group(rule.id(), rule.title(), rule.explanation(), List.copyOf(candidates), true));
        }
        return List.copyOf(groups);
    }
}
