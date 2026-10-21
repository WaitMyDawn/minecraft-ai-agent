package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentOverlapTest {
    @Test void publishedAddonAndKitchenDescriptionsDoNotBecomeGeneralRecipeViewers() throws Exception {
        var json = new tools.jackson.databind.ObjectMapper();
        try (var input = getClass().getResourceAsStream("/fixtures/overlap-recipe-attribution.json")) {
            assertNotNull(input);
            var projects = new ArrayList<ContentOverlap.Project>();
            for (var info : json.readTree(input)) projects.add(new ContentOverlap.Project(info.path("slug").asText(),
                    info.path("title").asText(), info.path("description").asText(), info.path("body").asText(), "", info.path("downloads").asLong()));
            assertEquals(2, projects.size());
            projects.add(project("general-viewer", "Browse crafting recipes", 1));
            assertTrue(ContentOverlap.detectByDimension(projects, Set.of(), Map.of()).stream().noneMatch(g -> g.original().equals("recipe-viewer")));
        }
    }
    @Test void integrationSectionsAndExternalLinkSubjectsDoNotSupplyOwnFeatureEvidence() {
        var viewer = project("viewer", "Browse crafting recipes", 10);
        for (String body : List.of(
                "- [External Index](https://example.org/tools/index) - Displays recipes for the new machines.",
                "## Integrations\nDisplays recipes in an external interface\n### More details\nShows item recipes",
                "<h2>Compatibility</h2>\nDisplays item recipes",
                "**Recommended mods**\nBrowse crafting recipes",
                "Compatibility:\nShows crafting recipes",
                "JEI: Displays crafting recipes")) {
            var addon = new ContentOverlap.Project("arbitrary-addon", "Addon", "Adds new machines", body, "", 200);
            assertTrue(ContentOverlap.detect(List.of(viewer, addon), Set.of()).isEmpty(), body);
        }
        var own = new ContentOverlap.Project("own", "Own", "Recipe interface", "## Integrations\nCompatible with external tools\n## Features\nBrowses item recipes", "", 20);
        assertEquals(2, ContentOverlap.detect(List.of(viewer, own), Set.of()).getFirst().candidates().size());
    }
    @Test void specializedRecipeInterfacesAreComplementaryToGeneralViewers() {
        var viewer = project("viewer", "Browse crafting recipes", 10);
        for (String description : List.of("Adds a multiblock kitchen that shows recipes you can make with available ingredients",
                "Browse food recipes", "Adds a cooking recipe browser", "Shows brewing recipes", "食材配方查询与厨房烹饪"))
            assertTrue(ContentOverlap.detect(List.of(viewer, project("specialist", description, 100)), Set.of()).isEmpty(), description);
        var groups = ContentOverlap.detect(List.of(viewer, project("other-viewer", "Search item recipes", 100)), Set.of());
        assertEquals("recipe-viewer", groups.getFirst().original());
        assertTrue(groups.getFirst().candidates().stream().allMatch(c -> !c.evidence().contains("https://")));
    }
    @Test void partiallyOverlappingWorldgenStillRecommendsUncoveredRealms() {
        var projects = List.of(project("protected-nether", "Overhauls Nether terrain", 500),
                project("protected-end", "Overhauls End terrain", 400),
                project("multi", "Adds 50 unique biomes to the Overworld, Nether and End", 300),
                project("unknown", "Explore 100 new biomes", 1000));
        var groups = ContentOverlap.detectByDimension(projects, Set.of("protected-nether", "protected-end"), Map.of());
        var selected = groups.getFirst().candidates().stream().filter(ContentOverlap.Candidate::recommended)
                .map(ContentOverlap.Candidate::slug).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("protected-nether", "protected-end", "multi"), selected);
        var unknownProtected = ContentOverlap.detectByDimension(List.of(project("unknown", "Explore 100 new biomes", 1000),
                project("overworld", "Overhauls Overworld terrain", 100)), Set.of("unknown"), Map.of()).getFirst();
        assertTrue(unknownProtected.candidates().stream().allMatch(ContentOverlap.Candidate::recommended));
    }
    @Test void dimensionConfirmationSeparatesKnownRealmsAndDoesNotRepeatConfirmedChoices() {
        var projects = List.of(project("nether-a", "Nether expansion with custom terrain", 100),
                project("nether-b", "Overhauls the Nether terrain", 200),
                project("end-a", "Transforms the End terrain", 300), project("end-b", "Overhauls the End terrain", 400));
        var groups = ContentOverlap.detectByDimension(projects, Set.of(), Map.of());
        assertEquals(Set.of("generation:nether", "generation:end"), groups.stream().map(ContentOverlap.Group::original).collect(java.util.stream.Collectors.toSet()));
        for (var group : groups) assertEquals(1, group.candidates().stream().filter(ContentOverlap.Candidate::recommended).count());
        assertTrue(ContentOverlap.detectByDimension(projects, Set.of(), Map.of("generation:nether", List.of("nether-a", "nether-b"),
                "generation:end", List.of("end-a", "end-b"))).isEmpty());
        assertTrue(ContentOverlap.detectByDimension(List.of(projects.get(0), projects.get(2)), Set.of(), Map.of()).isEmpty());
    }
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
    @Test void storageCompressionAndOrganizingFarmOutputDoNotProveInventorySorting() {
        var sorter = project("inventory-tools", "Help you keep your inventory sorted", 100);
        var compression = project("resource-blocks", "Smart storage: Great for saving inventory space or organizing output from auto-farms and mining sessions", 200);
        assertTrue(ContentOverlap.detect(List.of(sorter, compression), Set.of(compression.slug())).isEmpty());
        assertEquals("inventory-sort", ContentOverlap.detect(List.of(sorter, project("other-sorter", "Automatically sorts your inventory", 50)), Set.of()).getFirst().original());
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
    @Test void unknownGenerationScopeDoesNotForceSeparateNonemptySelectionsOrLoseDefaults() {
        var projects = List.of(project("core-a", "Transforms the Overworld with enhanced terrain", 500),
                project("core-b", "Overhauls the Overworld terrain", 100),
                project("unknown-biomes", "Explore 100 new biomes", 1000),
                project("chosen-nether", "Overhauls the Nether terrain", 2000),
                project("chosen-end", "Transforms the End terrain", 3000));
        var groups = ContentOverlap.detectByDimension(projects, Set.of("chosen-nether", "chosen-end"),
                Map.of("generation:nether", List.of("chosen-nether"), "generation:end", List.of("chosen-end")));
        assertEquals(1, groups.size());
        assertEquals("major-worldgen", groups.getFirst().original());
        assertEquals(Set.of("core-a", "core-b", "unknown-biomes", "chosen-nether", "chosen-end"), groups.getFirst().candidates().stream().map(ContentOverlap.Candidate::slug).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("core-a", "chosen-nether", "chosen-end"), groups.getFirst().candidates().stream().filter(ContentOverlap.Candidate::recommended).map(ContentOverlap.Candidate::slug).collect(java.util.stream.Collectors.toSet()));
        assertFalse(groups.getFirst().candidates().stream().filter(c -> c.slug().equals("unknown-biomes")).findFirst().orElseThrow().scopeNote().isEmpty());
        var protectedGroup = ContentOverlap.detectByDimension(projects, Set.of("core-b", "chosen-nether", "chosen-end"),
                Map.of("generation:nether", List.of("chosen-nether"), "generation:end", List.of("chosen-end"))).getFirst();
        assertTrue(protectedGroup.candidates().stream().filter(ContentOverlap.Candidate::protectedSelection).allMatch(ContentOverlap.Candidate::recommended));
    }
    @Test void inventoryDisplaysGroupByActualFunctionAndKeepComplementaryToolsSeparate() {
        var hud = List.of(project("display-a", "Adds your main inventory, potion effects and your equipments to your HUD", 10),
                project("display-b", "Display your inventory on the screen", 20),
                project("display-c", "Shows player inventory as a permanent overlay", 30),
                project("sorter", "Sorts your inventory with a button", 100),
                project("armor", "Shows armor durability on screen", 200));
        var groups = ContentOverlap.detectByDimension(hud, Set.of(), Map.of());
        assertEquals(1, groups.size());
        assertEquals("inventory-hud", groups.getFirst().original());
        assertEquals(List.of("display-c", "display-b", "display-a"), groups.getFirst().candidates().stream().map(ContentOverlap.Candidate::slug).toList());
        assertEquals(List.of("display-c"), groups.getFirst().candidates().stream().filter(ContentOverlap.Candidate::recommended).map(ContentOverlap.Candidate::slug).toList());
        assertTrue(groups.getFirst().explanation().contains("重复显示"));
    }
    @Test void inventoryReferencesAndNonPlayerInterfacesDoNotBecomeDuplicateHuds() {
        var own = project("own", "Displays your inventory on screen", 10);
        for (String description : List.of("Compatible with an inventory HUD", "Recommend installing an inventory HUD",
                "Hides the inventory HUD with a hotkey", "Shows inventory in a tabbed screen",
                "Displays inventory sorting buttons", "Does not display inventory on screen",
                "Displays inventories of networked pipes on screen"))
            assertTrue(ContentOverlap.detectByDimension(List.of(own, project("helper", description, 20)), Set.of(), Map.of()).isEmpty(), description);
        assertEquals("inventory-hud", ContentOverlap.detectByDimension(List.of(own,
                project("overlay", "Shows your inventory on screen without opening any menus", 20)), Set.of(), Map.of()).getFirst().original());
    }
    @Test void crossDimensionAggregateAndUncertainCandidatesHaveExplicitScopeNotes() {
        var multi = project("multi", "Adds 50 unique biomes to the Overworld, Nether and End", 500);
        var card = ContentOverlap.generationCandidate(multi, Set.of()).orElseThrow();
        assertEquals("下界 / 末地 / 主世界", card.scope());
        assertTrue(card.scopeNote().contains("不能分别核实"));
        var projects = List.of(multi, project("overworld", "Overhauls Overworld terrain", 100), project("end", "Overhauls End terrain", 50));
        assertEquals(1, ContentOverlap.detectByDimension(projects, Set.of(), Map.of()).size());
        assertTrue(ContentOverlap.detectByDimension(List.of(projects.get(1), projects.get(2)), Set.of(), Map.of()).isEmpty());
    }
    @Test void confirmedCombinationsAreNotRepeatedButNewOverlappingProjectsRequireConsent() {
        var projects = List.of(project("a", "Overhauls Nether terrain", 100), project("b", "Transforms Nether terrain", 200));
        var confirmed = Map.of("generation:nether", List.of("a", "b"));
        assertTrue(ContentOverlap.detectByDimension(projects, Set.of(), confirmed).isEmpty());
        var expanded = new ArrayList<>(projects); expanded.add(project("new", "Adds 30 new Nether biomes", 300));
        assertEquals(3, ContentOverlap.detectByDimension(expanded, Set.of("a", "b"), confirmed).getFirst().candidates().size());
        assertTrue(ContentOverlap.detectByDimension(expanded, Set.of(), Map.of("generation:nether", List.of("a", "b", "new"))).isEmpty());
    }
    @Test void onlyActuallyConnectedRangesAreMergedAndOrderingIsStable() {
        var projects = List.of(project("nether-a", "Overhauls Nether terrain", 1), project("nether-b", "Overhauls Nether terrain", 2),
                project("end-a", "Overhauls End terrain", 1), project("end-b", "Overhauls End terrain", 2),
                project("structure", "Adds a ruined tower in the End", 100));
        var groups = ContentOverlap.detectByDimension(projects, Set.of(), Map.of());
        assertEquals(2, groups.size());
        var reversed = new ArrayList<>(projects); Collections.reverse(reversed);
        assertEquals(groups, ContentOverlap.detectByDimension(reversed, Set.of(), Map.of()));
        assertTrue(groups.stream().allMatch(g -> g.candidates().size() == 2));
    }
    @Test void customDimensionScopesDoNotBecomeUnknownOrOverlapUnrelatedRealms() {
        var projects = List.of(project("a", "Overhauls terrain in the Aether dimension", 10),
                project("b", "Transforms terrain in the Aether dimension", 20), project("nether", "Overhauls Nether terrain", 30));
        var groups = ContentOverlap.detectByDimension(projects, Set.of(), Map.of());
        assertEquals(1, groups.size()); assertEquals("generation:aether", groups.getFirst().original());
        assertTrue(groups.getFirst().candidates().stream().allMatch(c -> c.scope().equals("aether维度")));
        assertTrue(ContentOverlap.detectByDimension(projects, Set.of(), Map.of("generation:aether", List.of("a", "b"))).isEmpty());
    }
}
