package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;

/** 最终覆盖以选版和留存项目为准；元数据用途关联不证明丰富程度或游戏运行效果。 */
public final class RequirementCoverage {
    private RequirementCoverage() {}
    public record Item(String slug, String purpose, String quote, String evidenceType) {
        public Item(String slug, String purpose, String quote) { this(slug, purpose, quote, "REVIEW_QUOTE"); }
    }
    public record EvidenceState(String code, List<String> projects, boolean countsComplete) {
        public EvidenceState(String code, List<String> projects) { this(code, projects, true); }
        public EvidenceState { projects = List.copyOf(projects.stream().distinct().limit(24).toList()); }
    }
    public record CriterionRow(String id, String source, String status, List<Item> mods, int modCount,
                               List<String> missing, int missingCount, String note, EvidenceState evidenceState) {}
    public record Row(String id, String source, String status, List<Item> mods, int modCount,
                      List<String> missing, int missingCount, String note, List<CriterionRow> criteria, EvidenceState evidenceState) {}
    public record Report(int schemaVersion, List<Row> requirements, String repairStatus, List<String> repaired) {}
    public record VerificationCounts(int provided, int accepted, int vetoed, int missingFacts, int quoteMismatch,
                                     int unknownRequirement, int unknownCriterion) {}
    public record EvidenceCheck(int provided, int accepted, String reason) {}
    public record Verification(List<RequirementReview> reviews, VerificationCounts counts, Map<String, EvidenceCheck> checks) {}

    public static List<RequirementReview> verified(List<BuildRequirement> requirements, List<RequirementReview> reviews,
                                                  Map<String, String> facts, Set<String> vetoes) {
        return verify(requirements, reviews, facts, vetoes).reviews();
    }

    public static Verification verify(List<BuildRequirement> requirements, List<RequirementReview> reviews,
                                      Map<String, String> facts, Set<String> vetoes) {
        var byId = new HashMap<String, BuildRequirement>(); requirements.forEach(r -> byId.put(r.id(), r));
        int[] count = new int[7];
        var checks = new LinkedHashMap<String, EvidenceCheck>();
        var accepted = new ArrayList<RequirementReview>();
        for (var review : reviews) {
            if (!byId.containsKey(review.id())) {
                int size = review.evidence().size() + review.criteria().stream().mapToInt(c -> c.evidence().size()).sum();
                count[0] += size; count[5] += size; continue;
            }
            var criteria = new ArrayList<CriterionReview>();
            for (var child : review.criteria()) {
                if (byId.get(review.id()).criteria().stream().noneMatch(c -> c.source().equals(child.source()))) {
                    count[0] += child.evidence().size(); count[6] += child.evidence().size(); continue;
                }
                String key = byId.get(review.id()).criteria().stream().filter(c -> c.source().equals(child.source()))
                        .findFirst().orElseThrow().key(review.id());
                criteria.add(new CriterionReview(child.source(), checked(key, child.evidence(), facts, vetoes, count, checks), child.gap()));
            }
            accepted.add(new RequirementReview(review.id(), checked(review.id(), review.evidence(), facts, vetoes, count, checks), review.gap(), criteria));
        }
        return new Verification(List.copyOf(accepted), new VerificationCounts(count[0], count[1], count[2], count[3], count[4], count[5], count[6]), Map.copyOf(checks));
    }
    private static List<RequirementReview.Contribution> checked(String key, List<RequirementReview.Contribution> evidence,
            Map<String, String> facts, Set<String> vetoes, int[] total, Map<String, EvidenceCheck> checks) {
        int[] count = new int[7];
        var accepted = quoted(evidence, facts, vetoes, count);
        for (int i = 0; i < count.length; i++) total[i] += count[i];
        String reason = !accepted.isEmpty() ? "VERIFIED" : evidence.isEmpty() ? "NO_EVIDENCE"
                : count[4] > 0 ? "QUOTE_MISMATCH" : count[3] > 0 ? "FACTS_UNAVAILABLE" : "VETOED";
        checks.put(key, new EvidenceCheck(evidence.size(), accepted.size(), reason));
        return accepted;
    }

