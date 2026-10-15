package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import yagen.waitmydawn.maa.model.RequirementRetrieval;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CriteriaPlanningTest {
    @Test void inventedCapabilityEnumHasAllowedValuesAndGenericGoalRepairGuidance() {
        var codec = new AgentContractCodec();
        var p = plan("世界生成", List.of(goal("世界生成", "worldgen", "world generation")), List.of());
        var tree = (ObjectNode)new ObjectMapper().readTree(codec.write(p));
        ((ObjectNode)tree.path("requirements").get(0)).putArray("capabilities").add("WORLD_GENERATION");
        var error = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(tree.toString()));
        assertTrue(error.failure().guidance().contains("capabilities 可为空"));
        assertTrue(error.failure().guidance().contains("TERRAIN"));
        assertEquals("/requirements/0/capabilities/0", error.failure().pointer());
    }
    @Test void mistakenParentFieldInCriterionHasSafeIndexedRepairGuidance() {
        var codec = new AgentContractCodec();
        var plan = plan("群系", List.of(goal("群系", "worldgen", "biomes")), List.of());
        var tree = (ObjectNode)new ObjectMapper().readTree(codec.write(plan));
        ((ObjectNode)tree.path("requirements").get(0).path("criteria").get(0)).putArray("capabilities").add("BIOMES");
        var error = assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(tree.toString()));
        assertEquals("requirements.criteria.capabilities", error.failure().field());
        assertEquals("/requirements/0/criteria/0/capabilities", error.failure().pointer());
        assertTrue(RequirementPlanning.repairInput("original", tree.toString(), error).contains("criteria 子目标没有 capabilities"));
        assertNull(error.getCause());
    }
    private RequirementCriterion goal(String source, String category, String keyword) {
        return new RequirementCriterion(source, BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(new BuildIntent.SearchIntent(category, 100, List.of(keyword))));
    }
    private BuildIntent plan(String source, List<RequirementCriterion> criteria, List<String> projects) {
        var r = new BuildRequirement(RequirementPlanning.anchors(source).keySet().iterator().next(), source, BuildRequirement.Kind.CAPABILITY,
                projects, RequirementPlanning.capabilities(source), List.of(), List.of(), SelectionPreferences.DEFAULT, criteria);
        return new BuildIntent(3, BuildIntent.Mode.NEW, "组合", "", BuildEnvironment.current("1.21.1", "neoforge"),
                new BuildIntent.CountTarget(20, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE), projects,
                List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L, ConstraintChanges.KEEP, List.of(r));
    }
    @Test void genericSemanticChildrenDoNotRequirePredefinedCapabilitiesAndKeepIndependentQueries() {
        String source = "工厂自动化和大型仓储";
        var intent = plan(source, List.of(goal("工厂自动化", "technology", "automation"), goal("大型仓储", "storage", "storage")), List.of());
        assertDoesNotThrow(() -> RequirementPlanning.bind(source, intent, Map.of()));
        var targets = RequirementRetrieval.active(intent, Set.of());
        assertEquals(2, targets.size()); assertNotEquals(targets.getFirst().id(), targets.get(1).id());
        assertEquals(List.of("automation", "storage"), RequirementRetrieval.queries(targets, Set.of()).stream().map(RequirementRetrieval.Query::keyword).toList());
        assertEquals(List.of("和平探索", "共和国工业"), RequirementPlanning.criterionSources("和平探索，共和国工业"));
    }
    @Test void missingKnownChildInventedTextOrMisplacedNamedUiCannotPassBinding() {
        String source = "优化、现代化UI和修复";
        var ui = new RequirementCriterion("现代化UI", BuildRequirement.Kind.PROJECTS, List.of("modern-ui"), List.of(), List.of());
        var valid = plan(source, List.of(goal("优化", "optimization", "performance"), ui, goal("修复", "utility", "bug fixes")), List.of("modern-ui"));
        assertDoesNotThrow(() -> RequirementPlanning.bind(source, valid, Map.of("现代化UI", "modern-ui")));
        assertThrows(RequirementPlanning.InvalidPlan.class, () -> RequirementPlanning.bind(source,
                plan(source, List.of(goal("优化", "optimization", "performance"), ui), List.of("modern-ui")), Map.of("现代化UI", "modern-ui")));
        assertThrows(RequirementPlanning.InvalidPlan.class, () -> RequirementPlanning.bind(source,
                plan(source, List.of(goal("优化、现代化UI和修复", "optimization", "performance")), List.of()), Map.of()));
        assertThrows(RequirementPlanning.InvalidPlan.class, () -> RequirementPlanning.bind(source,
                plan(source, List.of(goal("优化", "optimization", "performance"), goal("现代化UI", "utility", "hud"), goal("修复", "utility", "bug fixes")), List.of("modern-ui")), Map.of("现代化UI", "modern-ui")));
    }
    @Test void versionThreeRequiresCriterionFieldAndLegacyVersionTwoMigratesConservatively() {
        var codec = new AgentContractCodec();
        var p = plan("烹饪", List.of(goal("烹饪", "food", "cooking")), List.of());
        assertEquals(p, codec.readBuildIntent(codec.write(p)));
        var json = (ObjectNode)new ObjectMapper().readTree(codec.write(p));
        ((ObjectNode)json.path("requirements").get(0)).remove("criteria");
        assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readBuildIntent(json.toString()));
        json.put("schemaVersion", 2);
        assertTrue(codec.readBuildIntent(json.toString()).requirements().getFirst().criteria().isEmpty());
    }
    @Test void semanticCapabilityHintsDoNotRequireLiteralCatalogVocabulary() {
        String source = "改善渲染与内存效率";
        var base = plan(source, List.of(goal(source, "optimization", "rendering memory")), List.of());
        var r = base.requirements().getFirst();
        var semantic = new BuildRequirement(r.id(), r.source(), r.kind(), r.projects(), List.of(BuildRequirement.Capability.PERFORMANCE),
                r.addonCores(), r.searches(), r.preferences(), r.criteria());
        var intent = new BuildIntent(3, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(),
                base.add(), base.remove(), base.exclude(), base.expandAddons(), base.replacementPolicy(), base.searchIntents(),
                base.excludedCategories(), base.maxDownloads(), base.constraintChanges(), List.of(semantic));
        assertDoesNotThrow(() -> RequirementPlanning.bind(source, intent, Map.of()));
    }
    @Test void editReleaseAndReplacementPreserveIndependentRemainingGoalsAndPreferences() {
        var p = plan("工厂自动化和仓储", List.of(goal("工厂自动化", "technology", "automation"), goal("仓储", "storage", "storage")), List.of());
        var parent = p.requirements().getFirst();
        var prior = BuildConstraints.empty().apply(p, Set.of()).constraints();
        var changes = new ConstraintChanges(List.of(), List.of(), false, false, false, List.of(parent.criteria().get(1).key(parent.id())), List.of());
        var edit = new BuildIntent(3, BuildIntent.Mode.EDIT, "", "", p.environment(), null, List.of(), List.of(), List.of(), List.of(), List.of(),
                p.replacementPolicy(), List.of(), List.of(), p.maxDownloads(), changes, List.of());
        var remaining = prior.apply(edit, Set.of()).constraints().requirements().getFirst();
        assertEquals(List.of("工厂自动化"), remaining.criteria().stream().map(RequirementCriterion::source).toList());
        var c = new RequirementCriterion("Alex生物", BuildRequirement.Kind.PROJECTS, List.of("alexs-mobs"), List.of(), List.of());
        assertEquals(List.of("port-a", "port-b"), c.replace(Map.of("alexs-mobs", List.of("port-a", "port-b"))).projects());
        assertEquals(parent.preferences(), remaining.preferences());
    }

    @Test void pureNamedUiDoesNotRequireSemanticGoalsOrAdditionalUiRecommendations() {
        String source = "包含现代化UI";
        var r = new BuildRequirement(RequirementPlanning.anchors(source).keySet().iterator().next(), source, BuildRequirement.Kind.PROJECTS,
                List.of("modern-ui"), RequirementPlanning.capabilities(source));
        var p = new BuildIntent(3, BuildIntent.Mode.EDIT, "", "", BuildEnvironment.current("1.21.1", "neoforge"), null,
                r.projects(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(),
                2100000000L, ConstraintChanges.KEEP, List.of(r));
        assertDoesNotThrow(() -> RequirementPlanning.bind(source, p, Map.of("现代化UI", "modern-ui")));
        assertTrue(yagen.waitmydawn.maa.model.GoalCatalog.active(p, 100).isEmpty());
    }
    @Test void deletingAddonCoreKeepsUnrelatedNamedChildAndClearsInactiveRelationTargets() {
        String source = "核心附属和界面";
        var children = List.of(new RequirementCriterion("核心附属", BuildRequirement.Kind.ADDONS, List.of("core"), List.of("core"), List.of()),
                new RequirementCriterion("界面", BuildRequirement.Kind.PROJECTS, List.of("modern-ui"), List.of(), List.of()));
        var r = new BuildRequirement(RequirementPlanning.anchors(source).keySet().iterator().next(), source, BuildRequirement.Kind.ADDONS,
                List.of("core", "modern-ui"), List.of(), List.of("core"), List.of(), SelectionPreferences.DEFAULT, children);
        var prior = new BuildConstraints(3, r.projects(), List.of(), List.of(), null, BuildIntent.ReplacementPolicy.NONE, 2100000000L, List.of(r));
        var edit = new BuildIntent(3, BuildIntent.Mode.EDIT, "", "", BuildEnvironment.current("1.21.1", "neoforge"), null,
                List.of(), List.of(), List.of("core"), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L);
        var next = prior.apply(edit, Set.of()).constraints();
        assertEquals(List.of("modern-ui"), next.mustHave());
        assertEquals(List.of("界面"), next.requirements().getFirst().criteria().stream().map(RequirementCriterion::source).toList());
        assertTrue(next.requirements().getFirst().addonCores().isEmpty());
    }

    @Test void duplicateNamedProjectionCanBeCompletedOnlyForAlreadyAuthorizedOriginalAliases() {
        String source = "有很多优化模组，现代化UI和修复";
        var original = plan(source, List.of(goal("优化", "optimization", "performance"),
                new RequirementCriterion("现代化UI", BuildRequirement.Kind.PROJECTS, List.of("modern-ui"), List.of(), List.of()),
                goal("修复", "utility", "bug fixes")), List.of("modern-ui"));
        var old = original.requirements().getFirst();
        var missingParent = new BuildRequirement(old.id(), old.source(), old.kind(), List.of(), old.capabilities(), old.addonCores(), old.searches(), old.preferences(), old.criteria());
        var intent = new BuildIntent(3, original.mode(), original.reply(), original.name(), original.environment(), original.count(), original.mustHave(),
                original.add(), original.remove(), original.exclude(), original.expandAddons(), original.replacementPolicy(), original.searchIntents(),
                original.excludedCategories(), original.maxDownloads(), original.constraintChanges(), List.of(missingParent));
        var bound = RequirementPlanning.bind(source, intent, Map.of("现代化UI", "modern-ui"));
        assertEquals(List.of("modern-ui"),bound.requirements().getFirst().projects());
        assertEquals(original.mustHave(),bound.mustHave());
        var unauthorized = new BuildIntent(3,intent.mode(),intent.reply(),intent.name(),intent.environment(),intent.count(),List.of(),
                intent.add(),intent.remove(),intent.exclude(),intent.expandAddons(),intent.replacementPolicy(),intent.searchIntents(),
                intent.excludedCategories(),intent.maxDownloads(),intent.constraintChanges(),intent.requirements());
        assertThrows(RequirementPlanning.InvalidPlan.class,()->RequirementPlanning.bind(source,unauthorized,Map.of("现代化UI","modern-ui")));
    }

    @Test void repeatedAddonIdentityUsesAliasMappingWithoutAddingAnUnrequestedProject() {
        String source = "以机械核心为核心，多补充机械核心附属";
        String id = RequirementPlanning.anchors(source).keySet().iterator().next();
        var children = List.of(new RequirementCriterion("以机械核心为核心",BuildRequirement.Kind.PROJECTS,List.of("alpha-core"),List.of(),List.of()),
                new RequirementCriterion("多补充机械核心附属",BuildRequirement.Kind.ADDONS,List.of(),List.of("alpha-core"),List.of()));
        var requirement = new BuildRequirement(id,source,BuildRequirement.Kind.ADDONS,List.of("alpha-core"),List.of(),List.of("alpha-core"),List.of(),SelectionPreferences.DEFAULT,children);
        var intent = new BuildIntent(3,BuildIntent.Mode.NEW,"组合","",BuildEnvironment.current("1.21.1","neoforge"),
                new BuildIntent.CountTarget(10,BuildIntent.CountScope.TOTAL_FILES,BuildIntent.Precision.APPROXIMATE),
                List.of("alpha-core"),List.of(),List.of(),List.of(),List.of("alpha-core"),BuildIntent.ReplacementPolicy.NONE,List.of(),List.of(),2100000000L,
                ConstraintChanges.KEEP,List.of(requirement));
        var bound = RequirementPlanning.bind(source,intent,Map.of("机械核心","alpha-core"));
        assertEquals(List.of("alpha-core"),bound.requirements().getFirst().criteria().get(1).projects());
        assertEquals(intent.expandAddons(),bound.expandAddons());
        assertEquals(intent.mustHave(),bound.mustHave());
    }
}
