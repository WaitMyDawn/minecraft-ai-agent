package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackAddonPolicyTest {
    @Test void exactIncompatibilityDefersOnlyWhenItsSelectedTargetVersionIsStillUnknown() {
        var version = json.readTree("{\"id\":\"bridge-v\",\"dependencies\":[{\"project_id\":\"A\",\"version_id\":\"old-a\",\"dependency_type\":\"incompatible\"}]}");
        var a = project("A", "cooking-core", 100L, "food");
        var unknownPeer = project("B", "factory", 100L, "technology");
        assertTrue(PackAddonPolicy.assess("bridge", version, Map.of(), Map.of(), List.of(a)).conflict());
        assertFalse(PackAddonPolicy.assess("bridge", version, Map.of(), Map.of(), List.of(a), false, true).conflict());
        assertTrue(PackAddonPolicy.assess("bridge", version, Map.of(), Map.of(), List.of(a.withVersion("old-a")), true).conflict());
        assertFalse(PackAddonPolicy.assess("bridge", version, Map.of(), Map.of(), List.of(a.withVersion("new-a"), unknownPeer), true).conflict());
        var wholeProject = json.readTree("{\"id\":\"bridge-v\",\"dependencies\":[{\"project_id\":\"A\",\"dependency_type\":\"incompatible\"}]}");
        assertTrue(PackAddonPolicy.assess("bridge", wholeProject, Map.of(), Map.of(), List.of(a), false, true).conflict());
    }
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
    @Test void descriptionRelationsAreDistinctFromVersionClaimsAndNeedBothSelectedEndpoints() {
        var a = project("A", "cooking-core", 100L, "food").withVersion("a1");
        var b = project("B", "magic-core", 100L, "magic").withVersion("b1");
        var addon = new PackAddonPolicy.Project("C", "bridge", 100L, Set.of("food"), "Bridge", "c1",
                "Integration between cooking-core and magic-core");
        var version = json.readTree("{\"id\":\"c1\",\"dependencies\":[]}");
        var result = PackAddonPolicy.assess("bridge", version, Map.of(), Map.of("cooking-core", "A"), List.of(a, b, addon), true);
        assertTrue(result.integration()); assertEquals("DESCRIPTION_RELATION", result.evidence().getFirst().relationSource());
        assertEquals(addon.description(), result.evidence().getFirst().quote());
        assertFalse(PackAddonPolicy.assess("bridge", version, Map.of(), Map.of("cooking-core", "A"), List.of(a, addon), true).corePresent());
        var mismatch = json.readTree("{\"id\":\"c1\",\"dependencies\":[{\"project_id\":\"A\",\"version_id\":\"old-a\",\"dependency_type\":\"required\"}]}");
        assertFalse(PackAddonPolicy.assess("bridge", mismatch, Map.of(), Map.of("cooking-core", "A"), List.of(a, b, addon), true).corePresent());
    }
    @Test void strictFinalRelationsCannotUseUnknownVersionsOrInfrastructureAsAddons() {
        var version = json.readTree("{\"id\":\"c1\",\"dependencies\":[{\"project_id\":\"A\",\"version_id\":\"a1\",\"dependency_type\":\"required\"}]}");
        var a = project("A", "core", 100L, "magic");
        assertTrue(PackAddonPolicy.assess("addon", version, Map.of(), Map.of("core", "A"), List.of(a)).corePresent());
        assertFalse(PackAddonPolicy.assess("addon", version, Map.of(), Map.of("core", "A"), List.of(a), true).corePresent());
        assertFalse(PackAddonPolicy.assess("common-api", version, Map.of(), Map.of("core", "A"), List.of(a.withVersion("a1")), true).corePresent());
    }
    @Test void genericFeaturesOptionalCompatibilityAndUnrelatedMentionsDoNotEarnIntegrationCredit() {
        var core = project("A", "cooking-core", 100L, "food").withVersion("a1");
        var partner = project("B", "factory", 1000L, "technology").withVersion("b1");
        var version = json.readTree("{\"id\":\"c1\",\"dependencies\":[{\"project_id\":\"A\",\"dependency_type\":\"required\"}]}");
        for (String description : List.of(
                "Cooking Core addon with compat features and foods. Factory offers automation.",
                "Cooking Core addon. Optional integration between Cooking Core and Factory.",
                "Cooking Core addon acts as a bridge between electricity and kinetic energy. Factory adds machines.")) {
            var addon = new PackAddonPolicy.Project("C", "extension", 100L, Set.of("food"), "Extension", "c1", description);
            var check = PackAddonPolicy.assess(addon.slug(), version, Map.of(), Map.of(core.slug(), core.id()), List.of(core, partner, addon), true);
            assertTrue(check.corePresent(), description);
            assertFalse(check.integration(), description);
            assertEquals(1, check.coefficient(), description);
        }
    }
}
