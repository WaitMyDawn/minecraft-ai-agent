package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalBudgetReservationTest {
    @Test void explicitGoalsComplementaryContentRepairAndRelationsShareTheOriginalTotalLimits() {
        var budget = new RetrievalBudget();
        budget.reserveForRepair(); budget.reserveForRelations(); budget.reserveForComplementary(38);
        for (int i=0;i<29;i++) assertTrue(budget.search("goal-"+i));
        assertFalse(budget.search("goal-overflow"));
        for (int i=0;i<200;i++) assertTrue(budget.version("goal-"+i));
        assertFalse(budget.version("goal-overflow"));
        budget.beginComplementary();
        for (int i=0;i<3;i++) assertTrue(budget.search("complement-"+i));
        assertFalse(budget.search("complement-overflow"));
        for (int i=0;i<64;i++) assertTrue(budget.version("complement-"+i));
        assertFalse(budget.version("complement-overflow"));
        budget.endComplementary(); budget.beginRepair();
        for (int i=0;i<4;i++) assertTrue(budget.search("repair-"+i));
        for (int i=0;i<12;i++) assertTrue(budget.version("repair-"+i));
        budget.beginRelations();
        for (int i=0;i<6;i++) assertTrue(budget.search("relation-"+i));
        for (int i=0;i<24;i++) assertTrue(budget.version("relation-"+i));
        assertFalse(budget.search("relation-overflow")); assertFalse(budget.version("relation-overflow"));
        assertEquals(42,budget.snapshot().get("searchQueries"));
        assertEquals(300,budget.snapshot().get("candidateVersions"));
    }
    @Test void unusedComplementaryReservationsAreReleasedAndExistingFactsRemainReusable() {
        var budget = new RetrievalBudget(); budget.reserveForComplementary(38);
        assertTrue(budget.search("known")); assertTrue(budget.version("known"));
        budget.beginComplementary();
        assertTrue(budget.search("known")); assertTrue(budget.version("known"));
        budget.endComplementary();
        for (int i=0;i<41;i++) assertTrue(budget.search("other-"+i));
        for (int i=0;i<299;i++) assertTrue(budget.version("other-"+i));
        assertFalse(budget.search("overflow")); assertFalse(budget.version("overflow"));
    }
}
