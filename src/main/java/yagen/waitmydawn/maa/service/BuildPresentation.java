package yagen.waitmydawn.maa.service;

import yagen.waitmydawn.maa.model.*;
import java.util.*;

/** 对话展示数据只保存项目引用和数值；图标在浏览器渲染时加载，不进入历史消息。 */
public final class BuildPresentation {
    private BuildPresentation() {}
    public record Issue(String mod, String dependency, String versionId, String reason, boolean dropped,
                        String detail, List<String> codes, List<String> requiredVersions) {
        public Issue {
            detail = Objects.requireNonNullElse(detail, "");
            codes = codes == null ? List.of(reason) : List.copyOf(codes);
            requiredVersions = requiredVersions == null ? (versionId.isBlank() ? List.of() : List.of(versionId)) : List.copyOf(requiredVersions);
        }
        public Issue(String mod, String dependency, String versionId, String reason, boolean dropped) {
            this(mod, dependency, versionId, reason, dropped, "", List.of(reason), versionId.isBlank() ? List.of() : List.of(versionId));
        }
    }
    public record DependencyWarning(String mod, String modVersion, String dependency, String requiredVersion,
                                    String requiredVersionId, String currentVersion) {}
    public record Report(int schemaVersion, int total, Map<String, Integer> categories, List<Issue> issues,
                         OptimizationPortfolio.Report optimization, List<DependencyWarning> dependencyWarnings) {
        public Report(int schemaVersion, int total, Map<String, Integer> categories, List<Issue> issues, OptimizationPortfolio.Report optimization) {
            this(schemaVersion, total, categories, issues, optimization, List.of());
        }
        public Report(int schemaVersion, int total, Map<String, Integer> categories, List<Issue> issues) { this(schemaVersion, total, categories, issues, null); }
    }

    public static Report create(ModrinthApiClient api, DependencyGraph graph, ResolutionResult resolution,
                                DependencyClosure.Report closure, Set<String> selected, Map<String, Integer> categories) {
        Map<String, String> references = new HashMap<>(graph.projectAliases);
        graph.selectedVersions.forEach((slug, version) -> {
            references.put(version.path("project_id").asText(), slug);
            references.put("vid:" + version.path("id").asText(), slug);
        });
        Set<Issue> issues = new LinkedHashSet<>();
        var warnings = new LinkedHashSet<DependencyWarning>();
        var versionLabels = new HashMap<String, String>();
        int[] lookupBudget = {32};
        resolution.dropped().stream().filter(d -> !selected.contains(d.slug()))
                .forEach(d -> issues.add(new Issue(d.slug(), "", "", d.reason().name(), true, d.detail(), List.of(d.reason().name()), List.of())));
        for (var u : resolution.unresolved()) {
            String dependency = resolve(api, u.slug(), references, lookupBudget);
            String version = u.slug().startsWith("vid:") ? u.slug().substring(4) : "";
            issues.add(new Issue(u.requiredBy().startsWith("(") ? "" : u.requiredBy(), dependency,
                    version, u.reason().name(), !selected.contains(u.requiredBy()), u.detail(), List.of(u.reason().name()),
                    version.isBlank() ? List.of() : List.of(version)));
        }
        for (var issue : closure.issues()) {
            String dependency = resolve(api, issue.dependency(), references, lookupBudget);
            if (issue.code().equals("VERSION_MISMATCH") && issue.severity() == DependencyClosure.Severity.WARNING) {
                String required = versionLabels.get(issue.versionId());
                if (required == null) {
                    required = issue.versionId();
                    if (lookupBudget[0]-- > 0) try {
                        required = versionLabel(api.getVersionById(issue.versionId()), issue.versionId());
                    } catch (ModrinthApiClient.UnavailableException unavailable) {
                        // 版本号补全失败时保留平台 ID，提示仍可追溯。
                    }
                    versionLabels.put(issue.versionId(), required);
                }
                warnings.add(new DependencyWarning(issue.dependent(), versionLabel(graph.selectedVersions.get(issue.dependent()), "未知"),
                        dependency, required, issue.versionId(), versionLabel(graph.selectedVersions.get(dependency), "未知")));
                continue;
            }
            issues.add(new Issue(issue.dependent(), dependency,
                    !issue.versionId().isBlank() ? issue.versionId() : issue.dependency().startsWith("vid:") ? issue.dependency().substring(4) : "", issue.code(), false));
        }
        var grouped = new LinkedHashMap<String, List<Issue>>();
        for (var issue : issues) grouped.computeIfAbsent(issue.mod() + "\0" + issue.dependency() + "\0" + issue.dropped(), ignored -> new ArrayList<>()).add(issue);
        var merged = new ArrayList<Issue>();
        for (var rows : grouped.values()) {
            var first = rows.stream().min(Comparator.comparingInt(i -> switch (i.reason()) {
                case "MISSING_REQUIRED", "VERSION_MISMATCH" -> 1;
                case "MULTILOADER_DEPENDENCY_UNVERIFIED" -> 2;
                default -> 0;
            })).orElseThrow();
            var versions = rows.stream().flatMap(i -> i.requiredVersions().stream()).distinct().sorted().toList();
            String detail = String.join("；", rows.stream().map(Issue::detail).filter(s -> !s.isBlank()).distinct().toList());
            merged.add(new Issue(first.mod(), first.dependency(), versions.size() == 1 ? versions.getFirst() : "", first.reason(), first.dropped(),
                    detail.substring(0, Math.min(1000, detail.length())), rows.stream().flatMap(i -> i.codes().stream()).distinct().sorted().toList(), versions));
        }
        return new Report(1, selected.size(), Map.copyOf(categories), List.copyOf(merged), null, List.copyOf(warnings));
    }
    private static String versionLabel(tools.jackson.databind.JsonNode version, String fallback) {
        if (version == null) return fallback;
        String label = version.path("version_number").asText("");
        if (label.isBlank()) label = version.path("id").asText(fallback);
        return label.substring(0, Math.min(256, label.length()));
    }
    private static String resolve(ModrinthApiClient api, String reference, Map<String, String> references, int[] budget) {
        if (reference == null || reference.isBlank()) return "";
        if (references.containsKey(reference)) return references.get(reference);
        String resolved = reference.startsWith("vid:") ? "" : reference;
        if (!reference.startsWith("vid:") && !reference.matches("[A-Za-z0-9]{8}")) return reference;
        if (budget[0]-- <= 0) return resolved;
        try {
            String project = reference;
            if (reference.startsWith("vid:")) {
                var version = api.getVersionById(reference.substring(4));
                project = version == null ? "" : version.path("project_id").asText();
            }
            if (!project.isBlank()) {
                var info = api.getProjectInfo(project);
                if (info != null && !info.path("slug").asText().isBlank()) resolved = info.path("slug").asText();
            }
        } catch (ModrinthApiClient.UnavailableException unavailable) {
            // 展示补全失败不影响清单；保留版本引用，避免伪造项目名。
        }
        references.put(reference, resolved);
        return resolved;
    }
}
