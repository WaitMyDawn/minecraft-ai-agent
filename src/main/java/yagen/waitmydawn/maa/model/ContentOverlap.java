package yagen.waitmydawn.maa.model;

import java.util.*;
import java.util.regex.*;

/** 描述证据驱动的组合确认；类别、项目名和下载量均不能独立证明功能重叠。 */
public final class ContentOverlap {
    private ContentOverlap() {}
    public record Project(String slug, String title, String description, String body, String iconUrl, long downloads) {}
    public record Candidate(String slug, String title, String description, String iconUrl, long downloads,
                            String relation, String evidence, String scope, String scopeEvidence, boolean protectedSelection, boolean recommended,
                            String compatibilityNote, String scopeNote) {
        public Candidate { scopeNote = Objects.requireNonNullElse(scopeNote, ""); }
        public Candidate(String slug, String title, String description, String iconUrl, long downloads, String relation, String evidence,
                         String scope, String scopeEvidence, boolean protectedSelection, boolean recommended, String compatibilityNote) {
            this(slug, title, description, iconUrl, downloads, relation, evidence, scope, scopeEvidence, protectedSelection, recommended, compatibilityNote, "");
        }
        public Candidate(String slug, String title, String description, String iconUrl, long downloads, String relation, String evidence,
                         String scope, String scopeEvidence, boolean protectedSelection, boolean recommended) {
            this(slug, title, description, iconUrl, downloads, relation, evidence, scope, scopeEvidence, protectedSelection, recommended, "");
        }
    }
    public record Group(String original, String title, String explanation, List<Candidate> candidates, boolean verified) {}
    private record Rule(String id, String title, String explanation, Pattern pattern) {}
    private static Pattern pattern(String regex) { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
    private static final List<Rule> RULES = List.of(
        new Rule("real-animals", "现实生物生态", "这些模组都添加现实生物，可能出现重复生物或生态定位重叠；尚未证明具体物种重复。",
            pattern("现实(?:世界)?(?:的)?(?:生物|动物)|现实生活中的动物|real[- ](?:world|life)\\s+(?:animals|creatures)|realistic\\s+animals")),
        // 世界生成统一使用 DimensionGeneration 的依据，规则表只提供分组元数据。
        new Rule("major-worldgen", "大规模世界生成", "这些模组介绍体现较大范围的世界生成改动，组合可能存在兼容或风格风险。不同维度可以一起选择，这不是已确认冲突。",
            pattern("(?!)")),
        new Rule("inventory-sort", "背包整理功能", "介绍均明确提供背包排序或整理功能，使用体验可能重复。名称中的 inventory 或类别标签本身不构成依据。",
            pattern("(?:inventory|inventories|chests?|containers?)\\s+(?:(?:automatic|automated|auto|item)\\s+){0,3}(?:sort(?:ing|er|ed)?|organiz(?:e|ing|ation))|(?:sort(?:s|ing)?|organiz(?:e|es|ing))\\s+(?:(?:the|your|player|players|my|main|entire|all|of|an?|items in)\\s+){0,5}(?:inventory|inventories|chests?|containers?)|背包.{0,8}(?:整理|排序)|(?:整理|排序).{0,8}背包")),
        new Rule("inventory-hud", "背包常驻显示", "这些模组介绍均提供在游戏画面中显示背包内容的 HUD，可能重复显示相同物品信息。可保留一个、多个或全部；这不是已确认的运行冲突。",
            pattern("(?:display|show|render|add)\\w*[^.!?\\n]{0,45}inventor(?:y|ies)[^.!?\\n]{0,80}(?:\\bhud\\b|overlay|on[- ]screen|on (?:the |your )?screen|without opening)|inventor(?:y|ies)[^.!?\\n]{0,25}(?:\\bhud\\b|overlay)|(?:屏幕|画面|HUD).{0,25}(?:显示|展示).{0,12}背包|(?:显示|展示).{0,12}背包.{0,25}(?:屏幕|画面|HUD)")),
        new Rule("recipe-viewer", "通用配方浏览功能", "介绍均明确提供通用物品配方查询或浏览功能，可能形成重复界面；联动配方支持和厨房专用配方不作为此类依据。可保留一个或确认一起使用。",
            pattern("(?:view|browse|look up|show|display|search)\\w*[^.!?\\n]{0,30}(?:item\\s+)?recipes|recipes?\\s+(?:viewer|browser)|(?:配方查询|配方浏览|查询配方|浏览配方)"))
    );
    private static final Pattern EXCLUDED_CONTEXT = pattern("compatible with|compatibility with|integration with|support for|unlike |does not |doesn't |without |not provided|不添加|不提供|不改变|兼容|联动|不包含");
    private static final Pattern LOGISTICS_SORT = pattern("network(?:ed)?\\s+pipes?|(?:item|transport)\\s+pipes?|logistics|item\\s+(?:routing|transport(?:ation)?)|routes?\\s+items\\s+between|自动物流|物流管道|物品路由|物品运输");
    private static final Pattern RECIPE_REFERENCE = pattern("(?:consider|recommend\\w*|suggest\\w*|requir\\w*|needs?|install\\w*|download\\w*)[^.!?\\n]{0,70}(?:recipes?[- ](?:viewer|browser)|(?:view|brows|show|look up)\\w*[^.!?\\n]{0,30}recipes)|(?:建议|推荐|需要|安装).{0,25}(?:配方查询|配方浏览|配方查看器)");
    private static final Pattern RECIPE_INTEGRATION = pattern("(?:requir\\w*|needs?|using|via)\\s+(?:\\*{1,2}|\\[)?(?:JEI|REI|EMI|Just Enough Items|Roughly Enough Items)\\b|(?:hid\\w*|hide\\w*|toggl\\w*|mov\\w*|reposition\\w*)[^.!?\\n]{0,35}recipes?[- ](?:viewer|browser)|(?:隐藏|移动|切换).{0,15}配方(?:查看器|浏览器)");
    private static final Pattern EXTERNAL_SECTION = pattern("compatib|integrations?|recommended|suggested|dependencies|requirements|supported mods|兼容|联动|推荐|依赖|前置");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^\\s*(#{1,6})\\s+(.+)$");
    private static final Pattern LINK_SUBJECT = pattern("^\\s*(?:[-*+]\\s+)?\\[[^]\\n]+]\\(https?://[^)]+\\)\\s*(?:[-:：–—]|(?:adds?|shows?|displays?|provides?)\\b)");
    private static final Pattern RECIPE_SUBJECT = pattern("^\\s*(?:[-*+]\\s+)?(?:JEI|REI|EMI|Just Enough Items|Roughly Enough Items)\\b.*(?:[-:：–—]|shows?|displays?|provides?)");
    private static final Pattern SPECIALIZED_RECIPE = pattern("\\b(?:kitchen|cooking|culinary|food|foods|meal|meals|crops?|smelting|brewing)\\b|厨房|烹饪|食物|食材|冶炼|酿造");
    private static final Pattern HUD_REFERENCE = pattern("(?:recommend\\w*|suggest\\w*|install\\w*|requir\\w*|hide\\w*|hid\\w*|toggl\\w*)[^.!?\\n]{0,60}(?:inventory[^.!?\\n]{0,20}(?:hud|overlay)|(?:hud|overlay)[^.!?\\n]{0,20}inventory)|(?:建议|安装|隐藏|切换).{0,20}背包.{0,10}(?:显示|HUD)");
    private static final Pattern NETHER = pattern("\\bnether\\b|下界"), END = pattern("\\bend\\b|末地"), OVERWORLD = pattern("\\boverworld\\b|主世界");
    private static final Pattern WORLD_FEATURE = pattern("\\b(?:adds?|introduces?|overhauls?|overhauling|revamps?|revamping|reshapes?|reshaping|transforms?|transforming|explore|features?)\\b|with\\s+(?:over|more than)\\s+\\d+|expansive\\s+biome|new (?:and unique )?biomes|新增|添加|大幅|重塑");
    private static String evidence(Project project, Rule rule) {
        String raw = project.description() + "\n" + project.body();
        String text = raw.substring(0, Math.min(raw.length(), 16000));
        if (rule.id().equals("major-worldgen")) return DimensionGeneration.richEvidence(text);
        if (rule.id().equals("recipe-viewer")) return recipeEvidence(text, rule);
        for (String sentence : text.split("[.!?。！？\\n]+")) {
            if (sentence.length() > 600) continue;
            // 联动说明、对比说明或明确否定不作为自身新增功能的证明。
            String context = rule.id().equals("inventory-hud") ? sentence.replaceAll("(?i)without\\s+opening\\b", "") : sentence;
            if (EXCLUDED_CONTEXT.matcher(context).find()) continue;
            // 物流网络分拣不是玩家背包整理，即使介绍同时提及箱子和排序。
            if (rule.id().equals("inventory-sort") && LOGISTICS_SORT.matcher(sentence).find()) continue;
            if (rule.id().equals("inventory-hud") && (HUD_REFERENCE.matcher(sentence).find() || LOGISTICS_SORT.matcher(sentence).find())) continue;
            // 安装建议或依赖说明中的查看器不构成当前项目自身的配方浏览功能。
            if (rule.id().equals("recipe-viewer") && (RECIPE_REFERENCE.matcher(sentence).find()
                    || RECIPE_INTEGRATION.matcher(sentence).find())) continue;
            var match = rule.pattern().matcher(sentence);
            if (match.find()) return sentence.trim();
        }
        return "";
    }
    private static String recipeEvidence(String text, Rule rule) {
        int externalLevel = 0;
        text = pattern("<h([1-6])[^>]*>(.*?)</h[1-6]>").matcher(text)
                .replaceAll(match -> "\n" + "#".repeat(Integer.parseInt(match.group(1))) + " " + match.group(2) + "\n");
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.replaceAll("(?i)</?(?:p|div|h[1-6]|li|ul|ol|strong|b|em)[^>]*>", " ").trim();
            if (line.matches("\\*\\*[^*]+\\*\\*:?")) line = "## " + line.replace("**", "");
            var heading = MARKDOWN_HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                if (externalLevel > 0 && level <= externalLevel) externalLevel = 0;
                if (EXTERNAL_SECTION.matcher(heading.group(2)).find() && externalLevel == 0) externalLevel = level;
                continue;
            }
            if (line.endsWith(":") && (EXTERNAL_SECTION.matcher(line).find() || RECIPE_SUBJECT.matcher(line).find())) {
                externalLevel = externalLevel == 0 ? 6 : externalLevel;
                continue;
            }
            // 外部项目作为主语、兼容章节及链接目标均不能证明当前项目自身的界面功能。
            if (externalLevel > 0 || LINK_SUBJECT.matcher(line).find() || RECIPE_SUBJECT.matcher(line).find()) continue;
            line = line.replaceAll("\\[([^]\\n]+)]\\(https?://[^)]+\\)", "$1")
                    .replaceAll("https?://\\S+", "");
            for (String sentence : line.split("[.!?。！？]+")) {
                if (sentence.length() > 600 || EXCLUDED_CONTEXT.matcher(sentence).find()
                        || RECIPE_REFERENCE.matcher(sentence).find() || RECIPE_INTEGRATION.matcher(sentence).find()
                        || SPECIALIZED_RECIPE.matcher(sentence).find()) continue;
                if (rule.pattern().matcher(sentence).find()) return sentence.trim();
            }
        }
        return "";
    }
    private static String scope(String evidence) {
        var result = new ArrayList<String>();
        if (NETHER.matcher(evidence).find()) result.add("下界");
        if (END.matcher(evidence).find()) result.add("末地");
        if (OVERWORLD.matcher(evidence).find()) result.add("主世界");
        DimensionGeneration.describedGoals(evidence).stream().filter(g -> !Set.of("nether", "end", "overworld").contains(g.dimension()))
                .map(DimensionGeneration.Goal::label).filter(label -> !result.contains(label)).forEach(result::add);
        return result.isEmpty() ? "介绍未明确范围" : String.join(" / ", result);
    }
    private record Scope(String label, String evidence) {}
    /** 宽松线索只用于尊重既有生成选择，不作为核心生成需求的正向覆盖证据。 */
    public static boolean majorChangeIn(String slug, String facts, String dimension) {
        var rule = RULES.stream().filter(r -> r.id().equals("major-worldgen")).findFirst().orElseThrow();
        var project = new Project(slug, slug, facts, "", "", 0);
        String quote = evidence(project, rule);
        String label = switch (dimension) { case "nether" -> "下界"; case "end" -> "末地"; case "overworld" -> "主世界"; default -> dimension; };
        if (!quote.isEmpty() && Arrays.asList(worldScope(project, quote).label().split(" / ")).contains(label)) return true;
        // 选择边界也覆盖直接改写生成器的项目；高度上限、少量群系及局部结构不单独触发。
        var changesGenerator = pattern("(?:expand\\w*|chang\\w*|modif\\w*|alter\\w*|higher|increas\\w*)[^.!?\\n]{0,35}\\b(?:nether|end|overworld)\\s+(?:terrain\\s+|world\\s+)?generation\\b|floating\\s+islands?\\s+(?:world\\s+)?generation|a stack of new biomes");
        for (String sentence : facts.split("[.!?。！？\\n]+")) {
            if (sentence.length() > 1000 || EXCLUDED_CONTEXT.matcher(sentence).find()) continue;
            if (changesGenerator.matcher(sentence).find() && Arrays.asList(scope(sentence).split(" / ")).contains(label)) return true;
        }
        return false;
    }
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
    public static Optional<Candidate> generationCandidate(Project project, Set<String> protectedSlugs) {
        String text = project.description() + "\n" + project.body();
        if (text.length() > 16000) text = text.substring(0, 16000);
        String quote = DimensionGeneration.richEvidence(text);
        if (quote.isEmpty()) return Optional.empty();
        var range = worldScope(project, quote);
        var goals = DimensionGeneration.describedGoals(range.evidence());
        String facts = text;
        boolean scopedProof = !goals.isEmpty() && goals.stream().allMatch(g -> DimensionGeneration.supports(g, facts));
        return Optional.of(new Candidate(project.slug(), project.title(), project.description(), project.iconUrl(), project.downloads(),
                scopedProof ? "核心生成介绍依据" : "丰富生成候选", quote, range.label(), range.evidence(), protectedSlugs.contains(project.slug()), false, "",
                scopedProof ? "" : range.label().equals("介绍未明确范围")
                        ? "介绍证明有丰富生成内容，但影响哪些维度尚未明确，请核对详情后选择。"
                        : "介绍提及这些维度，但尚不能分别核实每个维度的变化规模；总群系数量不能按维度分摊。"));
    }
    private static Set<String> ranges(Candidate candidate) {
        return candidate.scope().equals("介绍未明确范围") ? Set.of() : new TreeSet<>(Arrays.asList(candidate.scope().split(" / ")));
    }
    private static boolean needsChoice(Candidate left, Candidate right, Map<String, List<String>> confirmed) {
        var a = ranges(left); var b = ranges(right);
        if (a.isEmpty() || b.isEmpty()) return confirmed.values().stream()
                .noneMatch(selected -> selected.contains(left.slug()) && selected.contains(right.slug()));
        var common = new HashSet<>(a); common.retainAll(b);
        return common.stream().anyMatch(label -> {
            String realm = realm(label);
            var selected = confirmed.getOrDefault("generation:" + realm, List.of());
            return !selected.contains(left.slug()) || !selected.contains(right.slug());
        });
    }
    public static List<Group> detectByDimension(List<Project> projects, Set<String> protectedSlugs,
                                               Map<String, List<String>> confirmed) {
        var result = new ArrayList<>(detect(projects, protectedSlugs).stream().filter(g -> !g.original().equals("major-worldgen")).toList());
        var candidates = projects.stream().map(p -> generationCandidate(p, protectedSlugs)).flatMap(Optional::stream)
                .sorted(Comparator.comparing(Candidate::slug)).toList();
        var remaining = new LinkedHashSet<>(candidates);
        while (!remaining.isEmpty()) {
            var component = new ArrayList<Candidate>(); var queue = new ArrayDeque<Candidate>();
            var first = remaining.iterator().next(); remaining.remove(first); queue.add(first);
            while (!queue.isEmpty()) {
                var current = queue.removeFirst(); component.add(current);
                var neighbors = remaining.stream().filter(c -> needsChoice(current, c, confirmed)).toList();
                remaining.removeAll(neighbors); queue.addAll(neighbors);
            }
            if (component.size() < 2) continue;
            // 只合并范围重叠的连通组合，避免独立维度强迫多选，也避免未知范围被拆成多个非空组。
            boolean unknown = component.stream().anyMatch(c -> ranges(c).isEmpty());
            var allRanges = new TreeSet<String>(); component.forEach(c -> allRanges.addAll(ranges(c)));
            var ordered = component.stream().sorted(Comparator.comparing(Candidate::protectedSelection).reversed()
                    .thenComparing(c -> !c.scopeNote().isEmpty())
                    .thenComparing(Comparator.comparingLong(Candidate::downloads).reversed()).thenComparing(Candidate::slug)).toList();
            var covered = new HashSet<String>();
            ordered.stream().filter(Candidate::protectedSelection).forEach(c -> covered.addAll(ranges(c).isEmpty() ? Set.of("未知范围") : ranges(c)));
            var cards = new ArrayList<Candidate>();
            for (var c : ordered) {
                var range = ranges(c);
                boolean recommended = c.protectedSelection() || (range.isEmpty() ? covered.isEmpty()
                        : range.stream().anyMatch(label -> !covered.contains(label)));
                if (range.isEmpty()) range = Set.of("未知范围");
                if (recommended) covered.addAll(range);
                cards.add(new Candidate(c.slug(), c.title(), c.description(), c.iconUrl(), c.downloads(), c.relation(), c.evidence(),
                        c.scope(), c.scopeEvidence(), c.protectedSelection(), recommended, c.compatibilityNote(), c.scopeNote()));
            }
            String label = String.join(" / ", allRanges);
            String realm = realm(label);
            String baseId = !unknown && allRanges.size() == 1 ? "generation:" + realm : "major-worldgen";
            String id = result.stream().anyMatch(g -> g.original().equals(baseId)) ? baseId + ":" + first.slug() : baseId;
            result.add(new Group(id, unknown ? "世界生成组合（部分范围未明确）" : label + "世界生成组合",
                    unknown ? "这些项目都有丰富生成内容，部分影响范围尚未明确，可能与同组项目重叠，请核对介绍后选择。可保留一个、多个或全部；尚未验证运行冲突。"
                            : "这些项目的介绍显示生成范围存在重叠（" + label + "），可能改变同一维度的群系、地形或结构组合。可保留一个、多个或全部；范围重叠不等于已确认的运行冲突。",
                    List.copyOf(cards), true));
        }
        return List.copyOf(result);
    }
    private static String realm(String label) {
        return switch (label) {
            case "下界" -> "nether"; case "末地" -> "end"; case "主世界" -> "overworld";
            default -> label.endsWith("维度") ? label.substring(0, label.length() - 2) : label;
        };
    }
}
