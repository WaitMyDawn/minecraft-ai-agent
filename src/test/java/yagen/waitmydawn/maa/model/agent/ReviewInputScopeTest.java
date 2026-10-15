package yagen.waitmydawn.maa.model.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ReviewInputScopeTest {
    @Test void retainedEvidenceRemainsAvailableWithoutAnyNewCandidatePermission() {
        var scope = ReviewInputScope.of(ReviewInputScope.Mode.REVIEW_MISSING_CONTENT, "neoforge", "1.21.1",
                Set.of(), Set.of("modern-ui", "jei"));
        var json = new ObjectMapper().valueToTree(scope);
        assertTrue(json.path("compatibleCandidates").isEmpty());
        assertEquals(2, json.path("retainedProjects").size());
        assertEquals("REVIEW_MISSING_CONTENT", json.path("mode").asText());
        assertEquals("NEOFORGE", json.path("environment").path("loader").asText());
        assertFalse(json.path("environment").path("changeRequested").asBoolean());
    }
    @Test void scopeIsImmutableAndPreservesSeparateCandidateAndRetainedSets() {
        var candidates = new ArrayList<>(List.of("candidate"));
        var retained = new ArrayList<>(List.of("existing"));
        var scope = ReviewInputScope.of(ReviewInputScope.Mode.SELECT_AND_REVIEW, "fabric", "1.20.1", candidates, retained);
        candidates.clear(); retained.add("other");
        assertEquals(List.of("candidate"), scope.compatibleCandidates());
        assertEquals(List.of("existing"), scope.retainedProjects());
        assertThrows(UnsupportedOperationException.class, () -> scope.compatibleCandidates().add("other"));
        assertThrows(IllegalArgumentException.class, () -> ReviewInputScope.of(ReviewInputScope.Mode.SELECT_AND_REVIEW,
                "fabric", "1.20.1", List.of("candidate", "candidate"), List.of()));
    }
}
