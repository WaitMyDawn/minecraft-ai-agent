package yagen.waitmydawn.maa.model;

import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** 只对附属或联动角色检查包内适用对象；可选兼容声明不改变普通主体模组的入包资格。 */
public final class AddonApplicability {
    private AddonApplicability() {}
    private static final Pattern ADDON = Pattern.compile("(?iu)\\badd[- ]?on\\b|附属|兼容补丁|联动模组|(?:^|[.!?。！？]\\s*)(?:(?:this mod|it)\\s+)?(?:(?:adds?|provides?|offers?|is)\\s+)?(?:(?:a|an|the)\\s+)?(?:compatibility|integration|bridge)\\b");
    private static final Pattern BRIDGE = Pattern.compile("(?iu)\\b(?:compatibility|integration|bridge|compat|compatible with|add[- ]?on for)\\b|联动|兼容补丁");
    private static final Pattern NEGATIVE = Pattern.compile("(?iu)\\b(?:not|doesn't|does not)\\s+(?:(?:an?|provide|offer|add)\\s+)?(?:compatible|compatibility|integration|bridge|compat)\\b|不兼容|不提供|并非|不是|建议安装|推荐安装");
    private static final String ENDPOINT = "[^\\n.!?;,。！？；，]{1,120}";
    private static final Pattern ADDON_PAIR = Pattern.compile("(?iu)\\b(?:add[- ]?on|crossover(?:\\s+add[- ]?on)?)\\s+for\\s+(?:both\\s+)?(?<left>" + ENDPOINT + "?)\\s+and\\s+(?<right>" + ENDPOINT + ")");
    private static final Pattern ADDON_FEATURE_CLAUSE = Pattern.compile("(?iu)\\b(?:that|which|adds?|with)\\b");
    private static final Pattern RELATION_MARKER = Pattern.compile("(?iu)\\b(?:compatibility|integration|bridge|compat|add[- ]?on|crossover)\\b|兼容补丁|联动");
    private static final Pattern CHINESE_PREFIX = Pattern.compile("(?iu)(?:兼容补丁|联动)(?:模组)?[：:]?\\s*(?<left>" + ENDPOINT + "?)(?:与|和)(?<right>" + ENDPOINT + ")");
    private static final Pattern CHINESE_SUFFIX = Pattern.compile("(?iu)(?<left>" + ENDPOINT + "?)(?:与|和)(?<right>" + ENDPOINT + "?)(?:之间)?的?(?:联动|兼容补丁)");
    private static final List<Pattern> RELATIONS = List.of(
            Pattern.compile("(?iu)\\b(?:compatibility|integration|bridge|compat)\\s+(?:between|for)\\s+(?<left>" + ENDPOINT + "?)\\s+and\\s+(?<right>" + ENDPOINT + ")"),
            Pattern.compile("(?iu)\\b(?:compatibility|integration|bridge|compat)\\s+of\\s+(?<left>" + ENDPOINT + "?)\\s+with\\s+(?<right>" + ENDPOINT + ")"),
            Pattern.compile("(?iu)\\b(?:compatibility|integration|bridge|compat)\\s+of\\s+(?![^\\n.!?;,。！？；，]{0,240}\\bwith\\b)(?<left>" + ENDPOINT + "?)\\s+and\\s+(?<right>" + ENDPOINT + ")"),
            CHINESE_PREFIX,
            CHINESE_SUFFIX, ADDON_PAIR);
    private static final Pattern OPTIONAL = Pattern.compile("(?iu)\\boptional(?:ly)?\\b|\\b(?:recommended|suggested)\\b|可选|可选装|建议安装|推荐安装");
    private static final Pattern EXPLICIT_MODS = Pattern.compile("(?iu)\\bmods?\\b|模组");
    private record Relation(Set<String> projects, boolean unverified) {}
    public static boolean addon(String text) { return text != null && ADDON.matcher(text).find(); }
    public static boolean bridge(String text) { return text != null && !NEGATIVE.matcher(text).find() && BRIDGE.matcher(text).find(); }
    private static String normalized(String text) {
        return " " + text.toLowerCase(Locale.ROOT).replaceAll("['’]", "").replaceAll("[^\\p{L}\\p{N}]+", " ").trim() + " ";
    }
    public static boolean mentions(String text, PackAddonPolicy.Project project) {
        String source = normalized(text);
        return List.of(project.slug(), project.title()).stream().filter(Objects::nonNull).map(AddonApplicability::normalized)
                .filter(name -> name.trim().length() >= 3 && !Set.of("mod", "core", "addon", "library").contains(name.trim())).anyMatch(source::contains);
    }
    private static List<Relation> relations(String text, Collection<PackAddonPolicy.Project> projects) {
        if (text == null || text.isBlank()) return List.of();
        var result = new ArrayList<Relation>();
        for (String clause : text.split("[\\n.!?;,。！？；，]")) {
            // 所有关系语法都包含这些标记；普通描述无须执行端点搜索。
            if (!RELATION_MARKER.matcher(clause).find()) continue;
            if (NEGATIVE.matcher(clause).find() || OPTIONAL.matcher(clause).find()) continue;
            for (var pattern : RELATIONS) {
                // 中文后缀语法在无对应关系词的拉丁文本上会产生大量无效回溯。
                if ((pattern == CHINESE_PREFIX || pattern == CHINESE_SUFFIX)
                        && !clause.contains("联动") && !clause.contains("兼容补丁")) continue;
                var matcher = pattern.matcher(clause);
                while (matcher.find()) {
                    String left = matcher.group("left"), right = matcher.group("right");
                    var a = new HashSet<String>(); var b = new HashSet<String>();
                    projects.stream().filter(PackAddonPolicy.Project::content).forEach(project -> {
                        if (mentions(left, project)) a.add(project.id());
                        if (mentions(right, project)) b.add(project.id());
                    });
                    if (pattern == ADDON_PAIR && (a.isEmpty() || b.isEmpty()
                            || ADDON_FEATURE_CLAUSE.matcher(left).find())) continue;
                    var endpoints = new HashSet<>(a); endpoints.addAll(b);
                    // 机制桥接不能仅凭关系词变成模组关系；未知端点须有已知身份或明确的模组称谓支撑。
                    boolean grounded = !endpoints.isEmpty() || EXPLICIT_MODS.matcher(matcher.group()).find();
                    if (grounded) result.add(new Relation(Set.copyOf(endpoints), a.isEmpty() || b.isEmpty()));
                }
            }
        }
        return result;
    }
    public static boolean relates(String text, PackAddonPolicy.Project a, PackAddonPolicy.Project b) {
        if (a.id().equals(b.id())) return false;
        return relations(text, List.of(a, b)).stream().anyMatch(r -> !r.unverified() && r.projects().contains(a.id()) && r.projects().contains(b.id()));
    }
    public record Check(String status, List<String> missingPartners, List<String> introducedContent, String quote) {
        public boolean blocks() { return status.equals("PARTNERS_UNVERIFIED") || !missingPartners.isEmpty() || !introducedContent.isEmpty(); }
    }
    public static Check assess(PackAddonPolicy.Project candidate, JsonNode version, Map<String, String> versionProjects,
                               Collection<PackAddonPolicy.Project> projects, Set<String> retained, boolean knownAddon) {
        return assess(candidate, version, versionProjects, projects, retained, retained, knownAddon);
    }
    public static Check assess(PackAddonPolicy.Project candidate, JsonNode version, Map<String, String> versionProjects,
                               Collection<PackAddonPolicy.Project> projects, Set<String> retained, Set<String> permittedContent, boolean knownAddon) {
        if (candidate == null || version == null || candidate.infrastructure() || !knownAddon && !addon(candidate.description()))
            return new Check("NOT_ASSESSED", List.of(), List.of(), "");
        var byId = new HashMap<String, PackAddonPolicy.Project>(); projects.forEach(p -> byId.put(p.id(), p));
        var peers = byId.values().stream().filter(p -> !p.id().equals(candidate.id())).toList();
        var relations = relations(candidate.description(), peers);
        var related = new HashSet<String>();
        relations.stream().filter(r -> r.projects().size() >= 2).forEach(r -> related.addAll(r.projects()));
        var missing = related.stream().map(byId::get)
                .filter(p -> !retained.contains(p.slug()) && !retained.contains(p.id())).map(PackAddonPolicy.Project::slug).distinct().sorted().toList();
        var introduced = new TreeSet<String>();
        for (var dep : version.path("dependencies")) {
            if (!"required".equals(dep.path("dependency_type").asText())) continue;
            String id = dep.path("project_id").asText();
            if (id.isBlank()) id = versionProjects.getOrDefault(dep.path("version_id").asText(), "");
            var project = byId.get(id);
            if (project != null && project.content() && !project.slug().equals(candidate.slug())
                    && !permittedContent.contains(project.slug()) && !permittedContent.contains(project.id())) introduced.add(project.slug());
        }
        return new Check(!missing.isEmpty() ? "PARTNER_NOT_RETAINED" : !introduced.isEmpty() ? "INTRODUCES_CONTENT"
                : relations.stream().anyMatch(Relation::unverified) ? "PARTNERS_UNVERIFIED"
                : !related.isEmpty() ? "PARTNERS_RETAINED" : "RELATION_UNVERIFIED", missing, List.copyOf(introduced), candidate.description());
    }
}
