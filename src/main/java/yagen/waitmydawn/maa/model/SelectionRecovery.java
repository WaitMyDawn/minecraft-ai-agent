package yagen.waitmydawn.maa.model;

import java.util.*;
import java.util.function.Function;

/** 基于实际选版闭包调整可选根；历史诊断保留在报告中，不作为当前留存范围的完整性结论。 */
public final class SelectionRecovery {
    private SelectionRecovery() {}
    public record Report(List<String> removed, List<String> missingProtected,
                         List<ResolutionResult.Unresolved> originalUnresolved, List<ResolutionResult.Dropped> originalDropped,
                         DependencyClosure.Report closure, int trials) {}
    public record Result(ResolutionResult resolution, Set<String> roots, Report report) {}

    public static Result recover(ResolutionResult original, Set<String> roots, Set<String> protectedRoots,
                                 Set<String> blocked, Function<Set<String>, ResolutionResult> resolver) {
        var current = original;
        var retained = new LinkedHashSet<>(roots);
        var removed = new TreeSet<String>();
        int trials = 0;
        for (int round = 0; round < 2; round++) {
            var graph = current.graph();
            var closure = DependencyClosure.capture(graph, current.unresolved(), Map.of());
            var issues = closure.inspect(graph.allSlugs).issues();
            var vetoes = new HashSet<String>();
            var aliases = new HashMap<>(graph.projectAliases);
            graph.selectedVersions.forEach((slug, v) -> aliases.put(v.path("project_id").asText(), slug));
            blocked.forEach(s -> vetoes.add(aliases.getOrDefault(s, s)));
            var next = new LinkedHashSet<>(retained);
            for (String root : retained) {
                if (protectedRoots.contains(root)) continue;
                var scope = reachable(graph, root);
                boolean broken = issues.stream().anyMatch(i -> scope.contains(i.dependent())
                        && i.severity() == DependencyClosure.Severity.BROKEN
                        // 汇总版本约束可能由另一个根施加；由其具体选版要求定位引入方。
                        && !(i.dependent().equals(i.dependency()) && i.code().equals("VERSION_CONFLICT")));
                if (!graph.allSlugs.contains(root) || !Collections.disjoint(scope, vetoes) || broken) next.remove(root);
            }
            if (next.equals(retained)) break;
            var trialRemoved = new TreeSet<>(retained); trialRemoved.removeAll(next);
            ResolutionResult trial;
            try { trials++; trial = resolver.apply(Collections.unmodifiableSet(next)); }
            catch (java.util.concurrent.CancellationException canceled) { throw canceled; }
            catch (RuntimeException unavailable) { break; }
            if (trial == null) break;
            // 移除可选内容不能连带丢失原本仍在图中的受保护项目。
            if (protectedRoots.stream().filter(graph.allSlugs::contains).anyMatch(s -> !trial.graph().allSlugs.contains(s))) break;
            if (protectedRoots.stream().filter(graph.selectedVersions::containsKey).anyMatch(s ->
                    !Objects.equals(graph.selectedVersions.get(s).path("id").asText(),
                            trial.graph().selectedVersions.containsKey(s) ? trial.graph().selectedVersions.get(s).path("id").asText() : ""))) break;
            removed.addAll(trialRemoved); retained = next; current = trial;
        }
        var audit = DependencyClosure.capture(current.graph(), current.unresolved(), Map.of()).inspect(current.graph().allSlugs);
        var finalGraph = current.graph();
        var missing = protectedRoots.stream().filter(s -> !finalGraph.allSlugs.contains(s)).sorted().toList();
        return new Result(current, Set.copyOf(retained), new Report(List.copyOf(removed), missing,
                List.copyOf(original.unresolved()), List.copyOf(original.dropped()), audit, trials));
    }

    public static boolean complete(ResolutionResult result, Set<String> roots) {
        if (!result.isComplete() && result.unresolved().isEmpty() && result.dropped().isEmpty()) return false;
        return !result.budgetExhausted() && result.graph().allSlugs.containsAll(roots)
                && DependencyClosure.capture(result.graph(), result.unresolved(), Map.of()).inspect(result.graph().allSlugs).status()
                    == DependencyClosure.Status.COMPLETE;
    }

    public static Set<String> verifiedProjects(ResolutionResult result) {
        var invalid = new HashSet<String>();
        DependencyClosure.capture(result.graph(), result.unresolved(), Map.of()).inspect(result.graph().allSlugs)
                .issues().forEach(i -> invalid.add(i.dependent()));
        var pending = new ArrayDeque<>(invalid);
        while (!pending.isEmpty()) for (String dependent : result.graph().getDependentsOf(pending.removeFirst()))
            if (invalid.add(dependent)) pending.addLast(dependent);
        var valid = new TreeSet<>(result.graph().allSlugs); valid.removeAll(invalid);
        return Set.copyOf(valid);
    }

    private static Set<String> reachable(DependencyGraph graph, String root) {
        var selected = new HashSet<String>(); var pending = new ArrayDeque<String>(); pending.add(root);
        while (!pending.isEmpty()) {
            var slug = pending.removeFirst(); if (!selected.add(slug)) continue;
            graph.getDependenciesOf(slug).forEach(pending::addLast);
        }
        return selected;
    }
}
