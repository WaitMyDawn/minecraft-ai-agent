package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.*;
import yagen.waitmydawn.maa.service.RequirementPlanning;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CriteriaCoverageTest {
    private RequirementCriterion purpose(String source, String category, String keyword) {
        return new RequirementCriterion(source, BuildRequirement.Kind.CAPABILITY, List.of(), List.of(),
                List.of(new BuildIntent.SearchIntent(category, 100, List.of(keyword))));
    }
    private BuildRequirement requirement(String source, List<RequirementCriterion> criteria) {
        var projects = criteria.stream().flatMap(c -> c.projects().stream()).distinct().toList();
        return new BuildRequirement(RequirementPlanning.anchors(source).keySet().iterator().next(), source, BuildRequirement.Kind.CAPABILITY,
                projects, RequirementPlanning.capabilities(source), List.of(), List.of(), SelectionPreferences.DEFAULT, criteria);
    }
    private RequirementReview.Contribution quote(String slug, String quote) { return new RequirementReview.Contribution(slug, quote, "支持该子目标"); }
    private DependencyGraph graph(String... slugs) {
        var graph = new DependencyGraph();
        for (String slug : slugs) { graph.allSlugs.add(slug); graph.selectedVersions.put(slug,
                new ObjectMapper().createObjectNode().put("id", slug + "V").put("project_id", slug)); }
        return graph;
    }
    private RequirementCoverage.Report report(BuildRequirement r, RequirementReview review, DependencyGraph graph) {
        return RequirementCoverage.assess(List.of(r), List.of(review), graph, graph.allSlugs, Map.of(), Map.of(), "NONE", List.of());
    }
    @Test void performanceEvidenceDoesNotCoverModernUiOrFixesAndNamedUiDoesNotNeedGameplayQuotes() {
        var r = requirement("优化、现代化UI和修复", List.of(purpose("优化", "optimization", "performance"),
                new RequirementCriterion("现代化UI", BuildRequirement.Kind.PROJECTS, List.of("modern-ui"), List.of(), List.of()),
                purpose("修复", "utility", "bug fixes")));
        var review = new RequirementReview(r.id(), List.of(), "", List.of(
                new CriterionReview("优化", List.of(quote("renderer", "rendering optimization")), ""),
                new CriterionReview("现代化UI", List.of(), ""), new CriterionReview("修复", List.of(), "尚缺漏洞修复依据")));
        var partial = report(r, review, graph("renderer", "modern-ui")).requirements().getFirst();
        assertEquals("PARTIAL", partial.status());
        assertEquals(List.of("SUPPORTING_EVIDENCE", "VERIFIED_PROJECTS", "UNVERIFIED"), partial.criteria().stream().map(RequirementCoverage.CriterionRow::status).toList());
        assertEquals("PARTIAL", report(r, review, graph("renderer", "hud")).requirements().getFirst().status());
        var all = new RequirementReview(r.id(), List.of(), "", List.of(review.criteria().get(0), review.criteria().get(1),
                new CriterionReview("修复", List.of(quote("fix", "fixes game bugs")), "")));
        assertEquals("SUPPORTING_EVIDENCE", report(r, all, graph("renderer", "modern-ui", "fix")).requirements().getFirst().status());
    }
    @Test void arbitraryGoalsRemainIndependentAndMissingChildCanBeRepairedDespiteAnotherChildBeingSupported() {
        var r = requirement("工厂自动化和仓储", List.of(purpose("工厂自动化", "technology", "automation"), purpose("仓储", "storage", "storage")));
        var review = new RequirementReview(r.id(), List.of(), "", List.of(
                new CriterionReview("工厂自动化", List.of(quote("factory", "factory automation")), ""),
                new CriterionReview("仓储", List.of(quote("storage", "mass storage")), "")));
        var verified = RequirementCoverage.verified(List.of(r), List.of(review), Map.of("factory", "factory automation", "storage", "mass storage"), Set.of());
        var before = report(r, verified.getFirst(), graph("factory"));
        assertEquals("PARTIAL", before.requirements().getFirst().status());
        assertEquals(List.of("storage"), RequirementCoverage.repairs(before, verified, Set.of("storage"), Set.of("factory")));
        assertEquals(List.of("仓储"), RequirementCoverage.missingTargets(List.of(r), before).stream().map(BuildRequirement::source).toList());
        assertEquals("SUPPORTING_EVIDENCE", report(r, verified.getFirst(), graph("factory", "storage")).requirements().getFirst().status());
    }
    @Test void foreignCriteriaInventedQuotesAndVetoesCannotBecomeEvidenceAndOmittedCriteriaAreInvalid() {
        var r = requirement("烹饪和建筑装饰", List.of(purpose("烹饪", "food", "cooking"), purpose("建筑装饰", "decoration", "decoration")));
        var invalid = new RequirementReview(r.id(), List.of(), "", List.of(new CriterionReview("烹饪", List.of(quote("cook", "invented quote")), ""),
                new CriterionReview("不在原文", List.of(quote("cook", "cooking system")), "")));
        assertThrows(IllegalArgumentException.class, () -> RequirementCoverage.validateReviews(List.of(r), List.of(invalid)));
        var verified = RequirementCoverage.verified(List.of(r), List.of(invalid), Map.of("cook", "cooking system"), Set.of());
        assertEquals(1, verified.getFirst().criteria().size()); assertTrue(verified.getFirst().criteria().getFirst().evidence().isEmpty());
        var complete = new RequirementReview(r.id(), List.of(), "", List.of(new CriterionReview("烹饪", List.of(quote("cook", "cooking system")), ""), new CriterionReview("建筑装饰", List.of(), "")));
        assertDoesNotThrow(() -> RequirementCoverage.validateReviews(List.of(r), List.of(complete)));
        assertTrue(RequirementCoverage.verified(List.of(r), List.of(complete), Map.of("cook", "cooking system"), Set.of("cook")).getFirst().criteria().getFirst().evidence().isEmpty());
    }
    @Test void missingTargetsShareThreeItemBudgetAndIncrementalReviewPreservesOtherChildren() {
        var r = requirement("仓储和烹饪", List.of(purpose("仓储", "storage", "storage"), purpose("烹饪", "food", "cooking")));
        var review = new RequirementReview(r.id(), List.of(), "", List.of(new CriterionReview("仓储", List.of(quote("a", "storage system"), quote("b", "storage system")), ""),
                new CriterionReview("烹饪", List.of(quote("c", "cooking system")), "")));
        assertEquals(List.of("a", "c", "b"), RequirementCoverage.repairs(report(r, review, graph()), List.of(review), Set.of("a", "b", "c"), Set.of()));
        var update = new RequirementReview(r.id(), List.of(), "", List.of(new CriterionReview("烹饪", List.of(quote("d", "cooking system")), "")));
        var merged = RequirementCoverage.merge(List.of(review), List.of(update)).getFirst();
        assertEquals(review.criteria().getFirst(), merged.criteria().getFirst());
        assertEquals("d", merged.criteria().get(1).evidence().getFirst().slug());
    }
    @Test void displayTruncationDoesNotReduceActualParentOrChildCounts() {
        var names = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "mod" + i).toList();
        var r = requirement("包含项目", List.of(new RequirementCriterion("包含项目", BuildRequirement.Kind.PROJECTS, names, List.of(), List.of())));
        var review = new RequirementReview(r.id(), List.of(), "", List.of(new CriterionReview("包含项目", List.of(), "")));
        var result = report(r, review, graph(names.toArray(String[]::new))).requirements().getFirst();
        assertEquals(40, result.modCount()); assertEquals(24, result.mods().size());
        assertEquals(40, result.criteria().getFirst().modCount()); assertEquals(24, result.criteria().getFirst().mods().size());
        var missing = report(r, review, graph()).requirements().getFirst();
        assertEquals(40, missing.missingCount()); assertEquals(40, missing.criteria().getFirst().missingCount());
    }
}
