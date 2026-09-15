package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 本轮新增额度（审核闸门上界）的离线测试。
 *
 * <p>回归点：{@code roundBudgetOf} 是纯函数，覆盖的是"target_count 已达标但用户点名要附属"这一轮，
 * 闸门上界不能是 0——旧实现下 {@code kept.size() >= 0} 恒成立，审核员挑中的附属会被整批丢掉
 * （实测 A10：审核通过 23 → 采纳 0）。
 */
class RoundBudgetTest {

    @Test
    @DisplayName("补量轮：上界就是折算后的补量预算，不受附属核心数放大")
    void fillRoundKeepsFillBudget() {
        assertEquals(7, ChatController.roundBudgetOf(7, 1), "补量轮应原样使用补量预算");
        assertEquals(2, ChatController.roundBudgetOf(2, 3), "补量轮不应被附属额度放大（否则会冲破 target_count）");
    }

    @Test
    @DisplayName("纯附属轮回归：target_count 已达标 + 点名 1 个核心 → 额度是附属额度而不是 0")
    void addonRoundNeverZeroWhenAddonsRequested() {
        assertEquals(5, ChatController.roundBudgetOf(0, 1), "needed=0 且有附属请求时必须给出非 0 额度");
        assertEquals(5, ChatController.roundBudgetOf(-3, 1), "总数已超标时同样按附属额度给，而不是 0");
    }

    @Test
    @DisplayName("多核心附属：额度按核心数线性放大")
    void addonQuotaScalesWithCores() {
        assertEquals(10, ChatController.roundBudgetOf(0, 2));
    }

    @Test
    @DisplayName("没点名附属：额度仍是 0，行为与旧实现一致")
    void noAddonRequestStaysZero() {
        assertEquals(0, ChatController.roundBudgetOf(0, 0));
        assertEquals(0, ChatController.roundBudgetOf(-5, 0));
    }
}
