package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 相关性打分的离线测试（P4-A）。
 *
 * <p>这套分数现在直接决定"谁能进 150 条候选池、Critic 先看到谁"，所以每条口径都要能被断言：
 * 字段权重、词元化与短语 AND、词长加成、热度归一的分母与下限。
 */
class RetrievalScorerTest {

    // ---------- 词元化 ----------

    @Test
    @DisplayName("短语按非字母数字拆成词元，多词关键词不再要求整串字面出现")
    void tokenizesPhrases() {
        assertEquals(List.of("item", "transport"), RetrievalScorer.tokenize("item transport"));
        assertEquals(List.of("create", "aeronautics"), RetrievalScorer.tokenize("Create-Aeronautics"));
        assertEquals(List.of("storage"), RetrievalScorer.tokenize("storage storage"), "同一词元只算一次");
    }

    @Test
    @DisplayName("过短的噪声词元被丢弃，3 字符的类别词保留")
    void dropsTooShortTerms() {
        assertEquals(List.of(), RetrievalScorer.tokenize("a of x"));
        assertEquals(List.of("ae2"), RetrievalScorer.tokenize("ae2"));
        assertEquals(List.of("fps", "qol", "api"), RetrievalScorer.tokenize("fps qol api"));
    }

    // ---------- 词长权重 ----------

    @Test
    @DisplayName("长词加成，短词保持 1.0（不惩罚 api/fps 这类短但精确的词）")
    void longTermsGetBoostShortTermsDoNot() {
        assertEquals(1.0, RetrievalScorer.termWeight("machines"), 1e-9);   // 8 字符
        assertEquals(1.5, RetrievalScorer.termWeight("automation"), 1e-9); // 10 字符
        assertEquals(1.0, RetrievalScorer.termWeight("api"), 1e-9);
        assertEquals(1.0, RetrievalScorer.termWeight("qol"), 1e-9);
    }

    // ---------- 字段权重 ----------

    @Test
    @DisplayName("字段权重：标题 2 > 简介 1 > slug 0.5，同一词元只按最强字段计一次")
    void fieldWeightsAreRanked() {
        List<String> terms = RetrievalScorer.tokenize("machine");

        RetrievalScorer.Match title = RetrievalScorer.match(terms, "unknown-slug", "machine factory", "");
        assertTrue(title.hit());
        assertEquals(2.0, title.score(), 1e-9);

        RetrievalScorer.Match desc = RetrievalScorer.match(terms, "unknown-slug", "a mod", "adds a machine");
        assertEquals(1.0, desc.score(), 1e-9);

        RetrievalScorer.Match slug = RetrievalScorer.match(terms, "machine-tools", "a mod", "nothing here");
        assertEquals(0.5, slug.score(), 1e-9, "只被 slug 命中也算命中，但只是弱信号");

        RetrievalScorer.Match both = RetrievalScorer.match(terms, "machine-tools", "machine factory", "adds a machine");
        assertEquals(2.0, both.score(), 1e-9, "标题与简介都命中不该重复加分，取最强字段");
    }

    @Test
    @DisplayName("slug 权重不高于任何其它字段：缩写型 slug 不会凭空拉开分差")
    void slugIsNeverTheStrongestSignal() {
        assertTrue(RetrievalScorer.W_SLUG < RetrievalScorer.W_DESC);
        assertTrue(RetrievalScorer.W_DESC < RetrievalScorer.W_TITLE);
    }

    // ---------- 短语 AND ----------

    @Test
    @DisplayName("多词元按 OR 召回：命中任一词元就进池，命中越多词元分越高")
    void phrasePartialHitStillRecalls() {
        List<String> terms = RetrievalScorer.tokenize("item transport");

        RetrievalScorer.Match partial = RetrievalScorer.match(terms, "", "item frame", "a container");
        assertTrue(partial.hit(), "只命中 item 也要进池：AND 会把召回面收窄到几乎为零（实测 A01 命中 313→44）");
        assertEquals(2.0, partial.score(), 1e-9, "只有 item 命中标题：1.0×2");

        RetrievalScorer.Match both = RetrievalScorer.match(terms, "", "item transport pipes", "");
        assertTrue(both.hit());
        assertEquals(5.0, both.score(), 1e-9, "item 1.0×2 + transport 1.5×2");
    }

