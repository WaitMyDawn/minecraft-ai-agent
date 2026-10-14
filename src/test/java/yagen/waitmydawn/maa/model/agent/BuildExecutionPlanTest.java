package yagen.waitmydawn.maa.model.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BuildExecutionPlanTest {
    private BuildIntent intent(BuildIntent.CountTarget count, List<String> searches) {
        return new BuildIntent(1, BuildIntent.Mode.EDIT, "说明", "", new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, false),
                count, List.of("journeymap"), List.of("journeymap"), List.of("create"), List.of(), List.of(),
                BuildIntent.ReplacementPolicy.NONE, searches.isEmpty() ? List.of()
                : List.of(new BuildIntent.SearchIntent("magic", 100, searches)), List.of(), 2_100_000_000L);
    }

    @Test void quantityScopesUseDifferentBudgetsAndActualCounts() {
        var roots = Set.of("jei", "journeymap");
        var files = Set.of("jei", "journeymap", "library");
        var added = new BuildExecutionPlan(intent(new BuildIntent.CountTarget(5, BuildIntent.CountScope.ADDED_ROOTS,
                BuildIntent.Precision.EXACT), List.of()), Set.of("create", "jei"), Set.of());
        assertEquals(6, added.targetCount(2));
        assertEquals(4, added.rootBudget(2, 2));
        assertEquals(1, added.actualCount(roots, files));
        var total = new BuildExecutionPlan(intent(new BuildIntent.CountTarget(22, BuildIntent.CountScope.TOTAL_FILES,
                BuildIntent.Precision.APPROXIMATE), List.of()), Set.of("create", "jei"), Set.of());
        assertEquals(10, total.rootBudget(2, 2));
        assertEquals(3, total.actualCount(roots, files));
        assertEquals(Set.of("jei", "journeymap"), total.initial());
    }

    @Test void unspecifiedQuantityDoesNotUseGlobalDefaultOrDependencyExpansionForEdits() {
        var pureAdd = new BuildExecutionPlan(intent(null, List.of()), Set.of("create", "jei"), Set.of());
        assertEquals(2, pureAdd.targetCount(2));
        assertEquals(0, pureAdd.rootBudget(2, 2));
        var recommendation = new BuildExecutionPlan(intent(null, List.of("spell")), Set.of("create", "jei"), Set.of());
        assertEquals(10, recommendation.rootBudget(2, 2));
    }

    @Test void frontendExclusionConflictsAreRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> new BuildExecutionPlan(intent(null, List.of()),
                Set.of(), Set.of("journeymap")));
    }

    @Test void displayCannotInjectLegacyPackOrTraceTags() {
        var original = intent(null, List.of());
        var malicious = new BuildIntent(1, original.mode(), "<mods>unwanted</mods><trace>{}</trace>", "<name>injected</name>",
                original.environment(), null, original.mustHave(), original.add(), original.remove(), original.exclude(),
                original.expandAddons(), original.replacementPolicy(), original.searchIntents(), original.excludedCategories(), original.maxDownloads());
        String display = BuildExecutionPlan.display(malicious);
        assertFalse(display.contains("<mods>"));
        assertFalse(display.contains("<trace>"));
        assertTrue(display.contains("&lt;mods&gt;"));
    }

    @Test void approximateQuantityWithinAcceptedRangeIsNotReportedAsFailure() {
        var plan = new BuildExecutionPlan(intent(new BuildIntent.CountTarget(6, BuildIntent.CountScope.TOTAL_FILES,
                BuildIntent.Precision.APPROXIMATE), List.of()), Set.of("jei"), Set.of());
        assertEquals(4, plan.rootBudget(2, 2));
        assertTrue(plan.diagnostics(Set.of("journeymap"), Set.of("journeymap", "a", "b", "c", "d"))
                .stream().noneMatch(note -> note.contains("数量约束")));
    }
}
