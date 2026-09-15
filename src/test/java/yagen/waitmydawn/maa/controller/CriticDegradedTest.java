package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Critic 输出"退化"的识别（坏例 B16）。
 *
 * <p>背景：模型复读候选池会把输出 token 打满，`<approved_mods>` 没有闭合标签，
 * 旧实现把它当成"审核员一个都没挑"，于是补位与告知全被跳过，用户拿到 0.25 倍的包还毫不知情。
 */
class CriticDegradedTest {

    @Test
    @DisplayName("没有标签 / 空输出 / 标签未闭合都算退化")
    void detectsDegeneration() {
        assertTrue(ChatController.looksLikeCriticDegeneration(null));
        assertTrue(ChatController.looksLikeCriticDegeneration("   "));
        assertTrue(ChatController.looksLikeCriticDegeneration("这轮候选都不太行，我就不挑了。"));
        assertTrue(ChatController.looksLikeCriticDegeneration("<approved_mods>create,jei"), "只有开始标签 = 被截断");
        assertTrue(ChatController.looksLikeCriticDegeneration(
                "<approved_mods>create,jei,create-cc-total-logistics,create-factory-logistics"),
                "复读候选池 → 输出被 token 上限截断，正是 B16 的现场");
    }

    @Test
    @DisplayName("正常输出（含闭合标签）不算退化，明确返回空清单也不该被当成退化")
    void normalOutputIsNotDegeneration() {
        assertFalse(ChatController.looksLikeCriticDegeneration("<approved_mods>create,jei</approved_mods>"));
        assertFalse(ChatController.looksLikeCriticDegeneration(
                "短说明\n<approved_mods>ae2</approved_mods>\n"));
        assertFalse(ChatController.looksLikeCriticDegeneration("<approved_mods></approved_mods>"),
                "明确给空清单是审核员的判断，不是输出异常（这种要尊重，不乱补量）");
    }
}
