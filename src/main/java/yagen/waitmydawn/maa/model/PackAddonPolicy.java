package yagen.waitmydawn.maa.model;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** 联动必须同时关联实际保留的核心与内容项目；下载量只决定加成，不授予关系证据。 */
public final class PackAddonPolicy {
    private PackAddonPolicy() {}
    private static final java.util.regex.Pattern INFRASTRUCTURE = java.util.regex.Pattern.compile("(?i)\\b(?:api|library|lib)\\b");
    public static boolean infrastructure(String slug, String title, Set<String> categories) {
        return categories.contains("library") || INFRASTRUCTURE.matcher(slug + " " + title).find();
    }
    public record Project(String id, String slug, Long downloads, Set<String> categories, String title, String versionId) {
        public Project(String id, String slug, Long downloads, Set<String> categories, String title) { this(id, slug, downloads, categories, title, ""); }
        public Project withVersion(String versionId) { return new Project(id, slug, downloads, categories, title, versionId); }
        public static Project from(JsonNode info) {
            var categories = new HashSet<String>();
            info.path("categories").forEach(c -> categories.add(c.asText()));
            Long downloads = info.path("downloads").isIntegralNumber() && info.path("downloads").asLong() >= 0
                    ? info.path("downloads").asLong() : null;
            return new Project(info.path("id").asText(), info.path("slug").asText(), downloads, Set.copyOf(categories), info.path("title").asText());
        }
        public boolean content() {
            return !id.isBlank() && !slug.isBlank() && !infrastructure(slug, title, categories)
                    && !categories.isEmpty();
        }
    }
    public record Evidence(String core, List<String> partners, Long coreDownloads, Long maxPartnerDownloads,
                           double coefficient, String downloadsStatus) {}
    public record Assessment(List<Evidence> evidence, boolean corePresent, boolean conflict) {
        public double coefficient() { return evidence.stream().mapToDouble(Evidence::coefficient).max().orElse(1); }
        public boolean integration() { return evidence.stream().anyMatch(e -> !e.partners().isEmpty()); }
    }
    private static boolean positiveRelation(Project project, JsonNode version, Map<String, String> versionProjects) {
        for (var dep : version.path("dependencies")) {
            if (!Set.of("required", "optional").contains(dep.path("dependency_type").asText())) continue;
            String id = dep.path("project_id").asText("");
            if (id.isBlank()) id = versionProjects.get(dep.path("version_id").asText());
            if (project.id().equals(id) && (dep.path("version_id").asText("").isBlank() || project.versionId().isBlank()
                    || dep.path("version_id").asText().equals(project.versionId()))) return true;
        }
        return false;
    }
    public static double coefficient(Long core, List<Long> partners) {
        if (core == null || core < 0 || partners.isEmpty() || partners.stream().anyMatch(d -> d == null || d < 0)) return 1;
        long max = partners.stream().mapToLong(Long::longValue).max().orElse(0);
        return core == 0 ? max > 0 ? 2 : 1 : 1 + Math.min(1, (double) max / core);
    }
    public static Assessment assess(String candidate, JsonNode version, Map<String, String> versionProjects,
                                    Map<String, String> cores, Collection<Project> retained) {
        if (version == null) return new Assessment(List.of(), false, false);
        var byId = new HashMap<String, Project>();
        retained.stream().filter(p -> !p.id().isBlank()).forEach(p -> byId.put(p.id(), p));
        boolean conflict = false;
        for (var dep : version.path("dependencies")) if (dep.path("dependency_type").asText().equals("incompatible")) {
            String id = dep.path("project_id").asText("");
            if (id.isBlank()) id = versionProjects.get(dep.path("version_id").asText());
            String exact = dep.path("version_id").asText("");
            String targetId = id;
            if (!exact.isBlank()) {
                if (byId.values().stream().anyMatch(p -> exact.equals(p.versionId()))
                        || (byId.values().stream().anyMatch(p -> p.versionId().isBlank()) && (targetId == null || byId.containsKey(targetId)))) conflict = true;
            } else if (id == null || id.isBlank() || byId.containsKey(id)) conflict = true;
        }
        var result = new ArrayList<Evidence>(); boolean corePresent = false;
        for (var core : cores.entrySet()) {
            var a = byId.get(core.getValue());
            if (a == null || !positiveRelation(a, version, versionProjects)) continue;
            corePresent = true;
            var partners = byId.values().stream().filter(Project::content)
                    .filter(b -> !b.id().equals(a.id()) && !b.slug().equals(candidate))
                    .filter(b -> positiveRelation(b, version, versionProjects))
                    .sorted(Comparator.comparing(Project::slug)).toList();
            String status = a.downloads() == null || partners.stream().anyMatch(b -> b.downloads() == null)
                    ? "UNKNOWN_DOWNLOADS" : a.downloads() == 0 ? "ZERO_CORE_DOWNLOADS" : "KNOWN_DOWNLOADS";
            Long max = partners.stream().anyMatch(b -> b.downloads() == null) ? null
                    : partners.stream().mapToLong(Project::downloads).max().orElse(0);
            result.add(new Evidence(core.getKey(), partners.stream().map(Project::slug).toList(),
                    a.downloads(), max,
                    coefficient(a.downloads(), partners.stream().map(Project::downloads).toList()), status));
        }
        return new Assessment(List.copyOf(result), corePresent, conflict);
    }
}
