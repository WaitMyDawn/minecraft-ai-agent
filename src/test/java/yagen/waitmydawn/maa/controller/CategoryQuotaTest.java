package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.controller.ChatController.SearchIntent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类别权重合并的离线测试（P3-7）。
 *
 * <p>覆盖的是"Tool 下发的类别权重能否真正落到配额上"这件事——它是纯函数
 * （{@code ChatController.mergeCategoryTargets}），所以可以确定性断言，
 * 不需要起 Spring、不需要调模型。
 */
class CategoryQuotaTest {

    private static SearchIntent intent(String category, int ratio, String... kws) {
        return new SearchIntent(category, ratio, List.of(kws));
    }

    private static int totalRatio(List<SearchIntent> intents) {
        return intents.stream().mapToInt(SearchIntent::ratio).sum();
    }

    @Test
    @DisplayName("已有该类别的意图：权重相加，其它类别占比被稀释，总和仍为 100")
    void mergesWeightIntoExistingIntent() {
        List<SearchIntent> base = List.of(
                intent("magic", 20, "spell"),
                intent("technology", 80, "machine"));
        Map<String, Integer> targets = Map.of("magic", 40);

        List<SearchIntent> merged = ChatController.mergeCategoryTargets(base, targets, new LinkedHashMap<>());

        int magic = merged.stream().filter(i -> i.category().equals("magic")).findFirst().orElseThrow().ratio();
        int tech = merged.stream().filter(i -> i.category().equals("technology")).findFirst().orElseThrow().ratio();
        assertTrue(magic > 20, "magic 占比应上升，实际=" + magic);
        assertTrue(tech < 80, "technology 占比应被稀释，实际=" + tech);
        assertEquals(100, totalRatio(merged), "归一化后总和必须回到 100");
    }

    @Test
    @DisplayName("该类别合法但本轮没有意图：用权威推荐检索词兜底，配额才落得下去")
    void backfillsMissingCategoryFromRegistryHints() {
        List<SearchIntent> base = List.of(intent("technology", 100, "machine"));
        Map<String, Integer> targets = Map.of("magic", 10);
        Map<String, String> notes = new LinkedHashMap<>();

        List<SearchIntent> merged = ChatController.mergeCategoryTargets(base, targets, notes);

        SearchIntent magic = merged.stream().filter(i -> i.category().equals("magic")).findFirst().orElse(null);
        assertTrue(magic != null, "缺失的类别应被兜底补入，实际=" + merged);
        assertFalse(magic.keywords().isEmpty(), "兜底类别必须有检索词，否则配额仍然落不到候选上");
        assertTrue(notes.containsKey("magic") && notes.get("magic").contains("兜底"),
                "必须记录兜底原因，实际=" + notes);
        assertEquals(100, totalRatio(merged));
    }

    @Test
    @DisplayName("非法类别：忽略并记录，不污染配额")
    void ignoresIllegalCategory() {
        List<SearchIntent> base = List.of(intent("technology", 100, "machine"));
        Map<String, Integer> targets = Map.of("combat123", 10);
        Map<String, String> notes = new LinkedHashMap<>();

        List<SearchIntent> merged = ChatController.mergeCategoryTargets(base, targets, notes);

        assertEquals(1, merged.size(), "非法类别不应被加入，实际=" + merged);
        assertTrue(notes.containsKey("combat123"), "必须记录忽略原因，实际=" + notes);
    }

    @Test
    @DisplayName("无增量时原样返回：不引入额外归一化误差")
    void noTargetsKeepsIntentsUntouched() {
        List<SearchIntent> base = List.of(intent("magic", 33, "spell"), intent("technology", 67, "machine"));
        List<SearchIntent> merged = ChatController.mergeCategoryTargets(base, Map.of(), new LinkedHashMap<>());
        assertEquals(base, merged);
    }
}
