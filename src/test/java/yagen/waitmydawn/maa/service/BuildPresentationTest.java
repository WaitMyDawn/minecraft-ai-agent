package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuildPresentationTest {
    @Test void advisoryDependenciesShowBothSelectedVersionsAndTheDeclaredVersionNumber() {
        var json = new ObjectMapper();
        var api = mock(ModrinthApiClient.class);
        var graph = new DependencyGraph();
        graph.allSlugs.addAll(Set.of("addon", "library"));
        var addon = json.createObjectNode().put("id", "A2").put("project_id", "PA").put("version_number", "2.0");
        addon.putArray("dependencies").addObject().put("dependency_type", "required").put("project_id", "PB").put("version_id", "B1");
        var library = json.createObjectNode().put("id", "B3").put("project_id", "PB").put("version_number", "3.0");
        library.putArray("dependencies");
        graph.selectedVersions.put("addon", addon); graph.selectedVersions.put("library", library);
        when(api.getVersionById("B1")).thenReturn(json.createObjectNode().put("version_number", "1.0"));
        var resolution = new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of("addon"), List.of(), List.of(), 500, false);
        var closure = DependencyClosure.capture(graph, List.of(), Map.of()).inspect(graph.allSlugs);
        var report = BuildPresentation.create(api, graph, resolution, closure, graph.allSlugs, Map.of());
        assertTrue(report.issues().isEmpty());
        assertEquals(List.of(new BuildPresentation.DependencyWarning("addon", "2.0", "library", "1.0", "B1", "3.0")), report.dependencyWarnings());
        when(api.getVersionById("B1")).thenThrow(new ModrinthApiClient.UnavailableException("fixture"));
        assertEquals("B1", BuildPresentation.create(api, graph, resolution, closure, graph.allSlugs, Map.of()).dependencyWarnings().getFirst().requiredVersion());
    }
    @Test void retainedProjectIsNotReportedAsDroppedAfterDependencyRepair() {
        var graph = new DependencyGraph();
        graph.allSlugs.add("core");
        var resolution = new ResolutionResult(graph, ResolutionResult.Status.INCOMPLETE, Set.of("core"),
                List.of(new ResolutionResult.Unresolved("vid:OldVersion", "addon", ResolutionResult.Reason.VERSION_CONFLICT, "")),
                List.of(new ResolutionResult.Dropped("core", ResolutionResult.Reason.VERSION_CONFLICT, "")), 500, false);
        var report = BuildPresentation.create(mock(ModrinthApiClient.class), graph, resolution,
                new DependencyClosure.Report(DependencyClosure.Status.COMPLETE, List.of()), Set.of("core", "addon"), Map.of());
        assertFalse(report.issues().stream().anyMatch(i -> i.mod().equals("core") && i.dropped()));
        assertEquals(1, report.issues().size());
    }
    @Test void exactVersionReferenceResolvesToClickableProjectWithoutPersistingIcon() {
        var json = new ObjectMapper();
        var api = mock(ModrinthApiClient.class);
        when(api.getVersionById("Az9kyOso")).thenReturn(json.createObjectNode().put("project_id", "Project1"));
        when(api.getProjectInfo("Project1")).thenReturn(json.createObjectNode().put("slug", "irons-spells-n-spellbooks").put("icon_url", "https://cdn.modrinth.com/fixture.png"));
        var resolution = new ResolutionResult(new DependencyGraph(), ResolutionResult.Status.INCOMPLETE, Set.of("addon"),
                List.of(new ResolutionResult.Unresolved("vid:Az9kyOso", "addon", ResolutionResult.Reason.VERSION_CONFLICT, "")), List.of(), 500, false);
        var closure = new DependencyClosure.Report(DependencyClosure.Status.INCOMPLETE,
                List.of(new DependencyClosure.Issue("addon", "vid:Az9kyOso", "VERSION_CONFLICT", DependencyClosure.Severity.BROKEN)));
        var report = BuildPresentation.create(api, resolution.graph(), resolution, closure, Set.of("addon"), Map.of("magic", 1));
        assertEquals(1, report.issues().size());
        assertEquals("irons-spells-n-spellbooks", report.issues().getFirst().dependency());
        assertEquals("Az9kyOso", report.issues().getFirst().versionId());
        assertFalse(report.issues().getFirst().dropped());
        assertFalse(json.writeValueAsString(report).contains("icon"));
        verify(api, times(1)).getVersionById("Az9kyOso");
    }
    @Test void unavailableDisplayMetadataDoesNotFailConstructionOrInventSlug() {
        var api = mock(ModrinthApiClient.class);
        when(api.getVersionById("Az9kyOso")).thenThrow(new ModrinthApiClient.UnavailableException("fixture"));
        var resolution = new ResolutionResult(new DependencyGraph(), ResolutionResult.Status.INCOMPLETE, Set.of(),
                List.of(new ResolutionResult.Unresolved("vid:Az9kyOso", "addon", ResolutionResult.Reason.UPSTREAM_UNAVAILABLE, "")), List.of(), 500, false);
        var report = BuildPresentation.create(api, resolution.graph(), resolution,
                new DependencyClosure.Report(DependencyClosure.Status.COMPLETE, List.of()), Set.of(), Map.of());
        assertEquals("", report.issues().getFirst().dependency());
        assertEquals("Az9kyOso", report.issues().getFirst().versionId());
    }
    @Test void oneDependencyEdgeProducesOneCardWithoutLosingReasonsOrRequiredVersions() {
        var graph = new DependencyGraph();
        var resolution = new ResolutionResult(graph, ResolutionResult.Status.INCOMPLETE, Set.of("addon"),
                List.of(new ResolutionResult.Unresolved("library", "addon", ResolutionResult.Reason.NO_VERSION_FOR_ENV, "没有当前环境选版")), List.of(), 500, false);
        var closure = new DependencyClosure.Report(DependencyClosure.Status.INCOMPLETE, List.of(
                new DependencyClosure.Issue("addon", "library", "MISSING_REQUIRED", DependencyClosure.Severity.BROKEN),
                new DependencyClosure.Issue("addon", "library", "MULTILOADER_DEPENDENCY_UNVERIFIED", DependencyClosure.Severity.UNKNOWN),
                new DependencyClosure.Issue("addon", "library", "VERSION_MISMATCH", DependencyClosure.Severity.BROKEN, "OldA"),
                new DependencyClosure.Issue("addon", "library", "VERSION_MISMATCH", DependencyClosure.Severity.BROKEN, "OldB")));
        var report = BuildPresentation.create(mock(ModrinthApiClient.class), graph, resolution, closure, Set.of("addon"), Map.of());
        assertEquals(1, report.issues().size()); var issue = report.issues().getFirst();
        assertEquals("NO_VERSION_FOR_ENV", issue.reason()); assertEquals(4, issue.codes().size());
        assertEquals(List.of("OldA", "OldB"), issue.requiredVersions()); assertEquals("", issue.versionId());
        assertEquals("没有当前环境选版", issue.detail()); assertFalse(issue.dropped());
    }
}
