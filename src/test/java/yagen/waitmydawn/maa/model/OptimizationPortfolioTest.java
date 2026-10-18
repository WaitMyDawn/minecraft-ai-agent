package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static yagen.waitmydawn.maa.model.OptimizationPortfolio.*;

class OptimizationPortfolioTest {
    @Test void hardwareBenchmarksAndModNamesDoNotProveMemoryOrCpuMechanisms() {
        for (String text : List.of("BadOptimizations tested on Intel i7 CPU with 4GB allocated RAM",
                "Performance optimization benchmark with Intel CPU and 4GB allocated RAM improves FPS",
                "Faster loading tested on a CPU with 8GB RAM", "提升帧率，测试配置为CPU和4GB内存")) {
            var roles = classify(text); assertFalse(roles.containsKey(Role.MEMORY), text); assertFalse(roles.containsKey(Role.CPU), text);
        }
        String text = "Tested with 4GB RAM on an Intel CPU, this mod reduces memory usage";
        assertTrue(classify(text).containsKey(Role.MEMORY)); assertFalse(classify(text).containsKey(Role.CPU));
        assertTrue(classify(text).get(Role.MEMORY).contains("reduces memory usage"));
        assertTrue(classify("Memory usage optimizations").containsKey(Role.MEMORY));
        assertTrue(classify("CPU overhead is reduced").containsKey(Role.CPU));
    }
    private final ObjectMapper json = new ObjectMapper();
    private BuildRequirement requirement(String text) {
        return new BuildRequirement("r-0000000000000001", text, BuildRequirement.Kind.CAPABILITY, List.of(), List.of(BuildRequirement.Capability.PERFORMANCE));
    }
    private Goal broad() { return goal(requirement("很多优化模组")).orElseThrow(); }
    private Fact fact(String slug, String text, Side side) { return new Fact(slug, side, classify(text), false); }
    @Test void namesTagsAndUnrelatedPerformanceWordsCannotSupplyEvidence() {
        var info = json.createObjectNode().put("slug", "sodium").put("title", "Fast Optimization FPS Renderer")
                .put("description", "Displays memory and FPS counters");
        info.putArray("categories").add("optimization");
        assertTrue(OptimizationPortfolio.fact("sodium", info).roles().isEmpty());
        assertTrue(classify("Improves magic with invisible creatures").isEmpty());
        assertTrue(classify("Compatible with mods that improve memory usage").isEmpty());
        assertTrue(classify("Does not optimize rendering").isEmpty());
        assertTrue(classify("Fixes broken crafting recipes").isEmpty());
    }
    @Test void specificMechanismsHaveIndependentRolesAndContiguousQuotes() {
        var descriptions = Map.ofEntries(
                Map.entry(Role.RENDERER, "Improves frame rates with a modern rendering engine"),
                Map.entry(Role.ENTITY_CULLING, "Uses occlusion culling to skip rendering invisible entities"),
                Map.entry(Role.BLOCK_CULLING, "Culls hidden block faces"), Map.entry(Role.LEAF_CULLING, "Culls hidden leaves"),
                Map.entry(Role.MEMORY, "Reduces memory usage"), Map.entry(Role.CPU, "Reduces CPU overhead"),
                Map.entry(Role.TICK, "Optimizes game logic and mob AI"), Map.entry(Role.LOADING, "Improves startup loading times"),
                Map.entry(Role.CHUNKS, "Optimizes chunk generation"), Map.entry(Role.NETWORK, "Optimizes network packets"),
                Map.entry(Role.RENDER_SUPPORT, "Reduces draw calls for rendering"));
        descriptions.forEach((role, text) -> {
            assertTrue(classify(text).containsKey(role), role.toString());
            assertTrue(text.contains(classify(text).get(role)));
        });
        assertFalse(classify(descriptions.get(Role.RENDERER)).containsKey(Role.RENDER_SUPPORT));
    }
    @Test void focusedScopesDoNotBecomeAFullPalette() {
        var server = goal(requirement("只降低服务器内存，补充优化模组")).orElseThrow();
        assertEquals(Side.SERVER, server.side()); assertEquals(Set.of(Role.MEMORY), server.desired()); assertFalse(server.broad());
        assertFalse(fact("client-memory", "Reduces memory usage", Side.CLIENT).supports(server));
        assertFalse(fact("unknown", "Reduces memory usage", Side.UNKNOWN).supports(server));
        assertTrue(fact("server-memory", "Reduces memory usage", Side.BOTH).supports(server));
        assertFalse(fact("client-only", "Reduces memory usage only on the client", Side.BOTH).supports(server));
        assertFalse(new Fact("lib", Side.BOTH, Map.of(Role.MEMORY, "Reduces memory usage"), true).supports(server));
        assertFalse(fact("render", "Improves frame rates with a modern rendering engine", Side.BOTH).supports(server));
    }
    @Test void scopedReportRequiresVersionsRootsAndCountsEachProjectOnce() {
        var memory = goal(requirement("降低内存，增加优化模组")).orElseThrow();
        var facts = Map.of("multi", fact("multi", "Reduces memory usage and improves startup loading times", Side.BOTH),
                "dependency", fact("dependency", "Reduces memory usage", Side.BOTH), "unverified", fact("unverified", "Reduces memory usage", Side.BOTH));
        var report = report(List.of(memory), facts, Map.of("multi", "V1", "dependency", "V2"), Set.of("multi", "unverified"), 100, true);
        assertEquals(1, report.modCount()); assertEquals(Set.of(Role.MEMORY), report.covered());
        assertFalse(report.referenceActive()); assertEquals("SCOPED_REFERENCE_NOT_APPLICABLE", report.countStatus());
        assertEquals(1.0, report.percentage()); assertEquals("V1", report.entries().getFirst().versionId());
    }
    @Test void broadDensityIsAdvisoryAndSelectionRecountsEvidence() {
        var report = report(List.of(broad()), Map.of("memory", fact("memory", "Reduces memory usage", Side.BOTH)),
                Map.of("memory", "V1"), Set.of("memory"), 100, true);
        assertEquals("BELOW_REFERENCE", report.countStatus());
        assertEquals(5, report.referenceMin()); assertEquals(10, report.referenceMax());
        var retained = retain(report, Set.of("other"), Set.of("other"));
        assertEquals(0, retained.modCount()); assertTrue(retained.covered().isEmpty()); assertEquals(1, retained.totalFiles());
        assertTrue(retained.suggestedMissing().contains(Role.MEMORY));
    }
    @Test void novelFunctionCannotStealPriorityFromAnotherUnmetRequirement() {
        var facts = Map.of("render", fact("render", "Improves frame rates with a modern rendering engine", Side.CLIENT),
                "duplicate", fact("duplicate", "Improves frame rates with a modern rendering engine", Side.CLIENT),
                "memory", fact("memory", "Reduces memory usage", Side.BOTH));
        var hints = Map.of("render", Set.of(broad().id()), "duplicate", Set.of(broad().id()), "memory", Set.of(broad().id()), "animals", Set.of("other"));
        assertEquals(List.of("animals", "memory", "duplicate"), order(List.of("duplicate", "memory", "animals"), hints, Set.of("render"), facts, List.of(broad()), 100));
        assertFalse(canAdd("duplicate", facts, Set.of("render")));
        assertTrue(canAdd("duplicate", facts, Set.of("render", "duplicate")), "Already retained explicit choices remain protected");
    }
    @Test void unprovenOptimizationOnlyCandidatesCannotUseUnrelatedHintsAsProof() {
        var scoped = goal(requirement("服务器内存优化")).orElseThrow();
        var facts = Map.of("client", fact("client", "Reduces memory usage", Side.CLIENT));
        assertFalse(withinScope("client", facts, List.of(scoped), Map.of("client", Set.of(scoped.id()))));
        assertTrue(withinScope("client", facts, List.of(scoped), Map.of("client", Set.of(scoped.id(), "ui"))));
    }
    @Test void reviewMustQuoteTheRequestedPurpose() {
        var req = requirement("服务器内存优化");
        var facts = Map.of("mem", fact("mem", "Reduces memory usage and improves loading times", Side.SERVER),
                "client", fact("client", "Reduces memory usage", Side.CLIENT));
        var review = new RequirementReview(req.id(), List.of(new RequirementReview.Contribution("mem", "Improves loading times", "加载"),
                new RequirementReview.Contribution("client", "Reduces memory usage", "内存")), "");
        assertTrue(filterReviews(List.of(req), List.of(review), facts).getFirst().evidence().isEmpty());
        var valid = new RequirementReview(req.id(), List.of(new RequirementReview.Contribution("mem", "Reduces memory usage", "内存")), "");
        assertEquals(1, filterReviews(List.of(req), List.of(valid), facts).getFirst().evidence().size());
    }
    @Test void broadReviewFallbackCannotUseNegatedOrOppositeSidePurpose() {
        var req = requirement("增加服务器优化模组");
        String description = "Reduces server memory usage. Client-only rendering performance optimization. "
                + "Does not provide network performance optimization. Minecraft performance optimization.";
        var facts = Map.of("multi", fact("multi", description, Side.BOTH));
        for (String quote : List.of("Client-only rendering performance optimization", "Does not provide network performance optimization")) {
            var review = new RequirementReview(req.id(), List.of(new RequirementReview.Contribution("multi", quote, "优化")), "");
            assertTrue(filterReviews(List.of(req), List.of(review), facts).getFirst().evidence().isEmpty(), quote);
        }
        var valid = new RequirementReview(req.id(), List.of(new RequirementReview.Contribution("multi", "Minecraft performance optimization", "优化")), "");
        assertEquals(1, filterReviews(List.of(req), List.of(valid), facts).getFirst().evidence().size());
    }
    @Test void installabilityOnBothSidesDoesNotChangeDeclaredPurposeSide() {
        var server = goal(requirement("降低服务端内存")).orElseThrow();
        var client = goal(requirement("降低客户端内存")).orElseThrow();
        assertFalse(fact("a", "Reduces client memory usage", Side.BOTH).supports(server));
        assertFalse(fact("b", "Reduces memory usage on the server", Side.BOTH).supports(client));
        assertTrue(fact("c", "Reduces memory usage on both client and server", Side.BOTH).supports(server));
        assertTrue(fact("c", "Reduces memory usage on both client and server", Side.BOTH).supports(client));
        assertFalse(fact("d", "减少客户端内存占用", Side.BOTH).supports(server));
    }
    @Test void boundedPurposeExcerptCannotLoseDistantSideQualifier() {
        String description = "Client-only " + "additional implementation detail ".repeat(6) + "reduces memory usage";
        var roles = classify(description);
        assertTrue(roles.isEmpty() || roles.values().stream().allMatch(q -> q.contains("Client-only")));
        assertFalse(new Fact("long", Side.BOTH, roles, false).supports(goal(requirement("服务端内存优化")).orElseThrow()));
    }
    @Test void projectMetadataAndDeclaredPurposeMustBothPermitTheRequestedSide() {
        var client = goal(requirement("降低客户端内存")).orElseThrow();
        var server = goal(requirement("降低服务端内存")).orElseThrow();
        var info = json.createObjectNode().put("description", "Reduces memory usage");
        info.put("client_side", "required").put("server_side", "unsupported");
        assertTrue(OptimizationPortfolio.fact("mod", info).supports(client));
        assertFalse(OptimizationPortfolio.fact("mod", info).supports(server));
        info.put("client_side", "unsupported").put("server_side", "required");
        assertFalse(OptimizationPortfolio.fact("mod", info).supports(client));
        assertTrue(OptimizationPortfolio.fact("mod", info).supports(server));
        info.put("client_side", "unknown").put("server_side", "unknown");
        assertFalse(OptimizationPortfolio.fact("mod", info).supports(client));
        assertFalse(OptimizationPortfolio.fact("mod", info).supports(server));
        info.putArray("environment").add("client_only_server_optional");
        info.put("description", "Reduces client memory usage");
        assertTrue(OptimizationPortfolio.fact("mod", info).supports(client));
        assertFalse(OptimizationPortfolio.fact("mod", info).supports(server));
    }
    @Test void explicitClientAndServerDemandRetainsMissingSide() {
        var req = requirement("客户端和服务器内存优化");
        var intent = intent(BuildIntent.Mode.NEW, List.of(req));
        var goals = goals(intent, Set.of(), false);
        assertEquals(2, goals.size());
        var facts = Map.of("mem", fact("mem", "Reduces memory usage", Side.CLIENT));
        var report = report(goals, facts, Map.of("mem", "V1"), Set.of("mem"), 50, true);
        assertEquals(Set.of(Role.MEMORY), report.suggestedMissing());
        assertEquals(0, report.targets().stream().filter(t -> t.side() == Side.SERVER).findFirst().orElseThrow().modCount());
        var review = new RequirementReview(req.id(), List.of(new RequirementReview.Contribution("mem", "Reduces memory usage", "内存")), "");
        assertTrue(filterReviews(List.of(req), List.of(review), facts).getFirst().gap().contains("服务端"));
    }
    private BuildRequirement scopedCriteria(String source, String... parts) {
        var searches = List.of(new BuildIntent.SearchIntent("optimization", 100, List.of("performance")));
        return new BuildRequirement("r-0000000000000001", source, BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(BuildRequirement.Capability.PERFORMANCE), List.of(), searches,
                SelectionPreferences.DEFAULT, Arrays.stream(parts).map(part -> new RequirementCriterion(part,
                        BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), searches)).toList());
    }
    @Test void parallelOptimizationCriteriaInheritTheirParentsSingleSideWithoutChangingSource() {
        for (var pair : Map.of(Side.CLIENT, "客户端", Side.SERVER, "服务端").entrySet()) {
            var req = scopedCriteria("改善" + pair.getValue() + "内存和加载", "改善" + pair.getValue() + "内存", "加载");
            var targets = goals(intent(BuildIntent.Mode.NEW, List.of(req)), Set.of(), false);
            assertEquals(2, targets.size());
            assertTrue(targets.stream().allMatch(g -> g.side() == pair.getKey()));
            var loading = targets.stream().filter(g -> g.source().equals("加载")).findFirst().orElseThrow();
            assertEquals(req.criteria().get(1).key(req.id()), loading.id());
            assertEquals(Set.of(Role.LOADING), loading.desired());
            var opposite = pair.getKey() == Side.CLIENT ? Side.SERVER : Side.CLIENT;
            assertFalse(fact("wrong-side", "Improves loading times", opposite).supports(loading));
        }
    }
    @Test void inheritedSideAlsoFiltersReviewEvidence() {
        var req = scopedCriteria("改善客户端渲染、实体裁剪和内存", "改善客户端渲染", "实体裁剪和内存");
        var facts = Map.of("client-memory", fact("client-memory", "Reduces memory usage", Side.CLIENT),
                "server-memory", fact("server-memory", "Reduces memory usage", Side.SERVER));
        var contributions = List.of(new RequirementReview.Contribution("client-memory", "Reduces memory usage", "内存"),
                new RequirementReview.Contribution("server-memory", "Reduces memory usage", "内存"));
        var review = new RequirementReview(req.id(), List.of(), "", List.of(new CriterionReview("实体裁剪和内存", contributions, "")));
        var filtered = filterReviews(List.of(req), List.of(review), facts).getFirst().criteria().getFirst();
        assertEquals(List.of("client-memory"), filtered.evidence().stream().map(RequirementReview.Contribution::slug).toList());
        var targets = goals(intent(BuildIntent.Mode.NEW, List.of(req)), Set.of(), false);
        var report = report(targets, facts, Map.of("client-memory", "V1"), Set.of("client-memory"), 1, true);
        assertTrue(report.targets().stream().anyMatch(t -> t.side() == Side.CLIENT && t.covered().contains(Role.MEMORY)));
    }
    @Test void ambiguousAndSeparateRequirementsDoNotBorrowASingleSide() {
        var mixed = scopedCriteria("客户端渲染、服务端加载和内存", "客户端渲染", "服务端加载", "内存");
        var targets = goals(intent(BuildIntent.Mode.NEW, List.of(mixed)), Set.of(), false);
        assertEquals(Side.CLIENT, targets.stream().filter(g -> g.source().equals("客户端渲染")).findFirst().orElseThrow().side());
        assertEquals(Side.SERVER, targets.stream().filter(g -> g.source().equals("服务端加载")).findFirst().orElseThrow().side());
        assertEquals(Side.BOTH, targets.stream().filter(g -> g.source().equals("内存")).findFirst().orElseThrow().side());
        var separate = new BuildRequirement("r-0000000000000002", "优化内存", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(BuildRequirement.Capability.PERFORMANCE));
        targets = goals(intent(BuildIntent.Mode.NEW, List.of(requirement("改善服务端加载"), separate)), Set.of(), false);
        assertEquals(Side.BOTH, targets.stream().filter(g -> g.id().equals(separate.id())).findFirst().orElseThrow().side());
    }
    @Test void projectNamesDoNotDeclareTheSideOfParallelOptimizationCriteria() {
        var performance = scopedCriteria("改善内存", "改善内存").criteria().getFirst();
        var named = new RequirementCriterion("添加client-tweaks", BuildRequirement.Kind.PROJECTS,
                List.of("client-tweaks"), List.of(), List.of());
        var parent = new BuildRequirement("r-0000000000000001", "添加client-tweaks，并改善内存", BuildRequirement.Kind.CAPABILITY,
                List.of("client-tweaks"), List.of(BuildRequirement.Capability.PERFORMANCE), List.of(), List.of(),
                SelectionPreferences.DEFAULT, List.of(named, performance));
        var targets = goals(intent(BuildIntent.Mode.NEW, List.of(parent)), Set.of(), false);
        assertEquals(1, targets.size()); assertEquals(Side.BOTH, targets.getFirst().side());
        assertEquals(Side.BOTH, goal(requirement("servercore之外补充内存优化模组")).orElseThrow().side());
    }
    @Test void excludedSideDoesNotDiluteThePositiveSideOfParallelCriteria() {
        var positive = scopedCriteria("改善客户端渲染和内存，不优化服务端", "改善客户端渲染", "内存");
        var criteria = new ArrayList<>(positive.criteria());
        criteria.add(new RequirementCriterion("不优化服务端", BuildRequirement.Kind.CHANGE, List.of(), List.of(), List.of()));
        var req = new BuildRequirement(positive.id(), positive.source(), positive.kind(), positive.projects(), positive.capabilities(),
                positive.addonCores(), positive.searches(), positive.preferences(), criteria);
        var targets = goals(intent(BuildIntent.Mode.NEW, List.of(req)), Set.of(), false);
        assertEquals(2, targets.size()); assertTrue(targets.stream().allMatch(g -> g.side() == Side.CLIENT));
    }
    @Test void unrelatedEditsAndExplicitPinsDoNotStartPerformanceRecall() {
        var req = requirement("优化模组");
        assertTrue(goals(intent(BuildIntent.Mode.EDIT, List.of(req)), Set.of("different"), true).isEmpty());
        var pin = new BuildRequirement(req.id(), "添加sodium优化模组", BuildRequirement.Kind.PROJECTS, List.of("sodium"), List.of());
        assertTrue(goal(pin).isEmpty());
    }
    private BuildIntent intent(BuildIntent.Mode mode, List<BuildRequirement> requirements) {
        return new BuildIntent(3, mode, "组合", "", BuildEnvironment.current("1.21.1", "neoforge"), new BuildIntent.CountTarget(100, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L,
                ConstraintChanges.KEEP, requirements);
    }

    @Test void releasingPerformanceRetainsNamedProjectsWithoutReactivatingSourceText() {
        var prior = new BuildRequirement("r-0000000000000001", "包含memory-mod以及优化模组", BuildRequirement.Kind.CAPABILITY,
                List.of("memory-mod"), List.of(BuildRequirement.Capability.PERFORMANCE));
        var constraints = new BuildConstraints(3, List.of("memory-mod"), List.of(), List.of(), null,
                BuildIntent.ReplacementPolicy.NONE, 2100000000L, List.of(prior));
        var changes = new ConstraintChanges(List.of(), List.of(), false, false, false, List.of(), List.of(BuildRequirement.Capability.PERFORMANCE));
        var edit = new BuildIntent(3, BuildIntent.Mode.EDIT, "取消优化", "", BuildEnvironment.current("1.21.1", "neoforge"), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L,
                changes, List.of());
        var applied = constraints.apply(edit, Set.of());
        assertEquals(List.of("memory-mod"), applied.constraints().mustHave());
        assertEquals(BuildRequirement.Kind.PROJECTS, applied.intent().requirements().getFirst().kind());
        assertTrue(goals(applied.intent(), Set.of(), false).isEmpty());
    }

    @Test void gameplayRatesWorldgenContentAndMarkdownNamesAreNotOptimizationProof() {
        assertTrue(classify("Brewing Stand now brews 2x faster, 200 ticks; UI progress remains correct").isEmpty());
        assertFalse(classify("bug fixes, compatibility improvements, and expanded Nether world generation").containsKey(Role.CHUNKS));
        assertTrue(classify("[Entity Culling Banner](https://example.invalid/image.png)").isEmpty());
        assertTrue(classify("### Entity Tick Optimization").isEmpty());
        assertTrue(classify("Using async path-tracing to hide Block-/Entities that are not visible").containsKey(Role.ENTITY_CULLING));
        assertFalse(classify("For leaves, there is an option to treat leaves as solid blocks for Entity Culling").containsKey(Role.LEAF_CULLING));
        assertTrue(classify("Optimises worldgen performance for a better gameplay experience").containsKey(Role.CHUNKS));
    }

    @Test void semanticOptimizationSearchHintDoesNotDependOnLiteralKeywordDistance() {
        var req=new BuildRequirement("r-0000000000000001","仅改善Minecraft服务端内存",BuildRequirement.Kind.CAPABILITY,
                List.of(),List.of(),List.of(),List.of(new BuildIntent.SearchIntent("optimization",100,List.of("server memory"))));
        var scoped=goal(req).orElseThrow(); assertEquals(Side.SERVER,scoped.side());
        assertEquals(Set.of(Role.MEMORY),scoped.desired()); assertFalse(scoped.broad());
        assertFalse(fact("client","Reduces memory usage",Side.CLIENT).supports(scoped));
        assertTrue(fact("server","Reduces memory usage",Side.SERVER).supports(scoped));
        var ordinary=new BuildRequirement(req.id(),"显示服务器内存信息",req.kind(),List.of(),List.of(),List.of(),
                List.of(new BuildIntent.SearchIntent("utility",100,List.of("memory monitor"))));
        assertTrue(goal(ordinary).isEmpty());
    }
}
