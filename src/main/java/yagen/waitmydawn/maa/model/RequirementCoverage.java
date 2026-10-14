package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;

/** 最终覆盖以选版和留存项目为准；元数据用途关联不证明丰富程度或游戏运行效果。 */
public final class RequirementCoverage {
    private RequirementCoverage() {}
    public record Item(String slug, String purpose, String quote) {}
    public record CriterionRow(String id, String source, String status, List<Item> mods, int modCount,
                               List<String> missing, int missingCount, String note) {}
    public record Row(String id, String source, String status, List<Item> mods, int modCount,
                      List<String> missing, int missingCount, String note, List<CriterionRow> criteria) {}
    public record Report(int schemaVersion, List<Row> requirements, String repairStatus, List<String> repaired) {}

    public static List<RequirementReview> verified(List<BuildRequirement> requirements, List<RequirementReview> reviews,
                                                  Map<String, String> facts, Set<String> vetoes) {
        var byId = new HashMap<String, BuildRequirement>(); requirements.forEach(r -> byId.put(r.id(), r));
        return reviews.stream().filter(r -> byId.containsKey(r.id())).map(r -> new RequirementReview(r.id(),
                quoted(r.evidence(), facts, vetoes), r.gap(), r.criteria().stream()
                        .filter(c -> byId.get(r.id()).criteria().stream().anyMatch(planned -> planned.source().equals(c.source())))
                        .map(c -> new CriterionReview(c.source(), quoted(c.evidence(), facts, vetoes), c.gap())).toList())).toList();
    }
    private static List<RequirementReview.Contribution> quoted(List<RequirementReview.Contribution> evidence, Map<String, String> facts, Set<String> vetoes) {
        return evidence.stream().filter(e -> !vetoes.contains(e.slug()))
                .filter(e -> facts.containsKey(e.slug()) && facts.get(e.slug()).contains(e.quote())).toList();
    }
    public static void validateReviews(List<BuildRequirement> requirements, List<RequirementReview> reviews) {
        var byId = new HashMap<String, RequirementReview>(); reviews.forEach(r -> byId.put(r.id(), r));
        for (var r : requirements) if (!r.criteria().isEmpty()) {
            var review = byId.get(r.id());
            if (review == null || !review.gap().isBlank() || !review.evidence().isEmpty()
                    || !r.criteria().stream().map(RequirementCriterion::source).collect(java.util.stream.Collectors.toSet())
                    .equals(review.criteria().stream().map(CriterionReview::source).collect(java.util.stream.Collectors.toSet())))
                throw new IllegalArgumentException("requirements.criteria 审核须逐项覆盖规划子目标，父项 evidence/gap 留空");
        }
    }

