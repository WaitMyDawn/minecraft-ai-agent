package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.BuildIntent;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RequiredModAlternativesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BuildIntent.Environment ENV = new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, false);

    @Test void alexSearchOffersBothMainPortsAndRejectsMusicAndDelightAddons() {
        var api = mock(ModrinthApiClient.class);
        var response = JSON.createObjectNode();
        var hits = response.putArray("hits");
        for (String slug : List.of("alexs-mobs-1.21.1", "alexs-mobs-continued", "alexs-mobs-extra-music", "alexs-mobs-continued-delight")) {
            hits.addObject().put("slug", slug).put("title", slug);
            when(api.getProjectInfo(slug)).thenReturn(JSON.createObjectNode().put("id", slug).put("title", slug)
                    .put("downloads", slug.equals("alexs-mobs-continued") ? 200 : 100));
            when(api.getLatestCompatibleVersionBySlug(slug, "1.21.1", "neoforge"))
                    .thenReturn(JSON.createObjectNode().put("id", "V1"));
        }
        when(api.search(eq("alexs-mobs"), eq(40), anyString())).thenReturn(response);
        when(api.getProjectInfo("alexs-mobs")).thenReturn(JSON.createObjectNode().put("id", "Original"));
        var result = RequiredModAlternatives.discover(api, "alexs-mobs", ENV, Set.of());
        assertTrue(result.verified());
        assertEquals(List.of("alexs-mobs-continued", "alexs-mobs-1.21.1"), result.candidates().stream().map(RequiredModAlternatives.Candidate::slug).toList());
        verify(api, never()).getLatestCompatibleVersionBySlug(eq("alexs-mobs-extra-music"), anyString(), anyString());
        verify(api, never()).getLatestCompatibleVersionBySlug(eq("alexs-mobs-continued-delight"), anyString(), anyString());
        verify(api).search(eq("alexs-mobs"), eq(40), contains("loaders:neoforge"));
    }

    @Test void cavePortWithParenthesesAndContinuationAreRecognizedWithoutAcceptingUnrelatedSuffixes() {
        assertEquals("移植版", RequiredModAlternatives.relation("alexs-caves", "alexs-caves-(unofficial-port)", "Alex's Caves (Unofficial Port)", ""));
        assertEquals("延续版", RequiredModAlternatives.relation("alexs-mobs", "alexs-mobs-continued", "Alex's Mobs Continued", ""));
        assertNull(RequiredModAlternatives.relation("alexs-mobs", "alexs-mobs-extra-music", "Music", ""));
        assertNull(RequiredModAlternatives.relation("alexs-mobs", "alexs-mobs-continued-delight", "Delight", ""));
        assertNull(RequiredModAlternatives.relation("alexs-mobs", "alexs-mobs-skins", "Skins", ""));
        assertNull(RequiredModAlternatives.relation("iron-chests", "doggy-talents", "Doggy Talents", ""));
        assertEquals("功能替代", RequiredModAlternatives.relation("iron-chests", "storage-boxes", "Storage Boxes", "A replacement for iron chests."));
    }

    @Test void networkFailureIsNotReportedAsAnExhaustiveEmptySearch() {
        var api = mock(ModrinthApiClient.class);
        when(api.search(anyString(), anyInt(), anyString())).thenThrow(new ModrinthApiClient.UnavailableException("fixture"));
        var result = RequiredModAlternatives.discover(api, "alexs-mobs", ENV, Set.of());
        assertFalse(result.verified()); assertTrue(result.candidates().isEmpty());
    }

    @Test void candidateDependingOnOriginalIsAnAddonEvenWhenItsNameLooksLikeAPort() {
        var api = mock(ModrinthApiClient.class);
        var response = JSON.createObjectNode();
        response.putArray("hits").addObject().put("slug", "alexs-mobs-continued");
        when(api.search(anyString(), anyInt(), anyString())).thenReturn(response);
        when(api.getProjectInfo("alexs-mobs")).thenReturn(JSON.createObjectNode().put("id", "Original"));
        when(api.getProjectInfo("alexs-mobs-continued")).thenReturn(JSON.createObjectNode().put("id", "Port"));
        var version = JSON.createObjectNode().put("id", "V1");
        version.putArray("dependencies").addObject().put("dependency_type", "required").put("project_id", "Original");
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(version);
        assertTrue(RequiredModAlternatives.discover(api, "alexs-mobs", ENV, Set.of()).candidates().isEmpty());
    }
}
