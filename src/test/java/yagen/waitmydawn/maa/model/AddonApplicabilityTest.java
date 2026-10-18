package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AddonApplicabilityTest {
    @Test void explicitDualAddonPhrasingRetainsKnownPartnersWithoutTurningOrdinaryFeaturesIntoPartners() {
        var core = project("A", "cooking-core", "Adds food", "food");
        var partner = project("B", "magic-core", "Adds magic", "magic");
        for (String text : List.of("An Addon for Cooking Core and Magic Core", "A dual crossover addon for both Cooking Core and Magic Core, featuring magical recipes")) {
            assertTrue(AddonApplicability.relates(text, core, partner), text);
            var addon = project("C", "bridge", text, "food");
            var check = AddonApplicability.assess(addon, json.readTree("{\"dependencies\":[]}"), Map.of(), List.of(core, partner, addon), Set.of(core.slug(), addon.slug()), true);
            assertEquals(List.of(partner.slug()), check.missingPartners());
        }
        assertFalse(AddonApplicability.relates("An addon for Cooking Core that adds food and Magic Core themed tools", core, partner));
    }
    private final ObjectMapper json = new ObjectMapper();
    private PackAddonPolicy.Project project(String id, String slug, String description, String category) {
        return new PackAddonPolicy.Project(id, slug, 100L, Set.of(category), slug, "v-" + slug, description);
    }
    @Test void anOptionalCompatibilityEndpointMustActuallyBeInThePack() {
        var core = project("A", "cooking-core", "Adds food", "food");
        var factory = project("B", "factory", "Adds machines", "technology");
        var addon = project("C", "bridge", "Compatibility between cooking-core and factory", "food");
        var version = json.readTree("{\"dependencies\":[{\"project_id\":\"A\",\"dependency_type\":\"required\"},{\"project_id\":\"B\",\"dependency_type\":\"optional\"}]}");
        var missing = AddonApplicability.assess(addon, version, Map.of(), List.of(core, factory, addon), Set.of("cooking-core", "bridge"), false);
        assertTrue(missing.blocks()); assertEquals(List.of("factory"), missing.missingPartners());
        assertTrue(missing.introducedContent().isEmpty());
        assertFalse(AddonApplicability.assess(addon, version, Map.of(), List.of(core, factory, addon), Set.of("cooking-core", "factory", "bridge"), false).blocks());
    }
    @Test void newRequiredContentIsReportedButLibrariesDoNotBecomeGameplayPartners() {
        var addon = project("C", "spells", "An addon for magic-core", "magic");
        var core = project("A", "magic-core", "Adds magic", "magic");
        var content = project("B", "creature-world", "Adds creatures", "mobs");
        var library = project("L", "common-library", "A library", "library");
        var version = json.readTree("{\"dependencies\":[{\"project_id\":\"A\",\"dependency_type\":\"required\"},{\"project_id\":\"B\",\"dependency_type\":\"required\"},{\"project_id\":\"L\",\"dependency_type\":\"required\"}]}");
        var check = AddonApplicability.assess(addon, version, Map.of(), List.of(core, content, library, addon), Set.of("magic-core", "spells"), true);
        assertEquals(List.of("creature-world"), check.introducedContent());
        var afterResolution = AddonApplicability.assess(addon, version, Map.of(), List.of(core, content, library, addon),
                Set.of("magic-core", "spells", "creature-world", "common-library"), Set.of("magic-core", "spells"), true);
        assertEquals(check.introducedContent(), afterResolution.introducedContent());
    }
    @Test void aNormalModIsNotRejectedBecauseItHasOptionalIntegrations() {
        var mod = project("A", "machines", "Adds machines and automation", "technology");
        var food = project("B", "food", "Adds food", "food");
        var version = json.readTree("{\"dependencies\":[{\"project_id\":\"B\",\"dependency_type\":\"optional\"}]}");
        assertEquals("NOT_ASSESSED", AddonApplicability.assess(mod, version, Map.of(), List.of(mod, food), Set.of("machines"), false).status());
        var mainWithCompatibility = project("A", "machines", "Adds machines with compatibility for food and cooking-core", "technology");
        assertEquals("NOT_ASSESSED", AddonApplicability.assess(mainWithCompatibility, version, Map.of(), List.of(mainWithCompatibility, food), Set.of("machines"), false).status());
    }
    @Test void missingMetadataAndDuplicateAliasesDoNotInventTwoPartners() {
        var core = project("A", "cooking-core", "Adds food", "food");
        var addon = project("C", "bridge", "Compatibility for cooking-core", "food");
        var version = json.readTree("{\"dependencies\":[{\"project_id\":\"Unknown\",\"dependency_type\":\"required\"}]}");
        var check = AddonApplicability.assess(addon, version, Map.of(), List.of(core, core, addon), Set.of("cooking-core", "bridge"), false);
        assertEquals("RELATION_UNVERIFIED", check.status()); assertFalse(check.blocks());
        assertFalse(AddonApplicability.mentions("Creates many recipes", project("X", "create", "", "technology")));
        assertFalse(AddonApplicability.bridge("Not compatible with cooking-core and factory"));
    }
    @Test void anExplicitBridgeCannotPassWithOnlyOneEndpointKnown() {
        var core = project("A", "cooking-core", "Adds food", "food");
        var bridge = project("B", "cooking-bridge", "Compatibility between Cooking Core and Unknown Harvest", "food");
        var version = json.readTree("{\"dependencies\":[{\"project_id\":\"A\",\"dependency_type\":\"required\"}]}");
        var check = AddonApplicability.assess(bridge, version, Map.of(), List.of(core, bridge), Set.of(core.slug(), bridge.slug()), true);
        assertEquals("PARTNERS_UNVERIFIED", check.status()); assertTrue(check.blocks());
        assertTrue(check.missingPartners().isEmpty());
        var recipeBridge = project("D", "recipe-bridge", "This mod adds the integration of Unknown Harvest recipes with Cooking Core and their addons.", "food");
        assertTrue(AddonApplicability.assess(recipeBridge, version, Map.of(), List.of(core, recipeBridge), Set.of(core.slug(), recipeBridge.slug()), false).blocks());
        var ordinary = project("C", "cooking-addon", "An addon for Cooking Core that adds food and tools", "food");
        assertFalse(AddonApplicability.assess(ordinary, version, Map.of(), List.of(core, ordinary), Set.of(core.slug(), ordinary.slug()), true).blocks());
    }
    @Test void genericCompatibilityFeaturesAndMechanismBridgesAreNotMultiModRequirements() {
        var core = project("A", "cooking-core", "Adds food", "food");
        var version = json.readTree("{\"dependencies\":[{\"project_id\":\"A\",\"dependency_type\":\"required\"}]}");
        for (String description : List.of(
                "Configurable Cooking Core addon with compat features and immersive new foods.",
                "Cooking Core Crafts & Additions extends Cooking Core and acts as a bridge between electricity and kinetic energy",
                "Cooking Core addon offering compatibility features and tools")) {
            var addon = project("B", "extension", description, "food");
            var check = AddonApplicability.assess(addon, version, Map.of(), List.of(core, addon), Set.of(core.slug(), addon.slug()), true);
            assertEquals("RELATION_UNVERIFIED", check.status(), description);
            assertFalse(check.blocks(), description);
        }
    }
    @Test void optionalCompatibilityDoesNotRequireAnotherContentMod() {
        var core = project("A", "cooking-core", "Adds food", "food");
        var factory = project("B", "factory", "Adds machines", "technology");
        var version = json.readTree("{\"dependencies\":[]}");
        for (String description : List.of(
                "An addon for Cooking Core. Optional compatibility between Cooking Core and Factory.",
                "An addon for Cooking Core with optional integration of Cooking Core and Factory",
                "An addon for Cooking Core with compatibility features and recipes. Factory is recommended.",
                "Cooking Core的附属。与Factory的联动为可选功能。")) {
            var addon = project("C", "extension", description, "food");
            var check = AddonApplicability.assess(addon, version, Map.of(), List.of(core, factory, addon), Set.of(core.slug(), addon.slug()), true);
            assertFalse(check.blocks(), description);
            assertFalse(AddonApplicability.relates(description, core, factory), description);
        }
    }
    @Test void onlyProjectsInTheSameRelationshipBecomePartners() {
        var core = project("A", "cooking-core", "Adds food", "food");
        var factory = project("B", "factory", "Adds machines", "technology");
        var other = project("D", "other-world", "Adds terrain", "worldgen");
        var addon = project("C", "extension", "Compatibility between Cooking Core and Factory. Other World is an unrelated recommendation.", "food");
        var version = json.readTree("{\"dependencies\":[]}");
        var check = AddonApplicability.assess(addon, version, Map.of(), List.of(core, factory, other, addon), Set.of(core.slug(), factory.slug(), addon.slug()), true);
        assertEquals("PARTNERS_RETAINED", check.status());
        assertFalse(check.blocks());
        assertTrue(AddonApplicability.relates(addon.description(), core, factory));
        assertFalse(AddonApplicability.relates(addon.description(), core, other));
        var missing = AddonApplicability.assess(addon, version, Map.of(), List.of(core, factory, other, addon), Set.of(core.slug(), addon.slug()), true);
        assertEquals(List.of(factory.slug()), missing.missingPartners());
    }
    @Test void everyExplicitRelationIsCheckedEvenWhenAnotherPairIsKnown() {
        var core = project("A", "cooking-core", "Adds food", "food");
        var factory = project("B", "factory", "Adds machines", "technology");
        var addon = project("C", "extension", "Compatibility between Cooking Core and Factory. Integration of Unknown Harvest with Cooking Core.", "food");
        var check = AddonApplicability.assess(addon, json.readTree("{\"dependencies\":[]}"), Map.of(), List.of(core, factory, addon), Set.of(core.slug(), factory.slug(), addon.slug()), true);
        assertEquals("PARTNERS_UNVERIFIED", check.status()); assertTrue(check.blocks());
        assertTrue(check.missingPartners().isEmpty());
    }
    @Test void chineseRelationsAndExplicitUnknownModEndpointsRemainConservative() {
        var core = project("A", "cooking-core", "Adds food", "food");
        var factory = project("B", "factory", "Adds machines", "technology");
        var version = json.readTree("{\"dependencies\":[]}");
        for (String description : List.of("联动Cooking Core和Factory", "Cooking Core与Factory之间的联动模组")) {
            var addon = project("C", "extension", description, "food");
            assertEquals(List.of(factory.slug()), AddonApplicability.assess(addon, version, Map.of(), List.of(core, factory, addon), Set.of(core.slug(), addon.slug()), true).missingPartners());
        }
        var unknown = project("C", "extension", "Bridge between the mods Unknown Harvest and Unknown Factory", "food");
        var check = AddonApplicability.assess(unknown, version, Map.of(), List.of(unknown), Set.of(unknown.slug()), true);
        assertTrue(check.blocks()); assertEquals("PARTNERS_UNVERIFIED", check.status());
    }
    @Test void namesContainingNotAndRepeatedAliasesDoNotChangeEndpointCount() {
        var core = project("A", "cooking-core", "Adds food", "food");
        var partner = project("B", "not-enough-recipes", "Adds recipes", "food");
        var version = json.readTree("{\"dependencies\":[]}");
        var addon = project("C", "extension", "Compatibility between Cooking Core and Not Enough Recipes", "food");
        var check = AddonApplicability.assess(addon, version, Map.of(), List.of(core, partner, addon), Set.of(core.slug(), addon.slug()), true);
        assertEquals(List.of(partner.slug()), check.missingPartners());
        var aliases = project("C", "extension", "Compatibility between cooking-core and Cooking Core", "food");
        var aliasCheck = AddonApplicability.assess(aliases, version, Map.of(), List.of(core, core, aliases), Set.of(core.slug(), aliases.slug()), true);
        assertFalse(aliasCheck.blocks()); assertEquals("RELATION_UNVERIFIED", aliasCheck.status());
        assertFalse(AddonApplicability.relates("Integration of Cooking Core and Not Enough Recipes with Unknown Harvest", core, partner));
        assertFalse(AddonApplicability.relates("Not a bridge between Cooking Core and Not Enough Recipes", core, partner));
    }
}