    public static Report assess(List<BuildRequirement> requirements, List<RequirementReview> reviews,
                                DependencyGraph graph, Set<String> selected, Map<String, String> identities,
                                Map<String, PackAddonPolicy.Project> metadata, String repairStatus, List<String> repaired) {
        return assess(requirements, reviews, graph, selected, identities, metadata, repairStatus, repaired, true);
    }
    private static Report assess(List<BuildRequirement> requirements, List<RequirementReview> reviews,
                                DependencyGraph graph, Set<String> selected, Map<String, String> identities,
                                Map<String, PackAddonPolicy.Project> metadata, String repairStatus, List<String> repaired, boolean truncate) {
        var byId = new HashMap<String, RequirementReview>();
        reviews.forEach(r -> byId.put(r.id(), r));
        var versionProjects = new HashMap<String, String>();
        graph.selectedVersions.forEach((s, v) -> versionProjects.put(v.path("id").asText(), v.path("project_id").asText()));
        var retained = metadata.values().stream().filter(p -> selected.contains(p.slug()))
                .filter(p -> graph.selectedVersions.containsKey(p.slug()))
                .map(p -> p.withVersion(graph.selectedVersions.get(p.slug()).path("id").asText())).toList();
        var rows = new ArrayList<Row>();
        for (var r : requirements) {
            if (r.kind() == BuildRequirement.Kind.CHANGE) continue;
            var items = new LinkedHashMap<String, Item>();
            var missing = new ArrayList<String>();
            for (String reference : r.projects()) {
                String slug = identities.getOrDefault(reference, reference);
                if (selected.contains(slug) && graph.selectedVersions.containsKey(slug))
                    items.put(slug, new Item(slug, "点名项目，已选兼容版本", ""));
                else missing.add(slug);
            }
            var review = byId.get(r.id());
            if (review != null) for (var e : review.evidence()) {
                if (selected.contains(e.slug()) && graph.selectedVersions.containsKey(e.slug()))
                    items.put(e.slug(), new Item(e.slug(), e.purpose(), e.quote()));
            }
            boolean addons = r.criteria().isEmpty() && (r.kind() == BuildRequirement.Kind.ADDONS || r.kind() == BuildRequirement.Kind.PACK_INTEGRATIONS);
            var missingCores = new ArrayList<String>();
            if (addons && r.addonCores().isEmpty()) missingCores.add("扩展核心身份未核实");
            if (addons) for (String reference : r.addonCores()) {
                String core = identities.getOrDefault(reference, reference);
                var coreVersion = graph.selectedVersions.get(core);
                var coreMap = coreVersion == null ? Map.<String, String>of() : Map.of(core, coreVersion.path("project_id").asText());
                boolean found = false;
                for (String slug : selected) {
                    if (slug.equals(core)) continue;
                    var evidence = PackAddonPolicy.assess(slug, graph.selectedVersions.get(slug), versionProjects, coreMap, retained);
                    boolean match = evidence.corePresent() && !evidence.conflict()
                            && (r.kind() != BuildRequirement.Kind.PACK_INTEGRATIONS || evidence.integration());
                    if (match) {
                        found = true;
                        items.put(slug, new Item(slug, "已选版本声明与 " + core + " 的"
                                + (r.kind() == BuildRequirement.Kind.PACK_INTEGRATIONS ? "包内内容联动" : "附属关系"), ""));
                    }
                }
                if (!found) missingCores.add(core);
            }
            String gap = review == null ? "" : review.gap();
            boolean gameplay = r.kind() != BuildRequirement.Kind.PROJECTS && (r.kind() == BuildRequirement.Kind.CAPABILITY || !r.capabilities().isEmpty()
                    || !r.searches().isEmpty() || (r.kind() == BuildRequirement.Kind.GENERAL && review != null && !review.evidence().isEmpty()));
            boolean semanticEvidence = review != null && review.evidence().stream()
                    .anyMatch(e -> selected.contains(e.slug()) && graph.selectedVersions.containsKey(e.slug()));
            String status;
            String note;
            if (!missing.isEmpty()) {
                status = items.isEmpty() ? "NOT_RETAINED" : "PARTIAL";
                note = "部分点名项目未在最终兼容清单中保留";
            } else if (!missingCores.isEmpty()) {
                status = items.isEmpty() ? "UNVERIFIED" : "PARTIAL";
                note = "尚无足够的最终版本关系证据：" + String.join("、", missingCores);
            } else if (gameplay && !semanticEvidence) {
                status = items.isEmpty() ? "UNVERIFIED" : "PARTIAL";
                note = "用途尚未取得可核对的审核证据；检索命中和类别数量不能证明满足";
            } else if (!gap.isBlank() && (gameplay || addons)) {
                status = items.isEmpty() ? "UNVERIFIED" : "PARTIAL";
                note = "审核尚待复核：" + gap;
                if (review.evidence().stream().anyMatch(e -> repaired.contains(e.slug())))
                    note += "；本轮已补入关联候选，此审核意见尚未重新裁决";
            } else if (gameplay || addons) {
                status = "SUPPORTING_EVIDENCE";
                note = "已有入包依据；丰富程度、组合体验及运行效果仍需实际评估"
                        + (addons ? "，附属目录未作穷尽承诺" : "");
            } else if (!r.projects().isEmpty()) {
                status = "VERIFIED_PROJECTS";
                note = "点名项目已保留，不代表游戏启动检查通过";
            } else {
                status = "CONTEXT";
                note = "名称、环境、数量或主题上下文；以最终成果及数量检查为准";
            }
            var criteria = new ArrayList<CriterionRow>();
            if (!r.criteria().isEmpty()) {
                var scoped = new HashMap<String, CriterionReview>();
                if (review != null) review.criteria().forEach(c -> scoped.put(c.source(), c));
                for (var c : r.criteria()) {
                    var target = c.target(r);
                    var evidence = scoped.get(c.source());
                    var childReviews = evidence == null ? List.<RequirementReview>of()
                            : List.of(new RequirementReview(target.id(), evidence.evidence(), evidence.gap()));
                    var child = assess(List.of(target), childReviews, graph, selected, identities, metadata, repairStatus, repaired, false).requirements();
                    if (child.isEmpty()) continue;
                    var row = child.getFirst();
                    row.mods().forEach(m -> items.put(m.slug(), m));
                    criteria.add(new CriterionRow(row.id(), row.source(), row.status(), row.mods().stream().limit(24).toList(), row.modCount(),
                            row.missing().stream().limit(24).toList(), row.missingCount(), row.note()));
                }
                if ((!missing.isEmpty() || !missingCores.isEmpty()) && !items.isEmpty()) status = "PARTIAL";
                var lacking = criteria.stream().filter(c -> lacking(c.status())).toList();
                if (missing.isEmpty() && missingCores.isEmpty()) {
                    if (!lacking.isEmpty()) {
                        status = items.isEmpty() ? "UNVERIFIED" : "PARTIAL";
                        note = "尚缺独立子目标依据：" + String.join("、", lacking.stream().map(CriterionRow::source).toList());
                    } else if (criteria.stream().anyMatch(c -> c.status().equals("SUPPORTING_EVIDENCE"))) {
                        status = "SUPPORTING_EVIDENCE";
                        note = "有效子目标分别有入包依据；不代表丰富程度或运行效果已验证";
                    } else if (criteria.stream().anyMatch(c -> c.status().equals("VERIFIED_PROJECTS"))) {
                        status = "VERIFIED_PROJECTS";
                        note = "有效子目标的点名项目已分别保留";
                    } else { status = "CONTEXT"; note = "有效子目标仅包含上下文或已解除项"; }
                }
            }
            rows.add(new Row(r.id(), r.source(), status, items.values().stream().limit(truncate ? 24 : Long.MAX_VALUE).toList(), items.size(),
                    missing.stream().limit(truncate ? 24 : Long.MAX_VALUE).toList(), missing.size(), note, List.copyOf(criteria)));
        }
        return new Report(1, List.copyOf(rows), repairStatus, List.copyOf(repaired));
    }

