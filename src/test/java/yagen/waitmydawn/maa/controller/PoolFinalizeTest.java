package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.controller.ChatController.CandidateMod;
import yagen.waitmydawn.maa.model.RetrievalScorer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选池收口的离线测试（P4-A）。
 *
 * <p>覆盖两件容易写错、错了又不报错的事：
 * ① 热度分母必须是<b>全池</b>的（按类别各自归一会让小类别整体虚高）；
 * ② 一个模组挂多个类别标签时会在多个桶里被召回，合并阶段必须去重并保留分最高的那一份。
 */
class PoolFinalizeTest {

    /** 造一条候选：文本分由传入的分数累加（等价于命中了一个关键词） */
    private static CandidateMod mod(String slug, long downloads, double textScore) {
        CandidateMod c = new CandidateMod(slug, "title of " + slug, "desc", downloads);
        c.addMatch(textScore, List.of(slug.split("-")[0]));
        return c;
    }

    @Test
    @DisplayName("热度分母取全池最大值：小类别不会被自己的小分母抬到 1.0")
    void globalDenominatorAcrossCategories() {
        List<CandidateMod> big = new ArrayList<>(List.of(mod("hot-mod", 10_000_000L, 2.0)));
        List<CandidateMod> small = new ArrayList<>(List.of(mod("niche-mod", 1_000L, 2.0)));

        long poolMax = ChatController.applyGlobalScores(List.of(big, small));

        assertEquals(10_000_000L, poolMax);
        assertEquals(1.0, big.get(0).popNorm, 1e-9, "全池最高下载量 → 热度系数 1.0");
        assertTrue(small.get(0).popNorm < 0.6, "小类别按全池分母算，不该被抬到 1.0，实际=" + small.get(0).popNorm);
        assertEquals(2.0, big.get(0).finalScore, 1e-9);
        assertEquals(2.0 * small.get(0).popNorm, small.get(0).finalScore, 1e-9);
    }

    @Test
    @DisplayName("热度只能打折：最终分不超过文本分")
    void finalScoreNeverExceedsTextScore() {
        List<CandidateMod> pool = new ArrayList<>(List.of(
                mod("a", 5_000_000L, 4.0), mod("b", 1L, 1.0)));
        ChatController.applyGlobalScores(List.of(pool));
        for (CandidateMod c : pool) {
            assertTrue(c.finalScore <= c.textScore.sum(), c.slug);
            assertTrue(c.popNorm >= RetrievalScorer.POP_FLOOR && c.popNorm <= 1.0, c.slug);
        }
    }

    @Test
    @DisplayName("跨类去重：同一 slug 保留最终分最高的那一份（连带它的类别标签）")
    void dedupeKeepsBestCopy() {
        CandidateMod tech = mod("create", 30_000_000L, 1.0);
        tech.category = "technology";
        CandidateMod deco = mod("create", 30_000_000L, 3.0);
        deco.category = "decoration";
        CandidateMod other = mod("jei", 50_000_000L, 2.0);
        other.category = "utility";

        List<CandidateMod> merged = new ArrayList<>(List.of(tech, deco, other));
        ChatController.applyGlobalScores(List.of(merged));
        List<CandidateMod> out = ChatController.dedupeAndCap(merged);

        assertEquals(2, out.size(), "create 的两次召回要合成一条");
        // create 文本分 3.0 虽被热度打折（下载量低于 jei），仍高于 jei 的 2.0×1.0 —— 热度只能打折不能加分
        assertEquals("create", out.get(0).slug, "按最终分降序：3.0×0.98 > 2.0×1.0");
        assertEquals("jei", out.get(1).slug);
        assertEquals("decoration", out.get(0).category, "保留分高的那一份的类别标签");
        assertEquals(3.0 * out.get(0).popNorm, out.get(0).finalScore, 1e-9);
    }

    @Test
    @DisplayName("全局排序后截断到 150 条上限")
    void capsAtPoolSize() {
        List<CandidateMod> merged = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            merged.add(mod("mod-" + i, 1_000L + i, 1.0 + i));
        }
        ChatController.applyGlobalScores(List.of(merged));
        List<CandidateMod> out = ChatController.dedupeAndCap(merged);

        assertEquals(150, out.size(), "候选池上限 POOL_CAP=150");
        assertEquals("mod-199", out.get(0).slug, "文本分最高的排在最前");
    }
}
