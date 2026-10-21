package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;

/** 局部数量全部有界时，为总量中无法由这些目标填充的部分保留互补候选。 */
public final class ComplementaryRetrieval {
    private ComplementaryRetrieval() {}
    public record Plan(int slots, List<BuildIntent.SearchIntent> searches) {}
    public static Plan plan(BuildIntent intent, int fixedRoots, boolean namedOnly) {
        if (intent == null || namedOnly || intent.mode() != BuildIntent.Mode.NEW || intent.count() == null)
            return new Plan(0, List.of());
        var targets = RequirementQuantities.targets(intent.requirements());
        var bounded = targets.stream().filter(r -> r.quantity() != null).toList();
        boolean unbounded = targets.stream().anyMatch(r -> r.quantity() == null
                && (r.kind() == BuildRequirement.Kind.ADDONS || r.kind() == BuildRequirement.Kind.PACK_INTEGRATIONS
                || r.kind() == BuildRequirement.Kind.CAPABILITY || !r.searches().isEmpty()));
        int gap = FinalQuantityCoordinator.minimum(intent.count()) - fixedRoots
                - bounded.stream().mapToInt(r -> r.quantity().max()).sum();
        if (bounded.isEmpty() || unbounded || gap <= 0) return new Plan(0, List.of());
        var searches = List.of(new BuildIntent.SearchIntent("utility", 40, List.of("quality of life")),
                new BuildIntent.SearchIntent("storage", 30, List.of("storage")),
                new BuildIntent.SearchIntent("game-mechanics", 30, List.of("gameplay"))).stream()
                .filter(s -> !intent.excludedCategories().contains(s.category())).toList();
        return searches.isEmpty() ? new Plan(0, List.of()) : new Plan(Math.min(48, gap * 2), searches);
    }
}
