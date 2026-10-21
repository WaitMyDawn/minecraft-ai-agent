package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateProvenanceTest {
    @Test void canonicalSourcesAndStagesExplainHowAnOfferedCandidateActuallyEnteredThePack() {
        var ledger=new CandidateProvenance();
        ledger.offer("Project1",CandidateProvenance.Source.EXPLICIT);
        ledger.checkpoint("INPUT",Set.of("Project1"),Map.of());
        ledger.offer("core",CandidateProvenance.Source.RETRIEVAL);ledger.offer("core",CandidateProvenance.Source.BACKFILL);
        ledger.offer("unused",CandidateProvenance.Source.RETRIEVAL);ledger.decide("unused","CRITIC_VETO");
        ledger.canonicalize(Map.of("Project1","core"));
        var graph=new DependencyGraph();graph.allSlugs.addAll(Set.of("core","library"));graph.addEdge("core","library");
        var json=new ObjectMapper();
        graph.selectedVersions.put("core",json.createObjectNode().put("id","version-core"));
        graph.selectedVersions.put("library",json.createObjectNode().put("id","version-library"));
        ledger.checkpoint("RESOLUTION",graph.allSlugs,graph.selectedVersions);
        var report=ledger.report(graph,graph.allSlugs,Set.of("core"),Map.of("core",Set.of("CORE")),Map.of("core",Set.of("requirement")));
        assertEquals(3,report.projects().size());
        var core=report.projects().stream().filter(r -> r.slug().equals("core")).findFirst().orElseThrow();
        assertEquals(Set.of(CandidateProvenance.Source.EXPLICIT,CandidateProvenance.Source.RETRIEVAL,CandidateProvenance.Source.BACKFILL),core.sources());
        assertEquals(Set.of("CORE"),core.protection());assertEquals(List.of("requirement"),core.requirements());
        var dependency=report.projects().stream().filter(r -> r.slug().equals("library")).findFirst().orElseThrow();
        assertEquals(List.of("core"),dependency.requiredBy());assertFalse(dependency.root());
        var unused=report.projects().stream().filter(r -> r.slug().equals("unused")).findFirst().orElseThrow();
        assertFalse(unused.retained());assertEquals(List.of("CRITIC_VETO"),unused.decisions());
        assertTrue(report.changes().stream().anyMatch(c -> c.slug().equals("core") && c.action().equals("VERSION_RESOLVED")));
        assertFalse(report.changes().stream().anyMatch(c -> c.slug().equals("core") && c.action().equals("REMOVED")));
        var closure=new DependencyClosure(Map.of("core","version-core","library","version-library"),
                List.of(new DependencyClosure.Requirement("core","library","")),List.of());
        var selected=new AssemblySnapshot("neoforge","1.21.1",List.of(),Map.of(),Map.of());
        var after=CandidateProvenance.afterSelection(report,selected,closure);
        assertTrue(after.projects().stream().noneMatch(CandidateProvenance.Entry::retained));
        assertEquals(2,after.changes().stream().filter(c -> c.stage().equals("USER_SELECTION")).count());
        assertEquals(after,CandidateProvenance.afterSelection(after,selected,closure));
    }
    @Test void largeRecallListsCannotCreateAnUnboundedRequestJournal() {
        var ledger=new CandidateProvenance();
        for(int i=0;i<3000;i++)ledger.offer("candidate-"+i,CandidateProvenance.Source.RETRIEVAL);
        var report=ledger.report(new DependencyGraph(),Set.of(),Set.of(),Map.of(),Map.of());
        assertTrue(report.projects().size() <= 2000);assertTrue(report.truncated());
        assertTrue(new ObjectMapper().writeValueAsString(report).length() <= 131072);
    }
    @Test void storageBudgetPrioritizesTheFiveHundredFinalFilesOverUnusedRecallAndOldChanges() {
        var ledger=new CandidateProvenance();var graph=new DependencyGraph();var json=new ObjectMapper();
        for(int i=0;i<2000;i++)ledger.offer("mod-"+i,CandidateProvenance.Source.RETRIEVAL);
        for(int i=0;i<500;i++) {
            String slug="mod-"+i;graph.allSlugs.add(slug);graph.selectedVersions.put(slug,json.createObjectNode().put("id","version-"+i));
        }
        ledger.checkpoint("RESOLUTION",graph.allSlugs,graph.selectedVersions);
        var report=ledger.report(graph,graph.allSlugs,graph.allSlugs,Map.of(),Map.of());
        assertEquals(500,report.projects().stream().filter(CandidateProvenance.Entry::retained).count());
        assertTrue(report.truncated());assertTrue(json.writeValueAsString(report).length() <= 131072);
    }
}
