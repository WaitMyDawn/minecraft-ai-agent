package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class VersionCoordinationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModrinthApiClient api = mock(ModrinthApiClient.class);
    private final DependencyEngine engine = new DependencyEngine(api, mock(KnowledgeDb.class));
    private JsonNode version(String id, String project, String... dependencies) {
        var v = json.createObjectNode().put("id", id).put("project_id", project);
        v.putArray("game_versions").add("1.21.1"); v.putArray("loaders").add("neoforge");
        var deps = v.putArray("dependencies");
        for (String d : dependencies) {
            var node = deps.addObject().put("dependency_type", "required");
            node.put(d.startsWith("vid:") ? "version_id" : "project_id", d.startsWith("vid:") ? d.substring(4) : d);
        }
        when(api.getVersionById(id)).thenReturn(v);
        return v;
    }
    private void project(String slug, String id, JsonNode latest) {
        var info = json.createObjectNode().put("id", id).put("slug", slug);
        when(api.getProjectInfo(slug)).thenReturn(info); when(api.getProjectInfo(id)).thenReturn(info);
        when(api.getLatestVersion(eq(id), eq("1.21.1"), anyString())).thenReturn(latest);
    }
    @Test void sameWorkflowTrialReusesVerifiedFactsWithoutRefetchingOriginalVersions() {
        var old = version("A1", "PA", "PB"); var b = version("B1", "PB");
        project("a", "PA", version("A2", "PA")); project("b", "PB", b);
        project("new", "PN", version("N1", "PN"));
        var originalText = old.toString();
        var r = engine.resolveUsingFacts(Set.of("a", "new"), "neoforge", "1.21.1", null, Map.of("a", old, "b", b));
        assertTrue(r.isComplete(), r.summary());
        assertEquals("A1", r.graph().selectedVersions.get("a").path("id").asText());
        assertEquals(Set.of("a", "b", "new"), r.graph().allSlugs);
        assertEquals(originalText, old.toString());
        verify(api, never()).getVersionById("A1"); verify(api, never()).getVersionById("B1");
        verify(api, never()).getLatestVersion(eq("PA"), anyString(), anyString());
    }
    @Test void incompatibleDeclaredVersionDoesNotDowngradeEitherProject() {
        var bad = version("Bad", "PB"); ((tools.jackson.databind.node.ObjectNode) bad).putArray("game_versions").add("1.20.1");
        var latest = version("A2", "PA", "vid:Bad"); var older = version("A1", "PA", "PB");
        project("a", "PA", latest); project("b", "PB", version("B1", "PB"));
        when(api.getCompatibleVersions(eq("PA"), anyString(), anyString())).thenReturn(json.createArrayNode().add(latest).add(older));
        var r = engine.resolveWithReport(Set.of("a"), "neoforge", "1.21.1");
        assertTrue(r.isComplete(), r.summary()); assertEquals(Set.of("a", "b"), r.orderedSlugs());
        assertEquals("A2", r.graph().selectedVersions.get("a").path("id").asText());
        assertEquals("B1", r.graph().selectedVersions.get("b").path("id").asText());
        assertEquals(0, r.coordination().attempts());
        verify(api, never()).getCompatibleVersions(anyString(), anyString(), anyString());
    }
    @Test void latestDependencyContributesItsOwnEdgesWithoutPullingOldDependencies() {
        project("a", "PA", version("A1", "PA", "vid:B1"));
        project("b", "PB", version("B2", "PB", "PD")); version("B1", "PB", "PE");
        project("d", "PD", version("D1", "PD")); project("e", "PE", version("E1", "PE"));
        var r = engine.resolveWithReport(Set.of("a", "b"), "neoforge", "1.21.1");
        assertTrue(r.isComplete(), r.summary()); assertEquals(Set.of("a", "b", "d"), r.orderedSlugs());
        assertEquals(Set.of("d"), r.graph().getDependenciesOf("b"));
    }
    @Test void anImportedPinIsNotOverwrittenByAnotherRootsExactDependency() {
        project("a", "PA", version("A1", "PA", "vid:B1"));
        project("b", "PB", version("B2", "PB")); version("B1", "PB");
        var r = engine.resolveWithReport(Set.of("a", "vid:B2"), "neoforge", "1.21.1");
        assertTrue(r.isComplete()); assertEquals("B2", r.graph().selectedVersions.get("b").path("id").asText());
        assertTrue(r.unresolved().isEmpty());
        verify(api, never()).getCompatibleVersions(eq("PB"), anyString(), anyString());
    }
    @Test void snapshotAliasesDoNotExhaustTheNodeBudgetForAlreadyResolvedProjects() {
        var tiny = new DependencyEngine(api, mock(KnowledgeDb.class), 2);
        project("a", "PA", version("A1", "PA", "PB")); project("b", "PB", version("B1", "PB"));
        var r = tiny.resolveWithReport(Set.of("vid:A1", "vid:B1"), "neoforge", "1.21.1");
        assertTrue(r.isComplete(), r.summary()); assertFalse(r.budgetExhausted());
    }
    @Test void unresolvableDependencyIdentityRemainsIncomplete() {
        var bad = version("Bad", "PBad"); ((tools.jackson.databind.node.ObjectNode) bad).putArray("game_versions").add("1.20.1");
        for (String slug : List.of("a", "b")) {
            String id = "P" + slug;
            var versions = json.createArrayNode();
            for (int i = 4; i >= 1; i--) versions.add(version(slug + i, id, "vid:Bad"));
            project(slug, id, versions.get(0)); when(api.getCompatibleVersions(eq(id), anyString(), anyString())).thenReturn(versions);
        }
        var r = engine.resolveWithReport(Set.of("a", "b"), "neoforge", "1.21.1");
        assertFalse(r.isComplete()); assertTrue(r.coordination().attempts() <= 6);
        assertEquals(Set.of("a", "b"), r.orderedSlugs());
    }
    @Test void missingDependencyIdentityDoesNotProduceACompletePack() {
        var bad = version("Bad", "PBad"); ((tools.jackson.databind.node.ObjectNode) bad).putArray("game_versions").add("1.20.1");
        for (String slug : List.of("a", "b")) {
            var latest = version(slug + "2", "P" + slug, "vid:Bad"); var older = version(slug + "1", "P" + slug);
            project(slug, "P" + slug, latest);
            when(api.getCompatibleVersions(eq("P" + slug), anyString(), anyString())).thenReturn(json.createArrayNode().add(latest).add(older));
        }
        var r = engine.resolveWithReport(Set.of("a", "b"), "neoforge", "1.21.1");
        assertFalse(r.isComplete(), r.summary());
        assertEquals("a2", r.graph().selectedVersions.get("a").path("id").asText());
        assertEquals("b2", r.graph().selectedVersions.get("b").path("id").asText());
    }
    @Test void aHardRootPinWinsOverAnAliasRegardlessOfCompletionOrder() {
        project("a", "PA", version("A1", "PA")); version("A2", "PA");
        for (int i = 0; i < 8; i++) {
            var r = engine.resolveWithReport(Set.of("a", "vid:A2"), "neoforge", "1.21.1");
            assertTrue(r.isComplete(), r.summary());
            assertEquals("A2", r.graph().selectedVersions.get("a").path("id").asText());
        }
    }
    @Test void aVersionSpecificConflictDoesNotRejectADifferentInstalledVersion() {
        var a = version("A1", "PA", "PB");
        ((tools.jackson.databind.node.ArrayNode) a.path("dependencies")).addObject()
                .put("dependency_type", "incompatible").put("project_id", "PB").put("version_id", "B0");
        project("a", "PA", a); project("b", "PB", version("B1", "PB"));
        var r = engine.resolveWithReport(Set.of("a", "b"), "neoforge", "1.21.1");
        assertTrue(r.isComplete(), r.summary());
        verify(api, never()).getCompatibleVersions(anyString(), anyString(), anyString());
    }
    @Test void contradictoryExactRequirementsKeepAllLatestVersions() {
        var a = version("A1", "PA", "vid:C2"); project("a", "PA", a);
        var latest = version("B2", "PB", "vid:C1");
        var compatible = version("B1", "PB", "PC");
        project("b", "PB", latest);
        project("core", "PC", version("C2", "PC")); version("C1", "PC");
        var generic = version("G1", "PG", "PC");
        ((tools.jackson.databind.node.ObjectNode) generic.path("dependencies").get(0)).putNull("version_id");
        project("generic", "PG", generic);
        when(api.getCompatibleVersions(eq("PA"), anyString(), anyString())).thenReturn(json.createArrayNode().add(a));
        when(api.getCompatibleVersions(eq("PB"), anyString(), anyString())).thenReturn(json.createArrayNode().add(latest).add(compatible));
        var r = engine.resolveWithReport(Set.of("a", "b", "core", "generic"), "neoforge", "1.21.1");
        assertTrue(r.isComplete(), r.summary());
        assertEquals("B2", r.graph().selectedVersions.get("b").path("id").asText());
        assertEquals("C2", r.graph().selectedVersions.get("core").path("id").asText());
        assertTrue(r.coordination().attempts() <= 6); assertTrue(r.coordination().queries() <= 4);
        verify(api, never()).getCompatibleVersions(eq("PG"), anyString(), anyString());
    }
    @Test void theOtherSideOfAConflictCanChangeButAHardPinCannot() {
        var a = version("A1", "PA");
        ((tools.jackson.databind.node.ArrayNode) a.path("dependencies")).addObject().put("dependency_type", "incompatible").put("version_id", "B2").put("project_id", "PB");
        project("a", "PA", a);
        var latest = version("B2", "PB"); var compatible = version("B1", "PB"); project("b", "PB", latest);
        when(api.getCompatibleVersions(eq("PA"), anyString(), anyString())).thenReturn(json.createArrayNode().add(a));
        when(api.getCompatibleVersions(eq("PB"), anyString(), anyString())).thenReturn(json.createArrayNode().add(latest).add(compatible));
        var r = engine.resolveWithReport(Set.of("a", "b"), "neoforge", "1.21.1");
        assertTrue(r.isComplete(), r.summary()); assertEquals("B1", r.graph().selectedVersions.get("b").path("id").asText());
        clearInvocations(api);
        var pinned = engine.resolveWithReport(Set.of("a", "vid:B2"), "neoforge", "1.21.1");
        assertFalse(pinned.isComplete()); assertEquals("B2", pinned.graph().selectedVersions.get("b").path("id").asText());
        verify(api, never()).getCompatibleVersions(eq("PB"), anyString(), anyString());
    }
}
