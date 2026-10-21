package yagen.waitmydawn.maa.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;

/** 选择续建的覆盖输入快照，保存完整证据输入而非截断后的展示行，不保留外部客户端。 */
public record SelectionCoverage(List<BuildRequirement> requirements, List<RequirementReview> reviews,
                                Map<String, JsonNode> versions, Map<String, String> identities,
                                Map<String, PackAddonPolicy.Project> metadata, String repairStatus, List<String> repaired,
                                Map<String, RequirementCoverage.EvidenceCheck> checks, Map<String, String> facts,
                                RequirementCoverage.CountContext counts) {
    public SelectionCoverage {
        requirements = List.copyOf(requirements); reviews = List.copyOf(reviews); identities = Map.copyOf(identities);
        metadata = Map.copyOf(metadata); repaired = List.copyOf(repaired); checks = Map.copyOf(checks); facts = Map.copyOf(facts);
        var minimal = new HashMap<String, JsonNode>(); var json = JsonMapper.builder().build();
        versions.forEach((slug, version) -> {
            var copy = json.createObjectNode().put("id", version.path("id").asText()).put("project_id", version.path("project_id").asText());
            copy.set("dependencies", version.path("dependencies").deepCopy()); minimal.put(slug, copy);
        });
        versions = Map.copyOf(minimal);
    }
    public RequirementCoverage.Report assess(AssemblySnapshot selected) {
        var graph = new DependencyGraph(); graph.allSlugs.addAll(selected.versions().keySet());
        selected.versions().forEach((slug, id) -> {
            var version = versions.get(slug);
            if (version != null && version.path("id").asText().equals(id)) graph.selectedVersions.put(slug, version);
        });
        var context = counts == null ? null : new RequirementCoverage.CountContext(Set.copyOf(selected.roots()), counts.original(),
                counts.rootsKnown(), counts.priorRoots());
        return RequirementCoverage.assess(requirements, reviews, graph, graph.allSlugs, identities, metadata, repairStatus, repaired, checks, facts, context);
    }
}
