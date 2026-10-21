package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionRecoveryTest {
    @Test void applicabilityRejectedAddonCannotReturnThroughAnOptionalCompatibilityRoot() {
        var original = graph(Set.of("magic-core", "cooking-core", "compat"),
                Map.of("compat", List.of("rejected-addon"), "rejected-addon", List.of("magic-core", "unselected-content")));
        var result = SelectionRecovery.recover(original, original.roots(), Set.of("magic-core", "cooking-core"),
                Set.of("rejected-addon"), roots -> graph(roots, Map.of()));
        assertEquals(Set.of("magic-core", "cooking-core"), result.roots());
        assertEquals(List.of("compat"), result.report().removed());
        assertFalse(result.resolution().graph().allSlugs.contains("unselected-content"));
        assertTrue(SelectionRecovery.complete(result.resolution(), result.roots()));
    }

    private ResolutionResult graph(Set<String> roots, Map<String, List<String>> dependencies) {
        var graph = new DependencyGraph(); var json = new ObjectMapper();
        var projects = new TreeSet<>(roots); dependencies.forEach((s, ds) -> { projects.add(s); projects.addAll(ds); });
        for (String slug : projects) {
            graph.allSlugs.add(slug); graph.projectAliases.put("p-" + slug, slug);
            var version = json.createObjectNode(); version.put("id", "v-" + slug); version.put("project_id", "p-" + slug);
            var required = version.putArray("dependencies");
            for (String dependency : dependencies.getOrDefault(slug, List.of())) {
                graph.addEdge(slug, dependency);
                required.addObject().put("project_id", "p-" + dependency).put("dependency_type", "required");
            }
            graph.selectedVersions.put(slug, version);
        }
        return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
    }

    @Test void vetoedDependencyRemovesOnlyItsOptionalIntroducers() {
        var original = graph(Set.of("core", "optional"), Map.of("optional", List.of("vetoed")));
        var result = SelectionRecovery.recover(original, original.roots(), Set.of("core"), Set.of("p-vetoed"),
                roots -> graph(roots, Map.of()));
        assertEquals(Set.of("core"), result.roots());
        assertEquals(List.of("optional"), result.report().removed());
        assertFalse(result.resolution().graph().allSlugs.contains("vetoed"));
        assertTrue(original.graph().allSlugs.contains("optional"));
        assertEquals(1, result.report().trials());
    }

    @Test void trialCannotDropAProtectedCore() {
        var original = graph(Set.of("core", "optional"), Map.of("optional", List.of("vetoed")));
        var result = SelectionRecovery.recover(original, original.roots(), Set.of("core"), Set.of("vetoed"),
                roots -> graph(Set.of(), Map.of()));
        assertSame(original, result.resolution()); assertTrue(result.report().removed().isEmpty());
    }

    @Test void historicalDropsDoNotInvalidateTheRetainedClosure() {
        var original = graph(Set.of("core"), Map.of());
        var historical = new ResolutionResult(original.graph(), ResolutionResult.Status.INCOMPLETE, original.roots(),
                List.of(new ResolutionResult.Unresolved("missing", "discarded", ResolutionResult.Reason.NO_VERSION_FOR_ENV, "")),
                List.of(new ResolutionResult.Dropped("discarded", ResolutionResult.Reason.DEPENDENT_OF_DROPPED, "")), 500, false);
        assertFalse(historical.isComplete()); assertTrue(SelectionRecovery.complete(historical, historical.roots()));
        var result = SelectionRecovery.recover(historical, historical.roots(), Set.of("core"), Set.of(), roots -> fail());
        assertEquals(1, result.report().originalDropped().size());
    }

    @Test void removingAnOptionalAddonCannotReplaceTheProtectedCoreVersion() {
        var original = graph(Set.of("core", "optional"), Map.of("optional", List.of("vetoed")));
        var result = SelectionRecovery.recover(original, original.roots(), Set.of("core"), Set.of("vetoed"), roots -> {
            var trial = graph(roots, Map.of());
            ((tools.jackson.databind.node.ObjectNode)trial.graph().selectedVersions.get("core")).put("id", "another-version");
            return trial;
        });
        assertSame(original, result.resolution()); assertTrue(result.report().removed().isEmpty());
    }

    @Test void anAddonExactVersionMismatchDoesNotDiscardTheProtectedLatestCore() {
        var original = graph(Set.of("core", "optional"), Map.of("optional", List.of("core")));
        ((tools.jackson.databind.node.ObjectNode)original.graph().selectedVersions.get("optional").path("dependencies").get(0))
                .put("version_id", "older-core-version");
        var result = SelectionRecovery.recover(original, original.roots(), Set.of("core"), Set.of(), roots -> graph(roots, Map.of()));
        assertEquals(Set.of("core"), result.roots()); assertEquals(List.of("optional"), result.report().removed());
        assertEquals("v-core", result.resolution().graph().selectedVersions.get("core").path("id").asText());
    }

    @Test void unresolvedMetadataInvalidatesOnlyItsDependents() {
        var original = graph(Set.of("good", "uncertain", "consumer"), Map.of("consumer", List.of("uncertain")));
        original.graph().selectedVersions.remove("uncertain");
        assertEquals(Set.of("good"), SelectionRecovery.verifiedProjects(original));
        assertFalse(SelectionRecovery.complete(original, original.roots()));
    }

    @Test void missingProtectedRootIsReportedAndNeverSilentlyRemoved() {
        var original = graph(Set.of("core"), Map.of()); original.graph().allSlugs.clear(); original.graph().selectedVersions.clear();
        var result = SelectionRecovery.recover(original, Set.of("core"), Set.of("core"), Set.of(), roots -> fail());
        assertEquals(Set.of("core"), result.roots()); assertEquals(List.of("core"), result.report().missingProtected());
    }

    @Test void cancellationEscapesAndUpstreamFailurePreservesOriginalSelection() {
        var original = graph(Set.of("optional"), Map.of("optional", List.of("vetoed")));
        assertThrows(java.util.concurrent.CancellationException.class, () -> SelectionRecovery.recover(original, original.roots(), Set.of(), Set.of("vetoed"),
                roots -> { throw new java.util.concurrent.CancellationException(); }));
        var result = SelectionRecovery.recover(original, original.roots(), Set.of(), Set.of("vetoed"), roots -> { throw new IllegalStateException(); });
        assertSame(original, result.resolution()); assertTrue(result.report().removed().isEmpty());
    }
}
