package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GenerationChoicesTest {
    @org.junit.jupiter.api.Test void laterCandidatesAreDeferredButExplicitRejectionsRemainBinding() {
        var selected = Map.of("generation:nether", List.of("chosen-nether"));
        var offered = Map.of("generation:nether", List.of("chosen-nether", "rejected-nether", "local-option"));
        String facts = "Overhauls the Overworld and Nether terrain with new biomes";
        assertTrue(GenerationChoices.allows(selected, offered, "later-cross-biomes", facts));
        assertFalse(GenerationChoices.allows(selected, offered, "rejected-nether", facts));
        assertFalse(GenerationChoices.allows(selected, offered, "local-option", "Adds a few local structures"));
        assertTrue(GenerationChoices.allows(selected, offered, "chosen-nether", facts));
        assertFalse(GenerationChoices.allows(selected, "later-cross-biomes", facts));
    }
    private final ObjectMapper json = new ObjectMapper();
    private BuildIntent intent(String source) {
        var requirement = new BuildRequirement("r-0000000000000001", source, BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(new BuildIntent.SearchIntent("worldgen", 100, List.of("nether biomes"))));
        return new BuildIntent(1, BuildIntent.Mode.NEW, "", "", BuildEnvironment.current("1.21.1", "neoforge"),
                new BuildIntent.CountTarget(100, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(),
                2100000000L, ConstraintChanges.KEEP, List.of(requirement));
    }
    @Test void compatibleCoreChoicesAreGroupedByDimensionAndLocalContentIsExcluded() {
        var api = mock(ModrinthApiClient.class);
        Map<String, JsonNode> facts = new LinkedHashMap<>();
        facts.put("nether-a", json.createObjectNode().put("title", "Nether A").put("description", "Nether expansion with custom terrain").put("downloads", 100));
        facts.put("nether-b", json.createObjectNode().put("title", "Nether B").put("description", "Overhauls the Nether terrain").put("downloads", 500));
        facts.put("end-a", json.createObjectNode().put("description", "Transforms the End terrain").put("downloads", 200));
        facts.put("end-b", json.createObjectNode().put("description", "Overhauls the End terrain").put("downloads", 300));
        facts.put("noise", json.createObjectNode().put("description", "Adds Nether structures and mobs").put("downloads", 10000));
        var response = json.createObjectNode(); var hits = response.putArray("hits");
        facts.forEach((slug, info) -> hits.add(((tools.jackson.databind.node.ObjectNode)info.deepCopy()).put("slug", slug)));
        when(api.searchPage(anyString(), eq(15), eq(0), nullable(String.class), anyString())).thenReturn(response);
        when(api.getProjectsBatch(anyCollection())).thenReturn(facts);
        when(api.getLatestCompatibleVersionBySlug(anyString(), eq("1.21.1"), eq("neoforge"))).thenReturn(json.createObjectNode().put("id", "v1"));
        var budget = new RetrievalBudget(100);
        var groups = GenerationChoices.discover(api, intent("丰富下界和末地生态"), Set.of("nether-a"), budget, () -> false);
        assertEquals(2, groups.size());
        var nether = groups.stream().filter(g -> g.original().equals("generation:nether")).findFirst().orElseThrow();
        assertEquals(List.of("nether-a", "nether-b"), nether.candidates().stream().map(ContentOverlap.Candidate::slug).toList());
        assertTrue(nether.candidates().getFirst().protectedSelection());
        assertEquals(1, nether.candidates().stream().filter(ContentOverlap.Candidate::recommended).count());
        assertTrue(groups.stream().flatMap(g -> g.candidates().stream()).noneMatch(c -> c.slug().equals("noise")));
        assertTrue(budget.snapshot().get("searchQueries") <= 4);
        assertTrue(budget.snapshot().get("candidateVersions") <= 4);
    }
    @Test void localRequestsDoNotFetchCandidatesAndCancellationPropagates() {
        var api = mock(ModrinthApiClient.class);
        assertTrue(GenerationChoices.discover(api, intent("下界只加生物，不改地形"), Set.of(), new RetrievalBudget(), () -> false).isEmpty());
        verifyNoInteractions(api);
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> GenerationChoices.discover(api, intent("丰富下界生态"), Set.of(), new RetrievalBudget(), () -> true));
    }
    @Test void richButUncertainOptionsRemainSelectableWithoutClaimingPerDimensionCoverage() {
        var api = mock(ModrinthApiClient.class);
        var facts = new LinkedHashMap<String, JsonNode>();
        facts.put("nether-core", json.createObjectNode().put("description", "Overhauls Nether terrain").put("downloads", 10));
        facts.put("end-core", json.createObjectNode().put("description", "Overhauls End terrain").put("downloads", 20));
        facts.put("multi", json.createObjectNode().put("description", "Adds 50 unique biomes to the Overworld, Nether and End").put("downloads", 100000));
        facts.put("unknown", json.createObjectNode().put("description", "Explore 100 new biomes").put("downloads", 5000));
        facts.put("sky", json.createObjectNode().put("description", "Skyblock with infinite void generation").put("downloads", 200000));
        facts.put("local", json.createObjectNode().put("description", "Adds a single Nether fortress").put("downloads", 100000));
        var response = json.createObjectNode(); var hits = response.putArray("hits");
        facts.forEach((slug, info) -> hits.add(((tools.jackson.databind.node.ObjectNode) info.deepCopy()).put("slug", slug)));
        when(api.searchPage(anyString(), eq(15), eq(0), nullable(String.class), anyString())).thenReturn(response);
        when(api.getProjectsBatch(anyCollection())).thenReturn(facts);
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(json.createObjectNode().put("id", "v1"));
        var groups = GenerationChoices.discover(api, intent("丰富下界和末地生态"), Set.of(), new RetrievalBudget(), () -> false);
        assertEquals(2, groups.size());
        for (var group : groups) {
            assertEquals(3, group.candidates().size());
            assertTrue(group.explanation().contains("尚未核实"));
            assertTrue(group.candidates().stream().filter(c -> c.slug().equals("multi") || c.slug().equals("unknown")).allMatch(c -> !c.scopeNote().isEmpty()));
            assertTrue(group.candidates().stream().filter(ContentOverlap.Candidate::recommended).allMatch(c -> c.slug().endsWith("-core")));
            assertTrue(group.candidates().stream().noneMatch(c -> c.slug().equals("sky") || c.slug().equals("local")));
        }
        verify(api, times(1)).getLatestCompatibleVersionBySlug(eq("multi"), anyString(), anyString());
        verify(api, times(1)).getLatestCompatibleVersionBySlug(eq("unknown"), anyString(), anyString());
        assertTrue(groups.getFirst().candidates().stream().filter(c -> c.slug().equals("multi")).findFirst().orElseThrow().scope().contains("主世界"));
    }
    @Test void absentOrIncompatibleChoicesDoNotInventASelectionWindow() {
        var api = mock(ModrinthApiClient.class);
        when(api.searchPage(anyString(), eq(15), eq(0), nullable(String.class), anyString())).thenReturn(json.readTree("{\"hits\":[{\"slug\":\"core\",\"description\":\"Overhauls the Nether terrain\"}]}"));
        when(api.getProjectsBatch(anyCollection())).thenReturn(Map.of("core", json.readTree("{\"description\":\"Overhauls the Nether terrain\"}")));
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(null);
        assertTrue(GenerationChoices.discover(api, intent("丰富下界生态"), Set.of(), new RetrievalBudget(), () -> false).isEmpty());
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(json.createObjectNode().put("id", "v1"));
        assertTrue(GenerationChoices.discover(api, intent("丰富下界生态"), Set.of(), new RetrievalBudget(), () -> false).isEmpty());
        when(api.searchPage(anyString(), eq(15), eq(0), nullable(String.class), anyString())).thenReturn(json.readTree("{\"hits\":[]}"));
        assertTrue(GenerationChoices.discover(api, intent("丰富下界生态"), Set.of(), new RetrievalBudget(), () -> false).isEmpty());
    }
    @Test void aSingleRichOptionWithUnknownScopeRequiresAnExplicitDecision() {
        var api = mock(ModrinthApiClient.class);
        var info = json.createObjectNode().put("slug", "unknown").put("description", "Explore 100 new biomes");
        var hits = json.createObjectNode(); hits.putArray("hits").add(info);
        when(api.searchPage(anyString(), eq(15), eq(0), nullable(String.class), anyString())).thenReturn(hits);
        when(api.getProjectsBatch(anyCollection())).thenReturn(Map.of("unknown", info));
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(json.createObjectNode().put("id", "v1"));
        var groups = GenerationChoices.discover(api, intent("丰富下界生态"), Set.of(), new RetrievalBudget(), () -> false);
        assertEquals(1, groups.size()); assertEquals(1, groups.getFirst().candidates().size());
        assertFalse(groups.getFirst().candidates().getFirst().scopeNote().isEmpty());
    }
    @Test void confirmedDimensionsRejectLaterCoreRecommendationsButKeepLocalContent() {
        var chosen = Map.of("generation:end", List.of("selected-end"));
        assertTrue(GenerationChoices.allows(chosen, "selected-end", "Overhauls the End terrain"));
        assertFalse(GenerationChoices.allows(chosen, "another-end", "Transforms the End terrain"));
        assertTrue(GenerationChoices.allows(chosen, "end-ruins", "Adds End structures"));
        assertTrue(GenerationChoices.allows(chosen, "end-island", "An overhaul of the main End Island where the dragon fight takes place!"));
        assertFalse(GenerationChoices.allows(chosen, "terrain-and-portals", "A revamp of Nether Portal mechanics and End terrain generation"));
        assertFalse(GenerationChoices.allows(chosen, "biomes", "Adds 50 unique biomes\nAdds new biomes to the End"));
        assertTrue(GenerationChoices.allows(chosen, "nether-core", "Nether expansion with custom terrain"));
    }
    @Test void directGeneratorChangesRespectConfirmedScopeWithoutBlockingLocalAdditions() {
        var chosen = Map.of("generation:nether", List.of("selected-nether"));
        assertFalse(GenerationChoices.allows(chosen, "generator", "Expanded Nether world generation"));
        assertFalse(GenerationChoices.allows(chosen, "islands", "Nether Floating Islands: creates floating island generation"));
        assertFalse(GenerationChoices.allows(chosen, "height", "Light changes to the nether generation with level design in mind"));
        assertFalse(GenerationChoices.allows(chosen, "biomes", "A stack of new biomes spread across the Overworld and Nether!"));
        assertTrue(GenerationChoices.allows(chosen, "height-limit", "Changes The Nether's maximum build height to 384 and logical height to 256"));
        assertTrue(GenerationChoices.allows(chosen, "local-biomes", "Adds 2 new Nether biomes and 16 mobs"));
        assertTrue(GenerationChoices.allows(chosen, "structures", "Changes Nether fortress generation"));
        assertTrue(GenerationChoices.allows(chosen, "integration", "Compatible with expanded Nether world generation"));
    }
}
