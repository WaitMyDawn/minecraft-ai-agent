package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.service.GenerationChoices;
import java.util.*;

/** 最终选版上的共同准入边界；来源只用于解释，不能豁免否决或关系约束。 */
public final class CandidateAdmission {
    private CandidateAdmission() {}
    public record Row(String slug, Set<String> roles, String status, List<String> reasons,
                      PackAddonPolicy.Assessment relation) {}
    public record Report(List<Row> projects) {
        public List<Row> blocked() { return projects.stream().filter(r -> r.status().equals("BLOCKED")).toList(); }
    }
    public static Report afterSelection(Report before, Set<String> selected) {
        return new Report(before.projects().stream().map(row -> {
            if (!selected.contains(row.slug())) return new Row(row.slug(),row.roles(),"NOT_RETAINED",List.of("USER_SELECTION_OR_ORPHAN_CLEANUP"),
                    new PackAddonPolicy.Assessment(List.of(),false,false));
            var evidence = row.relation().evidence().stream().filter(e -> selected.contains(e.core()) && selected.containsAll(e.partners())).toList();
            boolean changed = evidence.size() != row.relation().evidence().size();
            var reasons = new ArrayList<>(row.reasons()); if (changed) reasons.add("RELATION_CHANGED_AFTER_SELECTION");
            return new Row(row.slug(),row.roles(),changed ? "UNVERIFIED" : row.status(),List.copyOf(reasons),
                    new PackAddonPolicy.Assessment(evidence,!evidence.isEmpty(),row.relation().conflict()));
        }).toList());
    }
    public static Report assess(DependencyGraph graph, Set<String> roots, Set<String> selected,
            Map<String, PackAddonPolicy.Project> metadata, Map<String, Map<String, String>> knownAddonCores,
            Map<String, AddonApplicability.Check> applicability, Set<String> vetoes,
            Map<String, List<String>> generationChoices, Map<String, String> generationFacts, Set<String> preserved) {
        return assess(graph,roots,selected,metadata,knownAddonCores,applicability,vetoes,generationChoices,generationFacts,preserved,roots);
    }
    public static Report assess(DependencyGraph graph, Set<String> roots, Set<String> selected,
            Map<String, PackAddonPolicy.Project> metadata, Map<String, Map<String, String>> knownAddonCores,
            Map<String, AddonApplicability.Check> applicability, Set<String> vetoes,
            Map<String, List<String>> generationChoices, Map<String, String> generationFacts, Set<String> preserved, Set<String> permittedContent) {
        return assess(graph,roots,selected,metadata,knownAddonCores,applicability,vetoes,generationChoices,generationFacts,preserved,permittedContent,Map.of());
    }
    public static Report assess(DependencyGraph graph, Set<String> roots, Set<String> selected,
            Map<String, PackAddonPolicy.Project> metadata, Map<String, Map<String, String>> knownAddonCores,
            Map<String, AddonApplicability.Check> applicability, Set<String> vetoes,
            Map<String, List<String>> generationChoices, Map<String, String> generationFacts, Set<String> preserved, Set<String> permittedContent,
            Map<String, List<String>> offeredGeneration) {
        var aliases = new HashMap<>(graph.projectAliases);
        var references = new HashMap<String, String>();
        graph.selectedVersions.forEach((slug, v) -> {
            if (!v.path("project_id").asText().isBlank()) aliases.put(v.path("project_id").asText(), slug);
            if (!v.path("id").asText().isBlank()) references.put(v.path("id").asText(), v.path("project_id").asText());
        });
        metadata.values().forEach(p -> { if (!p.id().isBlank()) aliases.put(p.id(), p.slug()); });
        var blocked = new HashSet<String>(); vetoes.forEach(s -> blocked.add(aliases.getOrDefault(s, s)));
        var retained = metadata.values().stream().filter(p -> selected.contains(p.slug()))
                .map(p -> p.withVersion(graph.selectedVersions.containsKey(p.slug()) ? graph.selectedVersions.get(p.slug()).path("id").asText() : ""))
                .distinct().toList();
        var rows = new ArrayList<Row>();
        for (String slug : new TreeSet<>(selected)) {
            var roles = new TreeSet<String>(); roles.add(roots.contains(slug) ? "ROOT" : "DEPENDENCY");
            var reasons = new TreeSet<String>();
            if (blocked.contains(slug)) reasons.add("EXPLICITLY_BLOCKED");
            if (!preserved.contains(slug) && !GenerationChoices.allows(generationChoices, offeredGeneration, slug, generationFacts.getOrDefault(slug, "")))
                reasons.add("GENERATION_NOT_SELECTED");
            var project = metadata.get(slug);
            if (project == null) project = retained.stream().filter(p -> p.slug().equals(slug)).findFirst().orElse(null);
            boolean addon = knownAddonCores.containsKey(slug) || project != null && AddonApplicability.addon(project.description());
            if (project != null && project.infrastructure()) addon = false;
            var relation = new PackAddonPolicy.Assessment(List.of(), false, false);
            if (addon) {
                roles.add("ADDON");
                var cores = new LinkedHashMap<String, String>();
                knownAddonCores.getOrDefault(slug, Map.of()).forEach((core, id) -> cores.put(aliases.getOrDefault(core,core),id));
                // 泛化召回没有专用核心字段；关系仍须由实际选版或明确双边描述证明。
                var relatedIds = new HashSet<String>();
                var version = graph.selectedVersions.get(slug);
                if (version != null) for (var dependency : version.path("dependencies")) {
                    if (!Set.of("required", "optional").contains(dependency.path("dependency_type").asText())) continue;
                    String id = dependency.path("project_id").asText();
                    if (id.isBlank()) id = references.getOrDefault(dependency.path("version_id").asText(), "");
                    if (!id.isBlank()) relatedIds.add(id);
                }
                String description = project == null ? "" : project.description();
                retained.stream().filter(PackAddonPolicy.Project::content).filter(p -> !p.slug().equals(slug))
                        .filter(p -> relatedIds.contains(p.id()) || AddonApplicability.mentions(description,p))
                        .sorted(Comparator.comparing(PackAddonPolicy.Project::slug)).forEach(p -> cores.putIfAbsent(p.slug(), p.id()));
                relation = PackAddonPolicy.assess(slug, graph.selectedVersions.get(slug), references, cores, retained, true);
                if (relation.integration()) roles.add("IN_PACK_INTEGRATION");
                var check = applicability.get(slug);
                if (!preserved.contains(slug) && check != null && check.blocks()) reasons.add(check.status());
                if (!preserved.contains(slug) && project != null && graph.selectedVersions.containsKey(slug)) {
                    var current = AddonApplicability.assess(project, graph.selectedVersions.get(slug), references,
                            metadata.values(), selected, permittedContent, true);
                    if (current.blocks()) reasons.add(current.status());
                }
                if (relation.conflict()) reasons.add("CONFLICT_DECLARATION");
            }
            boolean unknown = project == null || project.id().isBlank() || !graph.selectedVersions.containsKey(slug)
                    || graph.selectedVersions.get(slug).path("id").asText().isBlank()
                    || addon && (relation.evidence().isEmpty() || !applicability.containsKey(slug)
                        || Set.of("PROJECT_UNVERIFIED", "VERSION_UNVERIFIED", "DEPENDENCIES_UNVERIFIED").contains(applicability.get(slug).status()));
            rows.add(new Row(slug, Set.copyOf(roles), !reasons.isEmpty() ? "BLOCKED" : unknown ? "UNVERIFIED" : "ALLOWED",
                    List.copyOf(reasons), relation));
        }
        return new Report(List.copyOf(rows));
    }
}
