package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.cache.ModrinthCacheService;
import yagen.waitmydawn.maa.model.RetrievalBudget;
import yagen.waitmydawn.maa.service.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RetrievalBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ModrinthCacheService cache = mock(ModrinthCacheService.class);
    private final ModrinthApiClient api = mock(ModrinthApiClient.class);
    private final ChatController controller = new ChatController(mock(AiAgentService.class), cache, null,
            mock(DependencyEngine.class), api, null, null, null, null, null, null, null,
            mock(ExpansionTracker.class), null, mock(LoaderVersionService.class));

    @Test void sparseLocalRecallUsesOnlineEvidenceAndRejectsNameOnlyOrUnknownConflict() {
        when(cache.isAvailable()).thenReturn(true);
        when(cache.findAddons("core", "neoforge", 25)).thenReturn(List.of(
                new ModrinthCacheService.ModEntry("name-only", "Core addon", "", 100000, List.of(), List.of("neoforge"))));
        when(api.getProjectInfo("core")).thenReturn(mapper.readTree("{\"id\":\"core-id\",\"title\":\"Core\"}"));
        when(api.search(anyString(), eq(25), anyString())).thenReturn(mapper.readTree("""
                {"hits":[{"slug":"real-addon","downloads":1000},{"slug":"unknown-conflict","downloads":900},
                {"slug":"bridge-api","title":"Core integration API","downloads":900000000}]}
                """));
        when(api.getLatestCompatibleVersionBySlug("name-only", "1.21.1", "neoforge"))
                .thenReturn(mapper.readTree("{\"dependencies\":[]}"));
        when(api.getLatestCompatibleVersionBySlug("real-addon", "1.21.1", "neoforge"))
                .thenReturn(mapper.readTree("""
                {"dependencies":[{"version_id":"core-version","dependency_type":"required"}]}
                """));
        when(api.getVersionById("core-version")).thenReturn(mapper.readTree("{\"project_id\":\"core-id\"}"));
        when(api.getLatestCompatibleVersionBySlug("unknown-conflict", "1.21.1", "neoforge"))
                .thenReturn(mapper.readTree("""
                {"dependencies":[{"project_id":"core-id","dependency_type":"required"},
                {"version_id":"missing-ref","dependency_type":"incompatible"}]}
                """));
        var budget = new RetrievalBudget();
        List<ChatController.CandidateMod> result = ReflectionTestUtils.invokeMethod(controller, "buildAddonPool",
                "core", "neoforge", "1.21.1", Set.<String>of(), budget);
        assertEquals(List.of("real-addon"), result.stream().map(c -> c.slug).toList());
        assertEquals("REQUIRED_CORE", result.getFirst().addonEvidence);
        assertEquals(1, budget.snapshot().get("unverified"));
        verify(api).search(eq("Core"), eq(25), contains("1.21.1"));
        verify(api).prefetchVersions(anyList());
        verify(api, never()).getLatestCompatibleVersionBySlug(eq("bridge-api"), anyString(), anyString());
    }

    @Test void incompatibleTopCandidateDoesNotConsumeTheOnlyCategorySlot() {
        var old = new ChatController.CandidateMod("old-mod", "Old", "", 100000);
        old.addMatch(2, List.of("magic"));
        var compatible = new ChatController.CandidateMod("new-mod", "New", "", 1000);
        compatible.addMatch(2, List.of("magic"));
        when(api.getLatestCompatibleVersionBySlug("new-mod", "1.21.1", "neoforge"))
                .thenReturn(mapper.readTree("{\"id\":\"version\"}"));
        List<ChatController.CandidateMod> result = ReflectionTestUtils.invokeMethod(controller, "finalizePool",
                Map.of("magic", new ArrayList<>(List.of(old, compatible))), Map.of("magic", 1),
                "neoforge", "1.21.1", "fixture", new RetrievalBudget());
        assertEquals(List.of("new-mod"), result.stream().map(c -> c.slug).toList());
    }
    @Test void recentColdAddonRecallUsesPublicationAndStillRequiresRealCoreDependency() {
        var preferences = new yagen.waitmydawn.maa.model.agent.SelectionPreferences(
                yagen.waitmydawn.maa.model.agent.SelectionPreferences.Popularity.LESS_POPULAR,
                yagen.waitmydawn.maa.model.agent.SelectionPreferences.Freshness.RECENT);
        when(api.getProjectInfo("core")).thenReturn(mapper.readTree("{\"id\":\"core-id\",\"title\":\"Core\"}"));
        when(api.search(anyString(), eq(25), anyString())).thenReturn(mapper.readTree("""
                {"hits":[{"slug":"old-addon","downloads":10000000,"date_created":"2018-01-01T00:00:00Z","date_modified":"2026-10-13T00:00:00Z"}]}
                """));
        when(api.searchPage(anyString(), eq(25), eq(0), eq("newest"), contains("downloads <= 500000")))
                .thenReturn(mapper.readTree("""
                {"hits":[{"slug":"fresh-addon","downloads":100,"date_created":"2026-10-01T00:00:00Z"},
                         {"slug":"name-only","downloads":1,"date_created":"2026-10-02T00:00:00Z"}]}
                """));
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenAnswer(call -> mapper.readTree(
                call.getArgument(0).equals("name-only") ? "{\"dependencies\":[]}"
                : "{\"dependencies\":[{\"project_id\":\"core-id\",\"dependency_type\":\"required\"}]}"));
        var budget = new RetrievalBudget();
        List<ChatController.CandidateMod> result = ReflectionTestUtils.invokeMethod(controller, "buildAddonPool",
                "core", "neoforge", "1.21.1", Set.<String>of(), budget, preferences);
        assertEquals(List.of("fresh-addon", "old-addon"), result.stream().map(c -> c.slug).toList());
        assertEquals(2, budget.snapshot().get("searchQueries"));
        assertTrue(result.getFirst().preferenceFacts().contains("发布=2026-10-01"));
        assertTrue(result.getLast().preferenceFacts().contains("发布=2018-01-01"));
        assertTrue(result.getLast().preferenceFacts().contains("更新=2026-10-13"));
    }

    @Test void laterAddonPreferenceOverridesOnlyItsAxisAndCore() {
        var coldRecent = new yagen.waitmydawn.maa.model.agent.SelectionPreferences(
                yagen.waitmydawn.maa.model.agent.SelectionPreferences.Popularity.LESS_POPULAR,
                yagen.waitmydawn.maa.model.agent.SelectionPreferences.Freshness.RECENT);
        var popular = new yagen.waitmydawn.maa.model.agent.SelectionPreferences(
                yagen.waitmydawn.maa.model.agent.SelectionPreferences.Popularity.POPULAR,
                yagen.waitmydawn.maa.model.agent.SelectionPreferences.Freshness.DEFAULT);
        var old = new yagen.waitmydawn.maa.model.agent.BuildRequirement("r-0000000000000001", "较新冷门附属",
                yagen.waitmydawn.maa.model.agent.BuildRequirement.Kind.ADDONS, List.of(), List.of(), List.of("core"), List.of(), coldRecent);
        var next = new yagen.waitmydawn.maa.model.agent.BuildRequirement("r-0000000000000002", "热门附属",
                yagen.waitmydawn.maa.model.agent.BuildRequirement.Kind.ADDONS, List.of(), List.of(), List.of("core"), List.of(), popular);
        yagen.waitmydawn.maa.model.agent.SelectionPreferences actual = ReflectionTestUtils.invokeMethod(controller,
                "addonPreferences", List.of(old, next), "core");
        assertEquals(popular.popularity(), actual.popularity());
        assertEquals(coldRecent.freshness(), actual.freshness());
        assertEquals(yagen.waitmydawn.maa.model.agent.SelectionPreferences.DEFAULT,
                ReflectionTestUtils.invokeMethod(controller, "addonPreferences", List.of(old, next), "other-core"));
    }

}
