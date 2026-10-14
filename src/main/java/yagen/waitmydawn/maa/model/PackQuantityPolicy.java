package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.BuildIntent;
import java.util.*;

/** 数量收口只移除可选根及孤立前置；不改缓存图，也不靠隐藏依赖诊断满足数量。 */
public final class PackQuantityPolicy {
    private PackQuantityPolicy() {}
    public static Optional<String> shortfall(BuildIntent.CountTarget target, int files) {
        if (target == null || target.scope() != BuildIntent.CountScope.TOTAL_FILES) return Optional.empty();
        int minimum = target.range() ? target.min() : target.precision() == BuildIntent.Precision.EXACT
                ? target.value() : (int) Math.ceil(target.value() * .8);
        return files < minimum ? Optional.of("⚠️ 本轮仅组装 " + files + " 个文件，低于数量目标下限 " + minimum
                + "；这是未补足的清单，数量要求尚未满足。可以继续补充，已保留的核心和前置不会因为数量不足被删除。") : Optional.empty();
    }
    public record Result(ResolutionResult resolution, Set<String> roots, List<String> removed, int totalFiles, String status) {}
    public static Result limit(ResolutionResult resolution, Set<String> roots, Set<String> protectedRoots,
                               BuildIntent.CountTarget target, List<String> removable) {
        var selected = new LinkedHashSet<>(roots);
        var removed = new ArrayList<String>();
        if (roots.stream().anyMatch(s -> !resolution.graph().allSlugs.contains(s)))
            return new Result(resolution, selected, removed, resolution.graph().allSlugs.size(), "UNKNOWN_ROOT_IDENTITY");
        if (target == null || target.scope() != BuildIntent.CountScope.TOTAL_FILES || !resolution.isComplete())
            return new Result(resolution, selected, removed, resolution.graph().allSlugs.size(), "NOT_TRIMMED");
        int upper = target.precision() == BuildIntent.Precision.EXACT ? target.max() : target.range()
                ? target.max() + Math.min(10, (int) Math.ceil(target.max() * .1)) : target.value() + Math.min(10, (int) Math.ceil(target.value() * .1));
        var files = reachable(resolution.graph(), selected);
        for (String slug : removable) {
            if (files.size() <= upper) break;
            if (protectedRoots.contains(slug) || !selected.remove(slug)) continue;
            removed.add(slug);
            files = reachable(resolution.graph(), selected);
        }
        if (removed.isEmpty()) return new Result(resolution, selected, removed, files.size(), files.size() > upper ? "PROTECTED_OVERAGE" : "WITHIN_POLICY");
        var graph = new DependencyGraph();
        graph.allSlugs.addAll(files);
        for (String slug : files) {
            if (resolution.graph().selectedVersions.containsKey(slug)) graph.selectedVersions.put(slug, resolution.graph().selectedVersions.get(slug));
            if (resolution.graph().pinnedSlugs.contains(slug)) graph.pinnedSlugs.add(slug);
            resolution.graph().getDependenciesOf(slug).stream().filter(files::contains).forEach(d -> graph.addEdge(slug, d));
        }
        var adjusted = new ResolutionResult(graph, resolution.status(), selected, resolution.unresolved(), resolution.dropped(),
                resolution.nodeBudget(), resolution.budgetExhausted()).coordinated(resolution.coordination());
        return new Result(adjusted, selected, List.copyOf(removed), files.size(), files.size() > upper ? "PROTECTED_OVERAGE" : "TRIMMED_OPTIONAL");
    }
    private static Set<String> reachable(DependencyGraph graph, Set<String> roots) {
        var result = new TreeSet<String>();
        var pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            String slug = pending.removeFirst();
            if (!graph.allSlugs.contains(slug) || !result.add(slug)) continue;
            graph.getDependenciesOf(slug).forEach(pending::add);
        }
        return result;
    }
}
