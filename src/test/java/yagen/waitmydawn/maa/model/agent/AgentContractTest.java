package yagen.waitmydawn.maa.model.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import yagen.waitmydawn.maa.model.CategoryRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentContractTest {
    @Test void repeatedSearchCategoriesHaveIndexedRepairGuidanceAtEveryPlanningLevel() {
        var search = new BuildIntent.SearchIntent("optimization", 100, List.of("memory"));
        var child = new RequirementCriterion("优化内存", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(search));
        var requirement = new BuildRequirement("r-0000000000000001", "优化内存", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(BuildRequirement.Capability.PERFORMANCE), List.of(), List.of(search), SelectionPreferences.DEFAULT, List.of(child));
        var root = buildJson();
        var row = (ObjectNode) mapper.valueToTree(requirement);
        root.withArray("requirements").add(row);
        row.withArray("searches").add(mapper.valueToTree(new BuildIntent.SearchIntent("optimization", 50, List.of("private-search-marker"))));
        var criterion = (ObjectNode) row.withArray("criteria").get(0);
        criterion.withArray("searches").add(mapper.valueToTree(search));
        var error = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(root.toString()));
        assertEquals("/requirements/0/searches/1/category", error.failure().pointer());
        assertTrue(error.failure().guidance().contains("category 只能出现一次"));
        assertFalse(error.getMessage().contains("private-search-marker"));
        row.withArray("searches").remove(1);
        error = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(root.toString()));
        assertEquals("/requirements/0/criteria/0/searches/1/category", error.failure().pointer());
        criterion.withArray("searches").remove(1);
        assertDoesNotThrow(() -> codec.readBuildIntent(root.toString()));
        root.withArray("searchIntents").add(root.withArray("searchIntents").get(0).deepCopy());
        error = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(root.toString()));
        assertEquals("/searchIntents/2/category", error.failure().pointer());
    }
    @Test void semanticFieldSurvivesBuildIntentParsingAndNoRawCauseEscapes() {
        var invalid = buildJson();
        invalid.withArray("exclude").add("irons-spells-n-spellbooks");
        var error = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(invalid.toString()));
        assertEquals(AgentContractCodec.FailureKind.SEMANTIC, error.failure().kind());
        assertEquals("mustHave/exclude", error.failure().field());
        assertNull(error.getCause());
        var unknown = buildJson().put("private-marker-not-a-schema-field", "private-value");
        var shape = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(unknown.toString()));
        assertEquals(AgentContractCodec.FailureKind.STRUCTURE, shape.failure().kind());
        assertFalse(shape.getMessage().contains("private-marker"));
        assertFalse(shape.getMessage().contains("private-value"));
        assertNull(shape.getCause());
    }
    @Test void syntaxMissingFieldAndScalarTypeAreDistinguishable() {
        assertEquals(AgentContractCodec.FailureKind.JSON_SYNTAX,
                assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent("{" )).failure().kind());
        var missing = buildJson(); missing.remove("mode");
        assertEquals("mode", assertThrows(AgentContractCodec.ValidationException.class,
                () -> codec.readBuildIntent(missing.toString())).failure().field());
        var type = buildJson().put("maxDownloads", "private-value");
        var invalid = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(type.toString()));
        assertEquals(AgentContractCodec.FailureKind.STRUCTURE, invalid.failure().kind());
        assertEquals("maxDownloads", invalid.failure().field());
    }
    private final AgentContractCodec codec = new AgentContractCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private BuildIntent build() {
        return new BuildIntent(1, BuildIntent.Mode.NEW, "铁魔法冒险模组组合", "铁魔法冒险纪",
                new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, true),
                new BuildIntent.CountTarget(60, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of("irons-spells-n-spellbooks", "alexs-caves-(unofficial-port)"),
                List.of("irons-spells-n-spellbooks", "alexs-caves-(unofficial-port)", "alexs-mobs(1.21.1)"),
                List.of(), List.of(), List.of("irons-spells-n-spellbooks"), BuildIntent.ReplacementPolicy.COMPATIBLE_PORTS,
                List.of(new BuildIntent.SearchIntent("adventure", 60, List.of("dungeon", "boss", "loot")),
                        new BuildIntent.SearchIntent("magic", 40, List.of("spell", "ritual", "mana"))),
                List.of("technology"), 2_100_000_000L);
    }

    private ObjectNode buildJson() { return (ObjectNode) mapper.readTree(codec.write(build())); }

    @Test void buildRoundTripPreservesNamedCoreEnvironmentAndCountScope() {
        assertEquals(build(), codec.readBuildIntent(codec.write(build())));
        var json = buildJson();
        json.withObject("count").put("scope", "ROOT_MODS").put("precision", "EXACT");
        var parsed = codec.readBuildIntent(json.toString());
        assertEquals(BuildIntent.CountScope.ROOT_MODS, parsed.count().scope());
        assertEquals(BuildIntent.Precision.EXACT, parsed.count().precision());
    }

    @Test void missingUnknownAndNullPrimitiveFieldsAreRejected() {
        for (String field : List.of("mode", "schemaVersion", "searchIntents", "replacementPolicy", "maxDownloads")) {
            var json = buildJson();
            json.remove(field);
            assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(json.toString()), field);
        }
        var unknown = buildJson().put("runtimeVerified", true);
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(unknown.toString()));
        var nullCount = buildJson();
        nullCount.withObject("count").putNull("value");
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(nullCount.toString()));
    }

    @Test void malformedVersionCoercionsAndTrailingOutputAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent("null"));
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent("```json\n" + codec.write(build()) + "\n```"));
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(codec.write(build()) + " {}"));
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(buildJson().put("schemaVersion", 4).toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(buildJson().put("schemaVersion", "1").toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(buildJson().put("mode", 0).toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(buildJson().put("name", 123).toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(codec.write(build()).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")));
        var floatCount = buildJson();
        floatCount.withObject("count").put("value", 60.5);
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(floatCount.toString()));
    }

    @Test void contradictoryConstraintsAndOutOfRangeValuesAreRejected() {
        var excludedCore = buildJson();
        excludedCore.withArray("exclude").add("irons-spells-n-spellbooks");
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(excludedCore.toString()));
        var excludedCategory = buildJson();
        excludedCategory.withArray("excludedCategories").add("magic");
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(excludedCategory.toString()));
        var largeCount = buildJson();
        largeCount.withObject("count").put("value", 501);
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(largeCount.toString()));
        var duplicate = buildJson();
        duplicate.withArray("add").add("irons-spells-n-spellbooks");
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(duplicate.toString()));
        assertThrows(IllegalArgumentException.class, () -> new BuildIntent.SearchIntent("tech", 50, List.of("machine")));
        assertThrows(IllegalArgumentException.class, () -> new BuildIntent.SearchIntent("magic", 0, List.of("spell")));
    }

    @Test void refusalClarificationAndEmptyPackCannotCarryHiddenBuildOperations() {
        for (var mode : List.of(BuildIntent.Mode.REJECT, BuildIntent.Mode.CLARIFY)) {
            var passive = new BuildIntent(1, mode, "请明确构建需求", "", null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                    List.of(), List.of(), 2_100_000_000L);
            assertEquals(passive, codec.readBuildIntent(codec.write(passive)));
            var json = (ObjectNode) mapper.readTree(codec.write(passive));
            json.withArray("add").add("jei");
            assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(json.toString()));
        }
        var empty = new BuildIntent(1, BuildIntent.Mode.EMPTY, "空图谱", "",
                build().environment(), new BuildIntent.CountTarget(0, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.EXACT),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2_100_000_000L);
        assertEquals(empty, codec.readBuildIntent(codec.write(empty)));
        var json = (ObjectNode) mapper.readTree(codec.write(empty));
        json.withArray("add").add("jei");
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(json.toString()));
    }

    @Test void editCanLeaveCountUnchangedWithoutImplicitDefault() {
        var edit = new BuildIntent(1, BuildIntent.Mode.EDIT, "新增一个地图", "", build().environment(), null,
                List.of(), List.of("journeymap"), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2_100_000_000L);
        assertNull(codec.readBuildIntent(codec.write(edit)).count());
    }

    @Test void reviewKeepsExplicitVetoAndEnforcesPoolMembershipAndBudget() {
        var review = new ReviewDecision(1, List.of("jei"),
                List.of(new ReviewDecision.Rejection("create", ReviewDecision.Reason.OFF_TOPIC, "本轮仅调整地图与信息界面")), true);
        assertEquals(review, codec.readReviewDecision(codec.write(review)));
        assertDoesNotThrow(() -> review.validateAgainst(Set.of("jei", "create"), 1));
        assertThrows(IllegalArgumentException.class, () -> review.validateAgainst(Set.of("jei"), 1));
        assertThrows(IllegalArgumentException.class, () -> review.validateAgainst(Set.of("jei", "create"), 0));
        assertThrows(IllegalArgumentException.class, () -> new ReviewDecision(1, List.of("create"), review.rejected(), true));
        assertDoesNotThrow(() -> new ReviewDecision(1, List.of(), List.of(), false));
    }

    @Test void toolResultDoesNotClaimAppliedChangesForFailureOrNoChange() {
        var issue = new ToolResult.Issue("not-a-project", ToolResult.IssueCode.UNKNOWN_PROJECT, "未找到项目");
        var result = new ToolResult(1, "round-1.op-2", ToolResult.Tool.REMOVE_MODS, ToolResult.Status.PARTIAL,
                "部分操作已应用", List.of("create"), List.of(issue));
        assertEquals(result, codec.readToolResult(codec.write(result)));
        assertThrows(IllegalArgumentException.class, () -> new ToolResult(1, "op-1", ToolResult.Tool.REMOVE_MODS,
                ToolResult.Status.REJECTED, "拒绝", List.of("jei"), List.of(issue)));
        assertThrows(IllegalArgumentException.class, () -> new ToolResult(1, "op-1", ToolResult.Tool.REMOVE_MODS,
                ToolResult.Status.APPLIED, "已完成", List.of(), List.of()));
    }

    @Test void contractListsAreImmutableCopies() {
        var approved = new ArrayList<>(List.of("jei"));
        var result = new ReviewDecision(1, approved, List.of(), false);
        approved.add("create");
        assertEquals(List.of("jei"), result.approved());
        assertThrows(UnsupportedOperationException.class, () -> result.approved().add("create"));
    }

    @Test void schemasTrackRecordFieldsEnumsAndCategoryRegistry() {
        var schemas = List.of(AgentSchemas.buildIntent(), AgentSchemas.reviewDecision(), AgentSchemas.toolResult());
        for (var schema : schemas) {
            assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText());
            assertFalse(schema.path("additionalProperties").asBoolean(true));
            assertEquals(schema.path("properties").size(), schema.path("required").size());
        }
        var categories = AgentSchemas.buildIntent().path("properties").path("searchIntents").path("items")
                .path("properties").path("category").path("enum");
        assertEquals(CategoryRegistry.CATEGORIES.size(), categories.size());
        for (int i = 0; i < categories.size(); i++) assertEquals(CategoryRegistry.CATEGORIES.get(i), categories.get(i).asText());
        assertEquals(500, AgentSchemas.buildIntent().path("properties").path("count").path("anyOf").get(0)
                .path("properties").path("value").path("maximum").asInt());
    }
}
