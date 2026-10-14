package yagen.waitmydawn.maa.model.agent;

import java.util.List;

/** 明确解除旧约束的增量；默认值不具备覆盖历史约束的权限。 */
public record ConstraintChanges(List<String> allowSlugs, List<String> allowCategories,
                                boolean clearCount, boolean replacementPolicyChanged,
                                boolean maxDownloadsChanged, List<String> releaseRequirements,
                                List<BuildRequirement.Capability> releaseCapabilities) {
    public ConstraintChanges(List<String> allowSlugs, List<String> allowCategories, boolean clearCount,
                             boolean replacementPolicyChanged, boolean maxDownloadsChanged, List<String> releaseRequirements) {
        this(allowSlugs, allowCategories, clearCount, replacementPolicyChanged, maxDownloadsChanged, releaseRequirements, List.of());
    }
    public ConstraintChanges(List<String> allowSlugs, List<String> allowCategories, boolean clearCount,
                             boolean replacementPolicyChanged, boolean maxDownloadsChanged) {
        this(allowSlugs, allowCategories, clearCount, replacementPolicyChanged, maxDownloadsChanged, List.of());
    }
    public static final ConstraintChanges KEEP = new ConstraintChanges(List.of(), List.of(), false, false, false);
    public ConstraintChanges {
        releaseCapabilities = ContractRules.list(releaseCapabilities, "constraintChanges.releaseCapabilities", BuildRequirement.Capability.values().length);
        ContractRules.require(releaseCapabilities.stream().distinct().count() == releaseCapabilities.size(), "constraintChanges.releaseCapabilities");
        releaseRequirements = ContractRules.list(releaseRequirements, "constraintChanges.releaseRequirements", 64);
        ContractRules.require(releaseRequirements.stream().distinct().count() == releaseRequirements.size()
                && releaseRequirements.stream().allMatch(id -> id.matches("r-[a-f0-9]{16}")), "constraintChanges.releaseRequirements");
        allowSlugs = ContractRules.projects(allowSlugs, "constraintChanges.allowSlugs");
        allowCategories = ContractRules.list(allowCategories, "constraintChanges.allowCategories", ContractRules.MAX_SEARCH_INTENTS);
        ContractRules.require(allowCategories.stream().distinct().count() == allowCategories.size()
                && allowCategories.stream().allMatch(yagen.waitmydawn.maa.model.CategoryRegistry.CATEGORY_SET::contains),
                "constraintChanges.allowCategories");
    }
}
