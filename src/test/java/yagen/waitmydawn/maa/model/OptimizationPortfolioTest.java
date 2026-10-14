package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static yagen.waitmydawn.maa.model.OptimizationPortfolio.*;

class OptimizationPortfolioTest {
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
