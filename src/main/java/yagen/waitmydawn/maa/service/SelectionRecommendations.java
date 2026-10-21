package yagen.waitmydawn.maa.service;

import yagen.waitmydawn.maa.model.ContentOverlap;
import yagen.waitmydawn.maa.model.AssemblySnapshot;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** 卡片默认值与自动选择共用；跨组出现的项目采用一致状态，保护项优先保留。 */
public final class SelectionRecommendations {
    private SelectionRecommendations() {}

    public static Map<String, ReplacementSelections.Selection> replacements(List<RequiredModAlternatives.Group> groups) {
        var result = new LinkedHashMap<String, ReplacementSelections.Selection>();
        for (var group : groups) {
            if (!group.verified() || group.candidates().isEmpty()) return Map.of();
            result.put(group.original(), new ReplacementSelections.Selection(List.of(group.candidates().getFirst().slug()), false, ""));
        }
        return Collections.unmodifiableMap(result);
    }

    public static Map<String, ReplacementSelections.Selection> combinations(List<ContentOverlap.Group> groups) {
        var selected = new LinkedHashSet<String>();
        groups.forEach(g -> g.candidates().stream().filter(c -> c.recommended() || c.protectedSelection()).forEach(c -> selected.add(c.slug())));
        for (var group : groups) {
            if (!group.verified() || group.candidates().isEmpty()) return Map.of();
            if (group.candidates().stream().noneMatch(c -> selected.contains(c.slug()))) selected.add(group.candidates().getFirst().slug());
        }
        var result = new LinkedHashMap<String, ReplacementSelections.Selection>();
        groups.forEach(g -> result.put(g.original(), new ReplacementSelections.Selection(
                g.candidates().stream().map(ContentOverlap.Candidate::slug).filter(selected::contains).toList(), false, "")));
        return Collections.unmodifiableMap(result);
    }

    /** 默认去重不能丢弃已核实的需求贡献；用户仍可在卡片中主动取消并看到更新后的缺口。 */
    static List<ContentOverlap.Group> preserveRequirements(List<ContentOverlap.Group> groups, JsonNode report, AssemblySnapshot assembly) {
        if (groups.isEmpty() || report == null) return groups;
        var allGrouped = new HashSet<String>();
        groups.forEach(g -> g.candidates().forEach(c -> allGrouped.add(c.slug())));
        var selected = new HashSet<>(assembly.versions().keySet()); selected.removeAll(allGrouped);
        combinations(groups).values().forEach(choice -> selected.addAll(choice.slugs()));
        var targets = new ArrayList<JsonNode>();
        for (var row : report.path("requirements")) {
            if (row.path("criteria").isEmpty()) targets.add(row);
            else row.path("criteria").forEach(targets::add);
        }
        for (var target : targets) {
            if (target.path("status").asText().equals("CONTEXT")) continue;
            var quantity = target.path("quantity");
            if (quantity.isObject() && !quantity.path("complete").asBoolean()) continue;
            int minimum = quantity.isObject() ? quantity.path("min").asInt(0) : 1;
            if (minimum <= 0) continue;
            var proofs = new TreeMap<String, Set<String>>();
            for (var proof : report.path("ledger")) {
                String slug = proof.path("slug").asText();
                if (!target.path("id").asText().equals(proof.path("targetId").asText())
                        || !proof.path("status").asText().equals("VERIFIED")
                        || !proof.path("versionId").asText().equals(assembly.versions().get(slug))) continue;
                if (quantity.isObject() && quantity.path("projects").valueStream().noneMatch(p -> p.asText().equals(slug))) continue;
                var contributors = new HashSet<String>(); contributors.add(slug);
                proof.path("partners").forEach(partner -> contributors.add(partner.asText()));
                if (assembly.versions().keySet().containsAll(contributors)) proofs.put(slug, contributors);
            }
            var ranked = proofs.entrySet().stream().sorted(Comparator
                    .<Map.Entry<String, Set<String>>>comparingInt(p -> (int) p.getValue().stream().filter(s -> !selected.contains(s)).count())
                    .thenComparing(Map.Entry::getKey)).toList();
            for (var proof : ranked) {
                if (proofs.values().stream().filter(selected::containsAll).count() >= minimum) break;
                selected.addAll(proof.getValue());
            }
        }
        return groups.stream().map(g -> new ContentOverlap.Group(g.original(), g.title(), g.explanation(),
                g.candidates().stream().map(c -> new ContentOverlap.Candidate(c.slug(), c.title(), c.description(), c.iconUrl(), c.downloads(),
                        c.relation(), c.evidence(), c.scope(), c.scopeEvidence(), c.protectedSelection(), selected.contains(c.slug()),
                        c.compatibilityNote(), c.scopeNote())).toList(), g.verified())).toList();
    }
}
