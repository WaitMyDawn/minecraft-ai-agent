package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.BuildIntent;
import java.util.*;
import java.util.function.Function;

/** 一轮最多补入三个已核实候选；试解不覆盖原图，失败时完整保留已装配结果。 */
public final class RequirementRepair {
    private RequirementRepair() {}
    public record Result(ResolutionResult resolution, Set<String> roots, String status, List<String> added) {}
    public static Result attempt(ResolutionResult original, Set<String> roots, List<String> proposed,
                                 Set<String> blocked, BuildIntent.CountTarget target, Set<String> priorRoots,
                                 Function<Set<String>, ResolutionResult> resolve) {
        if (proposed.isEmpty()) return new Result(original, roots, "NO_QUALIFIED_CANDIDATE", List.of());
        if (!original.isComplete()) return new Result(original, roots, "STATIC_CHECK_INCOMPLETE", List.of());
        var added = proposed.stream().distinct().filter(s -> !blocked.contains(s))
                .filter(s -> !original.graph().allSlugs.contains(s)).limit(3).toList();
        if (added.isEmpty()) return new Result(original, roots, "NO_QUALIFIED_CANDIDATE", List.of());
        var next = new LinkedHashSet<>(roots); next.addAll(added);
        ResolutionResult trial;
        try { trial = resolve.apply(Collections.unmodifiableSet(next)); }
        catch (java.util.concurrent.CancellationException canceled) { throw canceled; }
        catch (RuntimeException unavailable) { return new Result(original, roots, "TRIAL_UNVERIFIED", List.of()); }
        String rejection = "";
        if (!trial.isComplete() || !trial.graph().allSlugs.containsAll(original.graph().allSlugs)
                || !trial.graph().allSlugs.containsAll(added)) rejection = "DEPENDENCY_OR_RETENTION_REJECTED";
        if (original.graph().selectedVersions.entrySet().stream().anyMatch(e -> {
            var version = trial.graph().selectedVersions.get(e.getKey());
            return version == null || !version.path("id").asText().equals(e.getValue().path("id").asText());
        })) rejection = "VERSION_CHANGE_REJECTED";
        if (trial.graph().allSlugs.stream().anyMatch(blocked::contains)
                || trial.graph().selectedVersions.values().stream().anyMatch(v -> blocked.contains(v.path("project_id").asText())))
            rejection = "EXCLUDED_PROJECT_REJECTED";
        if (target != null) {
            int upper = target.max();
            if (target.scope() == BuildIntent.CountScope.TOTAL_FILES && target.precision() == BuildIntent.Precision.APPROXIMATE)
                upper += Math.min(10, (int) Math.ceil(upper * .1));
            long actual = switch (target.scope()) {
                case TOTAL_FILES -> trial.graph().allSlugs.size();
                case ROOT_MODS -> next.size();
                case ADDED_ROOTS -> next.stream().filter(s -> !priorRoots.contains(s)).count();
            };
            if (actual > upper) rejection = "QUANTITY_REJECTED";
        }
        return rejection.isEmpty() ? new Result(trial, Set.copyOf(next), "APPLIED_ONE_PASS", added)
                : new Result(original, roots, rejection, List.of());
    }
}
