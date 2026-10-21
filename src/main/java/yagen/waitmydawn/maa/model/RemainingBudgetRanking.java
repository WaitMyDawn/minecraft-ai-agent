package yagen.waitmydawn.maa.model;

import java.util.*;

/** 只调整剩余内容的采纳机会；分类和检索词是排序信号，不能充当用途或兼容证据。 */
public final class RemainingBudgetRanking {
    private RemainingBudgetRanking() {}
    public record Weights(double relevance, double variety, double exploration) {
        public Weights {
            if (!Double.isFinite(relevance + variety + exploration) || relevance <= 0 || variety < 0 || exploration < 0
                    || relevance + variety + exploration > 10) throw new IllegalArgumentException("剩余预算权重无效");
        }
        public static Weights defaults() { return new Weights(.60, .25, .15); }
    }
    public record Candidate(String slug, double relevance, Set<String> categories, Set<String> facets) {
        public Candidate {
            relevance = Double.isFinite(relevance) ? Math.max(0, relevance) : 0;
            categories = Set.copyOf(categories); facets = Set.copyOf(facets);
        }
    }
    public record Decision(String slug, double relevance, double variety, double exploration, double score) {}
    public record Report(List<String> order, List<String> priorities, List<String> deferred, List<Decision> decisions, Weights weights) {}

    public static Report rank(List<String> input, Map<String, Candidate> facts, Set<String> priority,
                              Set<String> retained, Map<String, Integer> familiarity, double influence, Weights weights,
                              boolean backfill) {
        var fixed = new ArrayList<String>(); var pending = new ArrayList<String>();
        for (String slug : new LinkedHashSet<>(input)) if (!retained.contains(slug)) {
            if (priority.contains(slug) || !facts.containsKey(slug)) fixed.add(slug); else pending.add(slug);
        }
        double maximum = pending.stream().map(facts::get).mapToDouble(Candidate::relevance).max().orElse(0);
        var deferred = new ArrayList<String>();
        // 有明确相对相关度时，不用明显较弱的候选凑数；缺少可比较分数时保留既有资格判断。
        if (backfill && maximum > 0) pending.removeIf(slug -> {
            if (facts.get(slug).relevance() >= maximum * .35) return false;
            deferred.add(slug); return true;
        });
        var categoryCounts = new HashMap<String,Integer>(); var facetCounts = new HashMap<String,Integer>();
        var seen = new HashSet<>(retained); seen.addAll(fixed);
        seen.forEach(slug -> observe(facts.get(slug), categoryCounts, facetCounts));
        var decisions = new ArrayList<Decision>(); var order = new ArrayList<>(fixed);
        double personal = Double.isFinite(influence) ? Math.clamp(influence,0,1) : 0;
        double sum = weights.relevance() + weights.variety() + weights.exploration();
        while (!pending.isEmpty()) {
            String best = pending.getFirst(); Decision bestDecision = null;
            for (String slug : pending) {
                var candidate = facts.get(slug);
                double relevance = maximum > 0 ? candidate.relevance() / maximum : 0;
                double variety = (novelty(candidate.categories(),categoryCounts) + novelty(candidate.facets(),facetCounts)) / 2;
                int known = Math.max(0, familiarity.getOrDefault(slug,0));
                double exploration = familiarity.isEmpty() ? 0 : personal / (1 + Math.log1p(known));
                double score = (weights.relevance()*relevance + weights.variety()*variety + weights.exploration()*exploration)/sum;
                if (bestDecision == null || score > bestDecision.score() + 1e-12) {
                    best = slug; bestDecision = new Decision(slug,relevance,variety,exploration,score);
                }
            }
            pending.remove(best); order.add(best); decisions.add(bestDecision); observe(facts.get(best),categoryCounts,facetCounts);
        }
        return new Report(List.copyOf(order),List.copyOf(fixed),List.copyOf(deferred),List.copyOf(decisions),weights);
    }
    private static double novelty(Set<String> values, Map<String,Integer> counts) {
        return values.stream().mapToDouble(v -> 1.0/(1+counts.getOrDefault(v,0))).average().orElse(0);
    }
    private static void observe(Candidate candidate, Map<String,Integer> categories, Map<String,Integer> facets) {
        if (candidate == null) return;
        candidate.categories().forEach(v -> categories.merge(v,1,Integer::sum));
        candidate.facets().forEach(v -> facets.merge(v,1,Integer::sum));
    }
}
