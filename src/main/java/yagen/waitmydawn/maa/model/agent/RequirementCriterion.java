package yagen.waitmydawn.maa.model.agent;

import yagen.waitmydawn.maa.service.RequirementPlanning;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 子目标沿用原文，不以固定玩法枚举限制可表达的目标。 */
public record RequirementCriterion(String source, BuildRequirement.Kind kind, List<String> projects,
                                   List<String> addonCores, List<BuildIntent.SearchIntent> searches, SelectionPreferences preferences) {
    public RequirementCriterion {
        source = ContractRules.text(source, "criteria.source", 1, 4000);
        ContractRules.required(kind, "criteria.kind");
        projects = ContractRules.projects(projects, "criteria.projects");
        addonCores = ContractRules.projects(addonCores, "criteria.addonCores");
        ContractRules.required(preferences, "criteria.preferences");
        searches = ContractRules.list(searches, "criteria.searches", 3);
        ContractRules.require(searches.stream().map(BuildIntent.SearchIntent::category).distinct().count() == searches.size(), "criteria.searches");
    }
    public RequirementCriterion(String source, BuildRequirement.Kind kind, List<String> projects,
                                List<String> addonCores, List<BuildIntent.SearchIntent> searches) {
        this(source, kind, projects, addonCores, searches, SelectionPreferences.fromSource(source));
    }
    public String key(String parentId) {
        try {
            return "r-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((parentId + "\n" + source).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public BuildRequirement target(BuildRequirement parent) {
        var capabilities = kind == BuildRequirement.Kind.PROJECTS ? List.<BuildRequirement.Capability>of()
                : RequirementPlanning.capabilities(source).stream().filter(parent.capabilities()::contains).toList();
        return new BuildRequirement(key(parent.id()), source, kind, projects, capabilities, addonCores, searches, preferences);
    }
    public RequirementCriterion replace(Map<String, List<String>> replacements) {
        return new RequirementCriterion(source, kind, projects.stream().flatMap(s -> replacements.getOrDefault(s, List.of(s)).stream()).distinct().toList(),
                addonCores.stream().flatMap(s -> replacements.getOrDefault(s, List.of(s)).stream()).distinct().toList(), searches, preferences);
    }
}
