package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackAddonPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private PackAddonPolicy.Project project(String id, String slug, Long downloads, String category) {
        return new PackAddonPolicy.Project(id, slug, downloads, Set.of(category), slug);
    }
    @Test void multiplierUsesMaximumPartnerDownloadsAndIsBoundedIncludingMissingAndZeroValues() {
        assertEquals(1.8, PackAddonPolicy.coefficient(100L, List.of(20L, 80L)), 0.0001);
        assertEquals(2, PackAddonPolicy.coefficient(100L, List.of(900L)));
        assertEquals(2, PackAddonPolicy.coefficient(0L, List.of(1L)));
        assertEquals(1, PackAddonPolicy.coefficient(0L, List.of(0L)));
        assertEquals(1, PackAddonPolicy.coefficient(null, List.of(10L)));
        assertEquals(1, PackAddonPolicy.coefficient(100L, Arrays.asList(10L, null)));
        assertEquals(1, PackAddonPolicy.coefficient(100L, List.of()));
    }
    @Test void onlyRetainedContentWithVersionRelationsCanBeAPartner() {
        var version = json.readTree("""
                {"dependencies":[{"project_id":"A","dependency_type":"required"},
                {"version_id":"b-version","dependency_type":"optional"},
                {"project_id":"L","dependency_type":"required"}]}
                """);
        var a = project("A", "cooking-core", 100L, "food");
        var b = project("B", "factory", 40L, "technology");
        var library = project("L", "common-library", 900000000L, "library");
        var evidence = PackAddonPolicy.assess("cooking-factory", version, Map.of("b-version", "B"), Map.of(a.slug(), a.id()), List.of(a, b, library));
        assertEquals(List.of("factory"), evidence.evidence().getFirst().partners());
        assertEquals(1.4, evidence.coefficient(), 0.0001);
        assertTrue(evidence.integration());
        assertFalse(PackAddonPolicy.assess("cooking-factory", version, Map.of("b-version", "B"), Map.of(a.slug(), a.id()), List.of(a, library)).integration());
        assertFalse(PackAddonPolicy.assess("cooking-factory", version, Map.of("b-version", "B"), Map.of(a.slug(), a.id()), List.of(b)).corePresent());
    }
    @Test void incompatibleOrUnknownConflictBlocksAutomaticSelectionAndMissingCategoriesAreNotContentProof() {
        var a = project("A", "core", 100L, "magic");
        var b = project("B", "other-content", 100L, "utility");
        var version = json.readTree("""
                {"dependencies":[{"project_id":"A","dependency_type":"required"},
                {"project_id":"B","dependency_type":"optional"},{"project_id":"B","dependency_type":"incompatible"}]}
                """);
        assertTrue(PackAddonPolicy.assess("addon", version, Map.of(), Map.of("core", "A"), List.of(a, b)).conflict());
        assertTrue(PackAddonPolicy.assess("addon", version, Map.of(), Map.of("core", "A"), List.of(a, b)).integration());
        var unknown = json.readTree("{\"dependencies\":[{\"version_id\":\"unknown\",\"dependency_type\":\"incompatible\"}]}");
        assertTrue(PackAddonPolicy.assess("addon", unknown, Map.of(), Map.of("core", "A"), List.of(a)).conflict());
        assertFalse(new PackAddonPolicy.Project("B", "unknown", 100L, Set.of(), "Unknown").content());
        assertFalse(project("L", "public-api", 100L, "utility").content());
        assertTrue(b.content());
    }
    @Test void unknownDownloadsDoNotEraseVerifiedIntegrationRelationships() {
        var version = json.readTree("""
                {"dependencies":[{"project_id":"A","dependency_type":"required"},{"project_id":"B","dependency_type":"optional"}]}
                """);
        var result = PackAddonPolicy.assess("bridge", version, Map.of(), Map.of("cooking-core", "A"),
                List.of(project("A", "cooking-core", null, "food"), project("B", "factory", 1000L, "technology")));
        assertTrue(result.integration());
        assertEquals(1, result.coefficient());
        assertEquals("UNKNOWN_DOWNLOADS", result.evidence().getFirst().downloadsStatus());
        assertNull(result.evidence().getFirst().coreDownloads());
    }
}
