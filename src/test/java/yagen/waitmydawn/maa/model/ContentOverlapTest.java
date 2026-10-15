package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentOverlapTest {
    private ContentOverlap.Project project(String slug, String description, long downloads) {
        return new ContentOverlap.Project(slug, slug, description, "", "", downloads);
    }
    @Test void realAnimalsTriggerConservativeWarningWithoutClaimingDuplicateSpecies() {
        var groups = ContentOverlap.detect(List.of(project("wildlife-one", "Adds real-world animals", 100),
                project("wildlife-two", "Introduces realistic animals", 1000)), Set.of("wildlife-one"));
        assertEquals(1, groups.size());
        assertTrue(groups.getFirst().explanation().contains("尚未证明"));
        assertTrue(groups.getFirst().candidates().stream().filter(c -> c.slug().equals("wildlife-one")).findFirst().orElseThrow().recommended());
        assertFalse(groups.getFirst().candidates().stream().filter(c -> c.slug().equals("wildlife-two")).findFirst().orElseThrow().recommended());
    }
    @Test void inventoryNameOrTagCannotTriggerDifferentFunctions() {
        assertTrue(ContentOverlap.detect(List.of(project("inventory-tabs", "Shows your inventory in tabs", 100),
                project("inventory-keep", "Keeps items on death", 1000)), Set.of()).isEmpty());
        assertTrue(ContentOverlap.detect(List.of(project("inventory-sort", "Sorts your inventory", 100),
                project("inventory-hud", "Displays your inventory on screen", 1000)), Set.of()).isEmpty());
    }
    @Test void equivalentInventoryFunctionsRequireMatchingDescriptionEvidence() {
        var groups = ContentOverlap.detect(List.of(project("clean-bags", "Sorts your inventory and containers", 100),
                project("tidy-storage", "Adds inventory sorting", 1000)), Set.of());
        assertEquals("inventory-sort", groups.getFirst().original());
        assertEquals("Adds inventory sorting", groups.getFirst().candidates().getFirst().evidence());
    }
    @Test void LogisticsNetworkSortingDoesNotDuplicatePlayerInventorySorting() {
        var pipes = project("arbitrary-pipes", "Introduces networked pipes hooked up to your chests and machines to sort, fetch, and craft items automatically", 100);
        var inventory = project("arbitrary-inventory", "Helps you keep your inventory sorted", 200);
        assertTrue(ContentOverlap.detect(List.of(pipes, inventory), Set.of()).isEmpty());
    }
    @Test void automaticItemRoutingIsNotAPlayerInventorySortingInterface() {
        var organizer = project("organizer", "Sorts your inventory and chests with a click", 100);
        for (String description : List.of("Routes items between inventories, sorting them automatically into connected machines",
                "Provides item routing and inventory sorting across a network of machines")) {
            assertTrue(ContentOverlap.detect(List.of(project("routing", description, 200), organizer), Set.of()).isEmpty(), description);
        }
        assertEquals(1, ContentOverlap.detect(List.of(organizer,
                project("organizer-two", "Organizes your containers with a sorting button", 200)), Set.of()).size());
    }
    @Test void anItemPipesFilterInventoryIsNotAPlayerSortingInterface() {
        var organizer = project("organizer", "Help you keep your inventory sorted", 100);
        String pipeDescription = "<li>diamond item pipe (sorts items based on filters in its inventory)";
        assertTrue(ContentOverlap.detect(List.of(organizer, project("pipes", pipeDescription, 200)), Set.of()).isEmpty());
        var hybrid = new ContentOverlap.Project("hybrid", "Hybrid", pipeDescription,
                "Adds buttons to sort your inventory", "", 200);
        assertEquals("inventory-sort", ContentOverlap.detect(List.of(organizer, hybrid), Set.of()).getFirst().original());
    }
    @Test void recommendedExternalRecipeViewersAreNotOwnFunctionEvidence() {
        var viewer = project("viewer", "Shows crafting recipes", 100);
        for (String reference : List.of(
                "<p><strong>Important</strong>: In order to be able to see all recipes required in this mod, consider installing a Recipe Viewer mod",
                "An external interface is recommended for viewing recipes and the available machines",
                "Requires a recipe browser to inspect all processing steps",
                "建议安装配方查看器以查询合成方式")) {
            assertTrue(ContentOverlap.detect(List.of(viewer, project("machine", reference, 200)), Set.of()).isEmpty(), reference);
        }
        var ownViewer = new ContentOverlap.Project("own-viewer", "Viewer", "Adds a recipe browser",
                "An external interface is recommended for viewing recipes", "", 200);
        assertEquals("recipe-viewer", ContentOverlap.detect(List.of(viewer, ownViewer), Set.of()).getFirst().original());
    }
    @Test void recipeIntegrationsAndViewerVisibilityControlsDoNotBecomeCompetingViewers() {
        var viewer = project("standalone-viewer", "Browse crafting recipes", 100);
        for (String reference : List.of("- Keypad 3: Show recipes (Requires JEI)",
                "Show recipes using REI", "Displays recipes via EMI",
                "Automatically hides the recipe viewer when not searching",
                "Toggles the recipe browser visibility with a hotkey", "隐藏配方查看器以减少界面干扰")) {
            assertTrue(ContentOverlap.detect(List.of(viewer, project("helper", reference, 200)), Set.of()).isEmpty(), reference);
        }
        var primaryViewer = new ContentOverlap.Project("primary-viewer", "Viewer", "Browse crafting recipes",
                "Requires Fabric API", "", 200);
        assertEquals("recipe-viewer", ContentOverlap.detect(List.of(viewer, primaryViewer), Set.of()).getFirst().original());
    }

    @Test void majorWorldgenGroupsDifferentDimensionsAndOrdinaryStructuresStayOutside() {
        var groups = ContentOverlap.detect(List.of(project("terralith", "Overhauls the Overworld with 95 new biomes", 1000),
                project("biomes-o-plenty", "Adds 80 new biomes to the Overworld", 500),
                project("nullscape", "Overhauls the End", 100), project("small-ruins", "Adds small ruins", 10000)), Set.of());
        assertEquals(1, groups.size());
        assertEquals("major-worldgen", groups.getFirst().original());
        assertEquals(3, groups.getFirst().candidates().size());
        assertEquals(2, groups.getFirst().candidates().stream().filter(ContentOverlap.Candidate::recommended).count());
        assertEquals("末地", groups.getFirst().candidates().getLast().scope());
    }
    @Test void comparisonsCompatibilityAndNegationAreNotOwnFeatureProof() {
        assertTrue(ContentOverlap.detect(List.of(project("a", "Compatible with mods that add real-world animals", 1),
                project("b", "Does not add real-world animals", 1)), Set.of()).isEmpty());
    }
    @Test void rulesApplyToArbitraryProjectsAndBoundedBodyEvidence() {
        var first = new ContentOverlap.Project("recipe-tool-a", "A", "Recipe interface", "Browses item recipes", "", 10);
        var second = new ContentOverlap.Project("recipe-tool-b", "B", "Recipe interface", "Shows crafting recipes", "", 20);
        assertEquals("recipe-viewer", ContentOverlap.detect(List.of(first, second), Set.of()).getFirst().original());
    }
    @Test void publishedWorldgenWordingWithPlusCountUniqueAndDimensionTransformationIsRecognized() {
        var terralith = project("terralith", "Explore almost 100 new biomes consisting of both realism and light fantasy, using just Vanilla blocks", 100);
        var bop = new ContentOverlap.Project("biomes-o-plenty", "Biomes O Plenty", "Adds 50+ unique biomes", 
                "new and unique biomes with vanilla-esque flair to the Overworld, Nether, and End", "", 200);
        var nullscape = project("nullscape", "Transforms the boring Vanilla end into an alien dimension", 50);
        var groups = ContentOverlap.detect(List.of(terralith, bop, nullscape), Set.of());
        assertEquals(1, groups.size()); assertEquals(3, groups.getFirst().candidates().size());
        assertEquals(1, groups.getFirst().candidates().stream().filter(ContentOverlap.Candidate::recommended).count());
        var biomes = groups.getFirst().candidates().getFirst();
        assertEquals("下界 / 末地 / 主世界", biomes.scope());
        assertTrue(biomes.scopeEvidence().contains("Overworld"));
        assertEquals("末地", groups.getFirst().candidates().getLast().scope());
    }


    @Test void compatibilityListsAndIndividualStructureOverhaulsAreNotMajorWorldgenProof() {
        var structure = new ContentOverlap.Project("structure-pack","Structures","Adds over 100 new structures to enhance the world exploration",
                "Compatible mods:\n- William Wythers' Overhauled Overworld\n- Terralith", "",100);
        var fortress=project("fortress","This Mod Overhauls the Nether Fortress with 1 guaranteed spawner",100);
        var world=project("world","Overhauls the End dimension with new terrain",200);
        assertTrue(ContentOverlap.detect(List.of(structure,fortress,world),Set.of()).isEmpty());
        var biome=project("biome","This mod overhauls all vanilla biomes, with different sub biomes",300);
        assertEquals(2,ContentOverlap.detect(List.of(biome,world),Set.of()).getFirst().candidates().size());
    }
}
