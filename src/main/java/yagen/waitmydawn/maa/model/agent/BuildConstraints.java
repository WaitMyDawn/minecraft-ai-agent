package yagen.waitmydawn.maa.model.agent;

import java.util.*;

/** 随包版本保存结果约束及逐项检索计划；不保存单轮主题配额、附属扩展或新增额度。 */
public record BuildConstraints(int schemaVersion, List<String> mustHave, List<String> exclude,
                               List<String> excludedCategories, BuildIntent.CountTarget count,
                               BuildIntent.ReplacementPolicy replacementPolicy, long maxDownloads,
                               List<BuildRequirement> requirements) {
    public BuildConstraints(int schemaVersion, List<String> mustHave, List<String> exclude,
                            List<String> excludedCategories, BuildIntent.CountTarget count,
                            BuildIntent.ReplacementPolicy replacementPolicy, long maxDownloads) {
        this(schemaVersion, mustHave, exclude, excludedCategories, count, replacementPolicy, maxDownloads, List.of());
    }
    public static BuildConstraints empty() {
        return new BuildConstraints(1, List.of(), List.of(), List.of(), null, BuildIntent.ReplacementPolicy.NONE, 2_100_000_000L);
    }
    public BuildConstraints {
        requirements = BuildRequirement.validate(requirements);
        ContractRules.buildVersion(schemaVersion);
        mustHave = ContractRules.projects(mustHave, "constraints.mustHave").stream().sorted().toList();
        exclude = ContractRules.projects(exclude, "constraints.exclude").stream().sorted().toList();
        excludedCategories = ContractRules.list(excludedCategories, "constraints.excludedCategories", ContractRules.MAX_SEARCH_INTENTS).stream().sorted().toList();
        ContractRules.require(excludedCategories.stream().distinct().count() == excludedCategories.size()
                && excludedCategories.stream().allMatch(yagen.waitmydawn.maa.model.CategoryRegistry.CATEGORY_SET::contains), "constraints.excludedCategories");
        ContractRules.disjoint(mustHave, exclude, "constraints.mustHave/exclude");
        ContractRules.required(replacementPolicy, "constraints.replacementPolicy");
        ContractRules.require(maxDownloads >= 0, "constraints.maxDownloads");
        ContractRules.require(count == null || count.value() > 0 && count.scope() != BuildIntent.CountScope.ADDED_ROOTS, "constraints.count");
    }

    public record Applied(BuildIntent intent, BuildConstraints constraints) {}

    public Applied apply(BuildIntent planned, Set<String> manualExclusions) {
        if (planned.mode() == BuildIntent.Mode.CLARIFY || planned.mode() == BuildIntent.Mode.REJECT)
            return new Applied(planned, this);
        boolean editing = planned.mode() == BuildIntent.Mode.EDIT;
        BuildConstraints previous = editing ? this : empty();
        var changes = planned.constraintChanges();
        var knownRequirements = previous.requirements().stream().map(BuildRequirement::id).collect(java.util.stream.Collectors.toSet());
        previous.requirements().forEach(r -> r.criteria().forEach(c -> knownRequirements.add(c.key(r.id()))));
        ContractRules.require(knownRequirements.containsAll(changes.releaseRequirements()), "constraintChanges.releaseRequirements");
        var knownCapabilities = previous.requirements().stream().flatMap(r -> r.capabilities().stream()).collect(java.util.stream.Collectors.toSet());
        ContractRules.require(knownCapabilities.containsAll(changes.releaseCapabilities()), "constraintChanges.releaseCapabilities");
        var blocked = new LinkedHashSet<>(previous.exclude());
        if (editing) blocked.addAll(manualExclusions);
        blocked.removeAll(changes.allowSlugs());
        blocked.addAll(planned.exclude());
        blocked.addAll(planned.remove());
        var required = new LinkedHashSet<>(previous.mustHave());
        var retainedNamed = previous.requirements().stream().filter(r -> !changes.releaseRequirements().contains(r.id()))
                .filter(r -> r.kind() == BuildRequirement.Kind.PROJECTS).flatMap(r -> r.projects().stream()).collect(java.util.stream.Collectors.toSet());
        previous.requirements().stream().filter(r -> changes.releaseRequirements().contains(r.id()))
                .filter(r -> r.kind() == BuildRequirement.Kind.PROJECTS).flatMap(r -> r.projects().stream())
                .filter(p -> !retainedNamed.contains(p)).forEach(required::remove);
        required.removeAll(blocked);
        required.addAll(planned.mustHave());
        ContractRules.require(Collections.disjoint(required, blocked) && Collections.disjoint(planned.add(), blocked), "constraints.blockedAddition");
        var categories = new LinkedHashSet<>(previous.excludedCategories());
        categories.removeAll(changes.allowCategories());
        categories.addAll(planned.excludedCategories());
        ContractRules.require(planned.searchIntents().stream().noneMatch(i -> categories.contains(i.category())), "constraints.searchCategory");
        ContractRules.require(planned.requirements().stream().flatMap(r -> r.searches().stream())
                .noneMatch(i -> categories.contains(i.category())), "constraints.searchCategory");
        var policy = !editing || changes.replacementPolicyChanged() ? planned.replacementPolicy() : previous.replacementPolicy();
        long downloads = !editing || changes.maxDownloadsChanged() ? planned.maxDownloads() : previous.maxDownloads();
        var durableCount = changes.clearCount() ? null : previous.count();
        if (planned.count() != null && planned.count().value() > 0
                && planned.count().scope() != BuildIntent.CountScope.ADDED_ROOTS) durableCount = planned.count();
        var effectiveCount = planned.count() == null ? durableCount : planned.count();
        var ledger = new LinkedHashMap<String, BuildRequirement>();
        for (var entry : previous.requirements()) {
            if (changes.releaseRequirements().contains(entry.id())) continue;
            var releasedProjects = entry.criteria().stream().filter(c -> changes.releaseRequirements().contains(c.key(entry.id())))
                    .flatMap(c -> c.projects().stream()).collect(java.util.stream.Collectors.toSet());
            var stillRequiredProjects = entry.criteria().stream().filter(c -> !changes.releaseRequirements().contains(c.key(entry.id())))
                    .flatMap(c -> c.projects().stream()).collect(java.util.stream.Collectors.toSet());
            var remaining = entry.projects().stream().filter(p -> !blocked.contains(p))
                    .filter(p -> !releasedProjects.contains(p) || stillRequiredProjects.contains(p)).toList();
            if (entry.kind() == BuildRequirement.Kind.PROJECTS && !entry.projects().isEmpty() && remaining.isEmpty()) continue;
            if (entry.criteria().isEmpty() && !entry.addonCores().isEmpty() && entry.addonCores().stream().allMatch(blocked::contains)) continue;
            var capabilities = entry.capabilities().stream().filter(c -> !changes.releaseCapabilities().contains(c)).toList();
            if (!entry.capabilities().isEmpty() && capabilities.isEmpty() && remaining.isEmpty() && entry.addonCores().isEmpty()) continue;
            var criteria = entry.criteria().stream().filter(c -> !changes.releaseRequirements().contains(c.key(entry.id())))
                            .filter(c -> Collections.disjoint(yagen.waitmydawn.maa.service.RequirementPlanning.capabilities(c.source()), changes.releaseCapabilities()))
                            .filter(c -> c.kind() != BuildRequirement.Kind.PROJECTS || c.projects().isEmpty() || !blocked.containsAll(c.projects()))
                            .filter(c -> c.addonCores().isEmpty() || !blocked.containsAll(c.addonCores()))
                            .map(c -> new RequirementCriterion(c.source(), c.kind(), c.projects().stream().filter(p -> !blocked.contains(p)).toList(),
                                    c.addonCores().stream().filter(p -> !blocked.contains(p)).toList(),
                                    c.searches().stream().filter(s -> !categories.contains(s.category())).toList(), c.preferences())).toList();
            if (!entry.criteria().isEmpty() && criteria.isEmpty() && remaining.isEmpty()) continue;
            var activeAddonCores = entry.criteria().isEmpty() ? entry.addonCores().stream().filter(p -> !blocked.contains(p)).toList()
                    : criteria.stream().flatMap(c -> c.addonCores().stream()).distinct().toList();
            var retainedKind = !Collections.disjoint(entry.capabilities(), changes.releaseCapabilities()) && capabilities.isEmpty()
                    && criteria.isEmpty() && activeAddonCores.isEmpty() && !remaining.isEmpty() ? BuildRequirement.Kind.PROJECTS : entry.kind();
            ledger.put(entry.id(), new BuildRequirement(entry.id(), entry.source(), retainedKind, remaining, capabilities, activeAddonCores,
                    Collections.disjoint(entry.capabilities(), changes.releaseCapabilities())
                            ? entry.searches().stream().filter(s -> !categories.contains(s.category())).toList() : List.of(), entry.preferences(), criteria));
        }
        planned.requirements().forEach(entry -> ledger.put(entry.id(), entry));
        var releasedChildProjects = previous.requirements().stream().flatMap(r -> r.criteria().stream()
                        .filter(c -> changes.releaseRequirements().contains(c.key(r.id())))).flatMap(c -> c.projects().stream())
                .collect(java.util.stream.Collectors.toSet());
        var activeProjects = ledger.values().stream().flatMap(r -> r.projects().stream()).collect(java.util.stream.Collectors.toSet());
        required.removeIf(p -> releasedChildProjects.contains(p) && !activeProjects.contains(p) && !planned.mustHave().contains(p));
        var state = new BuildConstraints(Math.max(previous.schemaVersion(), planned.schemaVersion()), List.copyOf(required), List.copyOf(blocked), List.copyOf(categories), durableCount, policy, downloads,
                List.copyOf(ledger.values()));
        var effective = new BuildIntent(planned.schemaVersion(), planned.mode(), planned.reply(), planned.name(), planned.environment(), effectiveCount,
                state.mustHave(), planned.add(), planned.remove(), state.exclude(), planned.expandAddons(), policy,
                planned.searchIntents(), state.excludedCategories(), downloads, ConstraintChanges.KEEP, state.requirements());
        return new Applied(effective, state);
    }
}
