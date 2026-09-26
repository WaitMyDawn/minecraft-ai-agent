package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "包内类别构成"（用户指标）的离线测试。
 *
 * <p>它存在的理由是一个真实误导：某轮 37 个模组里只有 12 个是"以 类别(X) 身份入包"，
 * 另外 24 个是"附属(核心)"、1 个是补位 —— 而配额表只统计前者，于是显示 `magic 0/7`
 * 并配上"候选不足或审核员未选中"，可包里魔法内容其实有 8 个（铁魔法本体 + 7 个附属）。
 * 这里盯死三件事：池标签优先、多标签各算一次、"查不到归未分类且不计入缺口"。
 */
class InPackCategoryTest {

    @Test
    @DisplayName("池标签优先：以\"附属\"入包的模组也按其池类别计入")
    void usesPoolLabels() {
        Map<String, Set<String>> pool = Map.of(
                "irons-spells-n-spellbooks", Set.of("magic"),
                "winds-spellbooks", Set.of("magic"),
                "cataclysm-weaponery", Set.of("equipment"));
        Map<String, Integer> counts = ChatController.inPackCategoryCounts(
                List.of("irons-spells-n-spellbooks", "winds-spellbooks", "cataclysm-weaponery", "geckolib"),
                pool, null);

        assertEquals(2, counts.get("magic"), "两个魔法模组都该计入 magic");
        assertEquals(1, counts.get("equipment"));
        assertEquals(1, counts.get("未分类"), "池里查不到、缓存也没有 → 未分类（不是缺口）");
    }

    @Test
    @DisplayName("多标签模组在每个类别里各算一次（口径如此，文案里也这么写）")
    void multiLabelCountsOncePerCategory() {
        Map<String, Set<String>> pool = Map.of("some-mod", Set.of("magic", "adventure"));
        Map<String, Integer> counts = ChatController.inPackCategoryCounts(List.of("some-mod"), pool, null);
        assertEquals(1, counts.get("magic"));
        assertEquals(1, counts.get("adventure"));
    }

    @Test
    @DisplayName("空包不报缺口；缓存为 null 也不能 NPE")
    void emptyPackAndNullCache() {
        assertTrue(ChatController.inPackCategoryCounts(List.of(), null, null).isEmpty());
        Map<String, Integer> onlyUnclassified = ChatController.inPackCategoryCounts(List.of("x"), null, null);
        assertEquals(1, onlyUnclassified.get("未分类"));
        assertEquals(1, onlyUnclassified.size(), "未分类之外不该凭空冒出类别");
    }
}
