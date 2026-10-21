package yagen.waitmydawn.maa.model.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ReviewResponseTest {
    @Test void repeatedApprovalsAreIdempotentButVetoesAndOrderRemainAuthoritative() {
        var codec = new AgentContractCodec();
        String raw = """
                {"schemaVersion":1,"approved":["second","blocked","first","second","blocked"],
                 "rejected":[{"slug":"blocked","reason":"OFF_TOPIC","explanation":"unrelated"}],"allowBackfill":false,"requirements":[]}
                """;
        var response = codec.readReviewResponse(raw);
        assertEquals(List.of("second", "blocked", "first"), response.approved());
        var selection = response.select(Set.of("first", "second", "blocked"), 1);
        assertEquals(List.of("second", "first"), selection.decision().approved());
        assertFalse(selection.decision().allowBackfill());
        assertEquals(1, selection.overlapRemoved());
        assertEquals(1, selection.excessApproved());
        assertThrows(IllegalArgumentException.class, () -> response.select(Set.of("first"), 5));
        assertDoesNotThrow(() -> codec.requireSameReviewSelection(raw,
                raw.replace("\"second\",\"blocked\",\"first\",\"second\",\"blocked\"", "\"second\",\"blocked\",\"first\"")));
    }

    @Test void repeatedApprovalsCannotHideInvalidEntriesOversizeOrOtherContractViolations() {
        var codec = new AgentContractCodec();
        String base = "{\"schemaVersion\":1,\"approved\":[\"chosen\",\"chosen\"],\"rejected\":[],\"allowBackfill\":false,\"requirements\":[]}";
        for (String invalid : List.of(
                base.replace("\"chosen\",\"chosen\"", "\"chosen\",null,\"chosen\""),
                base.replace("\"chosen\",\"chosen\"", "\"chosen\",5,\"chosen\""),
                base.replace("\"chosen\",\"chosen\"", "\"bad/slug\",\"bad/slug\""),
                base.replace("\"approved\":", "\"approved\":[],\"approved\":"),
                base.replace("\"rejected\":[]", "\"rejected\":[{\"slug\":\"chosen\",\"reason\":\"OFF_TOPIC\",\"explanation\":\"x\"},{\"slug\":\"chosen\",\"reason\":\"OFF_TOPIC\",\"explanation\":\"different\"}]"),
                base.replace("\"requirements\":[]", "\"requirements\":\"invalid\""),
                base.replace("\"allowBackfill\":false", "\"allowBackfill\":\"false\""),
                base.replace("\"schemaVersion\":1", "\"schemaVersion\":999"), base + "{}"))
            assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(invalid));
        var json = new tools.jackson.databind.ObjectMapper();
        var oversized = (tools.jackson.databind.node.ObjectNode) json.readTree(base);
        oversized.set("approved", json.valueToTree(java.util.Collections.nCopies(501, "chosen")));
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(oversized.toString()));
    }

    @Test void identicalRejectionsAreIdempotentWhileConflictingDetailsAndBoundsStayStrict() {
        var codec = new AgentContractCodec();
        String rejection = "{\"slug\":\"blocked\",\"reason\":\"OFF_TOPIC\",\"explanation\":\"unrelated\"}";
        String raw = "{\"schemaVersion\":1,\"approved\":[\"blocked\",\"chosen\"],\"rejected\":["+rejection+","+rejection+"],\"allowBackfill\":false,\"requirements\":[]}";
        var response = codec.readReviewResponse(raw).select(Set.of("blocked","chosen"),1).decision();
        assertEquals(List.of("chosen"), response.approved()); assertEquals(1, response.rejected().size());
        assertFalse(response.allowBackfill());
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(raw).select(Set.of("chosen"),1));
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(raw.replace(rejection+","+rejection,
                String.join(",", java.util.Collections.nCopies(501,rejection)))));
    }

    @Test void reportOnlyRepairCannotChangeAnySelectionPermission() {
        var json = new tools.jackson.databind.ObjectMapper();
        String original = """
                {"schemaVersion":1,"approved":["chosen","blocked"],"rejected":[{"slug":"blocked","reason":"OFF_TOPIC","explanation":"无关"}],
                 "allowBackfill":false,"requirements":[{"malformed":"report"}]}
                """;
        var codec = new AgentContractCodec();
        var repaired = (tools.jackson.databind.node.ObjectNode) json.readTree(original);
        repaired.putArray("requirements");
        assertDoesNotThrow(() -> codec.requireSameReviewSelection(original, repaired.toString()));
        for (String field : List.of("approved", "rejected", "allowBackfill")) {
            var changed = repaired.deepCopy();
            if (field.equals("allowBackfill")) changed.put(field, true); else changed.putArray(field);
            var error = assertThrows(AgentContractCodec.ValidationException.class,
                    () -> codec.requireSameReviewSelection(original, changed.toString()));
            assertEquals("repair.selection", error.failure().field());
        }
    }
    @org.junit.jupiter.api.Test void selectionCanSurviveInvalidCoverageWithoutAcceptingInvalidOperationsOrJson() {
        var codec = new AgentContractCodec();
        String valid = "{\"schemaVersion\":1,\"approved\":[\"core-mod\"],\"rejected\":[],\"allowBackfill\":false,\"requirements\":\"bad-report\"}";
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(valid));
        var selected = codec.readReviewSelection(valid).select(java.util.Set.of("core-mod"), 1).decision();
        assertEquals(java.util.List.of("core-mod"), selected.approved());
        assertFalse(selected.allowBackfill());
        assertTrue(selected.requirements().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewSelection(valid).select(java.util.Set.of(), 1));
        for (String invalid : java.util.List.of(valid + "{}", valid.substring(0, valid.length() - 1),
                valid.replace("\"approved\":[", "\"approved\":[],\"approved\":["),
                valid.replace("\"allowBackfill\":false", "\"allowBackfill\":\"false\""),
                valid.replace("\"requirements\":", "\"unknown\":"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":999"),
                valid.replace("\"rejected\":[]", "\"rejected\":[{\"slug\":\"core-mod\",\"reason\":\"UNKNOWN\",\"explanation\":\"x\"}]")))
            assertThrows(IllegalArgumentException.class, () -> codec.readReviewSelection(invalid));
    }

    @Test void correctionReportsMissingFieldsAndEvidenceOverflowTogether() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var evidence = java.util.stream.IntStream.range(0, CriterionReview.MAX_EVIDENCE).mapToObj(i ->
                new RequirementReview.Contribution("project-" + i, "Reduces memory usage", "内存")).toList();
        var review = new RequirementReview("r-0000000000000001", List.of(), "",
                List.of(new CriterionReview("优化内存", evidence, "")));
        var root = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(new ReviewResponse(1, List.of(), List.of(), false, List.of(review)));
        var criterion = (tools.jackson.databind.node.ObjectNode) root.path("requirements").get(0).path("criteria").get(0);
        criterion.remove("gap");
        criterion.withArray("evidence").add(mapper.valueToTree(new RequirementReview.Contribution("extra", "Reduces memory usage", "内存")));
        var codec = new AgentContractCodec();
        var error = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readReviewResponse(root.toString()));
        assertEquals("/requirements/0/criteria/0/gap", error.failure().pointer());
        assertTrue(error.failure().guidance().contains("必填字符串"));
        assertTrue(error.failure().guidance().contains("/requirements/0/criteria/0/evidence"));
        assertTrue(error.failure().guidance().contains("最多 6 项，当前 7 项"));
        assertFalse(error.failure().guidance().contains("不允许嵌套"));
        criterion.put("gap", 42);
        error = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readReviewResponse(root.toString()));
        assertTrue(error.failure().guidance().contains("必填字符串"));
        criterion.put("gap", ""); criterion.withArray("evidence").remove(CriterionReview.MAX_EVIDENCE);
        assertDoesNotThrow(() -> codec.readReviewResponse(root.toString()));
    }
    @Test void reviewDiagnosticsAreBoundedAndDoNotEchoSourceText() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode().put("schemaVersion", 1).put("allowBackfill", false);
        root.putArray("approved"); root.putArray("rejected");
        var row = root.putArray("requirements").addObject().put("id", "r-0000000000000001").put("gap", "");
        row.putArray("evidence"); var children = row.putArray("criteria");
        for (int i = 0; i < 16; i++) children.addObject().put("source", "private-source-marker-" + i).putArray("evidence");
        var error = assertThrows(AgentContractCodec.ValidationException.class, () -> new AgentContractCodec().readReviewResponse(root.toString()));
        assertEquals(8, error.failure().guidance().split("是必填", -1).length - 1);
        assertFalse(error.getMessage().contains("private-source-marker"));
        assertNull(error.getCause());
    }
    @Test void excessEvidenceHasAnIndexedBudgetForCorrectionWithoutTruncation() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode().put("schemaVersion", 1).put("allowBackfill", true);
        root.putArray("approved"); root.putArray("rejected");
        var row = root.putArray("requirements").addObject().put("id", "r-0000000000000001").put("gap", "");
        row.putArray("evidence");
        var criterion = row.putArray("criteria").addObject().put("source", "物品管理和界面").put("gap", "");
        var evidence = criterion.putArray("evidence");
        for (int i = 0; i <= CriterionReview.MAX_EVIDENCE; i++) evidence.addObject()
                .put("slug", "project-" + i).put("quote", "Adds inventory management").put("purpose", "物品管理");
        var codec = new AgentContractCodec();
        var error = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readReviewResponse(root.toString()));
        assertEquals(AgentContractCodec.FailureKind.SEMANTIC, error.failure().kind());
        assertEquals("/requirements/0/criteria/0/evidence", error.failure().pointer());
        assertTrue(error.failure().guidance().contains("最多 6 项，当前 7 项"));
        assertTrue(error.failure().guidance().contains("父项必须 evidence=[]"));
        assertEquals(7, evidence.size());
        evidence.remove(evidence.size() - 1);
        assertEquals(6, codec.readReviewResponse(root.toString()).requirements().getFirst().criteria().getFirst().evidence().size());
        row.set("evidence", evidence.deepCopy());
        var parentEvidence = (tools.jackson.databind.node.ArrayNode) row.path("evidence");
        for (int i = parentEvidence.size(); i <= RequirementReview.MAX_EVIDENCE; i++) parentEvidence.addObject()
                .put("slug", "project-" + i).put("quote", "Adds inventory management").put("purpose", "物品管理");
        error = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readReviewResponse(root.toString()));
        assertEquals("/requirements/0/evidence", error.failure().pointer());
        assertTrue(error.failure().guidance().contains("最多 12 项，当前 13 项"));
        var schema = AgentSchemas.reviewDecision().path("properties").path("requirements").path("items").path("properties");
        assertEquals(RequirementReview.MAX_EVIDENCE, schema.path("evidence").path("maxItems").asInt());
        assertEquals(CriterionReview.MAX_EVIDENCE, schema.path("criteria").path("items").path("properties").path("evidence").path("maxItems").asInt());
        var parentRule = AgentSchemas.reviewDecision().path("properties").path("requirements").path("items").path("allOf").get(0);
        assertEquals(1, parentRule.path("if").path("properties").path("criteria").path("minItems").asInt());
        assertEquals(0, parentRule.path("then").path("properties").path("evidence").path("maxItems").asInt(-1));
        assertEquals(0, parentRule.path("then").path("properties").path("gap").path("maxLength").asInt(-1));
    }
    @Test void parentSummaryDoesNotPreventPreservingParsedVetoAndBackfillRestrictions() {
        var contribution = new RequirementReview.Contribution("retained", "Adds inventory management", "管理");
        var row = new RequirementReview("r-0000000000000001", List.of(contribution), "",
                List.of(new CriterionReview("物品管理", List.of(contribution), "")));
        var input = new ReviewResponse(1, List.of(), List.of(new ReviewDecision.Rejection("blocked", ReviewDecision.Reason.OFF_TOPIC, "不相关")),
                false, List.of(row));
        var codec = new AgentContractCodec();
        var parsed = codec.readReviewResponse(new tools.jackson.databind.ObjectMapper().writeValueAsString(input));
        assertEquals(List.of("blocked"), parsed.rejected().stream().map(ReviewDecision.Rejection::slug).toList());
        assertFalse(parsed.allowBackfill());
        assertFalse(parsed.select(Set.of("blocked"), 0, Set.of("blocked", "retained")).decision().allowBackfill());
        var requirement = new BuildRequirement(row.id(), "物品管理", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(),
                List.of(), List.of(), SelectionPreferences.DEFAULT, List.of(new RequirementCriterion("物品管理", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> yagen.waitmydawn.maa.model.RequirementCoverage.validateReviews(List.of(requirement), parsed.requirements()));
    }
    @Test void recursivelyCopiedCriterionHasExplicitFlatSchemaRepairGuidance() {
        String json = """
                {"schemaVersion":1,"approved":[],"rejected":[],"allowBackfill":true,
                 "requirements":[{"id":"r-0000000000000001","evidence":[],"gap":"","criteria":[{"source":"群系","evidence":[],"gap":"","criteria":[]}]}]}
                """;
        var error = assertThrows(AgentContractCodec.ValidationException.class, () -> new AgentContractCodec().readReviewResponse(json));
        assertTrue(error.failure().guidance().contains("不允许嵌套 criteria"));
        assertEquals("/requirements/0/criteria/0/criteria", error.failure().pointer());
    }
    @Test void evidenceCannotReferToRejectedOrForeignProjectsButCanUseRetainedCore() {
        var evidence = new RequirementReview.Contribution("core", "Adds automation", "自动化");
        var row = new RequirementReview("r-0000000000000001", List.of(evidence), "");
        var review = new ReviewResponse(1, List.of("addon"), List.of(), false, List.of(row));
        assertDoesNotThrow(() -> review.select(Set.of("addon"), 1, Set.of("addon", "core")));
        assertThrows(IllegalArgumentException.class, () -> review.select(Set.of("addon"), 1, Set.of("addon")));
        var contradicted = new ReviewResponse(1, List.of(), List.of(new ReviewDecision.Rejection("core", ReviewDecision.Reason.OFF_TOPIC, "不相关")),
                true, List.of(row));
        assertThrows(IllegalArgumentException.class, () -> contradicted.select(Set.of("core"), 1));
    }
    @Test void overlapUsesVetoWithoutLosingBackfillPermissionOrOtherApprovals() {
        var rejection = new ReviewDecision.Rejection("create", ReviewDecision.Reason.CONSTRAINT, "用户排除");
        var selected = new ReviewResponse(1, List.of("create", "jei"), List.of(rejection), false)
                .select(Set.of("create", "jei"), 1);
        assertEquals(List.of("jei"), selected.decision().approved());
        assertEquals(List.of(rejection), selected.decision().rejected());
        assertFalse(selected.decision().allowBackfill());
        assertEquals(1, selected.overlapRemoved());
        assertEquals(0, selected.excessApproved());
    }

    @Test void excessIsMeasuredWithoutExpandingTheExecutionBudgetOrDiscardingVetoes() {
        var selected = new ReviewResponse(1, List.of("jei", "jade"), List.of(), true)
                .select(Set.of("jei", "jade"), 1);
        assertEquals(1, selected.excessApproved());
        assertEquals(2, selected.decision().approved().size());
        assertThrows(IllegalArgumentException.class, () -> selected.decision().validateAgainst(Set.of("jei", "jade"), 1));
    }

    @Test void membershipCorrectionLocatesBothArraysWithoutEchoingUntrustedProjectValues() {
        var response = new ReviewResponse(1, List.of("valid-project", "private-marker"),
                List.of(new ReviewDecision.Rejection("unknown-project", ReviewDecision.Reason.OFF_TOPIC, "不相关")), true);
        var error = assertThrows(IllegalArgumentException.class, () -> response.select(Set.of("valid-project"), 1));
        assertTrue(error.getMessage().contains("candidateMembership"));
        assertTrue(error.getMessage().contains("/approved/1"));
        assertTrue(error.getMessage().contains("/rejected/0/slug"));
        assertTrue(error.getMessage().contains("compatibleCandidates"));
        assertFalse(error.getMessage().contains("private-marker"));
        assertFalse(error.getMessage().contains("unknown-project"));
        var many = new ReviewResponse(1, java.util.stream.IntStream.range(0, 20).mapToObj(i -> "unknown-" + i).toList(), List.of(), true);
        var bounded = assertThrows(IllegalArgumentException.class, () -> many.select(Set.of(), 1));
        assertTrue(bounded.getMessage().contains("/approved/7"));
        assertFalse(bounded.getMessage().contains("/approved/8"));
    }

    @Test void malformedFieldsAndUnknownCandidatesStillFail() {
        var codec = new AgentContractCodec();
        String valid = "{\"schemaVersion\":1,\"approved\":[\"jei\"],\"rejected\":[],\"allowBackfill\":true}";
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(valid.replace("true", "\"true\"")));
        assertEquals(List.of("jei"), codec.readReviewResponse(valid.replace("\"jei\"", "\"jei\",\"jei\"")).approved());
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"unknown\":1")));
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(valid).select(Set.of("jade"), 10));
        assertThrows(IllegalArgumentException.class, () -> new ReviewResponse(1, List.of(),
                List.of(new ReviewDecision.Rejection("jade", ReviewDecision.Reason.OTHER, "明确拒绝")), true).select(Set.of("jei"), 10));
    }
}
