package yagen.waitmydawn.maa.model;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** 联动必须同时关联实际保留的核心与内容项目；下载量只决定加成，不授予关系证据。 */
public final class PackAddonPolicy {
    private PackAddonPolicy() {}
    private static final java.util.regex.Pattern INFRASTRUCTURE = java.util.regex.Pattern.compile("(?i)\\b(?:api|library|lib)\\b");
    private static final java.util.regex.Pattern DEVELOPER_API = java.util.regex.Pattern.compile(
            "(?iu)^(?:(?:this (?:mod|project) is|it is)\\s+)?(?:an?\\s+)?(?:(?:util(?:ity)?|shared|common|lightweight)\\s+)?"
                    + "(?:api|library)\\s+(?:for\\s+(?:making|creating|developing|building)\\b|(?:made|designed|intended)\\s+for\\s+(?:mod\\s+)?develop(?:ers|ment)\\b)");
    public static boolean infrastructure(String slug, String title, Set<String> categories) {
        return categories.contains("library") || INFRASTRUCTURE.matcher(slug + " " + title).find();
    }
    public static boolean infrastructure(String slug, String title, Set<String> categories, String description) {
        // 只有项目自身明确声明的开发工具用途可补足缺失的库标签，依赖或兼容说明不授予该身份。
        return infrastructure(slug, title, categories) || description != null && DEVELOPER_API.matcher(description.strip()).find();
    }
    public record Project(String id, String slug, Long downloads, Set<String> categories, String title, String versionId, String description) {
        public Project(String id, String slug, Long downloads, Set<String> categories, String title) { this(id, slug, downloads, categories, title, "", ""); }
        public Project(String id, String slug, Long downloads, Set<String> categories, String title, String versionId) { this(id, slug, downloads, categories, title, versionId, ""); }
        public Project withVersion(String versionId) { return new Project(id, slug, downloads, categories, title, versionId, description); }
        public boolean infrastructure() { return PackAddonPolicy.infrastructure(slug, title, categories, description); }
        public static Project from(JsonNode info) {
            var categories = new HashSet<String>();
            info.path("categories").forEach(c -> categories.add(c.asText()));
            Long downloads = info.path("downloads").isIntegralNumber() && info.path("downloads").asLong() >= 0
                    ? info.path("downloads").asLong() : null;
            String text = info.path("description").asText();
            return new Project(info.path("id").asText(), info.path("slug").asText(), downloads, Set.copyOf(categories), info.path("title").asText(), "", text.substring(0, Math.min(600, text.length())));
        }
        public boolean content() {
            return !id.isBlank() && !slug.isBlank() && !infrastructure()
                    && !categories.isEmpty();
        }
    }
    public record Evidence(String core, List<String> partners, Long coreDownloads, Long maxPartnerDownloads,
                           double coefficient, String downloadsStatus, String relationSource, String quote) {
        public Evidence(String core, List<String> partners, Long coreDownloads, Long maxPartnerDownloads, double coefficient, String downloadsStatus) {
            this(core, partners, coreDownloads, maxPartnerDownloads, coefficient, downloadsStatus, "VERSION_RELATION", "");
        }
    }
    public record Assessment(List<Evidence> evidence, boolean corePresent, boolean conflict) {
        public double coefficient() { return evidence.stream().mapToDouble(Evidence::coefficient).max().orElse(1); }
        public boolean integration() { return evidence.stream().anyMatch(e -> !e.partners().isEmpty()); }
    }
    private static boolean positiveRelation(Project project, JsonNode version, Map<String, String> versionProjects, boolean strict) {
        if (strict && project.versionId().isBlank()) return false;
        for (var dep : version.path("dependencies")) {
            if (!Set.of("required", "optional").contains(dep.path("dependency_type").asText())) continue;
            String id = dep.path("project_id").asText("");
            if (id.isBlank()) id = versionProjects.get(dep.path("version_id").asText());
            if (project.id().equals(id) && (dep.path("version_id").asText("").isBlank() || !strict && project.versionId().isBlank()
                    || dep.path("version_id").asText().equals(project.versionId()))) return true;
        }
        return false;
    }
    private static boolean exactMismatch(Project project, JsonNode version, Map<String, String> references) {
        for (var dep : version.path("dependencies")) if (Set.of("required", "optional").contains(dep.path("dependency_type").asText())) {
            String id = dep.path("project_id").asText();
            if (id.isBlank()) id = references.getOrDefault(dep.path("version_id").asText(), "");
            String exact = dep.path("version_id").asText();
            if (project.id().equals(id) && !exact.isBlank() && !exact.equals(project.versionId())) return true;
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
        return assess(candidate, version, versionProjects, cores, retained, false);
    }
    public static Assessment assess(String candidate, JsonNode version, Map<String, String> versionProjects,
                                    Map<String, String> cores, Collection<Project> retained, boolean strict) {
        return assess(candidate, version, versionProjects, cores, retained, strict, false);
    }
    public static Assessment assess(String candidate, JsonNode version, Map<String, String> versionProjects,
                                    Map<String, String> cores, Collection<Project> retained, boolean strict, boolean deferExactConflicts) {
        if (version == null || strict && version.path("id").asText().isBlank()) return new Assessment(List.of(), false, false);
        var byId = new HashMap<String, Project>();
        retained.stream().filter(p -> !p.id().isBlank()).forEach(p -> byId.put(p.id(), p));
        var own = retained.stream().filter(p -> p.slug().equals(candidate)).findFirst().orElse(null);
        if (own != null ? own.infrastructure() : infrastructure(candidate, "", Set.of())) return new Assessment(List.of(), false, false);
        boolean conflict = false;
        for (var dep : version.path("dependencies")) if (dep.path("dependency_type").asText().equals("incompatible")) {
            String id = dep.path("project_id").asText("");
            if (id.isBlank()) id = versionProjects.get(dep.path("version_id").asText());
            String exact = dep.path("version_id").asText("");
            String targetId = id;
            if (!exact.isBlank()) {
                var target = byId.get(targetId);
                if (byId.values().stream().anyMatch(p -> exact.equals(p.versionId())) || targetId == null || targetId.isBlank()
                        || target != null && target.versionId().isBlank() && !deferExactConflicts) conflict = true;
            } else if (id == null || id.isBlank() || byId.containsKey(id)) conflict = true;
        }
        var result = new ArrayList<Evidence>(); boolean corePresent = false;
        String description = retained.stream().filter(p -> p.slug().equals(candidate)).map(Project::description).findFirst().orElse("");
        description = description.substring(0, Math.min(280, description.length()));
        final String quote = description;
        for (var core : cores.entrySet()) {
            var a = byId.get(core.getValue());
            if (a == null || strict && exactMismatch(a, version, versionProjects)) continue;
            boolean versionCore = positiveRelation(a, version, versionProjects, strict);
            var partners = byId.values().stream().filter(Project::content)
                    .filter(b -> !b.id().equals(a.id()) && !b.slug().equals(candidate))
                    .filter(b -> !strict || !exactMismatch(b, version, versionProjects))
                    .filter(b -> versionCore && positiveRelation(b, version, versionProjects, strict)
                            || AddonApplicability.relates(quote, a, b)
                            && (!strict || !a.versionId().isBlank() && !b.versionId().isBlank()))
                    .sorted(Comparator.comparing(Project::slug)).toList();
            if (!versionCore && partners.isEmpty()) continue;
            corePresent = true;
            boolean versionRelation = versionCore && partners.stream().allMatch(b -> positiveRelation(b, version, versionProjects, strict));
            String status = a.downloads() == null || partners.stream().anyMatch(b -> b.downloads() == null)
                    ? "UNKNOWN_DOWNLOADS" : a.downloads() == 0 ? "ZERO_CORE_DOWNLOADS" : "KNOWN_DOWNLOADS";
            Long max = partners.stream().anyMatch(b -> b.downloads() == null) ? null
                    : partners.stream().mapToLong(Project::downloads).max().orElse(0);
            result.add(new Evidence(core.getKey(), partners.stream().map(Project::slug).toList(),
                    a.downloads(), max,
                    coefficient(a.downloads(), partners.stream().map(Project::downloads).toList()), status,
                    versionRelation ? "VERSION_RELATION" : "DESCRIPTION_RELATION", versionRelation ? "" : description));
        }
        return new Assessment(List.copyOf(result), corePresent, conflict);
    }
}