    private static boolean quoteMatches(String fact, String quote) {
        String normalizedQuote = quote.replaceAll("(?U)\\s+", " ").trim();
        if (normalizedQuote.replace(" ", "").length() < 4) return false;
        return fact.contains(quote) || fact.replaceAll("(?U)\\s+", " ").trim().contains(normalizedQuote);
    }
    private static List<RequirementReview.Contribution> quoted(List<RequirementReview.Contribution> evidence, Map<String, String> facts, Set<String> vetoes, int[] count) {
        var accepted = new ArrayList<RequirementReview.Contribution>();
        for (var item : evidence) {
            count[0]++;
            if (vetoes.contains(item.slug())) count[2]++;
            else if (!facts.containsKey(item.slug())) count[3]++;
            else if (!quoteMatches(facts.get(item.slug()), item.quote())) count[4]++;
            else { count[1]++; accepted.add(item); }
        }
        return List.copyOf(accepted);
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
        return assess(requirements, reviews, graph, selected, identities, metadata, repairStatus, repaired, Map.of());
    }
    public static Report assess(List<BuildRequirement> requirements, List<RequirementReview> reviews,
                                DependencyGraph graph, Set<String> selected, Map<String, String> identities,
                                Map<String, PackAddonPolicy.Project> metadata, String repairStatus, List<String> repaired,
                                Map<String, EvidenceCheck> checks) {
        return assess(requirements, reviews, graph, selected, identities, metadata, repairStatus, repaired, checks, true);
    }
    private static Report assess(List<BuildRequirement> requirements, List<RequirementReview> reviews,
                                DependencyGraph graph, Set<String> selected, Map<String, String> identities,
                                Map<String, PackAddonPolicy.Project> metadata, String repairStatus, List<String> repaired,
                                Map<String, EvidenceCheck> checks, boolean truncate) {
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
            var pendingVersions = new ArrayList<String>();
            for (String reference : r.projects()) {
                String slug = identities.getOrDefault(reference, reference);
                if (selected.contains(slug) && graph.selectedVersions.containsKey(slug))
                    items.put(slug, new Item(slug, "点名项目，已选兼容版本", "", "PROJECT_IDENTITY"));
                else if (selected.contains(slug)) pendingVersions.add(slug);
                else missing.add(slug);
            }
            var review = byId.get(r.id());
            if (review != null) for (var e : review.evidence()) {
                if (selected.contains(e.slug()) && graph.selectedVersions.containsKey(e.slug()))
                    items.put(e.slug(), new Item(e.slug(), e.purpose(), e.quote(),
                            items.containsKey(e.slug()) ? items.get(e.slug()).evidenceType() : "REVIEW_QUOTE"));
                else if (selected.contains(e.slug())) pendingVersions.add(e.slug());
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
                                + (r.kind() == BuildRequirement.Kind.PACK_INTEGRATIONS ? "包内内容联动" : "附属关系"), "", "VERSION_RELATION"));
                    }
                }
                if (!found) missingCores.add(core);
            }
            String gap = review == null ? "" : review.gap();
            boolean gameplay = r.kind() != BuildRequirement.Kind.PROJECTS && (r.kind() == BuildRequirement.Kind.CAPABILITY || !r.capabilities().isEmpty()
                    || (!addons && !r.searches().isEmpty()) || (r.kind() == BuildRequirement.Kind.GENERAL && review != null && !review.evidence().isEmpty()));
            boolean semanticEvidence = review != null && review.evidence().stream()
                    .anyMatch(e -> selected.contains(e.slug()) && graph.selectedVersions.containsKey(e.slug()));
            String status;
            String note;
            EvidenceState evidenceState = new EvidenceState("VERIFIED", List.of());
            if (!missing.isEmpty()) {
                status = items.isEmpty() ? "NOT_RETAINED" : "PARTIAL";
                note = "部分点名项目未在最终兼容清单中保留";
                evidenceState = new EvidenceState("PROJECT_MISSING", missing);
            } else if (!pendingVersions.isEmpty()) {
                status = items.isEmpty() ? "UNVERIFIED" : "PARTIAL";
                note = "项目已入清单，兼容版本仍待核实";
                evidenceState = new EvidenceState("VERSION_PENDING", pendingVersions);
            } else if (!missingCores.isEmpty()) {
                status = items.isEmpty() ? "UNVERIFIED" : "PARTIAL";
                note = "附属或包内联动关系仍待核实";
                evidenceState = new EvidenceState("RELATION_PENDING", missingCores);
            } else if (gameplay && !semanticEvidence) {
                status = items.isEmpty() ? "UNVERIFIED" : "PARTIAL";
                var check = checks.get(r.id());
                String reason = review != null && !review.evidence().isEmpty() ? "NOT_SELECTED"
                        : check != null && !check.reason().equals("VERIFIED") ? check.reason()
                        : check != null ? "PURPOSE_PENDING" : review == null ? "NO_REVIEW" : "NO_EVIDENCE";
                evidenceState = new EvidenceState(reason, List.of());
                note = switch (reason) {
                    case "NOT_SELECTED" -> "此前关联的候选未进入最终清单，当前用途待核实";
                    case "QUOTE_MISMATCH" -> "审核引用未能与项目说明对应，用途待核实";
                    case "FACTS_UNAVAILABLE" -> "项目资料暂不完整，用途待核实";
                    case "VETOED" -> "此前关联的候选已被否决，当前用途待核实";
                    default -> "本项目标的用途仍待核实";
                };
            } else if (!gap.isBlank() && (gameplay || addons)) {
                status = items.isEmpty() ? "UNVERIFIED" : "PARTIAL";
                note = "审核尚待复核：" + gap;
                evidenceState = new EvidenceState("REVIEW_GAP", List.of());
                if (review.evidence().stream().anyMatch(e -> repaired.contains(e.slug())))
                    note += "；本轮已补入关联候选，此审核意见尚未重新裁决";
            } else if (gameplay || addons) {
                status = "SUPPORTING_EVIDENCE";
                note = addons ? "已取得当前版本的附属或联动关系依据" : "已保留相关项目，并取得用途依据";
            } else if (!r.projects().isEmpty()) {
                status = "VERIFIED_PROJECTS";
                note = "点名项目及兼容版本已保留";
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
                    var child = assess(List.of(target), childReviews, graph, selected, identities, metadata, repairStatus, repaired, checks, false).requirements();
                    if (child.isEmpty()) continue;
                    var row = child.getFirst();
                    row.mods().forEach(m -> items.put(m.slug(), m));
                    criteria.add(new CriterionRow(row.id(), row.source(), row.status(), row.mods().stream().limit(24).toList(), row.modCount(),
                            row.missing().stream().limit(24).toList(), row.missingCount(), row.note(), row.evidenceState()));
                }
                if ((!missing.isEmpty() || !missingCores.isEmpty()) && !items.isEmpty()) status = "PARTIAL";
                var lacking = criteria.stream().filter(c -> lacking(c.status())).toList();
                if (missing.isEmpty() && missingCores.isEmpty() && pendingVersions.isEmpty()) {
                    if (!lacking.isEmpty()) {
                        status = items.isEmpty() ? "UNVERIFIED" : "PARTIAL";
                        boolean contentMissing = lacking.stream().anyMatch(c -> c.evidenceState().code().equals("PROJECT_MISSING"));
                        evidenceState = new EvidenceState(contentMissing ? "PROJECT_MISSING" : "PURPOSE_PENDING",
                                lacking.stream().flatMap(c -> c.evidenceState().projects().stream()).toList());
                        note = contentMissing ? "有点名项目未保留，请查看子项" : "还有 " + lacking.size() + " 项目标待核实，请查看子项";
                    } else if (criteria.stream().anyMatch(c -> c.status().equals("SUPPORTING_EVIDENCE"))) {
                        status = "SUPPORTING_EVIDENCE";
                        note = "各项目标已有对应依据";
                        evidenceState = new EvidenceState("VERIFIED", List.of());
                    } else if (criteria.stream().anyMatch(c -> c.status().equals("VERIFIED_PROJECTS"))) {
                        status = "VERIFIED_PROJECTS";
                        note = "有效子目标的点名项目已分别保留";
                    } else { status = "CONTEXT"; note = "有效子目标仅包含上下文或已解除项"; }
                }
            }
            rows.add(new Row(r.id(), r.source(), status, items.values().stream().limit(truncate ? 24 : Long.MAX_VALUE).toList(), items.size(),
                    missing.stream().limit(truncate ? 24 : Long.MAX_VALUE).toList(), missing.size(), note, List.copyOf(criteria), evidenceState));
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
