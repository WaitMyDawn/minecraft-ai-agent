package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComplementaryRetrievalTest {
    private BuildRequirement target(String id, Integer maximum) {
        return new BuildRequirement(id, "局部探索内容", BuildRequirement.Kind.CAPABILITY, List.of(),
                List.of(BuildRequirement.Capability.STRUCTURES), List.of(), List.of(), SelectionPreferences.DEFAULT, List.of(),
                maximum == null ? null : new RequirementQuantity(2, maximum, RequirementQuantity.Scope.ADDED_ROOTS));
    }
    private BuildIntent intent(BuildIntent.Mode mode, List<BuildRequirement> targets, List<String> excluded) {
        return new BuildIntent(3, mode, "", "", new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, false),
                new BuildIntent.CountTarget(55, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.APPROXIMATE, 40, 70),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), excluded,
                Long.MAX_VALUE, ConstraintChanges.KEEP, targets);
    }
    @Test void independentlyCappedTargetsCannotConsumeTheEntireGlobalCandidatePool() {
        var targets = List.of(target("r-0000000000000001", 10), target("r-0000000000000002", 5), target("r-0000000000000003", 4));
        var plan = ComplementaryRetrieval.plan(intent(BuildIntent.Mode.NEW, targets, List.of()), 2, false);
        assertEquals(38, plan.slots());
        assertEquals(3, plan.searches().size());
        assertTrue(plan.searches().stream().allMatch(s -> targets.stream().noneMatch(t -> t.searches().contains(s))));
        assertEquals(0, ComplementaryRetrieval.plan(intent(BuildIntent.Mode.EDIT, targets, List.of()), 2, false).slots());
        assertEquals(0, ComplementaryRetrieval.plan(intent(BuildIntent.Mode.NEW, targets, List.of()), 2, true).slots());
    }
    @Test void unboundedGoalsSufficientLocalCapacityAndExcludedCategoriesPreventUnnecessaryExpansion() {
        assertEquals(0, ComplementaryRetrieval.plan(intent(BuildIntent.Mode.NEW,
                List.of(target("r-0000000000000001", null)), List.of()), 2, false).slots());
        assertEquals(0, ComplementaryRetrieval.plan(intent(BuildIntent.Mode.NEW,
                List.of(target("r-0000000000000001", 50)), List.of()), 2, false).slots());
        var partial = ComplementaryRetrieval.plan(intent(BuildIntent.Mode.NEW,
                List.of(target("r-0000000000000001", 5)), List.of("utility")), 2, false);
        assertEquals(48, partial.slots());
        assertTrue(partial.searches().stream().noneMatch(s -> s.category().equals("utility")));
        assertEquals(0, ComplementaryRetrieval.plan(intent(BuildIntent.Mode.NEW,
                List.of(target("r-0000000000000001", 5)), List.of("utility", "storage", "game-mechanics")), 2, false).slots());
    }
    @Test void fileAndRootCountScopesBothReserveCandidatesWithoutEstimatingDependencies() {
        var base = intent(BuildIntent.Mode.NEW, List.of(target("r-0000000000000001", 5)), List.of());
        for (var scope : List.of(BuildIntent.CountScope.ROOT_MODS, BuildIntent.CountScope.TOTAL_FILES)) {
            var scoped = new BuildIntent(base.schemaVersion(), base.mode(), base.reply(), base.name(), base.environment(),
                    new BuildIntent.CountTarget(55, scope, BuildIntent.Precision.APPROXIMATE, 40, 70),
                    base.mustHave(), base.add(), base.remove(), base.exclude(), base.expandAddons(), base.replacementPolicy(),
                    base.searchIntents(), base.excludedCategories(), base.maxDownloads(), base.constraintChanges(), base.requirements());
            assertEquals(48, ComplementaryRetrieval.plan(scoped, 2, false).slots());
        }
    }
}
