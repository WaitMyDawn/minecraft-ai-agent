package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.BuildIntent;
import yagen.waitmydawn.maa.service.PackSnapshotHash;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssemblyAndQuantityTest {
    @Test void insufficientFileCountIsExplicitWithoutConfusingRootsOrOverage() {
        var range = new BuildIntent.CountTarget(150, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE, 100, 200);
        assertTrue(PackQuantityPolicy.shortfall(range, 21).orElseThrow().contains("数量要求尚未满足"));
        assertTrue(PackQuantityPolicy.shortfall(range, 100).isEmpty());
        assertTrue(PackQuantityPolicy.shortfall(range, 205).isEmpty());
        assertTrue(PackQuantityPolicy.shortfall(new BuildIntent.CountTarget(150, BuildIntent.CountScope.ROOT_MODS,
                BuildIntent.Precision.APPROXIMATE), 21).isEmpty());
    }
    @org.junit.jupiter.api.Test void snapshotAcceptsOfficialSlugCharactersButNotPathsOrUnsafeVersionIds() {
        for (String slug : List.of("alexs-mobs-1.21.1", "mods-with-+'punctuation")) {
            var snapshot = new AssemblySnapshot("neoforge", "1.21.1", List.of(slug), Map.of(slug, "V1"), Map.of(slug, Set.of("CORE")));
            assertEquals(List.of(slug), snapshot.roots());
            assertEquals(Set.of("vid:V1"), snapshot.seeds(Set.of(slug)));
        }
        assertThrows(IllegalArgumentException.class, () -> new AssemblySnapshot("neoforge", "1.21.1", List.of("bad/path"), Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AssemblySnapshot("neoforge", "1.21.1", List.of("valid"), Map.of("valid", "bad.version"), Map.of()));
    }
    @Test void projectIdsAreNotReportedMissingWhenTheirCanonicalSlugsArePresent() {
        var intent = new BuildIntent(1, BuildIntent.Mode.NEW, "组合", "", new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, false),
                new BuildIntent.CountTarget(1, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT),
                List.of("PA"), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L);
        var plan = new yagen.waitmydawn.maa.model.agent.BuildExecutionPlan(intent, Set.of(), Set.of());
        assertEquals(List.of(), plan.diagnostics(Set.of("core"), Set.of("core"), true, Map.of("PA", "core")));
    }
    @Test void persistedOriginsDistinguishDependenciesFromManuallyAddedRootsAndPinsAffectHistoryIdentity() {
        var a = new AssemblySnapshot("neoforge", "1.21.1", List.of("core"), Map.of("core", "V1", "library", "V2"),
                Map.of("core", Set.of("CORE"), "library", Set.of("DEPENDENCY")));
        assertEquals(Set.of("core", "manual"), a.rootsIn(Set.of("core", "library", "manual")));
        assertEquals(Set.of("vid:V1", "vid:V2", "manual"), a.seeds(Set.of("core", "library", "manual")));
        var b = new AssemblySnapshot(a.loader(), a.mcVersion(), a.roots(), Map.of("core", "V3", "library", "V2"), a.origins());
        assertNotEquals(PackSnapshotHash.id("neoforge", "1.21.1", "core,library", "", null, null, a),
                PackSnapshotHash.id("neoforge", "1.21.1", "core,library", "", null, null, b));
        assertThrows(IllegalArgumentException.class, () -> new AssemblySnapshot(null, "1.21.1", List.of(), Map.of(), Map.of()));
    }
    private ResolutionResult resolution() {
        var graph = new DependencyGraph(); graph.allSlugs.addAll(Set.of("core", "optional", "library"));
        graph.addEdge("optional", "library");
        return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of("core", "optional"), List.of(), List.of(), 500, false);
    }
    @Test void quantityTrimsOptionalRootsAndOrphanLibrariesWithoutMutatingTheCachedGraph() {
        var original = resolution();
        var result = PackQuantityPolicy.limit(original, Set.of("core", "optional"), Set.of("core"),
                new BuildIntent.CountTarget(1, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.EXACT), List.of("optional"));
        assertEquals(Set.of("core"), result.resolution().orderedSlugs());
        assertEquals(Set.of("core", "optional", "library"), original.orderedSlugs());
        assertEquals(List.of("optional"), result.removed());
    }
    @Test void protectedOrUnknownRootsAreNeverDeletedForCountCompliance() {
        var original = resolution();
        var target = new BuildIntent.CountTarget(1, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.EXACT);
        var result = PackQuantityPolicy.limit(original, original.roots(), original.roots(), target, List.of("optional"));
        assertEquals("PROTECTED_OVERAGE", result.status()); assertEquals(original.orderedSlugs(), result.resolution().orderedSlugs());
        var unknown = PackQuantityPolicy.limit(original, Set.of("project-id"), Set.of(), target, List.of("project-id"));
        assertEquals("UNKNOWN_ROOT_IDENTITY", unknown.status()); assertSame(original, unknown.resolution());
    }
}
