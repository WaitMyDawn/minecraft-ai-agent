package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldGenerationPolicyTest {
    @Test void configurationListEditingIsNotRemovalOfWorldContent() {
        for (String text : List.of("| `/letmedespawn remove <mobName>` | Remove mobs from the config |",
                "Remove mobs from the denylist", "Removes structures from the whitelist")) {
            assertTrue(WorldGenerationPolicy.findings(text).isEmpty(), text);
            assertTrue(WorldGenerationPolicy.blocked("adventure", "arbitrary-project", text).isEmpty(), text);
        }
    }

    @Test void configurationInstructionsDoNotEraseSeparateWorldRemovalOrExtremeGeneration() {
        for (String text : List.of("Remove mobs from the denylist and disables structures in the Nether",
                "Removes mobs from the world by default", "Remove mobs from the config. Removes structures from the Nether.",
                "Remove mobs from the config. Creates Superflat dimensions.",
                "Remove mobs from the config. Classic Skyblock with infinite void generation."))
            assertFalse(WorldGenerationPolicy.blocked("adventure", "arbitrary-project", text).isEmpty(), text);
    }

    @Test void ordinaryAdventureDoesNotAuthorizeExtremePresetsOrSubtractiveAddons() {
        for (String facts : List.of(
                "Classic SkyBlock with infinite void generation. The biome map matches a normal world.",
                "Datapack that creates worlds with missing terrain.",
                "Collection of datapacks that create Superflat Overworld, Nether, and End Dimensions.",
                "Removes structures, items, mobs and bosses from a Nether expansion, leaving only the biomes and terrain.")) {
            assertFalse(WorldGenerationPolicy.blocked("丰富下界和末地生态，冒险主题", "arbitrary-project", facts).isEmpty(), facts);
            assertTrue(DimensionGeneration.richEvidence(facts).isEmpty(), facts);
        }
    }
    @Test void explicitRequestsAreScopedAndNegativeMentionsDoNotGrantPermission() {
        String sky = "Classic SkyBlock with infinite void generation";
        assertTrue(WorldGenerationPolicy.blocked("制作一个空岛整合包", "unknown", sky).isEmpty());
        assertTrue(WorldGenerationPolicy.blocked("I want a Skyblock pack", "unknown", sky).isEmpty());
        assertTrue(WorldGenerationPolicy.blocked("使用 terrainless-worlds", "terrainless-worlds", "Creates worlds with missing terrain").isEmpty());
        for (String request : List.of("不要空岛，丰富下界生态", "No skyblock please", "不加入unknown", "不要空岛而是正常探险"))
            assertFalse(WorldGenerationPolicy.blocked(request, "unknown", sky).isEmpty(), request);
        assertFalse(WorldGenerationPolicy.blocked("我想玩空岛", "flat", "Creates Superflat dimensions").isEmpty());
        assertFalse(WorldGenerationPolicy.blocked("看看unknown-addon-extra", "unknown-addon", sky).isEmpty());
    }
    @Test void CompatibilityNegationAndOrdinaryFixesAreNotExtremeBehavior() {
        for (String facts : List.of("Compatible with Skyblock packs", "Works with superflat worlds",
                "Does not remove structures", "Without removing terrain", "Not a skyblock world generator",
                "Removes duplicate structures caused by a bug", "Adds new floating island biomes to the Overworld", "添加空岛群系和空岛结构",
                "Structures can be disabled in the configuration", "Removes terrain generation bugs", "移除地形生成漏洞",
                "Overhauls Nether terrain with new biomes and structures"))
            assertTrue(WorldGenerationPolicy.findings(facts).isEmpty(), facts);
        assertFalse(WorldGenerationPolicy.blocked("主世界增加空岛群系", "sky-world", "Classic Skyblock world").isEmpty());
    }
    @Test void anAllowedPresetDoesNotAuthorizeASeparateSubtractiveBehavior() {
        var blocked = WorldGenerationPolicy.blocked("我要空岛世界", "hybrid", "Creates a Skyblock world. Removes mobs from the Nether.");
        assertEquals(List.of(WorldGenerationPolicy.Effect.CONTENT_REMOVAL), blocked.stream().map(WorldGenerationPolicy.Finding::effect).toList());
    }
}
