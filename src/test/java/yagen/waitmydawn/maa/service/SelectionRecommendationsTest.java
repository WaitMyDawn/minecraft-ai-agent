package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SelectionRecommendationsTest {
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
    private AssemblySnapshot assembly() {
        return new AssemblySnapshot("neoforge", "1.21.1", List.of("a", "b", "c", "d"),
                Map.of("a", "A1", "b", "B1", "c", "C1", "d", "D1"), Map.of());
    }
    private tools.jackson.databind.node.ObjectNode report(String version, String status) {
        var report = json.createObjectNode();
        report.putArray("requirements").addObject().put("id", "goal").put("status", "SUPPORTING_EVIDENCE");
        report.putArray("ledger").addObject().put("targetId", "goal").put("slug", "b")
                .put("versionId", version).put("status", status).putArray("partners");
        return report;
    }
    @Test void verifiedRequirementCanRecommendAComplementaryCandidateWithoutLockingUserChoice() {
        var groups = List.of(group("overlap", candidate("a", true, false), candidate("b", false, false)));
        var updated = SelectionRecommendations.preserveRequirements(groups, report("B1", "VERIFIED"), assembly());
        assertEquals(List.of("a", "b"), SelectionRecommendations.combinations(updated).get("overlap").slugs());
        assertTrue(updated.getFirst().candidates().stream().noneMatch(ContentOverlap.Candidate::protectedSelection));
        assertEquals(List.of("a"), SelectionRecommendations.combinations(groups).get("overlap").slugs());
    }
    @Test void staleUnverifiedOrMissingPartnerEvidenceCannotChangeDefaults() {
        var groups = List.of(group("overlap", candidate("a", true, false), candidate("b", false, false)));
        for (var report : List.of(report("old", "VERIFIED"), report("B1", "FACT_CHANGED")))
            assertEquals(List.of("a"), SelectionRecommendations.combinations(
                    SelectionRecommendations.preserveRequirements(groups, report, assembly())).get("overlap").slugs());
        var missing = report("B1", "VERIFIED");
        ((tools.jackson.databind.node.ObjectNode) missing.path("ledger").get(0)).putArray("partners").add("absent");
        assertEquals(List.of("a"), SelectionRecommendations.combinations(
                SelectionRecommendations.preserveRequirements(groups, missing, assembly())).get("overlap").slugs());
    }
    @Test void defaultsPreserveSharedIntegrationPartnersAndExplicitLocalMinimum() {
        var groups = List.of(group("first", candidate("a", true, false), candidate("b", false, false)),
                group("second", candidate("d", true, false), candidate("c", false, false)));
        var report = report("B1", "VERIFIED");
        ((tools.jackson.databind.node.ObjectNode) report.path("ledger").get(0)).putArray("partners").add("c");
        var result = SelectionRecommendations.combinations(SelectionRecommendations.preserveRequirements(groups, report, assembly()));
        assertEquals(List.of("a", "b"), result.get("first").slugs());
        assertEquals(List.of("d", "c"), result.get("second").slugs());
        var row = (tools.jackson.databind.node.ObjectNode) report.path("requirements").get(0);
        row.putObject("quantity").put("complete", true).put("min", 2).putArray("projects").add("b").add("c");
        report.withArray("ledger").addObject().put("targetId", "goal").put("slug", "c").put("versionId", "C1").put("status", "VERIFIED");
        var defaults = SelectionRecommendations.preserveRequirements(groups, report, assembly());
        assertTrue(defaults.stream().flatMap(g -> g.candidates().stream()).allMatch(ContentOverlap.Candidate::recommended));
        var reversed = new ArrayList<>(groups); Collections.reverse(reversed);
        assertEquals(SelectionRecommendations.combinations(defaults), SelectionRecommendations.combinations(
                SelectionRecommendations.preserveRequirements(reversed, report, assembly())));
    }
    @Test void childQuantityUsesOnlyVerifiedCountedProjectsAndStopsAtMinimum() {
        var groups = List.of(group("overlap", candidate("a", true, false), candidate("b", false, false), candidate("c", false, false)));
        var report = report("B1", "VERIFIED");
        var parent = (tools.jackson.databind.node.ObjectNode) report.path("requirements").get(0);
        parent.put("id", "parent");
        var child = parent.putArray("criteria").addObject().put("id", "goal").put("status", "SUPPORTING_EVIDENCE");
        child.putObject("quantity").put("min", 1).put("complete", true).putArray("projects").add("b");
        assertEquals(List.of("a", "b"), SelectionRecommendations.combinations(
                SelectionRecommendations.preserveRequirements(groups, report, assembly())).get("overlap").slugs());
        child.withObject("quantity").put("complete", false);
        assertEquals(List.of("a"), SelectionRecommendations.combinations(
                SelectionRecommendations.preserveRequirements(groups, report, assembly())).get("overlap").slugs());
    }
    private ContentOverlap.Candidate candidate(String slug, boolean recommended, boolean locked) {
        return new ContentOverlap.Candidate(slug, slug, "", "", 10, "", "", "", "", locked, recommended);
    }
    private ContentOverlap.Group group(String id, ContentOverlap.Candidate... candidates) {
        return new ContentOverlap.Group(id, id, "", List.of(candidates), true);
    }
    @Test void recommendationsCanKeepMultipleComplementaryProjectsAndPreserveSharedDecisions() {
        var result = SelectionRecommendations.combinations(List.of(
                group("generation:nether", candidate("multi",true,false),candidate("alternative",false,false)),
                group("generation:end", candidate("multi",false,false),candidate("end-core",true,false))));
        assertEquals(List.of("multi"), result.get("generation:nether").slugs());
        assertEquals(List.of("multi","end-core"), result.get("generation:end").slugs());
    }
    @Test void protectedSelectionSurvivesEvenWhenItsRecommendedFlagIsFalse() {
        var result = SelectionRecommendations.combinations(List.of(group("overlap",
                candidate("core",false,true),candidate("extra",false,false))));
        assertEquals(List.of("core"),result.get("overlap").slugs());
        assertTrue(SelectionRecommendations.combinations(List.of(new ContentOverlap.Group("unknown","","",List.of(),false))).isEmpty());
    }
    @Test void generationAndOverlapSettingsAreIndependent() {
        var preferences = new PopupPreferences(false,false,false,true);
        assertTrue(preferences.asks(group("generation:nether")));
        assertTrue(preferences.asks(group("major-worldgen:custom")));
        assertFalse(preferences.asks(group("real-animals")));
    }
}
