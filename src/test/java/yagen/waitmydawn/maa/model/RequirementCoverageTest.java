package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.*;
import yagen.waitmydawn.maa.service.RequirementPlanning;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementCoverageTest {
    @Test void capturedSelectedCoreDescriptionsDoNotDependOnCriticRepeatingThem() {
        var base = requirement("丰富下界和末地生态", BuildRequirement.Kind.CAPABILITY, List.of());
        var children = List.of(new RequirementCriterion("丰富下界", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()),
                new RequirementCriterion("末地生态", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()));
        var r = new BuildRequirement(base.id(), base.source(), base.kind(), List.of(), List.of(), List.of(), List.of(), base.preferences(), children);
        var graph = graph("incendium", "nullscape");
        var facts = Map.of("incendium", "A nether biome overhaul combined with challenging structures to conquer, unique weapons to obtain, and tricky mobs to defeat",
                "nullscape", "Transforms the boring Vanilla end into an alien dimension with the most surreal terrain imaginable");
        var report = RequirementCoverage.assess(List.of(r), List.of(), graph, graph.allSlugs, Map.of(), Map.of(), "NONE", List.of(), Map.of(), facts);
        var row = report.requirements().getFirst();
        assertEquals("SUPPORTING_EVIDENCE", row.status());
        assertEquals(List.of("incendium"), row.criteria().getFirst().evidenceState().generation().getFirst().projects());
        assertEquals(List.of("nullscape"), row.criteria().getLast().evidenceState().generation().getFirst().projects());
        assertTrue(row.criteria().stream().allMatch(c -> c.mods().stream().allMatch(m -> m.evidenceType().equals("PROJECT_GENERATION"))));
    }
    @Test void retainedProjectFactsCanSupplyGenerationProofOmittedFromReviewWithoutInventingReviewQuotes() {
        var r = requirement("丰富下界生态", BuildRequirement.Kind.CAPABILITY, List.of());
        var review = new RequirementReview(r.id(), List.of(new RequirementReview.Contribution("local", "Adds Nether structures", "局部结构")), "");
        var facts = Map.of("core", "Overhauls Nether terrain with many new biomes", "local", "Adds Nether structures");
        var verified = RequirementCoverage.verify(List.of(r), List.of(review), facts, Set.of());
        var graph = graph("core", "local");
        var row = RequirementCoverage.assess(List.of(r), verified.reviews(), graph, graph.allSlugs, Map.of(), Map.of(), "NONE", List.of(), verified.checks(), facts).requirements().getFirst();
        assertEquals("SUPPORTING_EVIDENCE", row.status());
        assertEquals(List.of("core"), row.evidenceState().generation().getFirst().projects());
        var proof = row.mods().stream().filter(m -> m.slug().equals("core")).findFirst().orElseThrow();
        assertEquals("PROJECT_GENERATION", proof.evidenceType()); assertEquals(facts.get("core"), proof.quote());
        assertEquals(1, verified.counts().accepted());
        assertEquals(List.of("local"), verified.reviews().getFirst().evidence().stream().map(RequirementReview.Contribution::slug).toList());
    }
    @Test void projectFactsCannotClearReviewGapsOrProveAnUnrelatedGameplayGoal() {
        var r = requirement("丰富下界生态", BuildRequirement.Kind.CAPABILITY, List.of());
        var review = new RequirementReview(r.id(), List.of(), "生物生态丰富度仍需核对");
        var graph = graph("core"); var facts = Map.of("core", "Overhauls Nether terrain with many new biomes");
        var row = RequirementCoverage.assess(List.of(r), List.of(review), graph, graph.allSlugs, Map.of(), Map.of(), "NONE", List.of(), Map.of(), facts).requirements().getFirst();
        assertEquals("PARTIAL", row.status()); assertEquals("REVIEW_GAP", row.evidenceState().code());
        var unrelated = requirement("丰富物流自动化", BuildRequirement.Kind.CAPABILITY, List.of());
        var pending = RequirementCoverage.assess(List.of(unrelated), List.of(), graph, graph.allSlugs, Map.of(), Map.of(), "NONE", List.of(), Map.of(), facts).requirements().getFirst();
        assertEquals("UNVERIFIED", pending.status()); assertTrue(pending.mods().isEmpty());
    }
    @Test void projectGenerationProofNeedsRetainedVersionAndUnambiguousRealmFacts() {
        var r = requirement("丰富下界生态", BuildRequirement.Kind.CAPABILITY, List.of());
        for (String fact : List.of("Adds 50 biomes to the Overworld, Nether and End", "Overhauls Nether fortresses", "Doubles the Nether build height", "Compatibility with a Nether terrain overhaul", "Creates skyblock in the Nether with many biomes")) {
            var graph = graph("core");
            var row = RequirementCoverage.assess(List.of(r), List.of(), graph, graph.allSlugs, Map.of(), Map.of(), "NONE", List.of(), Map.of(), Map.of("core", fact)).requirements().getFirst();
            assertEquals("UNVERIFIED", row.status(), fact); assertTrue(row.mods().isEmpty(), fact);
        }
        var graph = graph("core"); var facts = Map.of("core", "Overhauls Nether terrain with many new biomes");
        for (var selected : List.of(Set.<String>of(), Set.of("core"))) {
            if (!selected.isEmpty()) graph.selectedVersions.clear();
            var row = RequirementCoverage.assess(List.of(r), List.of(), graph, selected, Map.of(), Map.of(), "NONE", List.of(), Map.of(), facts).requirements().getFirst();
            assertEquals("UNVERIFIED", row.status()); assertTrue(row.mods().isEmpty());
        }
    }
    @Test void projectFactsAreScopedIndependentlyForDimensionChildren() {
        var base = requirement("丰富下界和末地生态", BuildRequirement.Kind.CAPABILITY, List.of());
        var children = List.of(new RequirementCriterion("丰富下界", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()),
                new RequirementCriterion("末地生态", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()));
        var r = new BuildRequirement(base.id(), base.source(), base.kind(), List.of(), List.of(), List.of(), List.of(), base.preferences(), children);
        var graph = graph("end-core");
        var row = RequirementCoverage.assess(List.of(r), List.of(), graph, graph.allSlugs, Map.of(), Map.of(), "NONE", List.of(), Map.of(),
                Map.of("end-core", "Transforms the End terrain with new biomes")).requirements().getFirst();
        assertEquals("PARTIAL", row.status());
        assertEquals("UNVERIFIED", row.criteria().getFirst().status());
        assertEquals("SUPPORTING_EVIDENCE", row.criteria().getLast().status());
    }
    @Test void dimensionCoverageRequiresVerifiedRetainedCoreRatherThanStructureOrAnotherDimension() {
        var r = requirement("丰富下界生态", BuildRequirement.Kind.CAPABILITY, List.of());
        var facts = Map.of("fortress", "Overhauls Nether fortresses and adds structures",
                "core", "Nether expansion with custom terrain and new biomes",
                "end-core", "Transforms the End terrain with new biomes",
                "biomes", "Adds 50 unique biomes to the Overworld, Nether and End");
        var evidence = facts.entrySet().stream().map(e -> new RequirementReview.Contribution(e.getKey(), e.getValue(), "维度内容")).toList();
        var verified = RequirementCoverage.verify(List.of(r), List.of(new RequirementReview(r.id(), evidence, "")), facts, Set.of());
        assertEquals(4, verified.counts().accepted());
        assertEquals(List.of("core"), verified.checks().get(r.id()).generation().stream().map(DimensionGeneration.Proof::slug).toList());
        for (var selected : List.of(Set.of("fortress", "biomes", "end-core"), Set.of("core", "fortress"))) {
            var result = RequirementCoverage.assess(List.of(r), verified.reviews(), graph(selected.toArray(String[]::new)), selected,
                    Map.of(), Map.of(), "NONE", List.of(), verified.checks()).requirements().getFirst();
            if (selected.contains("core")) {
                assertEquals("SUPPORTING_EVIDENCE", result.status());
                assertEquals(List.of("core"), result.evidenceState().generation().getFirst().projects());
            } else {
                assertEquals("PARTIAL", result.status());
                assertEquals("CORE_GENERATION_PENDING", result.evidenceState().code());
                assertTrue(result.note().contains("核心世界生成"));
                assertEquals(List.of("core"), RequirementCoverage.repairs(new RequirementCoverage.Report(1, List.of(result), "PENDING", List.of()),
                        verified.reviews(), facts.keySet(), selected));
            }
        }
        var forged = RequirementCoverage.verify(List.of(r), List.of(new RequirementReview(r.id(), evidence, "")), Map.of(), Set.of());
        assertTrue(forged.checks().get(r.id()).generation().isEmpty());
        var vetoed = RequirementCoverage.verify(List.of(r), List.of(new RequirementReview(r.id(), evidence, "")), facts, Set.of("core"));
        assertTrue(vetoed.checks().get(r.id()).generation().isEmpty());
        var pendingGraph = graph("core"); pendingGraph.selectedVersions.clear();
        var pending = RequirementCoverage.assess(List.of(r), verified.reviews(), pendingGraph, Set.of("core"), Map.of(), Map.of(),
                "NONE", List.of(), verified.checks()).requirements().getFirst();
        assertEquals("VERSION_PENDING", pending.evidenceState().code());
        assertTrue(pending.evidenceState().generation().getFirst().projects().isEmpty());
    }

    @Test void independentDimensionChildrenDoNotBorrowEachOthersCoreEvidence() {
        var base = requirement("丰富下界和末地生态", BuildRequirement.Kind.CAPABILITY, List.of());
        var children = List.of(new RequirementCriterion("丰富下界", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()),
                new RequirementCriterion("末地生态", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()));
        var r = new BuildRequirement(base.id(), base.source(), base.kind(), List.of(), List.of(), List.of(), List.of(), base.preferences(), children);
        var quote = new RequirementReview.Contribution("end-core", "Transforms the End terrain with new biomes", "末地生成");
        var review = new RequirementReview(r.id(), List.of(), "", children.stream().map(c -> new CriterionReview(c.source(), List.of(quote), "")).toList());
        var verification = RequirementCoverage.verify(List.of(r), List.of(review), Map.of("end-core", quote.quote()), Set.of());
        var result = RequirementCoverage.assess(List.of(r), verification.reviews(), graph("end-core"), Set.of("end-core"), Map.of(), Map.of(),
                "NONE", List.of(), verification.checks()).requirements().getFirst();
        assertEquals("PARTIAL", result.status());
        assertEquals("CORE_GENERATION_PENDING", result.criteria().getFirst().evidenceState().code());
        assertEquals("SUPPORTING_EVIDENCE", result.criteria().getLast().status());
        assertEquals(List.of("end-core"), result.criteria().getLast().evidenceState().generation().getFirst().projects());
    }
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
    @Test void descriptionIntegrationEvidenceRecordsBothActualRetainedEndpoints() {
        var r0 = requirement("烹饪核心的包内联动", BuildRequirement.Kind.PACK_INTEGRATIONS, List.of("cooking-core"));
        var r = new BuildRequirement(r0.id(), r0.source(), r0.kind(), r0.projects(), List.of(), List.of("cooking-core"));
        var graph = graph("cooking-core", "magic-core", "bridge");
        var metadata = Map.of(
                "cooking-core", new PackAddonPolicy.Project("cooking-core", "cooking-core", 10L, Set.of("food"), "Cooking Core"),
                "magic-core", new PackAddonPolicy.Project("magic-core", "magic-core", 20L, Set.of("magic"), "Magic Core"),
                "bridge", new PackAddonPolicy.Project("bridge", "bridge", 5L, Set.of("food"), "Bridge", "", "Integration between cooking-core and magic-core"));
        var row = RequirementCoverage.assess(List.of(r), List.of(), graph, graph.allSlugs, Map.of(), metadata, "NONE", List.of()).requirements().getFirst();
        assertEquals("SUPPORTING_EVIDENCE", row.status());
        var evidence = row.mods().stream().filter(i -> i.slug().equals("bridge")).findFirst().orElseThrow();
        assertEquals("DESCRIPTION_RELATION", evidence.evidenceType());
        assertEquals(Set.of("cooking-core", "magic-core"), Set.copyOf(evidence.partners()));
        assertFalse(evidence.quote().isBlank());
        var missing = RequirementCoverage.assess(List.of(r), List.of(), graph, Set.of("cooking-core", "bridge"), Map.of(), metadata, "NONE", List.of()).requirements().getFirst();
        assertNotEquals("SUPPORTING_EVIDENCE", missing.status());
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
