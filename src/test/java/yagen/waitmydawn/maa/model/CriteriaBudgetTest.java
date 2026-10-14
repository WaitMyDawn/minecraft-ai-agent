package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CriteriaBudgetTest {
    @Test void reservedRepairDoesNotIncreaseGlobalLimitsAndAlsoBoundsRepeatedQueries() {
        var budget = new RetrievalBudget(); budget.reserveForRepair();
        for (int i = 0; i < 38; i++) assertTrue(budget.search("q" + i));
        assertFalse(budget.search("q38"));
        for (int i = 0; i < 288; i++) assertTrue(budget.version("v" + i));
        assertFalse(budget.version("v288"));
        budget.beginRepair();
        for (int i = 38; i < 42; i++) assertTrue(budget.search("q" + i));
        assertFalse(budget.search("q42")); assertFalse(budget.search("q0"));
        for (int i = 288; i < 300; i++) assertTrue(budget.version("v" + i));
        assertFalse(budget.version("v300"));
        assertEquals(42, budget.snapshot().get("searchQueries")); assertEquals(300, budget.snapshot().get("candidateVersions"));
        assertEquals(4, budget.snapshot().get("repairSearchQueries")); assertEquals(12, budget.snapshot().get("repairCandidateVersions"));
    }
}
