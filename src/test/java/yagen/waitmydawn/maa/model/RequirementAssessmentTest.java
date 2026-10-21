package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementAssessmentTest {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void sharedWebsiteAndEvaluationCasesHaveTheProductionConclusion() throws Exception {
        try(var input=getClass().getResourceAsStream("/fixtures/requirement-assessment-cases.json")) {
            for(var test : json.readTree(input)) {
                var row=test.path("row"); var previous=row.deepCopy();
                var report=json.createObjectNode(); report.putArray("requirements").add(row);
                RequirementAssessment.refresh(report,report.deepCopy(),Set.of(),Set.of());
                assertEquals(previous.path("assessment"),row.path("assessment"),test.path("name").asText());
            }
        }
    }
    @Test void typedRowsSerializeAnExplicitAssessmentWithoutChangingTheCoverageContract() {
        var row=new RequirementCoverage.Row("r-0000000000000001","物流用途","SUPPORTING_EVIDENCE",List.of(),1,List.of(),0,"",
                List.of(),new RequirementCoverage.EvidenceState("VERIFIED",List.of()),null);
        var data=json.valueToTree(row);
        assertEquals("MET",data.path("assessment").path("outcome").asText());
        assertEquals("SUPPORTING_EVIDENCE",data.path("status").asText());
    }
    @Test void legacySuccessWithoutEvidenceStateCannotBeUpgradedToVerified() {
        var row=new RequirementCoverage.Row("r-0000000000000001","历史用途","SUPPORTING_EVIDENCE",List.of(),1,List.of(),0,"",List.of(),null,null);
        assertEquals("UNKNOWN",row.assessment().outcome());
    }
    @Test void projectIdentityLabelDoesNotClaimTheWholeSourceRequirementWasSatisfied() {
        var row = new RequirementCoverage.Row("r-0000000000000001", "构建40～70个模组并包含核心", "VERIFIED_PROJECTS",
                List.of(),2,List.of(),0,"",List.of(),new RequirementCoverage.EvidenceState("VERIFIED",List.of()),null);
        assertEquals("MET",row.assessment().outcome());
        assertEquals("点名项目已保留",row.assessment().label());
    }
    @Test void configuredDefaultRejectionIsNotReportedAsAManualChoice() {
        var previous=json.readTree("{\"requirements\":[{\"id\":\"r-0000000000000001\",\"mods\":[{\"slug\":\"default-dropped\"}]}]}");
        var current=json.readTree("{\"requirements\":[{\"id\":\"r-0000000000000001\",\"status\":\"UNVERIFIED\",\"mods\":[],\"missing\":[]}]}");
        RequirementAssessment.refresh(current,previous,Set.of("default-dropped"),Set.of("default-dropped"),Set.of("default-dropped"));
        var result=current.path("requirements").get(0).path("assessment");
        assertTrue(result.path("userRemoved").isEmpty());assertTrue(result.path("cleanupRemoved").isEmpty());
        assertEquals("default-dropped",result.path("automaticRemoved").get(0).asText());
    }
    @Test void selectionCauseRemainsSeparateFromWhetherTheRequirementWasSatisfied() {
        var previous=json.readTree("""
            {"requirements":[{"id":"r-0000000000000001","mods":[{"slug":"user-choice"},{"slug":"orphan"}],"quantity":{"projects":["user-choice","orphan"]}}]}
            """);
        var current=json.readTree("""
            {"requirements":[{"id":"r-0000000000000001","status":"UNVERIFIED","mods":[],"missing":[],"evidenceState":{"code":"NOT_SELECTED"}}],
             "ledger":[{"slug":"user-choice","status":"VERIFIED"},{"slug":"integration","partners":["orphan"],"status":"VERIFIED"}]}
            """);
        RequirementAssessment.refresh(current,previous,Set.of("user-choice"),Set.of("user-choice","orphan"));
        var outcome=current.path("requirements").get(0).path("assessment");
        assertEquals("UNKNOWN",outcome.path("outcome").asText());assertEquals("user-choice",outcome.path("userRemoved").get(0).asText());
        assertEquals("orphan",outcome.path("cleanupRemoved").get(0).asText());
        assertEquals("NOT_RETAINED",current.path("ledger").get(0).path("status").asText());
        assertEquals("RELATION_CHANGED",current.path("ledger").get(1).path("status").asText());
    }
}
