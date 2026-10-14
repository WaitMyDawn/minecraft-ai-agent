package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BasicBuildInputsTest {
    private BuildIntent plan(BuildIntent.Mode mode) {
        return new BuildIntent(1, mode, "", "", BuildEnvironment.current("1.21.1", "neoforge"),
                mode == BuildIntent.Mode.EDIT ? null : new BuildIntent.CountTarget(60, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L);
    }
    @Test void arbitraryThemeWithoutBasicsGetsConcreteSuggestion() {
        for (String theme : List.of("冒险", "自动化流水线", "蒸汽朋克", "烹饪经营")) {
            String question = BasicBuildInputs.confirmation("我想玩一个" + theme + "整合包", plan(BuildIntent.Mode.NEW));
            assertTrue(question.contains("neoforge 1.21.1")); assertTrue(question.contains("80～120"));
        }
    }
    @Test void completeMinimalRequestDoesNotRequireCoreOrDetailedGameplay() {
        for (String prompt : List.of("NeoForge1.21.1冒险包，20～30个模组", "Fabric 1.20.1 科技包，模组数量30", "Forge 1.20.1建筑包，三十个模组"))
            assertEquals("", BasicBuildInputs.confirmation(prompt, plan(BuildIntent.Mode.NEW)));
    }
    @Test void singleEditDoesNotBecomeLargeNewBuildAndExplicitCountIsPreserved() {
        assertEquals("", BasicBuildInputs.confirmation("只加JEI", plan(BuildIntent.Mode.EDIT)));
        String question = BasicBuildInputs.confirmation("冒险包，60个模组", plan(BuildIntent.Mode.NEW));
        assertTrue(question.contains("60 个")); assertFalse(question.contains("80～120"));
    }
}
