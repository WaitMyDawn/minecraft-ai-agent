package yagen.waitmydawn.maa.model.agent;

import yagen.waitmydawn.maa.model.CategoryRegistry;

import java.util.HashSet;
import java.util.List;

/** Architect 的本轮意图。mustHave 是结果约束，add/remove 是增量操作，两者不可混用。 */
public record BuildIntent(int schemaVersion, Mode mode, String reply, String name,
                          Environment environment, CountTarget count,
                          List<String> mustHave, List<String> add, List<String> remove,
                          List<String> exclude, List<String> expandAddons,
                          ReplacementPolicy replacementPolicy, List<SearchIntent> searchIntents,
                          List<String> excludedCategories, long maxDownloads, ConstraintChanges constraintChanges,
                          List<BuildRequirement> requirements) {
    public BuildIntent(int schemaVersion, Mode mode, String reply, String name, Environment environment,
                       CountTarget count, List<String> mustHave, List<String> add, List<String> remove,
                       List<String> exclude, List<String> expandAddons, ReplacementPolicy replacementPolicy,
                       List<SearchIntent> searchIntents, List<String> excludedCategories, long maxDownloads,
                       ConstraintChanges constraintChanges) {
        this(schemaVersion, mode, reply, name, environment, count, mustHave, add, remove, exclude, expandAddons,
                replacementPolicy, searchIntents, excludedCategories, maxDownloads, constraintChanges, List.of());
    }
    /** 兼容不携带解除操作的旧计划调用方。 */
    public BuildIntent(int schemaVersion, Mode mode, String reply, String name, Environment environment,
                       CountTarget count, List<String> mustHave, List<String> add, List<String> remove,
                       List<String> exclude, List<String> expandAddons, ReplacementPolicy replacementPolicy,
                       List<SearchIntent> searchIntents, List<String> excludedCategories, long maxDownloads) {
        this(schemaVersion, mode, reply, name, environment, count, mustHave, add, remove, exclude, expandAddons,
                replacementPolicy, searchIntents, excludedCategories, maxDownloads, ConstraintChanges.KEEP);
    }
    public enum Mode { NEW, EDIT, EMPTY, CLARIFY, REJECT }
    public enum Loader { NEOFORGE, FORGE, FABRIC }
    public enum CountScope { TOTAL_FILES, ROOT_MODS, ADDED_ROOTS }
    public enum Precision { APPROXIMATE, EXACT }
    public enum ReplacementPolicy { NONE, COMPATIBLE_PORTS, FUNCTIONAL_ALTERNATIVES }

    /** changeRequested 表达切换意图；版本是否存在及是否允许切换由 Java 结合当前包校验。 */
    public record Environment(String mcVersion, Loader loader, boolean changeRequested) {
        public Environment {
            mcVersion = ContractRules.text(mcVersion, "environment.mcVersion", 1, 32);
            ContractRules.require(mcVersion.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,31}"), "environment.mcVersion");
            ContractRules.required(loader, "environment.loader");
        }
    }

    /** TOTAL_FILES 包含前置；ROOT_MODS 是用户选择的根模组；ADDED_ROOTS 仅计本轮新增根模组。 */
    public record CountTarget(int value, CountScope scope, Precision precision, int min, int max) {
        public CountTarget(int value, CountScope scope, Precision precision) {
            this(value, scope, precision, value, value);
        }
        public CountTarget {
            ContractRules.require(value >= 0 && value <= ContractRules.MAX_MODS, "count.value");
            ContractRules.require(min >= 0 && min <= value && value <= max && max <= ContractRules.MAX_MODS, "count.range");
            ContractRules.required(scope, "count.scope");
            ContractRules.required(precision, "count.precision");
        }
        public boolean range() { return min != max; }
        public boolean accepts(int actual) {
            return range() ? actual >= min && actual <= max : precision == Precision.EXACT ? actual == value
                    : actual >= value * 0.8 && actual <= value * 1.5;
        }
        public String targetLabel() { return range() ? min + "～" + max : Integer.toString(value); }
    }

    public record SearchIntent(String category, int weight, List<String> keywords) {
        public SearchIntent {
            ContractRules.required(category, "searchIntents.category");
            ContractRules.require(CategoryRegistry.CATEGORY_SET.contains(category), "searchIntents.category");
            ContractRules.require(weight >= 1 && weight <= 100, "searchIntents.weight");
            keywords = ContractRules.list(keywords, "searchIntents.keywords", ContractRules.MAX_KEYWORDS);
            ContractRules.require(!keywords.isEmpty() && new HashSet<>(keywords).size() == keywords.size(), "searchIntents.keywords");
            keywords.forEach(keyword -> ContractRules.text(keyword, "searchIntents.keyword", 1, 80));
        }
    }

    public BuildIntent {
        requirements = BuildRequirement.validate(requirements);
        ContractRules.required(constraintChanges, "constraintChanges");
        ContractRules.buildVersion(schemaVersion);
        ContractRules.required(mode, "mode");
        reply = ContractRules.text(reply, "reply", 0, ContractRules.MAX_REPLY);
        name = ContractRules.text(name, "name", 0, 60);
        mustHave = ContractRules.projects(mustHave, "mustHave");
        add = ContractRules.projects(add, "add");
        remove = ContractRules.projects(remove, "remove");
        exclude = ContractRules.projects(exclude, "exclude");
        expandAddons = ContractRules.projects(expandAddons, "expandAddons");
        ContractRules.required(replacementPolicy, "replacementPolicy");
        searchIntents = ContractRules.list(searchIntents, "searchIntents", ContractRules.MAX_SEARCH_INTENTS);
        ContractRules.require(searchIntents.stream().map(SearchIntent::category).distinct().count() == searchIntents.size(), "searchIntents.duplicates");
        excludedCategories = ContractRules.list(excludedCategories, "excludedCategories", ContractRules.MAX_SEARCH_INTENTS);
        ContractRules.require(new HashSet<>(excludedCategories).size() == excludedCategories.size()
                && excludedCategories.stream().allMatch(CategoryRegistry.CATEGORY_SET::contains), "excludedCategories");
        var excludedCategorySet = java.util.Set.copyOf(excludedCategories);
        ContractRules.require(searchIntents.stream().noneMatch(intent -> excludedCategorySet.contains(intent.category())), "searchIntents/excludedCategories");
        ContractRules.require(requirements.stream().flatMap(r -> r.searches().stream())
                .noneMatch(s -> excludedCategorySet.contains(s.category())), "requirements.searches/excludedCategories");
        ContractRules.require(requirements.stream().flatMap(r -> r.criteria().stream()).flatMap(c -> c.searches().stream())
                .noneMatch(s -> excludedCategorySet.contains(s.category())), "criteria.searches/excludedCategories");
        ContractRules.require(maxDownloads >= 0, "maxDownloads");
        ContractRules.disjoint(mustHave, remove, "mustHave/remove");
        ContractRules.disjoint(mustHave, exclude, "mustHave/exclude");
        ContractRules.disjoint(add, remove, "add/remove");
        ContractRules.disjoint(add, exclude, "add/exclude");
        ContractRules.disjoint(expandAddons, remove, "expandAddons/remove");
        ContractRules.disjoint(expandAddons, exclude, "expandAddons/exclude");
        ContractRules.disjoint(constraintChanges.allowSlugs(), exclude, "allowSlugs/exclude");
        ContractRules.disjoint(constraintChanges.allowSlugs(), remove, "allowSlugs/remove");
        ContractRules.disjoint(constraintChanges.allowCategories(), excludedCategories, "allowCategories/excludedCategories");
        ContractRules.require(!constraintChanges.clearCount() || count == null, "clearCount/count");
        boolean actionable = mode == Mode.NEW || mode == Mode.EDIT || mode == Mode.EMPTY;
        if (actionable) ContractRules.required(environment, "environment");
        if (mode == Mode.NEW || mode == Mode.EMPTY) ContractRules.required(count, "count");
        if (mode == Mode.NEW) {
            ContractRules.require(count.value() > 0 && count.scope() != CountScope.ADDED_ROOTS, "count");
            ContractRules.require(remove.isEmpty(), "NEW.remove");
        }
        if (mode == Mode.EMPTY) {
            ContractRules.require(count.value() == 0 && count.max() == 0 && count.scope() != CountScope.ADDED_ROOTS
                    && mustHave.isEmpty() && add.isEmpty() && remove.isEmpty()
                    && expandAddons.isEmpty() && searchIntents.isEmpty(), "EMPTY");
        } else if (actionable && count != null) ContractRules.require(count.value() > 0, "count.value");
        if (!actionable) {
            ContractRules.require(environment == null && count == null && name.isEmpty()
                    && mustHave.isEmpty() && add.isEmpty() && remove.isEmpty() && exclude.isEmpty()
                    && expandAddons.isEmpty() && searchIntents.isEmpty() && excludedCategories.isEmpty()
                    && replacementPolicy == ReplacementPolicy.NONE && constraintChanges.equals(ConstraintChanges.KEEP)
                    && !reply.isEmpty(), "nonActionable");
        }
    }
}
