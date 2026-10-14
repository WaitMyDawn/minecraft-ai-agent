package yagen.waitmydawn.maa.model;

import tools.jackson.databind.JsonNode;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import java.util.regex.*;

/** 优化组合只记录项目介绍中的用途线索；兼容选版与实测收益分别由执行器和运行检查负责。 */
public final class OptimizationPortfolio {
    private OptimizationPortfolio() {}
    public enum Side { CLIENT, SERVER, BOTH, UNKNOWN }
    public enum Role {
        RENDERER("renderer", "渲染引擎", "rendering engine optimization", List.of("sodium", "embeddium")),
        ENTITY_CULLING("entity-culling", "实体裁剪", "entity rendering culling", List.of("entityculling")),
        BLOCK_CULLING("block-culling", "方块面裁剪", "block face culling", List.of("moreculling")),
        LEAF_CULLING("leaf-culling", "树叶裁剪", "leaves rendering culling", List.of("moreculling")),
        MEMORY("memory", "内存", "memory usage optimization", List.of("ferrite-core", "modernfix")),
        CPU("cpu", "CPU 与计算开销", "cpu usage optimization", List.of("lithium", "modernfix")),
        TICK("tick", "游戏逻辑与 tick", "game logic tick optimization", List.of("lithium")),
        LOADING("loading", "启动与加载", "startup loading optimization", List.of("modernfix")),
        CHUNKS("chunks", "区块处理", "chunk generation optimization", List.of("noisium")),
        NETWORK("network", "网络处理", "network packet optimization", List.of("krypton")),
        RENDER_SUPPORT("render-support", "渲染辅助", "rendering performance optimization", List.of("immediatelyfast"));
        public final String id, label, query;
        public final List<String> seeds;
        Role(String id, String label, String query, List<String> seeds) { this.id=id; this.label=label; this.query=query; this.seeds=seeds; }
    }
    public record Goal(String id, String source, Side side, Set<Role> desired, boolean broad, SelectionPreferences preferences) {
        public Goal { desired = Set.copyOf(desired); }
    }
    public record Fact(String slug, Side side, Map<Role, String> roles, boolean infrastructure) {
        public Fact { roles = Map.copyOf(roles); }
        public boolean supports(Goal goal) {
            return !matching(goal).isEmpty();
        }
        public Set<Role> matching(Goal goal) {
            if (infrastructure || !(goal.side() == Side.BOTH || side == Side.BOTH || side == goal.side())) return Set.of();
            return roles.keySet().stream().filter(goal.desired()::contains)
                    .filter(role -> goal.side() != Side.SERVER || !CLIENT_ROLES.contains(role))
                    .filter(role -> goal.side() != Side.SERVER || !CLIENT_ONLY.matcher(roles.get(role)).find())
                    .filter(role -> goal.side() != Side.CLIENT || !SERVER_ONLY.matcher(roles.get(role)).find())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
    public record Entry(String slug, String versionId, Side side, Map<Role, String> roles) {
        public Entry { roles = Map.copyOf(roles); }
    }
    public record Target(String id, String source, Side side, Set<Role> desired, Set<Role> covered, Set<Role> missing, int modCount) {}
    public record Report(int schemaVersion, int totalFiles, int modCount, double percentage, boolean referenceActive,
                         int referenceMin, int referenceMax, String countStatus, Set<Role> covered, Set<Role> suggestedMissing,
                         List<Goal> goals, List<Target> targets, List<Entry> entries, boolean rootsKnown, List<String> additionalRenderers) {
        public Report { covered=Set.copyOf(covered); suggestedMissing=Set.copyOf(suggestedMissing); goals=List.copyOf(goals); targets=List.copyOf(targets); entries=List.copyOf(entries); additionalRenderers=List.copyOf(additionalRenderers); }
    }
    private static Pattern p(String value) { return Pattern.compile(value, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
    private static final Pattern NEGATIVE = p("does not |doesn't |without |not an? |not provided|compatib(?:le|ility) with|integration with|addon (?:to|for)|不提供|不优化|不减少|不改善|不需要|不要");
    private static final Pattern BENEFIT = p("optimi[sz]\\w*|improv\\w*|reduc\\w*|lower\\w*|faster|speed\\w* up|fix\\w*.{0,20}(?:memory leak|allocation)|优化|提高|提升|减少|降低|加快|修复.{0,6}内存泄漏");
    private static final Set<Role> CLIENT_ROLES = Set.of(Role.RENDERER, Role.ENTITY_CULLING, Role.BLOCK_CULLING, Role.LEAF_CULLING, Role.RENDER_SUPPORT);
    private static final Pattern CLIENT_ONLY = p("client.only|only on (?:the )?client|仅.{0,6}客户端"), SERVER_ONLY = p("server.only|only on (?:the )?server|仅.{0,6}服务端");
    private static final Pattern CULLING = p("\\bcull\\w*\\b|\\bocclusion\\b|path[- ]tracing|skip\\w*.{0,12}render|不渲染|裁剪");
    private static final Pattern CHUNK_IMPROVEMENT = p("(?:chunks?|worldgen|world generation).{0,55}(?:performance|optimi[sz]|faster|stutter|overhead|speed|latency)|(?:optimi[sz]\\w*|reduc\\w*|improv\\w*|faster).{0,35}(?:chunk (?:generation|loading|rendering)|worldgen (?:performance|speed)|world generation (?:performance|speed))|(?:优化|降低|减少).{0,15}(?:区块|世界生成)");
    private static final Pattern GAMEPLAY_RATE = p("brews?|brewing|smelt\\w*|cooking|crafting|酿造|烹饪|冶炼");
    private static final Pattern COMPUTATIONAL_TICK = p("optimi[sz]\\w*|\\bcpu\\b|processor|mspt|overhead|tick timings|game logic|计算|开销|优化");
    private static final Map<Role, Pattern> SUBJECTS = Map.ofEntries(
        Map.entry(Role.RENDERER, p("(?:modern|new|replacement|optimized|fast|high.performance).{0,25}(?:rendering engine|renderer)|replac\\w*.{0,45}(?:rendering engine|renderer)|替换.{0,8}渲染器|渲染引擎")),
        Map.entry(Role.ENTITY_CULLING, p("(?:entities|entity|block entities|实体|生物).{0,65}(?:cull\\w*|occlusion|not visible|invisible|裁剪|不可见)|(?:cull\\w*|occlusion|path tracing|hide\\w*|裁剪).{0,65}(?:entities|entity|实体)")),
        Map.entry(Role.BLOCK_CULLING, p("(?:block faces|block face|block rendering|方块面).{0,45}(?:cull\\w*|occlusion|裁剪)|(?:cull\\w*|occlusion|裁剪).{0,45}(?:block faces|block face|方块面)")),
        Map.entry(Role.LEAF_CULLING, p("(?:leaf|leaves|foliage|树叶).{0,45}(?:cull\\w*|occlusion|裁剪)|(?:cull\\w*|occlusion|裁剪).{0,45}(?:leaf|leaves|foliage|树叶)")),
        Map.entry(Role.CPU, p("\\bcpu\\b|processor|计算开销|CPU负载")),
        Map.entry(Role.MEMORY, p("memory|\\bram\\b|allocations?|内存")),
        Map.entry(Role.TICK, p("\\bticks?\\b|ticking|game logic|mob ai|entity ai|pathfinding|game physics|游戏逻辑|寻路")),
        Map.entry(Role.LOADING, p("startup|start.up|load(?:ing)? times?|resource reload|启动|加载(?:时间|速度)")),
        Map.entry(Role.CHUNKS, p("chunks?|world generation|worldgen|区块|世界生成")),
        Map.entry(Role.NETWORK, p("network|packets?|networking|网络|数据包")),
        Map.entry(Role.RENDER_SUPPORT, p("render(?:ing)?|frame rates?|\\bfps\\b|draw calls?|渲染|帧率"))
    );
    public static Map<Role, String> classify(String description) {
        var result = new EnumMap<Role, String>(Role.class);
        String text = description == null ? "" : description.substring(0, Math.min(16000, description.length()));
        for (String sentence : text.split("[.!?。！？\\n]+")) {
            if (sentence.isBlank() || sentence.length() > 600 || NEGATIVE.matcher(sentence).find()
                    || sentence.stripLeading().matches("^(?:!\\[|\\[|#{1,6}\\s).*")) continue;
            boolean benefit = BENEFIT.matcher(sentence).find();
            for (var rule : SUBJECTS.entrySet()) {
                if (rule.getKey() == Role.CHUNKS && !CHUNK_IMPROVEMENT.matcher(sentence).find()) continue;
                if (rule.getKey() == Role.TICK && GAMEPLAY_RATE.matcher(sentence).find() && !COMPUTATIONAL_TICK.matcher(sentence).find()) continue;
                var match = rule.getValue().matcher(sentence);
                boolean culling = Set.of(Role.ENTITY_CULLING, Role.BLOCK_CULLING, Role.LEAF_CULLING).contains(rule.getKey());
                String mechanismText = rule.getKey() == Role.ENTITY_CULLING ? sentence : sentence.replaceAll("(?i)entity\\s*culling", "");
                boolean mechanism = culling && CULLING.matcher(mechanismText).find();
                if (match.find() && (mechanism || benefit && !culling)) {
                    // 引用保持原文连续片段，超长句仅保留匹配附近，避免单项历史放大。
                    int start = Math.max(0, match.start() - 35), end = Math.min(sentence.length(), start + 160);
                    result.putIfAbsent(rule.getKey(), sentence.substring(start, end).trim());
                }
            }
        }
        if (!Collections.disjoint(result.keySet(), Set.of(Role.RENDERER, Role.ENTITY_CULLING, Role.BLOCK_CULLING, Role.LEAF_CULLING))) result.remove(Role.RENDER_SUPPORT);
        return Map.copyOf(result);
    }
    public static Fact fact(String slug, JsonNode info) {
        if (info == null) return new Fact(slug, Side.UNKNOWN, Map.of(), false);
        var categories = new HashSet<String>(); info.path("categories").forEach(c -> categories.add(c.asText()));
        String description = info.path("description").asText() + "\n" + info.path("body").asText();
        return new Fact(slug, side(info), classify(description), PackAddonPolicy.infrastructure(slug, info.path("title").asText(), categories));
    }
    private static Side side(JsonNode info) {
        boolean client = Set.of("required", "optional").contains(info.path("client_side").asText());
        boolean server = Set.of("required", "optional").contains(info.path("server_side").asText());
        if (client && server) return Side.BOTH;
        if (client) return Side.CLIENT;
        if (server) return Side.SERVER;
        for (var value : info.path("environment")) {
            String env = value.asText();
            if (Set.of("client_and_server", "client_or_server", "client_or_server_prefers_both").contains(env)) return Side.BOTH;
            if (Set.of("client_only", "singleplayer_only").contains(env)) return Side.CLIENT;
            if (Set.of("server_only", "dedicated_server_only").contains(env)) return Side.SERVER;
            if (Set.of("client_only_server_optional", "server_only_client_optional").contains(env)) return Side.BOTH;
        }
        return Side.UNKNOWN;
    }
    public static Optional<Goal> goal(BuildRequirement requirement) {
        if (!Set.of(BuildRequirement.Kind.CAPABILITY, BuildRequirement.Kind.GENERAL).contains(requirement.kind())) return Optional.empty();
        String source = requirement.source();
        boolean explicit = p("性能|优化模组|优化mod|帧率|\\bfps\\b|\\btps\\b|performance|optimi[sz]ation|(?:改善|优化|提高|降低|减少).{0,10}(?:渲染|内存|区块|游戏逻辑|网络|启动)").matcher(source).find();
        boolean performanceSearch = requirement.searches().stream().anyMatch(search -> search.category().equals("optimization"));
        if (!explicit && !performanceSearch && !requirement.capabilities().contains(BuildRequirement.Capability.PERFORMANCE)) return Optional.empty();
        if (!requirement.capabilities().isEmpty() && !requirement.capabilities().contains(BuildRequirement.Capability.PERFORMANCE)) return Optional.empty();
        if (p("不需要优化|不要优化|无需优化|不需要性能|no optimization").matcher(source).find()) return Optional.empty();
        boolean server = p("服务端|服务器|server|\\btps\\b").matcher(source).find();
        boolean client = p("客户端|client|\\bfps\\b|帧率").matcher(source).find();
        Side side = server && !client ? Side.SERVER : client && !server ? Side.CLIENT : Side.BOTH;
        var focused = EnumSet.noneOf(Role.class);
        if (p("内存|memory|\\bram\\b").matcher(source).find()) focused.add(Role.MEMORY);
        if (p("游戏逻辑|寻路|tick|\\btps\\b").matcher(source).find()) focused.add(Role.TICK);
        if (p("\\bcpu\\b|处理器|计算开销").matcher(source).find()) focused.add(Role.CPU);
        if (p("启动|加载|startup|loading").matcher(source).find()) focused.add(Role.LOADING);
        if (p("区块|chunk|worldgen").matcher(source).find()) focused.add(Role.CHUNKS);
        if (p("网络|network|packet").matcher(source).find()) focused.add(Role.NETWORK);
        if (p("实体裁剪|entity culling").matcher(source).find()) focused.add(Role.ENTITY_CULLING);
        if (p("树叶|leaf|leaves").matcher(source).find()) focused.add(Role.LEAF_CULLING);
        if (p("方块裁剪|block culling").matcher(source).find()) focused.add(Role.BLOCK_CULLING);
        if (p("渲染|render|\\bfps\\b|帧率").matcher(source).find()) focused.addAll(Set.of(Role.RENDERER, Role.ENTITY_CULLING, Role.BLOCK_CULLING, Role.LEAF_CULLING, Role.RENDER_SUPPORT));
        boolean broad = focused.isEmpty();
        if (broad) focused.addAll(side == Side.SERVER ? Set.of(Role.MEMORY, Role.CPU, Role.TICK, Role.LOADING, Role.CHUNKS, Role.NETWORK)
                : side == Side.CLIENT ? Set.of(Role.RENDERER, Role.ENTITY_CULLING, Role.MEMORY, Role.LOADING)
                : Set.of(Role.RENDERER, Role.ENTITY_CULLING, Role.BLOCK_CULLING, Role.LEAF_CULLING, Role.MEMORY, Role.CPU, Role.TICK, Role.LOADING, Role.CHUNKS, Role.NETWORK));
        return Optional.of(new Goal(requirement.id(), source.substring(0, Math.min(source.length(), 400)), side, focused, broad, requirement.preferences()));
    }
    public static List<Goal> goals(BuildIntent intent, Set<String> freshIds, boolean freshOnly) {
        if (intent == null || intent.mode() == BuildIntent.Mode.EMPTY) return List.of();
        return intent.requirements().stream().filter(r -> !freshOnly || intent.mode() != BuildIntent.Mode.EDIT || freshIds.contains(r.id()))
                .flatMap(r -> r.criteria().isEmpty() ? java.util.stream.Stream.of(r) : r.criteria().stream().map(c -> c.target(r)))
                .map(OptimizationPortfolio::goal).flatMap(Optional::stream).flatMap(g -> expand(g).stream()).toList();
    }
    private static List<Goal> expand(Goal goal) {
        if (goal.side() != Side.BOTH || !p("客户端|client|\\bfps\\b|帧率").matcher(goal.source()).find()
                || !p("服务端|服务器|server|\\btps\\b").matcher(goal.source()).find()) return List.of(goal);
        var serverRoles = EnumSet.noneOf(Role.class); serverRoles.addAll(goal.desired()); serverRoles.removeAll(CLIENT_ROLES);
        var clientRoles = goal.broad() ? Set.of(Role.RENDERER, Role.ENTITY_CULLING, Role.MEMORY, Role.LOADING) : goal.desired();
        return List.of(new Goal(goal.id(), goal.source(), Side.CLIENT, clientRoles, goal.broad(), goal.preferences()),
                new Goal(goal.id(), goal.source(), Side.SERVER, serverRoles, goal.broad(), goal.preferences()));
    }
    public static boolean withinScope(String slug, Map<String, Fact> facts, List<Goal> goals, Map<String, Set<String>> hints) {
        Set<String> associated = hints.getOrDefault(slug, Set.of());
        var matched = goals.stream().filter(g -> associated.contains(g.id())).toList();
        if (matched.isEmpty() || associated.stream().anyMatch(id -> goals.stream().noneMatch(g -> g.id().equals(id)))) return true;
        return facts.containsKey(slug) && matched.stream().anyMatch(facts.get(slug)::supports);
    }
    public static Set<String> seeds(List<Goal> goals) {
        var result = new LinkedHashSet<String>();
        goals.stream().flatMap(g -> g.desired().stream().filter(r -> g.side() != Side.SERVER || !CLIENT_ROLES.contains(r))).distinct().sorted().forEach(r -> result.addAll(r.seeds));
        return result;
    }
    public static boolean canAdd(String slug, Map<String, Fact> facts, Set<String> selected) {
        if (selected.contains(slug)) return true;
        var candidate = facts.get(slug);
        return candidate == null || !candidate.roles().containsKey(Role.RENDERER)
                || selected.stream().map(facts::get).filter(Objects::nonNull).noneMatch(f -> f.roles().containsKey(Role.RENDERER));
    }
    /** 先补原文目标，再补不同优化用途；软数量参考不授予批准或补位权限。 */
    public static List<String> order(List<String> eligible, Map<String, Set<String>> hints, Set<String> existing,
                                     Map<String, Fact> facts, List<Goal> goals, int totalFiles) {
        if (goals.isEmpty()) return eligible;
        var covered = new HashSet<String>(); existing.forEach(s -> covered.addAll(hints.getOrDefault(s, Set.of())));
        var roles = new HashSet<String>(); var selected = new HashSet<>(existing);
        existing.stream().map(facts::get).filter(Objects::nonNull).filter(f -> goals.stream().anyMatch(f::supports)).forEach(f -> goals.forEach(g -> f.matching(g).forEach(r -> roles.add(g.id() + ":" + g.side() + ":" + r))));
        var pending = new ArrayList<>(new LinkedHashSet<>(eligible)); var result = new ArrayList<String>();
        while (!pending.isEmpty()) {
            String best = pending.getFirst(); long bestGain = -1;
            for (String slug : pending) {
                long gain = hints.getOrDefault(slug, Set.of()).stream().filter(id -> !covered.contains(id)).count() * 100;
                var fact = facts.get(slug);
                if (fact != null && goals.stream().anyMatch(fact::supports) && canAdd(slug, facts, selected)) {
                    gain += goals.stream().flatMap(g -> fact.matching(g).stream().map(r -> g.id() + ":" + g.side() + ":" + r)).distinct().filter(r -> !roles.contains(r)).count() * 4;
                    long count = selected.stream().map(facts::get).filter(Objects::nonNull).filter(f -> goals.stream().anyMatch(f::supports)).count();
                    if (goals.stream().anyMatch(Goal::broad) && count < referenceMin(totalFiles)) gain++;
                }
                if (gain > bestGain) { best=slug; bestGain=gain; }
            }
            pending.remove(best); result.add(best);
            if (canAdd(best, facts, selected) && withinScope(best, facts, goals, hints)) {
                covered.addAll(hints.getOrDefault(best, Set.of())); selected.add(best);
                var next = facts.get(best); if (next != null) goals.forEach(g -> next.matching(g).forEach(r -> roles.add(g.id() + ":" + g.side() + ":" + r)));
            }
        }
        return List.copyOf(result);
    }
    public static int referenceMin(int files) { return Math.max(1, (int) Math.ceil(files * .05)); }
    public static int referenceMax(int files) { return Math.max(referenceMin(files), (int) Math.ceil(files * .10)); }
    public static Report report(List<Goal> goals, Map<String, Fact> facts, Map<String, String> versions, Set<String> roots, int totalFiles, boolean rootsKnown) {
        if (goals.isEmpty()) return null;
        var entries = new ArrayList<Entry>();
        for (String slug : new TreeSet<>(roots)) {
            var fact = facts.get(slug);
            if (versions.containsKey(slug) && !versions.get(slug).isBlank() && fact != null && goals.stream().anyMatch(fact::supports)) entries.add(new Entry(slug, versions.get(slug), fact.side(), fact.roles().entrySet().stream()
                    .filter(e -> goals.stream().anyMatch(g -> fact.matching(g).contains(e.getKey())))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        }
        return summarize(goals, entries, totalFiles, rootsKnown);
    }
    public static Report retain(Report original, Set<String> selected, Set<String> roots) {
        return summarize(original.goals(), original.entries().stream().filter(e -> selected.contains(e.slug()) && roots.contains(e.slug())).toList(), selected.size(), original.rootsKnown());
    }
    private static Report summarize(List<Goal> goals, List<Entry> entries, int totalFiles, boolean rootsKnown) {
        var covered = EnumSet.noneOf(Role.class); entries.forEach(e -> covered.addAll(e.roles().keySet()));
        var targets = new ArrayList<Target>(); var missing = EnumSet.noneOf(Role.class);
        for (var goal : goals) {
            var matching = entries.stream().map(e -> new Fact(e.slug(), e.side(), e.roles(), false)).filter(f -> f.supports(goal)).toList();
            var found = EnumSet.noneOf(Role.class); matching.forEach(f -> found.addAll(f.matching(goal)));
            var gaps = EnumSet.noneOf(Role.class); gaps.addAll(goal.desired()); gaps.removeAll(found); missing.addAll(gaps);
            targets.add(new Target(goal.id(), goal.source(), goal.side(), goal.desired(), found, gaps, matching.size()));
        }
        boolean reference = goals.stream().anyMatch(Goal::broad); int low = referenceMin(totalFiles), high = referenceMax(totalFiles);
        String status = !reference ? "SCOPED_REFERENCE_NOT_APPLICABLE" : entries.size() < low ? "BELOW_REFERENCE" : entries.size() > high ? "ABOVE_REFERENCE" : "WITHIN_REFERENCE";
        var renderers = entries.stream().filter(e -> e.roles().containsKey(Role.RENDERER)).map(Entry::slug).toList();
        return new Report(1, totalFiles, entries.size(), totalFiles == 0 ? 0 : entries.size() * 100.0 / totalFiles,
                reference, reference ? low : 0, reference ? high : 0, status, covered, missing, goals, targets, entries, rootsKnown,
                renderers.size() > 1 ? renderers : List.of());
    }
    public static List<RequirementReview> filterReviews(List<BuildRequirement> requirements, List<RequirementReview> reviews, Map<String, Fact> facts) {
        var parents = new HashMap<String, BuildRequirement>(); requirements.forEach(r -> parents.put(r.id(), r));
        return reviews.stream().map(review -> {
            var parent = parents.get(review.id()); if (parent == null) return review;
            var parentGoal = !parent.criteria().isEmpty() || parent.capabilities().size() > 1 ? Optional.<Goal>empty() : goal(parent);
            var evidence = filter(review.evidence(), parentGoal, facts);
            var criteria = review.criteria().stream().map(c -> {
                var planned = parent.criteria().stream().filter(t -> t.source().equals(c.source())).findFirst();
                var target = planned.flatMap(t -> goal(t.target(parent)));
                var filtered = filter(c.evidence(), target, facts);
                return new CriterionReview(c.source(), filtered, reviewedGap(c.gap(), target, filtered, facts));
            }).toList();
            return new RequirementReview(review.id(), evidence, reviewedGap(review.gap(), parentGoal, evidence, facts), criteria);
        }).toList();
    }
    private static String reviewedGap(String initial, Optional<Goal> goal, List<RequirementReview.Contribution> evidence, Map<String, Fact> facts) {
        if (goal.isEmpty() || expand(goal.get()).size() < 2) return initial;
        var missing = expand(goal.get()).stream().filter(g -> evidence.stream().noneMatch(e ->
                !filter(List.of(e), Optional.of(g), facts).isEmpty())).map(g -> g.side() == Side.SERVER ? "Minecraft服务端" : "客户端").toList();
        if (missing.isEmpty()) return initial;
        String result = initial + (initial.isBlank() ? "" : "；") + "优化侧别尚无用途证据：" + String.join("、", missing);
        return result.substring(0, Math.min(500, result.length()));
    }
    private static List<RequirementReview.Contribution> filter(List<RequirementReview.Contribution> contributions, Optional<Goal> goal, Map<String, Fact> facts) {
        if (goal.isEmpty()) return contributions;
        return contributions.stream().filter(e -> {
            var fact = facts.get(e.slug());
            if (fact == null || !fact.supports(goal.get())) return false;
            var quoted = new Fact(e.slug(), fact.side(), classify(e.quote()), fact.infrastructure());
            return quoted.supports(goal.get()) || goal.get().broad() && BENEFIT.matcher(e.quote()).find()
                    && p("performance|optimi[sz]ation|性能|优化").matcher(e.quote()).find();
        }).toList();
    }
}
