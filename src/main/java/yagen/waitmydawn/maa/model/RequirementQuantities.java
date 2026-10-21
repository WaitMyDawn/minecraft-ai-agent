package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;

/** 召回关联只用于分配机会，最终数量必须以用途或关系证据另行计数。 */
public final class RequirementQuantities {
    public static final int DEFAULT_ADDON_PER_CORE = 5;
    private RequirementQuantities() {}
    public static List<BuildRequirement> targets(List<BuildRequirement> requirements) {
        return requirements.stream().filter(r -> r.kind() != BuildRequirement.Kind.CHANGE).flatMap(r -> r.criteria().isEmpty() ? java.util.stream.Stream.of(r)
                : r.criteria().stream().map(c -> c.target(r))).toList();
    }
    public static int candidateLimit(BuildRequirement target) {
        return target.quantity() == null ? 8 : Math.min(32, Math.max(8, target.quantity().max() * 2));
    }
    /** 附属检索的文本命中不能占用关系候选的数量额度或优先机会。 */
    public static void retainVerifiedAddonHints(Map<String, Set<String>> hints, List<BuildRequirement> requirements,
                                                Map<String, Set<String>> verifiedCores) {
        var addonTargets = new HashMap<String, Set<String>>();
        targets(requirements).stream().filter(r -> !r.addonCores().isEmpty())
                .forEach(r -> addonTargets.put(r.id(), Set.copyOf(r.addonCores())));
        hints.replaceAll((slug, ids) -> {
            var retained = new LinkedHashSet<>(ids);
            retained.removeIf(id -> addonTargets.containsKey(id)
                    && Collections.disjoint(addonTargets.get(id), verifiedCores.getOrDefault(slug, Set.of())));
            return retained;
        });
    }
    private static boolean explicit(List<BuildRequirement> requirements) {
        return requirements.stream().anyMatch(r -> r.quantity() != null || r.criteria().stream().anyMatch(c -> c.quantity() != null));
    }
    public static int addonReserve(List<BuildRequirement> requirements, int fallback) {
        if (!explicit(requirements)) return fallback;
        var explicit = targets(requirements).stream().filter(r -> !r.addonCores().isEmpty() && r.quantity() != null).toList();
        long cores = explicit.stream().flatMap(r -> r.addonCores().stream()).distinct().count();
        return Math.min(500, explicit.stream().mapToInt(r -> r.quantity().max()).sum()
                + Math.max(0, fallback - (int) cores * DEFAULT_ADDON_PER_CORE));
    }
    private static long count(String id, RequirementQuantity quantity, Set<String> selected, Set<String> original,
                              Map<String, Set<String>> hints) {
        return selected.stream().filter(s -> quantity.scope() != RequirementQuantity.Scope.ADDED_ROOTS || !original.contains(s))
                .filter(s -> hints.getOrDefault(s, Set.of()).contains(id)).count();
    }
    public static List<String> order(List<String> candidates, Map<String, Set<String>> hints, Set<String> selected,
                                     Set<String> original, List<BuildRequirement> requirements) {
        if (!explicit(requirements)) return candidates;
        var targets = targets(requirements).stream().filter(r -> r.quantity() != null).toList();
        if (targets.isEmpty()) return candidates;
        var counts = new HashMap<String, Long>();
        targets.forEach(r -> counts.put(r.id(), count(r.id(), r.quantity(), selected, original, hints)));
        var pending = new ArrayList<>(new LinkedHashSet<>(candidates));
        var result = new ArrayList<String>();
        while (!pending.isEmpty()) {
            String best = pending.getFirst(); int bestScore = -1;
            for (String slug : pending) {
                int score = 0;
                for (var r : targets) if (hints.getOrDefault(slug, Set.of()).contains(r.id())) {
                    long n = counts.get(r.id());
                    if (n < r.quantity().min()) score += 1000;
                    else if (n < r.quantity().max()) score++;
                }
                if (score > bestScore) { bestScore = score; best = slug; }
            }
            pending.remove(best); result.add(best);
            if (!selected.contains(best)) for (var r : targets)
                if (hints.getOrDefault(best, Set.of()).contains(r.id())
                        && (r.quantity().scope() != RequirementQuantity.Scope.ADDED_ROOTS || !original.contains(best)))
                    counts.merge(r.id(), 1L, Long::sum);
        }
        return result;
    }
    public static boolean allows(String slug, Map<String, Set<String>> hints, Set<String> selected,
                                 Set<String> original, List<BuildRequirement> requirements) {
        if (!explicit(requirements)) return true;
        var targets = targets(requirements);
        var touched = targets.stream().filter(r -> hints.getOrDefault(slug, Set.of()).contains(r.id())).toList();
        return touched.isEmpty() || touched.stream().anyMatch(r -> r.quantity() == null
                || count(r.id(), r.quantity(), selected, original, hints) < r.quantity().max());
    }
}
