package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.ResolutionResult;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 固定公开选版事实的局部复现；不访问网络，不证明安装后的运行兼容。 */
class SelectedDependencyFactsTest {
    @Test void contradictoryAddonPinsAreAdvisoryWhileAGenuineTerrainConflictRemainsVisible() throws Exception {
        var json = new ObjectMapper();
        var fixture = json.readTree(Files.readString(Path.of("src/test/resources/dependency-facts/20261017-selected-constraints.json")));
        var api = mock(ModrinthApiClient.class);
        var knowledge = mock(KnowledgeDb.class);
        for (var project : fixture.path("projects")) {
            String id = project.path("id").asText(), slug = project.path("slug").asText();
            var info = json.createObjectNode().put("id", id).put("slug", slug);
            when(api.getProjectInfo(id)).thenReturn(info); when(api.getProjectInfo(slug)).thenReturn(info);
            var domain = project.path("versions");
            when(api.getCompatibleVersions(eq(id), anyString(), anyString())).thenReturn(domain);
            for (var version : domain) {
                when(api.getVersionById(version.path("id").asText())).thenReturn(version);
                if (version.path("id").asText().equals(project.path("selectedVersion").asText()))
                    when(api.getLatestVersion(eq(id), anyString(), anyString())).thenReturn(version);
            }
        }
        var roots = Set.of("farmers-delight", "ends-delight", "farmers-delight-contracts-feasts", "epicterrain", "terrablender");
        var result = new DependencyEngine(api, knowledge).resolveWithReport(roots, "neoforge", "1.21.1");
        assertFalse(result.isComplete());
        assertTrue(result.orderedSlugs().containsAll(roots));
        assertEquals("YTApg6Hl", result.graph().selectedVersions.get("ends-delight").path("id").asText());
        assertEquals("9gp7w8NC", result.graph().selectedVersions.get("farmers-delight").path("id").asText());
        var closure = yagen.waitmydawn.maa.model.DependencyClosure.capture(result.graph(), result.unresolved(), Map.of()).inspect(result.orderedSlugs());
        assertTrue(closure.issues().stream().anyMatch(i -> i.dependent().equals("farmers-delight-contracts-feasts")
                && i.code().equals("VERSION_MISMATCH") && i.severity() == yagen.waitmydawn.maa.model.DependencyClosure.Severity.WARNING));
        assertTrue(result.unresolved().stream().noneMatch(u -> u.reason() == ResolutionResult.Reason.VERSION_CONFLICT));
        assertTrue(result.unresolved().stream().anyMatch(u -> u.reason() == ResolutionResult.Reason.CONFLICT_BETWEEN_ROOTS
                && u.requiredBy().equals("epicterrain") && u.slug().equals("terrablender")));
        assertTrue(result.coordination().attempts() <= 6); assertTrue(result.coordination().queries() <= 4);
    }
    @Test void defaultRulesDoNotGeneralizeAFixedVersionSpecificSodiumIssue() throws Exception {
        var rules = new ObjectMapper().readTree(Files.readString(Path.of("maa_db/rules.json"))).path("neoforge-1.21.1");
        boolean rendererConflict = false;
        for (var rule : rules) {
            var pair = Set.of(rule.path("modA").asText(), rule.path("modB").asText());
            assertFalse(pair.equals(Set.of("sodium", "supplementaries")) && rule.path("relationType").asText().equals("CONFLICTS_WITH"));
            if (pair.equals(Set.of("sodium", "embeddium")) && rule.path("relationType").asText().equals("CONFLICTS_WITH")) rendererConflict = true;
        }
        assertTrue(rendererConflict);
    }
}
