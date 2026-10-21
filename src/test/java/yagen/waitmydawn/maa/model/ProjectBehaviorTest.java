package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectBehaviorTest {
    @Test void observedCompatibilityPresetConfigurationAndHistoryAreNotDefaultBehavior() {
        var examples = Map.of(
                "This means Terralith will respect superflat worlds, and work with the Single Biome, Large Biomes, and Amplified world types", ProjectBehavior.Scope.REFERENCE,
                "All Skylands (`terralith:all_skylands`): Creates a void world that is completely filled in with Skylands, and only Skylands", ProjectBehavior.Scope.OPTIONAL,
                "* **Globally disable all structures:** Easily disable all structures across your world with a single setting,", ProjectBehavior.Scope.OPTIONAL,
                "No Void Structures was one of my first Java modding projects, and because of that, it was quite frankly horrible (inflexible, incompatible, broke superflat structures, etc)", ProjectBehavior.Scope.HISTORY);
        examples.forEach((text, scope) -> {
            var statements = ProjectBehavior.statements(text);
            assertFalse(statements.isEmpty());
            assertTrue(statements.stream().allMatch(s -> s.scope() == scope), text);
            assertTrue(WorldGenerationPolicy.findings(text).isEmpty(), text);
            statements.forEach(s -> assertTrue(text.contains(s.quote())));
        });
    }

    @Test void optionalAndHistoricalSectionsStopAtPeerHeadingAndRetainNestedScope() {
        String facts = """
                # World presets
                ## Islands
                Creates a void world.
                # Changelog
                Removes all structures.
                # Features
                Removes mobs from the Nether by default.
                """;
        assertEquals(List.of(ProjectBehavior.Scope.OPTIONAL, ProjectBehavior.Scope.HISTORY, ProjectBehavior.Scope.OWN),
                ProjectBehavior.statements(facts).stream().map(ProjectBehavior.Statement::scope).toList());
        assertEquals(List.of(WorldGenerationPolicy.Effect.CONTENT_REMOVAL), WorldGenerationPolicy.findings(facts).stream()
                .map(WorldGenerationPolicy.Finding::effect).toList());
    }

    @Test void separateDefaultBehaviorIsNotHiddenByCompatibilityOrConfiguration() {
        for (String facts : List.of(
                "Compatible with Skyblock packs and removes mobs from the Nether.",
                "Structures can be configured. Creates a void world by default.",
                "## Configuration\nRemoves mobs from the Nether by default.",
                "You can choose a preset but creates a void world by default.",
                "支持配置选项。但是默认移除下界生物。")) {
            assertFalse(WorldGenerationPolicy.findings(facts).isEmpty(), facts);
        }
    }

    @Test void plainStandaloneExtremePresetAndDefaultRemovalAreStillBlocked() {
        for (String facts : List.of("Skyblock preset: Creates a void world.", "Creates Superflat dimensions.", "Superflat worlds with villages.", "超平坦世界。",
                "Removes mobs from the Nether by default; structures are configurable.",
                "默认生成虚空世界，可以配置。"))
            assertFalse(WorldGenerationPolicy.blocked("冒险整合包", "generic-project", facts).isEmpty(), facts);
        assertTrue(WorldGenerationPolicy.findings("Fixes broken superflat structures").isEmpty());
        assertTrue(WorldGenerationPolicy.findings("Superflat support").isEmpty());
    }

    @Test void configurationExamplesDoNotBecomeDefaultBehavior() {
        String facts = """
                # Configuration
                ```text
                Removes all mobs from the Nether.
                ```
                # Features
                Overhauls Nether terrain with many new biomes.
                """;
        assertTrue(WorldGenerationPolicy.findings(facts).isEmpty());
        assertTrue(DimensionGeneration.richEvidence(facts).contains("Overhauls Nether terrain"));
    }

    @Test void optionalGenerationIsNotDefaultCompletionEvidence() {
        var goal = new DimensionGeneration.Goal("nether", "下界", "");
        for (String facts : List.of("Optionally overhauls Nether terrain with many new biomes.",
                "# Optional presets\nOverhauls Nether terrain with many new biomes.",
                "# Changelog\nOverhauls Nether terrain with many new biomes.")) {
            assertTrue(DimensionGeneration.richEvidence(facts).isEmpty(), facts);
            assertFalse(DimensionGeneration.supports(goal, facts), facts);
        }
        assertTrue(DimensionGeneration.supports(goal, "# Features\nOverhauls Nether terrain with many new biomes."));
    }

    @Test void clippingOptionalContextCannotTurnAQuoteIntoDefaultEvidence() {
        String quote = "Overhauls Nether terrain with many new biomes";
        assertFalse(ProjectBehavior.owns("# Optional presets\n" + quote, quote));
        assertFalse(ProjectBehavior.owns("Optionally " + quote, quote));
        assertTrue(ProjectBehavior.owns("# Features\n" + quote, quote));
        assertTrue(ProjectBehavior.owns("# Features\n" + quote + " and structures.", "Nether terrain with many new biomes"));
    }

    @Test void generationVerificationUsesFullFactsEvenWhenModelQuoteDropsOptionalHeading() {
        String id = "r-0000000000000001";
        String quote = "Overhauls Nether terrain with many new biomes";
        var requirement = new BuildRequirement(id, "丰富下界生态", BuildRequirement.Kind.CAPABILITY, List.of(), List.of());
        var review = new RequirementReview(id, List.of(new RequirementReview.Contribution("generic-project", quote, "下界地形")), "");
        var optional = RequirementCoverage.verify(List.of(requirement), List.of(review),
                Map.of("generic-project", "# Optional presets\n" + quote), Set.of());
        assertEquals(1, optional.counts().accepted());
        assertTrue(optional.checks().get(id).generation().isEmpty());
        var own = RequirementCoverage.verify(List.of(requirement), List.of(review),
                Map.of("generic-project", "# Features\n" + quote), Set.of());
        assertEquals(1, own.checks().get(id).generation().size());
    }

    @Test void proseListsHtmlSectionsAndActivationConditionsStayScoped() {
        for (String facts : List.of("World presets:\nCreates a void world.",
                "<h2>Optional presets</h2>\nCreates a void world.\n<h2>Features</h2>\nAdds food.",
                "If enabled, removes all structures.", "When selected, creates a void world.",
                "启用后移除下界生物。", "Not skyblock world generation."))
            assertTrue(WorldGenerationPolicy.findings(facts).isEmpty(), facts);
        assertFalse(WorldGenerationPolicy.findings("World presets:\nCreates a void world.\n\nRemoves mobs by default.").isEmpty());
    }
}
