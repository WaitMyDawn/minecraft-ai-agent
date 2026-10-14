package yagen.waitmydawn.maa.model;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** 文本只用于粗召回；自动附属候选必须有指向核心项目的已选版本依赖证据。 */
public final class AddonEvidence {
    private AddonEvidence() {}
    private static final Set<String> GENERIC = Set.of("mod", "mods", "addon", "addons", "the", "and", "for", "with", "forge", "fabric", "neoforge");
    public static int textStrength(String core, String haystack) {
        Set<String> words = new LinkedHashSet<>(List.of(core.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")));
        words.removeIf(w -> w.length() < 3 || GENERIC.contains(w));
        Set<String> hay = Set.copyOf(List.of(haystack.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")));
        int hits = (int) words.stream().filter(hay::contains).count();
        return hits >= Math.min(2, words.size()) && !words.isEmpty() ? hits : 0;
    }
    public static String relationship(String projectId, JsonNode version, Map<String, String> versionProjects) {
        return relationship(projectId, version, versionProjects, false);
    }
    /** 精确版本的冲突可延后至选版协调；项目级互斥仍否决当前版本的核心关系。 */
    public static String relationship(String projectId, JsonNode version, Map<String, String> versionProjects, boolean deferExactConflicts) {
        for (JsonNode dep : version.path("dependencies")) {
            String target = dep.path("project_id").asText("");
            if (target.isBlank()) target = versionProjects.get(dep.path("version_id").asText());
            if ("incompatible".equals(dep.path("dependency_type").asText()) && projectId.equals(target)
                    && (!deferExactConflicts || dep.path("version_id").asText("").isBlank())) return null;
        }
        String result = null;
        for (JsonNode dep : version.path("dependencies")) {
            String type = dep.path("dependency_type").asText();
            if (!Set.of("required", "optional").contains(type)) continue;
            String target = dep.path("project_id").asText("");
            if (target.isBlank()) target = versionProjects.get(dep.path("version_id").asText());
            if (!projectId.equals(target)) continue;
            if ("required".equals(type)) return "REQUIRED_CORE";
            result = "OPTIONAL_CORE";
        }
        return result;
    }
}
