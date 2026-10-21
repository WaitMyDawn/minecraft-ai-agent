package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewEvidenceLedgerTest {
    @Test void recordedContradictoryDraftCannotVetoLaterValidatedRepairApprovals() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/review-contradictory-retry.json")) {
            assertNotNull(input);var fixture=json.readTree(input);var candidates=new HashSet<String>();
            fixture.path("candidates").forEach(s -> candidates.add(s.asText()));
            var ledger=new ReviewEvidenceLedger(List.of(),candidates,candidates,Map.of());
            var codec=new AgentContractCodec();
            ledger.capture(json.writeValueAsString(fixture.path("responses").get(0)));
            assertEquals(145,ledger.vetoes().size());
            var repaired=json.writeValueAsString(fixture.path("responses").get(1));ledger.capture(repaired);
            var selection=codec.readReviewSelection(repaired).select(candidates,103);
            ledger.acceptSelection(selection.decision().approved());
            assertEquals(49,ledger.vetoes().size());
            assertTrue(Collections.disjoint(ledger.vetoes(),selection.decision().approved()));
            assertEquals(96,selection.decision().approved().size());
        }
    }
    @Test void unresolvedContradictionsStayConservativeAndUnambiguousVetoesRemainBinding() {
        var ledger=ledger();
        String rejected="[{\"slug\":\"pipes\",\"reason\":\"OTHER\",\"explanation\":\"not selected\"},{\"slug\":\"storage\",\"reason\":\"CONFLICT\",\"explanation\":\"incompatible\"}]";
        ledger.capture(response(child("物流","pipes","Transfers items"),rejected,false)
                .replace("\"approved\":[\"not-a-candidate\"]","\"approved\":[\"pipes\"]"));
        assertEquals(Set.of("pipes","storage"),ledger.vetoes());
        ledger.capture(response(child("物流","pipes","Transfers items"),"[]",true));
        ledger.acceptSelection(Set.of("pipes","storage"));
        assertEquals(Set.of("storage"),ledger.vetoes());
        assertTrue(ledger.backfillForbidden());
        assertEquals(0,ledger.verify(Set.of("pipes")).counts().accepted());
        assertThrows(IllegalArgumentException.class,()->ledger.acceptSelection(Set.of("foreign")));
    }
    @Test void recordedDegradedResponsesRetainQuotedTargetsWithoutAuthorizingTheirInvalidSelections() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/review-evidence-degraded.json")) {
            assertNotNull(input); var fixture = json.readTree(input);
            var requirements = Arrays.asList(json.treeToValue(fixture.path("requirements"), BuildRequirement[].class));
            var candidates = new HashSet<String>();fixture.path("candidates").forEach(v -> candidates.add(v.asText()));
            var sources = new HashSet<>(candidates);fixture.path("retained").forEach(v -> sources.add(v.asText()));
            var facts = new HashMap<String,String>();fixture.path("facts").propertyStream().forEach(e -> facts.put(e.getKey(),e.getValue().asText()));
            var ledger = new ReviewEvidenceLedger(requirements,candidates,sources,facts);var codec = new AgentContractCodec();
            for (var response : fixture.path("responses")) {
                assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(response.asText()).select(candidates,150,sources));
                ledger.capture(response.asText());
            }
            var result = ledger.verify(Set.of());
            assertTrue(result.counts().accepted() >= 12, result.counts().toString());
            assertTrue(result.checks().values().stream().filter(c -> c.accepted() > 0).count() >= 6);
            for (var check : result.checks().values()) for (var proof : check.proofs()) if (proof.reason().equals("VERIFIED")) {
                assertTrue(sources.contains(proof.slug()));
                assertTrue(facts.get(proof.slug()).contains(proof.quote())
                        || facts.get(proof.slug()).replaceAll("(?U)\\s+"," ").contains(proof.quote().replaceAll("(?U)\\s+"," ")));
                assertFalse(ledger.vetoes().contains(proof.slug()));
            }
        }
    }
    private static final String ID = "r-0000000000000001";
    private final ObjectMapper json = new ObjectMapper();
    private BuildRequirement requirement() {
        return new BuildRequirement(ID,"物流与仓储",BuildRequirement.Kind.CAPABILITY,List.of(),List.of(),List.of(),List.of(),
                SelectionPreferences.DEFAULT,List.of(
                new RequirementCriterion("物流",BuildRequirement.Kind.CAPABILITY,List.of(),List.of(),List.of()),
                new RequirementCriterion("仓储",BuildRequirement.Kind.CAPABILITY,List.of(),List.of(),List.of())));
    }
    private String response(String children, String rejected, boolean backfill) {
        return "{\"schemaVersion\":1,\"approved\":[\"not-a-candidate\"],\"rejected\":"+rejected+",\"allowBackfill\":"+backfill
                +",\"requirements\":[{\"id\":\""+ID+"\",\"evidence\":[],\"gap\":\"\",\"criteria\":["+children+"]}]}";
    }
    private String child(String source, String slug, String quote) {
        return json.writeValueAsString(new CriterionReview(source,List.of(new RequirementReview.Contribution(slug,quote,"用途")),""));
    }
    private ReviewEvidenceLedger ledger() {
        return new ReviewEvidenceLedger(List.of(requirement()),Set.of("pipes","storage"),Set.of("pipes","storage"),
                Map.of("pipes","Transfers items with pipes","storage","Stores items in bulk"));
    }
    @Test void invalidSelectionAndMalformedSiblingDoNotEraseIndependentQuotedEvidence() {
        var ledger = ledger();
        ledger.capture(response(child("物流","pipes","Transfers items")+",{\"source\":\"仓储\",\"evidence\":\"bad\",\"gap\":\"\"}",
                "[{\"slug\":\"foreign\",\"reason\":\"OTHER\",\"explanation\":\"x\"}]",false));
        var verified = ledger.verify(Set.of());
        assertEquals("pipes",verified.reviews().getFirst().criteria().getFirst().evidence().getFirst().slug());
        assertEquals(1,verified.counts().accepted()); assertTrue(ledger.backfillForbidden());
        assertTrue(ledger.vetoes().isEmpty());
        assertTrue(ledger.issues().stream().anyMatch(i -> i.code().equals("INVALID_CRITERION")));
        assertThrows(IllegalArgumentException.class, () -> new AgentContractCodec().readReviewSelection(
                response(child("物流","pipes","Transfers items"),"[]",false)).select(Set.of("pipes"),1));
    }
    @Test void repairCanFillOneTargetWithoutErasingPriorProofOrRelaxingPriorRestrictions() {
        var ledger = ledger();
        ledger.capture(response(child("物流","pipes","Transfers items"),"[]",false));
        ledger.capture(response(child("物流","pipes","invented wording")+","+child("仓储","storage","Stores items"),"[]",true));
        var verified = ledger.verify(Set.of());
        assertEquals(2,verified.reviews().getFirst().criteria().stream().mapToInt(c -> c.evidence().size()).sum());
        assertEquals(1,verified.counts().quoteMismatch()); assertTrue(ledger.backfillForbidden());
        ledger.capture(response(child("物流","pipes","Transfers items"),
                "[{\"slug\":\"pipes\",\"reason\":\"CONFLICT\",\"explanation\":\"incompatible\"}]",true));
        assertTrue(ledger.verify(Set.of()).reviews().getFirst().criteria().getFirst().evidence().isEmpty());
        assertEquals(Set.of("pipes"),ledger.vetoes());
    }
    @Test void syntaxUnknownTargetsAndForeignEvidenceCannotBecomeProof() {
        var ledger = ledger();
        ledger.capture(response(child("不存在的目标","pipes","Transfers items"),"[]",true));
        ledger.capture(response(child("物流","foreign","Transfers items"),"[]",true));
        ledger.capture(response(child("仓储","storage","Stores items"),"[]",true).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
        assertEquals(0,ledger.verify(Set.of()).counts().accepted());
        assertTrue(ledger.issues().stream().anyMatch(i -> i.code().equals("UNREADABLE_RESPONSE")));
    }
    @Test void emptyProofFollowupPreservesPriorVerifiedEvidenceAndItsAuditTrail() {
        var first=ledger();first.capture(response(child("物流","pipes","Transfers items"),"[]",false));
        var prior=first.verify(Set.of());
        var second=ledger();second.capture(response(child("物流","pipes","invented wording"),"[]",false));
        var update=second.verify(Set.of());
        var reviews=ReviewEvidenceLedger.mergeVerifiedReviews(prior.reviews(),update.reviews());
        assertEquals("pipes",reviews.getFirst().criteria().getFirst().evidence().getFirst().slug());
        var checks=ReviewEvidenceLedger.mergeVerifiedChecks(prior.checks(),update.checks());
        var check=checks.get(requirement().criteria().getFirst().key(ID));
        assertEquals(1,check.accepted());assertEquals(2,check.proofs().size());
        assertTrue(check.proofs().stream().anyMatch(p -> p.reason().equals("VERIFIED")));
        assertTrue(check.proofs().stream().anyMatch(p -> p.reason().equals("QUOTE_MISMATCH")));
    }
    @Test void finalLedgerTracksVersionsRemovalAndChangedFactsAcrossSelectionSnapshots() {
        var ledger=ledger(); ledger.capture(response(child("物流","pipes","Transfers items"),"[]",false));
        var proof=ledger.verify(Set.of());
        var graph=new DependencyGraph();graph.allSlugs.add("pipes");
        graph.selectedVersions.put("pipes",json.createObjectNode().put("id","version-pipes").put("project_id","project-pipes"));
        var facts=Map.of("pipes","Transfers items with pipes");
        var before=RequirementCoverage.assess(List.of(requirement()),proof.reviews(),graph,Set.of("pipes"),Map.of(),Map.of(),"NONE",List.of(),proof.checks(),facts);
        assertEquals("VERIFIED",before.ledger().getFirst().status());
        assertEquals("version-pipes",before.ledger().getFirst().versionId());assertEquals(64,before.ledger().getFirst().factHash().length());
        var snapshot=new SelectionCoverage(List.of(requirement()),proof.reviews(),graph.selectedVersions,Map.of(),Map.of(),"NONE",List.of(),proof.checks(),facts,null);
        var selected=new AssemblySnapshot("neoforge","1.21.1",List.of("pipes"),Map.of("pipes","version-pipes"),Map.of());
        assertEquals(before.ledger(),snapshot.assess(selected).ledger());
        var removed=new AssemblySnapshot("neoforge","1.21.1",List.of(),Map.of(),Map.of());
        assertEquals("NOT_RETAINED",snapshot.assess(removed).ledger().getFirst().status());
        var unknownVersion=new AssemblySnapshot("neoforge","1.21.1",List.of("pipes"),Map.of("pipes","unseen-version"),Map.of());
        assertEquals("VERSION_UNVERIFIED",snapshot.assess(unknownVersion).ledger().getFirst().status());
        var changed=RequirementCoverage.assess(List.of(requirement()),proof.reviews(),graph,Set.of("pipes"),Map.of(),Map.of(),"NONE",List.of(),proof.checks(),Map.of("pipes","Only adds decoration"));
        assertEquals("FACT_CHANGED",changed.ledger().getFirst().status());
        assertTrue(changed.requirements().getFirst().criteria().getFirst().mods().isEmpty());
    }
    @Test void changedFactsAlsoInvalidateDerivedDimensionProofs() {
        var r=new BuildRequirement(ID,"丰富下界生态",BuildRequirement.Kind.CAPABILITY,List.of(),List.of(BuildRequirement.Capability.NETHER));
        String text="Overhauls Nether terrain with new biomes";
        var review=new RequirementReview(ID,List.of(new RequirementReview.Contribution("worldgen",text,"下界生成")),"");
        var proof=RequirementCoverage.verify(List.of(r),List.of(review),Map.of("worldgen",text),Set.of());
        var graph=new DependencyGraph();graph.allSlugs.add("worldgen");
        graph.selectedVersions.put("worldgen",json.createObjectNode().put("id","version-worldgen"));
        var changed=RequirementCoverage.assess(List.of(r),proof.reviews(),graph,Set.of("worldgen"),Map.of(),Map.of(),"NONE",List.of(),
                proof.checks(),Map.of("worldgen","Only adds a decorative block"));
        assertEquals("UNVERIFIED",changed.requirements().getFirst().status());
        assertTrue(changed.requirements().getFirst().evidenceState().generation().getFirst().projects().isEmpty());
        assertEquals("FACT_CHANGED",changed.ledger().getFirst().status());
    }
    @Test void fragmentRecoveryRequiresAnUnambiguousSupportedEnvelope() {
        var codec=new AgentContractCodec();String raw=response(child("物流","pipes","Transfers items"),"[]",false);
        for (String invalid : List.of(raw+"{}",raw.replace("\"schemaVersion\":1","\"schemaVersion\":4294967297"),
                raw.replace("\"schemaVersion\":1","\"schemaVersion\":\"1\""),raw.replace("\"allowBackfill\":false","\"allowBackfill\":\"false\""),
                raw.replace("\"requirements\":", "\"unrecognized\":true,\"requirements\":")))
            assertThrows(RuntimeException.class,() -> codec.readReviewFragments(invalid,Set.of("pipes")));
    }
}
