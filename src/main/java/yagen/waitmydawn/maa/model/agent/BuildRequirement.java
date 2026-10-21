package yagen.waitmydawn.maa.model.agent;

import java.util.List;
import java.util.Map;

/** 原始要求及规划映射；记录目标，不授予模型声明兼容或满足的权限。 */
public record BuildRequirement(String id, String source, Kind kind, List<String> projects,
                               List<Capability> capabilities, List<String> addonCores, List<BuildIntent.SearchIntent> searches,
                               SelectionPreferences preferences, List<RequirementCriterion> criteria, RequirementQuantity quantity) {
    public BuildRequirement(String id, String source, Kind kind, List<String> projects, List<Capability> capabilities,
                            List<String> addonCores, List<BuildIntent.SearchIntent> searches, SelectionPreferences preferences,
                            List<RequirementCriterion> criteria) {
        this(id, source, kind, projects, capabilities, addonCores, searches, preferences, criteria,
                criteria.isEmpty() && kind != Kind.CHANGE && (kind != Kind.GENERAL || !searches.isEmpty())
                        ? RequirementQuantity.fromSource(source) : null);
    }
    public BuildRequirement(String id, String source, Kind kind, List<String> projects,
                            List<Capability> capabilities, List<String> addonCores, List<BuildIntent.SearchIntent> searches,
                            SelectionPreferences preferences) {
        this(id, source, kind, projects, capabilities, addonCores, searches, preferences, List.of());
    }
    public BuildRequirement(String id, String source, Kind kind, List<String> projects,
                            List<Capability> capabilities, List<String> addonCores, List<BuildIntent.SearchIntent> searches) {
        this(id, source, kind, projects, capabilities, addonCores, searches, SelectionPreferences.DEFAULT);
    }
    public BuildRequirement(String id, String source, Kind kind, List<String> projects,
                            List<Capability> capabilities, List<String> addonCores) {
        this(id, source, kind, projects, capabilities, addonCores, List.of());
    }
    public BuildRequirement(String id, String source, Kind kind, List<String> projects, List<Capability> capabilities) {
        this(id, source, kind, projects, capabilities,
                kind == Kind.ADDONS || kind == Kind.PACK_INTEGRATIONS ? projects : List.of());
    }
    public enum Kind { PROJECTS, CAPABILITY, ADDONS, PACK_INTEGRATIONS, CHANGE, GENERAL }
    public enum Capability { TERRAIN, BIOMES, STRUCTURES, CREATURE_ECOLOGY, NETHER, END,
        PERFORMANCE, MODERN_UI, FIXES, UTILITY }
    public BuildRequirement {
        id = ContractRules.text(id, "requirements.id", 1, 64);
        ContractRules.require(id.matches("r-[a-f0-9]{16}"), "requirements.id");
        source = ContractRules.text(source, "requirements.source", 1, 4000);
        ContractRules.required(kind, "requirements.kind");
        ContractRules.required(preferences, "requirements.preferences");
        criteria = ContractRules.list(criteria, "requirements.criteria", 16);
        ContractRules.require(criteria.stream().map(RequirementCriterion::source).distinct().count() == criteria.size(), "requirements.criteria.duplicates");
        projects = ContractRules.projects(projects, "requirements.projects");
        addonCores = ContractRules.projects(addonCores, "requirements.addonCores");
        capabilities = ContractRules.list(capabilities, "requirements.capabilities", Capability.values().length);
        searches = ContractRules.list(searches, "requirements.searches", 3);
        ContractRules.require(searches.stream().map(BuildIntent.SearchIntent::category).distinct().count() == searches.size(), "requirements.searches");
        ContractRules.require(capabilities.stream().distinct().count() == capabilities.size(), "requirements.capabilities");
    }
    public BuildRequirement replace(Map<String, List<String>> replacements) {
        return new BuildRequirement(id, source, kind, projects.stream()
                .flatMap(p -> replacements.getOrDefault(p, List.of(p)).stream()).distinct().toList(), capabilities,
                addonCores.stream().flatMap(p -> replacements.getOrDefault(p, List.of(p)).stream()).distinct().toList(), searches, preferences,
                criteria.stream().filter(c -> c.kind() != Kind.PROJECTS || c.projects().isEmpty()
                                || c.projects().stream().anyMatch(p -> !replacements.getOrDefault(p, List.of(p)).isEmpty()))
                        .filter(c -> c.addonCores().isEmpty() || c.addonCores().stream().anyMatch(p -> !replacements.getOrDefault(p, List.of(p)).isEmpty()))
                        .map(c -> c.replace(replacements)).toList(), quantity);
    }
    public static List<BuildRequirement> validate(List<BuildRequirement> entries) {
        var result = ContractRules.list(entries, "requirements", 64);
        ContractRules.require(result.stream().map(BuildRequirement::id).distinct().count() == result.size(), "requirements.duplicates");
        ContractRules.require(result.stream().mapToInt(r -> r.criteria().size()).sum() <= 128, "requirements.criteria.total");
        return result;
    }
}
