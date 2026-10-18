package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementRetrievalTest {
    @Test void coreDimensionQueryPrecedesLocalContentWithinTheSameSharedBudget() {
        var dimension = new BuildRequirement("r-0000000000000002", "丰富下界生态", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(new BuildIntent.SearchIntent("adventure", 100, List.of("nether structures"))));
        var queries = RequirementRetrieval.queries(List.of(r("r-0000000000000001", "utility", "quality of life"), dimension), Set.of());
        assertEquals(new RequirementRetrieval.Query(dimension.id(), "worldgen", "nether terrain overhaul"), queries.getFirst());
        assertTrue(queries.stream().anyMatch(q -> q.keyword().equals("nether structures")));
        assertTrue(RequirementRetrieval.queries(List.of(dimension), Set.of("worldgen")).stream().noneMatch(q -> q.category().equals("worldgen")));
        var limited = new BuildRequirement(dimension.id(), "下界只加生物，不改地形", dimension.kind(), List.of(), List.of(), List.of(), dimension.searches());
        assertTrue(RequirementRetrieval.queries(List.of(limited), Set.of()).stream().noneMatch(q -> q.keyword().contains("overhaul")));
    }
    private BuildRequirement r(String id, String category, String... keywords) {
        return new BuildRequirement(id, "任意玩法要求", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(),
                List.of(new BuildIntent.SearchIntent(category, 100, List.of(keywords))));
    }
    @Test void arbitraryGoalsUseRoundRobinAndRespectExcludedCategories() {
        var rows = RequirementRetrieval.queries(List.of(r("r-0000000000000001", "technology", "automation", "logistics", "power"),
                r("r-0000000000000002", "storage", "storage", "sorting")), Set.of());
        assertEquals(List.of("automation", "storage", "logistics", "sorting", "power"), rows.stream().map(RequirementRetrieval.Query::keyword).toList());
        assertEquals(List.of("automation", "logistics", "power"), RequirementRetrieval.queries(
                List.of(r("r-0000000000000001", "technology", "automation", "logistics", "power"),
                        r("r-0000000000000002", "storage", "storage")), Set.of("storage"))
                .stream().map(RequirementRetrieval.Query::keyword).toList());
    }
    @Test void unmetArbitraryGoalPrecedesAnotherPopularCandidateForTheCoveredGoal() {
        var hints = Map.of("machine", Set.of("automation"), "popular-machine", Set.of("automation"), "warehouse", Set.of("storage"));
        assertEquals(List.of("warehouse", "popular-machine"), RequirementRetrieval.order(
                List.of("popular-machine", "warehouse"), hints, Set.of("machine")));
    }
    @Test void explicitSearchPlanOverridesKnownUtilityTemplateWithoutInventingFoodOrRecipeGoals() {
        var query = new BuildIntent.SearchIntent("storage", 100, List.of("storage sorting"));
        var r = new BuildRequirement("r-0000000000000001", "实用仓储整理", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(BuildRequirement.Capability.UTILITY), List.of(), List.of(query));
        var intent = new BuildIntent(2, BuildIntent.Mode.NEW, "组合", "", BuildEnvironment.current("1.21.1", "neoforge"),
                new BuildIntent.CountTarget(100, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2100000000L, ConstraintChanges.KEEP, List.of(r));
        assertEquals(List.of(), GoalCatalog.active(intent, 100));
        assertEquals(List.of(r), RequirementRetrieval.active(intent, Set.of()));
        assertEquals(List.of(new RequirementRetrieval.Query(r.id(), "storage", "storage sorting")), RequirementRetrieval.queries(List.of(r), Set.of()));
    }
    @Test void queriesSurviveHistoryAndReplacementWithoutBecomingCompatibilityClaims() {
        var requirement = r("r-0000000000000001", "technology", "automation");
        var state = new BuildConstraints(2, List.of(), List.of(), List.of(), null, BuildIntent.ReplacementPolicy.NONE, 2100000000L, List.of(requirement));
        var codec = new AgentContractCodec();
        assertEquals(state, codec.readConstraints(codec.write(state)));
        assertEquals(requirement.searches(), requirement.replace(Map.of()).searches());
        var historical = codec.write(state).replace(",\"searches\":[{\"category\":\"technology\",\"weight\":100,\"keywords\":[\"automation\"]}]", "");
        assertEquals(List.of(), codec.readConstraints(historical).requirements().getFirst().searches());
    }
}
