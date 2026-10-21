package yagen.waitmydawn.maa.model.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import yagen.waitmydawn.maa.service.RequirementPlanning;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementQuantityContractTest {
    private final String prompt = "新建整合包40-70个模组\n补充物流模组2-4个";
    private BuildIntent plan() { return plan(prompt); }
    private BuildIntent plan(String input) {
        var requirements = RequirementPlanning.anchors(input).entrySet().stream().map(e -> {
            boolean local = e.getValue().contains("物流");
            var searches = local ? List.of(new BuildIntent.SearchIntent("technology", 100, List.of("logistics"))) : List.<BuildIntent.SearchIntent>of();
            var criteria = local ? List.of(new RequirementCriterion(e.getValue(), BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), searches))
                    : List.<RequirementCriterion>of();
            return new BuildRequirement(e.getKey(), e.getValue(), local ? BuildRequirement.Kind.CAPABILITY : BuildRequirement.Kind.GENERAL,
                    List.of(), List.of(), List.of(), searches, SelectionPreferences.DEFAULT, criteria);
        }).toList();
        return new BuildIntent(3, BuildIntent.Mode.NEW, "", "物流", new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, false),
                new BuildIntent.CountTarget(55, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE, 40, 70),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), Long.MAX_VALUE,
                ConstraintChanges.KEEP, requirements);
    }
    @Test void anUnambiguouslyImpossibleLocalMinimumRequestsClarification() {
        String input = "新建整合包2-3个模组\n补充物流模组6-8个";
        var codec = new AgentContractCodec(); var root = (ObjectNode)new ObjectMapper().readTree(codec.write(plan(input)));
        ((ObjectNode)root.path("count")).put("value",3).put("min",2).put("max",3);
        var error = assertThrows(RequirementPlanning.InvalidPlan.class, () -> RequirementPlanning.bind(input,codec.readBuildIntent(root.toString()),Map.of()));
        assertEquals("requirements.quantity.conflict", error.field());
    }
    @Test void localRangeDoesNotOverrideTheWholePackRange() {
        assertDoesNotThrow(() -> RequirementPlanning.bind(prompt, plan(), Map.of()));
        var json = new ObjectMapper(); var root = (ObjectNode) json.readTree(new AgentContractCodec().write(plan()));
        var count = (ObjectNode) root.path("count"); count.put("value",3).put("min",2).put("max",4);
        assertThrows(RequirementPlanning.InvalidPlan.class, () -> RequirementPlanning.bind(prompt, new AgentContractCodec().readBuildIntent(root.toString()), Map.of()));
    }
    @Test void schemaRoundTripAndOldPlansPreserveTheExplicitChildRange() {
        var codec = new AgentContractCodec();
        var root = (ObjectNode) new ObjectMapper().readTree(codec.write(plan()));
        for (var r : root.path("requirements")) {
            ((ObjectNode)r).remove("quantity");
            for (var c : r.path("criteria")) ((ObjectNode)c).remove("quantity");
        }
        var restored = codec.readBuildIntent(root.toString());
        assertDoesNotThrow(() -> RequirementPlanning.bind(prompt, restored, Map.of()));
        var target = restored.requirements().stream().flatMap(r -> r.criteria().stream().map(c -> c.target(r))).findFirst().orElseThrow();
        assertEquals(new RequirementQuantity(2,4,RequirementQuantity.Scope.ADDED_ROOTS), target.quantity());
        assertTrue(AgentSchemas.buildIntent().path("properties").path("requirements").path("items").path("properties").has("quantity"));
    }
    @Test void alteredOrIllTypedLocalQuantitiesRemainInvalid() {
        var codec = new AgentContractCodec(); var root = (ObjectNode) new ObjectMapper().readTree(codec.write(plan()));
        ObjectNode child = null;
        for (var r : root.path("requirements")) for (var c : r.path("criteria")) child = (ObjectNode)c;
        var q = (ObjectNode) child.path("quantity"); q.put("max",5);
        assertThrows(RequirementPlanning.InvalidPlan.class, () -> RequirementPlanning.bind(prompt, codec.readBuildIntent(root.toString()), Map.of()));
        q.put("min","2");
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(root.toString()));
    }
}
