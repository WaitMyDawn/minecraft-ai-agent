package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewAuthorityTest {
    private static final String ID = "r-0000000000000001";
    private static final String REQUEST = "制作冒险整合包，不要移除原版结构。";
    private static final String BOSS = "Adds a single boss and a new dungeon";
    private final ObjectMapper json = new ObjectMapper();
    private final AgentContractCodec codec = new AgentContractCodec();
    private final BuildRequirement goal = new BuildRequirement(ID, "丰富生物与结构", BuildRequirement.Kind.CAPABILITY,
            List.of(), List.of(), List.of(), List.of(), SelectionPreferences.DEFAULT,
            List.of(new RequirementCriterion("丰富生物", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()),
                    new RequirementCriterion("丰富结构", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of())));
    private String target(int index) { return goal.criteria().get(index).key(ID); }
    private Map<String,String> facts() { return Map.of("boss", BOSS, "remover", "Removes vanilla structures",
            "conflicting", "Incompatible with retained-core", "retained-core", "Adds new forests"); }
    private Set<String> candidates() { return Set.of("boss", "remover", "conflicting"); }
    private ReviewAuthority policy() { return new ReviewAuthority(List.of(goal), candidates(), facts().keySet(), facts(), REQUEST); }
    private ReviewDecision.Rejection rejection(String slug, ReviewDecision.Reason reason, ReviewDecision.Scope scope,
                                               String target, String quote, String request, String related) {
        return new ReviewDecision.Rejection(slug, reason, "候选范围核对", new ReviewDecision.Authority(scope, target, quote, request, related));
    }
    private ReviewDecision.Rejection local(int index) {
        return rejection("boss", ReviewDecision.Reason.OFF_TOPIC, ReviewDecision.Scope.GOAL, target(index), BOSS, "", "");
    }
    private ReviewDecision.Rejection global() {
        return rejection("remover", ReviewDecision.Reason.CONSTRAINT, ReviewDecision.Scope.PACK, "", "Removes vanilla structures", "不要移除原版结构", "");
    }
    private ReviewResponse response(List<String> approved, List<ReviewDecision.Rejection> rejected, boolean allowed, ReviewDecision.Backfill basis) {
        return new ReviewResponse(2, approved, rejected, allowed, List.of(), basis);
    }
    private ReviewDecision.Backfill allow() { return new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.ALLOW, ""); }
    private ReviewEvidenceLedger ledger() { return new ReviewEvidenceLedger(List.of(goal), candidates(), facts().keySet(), facts(), REQUEST); }
    private void capture(ReviewEvidenceLedger ledger, ReviewResponse response) { ledger.capture(json.writeValueAsString(response)); }

    @Test void localMismatchCanBeApprovedForAnotherGoalAndNeverBecomesGlobalVeto() {
        var raw = response(List.of("boss"), List.of(local(0)), true, allow());
        assertThrows(IllegalArgumentException.class, () -> raw.select(candidates(), 2));
        var selected = policy().checked(codec.readReviewResponse(json.writeValueAsString(raw))).select(candidates(), 2).decision();
        assertEquals(List.of("boss"), selected.approved()); assertTrue(selected.rejected().isEmpty());
        var ledger = ledger(); capture(ledger, raw); ledger.acceptSelection(selected.approved());
        assertTrue(ledger.vetoes().isEmpty()); assertFalse(ledger.backfillForbidden());
    }

    @Test void multipleLocalTargetsAreDistinctButDuplicateTargetDecisionsAreRejected() {
        var raw = response(List.of("boss"), List.of(local(0), local(1)), true, allow());
        assertEquals(2, codec.readReviewResponse(json.writeValueAsString(raw)).rejected().size());
        assertThrows(IllegalArgumentException.class, () -> response(List.of(), List.of(local(0), local(0)), true, allow()));
    }

    @Test void localRejectionSuppressesOnlyItsOwnModelContribution() {
        var children = goal.criteria().stream().map(c -> new CriterionReview(c.source(),
                List.of(new RequirementReview.Contribution("boss", BOSS, "内容贡献")), "")).toList();
        var raw = new ReviewResponse(2, List.of("boss"), List.of(local(0)), true,
                List.of(new RequirementReview(ID, List.of(), "", children)), allow());
        var ledger = ledger(); capture(ledger, raw);
        var verified = ledger.verify(Set.of());
        assertEquals(1, verified.counts().accepted());
        assertTrue(verified.reviews().getFirst().criteria().getFirst().evidence().isEmpty());
        assertEquals("boss", verified.reviews().getFirst().criteria().get(1).evidence().getFirst().slug());
        assertEquals(0, ledger.verify(Set.of("boss")).counts().accepted());
    }

    @Test void globalScopeRequiresHardConstraintOrVerifiedConflictRatherThanSelectionTaste() {
        for (var reason : List.of(ReviewDecision.Reason.OFF_TOPIC, ReviewDecision.Reason.DUPLICATE_FUNCTION,
                ReviewDecision.Reason.INSUFFICIENT_EVIDENCE, ReviewDecision.Reason.OTHER)) {
            var veto = rejection("boss", reason, ReviewDecision.Scope.PACK, "", BOSS, REQUEST, "");
            var exception = assertThrows(IllegalArgumentException.class, () -> policy().checked(response(List.of(), List.of(veto), true, allow())));
            assertTrue(exception.getMessage().contains("REASON_REQUIRES_LOCAL_SCOPE"));
        }
        assertEquals("remover", policy().checked(response(List.of(), List.of(global()), true, allow())).rejected().getFirst().slug());
    }

    @Test void originalRequestAndProjectQuoteMustBothBeVerifiableForConstraint() {
        for (var veto : List.of(
                rejection("remover", ReviewDecision.Reason.CONSTRAINT, ReviewDecision.Scope.PACK, "", "Removes vanilla structures", "用户禁止生成建筑", ""),
                rejection("remover", ReviewDecision.Reason.CONSTRAINT, ReviewDecision.Scope.PACK, "", "Removes all bosses", "不要移除原版结构", "")))
            assertThrows(IllegalArgumentException.class, () -> policy().checked(response(List.of(), List.of(veto), true, allow())));
    }

    @Test void optionalConfigurationAndHistoryCannotJustifyGlobalDefaultConstraint() {
        for (String text : List.of("# Optional presets\nRemoves vanilla structures", "# Changelog\nRemoves vanilla structures")) {
            var facts = new HashMap<>(facts()); facts.put("remover", text);
            var policy = new ReviewAuthority(List.of(goal), candidates(), facts.keySet(), facts, REQUEST);
            assertThrows(IllegalArgumentException.class, () -> policy.checked(response(List.of(), List.of(global()), true, allow())));
        }
    }

    @Test void themeQuantityOrClippedSoftPreferenceCannotBeRelabeledAsHardGlobalConstraint() {
        for (String request : List.of("制作冒险整合包", "不要超过70个模组", "不要加入超过5-10个结构模组", "尽量不要移除原版结构")) {
            var p = new ReviewAuthority(List.of(goal), candidates(), facts().keySet(), facts(), request);
            String citation = request.startsWith("尽量") ? request.substring(2) : request;
            var wrong = rejection("remover", ReviewDecision.Reason.CONSTRAINT, ReviewDecision.Scope.PACK, "", "Removes vanilla structures", citation, "");
            assertThrows(IllegalArgumentException.class, () -> p.checked(response(List.of(), List.of(wrong), true, allow())));
        }
    }

    @Test void conflictMustNameAnActualRetainedOrApprovedCounterpartAndExplicitRelation() {
        var valid = rejection("conflicting", ReviewDecision.Reason.CONFLICT, ReviewDecision.Scope.PACK, "", "Incompatible with retained-core", "", "retained-core");
        assertEquals(1, policy().checked(response(List.of(), List.of(valid), true, allow())).rejected().size());
        for (String related : List.of("missing-core", "conflicting", "boss")) {
            var wrong = rejection("conflicting", ReviewDecision.Reason.CONFLICT, ReviewDecision.Scope.PACK, "", "Incompatible with retained-core", "", related);
            assertThrows(IllegalArgumentException.class, () -> policy().checked(response(List.of("boss"), List.of(wrong), true, allow())));
        }
        for (String text : List.of("If enabled, Incompatible with retained-core", "# Changelog\nIncompatible with retained-core")) {
            var facts = new HashMap<>(facts()); facts.put("conflicting", text);
            assertThrows(IllegalArgumentException.class, () -> new ReviewAuthority(List.of(goal), candidates(), facts.keySet(), facts, REQUEST)
                    .checked(response(List.of(), List.of(valid), true, allow())));
        }
    }

    @Test void unknownTargetAndParentOfCompositeRequirementCannotAcquireLocalAuthority() {
        for (String key : List.of(ID, "r-ffffffffffffffff", "")) {
            var wrong = rejection("boss", ReviewDecision.Reason.OFF_TOPIC, ReviewDecision.Scope.GOAL, key, BOSS, "", "");
            assertThrows(IllegalArgumentException.class, () -> policy().checked(response(List.of("boss"), List.of(wrong), true, allow())));
        }
    }

    @Test void poolExhaustionNeedsEveryRemainingCandidateToHaveValidGlobalVeto() {
        var proof = new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.POOL_EXHAUSTED, "");
        assertThrows(IllegalArgumentException.class, () -> policy().checked(response(List.of("conflicting"), List.of(global(), local(0)), false, proof)));
        var selected = policy().checked(response(List.of("boss", "conflicting"), List.of(global()), false, proof));
        assertFalse(selected.allowBackfill());
        assertTrue(new ReviewAuthority(List.of(), Set.of(), Set.of(), Map.of(), "")
                .checked(response(List.of(), List.of(), false, proof)).rejected().isEmpty());
    }

    @Test void unvalidatedApprovalCannotPermanentlyEstablishConflictOrExhaustion() {
        var facts = new HashMap<>(facts()); facts.put("conflicting", "Incompatible with boss");
        var ledger = new ReviewEvidenceLedger(List.of(goal), candidates(), facts.keySet(), facts, REQUEST);
        var veto = rejection("conflicting", ReviewDecision.Reason.CONFLICT, ReviewDecision.Scope.PACK, "", "Incompatible with boss", "", "boss");
        capture(ledger, response(List.of("boss"), List.of(veto), true, allow()));
        assertTrue(ledger.vetoes().contains("conflicting"));
        assertEquals(Set.of(), ledger.authoritySummary().get("confirmedGlobalVetoes"));
        capture(ledger, response(List.of("remover"), List.of(), true, allow())); ledger.acceptSelection(List.of("remover"));
        assertTrue(ledger.vetoes().isEmpty());

        var exhausted = ledger();
        capture(exhausted, response(new ArrayList<>(candidates()), List.of(), false,
                new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.POOL_EXHAUSTED, "")));
        assertEquals(false, exhausted.authoritySummary().get("verifiedBackfillForbidden"));
        capture(exhausted, response(List.of("boss"), List.of(), true, allow())); exhausted.acceptSelection(List.of("boss"));
        assertFalse(exhausted.backfillForbidden());
    }

    @Test void validatedApprovalCanEstablishActualConflictCounterpartButRejectedCounterpartCannot() {
        var facts = new HashMap<>(facts()); facts.put("conflicting", "Incompatible with boss");
        var veto = rejection("conflicting", ReviewDecision.Reason.CONFLICT, ReviewDecision.Scope.PACK, "", "Incompatible with boss", "", "boss");
        var ledger = new ReviewEvidenceLedger(List.of(goal), candidates(), facts.keySet(), facts, REQUEST);
        capture(ledger, response(List.of("boss"), List.of(veto), true, allow())); ledger.acceptSelection(List.of("boss"));
        assertEquals(Set.of("conflicting"), ledger.authoritySummary().get("confirmedGlobalVetoes"));
        var bossDenied = rejection("boss", ReviewDecision.Reason.CONSTRAINT, ReviewDecision.Scope.PACK, "", BOSS, "制作冒险整合包", "");
        assertThrows(IllegalArgumentException.class, () -> new ReviewAuthority(List.of(goal), candidates(), facts.keySet(), facts, REQUEST)
                .checked(response(List.of("boss"), List.of(veto, bossDenied), true, allow())));
    }

    @Test void onlyVerifiedExplicitUserOptOutMayForbidBackfillByRequest() {
        for (String request : List.of("只加入点名的模组", "不要自动补位", "No additional mods please", "Only the named mods")) {
            var p = new ReviewAuthority(List.of(goal), candidates(), facts().keySet(), facts(), request);
            assertFalse(p.checked(response(List.of("boss"), List.of(), false,
                    new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.USER_REQUEST, request))).allowBackfill());
        }
        for (String request : List.of("铁魔法附属5-10个", "已经满足局部配额", "不要超过70个模组", "没有更多推荐")) {
            var p = new ReviewAuthority(List.of(goal), candidates(), facts().keySet(), facts(), request);
            assertThrows(IllegalArgumentException.class, () -> p.checked(response(List.of("boss"), List.of(), false,
                    new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.USER_REQUEST, request))));
        }
        for (String request : List.of("不要自动补位结构模组", "不要自动添加更多结构模组", "不是真的不要自动补位", "不要自动补位但可以补充实用模组")) {
            var p = new ReviewAuthority(List.of(goal), candidates(), facts().keySet(), facts(), request);
            assertThrows(IllegalArgumentException.class, () -> p.checked(response(List.of("boss"), List.of(), false,
                    new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.USER_REQUEST, "不要自动补位"))));
        }
    }

    @Test void mismatchedBackfillFlagAndSourceAreNotAccepted() {
        for (var basis : List.of(ReviewDecision.BackfillBasis.POOL_EXHAUSTED, ReviewDecision.BackfillBasis.USER_REQUEST, ReviewDecision.BackfillBasis.LEGACY))
            assertThrows(IllegalArgumentException.class, () -> policy().checked(response(List.of(), List.of(), true, new ReviewDecision.Backfill(basis, ""))));
        assertThrows(IllegalArgumentException.class, () -> policy().checked(response(List.of(), List.of(), false, allow())));
    }

    @Test void javaPreservesExplicitUserOptOutEvenWhenModelAlwaysClaimsAllow() {
        for (String request : List.of("不要自动补位", "1）不要自动补位", "只加入点名的模组", "No additional mods please")) {
            var p = new ReviewAuthority(List.of(goal), candidates(), facts().keySet(), facts(), request);
            assertThrows(IllegalArgumentException.class, () -> p.checked(response(List.of("boss"), List.of(), true, allow())));
            var ledger = new ReviewEvidenceLedger(List.of(goal), candidates(), facts().keySet(), facts(), request);
            assertTrue(ledger.backfillForbidden());
            capture(ledger, response(List.of("boss"), List.of(), true, allow()));
            assertTrue(ledger.backfillForbidden());
            assertEquals(true, ledger.authoritySummary().get("verifiedBackfillForbidden"));
        }
    }

    @Test void invalidDraftRestrictionsStayPendingUntilAnAcceptedRepairButDoNotPoisonLaterPermission() {
        var ledger = ledger();
        var invalid = rejection("boss", ReviewDecision.Reason.OFF_TOPIC, ReviewDecision.Scope.PACK, "", BOSS, REQUEST, "");
        capture(ledger, response(List.of(), List.of(invalid), false,
                new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.POOL_EXHAUSTED, "")));
        assertTrue(ledger.vetoes().contains("boss")); assertTrue(ledger.backfillForbidden());
        var repair = response(List.of("boss"), List.of(local(0)), true, allow()); capture(ledger, repair);
        assertTrue(ledger.vetoes().contains("boss")); assertTrue(ledger.backfillForbidden());
        var selected = policy().checked(repair).select(candidates(), 2).decision(); ledger.acceptSelection(selected.approved());
        assertTrue(ledger.vetoes().isEmpty()); assertFalse(ledger.backfillForbidden());
    }

    @Test void verifiedGlobalVetoAndOptOutSurviveLaterApproval() {
        String request = "不要自动补位，不要移除原版结构";
        var ledger = new ReviewEvidenceLedger(List.of(goal), candidates(), facts().keySet(), facts(), request);
        var first = response(List.of("boss"), List.of(global()), false,
                new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.USER_REQUEST, "不要自动补位"));
        capture(ledger, first);
        capture(ledger, response(List.of("remover"), List.of(), true, allow())); ledger.acceptSelection(List.of("remover"));
        assertEquals(Set.of("remover"), ledger.vetoes()); assertTrue(ledger.backfillForbidden());
    }

    @Test void brokenRepairPreservesPreviouslyVerifiedPermissionButCannotGrantInitialPermission() {
        var ledger = ledger(); capture(ledger, response(List.of("boss"), List.of(), true, allow())); ledger.acceptSelection(List.of("boss"));
        ledger.capture("{\"schemaVersion\":2,\"approved\":[");
        assertTrue(ledger.vetoes().isEmpty()); assertFalse(ledger.backfillForbidden());
        var unreadable = ledger(); unreadable.capture("{\"schemaVersion\":2,\"approved\":[");
        assertTrue(unreadable.backfillForbidden());
    }

    @Test void versionTwoCannotOmitAuthorityOrUseLegacyDefaultsAndVersionOneCannotSpoofScopes() {
        var tree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(response(List.of("boss"), List.of(local(0)), true, allow()));
        tree.withObject("/rejected/0").remove("authority");
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(tree.toString()));
        assertThrows(IllegalArgumentException.class, () -> policy().checked(response(List.of(), List.of(), true, ReviewDecision.Backfill.LEGACY)));
        var spoofed = (tools.jackson.databind.node.ObjectNode) json.valueToTree(response(List.of("boss"), List.of(local(0)), true, allow()));
        spoofed.put("schemaVersion", 1);
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(spoofed.toString()));
        String legacy = "{\"schemaVersion\":1,\"approved\":[],\"rejected\":[{\"slug\":\"boss\",\"reason\":\"OFF_TOPIC\",\"explanation\":\"不适合\"}],\"allowBackfill\":false}";
        var decision = policy().checked(codec.readReviewResponse(legacy)).select(candidates(), 1).decision();
        assertEquals(1, decision.rejected().size()); assertFalse(decision.allowBackfill());
    }

    @Test void requestedVersionTwoCannotBeDowngradedByModelToLegacyPermissionSemantics() {
        String legacy = "{\"schemaVersion\":1,\"approved\":[],\"rejected\":[{\"slug\":\"boss\",\"reason\":\"OFF_TOPIC\",\"explanation\":\"仅增加一个Boss\"}],\"allowBackfill\":false}";
        var strict = new ReviewAuthority(List.of(goal), candidates(), facts().keySet(), facts(), REQUEST, 2);
        assertThrows(IllegalArgumentException.class, () -> strict.checked(codec.readReviewResponse(legacy)));
        var ledger = new ReviewEvidenceLedger(List.of(goal), candidates(), facts().keySet(), facts(), REQUEST, 2);
        ledger.capture(legacy);
        assertEquals(Set.of(), ledger.authoritySummary().get("confirmedGlobalVetoes"));
        assertEquals(false, ledger.authoritySummary().get("verifiedBackfillForbidden"));
        assertTrue(ledger.backfillForbidden());
        capture(ledger, response(List.of("boss"), List.of(local(0)), true, allow())); ledger.acceptSelection(List.of("boss"));
        assertTrue(ledger.vetoes().isEmpty()); assertFalse(ledger.backfillForbidden());
    }

    @Test void reportOnlyRepairCannotChangeScopeQuoteTargetOrBackfillSource() {
        String raw = json.writeValueAsString(response(List.of("boss"), List.of(local(0)), true, allow()));
        for (String field : List.of("targetId", "quote", "scope", "basis")) {
            var tree = (tools.jackson.databind.node.ObjectNode) json.readTree(raw);
            if (field.equals("basis")) tree.withObject("backfill").put(field, "POOL_EXHAUSTED");
            else tree.withObject("/rejected/0/authority").put(field, field.equals("scope") ? "PACK" : "different-value");
            assertThrows(IllegalArgumentException.class, () -> codec.requireSameReviewSelection(raw, tree.toString()));
        }
    }

    @Test void schemaPublishesNewAuthorityFieldsAndNoLegacyPermissionEnums() {
        var schema = AgentSchemas.reviewDecision();
        assertEquals(2, schema.path("properties").path("schemaVersion").path("const").asInt());
        assertTrue(schema.path("properties").has("backfill"));
        assertFalse(schema.path("properties").path("rejected").path("items").path("properties").path("authority")
                .path("properties").path("scope").toString().contains("LEGACY"));
    }

    @Test void recordedQ01LocalTasteRejectionsCannotBecomeVersionTwoGlobalPoolExhaustion() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/review-q01-overrejection.json")) {
            assertNotNull(input); var fixture = json.readTree(input);
            assertEquals(109, fixture.path("rejectedCount").asInt());
            assertEquals(103, fixture.path("reasonCounts").path("OFF_TOPIC").asInt());
            var facts = new LinkedHashMap<String,String>();
            var pack = new ArrayList<ReviewDecision.Rejection>(); var local = new ArrayList<ReviewDecision.Rejection>();
            for (var sample : fixture.path("samples")) {
                String slug = sample.path("slug").asText(), fact = sample.path("fact").asText();
                facts.put(slug, fact); String quote = fact.substring(0, Math.min(160, fact.length()));
                pack.add(rejection(slug, ReviewDecision.Reason.OFF_TOPIC, ReviewDecision.Scope.PACK, "", quote, REQUEST, ""));
                local.add(rejection(slug, ReviewDecision.Reason.OFF_TOPIC, ReviewDecision.Scope.GOAL, target(0), quote, "", ""));
            }
            assertEquals(3, facts.size());
            var policy = new ReviewAuthority(List.of(goal), facts.keySet(), facts.keySet(), facts, REQUEST);
            var invalid = response(List.of(), pack, false, new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.POOL_EXHAUSTED, ""));
            assertThrows(IllegalArgumentException.class, () -> policy.checked(invalid));
            var inspected = policy.inspect(2, pack, Set.of(), false, invalid.backfill());
            assertTrue(inspected.globalVetoes().isEmpty()); assertTrue(inspected.backfillUnverified());
            var corrected = policy.checked(response(List.of(), local, true, allow()));
            assertTrue(corrected.rejected().isEmpty()); assertTrue(corrected.allowBackfill());
        }
    }
}
