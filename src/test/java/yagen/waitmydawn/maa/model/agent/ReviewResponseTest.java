package yagen.waitmydawn.maa.model.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ReviewResponseTest {
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

    @Test void malformedFieldsAndUnknownCandidatesStillFail() {
        var codec = new AgentContractCodec();
        String valid = "{\"schemaVersion\":1,\"approved\":[\"jei\"],\"rejected\":[],\"allowBackfill\":true}";
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(valid.replace("true", "\"true\"")));
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(valid.replace("\"jei\"", "\"jei\",\"jei\"")));
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"unknown\":1")));
        assertThrows(IllegalArgumentException.class, () -> codec.readReviewResponse(valid).select(Set.of("jade"), 10));
        assertThrows(IllegalArgumentException.class, () -> new ReviewResponse(1, List.of(),
                List.of(new ReviewDecision.Rejection("jade", ReviewDecision.Reason.OTHER, "明确拒绝")), true).select(Set.of("jei"), 10));
    }
}
