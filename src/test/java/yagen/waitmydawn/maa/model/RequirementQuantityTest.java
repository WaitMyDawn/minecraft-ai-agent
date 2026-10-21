package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementQuantityTest {
    @Test void keywordMatchesCannotConsumeAddonQuotaButOtherGoalsKeepTheirHints() {
        var addon = new BuildRequirement("r-0000000000000003", "补充核心附属2-3个", BuildRequirement.Kind.ADDONS,
                List.of(), List.of(), List.of("core"), List.of(), SelectionPreferences.DEFAULT, List.of(),
                new RequirementQuantity(2, 3, RequirementQuantity.Scope.ADDED_ROOTS));
        var hints = new LinkedHashMap<String, Set<String>>();
        for (String slug : List.of("keyword-one", "keyword-two", "keyword-three", "verified-addon", "other-core-addon"))
            hints.put(slug, Set.of(addon.id(), "other-goal"));
        RequirementQuantities.retainVerifiedAddonHints(hints, List.of(addon),
                Map.of("verified-addon", Set.of("core"), "other-core-addon", Set.of("unrelated-core")));
        assertEquals(Set.of("other-goal"), hints.get("keyword-one"));
        assertEquals(Set.of("other-goal"), hints.get("other-core-addon"));
        assertEquals(Set.of(addon.id(), "other-goal"), hints.get("verified-addon"));
        assertTrue(RequirementQuantities.allows("verified-addon", hints,
                Set.of("keyword-one", "keyword-two", "keyword-three"), Set.of(), List.of(addon)));
        RequirementQuantities.retainVerifiedAddonHints(hints, List.of(addon), Map.of("verified-addon", Set.of("core")));
        assertEquals(Set.of(addon.id(), "other-goal"), hints.get("verified-addon"));
    }
    private final String a = "r-0000000000000001", b = "r-0000000000000002";
    private BuildRequirement target(String id, int min, int max, RequirementQuantity.Scope scope) {
        return new BuildRequirement(id, "物流内容", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(), List.of(),
                SelectionPreferences.DEFAULT, List.of(), new RequirementQuantity(min, max, scope));
    }
    @Test void explicitRangesAreLocalAndContextOrMultipleRangesAreNotGuessed() {
        assertEquals(new RequirementQuantity(5, 10, RequirementQuantity.Scope.ADDED_ROOTS), RequirementQuantity.fromSource("补充铁魔法的附属5-10个"));
        assertEquals(new RequirementQuantity(3, 5, RequirementQuantity.Scope.ADDED_ROOTS), RequirementQuantity.fromSource("补充农夫乐事的偏冷门附属3-5个"));
        assertEquals(new RequirementQuantity(2, 4, RequirementQuantity.Scope.RETAINED_ROOTS), RequirementQuantity.fromSource("保留物流模组2～4个"));
        assertNull(RequirementQuantity.fromSource("NeoForge1.21.1整合包100～200个模组"));
        assertNull(RequirementQuantity.fromSource("约100～200个模组"));
        assertNull(RequirementQuantity.fromSource("补充甲5-10个、乙3-5个"));
        assertThrows(IllegalArgumentException.class, () -> new RequirementQuantity(10, 5, RequirementQuantity.Scope.ADDED_ROOTS));
        assertThrows(IllegalArgumentException.class, () -> new RequirementQuantity(1, 501, RequirementQuantity.Scope.ADDED_ROOTS));
    }
    @Test void unmetMinimumPrecedesAnotherTargetsUpperRangeAndGlobalCountRemainsUnique() {
        var targets = List.of(target(a, 2, 3, RequirementQuantity.Scope.ADDED_ROOTS), target(b, 1, 2, RequirementQuantity.Scope.ADDED_ROOTS));
        Map<String, Set<String>> hints = Map.of("shared", Set.of(a,b), "a-one", Set.of(a), "a-two", Set.of(a), "b-one", Set.of(b));
        var order = RequirementQuantities.order(List.of("a-one", "a-two", "b-one", "shared"), hints, Set.of(), Set.of(), targets);
        assertEquals("shared", order.getFirst());
        assertEquals("a-one", order.get(1));
        assertEquals(4, new HashSet<>(order).size());
        assertFalse(RequirementQuantities.allows("a-two", hints, Set.of("a-one", "shared"), Set.of(), List.of(target(a, 1, 2, RequirementQuantity.Scope.ADDED_ROOTS))));
        assertTrue(RequirementQuantities.allows("a-two", hints, Set.of("a-one", "shared"), Set.of("a-one"), List.of(target(a, 1, 2, RequirementQuantity.Scope.ADDED_ROOTS))));
    }
    @Test void finalCountsExcludeExistingFilesDependencyRootsAndInfrastructure() {
        var r = target(a, 2, 4, RequirementQuantity.Scope.ADDED_ROOTS);
        var graph = new DependencyGraph(); var json = new ObjectMapper();
        for (String slug : List.of("existing", "new-content", "auto-dependency", "helper-api")) {
            graph.allSlugs.add(slug); graph.selectedVersions.put(slug, json.createObjectNode().put("id", "v-"+slug));
        }
        var review = new RequirementReview(a, graph.allSlugs.stream().sorted()
                .map(slug -> new RequirementReview.Contribution(slug, "Provides logistics", "物流")).toList(), "");
        var row = RequirementCoverage.assess(List.of(r), List.of(review), graph, graph.allSlugs, Map.of(), Map.of(), "NONE", List.of(), Map.of(), Map.of(),
                new RequirementCoverage.CountContext(Set.of("existing", "new-content", "helper-api"), Set.of("existing"))).requirements().getFirst();
        assertEquals(List.of("new-content"), row.quantity().projects());
        assertEquals(1, row.quantity().actual()); assertEquals("PARTIAL", row.status());
        assertTrue(row.quantity().complete());
        var retained = RequirementCoverage.assess(List.of(target(a, 2, 4, RequirementQuantity.Scope.RETAINED_ROOTS)), List.of(review), graph,
                graph.allSlugs, Map.of(), Map.of(), "NONE", List.of(), Map.of(), Map.of(),
                new RequirementCoverage.CountContext(Set.of("existing", "new-content"), Set.of("existing"))).requirements().getFirst();
        assertEquals(2, retained.quantity().actual());
        var knownRoots = RequirementCoverage.assess(List.of(target(a,2,4,RequirementQuantity.Scope.RETAINED_ROOTS)),List.of(review),graph,
                graph.allSlugs,Map.of(),Map.of(),"NONE",List.of(),Map.of(),Map.of(),new RequirementCoverage.CountContext(
                        graph.allSlugs,Set.of("existing","auto-dependency","helper-api"),true,Set.of("existing"))).requirements().getFirst();
        assertEquals(2,knownRoots.quantity().actual(),"重解析把旧前置也列为种子时仍须保留原根来源");
        var unknownRoots = RequirementCoverage.assess(List.of(target(a,2,4,RequirementQuantity.Scope.RETAINED_ROOTS)),List.of(review),graph,
                graph.allSlugs,Map.of(),Map.of(),"NONE",List.of(),Map.of(),Map.of(),new RequirementCoverage.CountContext(
                        graph.allSlugs,Set.of("existing","auto-dependency","helper-api"),false,Set.of())).requirements().getFirst();
        assertEquals(1,unknownRoots.quantity().actual()); assertFalse(unknownRoots.quantity().complete());
    }
    @Test void quantitySurvivesReplacementAndChildProjectionWithoutLeakingToOtherCriteria() {
        var c = new RequirementCriterion("补充核心附属5-10个", BuildRequirement.Kind.ADDONS, List.of(), List.of("core"), List.of());
        var other = new RequirementCriterion("增加现代界面", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of());
        var parent = new BuildRequirement(a, "复合目标", BuildRequirement.Kind.GENERAL, List.of(), List.of(), List.of("core"), List.of(),
                SelectionPreferences.DEFAULT, List.of(c, other));
        assertNull(parent.quantity()); assertNull(other.target(parent).quantity());
        assertEquals(c.quantity(), c.replace(Map.of("core",List.of("port"))).target(parent).quantity());
        assertEquals(20, RequirementQuantities.candidateLimit(c.target(parent)));
        assertEquals(10, RequirementQuantities.addonReserve(List.of(parent), 5));
    }

    @Test void knownAddonRelationsCountAllRootsBeforeDisplayTruncationAndExcludeTheCore() {
        var graph = new DependencyGraph(); var json = new ObjectMapper();
        Map<String, PackAddonPolicy.Project> metadata = new LinkedHashMap<>();
        for (int i = 0; i <= 30; i++) {
            String slug = i == 0 ? "core" : "addon-"+i;
            var v = json.createObjectNode().put("id", "V"+i).put("project_id", "P"+i);
            var dependencies = v.putArray("dependencies");
            if (i > 0) dependencies.addObject().put("project_id", "P0").put("dependency_type", "required");
            graph.allSlugs.add(slug); graph.selectedVersions.put(slug,v);
            metadata.put(slug,new PackAddonPolicy.Project("P"+i,slug,10L,Set.of("magic"),slug,"V"+i));
        }
        var r = new BuildRequirement(a,"核心附属",BuildRequirement.Kind.ADDONS,List.of("P0"),List.of(),List.of("P0"),List.of(),
                SelectionPreferences.DEFAULT,List.of(),new RequirementQuantity(25,32,RequirementQuantity.Scope.RETAINED_ROOTS));
        var row = RequirementCoverage.assess(List.of(r),List.of(),graph,graph.allSlugs,Map.of("P0","core"),metadata,"NONE",List.of(),Map.of(),Map.of(),
                new RequirementCoverage.CountContext(graph.allSlugs,Set.of())).requirements().getFirst();
        assertEquals(24,row.mods().size()); assertEquals(30,row.quantity().actual());
        assertFalse(row.quantity().projects().contains("core"));
    }
}
