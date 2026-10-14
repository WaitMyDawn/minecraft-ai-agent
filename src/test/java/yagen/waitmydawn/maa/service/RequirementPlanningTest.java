package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementPlanningTest {
    private static final Map<String, String> ALIASES = Map.of("alex生物", "alexs-mobs", "海灵物语", "aquamirae", "农夫乐事", "farmers-delight", "ae2", "ae2");
    private BuildIntent plan(int version, List<String> required, BuildIntent.CountTarget count, List<BuildRequirement> requirements) {
        return new BuildIntent(version, BuildIntent.Mode.NEW, "组合", "", BuildEnvironment.current("1.21.1", "neoforge"),
                count, required, List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.COMPATIBLE_PORTS,
                List.of(), List.of(), 2100000000L, ConstraintChanges.KEEP, requirements);
    }
    private BuildIntent.CountTarget count() { return new BuildIntent.CountTarget(10, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE); }
    private BuildRequirement requirement(String prompt, BuildRequirement.Kind kind, List<String> projects, List<BuildRequirement.Capability> capabilities) {
        var anchor = RequirementPlanning.anchors(prompt).entrySet().iterator().next();
        return new BuildRequirement(anchor.getKey(), anchor.getValue(), kind, projects, capabilities);
    }

    @Test void knownChineseNamesCannotDisappearFromStructurallyValidPlan() {
        String prompt = "包含 Alex 生物、海灵物语";
        assertThrows(IllegalArgumentException.class, () -> RequirementPlanning.bind(prompt, plan(1, List.of("alexs-mobs"), count(), List.of()), ALIASES));
        var bound = RequirementPlanning.bind(prompt, plan(1, List.of("alexs-mobs", "aquamirae"), count(), List.of()), ALIASES);
        assertEquals(Set.of("alexs-mobs", "aquamirae"), Set.copyOf(bound.requirements().getFirst().projects()));
        assertEquals(prompt, bound.requirements().getFirst().source());
    }
    @Test void exclusionsHypotheticalsAndExamplesDoNotRequireThoseProjects() {
        for (String prompt : List.of("不包含Alex生物、海灵物语", "如果有海灵物语可以做联动", "例如海灵物语", "不要Alex生物，包含海灵物语")) {
            var required = prompt.endsWith("包含海灵物语") ? List.of("aquamirae") : List.<String>of();
            assertDoesNotThrow(() -> RequirementPlanning.bind(prompt, plan(1, required, count(), List.of()), ALIASES));
        }
        assertDoesNotThrow(() -> RequirementPlanning.bind("添加ae2-things", plan(1, List.of("ae2-things"), count(), List.of()), ALIASES));
    }
    @Test void exactSlugInInclusionListCannotBeDropped() {
        assertThrows(IllegalArgumentException.class, () -> RequirementPlanning.bind("包含海灵物语、yagens-attributes",
                plan(1, List.of("aquamirae"), count(), List.of()), ALIASES));
    }
    @Test void rangeKeepsBoundsAndDoesNotUseScalarTolerance() {
        String prompt = "大概100～200个模组";
        assertThrows(IllegalArgumentException.class, () -> RequirementPlanning.bind(prompt,
                plan(1, List.of(), new BuildIntent.CountTarget(150, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE), List.of()), ALIASES));
        var range = new BuildIntent.CountTarget(150, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE, 100, 200);
        assertDoesNotThrow(() -> RequirementPlanning.bind(prompt, plan(1, List.of(), range, List.of()), ALIASES));
        assertTrue(range.accepts(100)); assertTrue(range.accepts(200)); assertFalse(range.accepts(210));
        assertThrows(IllegalArgumentException.class, () -> new BuildIntent.CountTarget(150, range.scope(), range.precision(), 160, 200));
    }
    @Test void v2RequiresEveryOriginalAnchorAndItsCapabilityMapping() {
        String prompt = "丰富下界和末地生态";
        assertThrows(IllegalArgumentException.class, () -> RequirementPlanning.bind(prompt, plan(2, List.of(), count(), List.of()), ALIASES));
        var incomplete = requirement(prompt, BuildRequirement.Kind.CAPABILITY, List.of(), List.of(BuildRequirement.Capability.NETHER));
        assertThrows(IllegalArgumentException.class, () -> RequirementPlanning.bind(prompt, plan(2, List.of(), count(), List.of(incomplete)), ALIASES));
        var complete = requirement(prompt, BuildRequirement.Kind.CAPABILITY, List.of(), List.of(BuildRequirement.Capability.NETHER, BuildRequirement.Capability.END));
        assertDoesNotThrow(() -> RequirementPlanning.bind(prompt, plan(2, List.of(), count(), List.of(complete)), ALIASES));
    }
    @Test void unresolvedProjectIdentityMustClarifyBeforeAssembly() {
        String prompt = "包含一个不认识的名称";
        var unresolved = requirement(prompt, BuildRequirement.Kind.PROJECTS, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> RequirementPlanning.bind(prompt, plan(2, List.of(), count(), List.of(unresolved)), ALIASES));
    }
    @Test void replacementPreservesOriginalDemandAndAllowsMultipleChoices() {
        var original = requirement("包含Alex生物", BuildRequirement.Kind.PROJECTS, List.of("alexs-mobs"), List.of());
        var replaced = original.replace(Map.of("alexs-mobs", List.of("alexs-mobs-continued", "alexs-mobs-1.21.1")));
        assertEquals(original.id(), replaced.id()); assertEquals(original.source(), replaced.source());
        assertEquals(2, replaced.projects().size());
    }
    @Test void packIntegrationsCannotBeFlattenedIntoCategorySearch() {
        String prompt = "包含农夫乐事，特别是整合包内模组的联动附属";
        var generic = requirement(prompt, BuildRequirement.Kind.GENERAL, List.of("farmers-delight"), List.of());
        assertThrows(IllegalArgumentException.class, () -> RequirementPlanning.bind(prompt, plan(2, List.of("farmers-delight"), count(), List.of(generic)), ALIASES));
    }
    @Test void goalsPersistAcrossEditAndOnlyExplicitKnownIdsCanBeReleased() {
        var goal = requirement("丰富下界生态", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(BuildRequirement.Capability.NETHER));
        var old = new BuildConstraints(2, List.of(), List.of(), List.of(), count(), BuildIntent.ReplacementPolicy.NONE, 2100000000L, List.of(goal));
        var edit = new BuildIntent(2, BuildIntent.Mode.EDIT, "编辑", "", BuildEnvironment.current("1.21.1", "neoforge"), null,
                List.of(), List.of("jei"), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L, ConstraintChanges.KEEP, List.of());
        assertEquals(List.of(goal), old.apply(edit, Set.of()).constraints().requirements());
        var changes = new ConstraintChanges(List.of(), List.of(), false, false, false, List.of(goal.id()));
        var release = new BuildIntent(2, edit.mode(), edit.reply(), edit.name(), edit.environment(), null,
                edit.mustHave(), edit.add(), edit.remove(), edit.exclude(), edit.expandAddons(), edit.replacementPolicy(),
                edit.searchIntents(), edit.excludedCategories(), edit.maxDownloads(), changes, List.of());
        assertTrue(old.apply(release, Set.of()).constraints().requirements().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RequirementPlanning.bind("添加jei", release, ALIASES));
    }
    @Test void strictV2AndHistoricalV1RoundTripHaveSeparateMigrationRules() {
        var codec = new AgentContractCodec();
        var old = new tools.jackson.databind.ObjectMapper().readTree(codec.write(plan(1, List.of(), count(), List.of())));
        ((tools.jackson.databind.node.ObjectNode) old).remove("requirements");
        ((tools.jackson.databind.node.ObjectNode) old.get("count")).remove("min");
        ((tools.jackson.databind.node.ObjectNode) old.get("count")).remove("max");
        var migrated = codec.readBuildIntent(old.toString());
        assertEquals(10, migrated.count().min());
        ((tools.jackson.databind.node.ObjectNode) old).put("schemaVersion", 2);
        assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(old.toString()));
    }
    @Test void inlineNumberedGoalsHaveIndependentAnchors() {
        var anchors = RequirementPlanning.anchors("冒险主题 1）包含海灵物语 2）丰富下界生态 3）现代化UI");
        assertEquals(4, anchors.size());
        assertEquals(anchors, RequirementPlanning.anchors("冒险主题 1）包含海灵物语 2）丰富下界生态 3）现代化UI"));
    }
    @Test void mixedIdentityAndAddonRequirementsUseOnlyTheirAddonCores() {
        String prompt = "包含Alex生物和农夫乐事，特别是整合包内模组的联动附属";
        var original = requirement(prompt, BuildRequirement.Kind.PACK_INTEGRATIONS, List.of("alexs-mobs", "farmers-delight"), List.of());
        var entry = new BuildRequirement(original.id(), original.source(), original.kind(), original.projects(), original.capabilities(), List.of("farmers-delight"));
        var p = plan(2, original.projects(), count(), List.of(entry));
        var intent = new BuildIntent(p.schemaVersion(), p.mode(), p.reply(), p.name(), p.environment(), p.count(),
                p.mustHave(), p.add(), p.remove(), p.exclude(), List.of("farmers-delight"), p.replacementPolicy(),
                p.searchIntents(), p.excludedCategories(), p.maxDownloads(), p.constraintChanges(), p.requirements());
        assertDoesNotThrow(() -> RequirementPlanning.bind(prompt, intent, ALIASES));
    }
    @Test void cancellingOneCapabilityKeepsOtherTargetsInTheSameRequirement() {
        var entry = requirement("优化、现代UI和修复", BuildRequirement.Kind.CAPABILITY, List.of(),
                List.of(BuildRequirement.Capability.PERFORMANCE, BuildRequirement.Capability.MODERN_UI, BuildRequirement.Capability.FIXES));
        var previous = new BuildConstraints(2, List.of(), List.of(), List.of(), null, BuildIntent.ReplacementPolicy.NONE, 2100000000L, List.of(entry));
        var changes = new ConstraintChanges(List.of(), List.of(), false, false, false, List.of(), List.of(BuildRequirement.Capability.PERFORMANCE));
        var edit = new BuildIntent(2, BuildIntent.Mode.EDIT, "编辑", "", BuildEnvironment.current("1.21.1", "neoforge"), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L, changes, List.of());
        var retained = previous.apply(edit, Set.of()).constraints().requirements().getFirst();
        assertEquals(entry.id(), retained.id());
        assertEquals(List.of(BuildRequirement.Capability.MODERN_UI, BuildRequirement.Capability.FIXES), retained.capabilities());
    }
    @Test void biomeAndStructureRequestDoesNotImposeAnUnrequestedTerrainGoal() {
        String prompt = "丰富群系和结构";
        var incomplete = requirement(prompt, BuildRequirement.Kind.CAPABILITY, List.of(),
                List.of(BuildRequirement.Capability.BIOMES, BuildRequirement.Capability.STRUCTURES));
        assertDoesNotThrow(() -> RequirementPlanning.bind(prompt,
                plan(2, List.of(), count(), List.of(incomplete)), ALIASES));
        var complete = requirement(prompt, BuildRequirement.Kind.CAPABILITY, List.of(),
                List.of(BuildRequirement.Capability.TERRAIN, BuildRequirement.Capability.BIOMES, BuildRequirement.Capability.STRUCTURES));
        assertDoesNotThrow(() -> RequirementPlanning.bind(prompt, plan(2, List.of(), count(), List.of(complete)), ALIASES));
    }
    @Test void arbitraryCapabilityMustHaveAQueryPlanInsteadOfAnEmptyEnumMapping() {
        String prompt = "工厂自动化与物流";
        var empty = requirement(prompt, BuildRequirement.Kind.CAPABILITY, List.of(), List.of());
        assertThrows(RequirementPlanning.InvalidPlan.class, () -> RequirementPlanning.bind(prompt,
                plan(2, List.of(), count(), List.of(empty)), ALIASES));
        var searchable = new BuildRequirement(empty.id(), empty.source(), empty.kind(), List.of(), List.of(), List.of(),
                List.of(new BuildIntent.SearchIntent("technology", 100, List.of("factory automation", "logistics"))));
        assertDoesNotThrow(() -> RequirementPlanning.bind(prompt, plan(2, List.of(), count(), List.of(searchable)), ALIASES));
    }
    @Test void cancelledOrHistoricalRangeDoesNotOverrideCurrentQuantity() {
        var prompt = "取消100～200个模组限制";
        var changes = new ConstraintChanges(List.of(), List.of(), true, false, false);
        var edit = new BuildIntent(1, BuildIntent.Mode.EDIT, "编辑", "", BuildEnvironment.current("1.21.1", "neoforge"), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L, changes);
        assertDoesNotThrow(() -> RequirementPlanning.bind(prompt, edit, ALIASES));
        var target = new BuildIntent.CountTarget(150, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE, 120, 180);
        assertDoesNotThrow(() -> RequirementPlanning.bind("原来100～200个模组，改为120～180个模组", plan(1, List.of(), target, List.of()), ALIASES));
    }

    @Test void compositeAddonTargetsUseSourceSemanticsWithoutInventingCores() {
        for (String prompt : List.of("包含农夫乐事及其附属，特别是整合包内模组的乐事联动附属",
                "包含农夫乐事，多补充烹饪玩法和附属")) {
            var original = requirement(prompt, BuildRequirement.Kind.CAPABILITY, List.of("farmers-delight"), List.of());
            var queries = List.of(new BuildIntent.SearchIntent("food", 100, List.of("cooking")));
            var entry = new BuildRequirement(original.id(), original.source(), original.kind(), original.projects(),
                    original.capabilities(), List.of("farmers-delight"), queries);
            var p = plan(2, entry.projects(), count(), List.of(entry));
            var intent = new BuildIntent(p.schemaVersion(), p.mode(), p.reply(), p.name(), p.environment(), p.count(),
                    p.mustHave(), p.add(), p.remove(), p.exclude(), entry.addonCores(), p.replacementPolicy(),
                    p.searchIntents(), p.excludedCategories(), p.maxDownloads(), p.constraintChanges(), p.requirements());
            var bound = RequirementPlanning.bind(prompt, intent, ALIASES).requirements().getFirst();
            assertEquals(prompt.contains("联动") ? BuildRequirement.Kind.PACK_INTEGRATIONS : BuildRequirement.Kind.ADDONS, bound.kind());
            assertEquals(entry.projects(), bound.projects());
            assertEquals(queries, bound.searches());
            var missingCore = plan(2, entry.projects(), count(), List.of(original));
            var failure = assertThrows(RequirementPlanning.InvalidPlan.class,
                    () -> RequirementPlanning.bind(prompt, missingCore, ALIASES));
            assertEquals("requirements.addonCores", failure.field());
            assertTrue(failure.getMessage().contains("expandAddons"));
        }
    }

    @Test void repairReceivesPreviousFieldsAndRedactsCredentials() {
        String input = RequirementPlanning.repairInput("原文片段", "{\"kind\":\"CAPABILITY\",\"token\":\"sk-fixtureSecret123\"}",
                new IllegalArgumentException("requirements.addonCores"));
        assertTrue(input.contains("\"kind\":\"CAPABILITY\""));
        assertTrue(input.contains("PACK_INTEGRATIONS"));
        assertFalse(input.contains("sk-fixtureSecret123"));
        assertTrue(input.contains("原文片段"));
    }

    @Test void modernUiAliasIsAnExplicitProjectAndCannotBeReplacedWithHudEvidence() {
        var aliases = Map.of("现代化UI", "modern-ui");
        for (String prompt : List.of("有很多优化模组，现代化UI和修复", "现代化UI", "要求现代化UI")) {
            assertThrows(RequirementPlanning.InvalidPlan.class, () -> RequirementPlanning.bind(prompt,
                    plan(1, List.of(), count(), List.of()), aliases));
            var bound = RequirementPlanning.bind(prompt, plan(1, List.of("modern-ui"), count(), List.of()), aliases);
            assertTrue(bound.requirements().getFirst().projects().contains("modern-ui"));
        }
        assertDoesNotThrow(() -> RequirementPlanning.bind("不要现代化UI", plan(1, List.of(), count(), List.of()), aliases));
    }

    @Test void localFreshnessAndPopularityRemainSeparateAndSurviveEditAndReplacement() {
        String prompt = "想玩比较新、非热门的自动化模组";
        var anchor = RequirementPlanning.anchors(prompt).entrySet().iterator().next();
        var queries = List.of(new BuildIntent.SearchIntent("technology", 100, List.of("automation")));
        var preferences = new SelectionPreferences(SelectionPreferences.Popularity.LESS_POPULAR, SelectionPreferences.Freshness.RECENT);
        var missing = new BuildRequirement(anchor.getKey(), prompt, BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(), queries);
        assertThrows(RequirementPlanning.InvalidPlan.class, () -> RequirementPlanning.bind(prompt, plan(2, List.of(), count(), List.of(missing)), Map.of()));
        var entry = new BuildRequirement(anchor.getKey(), prompt, missing.kind(), missing.projects(), missing.capabilities(), missing.addonCores(), queries, preferences);
        var bound = RequirementPlanning.bind(prompt, plan(2, List.of(), count(), List.of(entry)), Map.of());
        var codec = new AgentContractCodec();
        assertEquals(preferences, codec.readBuildIntent(codec.write(bound)).requirements().getFirst().preferences());
        assertEquals(preferences, entry.replace(Map.of()).preferences());
        var constraints = BuildConstraints.empty().apply(bound, Set.of()).constraints();
        var edit = new BuildIntent(2, BuildIntent.Mode.EDIT, "", "", bound.environment(), null, List.of(), List.of(), List.of(),
                List.of(), List.of(), bound.replacementPolicy(), List.of(), List.of(), bound.maxDownloads(), ConstraintChanges.KEEP, List.of());
        assertEquals(preferences, constraints.apply(edit, Set.of()).constraints().requirements().getFirst().preferences());
        var tree = (tools.jackson.databind.node.ObjectNode)new tools.jackson.databind.ObjectMapper().readTree(codec.write(bound));
        ((tools.jackson.databind.node.ObjectNode)tree.path("requirements").get(0)).remove("preferences");
        assertEquals(SelectionPreferences.DEFAULT, codec.readBuildIntent(tree.toString()).requirements().getFirst().preferences());
        ((tools.jackson.databind.node.ObjectNode)tree.path("requirements").get(0)).putObject("preferences").put("popularity", "DEFAULT").put("freshness", "invalid");
        assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(tree.toString()));
    }

    @Test void confirmedDefaultRangeCannotCollapseAndNewScalarAnswerCanOverrideEarlierRange() {
        String question = "构建前请确认基本参数：neoforge 1.21.1，80～120 个模组文件（含前置）";
        assertThrows(RequirementPlanning.InvalidPlan.class, () -> RequirementPlanning.validateConfirmedBasics("冒险包\n用户答复：好的",
                plan(1, List.of(), count(), List.of()), question));
        var range = new BuildIntent.CountTarget(100, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE, 80, 120);
        assertDoesNotThrow(() -> RequirementPlanning.validateConfirmedBasics("冒险包\n用户答复：好的", plan(1, List.of(), range, List.of()), question));
        String prompt = "冒险包100～200个模组\n用户答复：改成60个";
        var target = new BuildIntent.CountTarget(60, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE);
        assertDoesNotThrow(() -> RequirementPlanning.bind(prompt, plan(1, List.of(), target, List.of()), Map.of()));
    }
}
