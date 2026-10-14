package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.BuildIntent;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RequirementRepairTest {
    private ResolutionResult result(String... slugs) {
        var graph = new DependencyGraph();
        for (String slug : slugs) {
            graph.allSlugs.add(slug);
            graph.selectedVersions.put(slug, new ObjectMapper().createObjectNode().put("id", slug + "v").put("project_id", slug));
        }
        return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of(slugs), List.of(), List.of(), 500, false);
    }
    @Test void onePassKeepsOriginalGraphAndAddsAtMostThreeCandidates() {
        var original = result("core"); var calls = new AtomicInteger();
        var repaired = RequirementRepair.attempt(original, Set.of("core"), List.of("one", "two", "three", "four"), Set.of(), null, Set.of(), roots -> {
            calls.incrementAndGet();
            assertEquals(Set.of("core", "one", "two", "three"), roots);
            return result("core", "one", "two", "three", "library");
        });
        assertEquals("APPLIED_ONE_PASS", repaired.status());
        assertEquals(3, repaired.added().size()); assertEquals(1, calls.get());
        assertEquals(Set.of("core"), original.graph().allSlugs);
    }
    @Test void failedTrialCannotRemoveCoreOrReplaceItsSelectedVersion() {
        var original = result("core");
        var lost = RequirementRepair.attempt(original, Set.of("core"), List.of("new"), Set.of(), null, Set.of(), roots -> result("new"));
        assertSame(original, lost.resolution());
        var changed = result("core", "new");
        ((tools.jackson.databind.node.ObjectNode) changed.graph().selectedVersions.get("core")).put("id", "anotherVersion");
        var repaired = RequirementRepair.attempt(original, Set.of("core"), List.of("new"), Set.of(), null, Set.of(), roots -> changed);
        assertEquals("VERSION_CHANGE_REJECTED", repaired.status()); assertSame(original, repaired.resolution());
    }
    @Test void excludedTransitiveDependencyRejectsTheWholeTrial() {
        var original = result("core");
        var repaired = RequirementRepair.attempt(original, Set.of("core"), List.of("new"), Set.of("blocked"), null, Set.of(), roots -> result("core", "new", "blocked"));
        assertEquals("EXCLUDED_PROJECT_REJECTED", repaired.status()); assertSame(original, repaired.resolution());
    }
    @Test void countScopesAndBoundedDependencyToleranceAreEnforced() {
        var original = result("core");
        for (var scope : BuildIntent.CountScope.values()) {
            var target = new BuildIntent.CountTarget(1, scope, BuildIntent.Precision.EXACT);
            var repaired = RequirementRepair.attempt(original, Set.of("core"), List.of("new"), Set.of(), target, Set.of(), roots -> result("core", "new", "lib"));
            assertEquals("QUANTITY_REJECTED", repaired.status(), scope.toString());
        }
        var approximate = new BuildIntent.CountTarget(2, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE);
        assertEquals("APPLIED_ONE_PASS", RequirementRepair.attempt(original, Set.of("core"), List.of("new"), Set.of(), approximate, Set.of(), roots -> result("core", "new", "lib")).status());
    }
    @Test void staticUncertaintySkipsRepairAndCancellationPropagates() {
        var complete = result("core");
        var incomplete = new ResolutionResult(complete.graph(), ResolutionResult.Status.INCOMPLETE, complete.roots(), List.of(), List.of(), 500, false);
        assertEquals("STATIC_CHECK_INCOMPLETE", RequirementRepair.attempt(incomplete, Set.of("core"), List.of("new"), Set.of(), null, Set.of(), roots -> { fail("不应发起试解"); return null; }).status());
        assertThrows(java.util.concurrent.CancellationException.class, () -> RequirementRepair.attempt(complete, Set.of("core"), List.of("new"), Set.of(), null, Set.of(), roots -> { throw new java.util.concurrent.CancellationException(); }));
        assertEquals("TRIAL_UNVERIFIED", RequirementRepair.attempt(complete, Set.of("core"), List.of("new"), Set.of(), null, Set.of(), roots -> { throw new IllegalStateException("fixture"); }).status());
    }
}
