package yagen.waitmydawn.maa.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 候选相关性打分（P4-A）：把旧的"关键词命中计数"升级为<b>可解释的加权分 × 热度归一</b>。
 *
 * <p>旧口径的问题（实测）：同类别内每命中一个关键词 +1，分域只有 1~4，
 * 送审候选里 51.5% 并列 1 分、44.2% 并列 2 分，排序实际由下载量决定，而关键词命中哪个字段
 * （标题 / 简介 / slug）完全没有区别。
 *
 * <p>新口径拆成两层：
 * <pre>
 *   textScore  = Σ_命中的词元t  termW(t) × max(命中该词的字段权重)     // 文本相关度
 *   finalScore = textScore × (POP_FLOOR + (1-POP_FLOOR) × popRatio)   // 再乘热度系数
 *   popRatio   = log10(downloads) / log10(全池最高下载量)               // 全池同一分母
 * </pre>
 * 三个设计取舍（都有明确理由，别随手改）：
 * <ol>
 *   <li><b>标题 2 / 简介 1 / slug 0.5</b>：标题是模组自己起的名字，最强证据；简介里出现往往只是
 *       "顺便提一句"；slug 是归一化名字，与标题高度冗余，且缩写型 slug（jei/ae2）与 slug≈title
 *       的模组之间不该因为命名风格被拉开分差，所以只给弱信号权重。</li>
 *   <li><b>热度是乘法且归一化到 [POP_FLOOR, 1]</b>：热度只能"打折"、不能凭空加分，
 *       避免"热门但完全不相关"的模组被下载量抬上来；对数压缩让长尾不至于一刀切；
 *       下限 {@value #POP_FLOOR} 是防止 log10(1)=0 把"强匹配但极新"的模组直接清零。</li>
 *   <li><b>长词加成、短词不惩罚</b>：稀有长词命中信息量更大；而 api/fps/core/qol 这类
 *       "短但精确"的类别词一旦被惩罚会误伤。</li>
 *   <li><b>多词短语按 OR 召回、按命中数计分</b>：短语（"item transport"）拆成词元后，
 *       命中任意一个词元就能把候选捞进池，命中越多词元分越高。实测教训：先写成 AND（要求
 *       每个词元都命中）时，模型爱写 `machine multiblock` 这类短语，A01 的 technology 命中数
 *       从 313 掉到 44——召回被自己的"更精确"收窄了。短语对 live 检索（整串交给 Modrinth）
 *       是好事，所以修的是本地匹配语义，而不是禁止短语。</li>
 * </ol>
 */
public final class RetrievalScorer {

    private RetrievalScorer() {
    }

    /** 命中字段权重：标题 > 简介 > slug（见类注释第 1 条） */
    public static final double W_TITLE = 2.0;
    public static final double W_DESC = 1.0;
    public static final double W_SLUG = 0.5;

    /** 长词（≥ 该长度）命中加成倍率 */
    private static final int LONG_TERM_LEN = 9;
    private static final double LONG_TERM_BOOST = 1.5;

    /** 热度系数下限（见类注释第 2 条） */
    public static final double POP_FLOOR = 0.2;

    /** 词元最短长度：与 findAddons 的核心名 token 口径保持一致，避免 1~2 字符噪声词 */
    private static final int MIN_TERM_LEN = 3;

    /**
     * 关键词 → 词元：小写、按非字母数字切分、去重、丢弃过短词。
     *
     * <p>例：{@code "item transport"} → {@code [item, transport]}；{@code "create-aeronautics"} →
     * {@code [create, aeronautics]}。这样多词短语不必再要求"整串字面出现"（旧实现里
     * {@code "item transport"} 只有字面写过这两个词的简介才可能命中，等于浪费掉一个关键词）。
     */
    public static List<String> tokenize(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String t : keyword.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() >= MIN_TERM_LEN) out.add(t);
        }
        return List.copyOf(out);
    }

    /** 词元权重：长词加成，短词保持 1.0（不惩罚） */
    public static double termWeight(String term) {
        return term.length() >= LONG_TERM_LEN ? LONG_TERM_BOOST : 1.0;
    }

    /** 单个关键词对一条模组的命中结果 */
    public record Match(boolean hit, double score, List<String> terms) {
        public static final Match NONE = new Match(false, 0.0, List.of());
    }

    /**
     * 计算某个关键词对一条模组的文本得分。
     *
     * <p>多词元关键词按 <b>OR</b> 召回、按命中词元数计分：命中任意一个词元就算这条关键词召回成功，
     * 命中的词元越多分越高（每个词元按"命中的最强字段"计分，同一词元只计一次，
     * 不因同时出现在标题和简介而重复加分）。
     *
     * <p>为什么不是 AND：短语是模型自然写出来的（`machine multiblock`、`item transport`），
     * 要求每个词元都出现在同一条模组的文本里，等于把召回面收窄到几乎为零；而"短语整体交给
     * Modrinth 检索"的 live 路径又确实需要短语。OR + 累加计分让两条路径都能用同一份关键词。
     *
     * @param terms 已分词的关键词词元（见 {@link #tokenize(String)}）
     * @param slug  小写 slug
     * @param title 小写标题
     * @param desc  小写简介
     */
    public static Match match(List<String> terms, String slug, String title, String desc) {
        if (terms == null || terms.isEmpty()) return Match.NONE;
        double score = 0;
        List<String> hits = new ArrayList<>(terms.size());
        for (String term : terms) {
            double fieldWeight;
            if (title != null && title.contains(term)) fieldWeight = W_TITLE;
            else if (desc != null && desc.contains(term)) fieldWeight = W_DESC;
            else if (slug != null && slug.contains(term)) fieldWeight = W_SLUG;
            else continue;                                // OR：没命中的词元跳过，不影响其它词元得分
            score += termWeight(term) * fieldWeight;
            hits.add(term);
        }
        return hits.isEmpty() ? Match.NONE : new Match(true, score, List.copyOf(hits));
    }

    /**
     * 热度归一：全池最高下载量 → 1.0，最低 → {@value #POP_FLOOR}。
     *
     * <p>分母由调用方用<b>全池</b>（本轮所有类别合并后的命中）最大值传入，不能按类别各自归一，
     * 否则小类别会被整体抬到 1.0、与大类别不可比。
     */
    public static double popularityNorm(long downloads, long poolMaxDownloads) {
        double denominator = Math.log10(Math.max(10L, poolMaxDownloads));
        double ratio = Math.log10(Math.max(1L, downloads)) / denominator;
        double clamped = Math.min(1.0, Math.max(0.0, ratio));
        return POP_FLOOR + (1.0 - POP_FLOOR) * clamped;
    }
}
