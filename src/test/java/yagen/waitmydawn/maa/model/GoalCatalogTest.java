package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GoalCatalogTest {
    @Test void libraryAddonAndBiomeMentionDoNotProveTerrainOrModernUi() {
        var terrain = GoalCatalog.profile(Capability.TERRAIN, 150);
        assertNull(GoalCatalog.evidence(terrain, "biome-sounds", "Biome Sounds", "Adds ambient sounds to biomes"));
        assertNull(GoalCatalog.evidence(GoalCatalog.profile(Capability.NETHER, 150), "nether-api", "Nether API", "Library for Nether biomes"));
        assertNull(GoalCatalog.evidence(GoalCatalog.profile(Capability.MODERN_UI, 150), "config-screen", "Config GUI", "A simple configuration interface"));
        assertNotNull(GoalCatalog.evidence(terrain, "mountain-world", "Mountain World", "Overhauls terrain generation"));
    }
    @Test void endAndNetherHaveSeparateEvidenceAndMobSearchCategories() {
        var end = GoalCatalog.profile(Capability.END, 150);
        var nether = GoalCatalog.profile(Capability.NETHER, 150);
        assertNull(GoalCatalog.evidence(end, "nether-world", "Nether World", "New Nether biomes and structures"));
        assertNotNull(GoalCatalog.evidence(nether, "nether-world", "Nether World", "New Nether biomes and structures"));
        assertNull(GoalCatalog.evidence(GoalCatalog.profile(Capability.BIOMES, 150), "incendium", "Incendium", "Nether biomes overhaul"));
        assertEquals("mobs", GoalCatalog.queryCategory(nether, "nether mobs"));
        assertEquals("worldgen", GoalCatalog.queryCategory(nether, "nether biomes"));
    }
    @Test void uncoveredGoalWinsBeforeMorePopularCandidatesForAnAlreadyCoveredGoal() {
        var roles = Map.of("render", Set.of(Capability.PERFORMANCE), "memory", Set.of(Capability.PERFORMANCE), "food", Set.of(Capability.UTILITY));
        var selected = GoalCatalog.order(List.of("render", "memory", "food"), roles,
                Map.of(Capability.PERFORMANCE, 4, Capability.UTILITY, 3), Set.of());
        assertEquals(List.of("render", "food", "memory"), selected);
        assertEquals(List.of("food", "memory"), GoalCatalog.order(List.of("memory", "food"), roles,
                Map.of(Capability.PERFORMANCE, 4, Capability.UTILITY, 3), Set.of("render")));
    }
    @Test void dimensionMobFacetCanWinAfterDimensionWorldgenIsAlreadySelected() {
        var roles = Map.of("terrain", Set.of(Capability.NETHER), "more-biomes", Set.of(Capability.NETHER), "creatures", Set.of(Capability.NETHER));
        var facets = Map.of("terrain", Set.of("NETHER:biomes"), "more-biomes", Set.of("NETHER:biomes"), "creatures", Set.of("NETHER:creatures"));
        assertEquals(List.of("creatures", "more-biomes"), GoalCatalog.order(List.of("more-biomes", "creatures"), roles,
                Map.of(Capability.NETHER, 2), Set.of("terrain"), facets));
    }
    @Test void targetScaledBudgetIsBoundedAndDefaultLimitsRemainCompatible() {
        assertEquals(300, new RetrievalBudget().snapshot().get("versionLimit"));
        assertEquals(450, new RetrievalBudget(150).snapshot().get("versionLimit"));
        assertEquals(600, new RetrievalBudget(500).snapshot().get("versionLimit"));
    }
    @Test void realmSpecificStructuresDoNotRecallOverworldBiomesOrUnrequestedDimensionMobs() {
        var requirement = new BuildRequirement("r-0000000000000001", "丰富下界结构", BuildRequirement.Kind.CAPABILITY, List.of(),
                List.of(Capability.NETHER, Capability.STRUCTURES));
        var intent = new BuildIntent(2, BuildIntent.Mode.NEW, "组合", "", BuildEnvironment.current("1.21.1", "neoforge"),
                new BuildIntent.CountTarget(100, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L,
                ConstraintChanges.KEEP, List.of(requirement));
        var active = GoalCatalog.active(intent, 100);
        assertEquals(1, active.size());
        assertEquals(List.of("nether structures"), active.getFirst().queries());
        assertEquals(Set.of("NETHER:structures"), GoalCatalog.desiredFacets(active.getFirst()));
        assertNull(GoalCatalog.evidence(active.getFirst(), "nether-animals", "Nether Animals", "Adds new Nether mobs"));
    }
}
