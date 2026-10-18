package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DimensionGenerationTest {
    private BuildRequirement requirement(String source) {
        return new BuildRequirement("r-0000000000000001", source, BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(),
                List.of(new BuildIntent.SearchIntent("worldgen", 100, List.of("nether structures"))));
    }
    @Test void broadDimensionChildrenInheritEcologyContextWithoutAddingAnOverworldGoal() {
        assertEquals(2, DimensionGeneration.goals(requirement("分别大幅改造下界和末地的地形与世界生成")).size());
        assertTrue(DimensionGeneration.goals(requirement("别改下界地形")).isEmpty());
        var base = requirement("丰富下界和末地生态");
        var children = List.of(new RequirementCriterion("丰富下界", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), base.searches()),
                new RequirementCriterion("末地生态", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), base.searches()));
        var parent = new BuildRequirement(base.id(), base.source(), base.kind(), base.projects(), base.capabilities(),
                base.addonCores(), base.searches(), base.preferences(), children);
        var goals = DimensionGeneration.targets(List.of(parent));
        assertEquals(Set.of("nether", "end"), goals.values().stream().flatMap(List::stream).map(DimensionGeneration.Goal::dimension).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, goals.size());
    }
    @Test void scopedRestrictionsAndLocalContentAreNotExpandedIntoTerrainChanges() {
        for (String source : List.of("只添加下界生物", "丰富末地结构", "增加下界群系", "丰富下界生态但不改地形",
                "Keep vanilla terrain and enrich the Nether", "only add mobs in the End", "丰富下界UI", "优化下界地形性能"))
            assertTrue(DimensionGeneration.targets(List.of(requirement(source))).isEmpty(), source);
        var nether = requirement("丰富下界生态");
        var restriction = new BuildRequirement("r-0000000000000002", "不改主世界地形", BuildRequirement.Kind.GENERAL, List.of(), List.of());
        assertEquals("nether", DimensionGeneration.targets(List.of(nether, restriction)).get(nether.id()).getFirst().dimension());
        restriction = new BuildRequirement(restriction.id(), "不改地形", restriction.kind(), List.of(), List.of());
        assertTrue(DimensionGeneration.targets(List.of(nether, restriction)).isEmpty());
    }
    @Test void namedCustomDimensionsAndSemanticSearchPlansRemainExpressible() {
        assertEquals("月球", DimensionGeneration.goals(requirement("大幅改造月球维度的地形")).getFirst().dimension());
        assertEquals("aether", DimensionGeneration.goals(requirement("Overhaul terrain in the Aether dimension")).getFirst().dimension());
        var base = requirement("让下界焕然一新");
        var planned = new BuildRequirement(base.id(), base.source(), base.kind(), List.of(), List.of(), List.of(),
                List.of(new BuildIntent.SearchIntent("worldgen", 100, List.of("nether terrain overhaul"))));
        assertEquals("nether", DimensionGeneration.goals(planned).getFirst().dimension());
        assertTrue(DimensionGeneration.targets(List.of(new BuildRequirement(base.id(), "只加nether-overhaul", BuildRequirement.Kind.PROJECTS,
                List.of("nether-overhaul"), List.of()))).isEmpty());
    }
    @Test void descriptionMustTieMajorGenerationToTheRequestedDimension() {
        var nether = DimensionGeneration.goals(requirement("丰富下界生态")).getFirst();
        var end = DimensionGeneration.goals(requirement("丰富末地生态")).getFirst();
        assertTrue(DimensionGeneration.supports(nether, "Nether expansion with custom terrain, new biomes, structures and mobs!"));
        assertTrue(DimensionGeneration.supports(end, "Transforms the boring Vanilla end into an alien dimension with surreal terrain."));
        String endDescription = "Overhauls the End dimension, featuring four atmospheric biomes, new world generation";
        assertTrue(DimensionGeneration.supports(end, endDescription));
        assertTrue(endDescription.contains(DimensionGeneration.evidence(end, endDescription)));
        assertFalse(DimensionGeneration.supports(nether, "The nether explorer's simple dream: doubled height and amplified terrain."));
        for (String quote : List.of("Overhauls Nether fortresses and adds structures", "Adds more mobs to the Nether",
                "Adds 50 unique biomes to the Overworld, Nether and End", "Overhauls Overworld terrain and adds Nether structures",
                "Compatible with a Nether terrain overhaul", "Does not overhaul Nether terrain", "Nether terrain is not overhauled",
                "不是下界地形重塑模组", "重塑主世界地形并添加下界结构", "Overhauls Overworld terrain, featuring Nether structures", "Nether Overhaul API"))
            assertFalse(DimensionGeneration.supports(nether, quote), quote);
        assertFalse(DimensionGeneration.supports(nether, "Transforms the End terrain with new biomes"));
    }
    @Test void broadMultiDimensionProjectsAreCandidatesWithoutDistributingTheirTotalBiomeCount() {
        String aggregate = "Adds 50 unique biomes to the Overworld, Nether and End";
        assertFalse(DimensionGeneration.richEvidence(aggregate).isEmpty());
        for (var realm : List.of("overworld", "nether", "end"))
            assertFalse(DimensionGeneration.supports(new DimensionGeneration.Goal(realm, realm, ""), aggregate));
        assertTrue(DimensionGeneration.supports(new DimensionGeneration.Goal("end", "末地", ""),
                "Overhauls terrain throughout both the Nether and End with rich landscapes"));
    }
    @Test void substantialStructuresCanQualifyButSingleBuildingsAndHeightAloneCannot() {
        var goal = new DimensionGeneration.Goal("nether", "下界", "");
        assertTrue(DimensionGeneration.supports(goal, "Adds hundreds of new structures throughout the Nether"));
        assertFalse(DimensionGeneration.supports(goal, "Overhauls the Nether fortress"));
        assertFalse(DimensionGeneration.supports(goal, "Doubles the Nether build height"));
        assertTrue(DimensionGeneration.supports(goal, "Overhauls Nether terrain with many new biomes"));
        assertEquals("nether large scale structures", DimensionGeneration.goals(requirement("大规模扩展下界结构")).getFirst().query());
        assertEquals("overworld biome expansion", DimensionGeneration.goals(requirement("主世界加入大量群系")).getFirst().query());
    }
}