    @Test
    @DisplayName("短语的两个词元可以落在不同字段（标题 + 简介），字段权重分别计算")
    void phraseTermsMayMatchDifferentFields() {
        RetrievalScorer.Match m = RetrievalScorer.match(
                RetrievalScorer.tokenize("item transport"), "", "item frame", "helps transport stuff");
        assertEquals(3.5, m.score(), 1e-9, "item 在标题 1.0×2 + transport 在简介 1.5×1.0");
    }

    @Test
    @DisplayName("一个词元都没命中才算未命中")
    void noTermHitMeansMiss() {
        assertFalse(RetrievalScorer.match(RetrievalScorer.tokenize("item transport"),
                "", "unrelated mod", "nothing to see").hit());
        assertFalse(RetrievalScorer.match(List.of(), "", "anything", "").hit());
    }

    @Test
    @DisplayName("命中词元会被记录下来，供 Critic 上下文解释")
    void recordsHitTerms() {
        RetrievalScorer.Match m = RetrievalScorer.match(
                RetrievalScorer.tokenize("machine automation"), "", "machine automation", "");
        assertEquals(List.of("machine", "automation"), m.terms());
    }

    // ---------- 热度归一 ----------

    @Test
    @DisplayName("热度归一：全池最高下载量得 1.0，最低得下限 0.2，且对数压缩")
    void popularityNormMatchesAgreedFormula() {
        assertEquals(1.0, RetrievalScorer.popularityNorm(10_000, 10_000), 1e-9);
        assertEquals(0.8, RetrievalScorer.popularityNorm(1_000, 10_000), 1e-9,
                "用户确认的例：log10(1000)/log10(10000)=0.75 → 0.2+0.8×0.75=0.8");
        assertEquals(RetrievalScorer.POP_FLOOR, RetrievalScorer.popularityNorm(1, 10_000), 1e-9,
                "log10(1)=0，必须落到下限而不是清零：强匹配但极新的模组不该被直接抹掉");
    }

    @Test
    @DisplayName("热度归一单调不超 1，小池子（最大下载量<10）也不会数值爆炸")
    void popularityNormIsBoundedAndMonotone() {
        for (long dl : new long[]{1, 10, 100, 1_000, 100_000, 10_000_000}) {
            double norm = RetrievalScorer.popularityNorm(dl, 10_000_000);
            assertTrue(norm >= RetrievalScorer.POP_FLOOR && norm <= 1.0, "越界: dl=" + dl + " norm=" + norm);
        }
        assertTrue(RetrievalScorer.popularityNorm(1_000, 10_000) < RetrievalScorer.popularityNorm(5_000, 10_000));
        assertTrue(RetrievalScorer.popularityNorm(5, 5) <= 1.0, "分母下限保护：池子最大值 <10 时不越界");
        assertTrue(RetrievalScorer.popularityNorm(999, 5) <= 1.0, "理论上不会出现下载量大于全池最大值，仍要夹住");
        assertTrue(RetrievalScorer.popularityNorm(1, 0) >= RetrievalScorer.POP_FLOOR);
    }

    @Test
    @DisplayName("热度只能打折不能加分：finalScore ≤ textScore")
    void popularityOnlyDiscounts() {
        double text = RetrievalScorer.match(
                RetrievalScorer.tokenize("machine automation"),
                "", "machine automation", "").score();
        assertEquals(5.0, text, 1e-9);
        double norm = RetrievalScorer.popularityNorm(500_000, 10_000_000);
        assertTrue(text * norm <= text, "热度系数必须 ≤ 1");
    }
}