    public static List<String> repairs(Report report, List<RequirementReview> reviews, Set<String> eligible, Set<String> selected) {
        var gaps = report.requirements().stream().filter(r -> r.status().equals("UNVERIFIED") || r.status().equals("PARTIAL"))
                .filter(r -> r.mods().stream().noneMatch(m -> !m.quote().isBlank())).map(Row::id)
                .collect(java.util.stream.Collectors.toSet());
        var result = new LinkedHashSet<String>();
        var groups = new ArrayList<List<RequirementReview.Contribution>>();
        var criterionGaps = new HashMap<String, Set<String>>();
        report.requirements().forEach(r -> criterionGaps.put(r.id(), r.criteria().stream().filter(c -> lacking(c.status()))
                .map(CriterionRow::source).collect(java.util.stream.Collectors.toSet())));
        for (var r : reviews) {
            if (gaps.contains(r.id())) groups.add(r.evidence());
            r.criteria().stream().filter(c -> criterionGaps.getOrDefault(r.id(), Set.of()).contains(c.source()))
                    .forEach(c -> groups.add(c.evidence()));
        }
        var permitted = groups.stream().map(g -> g.stream().filter(e -> eligible.contains(e.slug()) && !selected.contains(e.slug())).toList()).toList();
        int rounds = permitted.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < rounds && result.size() < 3; i++) for (var group : permitted)
            if (i < group.size() && result.size() < 3) result.add(group.get(i).slug());
        return List.copyOf(result);
    }
    private static boolean lacking(String status) { return Set.of("UNVERIFIED", "PARTIAL", "NOT_RETAINED").contains(status); }
    public static List<BuildRequirement> missingTargets(List<BuildRequirement> requirements, Report report) {
        var byId = new HashMap<String, Set<String>>();
        report.requirements().forEach(r -> byId.put(r.id(), r.criteria().stream().filter(c -> lacking(c.status()))
                .map(CriterionRow::source).collect(java.util.stream.Collectors.toSet())));
        return requirements.stream().flatMap(r -> r.criteria().stream()
                        .filter(c -> byId.getOrDefault(r.id(), Set.of()).contains(c.source())).map(c -> c.target(r)))
                .filter(r -> r.kind() == BuildRequirement.Kind.CAPABILITY && !r.searches().isEmpty()).limit(4).toList();
    }
    /** 定向复核只替换指定子目标的证据，已经支持的其它目标不被增量响应抹掉。 */
    public static List<RequirementReview> merge(List<RequirementReview> prior, List<RequirementReview> updates) {
        var rows = new LinkedHashMap<String, RequirementReview>(); prior.forEach(r -> rows.put(r.id(), r));
        for (var update : updates) {
            var old = rows.get(update.id());
            if (old == null || update.criteria().isEmpty()) { rows.put(update.id(), update); continue; }
            var criteria = new LinkedHashMap<String, CriterionReview>(); old.criteria().forEach(c -> criteria.put(c.source(), c));
            update.criteria().forEach(c -> criteria.put(c.source(), c));
            rows.put(update.id(), new RequirementReview(old.id(), old.evidence(), old.gap(), List.copyOf(criteria.values())));
        }
        return List.copyOf(rows.values());
    }
}
