package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.BuildIntent;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RequiredModPreflightTest {
    private final ModrinthApiClient api = mock(ModrinthApiClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private BuildIntent intent(List<String> required) {
        return new BuildIntent(1, BuildIntent.Mode.EDIT, "说明", "", new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, false),
                null, required, List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L);
    }
    @Test void mixedCoreResultsRemainDistinctAndOrdered() {
        when(api.getLatestCompatibleVersionBySlug("jade", "1.21.1", "neoforge")).thenReturn(mapper.createObjectNode().put("id", "v"));
        when(api.getLatestCompatibleVersionBySlug("offline", "1.21.1", "neoforge")).thenThrow(new ModrinthApiClient.UnavailableException("private-marker"));
        var report = RequiredModPreflight.check(api, intent(List.of("jade", "missing", "offline")), null);
        assertEquals("BLOCKED", report.status());
        assertEquals(List.of(RequiredModsReport.Availability.AVAILABLE, RequiredModsReport.Availability.NO_COMPATIBLE_VERSION,
                RequiredModsReport.Availability.UNVERIFIED), report.entries().stream().map(RequiredModsReport.Entry::availability).toList());
        assertFalse(mapper.writeValueAsString(report).contains("private-marker"));
    }
    @Test void importedRequiredModChecksExactPinInsteadOfLatestVersion() {
        var imported = new ImportedPack("neoforge", "1.21.1", "21.1.42", List.of(new ImportedPack.File(
                "jade", "projectId", "pinnedVersion", "jade.jar", "https://cdn.modrinth.com/data/projectId/versions/pinnedVersion/jade.jar",
                "a".repeat(40), "b".repeat(128), 100, Map.of())));
        when(api.getVersionById("pinnedVersion")).thenReturn(mapper.readTree("""
                {"id":"pinnedVersion","project_id":"projectId","game_versions":["1.21.1"],"loaders":["neoforge"]}
                """));
        assertEquals("READY", RequiredModPreflight.check(api, intent(List.of("jade")), imported).status());
        verify(api, never()).getLatestCompatibleVersionBySlug("jade", "1.21.1", "neoforge");
        when(api.getVersionById("pinnedVersion")).thenReturn(mapper.createObjectNode().put("project_id", "other-project"));
        assertEquals(RequiredModsReport.Availability.UNVERIFIED,
                RequiredModPreflight.check(api, intent(List.of("jade")), imported).entries().getFirst().availability());
    }
}
