package yagen.waitmydawn.maa.service;

import tools.jackson.databind.JsonNode;
import yagen.waitmydawn.maa.model.agent.BuildIntent;
import java.util.*;

/** 必选项的候选发现只召回本体移植、延续版或明确声明的功能替代，不把附属当作本体。 */
public final class RequiredModAlternatives {
    private RequiredModAlternatives() {}
    public record Candidate(String slug, String title, String description, String iconUrl,
                            long downloads, int similarity, String relation) {}
    public record Group(String original, List<Candidate> candidates, boolean verified) {
        public Group { candidates = List.copyOf(candidates); }
    }
    private static final Set<String> EDITION_WORDS = Set.of("unofficial", "official", "port", "ported", "ports",
            "continued", "continuation", "community", "fork", "reborn", "revived", "updated", "update",
            "neoforge", "forge", "fabric", "version", "edition", "for");
    private static final Set<String> ADDON_WORDS = Set.of("addon", "addons", "extension", "extensions", "compat",
            "compatibility", "integration", "delight", "music", "extra", "patch", "tweak", "tweaks");

    public static Group discover(ModrinthApiClient api, String original, BuildIntent.Environment environment,
                                 Set<String> excluded) {
        String loader = environment.loader().name().toLowerCase(Locale.ROOT);
        String facets = "[[\"project_type:mod\"],[\"loaders:" + loader + "\"],[\"versions:"
                + environment.mcVersion() + "\"]]";
        List<Candidate> result = new ArrayList<>();
        boolean verified = true;
        try {
            JsonNode response = api.search(original, 40, facets);
            if (response == null || !response.path("hits").isArray()) return new Group(original, List.of(), false);
            JsonNode originalProject = api.getProjectInfo(original);
            String originalId = originalProject == null ? original : originalProject.path("id").asText(original);
            Set<String> seen = new HashSet<>();
            for (JsonNode hit : response.path("hits")) {
                String slug = hit.path("slug").asText();
                if (!ModrinthIdentifiers.isProjectReference(slug) || slug.equals(original)
                        || excluded.contains(slug) || !seen.add(slug)) continue;
                String relation = relation(original, slug, hit.path("title").asText(), hit.path("description").asText());
                if (relation == null) continue;
                try {
                    JsonNode version = api.getLatestCompatibleVersionBySlug(slug, environment.mcVersion(), loader);
                    if (version == null) continue;
                    JsonNode project = api.getProjectInfo(slug);
                    if (project == null || !project.path("id").isTextual()) { verified = false; continue; }
                    // 项目名相似仍不足以排除附属；直接依赖原项目的候选不承担本体替换职责。
                    boolean addon = false;
                    for (JsonNode dependency : version.path("dependencies")) {
                        if (!Set.of("required", "optional").contains(dependency.path("dependency_type").asText())) continue;
                        String projectId = dependency.path("project_id").asText();
                        if (projectId.equals(originalId) || projectId.equals(original)) addon = true;
                    }
                    if (addon) continue;
                    result.add(new Candidate(slug, clip(project.path("title").asText(slug), 200),
                            clip(project.path("description").asText(), 600), safeIcon(project.path("icon_url").asText()),
                            project.path("downloads").asLong(), relation.equals("功能替代") ? 80 : 100, relation));
                } catch (ModrinthApiClient.UnavailableException failure) { verified = false; }
            }
        } catch (ModrinthApiClient.UnavailableException failure) { verified = false; }
        result.sort(Comparator.comparingInt(Candidate::similarity).reversed()
                .thenComparing(Comparator.comparingLong(Candidate::downloads).reversed()).thenComparing(Candidate::slug));
        return new Group(original, result.stream().limit(8).toList(), verified);
    }

    static String relation(String original, String slug, String title, String description) {
        List<String> base = words(original);
        if (base.isEmpty()) return null;
        List<String> candidate = words(slug);
        List<String> titleWords = words(title);
        if (candidate.stream().anyMatch(ADDON_WORDS::contains) || titleWords.stream().anyMatch(ADDON_WORDS::contains)) return null;
        if (candidate.size() >= base.size() && candidate.subList(0, base.size()).equals(base)) {
            List<String> suffix = candidate.subList(base.size(), candidate.size());
            if (suffix.stream().allMatch(w -> EDITION_WORDS.contains(w) || w.matches("\\d+")))
                return suffix.contains("continued") || suffix.contains("continuation") ? "延续版" : "移植版";
        }
        String text = description.toLowerCase(Locale.ROOT).replace('-', ' ');
        String name = String.join(" ", base);
        if (text.contains("alternative to " + name) || text.contains("replacement for " + name)) return "功能替代";
        return null;
    }
    private static List<String> words(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).replace("'", "").split("[^a-z0-9]+"))
                .filter(w -> !w.isEmpty()).toList();
    }
    private static String clip(String text, int max) { return text.substring(0, Math.min(text.length(), max)); }
    private static String safeIcon(String icon) {
        try {
            var uri = java.net.URI.create(icon);
            return "https".equals(uri.getScheme()) && "cdn.modrinth.com".equals(uri.getHost()) ? icon : "";
        } catch (IllegalArgumentException invalid) { return ""; }
    }
}
