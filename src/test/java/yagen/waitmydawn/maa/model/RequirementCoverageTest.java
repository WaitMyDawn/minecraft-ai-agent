package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.*;
import yagen.waitmydawn.maa.service.RequirementPlanning;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementCoverageTest {
    @Test void discardedQuotesExposeMutuallyExclusiveCausesWithoutRelaxingEvidenceChecks() {
        var r = requirement("自动化", BuildRequirement.Kind.CAPABILITY, List.of());
        var values = List.of(new RequirementReview.Contribution("kept", "Adds automation", "自动化"),
                new RequirementReview.Contribution("vetoed", "Adds automation", "自动化"),
                new RequirementReview.Contribution("missing", "Adds automation", "自动化"),
                new RequirementReview.Contribution("paraphrase", "Automatic machine networks", "自动化"));
        var verified = RequirementCoverage.verify(List.of(r), List.of(new RequirementReview(r.id(), values, "")),
                Map.of("kept", "Adds automation", "vetoed", "Adds automation", "paraphrase", "Adds automation"), Set.of("vetoed"));
        assertEquals(new RequirementCoverage.VerificationCounts(4, 1, 1, 1, 1, 0, 0), verified.counts());
        assertEquals(List.of("kept"), verified.reviews().getFirst().evidence().stream().map(RequirementReview.Contribution::slug).toList());
    }
    private BuildRequirement requirement(String source, BuildRequirement.Kind kind, List<String> projects) {
        return new BuildRequirement(RequirementPlanning.anchors(source).keySet().iterator().next(), source,
                kind, projects, List.of(), List.of(), kind == BuildRequirement.Kind.CAPABILITY
                ? List.of(new BuildIntent.SearchIntent("technology", 100, List.of("automation"))) : List.of());
    }
    private DependencyGraph graph(String... slugs) {
        var graph = new DependencyGraph();
        for (String slug : slugs) {
            graph.allSlugs.add(slug);
            graph.selectedVersions.put(slug, new ObjectMapper().createObjectNode().put("id", slug + "v").put("project_id", slug));
        }
        return graph;
    }
    private RequirementCoverage.Row row(BuildRequirement requirement, List<RequirementReview> reviews, DependencyGraph graph) {
        return RequirementCoverage.assess(List.of(requirement), reviews, graph, graph.allSlugs, Map.of(), Map.of(), "NONE", List.of()).requirements().getFirst();
    }
    @Test void exactIdentityIsCheckedAfterFinalSelectionAndRequiresAVersion() {
        var r = requirement("包含机械与存储核心", BuildRequirement.Kind.PROJECTS, List.of("core", "storage"));
        assertEquals("PARTIAL", row(r, List.of(), graph("core")).status());
        assertEquals(List.of("storage"), row(r, List.of(), graph("core")).missing());
        assertEquals("VERIFIED_PROJECTS", row(r, List.of(), graph("core", "storage")).status());
        var single = requirement("包含核心", BuildRequirement.Kind.PROJECTS, List.of("core"));
        var unresolved = graph("core"); unresolved.selectedVersions.clear();
        var pending = row(single, List.of(), unresolved);
        assertEquals("UNVERIFIED", pending.status());
        assertTrue(pending.missing().isEmpty());
        assertEquals("VERSION_PENDING", pending.evidenceState().code());
        assertEquals(List.of("core"), pending.evidenceState().projects());
    }
    @Test void whitespaceNormalizationPreservesTextMeaningAndReportsRejectedQuotes() {
        var r = requirement("自动化", BuildRequirement.Kind.CAPABILITY, List.of());
        var facts = Map.of("factory", "Supports\nfactory\u00a0automation, but not logistics.");
        var good = new RequirementReview(r.id(), List.of(new RequirementReview.Contribution("factory", "factory automation", "自动化")), "");
        assertEquals(1, RequirementCoverage.verify(List.of(r), List.of(good), facts, Set.of()).counts().accepted());
        assertThrows(IllegalArgumentException.class, () -> new RequirementReview.Contribution("factory", "    \n  ", "自动化"));
        for (String quote : List.of("supports factory automation", "Supports factory automation and logistics", "but logistics")) {
            var bad = new RequirementReview(r.id(), List.of(new RequirementReview.Contribution("factory", quote, "自动化")), "");
            var verification = RequirementCoverage.verify(List.of(r), List.of(bad), facts, Set.of());
            assertEquals(0, verification.counts().accepted(), quote);
            var report = RequirementCoverage.assess(List.of(r), verification.reviews(), graph("factory"), Set.of("factory"),
                    Map.of(), Map.of(), "NONE", List.of(), verification.checks());
            assertEquals("QUOTE_MISMATCH", report.requirements().getFirst().evidenceState().code());
            assertTrue(report.requirements().getFirst().missing().isEmpty());
        }
    }
    @Test void namedIdentityVerificationDoesNotRequireUnusedCandidateOrLaunchEvidence() {
        var r = requirement("包含核心", BuildRequirement.Kind.PROJECTS, List.of("core"));
        var review = new RequirementReview(r.id(), List.of(), "已在包内，但候选池无对应项目，启动未验证");
        assertEquals("VERIFIED_PROJECTS", row(r, List.of(review), graph("core")).status());
        assertEquals("NOT_RETAINED", row(r, List.of(review), graph()).status());
    }
    @Test void arbitraryAutomationGoalUsesQuotedFactsRatherThanCategoryOrRetrievalHits() {
        var r = requirement("工厂自动化和物流", BuildRequirement.Kind.CAPABILITY, List.of());
        var review = new RequirementReview(r.id(), List.of(new RequirementReview.Contribution("factory", "factory automation", "提供工厂自动化")), "");
        var verified = RequirementCoverage.verified(List.of(r), List.of(review), Map.of("factory", "Supports factory automation and logistics"), Set.of());
        var result = row(r, verified, graph("factory"));
        assertEquals("SUPPORTING_EVIDENCE", result.status());
        assertEquals("factory automation", result.mods().getFirst().quote());
        assertEquals("UNVERIFIED", row(r, verified, graph("unrelated-tech")).status());
        assertEquals("UNVERIFIED", row(r, List.of(), graph("factory")).status());
    }
    @Test void inventedQuotesForeignRequirementsAndVetoesCannotBecomeEvidence() {
        var r = requirement("远程物流", BuildRequirement.Kind.CAPABILITY, List.of());
        var review = new RequirementReview(r.id(), List.of(new RequirementReview.Contribution("factory", "invented quote", "提供物流")), "");
        assertTrue(RequirementCoverage.verified(List.of(r), List.of(review), Map.of("factory", "actual description"), Set.of()).getFirst().evidence().isEmpty());
        assertTrue(RequirementCoverage.verified(List.of(r), List.of(review), Map.of("factory", "invented quote"), Set.of("factory")).getFirst().evidence().isEmpty());
        assertTrue(RequirementCoverage.verified(List.of(), List.of(review), Map.of("factory", "invented quote"), Set.of()).isEmpty());
    }
    @Test void aRichCompositeGoalRemainsPartialWhenReviewerReportsMissingDimension() {
        var r = requirement("丰富职业与战斗体验", BuildRequirement.Kind.CAPABILITY, List.of());
        var review = new RequirementReview(r.id(), List.of(new RequirementReview.Contribution("combat", "combat system", "提供战斗系统")), "尚缺职业玩法依据");
        assertEquals("PARTIAL", row(r, List.of(review), graph("combat")).status());
        assertTrue(row(r, List.of(review), graph("combat")).note().contains("职业"));
    }
    @Test void packIntegrationNeedsCurrentCoreAndContentRelationNotALibrary() {
        var r0 = requirement("核心的包内联动附属", BuildRequirement.Kind.PACK_INTEGRATIONS, List.of("core"));
        var r = new BuildRequirement(r0.id(), r0.source(), r0.kind(), r0.projects(), List.of(), List.of("core"));
        var graph = graph("core", "partner", "addon");
        var deps = ((tools.jackson.databind.node.ObjectNode) graph.selectedVersions.get("addon")).putArray("dependencies");
        deps.addObject().put("project_id", "core").put("dependency_type", "required");
        deps.addObject().put("project_id", "partner").put("dependency_type", "optional");
        var metadata = Map.of("core", new PackAddonPolicy.Project("core", "core", 10L, Set.of("magic"), "Core"),
                "partner", new PackAddonPolicy.Project("partner", "partner", 20L, Set.of("technology"), "Partner"));
        var report = RequirementCoverage.assess(List.of(r), List.of(), graph, graph.allSlugs, Map.of(), metadata, "NONE", List.of());
        assertEquals("SUPPORTING_EVIDENCE", report.requirements().getFirst().status());
        assertTrue(report.requirements().getFirst().mods().stream().anyMatch(i -> i.slug().equals("addon")));
        var library = new HashMap<>(metadata);
        library.put("partner", new PackAddonPolicy.Project("partner", "partner", 20L, Set.of("library"), "Partner API"));
        assertEquals("PARTIAL", RequirementCoverage.assess(List.of(r), List.of(), graph, graph.allSlugs, Map.of(), library, "NONE", List.of()).requirements().getFirst().status());
        ((tools.jackson.databind.node.ObjectNode) deps.get(0)).put("version_id", "oldCoreVersion");
        assertEquals("PARTIAL", RequirementCoverage.assess(List.of(r), List.of(), graph, graph.allSlugs, Map.of(), metadata, "NONE", List.of()).requirements().getFirst().status());
    }
    @Test void repairProposalsUseOnlyAllowedUnretainedEvidenceAndHaveThreeItemBound() {
        var r = requirement("精细物流", BuildRequirement.Kind.CAPABILITY, List.of());
        var evidence = List.of("one", "two", "three", "four", "vetoed").stream()
                .map(s -> new RequirementReview.Contribution(s, "logistics", "支持物流")).toList();
        var reviews = List.of(new RequirementReview(r.id(), evidence, ""));
        var report = RequirementCoverage.assess(List.of(r), reviews, graph(), Set.of(), Map.of(), Map.of(), "NONE", List.of());
        assertEquals(List.of("one", "two", "three"), RequirementCoverage.repairs(report, reviews, Set.of("one", "two", "three", "four"), Set.of()));
    }
    @Test void reviewEvidenceSurvivesTransportSelectionAndLegacyResponsesStayUnverified() {
        var r = requirement("自动化", BuildRequirement.Kind.CAPABILITY, List.of());
        var codec = new AgentContractCodec();
        var review = new RequirementReview(r.id(), List.of(new RequirementReview.Contribution("factory", "automation", "提供自动化")), "");
        var decision = new ReviewDecision(1, List.of("factory"), List.of(), false, List.of(review));
        assertEquals(List.of(review), codec.readReviewResponse(codec.write(decision)).select(Set.of("factory"), 1).decision().requirements());
        assertTrue(codec.readReviewResponse("{\"schemaVersion\":1,\"approved\":[],\"rejected\":[],\"allowBackfill\":false}").requirements().isEmpty());
        assertThrows(AgentContractCodec.ValidationException.class, () -> codec.readReviewResponse(codec.write(decision).replace("\"purpose\"", "\"unexpected\"")));
    }
    @Test void anUnresolvedAddonCoreCannotBeReportedAsSupported() {
        var r = requirement("扩展附属", BuildRequirement.Kind.ADDONS, List.of());
        assertEquals("UNVERIFIED", row(r, List.of(), graph("unrelated")).status());
    }
}
