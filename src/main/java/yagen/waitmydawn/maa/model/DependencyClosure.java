package yagen.waitmydawn.maa.model;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** 已选版本元数据的只读闭合核查；保留失败依赖，不能从已裁剪的可视图推断完整性。 */
public record DependencyClosure(Map<String, String> versions, List<Requirement> requirements, List<Issue> unresolved) {
    private static final int MAX_REQUIREMENTS = 8192;
    public enum Status { COMPLETE, INCOMPLETE, UNVERIFIED }
    public enum Severity { BROKEN, UNKNOWN }
    public record Requirement(String dependent, String dependency, String versionId) {}
    public record Issue(String dependent, String dependency, String code, Severity severity) {}
    public record Report(Status status, List<Issue> issues) {}
    public DependencyClosure {
        versions = Map.copyOf(versions);
        requirements = List.copyOf(requirements);
        unresolved = List.copyOf(unresolved);
    }

    public static DependencyClosure capture(DependencyGraph graph, List<ResolutionResult.Unresolved> failures,
                                             Map<String, String> projectAliases) {
        Map<String, String> aliases = new HashMap<>(projectAliases);
        Map<String, String> byVersion = new HashMap<>();
        Map<String, String> versions = new TreeMap<>();
        for (var entry : graph.selectedVersions.entrySet()) {
            String slug = entry.getKey();
            String versionId = entry.getValue().path("id").asText("");
            String projectId = entry.getValue().path("project_id").asText("");
            if (!projectId.isBlank()) aliases.put(projectId, slug);
            if (!versionId.isBlank()) { versions.put(slug, versionId); byVersion.put(versionId, slug); }
        }
        var requirements = new LinkedHashSet<Requirement>();
        var issues = new LinkedHashSet<Issue>();
        for (String dependent : new TreeSet<>(graph.allSlugs)) {
            for (String dependency : new TreeSet<>(graph.getDependenciesOf(dependent)))
                addRequirement(requirements, issues, new Requirement(dependent, dependency, ""));
            JsonNode version = graph.selectedVersions.get(dependent);
            if (version == null || !version.path("dependencies").isArray() || version.path("id").asText("").isBlank()) {
                issues.add(new Issue(dependent, "", "UNVERIFIED_METADATA", Severity.UNKNOWN));
                continue;
            }
            for (JsonNode dependency : version.path("dependencies")) {
                if (!"required".equals(dependency.path("dependency_type").asText())) continue;
                String projectId = dependency.path("project_id").asText("");
                String versionId = dependency.path("version_id").asText("");
                if (!projectId.isBlank()) {
                    addRequirement(requirements, issues, new Requirement(dependent, aliases.getOrDefault(projectId, projectId), versionId));
                } else if (!versionId.isBlank()) {
                    addRequirement(requirements, issues, new Requirement(dependent, byVersion.getOrDefault(versionId, "vid:" + versionId), versionId));
                } else {
                    issues.add(new Issue(dependent, dependency.path("file_name").asText(""), "UNVERIFIED_FILE_DEPENDENCY", Severity.UNKNOWN));
                }
            }
        }
        for (var failure : failures) {
            String dependent = failure.requiredBy();
            if (graph.allSlugs.contains(dependent)) {
                Severity severity = failure.reason() == ResolutionResult.Reason.UPSTREAM_UNAVAILABLE
                        || failure.reason() == ResolutionResult.Reason.BUDGET_EXCEEDED ? Severity.UNKNOWN : Severity.BROKEN;
                issues.add(new Issue(dependent, failure.slug(), failure.reason().name(), severity));
            } else if ("(版本约束)".equals(dependent) && graph.allSlugs.contains(failure.slug())) {
                issues.add(new Issue(failure.slug(), failure.slug(), failure.reason().name(), Severity.BROKEN));
            }
        }
        return new DependencyClosure(versions, List.copyOf(requirements), List.copyOf(issues));
    }

    /** 快照表全局共享，异常高边数不能按快照数量成倍占用内存；触顶必须显式保留未知状态。 */
    private static void addRequirement(Set<Requirement> requirements, Set<Issue> issues, Requirement requirement) {
        if (requirements.contains(requirement)) return;
        if (requirements.size() >= MAX_REQUIREMENTS) {
            issues.add(new Issue(requirement.dependent(), "", "AUDIT_BUDGET_EXCEEDED", Severity.UNKNOWN));
        } else requirements.add(requirement);
    }

    public Report inspect(Set<String> selected) {
        var issues = new LinkedHashSet<Issue>();
        for (var issue : unresolved) if (selected.contains(issue.dependent())) issues.add(issue);
        Set<String> unknownDependents = new HashSet<>();
        for (var issue : issues) if (issue.severity() == Severity.UNKNOWN) unknownDependents.add(issue.dependent());
        for (var requirement : requirements) {
            if (!selected.contains(requirement.dependent())) continue;
            if (!selected.contains(requirement.dependency())) {
                // 未完成上游查询不能被元数据引用的缺席升级为“确定不存在”。
                boolean unknown = !versions.containsKey(requirement.dependency()) && unknownDependents.contains(requirement.dependent());
                if (!unknown) issues.add(new Issue(requirement.dependent(), requirement.dependency(), "MISSING_REQUIRED", Severity.BROKEN));
            } else if (!requirement.versionId().isBlank()
                    && !requirement.versionId().equals(versions.get(requirement.dependency()))) {
                issues.add(new Issue(requirement.dependent(), requirement.dependency(), "VERSION_MISMATCH", Severity.BROKEN));
            }
        }
        List<Issue> ordered = issues.stream().sorted(Comparator.comparing(Issue::dependent).thenComparing(Issue::dependency).thenComparing(Issue::code)).toList();
        return new Report(ordered.stream().anyMatch(i -> i.severity() == Severity.BROKEN) ? Status.INCOMPLETE
                : ordered.isEmpty() ? Status.COMPLETE : Status.UNVERIFIED, ordered);
    }
}
