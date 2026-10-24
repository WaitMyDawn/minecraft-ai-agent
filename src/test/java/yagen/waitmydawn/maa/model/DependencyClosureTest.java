package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DependencyClosureTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode version(String id, String project) {
        var version = mapper.createObjectNode().put("id", id).put("project_id", project);
        version.putArray("dependencies");
        return version;
    }
    private DependencyGraph graph() {
        var graph = new DependencyGraph();
        graph.allSlugs.addAll(Set.of("root", "library"));
        var root = version("va", "A");
        root.withArray("dependencies").addObject().put("dependency_type", "required").put("project_id", "B").put("version_id", "vb");
        graph.selectedVersions.put("root", root);
        graph.selectedVersions.put("library", version("vb", "B"));
        graph.addEdge("root", "library");
        return graph;
    }
    @Test void selectionIsCheckedAfterExclusionsAndOmittingDependentRemovesItsObligations() {
        var snapshot = DependencyClosure.capture(graph(), List.of(), Map.of());
        assertEquals(DependencyClosure.Status.COMPLETE, snapshot.inspect(Set.of("root", "library")).status());
        var broken = snapshot.inspect(Set.of("root"));
        assertEquals(DependencyClosure.Status.INCOMPLETE, broken.status());
        assertTrue(broken.issues().stream().anyMatch(i -> i.dependency().equals("library") && i.code().equals("MISSING_REQUIRED")));
        assertEquals(DependencyClosure.Status.COMPLETE, snapshot.inspect(Set.of("library")).status());
    }
    @Test void exactVersionDifferencesAreAdvisoryAndVersionOnlyReferencesResolve() {
        var graph = graph();
        var dependencies = ((ObjectNode) graph.selectedVersions.get("root")).withArray("dependencies");
        dependencies.removeAll();
        dependencies.addObject().put("dependency_type", "required").put("version_id", "vb");
        assertEquals(DependencyClosure.Status.COMPLETE, DependencyClosure.capture(graph, List.of(), Map.of()).inspect(Set.of("root", "library")).status());
        dependencies.removeAll();
        dependencies.addObject().put("dependency_type", "required").put("project_id", "B").put("version_id", "other");
        assertTrue(DependencyClosure.capture(graph, List.of(), Map.of()).inspect(Set.of("root", "library")).issues()
                .stream().anyMatch(i -> i.code().equals("VERSION_MISMATCH")));
        var report = DependencyClosure.capture(graph, List.of(), Map.of()).inspect(Set.of("root", "library"));
        assertEquals(DependencyClosure.Status.COMPLETE, report.status());
        assertEquals(DependencyClosure.Severity.WARNING, report.issues().getFirst().severity());
        dependencies.removeAll();
        dependencies.addObject().put("dependency_type", "required").put("version_id", "other");
        var aliased = DependencyClosure.capture(graph, List.of(), Map.of("vid:other", "library"));
        assertEquals("library", aliased.inspect(Set.of("root", "library")).issues().getFirst().dependency());
        assertEquals(DependencyClosure.Status.INCOMPLETE, aliased.inspect(Set.of("root")).status());
    }
    @Test void prunedMissingDependenciesRemainVisibleAndNetworkUncertaintyIsNotAbsence() {
        var graph = graph();
        graph.allSlugs.remove("library");
        graph.selectedVersions.remove("library");
        graph.depsOf.clear();
        var failure = new ResolutionResult.Unresolved("library", "root", ResolutionResult.Reason.NO_VERSION_FOR_ENV, "");
        assertEquals(DependencyClosure.Status.INCOMPLETE, DependencyClosure.capture(graph, List.of(failure), Map.of()).inspect(Set.of("root")).status());
        var unknown = new ResolutionResult.Unresolved("library", "root", ResolutionResult.Reason.UPSTREAM_UNAVAILABLE, "");
        var report = DependencyClosure.capture(graph, List.of(unknown), Map.of("B", "library")).inspect(Set.of("root"));
        assertEquals(DependencyClosure.Status.UNVERIFIED, report.status());
        assertTrue(report.issues().stream().allMatch(i -> i.severity() == DependencyClosure.Severity.UNKNOWN));
    }
    @Test void optionalDependenciesAreIgnoredAndMissingMetadataIsUnverified() {
        var graph = graph();
        ((ObjectNode) graph.selectedVersions.get("library")).withArray("dependencies").addObject()
                .put("dependency_type", "optional").put("project_id", "absent");
        var snapshot = DependencyClosure.capture(graph, List.of(), Map.of());
        graph.selectedVersions.clear();
        assertEquals(DependencyClosure.Status.COMPLETE, snapshot.inspect(Set.of("library")).status());
        assertEquals(DependencyClosure.Status.UNVERIFIED, DependencyClosure.capture(graph, List.of(), Map.of()).inspect(Set.of("library")).status());
    }
    @Test void cyclesAreCheckedWithoutTraversalAndFileOnlyReferencesAreUnverified() {
        var graph = graph();
        graph.addEdge("library", "root");
        assertEquals(DependencyClosure.Status.COMPLETE, DependencyClosure.capture(graph, List.of(), Map.of()).inspect(Set.of("root", "library")).status());
        ((ObjectNode) graph.selectedVersions.get("library")).withArray("dependencies").addObject()
                .put("dependency_type", "required").put("file_name", "external.jar");
        assertEquals(DependencyClosure.Status.UNVERIFIED, DependencyClosure.capture(graph, List.of(), Map.of()).inspect(Set.of("root", "library")).status());
    }
    @Test void oversizedClosureCannotBeSilentlyTruncatedToComplete() {
        var graph = new DependencyGraph();
        for (int i = 0; i < 250; i++) {
            String slug = "node-" + i;
            graph.allSlugs.add(slug);
            graph.selectedVersions.put(slug, version("v-" + i, "p-" + i));
        }
        for (int i = 0; i < 50; i++) for (int j = 50; j < 250; j++) graph.addEdge("node-" + i, "node-" + j);
        var closure = DependencyClosure.capture(graph, List.of(), Map.of());
        assertEquals(8192, closure.requirements().size());
        assertEquals(DependencyClosure.Status.UNVERIFIED, closure.inspect(graph.allSlugs).status());
        assertTrue(closure.inspect(graph.allSlugs).issues().stream().anyMatch(i -> i.code().equals("AUDIT_BUDGET_EXCEEDED")));
    }
    @Test void aNetworkFailureCannotHideAnotherMissingDependencyOfTheSameProject() {
        var graph = graph(); graph.allSlugs.remove("library"); graph.selectedVersions.remove("library"); graph.depsOf.clear();
        ((ObjectNode) graph.selectedVersions.get("root")).withArray("dependencies").addObject()
                .put("dependency_type", "required").put("project_id", "Other");
        var report = DependencyClosure.capture(graph, List.of(new ResolutionResult.Unresolved("Other", "root",
                ResolutionResult.Reason.UPSTREAM_UNAVAILABLE, "network")), Map.of()).inspect(Set.of("root"));
        assertEquals(DependencyClosure.Status.INCOMPLETE, report.status());
        assertTrue(report.issues().stream().anyMatch(i -> i.dependency().equals("B") && i.code().equals("MISSING_REQUIRED")));
        assertFalse(report.issues().stream().anyMatch(i -> i.dependency().equals("Other") && i.code().equals("MISSING_REQUIRED")));
    }
    @Test void anExplainedMissingEdgeDoesNotAlsoProduceGenericMissingRequired() {
        var graph = graph(); graph.allSlugs.remove("library"); graph.selectedVersions.remove("library"); graph.depsOf.clear();
        var report = DependencyClosure.capture(graph, List.of(new ResolutionResult.Unresolved("B", "root",
                ResolutionResult.Reason.NO_VERSION_FOR_ENV, "no compatible version")), Map.of()).inspect(Set.of("root"));
        assertEquals(1, report.issues().size()); assertEquals("NO_VERSION_FOR_ENV", report.issues().getFirst().code());
    }
    @Test void ambiguousMultiLoaderDependenciesRemainRequiredAndIncomplete() {
        var graph = graph(); graph.allSlugs.remove("library"); graph.selectedVersions.remove("library"); graph.depsOf.clear();
        ((ObjectNode) graph.selectedVersions.get("root")).putArray("loaders").add("fabric").add("neoforge").add("quilt");
        var closure = DependencyClosure.capture(graph, List.of(new ResolutionResult.Unresolved("B", "root",
                ResolutionResult.Reason.NO_VERSION_FOR_ENV, "no compatible version")), Map.of());
        assertFalse(closure.requirements().isEmpty());
        var report = closure.inspect(Set.of("root"));
        assertEquals(DependencyClosure.Status.INCOMPLETE, report.status());
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("MULTILOADER_DEPENDENCY_UNVERIFIED")));
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("NO_VERSION_FOR_ENV") && i.severity() == DependencyClosure.Severity.BROKEN));
    }
}
