package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateAdmissionTest {
    @Test void developerApiDependencyDoesNotIntroduceGameplayOrCountAsAnAddon() {
        var graph = graph();
        var facts = new HashMap<>(metadata());
        facts.put("factory", new PackAddonPolicy.Project("id-factory", "factory", 100L, Set.of("magic", "utility"),
                "Spell Tools", "v-factory", "A util API for making spell addons"));
        var report = CandidateAdmission.assess(graph, Set.of("cooking", "bridge"), graph.allSlugs, facts,
                Map.of(), Map.of(), Set.of(), Map.of(), Map.of(), Set.of());
        assertTrue(report.blocked().isEmpty(), report.toString());
        var tools = report.projects().stream().filter(p -> p.slug().equals("factory")).findFirst().orElseThrow();
        assertFalse(tools.roles().contains("ADDON"));
        assertFalse(bridge(report).relation().integration());
    }

    @Test void unofferedGenerationIsDeferredWithoutExemptingExplicitRejections() {
        var selected = Map.of("generation:nether",List.of("cooking"));
        var facts = Map.of("factory","Overhauls both Overworld and Nether terrain with many new biomes");
        var unseen = CandidateAdmission.assess(graph(),graph().allSlugs,graph().allSlugs,metadata(),Map.of(),Map.of(),Set.of(),
                selected,facts,Set.of(),graph().allSlugs,Map.of("generation:nether",List.of("cooking","rejected")));
        assertTrue(unseen.projects().stream().filter(r -> r.slug().equals("factory")).noneMatch(r -> r.reasons().contains("GENERATION_NOT_SELECTED")));
        var rejected = CandidateAdmission.assess(graph(),graph().allSlugs,graph().allSlugs,metadata(),Map.of(),Map.of(),Set.of(),
                selected,facts,Set.of(),graph().allSlugs,Map.of("generation:nether",List.of("cooking","factory")));
        assertTrue(rejected.blocked().stream().anyMatch(r -> r.slug().equals("factory") && r.reasons().contains("GENERATION_NOT_SELECTED")));
    }
    private final ObjectMapper json = new ObjectMapper();
    private PackAddonPolicy.Project project(String slug, String description) {
        return new PackAddonPolicy.Project("id-"+slug,slug,100L,Set.of("adventure"),slug,"v-"+slug,description);
    }
    private DependencyGraph graph() {
        var graph = new DependencyGraph();
        for (String slug : List.of("cooking", "factory", "bridge")) {
            graph.allSlugs.add(slug);
            var version=json.createObjectNode().put("id","v-"+slug).put("project_id","id-"+slug);version.putArray("dependencies");
            graph.selectedVersions.put(slug,version);
        }
        var dependencies = ((tools.jackson.databind.node.ObjectNode)graph.selectedVersions.get("bridge")).putArray("dependencies");
        for (String slug : List.of("cooking","factory")) {
            dependencies.addObject().put("project_id","id-"+slug).put("dependency_type","required");
            graph.addEdge("bridge",slug);
        }
        return graph;
    }
    private Map<String,PackAddonPolicy.Project> metadata() {
        return Map.of("cooking",project("cooking","Cooking content"),"factory",project("factory","Factory content"),
                "bridge",project("bridge","An addon for cooking and factory"));
    }
    private CandidateAdmission.Row bridge(CandidateAdmission.Report report) {
        return report.projects().stream().filter(r -> r.slug().equals("bridge")).findFirst().orElseThrow();
    }
    @Test void ordinaryRecallAndDependencyPathsReceiveTheSameRoleAndPartnerChecks() {
        for (var roots : List.of(Set.of("cooking","factory","bridge"),Set.of("cooking","factory"))) {
            var report = CandidateAdmission.assess(graph(),roots,graph().allSlugs,metadata(),Map.of(),Map.of(),Set.of(),Map.of(),Map.of(),Set.of());
            var row=bridge(report);
            assertTrue(row.roles().contains("ADDON")); assertTrue(row.roles().contains("IN_PACK_INTEGRATION"));
            assertFalse(row.relation().evidence().isEmpty());
            assertEquals(roots.contains("bridge") ? "ROOT" : "DEPENDENCY", row.roles().contains("ROOT") ? "ROOT" : "DEPENDENCY");
            assertTrue(report.blocked().isEmpty());
        }
        var report=CandidateAdmission.assess(graph(),Set.of("bridge"),graph().allSlugs,metadata(),Map.of(),Map.of(),Set.of(),Map.of(),Map.of(),Set.of());
        assertEquals(List.of("INTRODUCES_CONTENT"),bridge(report).reasons());
    }
    @Test void vetoByProjectIdCannotBeReintroducedAsASlugDependency() {
        var report=CandidateAdmission.assess(graph(),Set.of("factory"),graph().allSlugs,metadata(),Map.of(),Map.of(),Set.of("id-cooking"),Map.of(),Map.of(),Set.of());
        assertTrue(report.blocked().stream().anyMatch(r -> r.slug().equals("cooking") && r.reasons().contains("EXPLICITLY_BLOCKED")));
    }
    @Test void declaredCoreIdsAndInferredSlugsProduceOneCanonicalRelationPerCore() {
        var report=CandidateAdmission.assess(graph(),graph().allSlugs,graph().allSlugs,metadata(),
                Map.of("bridge",Map.of("id-cooking","id-cooking")),Map.of(),Set.of(),Map.of(),Map.of(),Set.of());
        var row=bridge(report);
        assertEquals(2,row.relation().evidence().size());
        assertTrue(row.relation().evidence().stream().allMatch(e -> Set.of("cooking","factory").contains(e.core())));
        assertEquals(row,bridge(CandidateAdmission.afterSelection(report,graph().allSlugs)));
    }
    @Test void ordinaryContentIsNotAnAddonMerelyBecauseItHasContentDependencies() {
        var metadata=new HashMap<>(metadata());metadata.put("bridge",project("bridge","Adds machines with optional cooking features"));
        var report=CandidateAdmission.assess(graph(),Set.of("bridge"),graph().allSlugs,metadata,Map.of(),Map.of(),Set.of(),Map.of(),Map.of(),Set.of());
        assertFalse(bridge(report).roles().contains("ADDON"));assertTrue(report.blocked().isEmpty());
    }
    @Test void finalSelectionInvalidatesOnlyLostRelationsAndLeavesOtherProjectsIntact() {
        var before=CandidateAdmission.assess(graph(),graph().allSlugs,graph().allSlugs,metadata(),Map.of(),Map.of(),Set.of(),Map.of(),Map.of(),Set.of());
        var after=CandidateAdmission.afterSelection(before,Set.of("bridge","cooking"));
        assertEquals("NOT_RETAINED",after.projects().stream().filter(r -> r.slug().equals("factory")).findFirst().orElseThrow().status());
        assertEquals("UNVERIFIED",bridge(after).status());assertTrue(bridge(after).relation().evidence().isEmpty());
        assertEquals("ALLOWED",after.projects().stream().filter(r -> r.slug().equals("cooking")).findFirst().orElseThrow().status());
    }
    @Test void confirmedGenerationAppliesToLateDependenciesAndPreservesExistingEditContent() {
        var graph=graph();var choices=Map.of("generation:nether",List.of("chosen"));
        var facts=Map.of("factory","Overhauls the Nether with new biomes and terrain");
        var report=CandidateAdmission.assess(graph,Set.of("bridge"),graph.allSlugs,metadata(),Map.of(),Map.of(),Set.of(),choices,facts,Set.of());
        assertTrue(report.blocked().stream().anyMatch(r -> r.slug().equals("factory") && r.reasons().contains("GENERATION_NOT_SELECTED")));
        var edit=CandidateAdmission.assess(graph,graph.allSlugs,graph.allSlugs,metadata(),Map.of(),Map.of(),Set.of(),choices,facts,Set.of("factory"));
        assertFalse(edit.blocked().stream().anyMatch(r -> r.slug().equals("factory")));
    }
    @Test void missingMetadataIsVisibleWithoutInventingRoleOrProof() {
        var report=CandidateAdmission.assess(graph(),graph().allSlugs,graph().allSlugs,Map.of(),Map.of(),Map.of(),Set.of(),Map.of(),Map.of(),Set.of());
        assertTrue(report.projects().stream().allMatch(r -> r.status().equals("UNVERIFIED")));
        assertTrue(report.projects().stream().allMatch(r -> r.relation().evidence().isEmpty()));
    }
    @Test void partialDependencyMetadataDoesNotGrantFullyVerifiedAdmission() {
        var report=CandidateAdmission.assess(graph(),graph().allSlugs,graph().allSlugs,metadata(),Map.of(),
                Map.of("bridge",new AddonApplicability.Check("DEPENDENCIES_UNVERIFIED",List.of(),List.of(),"")),Set.of(),Map.of(),Map.of(),Set.of());
        assertFalse(bridge(report).relation().evidence().isEmpty());
        assertEquals("UNVERIFIED",bridge(report).status());assertTrue(report.blocked().isEmpty());
    }
}
