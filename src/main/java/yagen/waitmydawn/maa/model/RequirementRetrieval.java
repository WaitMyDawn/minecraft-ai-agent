package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.BuildRequirement;
import yagen.waitmydawn.maa.model.agent.BuildIntent;
import java.util.*;

/** 逐项检索与优先级不依赖固定能力枚举；检索关联仅是供审核使用的线索。 */
public final class RequirementRetrieval {
    private RequirementRetrieval() {}
    public record Query(String requirementId, String category, String keyword) {}
    public static List<BuildRequirement> active(BuildIntent intent, Set<String> freshIds) {
        if (intent == null) return List.of();
        return intent.requirements().stream().filter(r -> r.kind() != BuildRequirement.Kind.CHANGE)
                .filter(r -> intent.mode() != BuildIntent.Mode.EDIT || freshIds.contains(r.id()))
                .flatMap(r -> r.criteria().isEmpty() ? java.util.stream.Stream.of(r) : r.criteria().stream().map(c -> c.target(r)))
                .filter(r -> r.kind() != BuildRequirement.Kind.CHANGE && !r.searches().isEmpty()).toList();
    }
    /** 先给每条要求一次召回机会，再进行次级关键词检索，避免长词表占尽共享预算。 */
    public static List<Query> queries(List<BuildRequirement> requirements, Set<String> excludedCategories) {
        var rows = requirements.stream().map(r -> r.searches().stream()
                .filter(s -> !excludedCategories.contains(s.category()))
                .flatMap(s -> s.keywords().stream().map(k -> new Query(r.id(), s.category(), k))).toList()).toList();
        var result = new ArrayList<Query>();
        int max = rows.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < max; i++) for (var row : rows) if (i < row.size()) result.add(row.get(i));
        return List.copyOf(result);
    }
    public static List<String> order(List<String> eligible, Map<String, Set<String>> hints, Set<String> existing) {
        var covered = new HashSet<String>();
        existing.forEach(s -> covered.addAll(hints.getOrDefault(s, Set.of())));
        var pending = new ArrayList<>(new LinkedHashSet<>(eligible));
        var result = new ArrayList<String>();
        while (!pending.isEmpty()) {
            String best = pending.getFirst(); long gain = -1;
            for (String slug : pending) {
                long next = hints.getOrDefault(slug, Set.of()).stream().filter(id -> !covered.contains(id)).count();
                if (next > gain) { best = slug; gain = next; }
            }
            pending.remove(best); result.add(best); covered.addAll(hints.getOrDefault(best, Set.of()));
        }
        return List.copyOf(result);
    }
}
