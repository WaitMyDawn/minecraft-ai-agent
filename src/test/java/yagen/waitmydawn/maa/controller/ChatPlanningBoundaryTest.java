package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import yagen.waitmydawn.maa.cache.ModrinthCacheService;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.*;
import yagen.waitmydawn.maa.service.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatPlanningBoundaryTest {
    private ChatController controller;
    private AiAgentService agents;
    private DependencyEngine engine;
    private LoaderVersionService versions;
    private ModrinthApiClient api;
    private ModAliasRegistry aliases;
    private UserController users;
    private org.mockito.MockedStatic<BasicBuildInputs> basicInputs;

    @BeforeEach void setup() {
        // 装配边界用例从已确认参数开始；确认入口由独立用例覆盖。
        basicInputs = mockStatic(BasicBuildInputs.class);
        basicInputs.when(() -> BasicBuildInputs.confirmation(anyString(), any())).thenReturn("");
        agents = mock(AiAgentService.class);
        engine = mock(DependencyEngine.class);
        versions = mock(LoaderVersionService.class);
        api = mock(ModrinthApiClient.class);
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString()))
                .thenReturn(new tools.jackson.databind.ObjectMapper().createObjectNode().put("id", "fixture-version"));
        users = mock(UserController.class);
        aliases = mock(ModAliasRegistry.class);
        when(users.getUserApiKey("fixture-token")).thenReturn("fixture-key");
        when(aliases.promptBlock()).thenReturn("");
        controller = new ChatController(agents, mock(ModrinthCacheService.class), null, engine,
                api, null, null, users, mock(UserRepository.class), null, null,
                aliases, mock(ExpansionTracker.class), null, versions);
        when(versions.supports("fabric", "1.20.1")).thenReturn(true);
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            Set<String> roots = new LinkedHashSet<>(call.getArgument(0));
            var graph = new DependencyGraph();
            graph.allSlugs.addAll(roots);
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        });
    }
    @AfterEach void releaseInputs() { basicInputs.close(); }

    private String decision(String mc, BuildIntent.Loader loader, boolean change) {
        return new AgentContractCodec().write(new BuildIntent(1, BuildIntent.Mode.EDIT, "保留其余清单", "",
                new BuildIntent.Environment(mc, loader, change), null, List.of(), List.of("jei"), List.of("create"),
                List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2_100_000_000L));
    }

    private AiAgentService.AgentCallResult response(String json) {
        return new AiAgentService.AgentCallResult(json, 1, 1, 10, 10);
    }

    @Test void boundedFollowupCanReviewRetainedFactsWithoutCandidatesAndReturnsOnlyScopedUpdates() {
        var storage = new RequirementCriterion("仓储", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(),
                List.of(new BuildIntent.SearchIntent("storage", 100, List.of("storage"))));
        var automation = new RequirementCriterion("自动化", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(),
                List.of(new BuildIntent.SearchIntent("technology", 100, List.of("automation"))));
        var requirement = new BuildRequirement("r-0000000000000001", "自动化和仓储", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(), SelectionPreferences.DEFAULT, List.of(automation, storage));
        var supported = new CriterionReview("自动化", List.of(new RequirementReview.Contribution("factory", "factory automation", "提供自动化")), "");
        var prior = new RequirementReview(requirement.id(), List.of(), "", List.of(supported, new CriterionReview("仓储", List.of(), "")));
        var graph = new DependencyGraph(); graph.allSlugs.add("factory");
        graph.selectedVersions.put("factory", new tools.jackson.databind.ObjectMapper().createObjectNode().put("id", "V1"));
        var report = RequirementCoverage.assess(List.of(requirement), List.of(prior), graph, graph.allSlugs,
                Map.of(), Map.of(), "NONE", List.of());
        var update = new RequirementReview(requirement.id(), List.of(), "", List.of(new CriterionReview("仓储",
                List.of(new RequirementReview.Contribution("factory", "bulk storage", "提供仓储")), "")));
        when(agents.criticPools(anyString(), anyString(), eq(Set.of()), eq(0))).thenReturn(response(
                new AgentContractCodec().write(new ReviewDecision(1, List.of(), List.of(), false, List.of(update)))));
        var budget = new RetrievalBudget();
        for (int i = 0; i < RetrievalBudget.MAX_SEARCHES; i++) budget.search("used-" + i);
        var intent = new AgentContractCodec().readBuildIntent(decision("1.20.1", BuildIntent.Loader.FABRIC, false));
        Object followup = ReflectionTestUtils.invokeMethod(controller, "reviewMissingCriteria", List.of(requirement), report,
                List.of(prior), Map.of("factory", "factory automation with bulk storage"), Set.of("factory"), Set.of("factory"), Set.of(),
                new BuildExecutionPlan(intent, Set.of("factory"), Set.of()), budget, "fabric", "1.20.1", Long.MAX_VALUE, "fixture-session", "fixture-key");
        assertEquals("REVIEWED_ONCE", ReflectionTestUtils.invokeMethod(followup, "status"));
        List<RequirementReview> updates = ReflectionTestUtils.invokeMethod(followup, "updates");
        assertEquals(List.of(update), updates);
        var merged = RequirementCoverage.merge(List.of(prior), updates);
        assertEquals(supported, merged.getFirst().criteria().getFirst());
        assertEquals("bulk storage", merged.getFirst().criteria().get(1).evidence().getFirst().quote());
        verify(agents, times(1)).criticPools(anyString(), anyString(), eq(Set.of()), eq(0));
        verifyNoInteractions(api);
    }

    private String run(String prompt) {
        return ReflectionTestUtils.invokeMethod(controller, "doChat",
                Map.of("prompt", prompt, "currentMods", "create", "uuid", "fixture-session",
                        "mcVersion", "1.20.1", "loader", "Fabric"), "fixture-token", null, null);
    }

    @Test void inheritedEnvironmentIsValidatedAndAssembledEvenIfModelRepeatsAnotherVersion() {
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(decision("1.21.1", BuildIntent.Loader.NEOFORGE, false)));
        String result = run("不要切到NeoForge1.21.1，只加JEI并删除Create");
        assertTrue(result.contains("<mc>1.20.1</mc>"), result);
        assertTrue(result.contains("<loader>fabric</loader>"), result);
        verify(versions).supports("fabric", "1.20.1");
        verify(versions, never()).supports("neoforge", "1.21.1");
        verify(agents).planBlueprint(argThat(prompt -> !prompt.contains("【用户本轮明确点名了环境】")), anyString());
        verify(engine).resolveCachedResult(eq(Set.of("jei")), eq("fabric"), eq("1.20.1"), any());
    }

    @Test void unsupportedExplicitSwitchDoesNotSilentlyBuildInOldEnvironment() {
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(decision("26.2", BuildIntent.Loader.NEOFORGE, true)));
        String result = run("换到NeoForge26.2");
        assertTrue(result.contains("本轮未修改清单"), result);
        assertFalse(result.contains("<mods>"), result);
        verifyNoInteractions(engine);
    }

    @Test void switchingToCurrentEnvironmentDoesNotReportAnEnvironmentChange() {
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(decision("1.20.1", BuildIntent.Loader.FABRIC, true)));
        String result = run("使用Fabric1.20.1，只加JEI并删除Create");
        assertFalse(result.contains("\"env\":"), result);
        assertTrue(result.contains("<mods>jei</mods>"), result);
    }

    @Test void supportedExplicitSwitchUsesTheSameEnvironmentForSummaryAndAssembly() {
        when(versions.supports("forge", "1.20.1")).thenReturn(true);
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(decision("1.20.1", BuildIntent.Loader.FORGE, true)));
        String result = run("MC不变，只换成Forge，加JEI并删除Create");
        assertTrue(result.contains("<mc>1.20.1</mc>"), result);
        assertTrue(result.contains("<loader>forge</loader>"), result);
        assertTrue(result.contains("1.20.1 + fabric → 1.20.1 + forge"), result);
        verify(engine).resolveCachedResult(eq(Set.of("jei")), eq("forge"), eq("1.20.1"), any());
    }

    @Test void malformedCurrentEnvironmentIsRejectedBeforeAnyModelCall() {
        String result = ReflectionTestUtils.invokeMethod(controller, "doChat",
                Map.of("prompt", "加JEI", "loader", "unknown"), "fixture-token", null, null);
        assertTrue(result.contains("环境格式无效"), result);
        verifyNoInteractions(agents, engine, versions);
    }

    @Test void transportRetryAndFormatRepairApplyOnlyOneValidatedPlan() {
        when(agents.planBlueprint(anyString(), anyString()))
                .thenThrow(new dev.langchain4j.exception.InternalServerException("fixture upstream failure"))
                .thenReturn(response("{\"mode\":\"EDIT\"}"))
                .thenReturn(response(decision("1.20.1", BuildIntent.Loader.FABRIC, false)));
        String result = run("删除Create并加JEI");
        assertTrue(result.contains("<mods>jei</mods>"), result);
        verify(agents, times(3)).planBlueprint(anyString(), anyString());
        verify(engine, times(1)).resolveCachedResult(anySet(), anyString(), anyString(), any());
        verify(agents, never()).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void invalidPlansHaveNoAssemblySideEffectsAndReleaseRoundReservation() {
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response("{}"));
        assertTrue(run("删除Create").contains("未通过结构化校验"));
        assertTrue(run("删除Create").contains("未通过结构化校验"));
        verify(agents, times(4)).planBlueprint(anyString(), anyString());
        verifyNoInteractions(engine, versions);
    }
    @Test void failedRepairIncludesBothSanitizedValidationAttempts() {
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response("{"), response("{}"));
        String result = run("只加JEI");
        assertTrue(result.contains("\"plannerValidation\":{\"status\":\"FAILED\""), result);
        assertTrue(result.contains("\"kind\":\"JSON_SYNTAX\""), result);
        assertTrue(result.contains("\"kind\":\"STRUCTURE\""), result);
        verifyNoInteractions(engine, versions);
    }
    @Test void successfulRepairReceivesSpecificFieldAndRecordsRecovery() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var invalid = (tools.jackson.databind.node.ObjectNode)mapper.readTree(decision("1.20.1", BuildIntent.Loader.FABRIC, false));
        invalid.withArray("exclude").add("jei");
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(invalid.toString()),
                response(decision("1.20.1", BuildIntent.Loader.FABRIC, false)));
        String result = run("只加JEI并删除Create");
        assertTrue(result.contains("\"plannerValidation\":{\"status\":\"VALID\""), result);
        assertTrue(result.contains("\"field\":\"add/exclude\""), result);
        verify(agents).planBlueprint(argThat(p -> p.contains("【格式修正】") && p.contains("SEMANTIC:add/exclude")), anyString());
        verify(engine, times(1)).resolveCachedResult(anySet(), anyString(), anyString(), any());
    }

    @Test void chatRoundReceivesPersistentConstraintsAndCommitsOnlyWithBuiltManifest() {
        var codec = new AgentContractCodec();
        var previous = new BuildConstraints(1, List.of("jade"), List.of("sodium"), List.of("technology"), null,
                BuildIntent.ReplacementPolicy.COMPATIBLE_PORTS, 500_000);
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(decision("1.20.1", BuildIntent.Loader.FABRIC, false)));
        String result = ReflectionTestUtils.invokeMethod(controller, "doChat",
                Map.of("prompt", "删除Create，新增JEI", "currentMods", "create,jade", "constraints", codec.write(previous),
                        "uuid", "persistent-session", "mcVersion", "1.20.1", "loader", "fabric"), "fixture-token", null, null);
        assertTrue(result.contains("<constraints>"), result);
        String state = result.substring(result.indexOf("<constraints>") + 13, result.indexOf("</constraints>"));
        BuildConstraints saved = codec.readConstraints(state);
        assertEquals(List.of("jade"), saved.mustHave());
        assertTrue(saved.exclude().containsAll(List.of("create", "sodium")));
        assertEquals(500_000, saved.maxDownloads());
        assertEquals(previous.replacementPolicy(), saved.replacementPolicy());
        verify(agents).planBlueprint(argThat(prompt -> prompt.contains("持久约束") && prompt.contains("500000")), anyString());
        verify(engine).resolveCachedResult(eq(Set.of("jade", "jei")), eq("fabric"), eq("1.20.1"), any());
    }

    @Test void conflictingOrMalformedConstraintsCannotCommitOrInvokeAssembly() {
        var codec = new AgentContractCodec();
        var previous = new BuildConstraints(1, List.of(), List.of("jei"), List.of(), null, BuildIntent.ReplacementPolicy.NONE, 500_000);
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(decision("1.20.1", BuildIntent.Loader.FABRIC, false)));
        String result = ReflectionTestUtils.invokeMethod(controller, "doChat",
                Map.of("prompt", "加JEI", "constraints", codec.write(previous), "mcVersion", "1.20.1", "loader", "fabric"), "fixture-token", null, null);
        assertTrue(result.contains("已有约束") && result.contains("本轮未修改清单"), result);
        assertFalse(result.contains("<constraints>"));
        verifyNoInteractions(engine, versions);
        String invalid = ReflectionTestUtils.invokeMethod(controller, "doChat",
                Map.of("prompt", "加JEI", "constraints", "{}"), "fixture-token", null, null);
        assertTrue(invalid.contains("约束无效"), invalid);
        verify(agents, times(1)).planBlueprint(anyString(), anyString());
    }
    @Test void omittedKnownIdentityIsRepairedBeforeAnyPreflightAndLedgerIsSaved() {
        String prompt = "包含Alex生物、海灵物语";
        when(aliases.all()).thenReturn(Map.of("alex生物", "alexs-mobs", "海灵物语", "aquamirae"));
        var anchor = RequirementPlanning.anchors(prompt).entrySet().iterator().next();
        var entry = new BuildRequirement(anchor.getKey(), anchor.getValue(), BuildRequirement.Kind.PROJECTS,
                List.of("alexs-mobs", "aquamirae"), List.of());
        var valid = new BuildIntent(2, BuildIntent.Mode.NEW, "组合", "", BuildEnvironment.current("1.20.1", "fabric"),
                new BuildIntent.CountTarget(2, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.EXACT),
                entry.projects(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.COMPATIBLE_PORTS,
                List.of(), List.of(), 2100000000L, ConstraintChanges.KEEP, List.of(entry));
        var invalid = new BuildIntent(2, valid.mode(), valid.reply(), valid.name(), valid.environment(), valid.count(),
                List.of("aquamirae"), valid.add(), valid.remove(), valid.exclude(), valid.expandAddons(), valid.replacementPolicy(),
                valid.searchIntents(), valid.excludedCategories(), valid.maxDownloads(), valid.constraintChanges(), valid.requirements());
        var codec = new AgentContractCodec();
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(codec.write(invalid)), response(codec.write(valid)));
        String result = run(prompt);
        assertTrue(result.contains("alexs-mobs") && result.contains("aquamirae"), result);
        verify(agents, times(2)).planBlueprint(anyString(), anyString());
        verify(engine).resolveCachedResult(eq(Set.of("alexs-mobs", "aquamirae")), eq("fabric"), eq("1.20.1"), any());
        String json = result.substring(result.indexOf("<constraints>") + 13, result.indexOf("</constraints>"));
        assertEquals(List.of(entry), codec.readConstraints(json).requirements());
    }

    private String goalBuild(String rejected) {
        String prompt = "新建优化和实用组合";
        var anchor = RequirementPlanning.anchors(prompt).entrySet().iterator().next();
        var entry = new BuildRequirement(anchor.getKey(), anchor.getValue(), BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(BuildRequirement.Capability.PERFORMANCE, BuildRequirement.Capability.UTILITY));
        var intent = new BuildIntent(2, BuildIntent.Mode.NEW, "组合", "", BuildEnvironment.current("1.20.1", "fabric"),
                new BuildIntent.CountTarget(2, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.EXACT),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2100000000L, ConstraintChanges.KEEP, List.of(entry));
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(new AgentContractCodec().write(intent)));
        when(api.getProjectInfo(anyString())).thenAnswer(call -> {
            String slug = call.getArgument(0);
            boolean utility = Set.of("appleskin", "jei", "jade").contains(slug);
            return new tools.jackson.databind.ObjectMapper().createObjectNode().put("id", "P-" + slug)
                    .put("slug", slug).put("title", slug).put("description", utility
                            ? slug.equals("appleskin") ? "Shows hunger and food information" : "Recipe viewer and item information"
                            : "Improves rendering performance and reduces memory")
                    .put("downloads", utility ? 1000 : slug.equals("sodium") ? 100000000 : 100000);
        });
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var metadata = new HashMap<String, tools.jackson.databind.JsonNode>();
            for (String slug : (Collection<String>) call.getArgument(0)) {
                var info = (tools.jackson.databind.node.ObjectNode) api.getProjectInfo(slug);
                info.put("client_side", "required").put("server_side", "optional");
                metadata.put(slug, info);
            }
            return metadata;
        });
        when(api.searchPage(anyString(), eq(15), eq(0), isNull(), anyString()))
                .thenReturn(new tools.jackson.databind.ObjectMapper().readTree("{\"hits\":[]}"));
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(response(
                "{\"schemaVersion\":1,\"approved\":[\"sodium\",\"ferrite-core\",\"appleskin\"],\"rejected\":" + rejected + ",\"allowBackfill\":false}"));
        return run(prompt);
    }

    @Test void goalCoverageIsPreferredOverPopularityWhenApprovedChoicesExceedBudget() {
        String result = goalBuild("[]");
        assertEquals(Set.of("sodium", "appleskin"), Set.copyOf(Arrays.asList(result.substring(result.indexOf("<mods>") + 6, result.indexOf("</mods>")).split(","))), result);
        assertTrue(result.contains("goalEvidence") && result.contains("\"selected\":[\"appleskin\"]"), result);
        verify(agents).criticPools(argThat(context -> context.contains("逐项需求目标") && context.contains("UTILITY")), anyString(), anySet(), eq(2));
    }
    @Test void explicitVetoStillWinsOverGoalCoverageAndCannotBeBackfilled() {
        String result = goalBuild("[{\"slug\":\"appleskin\",\"reason\":\"OFF_TOPIC\",\"explanation\":\"明确否决\"}]");
        assertEquals(Set.of("sodium", "ferrite-core"), Set.copyOf(Arrays.asList(result.substring(result.indexOf("<mods>") + 6, result.indexOf("</mods>")).split(","))), result);
        assertTrue(result.contains("NO_EVIDENCE"));
    }

    private String arbitraryGoalBuild(String rejected) {
        return arbitraryGoalBuild(rejected, false);
    }
    private String arbitraryGoalBuild(String rejected, boolean includeEvidence) {
        return arbitraryGoalBuild(rejected, includeEvidence, false);
    }
    private String arbitraryGoalBuild(String rejected, boolean includeEvidence, boolean repairScenario) {
        String prompt = "工厂自动化\n大型仓储" + (repairScenario ? "\n能源系统" : "");
        var anchors = RequirementPlanning.anchors(prompt).entrySet().stream().toList();
        var requirements = new ArrayList<>(List.of(
                new BuildRequirement(anchors.get(0).getKey(), anchors.get(0).getValue(), BuildRequirement.Kind.CAPABILITY,
                        List.of(), List.of(), List.of(), List.of(new BuildIntent.SearchIntent("technology", 100, List.of("automation")))),
                new BuildRequirement(anchors.get(1).getKey(), anchors.get(1).getValue(), BuildRequirement.Kind.CAPABILITY,
                        List.of(), List.of(), List.of(), List.of(new BuildIntent.SearchIntent("storage", 100, List.of("storage"))))));
        if (repairScenario) requirements.add(new BuildRequirement(anchors.get(2).getKey(), anchors.get(2).getValue(),
                BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(),
                List.of(new BuildIntent.SearchIntent("technology", 100, List.of("power")))));
        var intent = new BuildIntent(2, BuildIntent.Mode.NEW, "组合", "", BuildEnvironment.current("1.20.1", "fabric"),
                new BuildIntent.CountTarget(repairScenario ? 12 : 2, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.EXACT),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2100000000L, ConstraintChanges.KEEP, requirements);
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(new AgentContractCodec().write(intent)));
        var json = new tools.jackson.databind.ObjectMapper();
        when(api.searchPage(anyString(), eq(15), eq(0), isNull(), anyString())).thenAnswer(call -> {
            String query = call.getArgument(0);
            var page = json.createObjectNode();
            var hits = page.putArray("hits");
            if (query.equals("automation")) {
                hits.addObject().put("slug", "factory-machines").put("title", "Automation machines").put("description", "Factory automation").put("downloads", 100000000);
                hits.addObject().put("slug", "power-machines").put("title", "Automation power").put("description", "Factory automation").put("downloads", 90000000);
                hits.addObject().put("slug", "automation-api").put("title", "Automation API").put("description", "Automation library").put("downloads", 200000000);
                hits.addObject().put("slug", "incompatible-machines").put("title", "Automation machines").put("description", "Factory automation").put("downloads", 200000000);
            } else if (!query.equals("power")) hits.addObject().put("slug", "warehouse-storage").put("title", "Warehouse storage").put("description", "Large storage").put("downloads", 100);
            return page;
        });
        when(api.getLatestCompatibleVersionBySlug(eq("incompatible-machines"), anyString(), anyString())).thenReturn(null);
        var reviews = new ArrayList<>(List.of(
                new RequirementReview(requirements.get(0).id(), List.of(new RequirementReview.Contribution("factory-machines", "Factory automation", "支持工厂自动化")), ""),
                new RequirementReview(requirements.get(1).id(), List.of(new RequirementReview.Contribution("warehouse-storage", "Large storage", "提供大型仓储")), "")));
        if (repairScenario) reviews.add(new RequirementReview(requirements.get(2).id(),
                List.of(new RequirementReview.Contribution("power-machines", "Automation power", "提供能源系统")), ""));
        String evidence = includeEvidence ? ",\"requirements\":" + json.writeValueAsString(reviews) : "";
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(response(
                "{\"schemaVersion\":1,\"approved\":[\"factory-machines\",\"power-machines\",\"warehouse-storage\"],\"rejected\":" + rejected + ",\"allowBackfill\":false" + evidence + "}"));
        return run(prompt);
    }

    @Test void finalRepairAddsAnUnretainedReviewedCandidateOnceAndReportsActualCoverage() {
        var expansion = (ExpansionTracker) ReflectionTestUtils.getField(controller, "expansionTracker");
        when(expansion.factor(anyString(), anyString())).thenReturn(6.0);
        org.mockito.stubbing.Answer<ResolutionResult> complete = call -> {
            Set<String> roots = call.getArgument(0);
            var graph = new DependencyGraph(); graph.allSlugs.addAll(roots);
            for (String slug : roots) graph.selectedVersions.put(slug, new tools.jackson.databind.ObjectMapper()
                    .createObjectNode().put("id", slug + "v").put("project_id", slug));
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        };
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(complete);
        when(engine.resolveUsingFacts(anySet(), anyString(), anyString(), any(), anyMap())).thenAnswer(complete);
        String result = arbitraryGoalBuild("[]", true, true);
        var match = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(match.find(), result);
        var report = new tools.jackson.databind.ObjectMapper().readTree(match.group(1));
        assertEquals("APPLIED_ONE_PASS", report.path("repairStatus").asText(), result);
        assertEquals("power-machines", report.path("repaired").get(0).asText());
        assertEquals("SUPPORTING_EVIDENCE", report.path("requirements").get(2).path("status").asText());
        assertTrue(result.contains("<mods>factory-machines,power-machines,warehouse-storage</mods>"), result);
        verify(engine, times(1)).resolveUsingFacts(eq(Set.of("factory-machines", "power-machines", "warehouse-storage")), anyString(), anyString(), any(), anyMap());
        verify(agents, times(1)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void finalRequirementReportUsesVerifiedVersionAndOriginalPurposeEvidence() {
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            Set<String> roots = call.getArgument(0);
            var graph = new DependencyGraph();
            graph.allSlugs.addAll(roots);
            for (String slug : roots) graph.selectedVersions.put(slug, new tools.jackson.databind.ObjectMapper()
                    .createObjectNode().put("id", slug + "v").put("project_id", slug));
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        });
        String result = arbitraryGoalBuild("[]", true);
        var match = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(match.find(), result);
        var report = new tools.jackson.databind.ObjectMapper().readTree(match.group(1));
        assertEquals(2, report.path("requirements").size());
        assertEquals("SUPPORTING_EVIDENCE", report.path("requirements").get(0).path("status").asText());
        assertEquals("支持工厂自动化", report.path("requirements").get(0).path("mods").get(0).path("purpose").asText());
        assertEquals("Factory automation", report.path("requirements").get(0).path("mods").get(0).path("quote").asText());
        assertFalse(match.group(1).contains("icon"));
        verify(agents, times(1)).criticPools(argThat(context -> context.contains("项目用途事实")), anyString(), anySet(), anyInt());
        verify(engine, never()).resolvePreferred(anySet(), anyString(), anyString(), any(), anyMap());
    }

    @Test void arbitraryTechnologyAndStorageGoalsParticipateWithoutAnyPredefinedCapability() {
        String result = arbitraryGoalBuild("[]");
        assertEquals(Set.of("factory-machines", "warehouse-storage"), Set.copyOf(Arrays.asList(
                result.substring(result.indexOf("<mods>") + 6, result.indexOf("</mods>")).split(","))), result);
        assertTrue(result.contains("RETRIEVAL_ASSOCIATION_NOT_SATISFACTION"));
        verify(agents).criticPools(argThat(context -> context.contains("工厂自动化") && context.contains("大型仓储")
                && !context.contains("incompatible-machines") && !context.contains("automation-api")), anyString(), anySet(), eq(2));
        verify(api, never()).getLatestCompatibleVersionBySlug(eq("automation-api"), anyString(), anyString());
        verify(api).searchPage(eq("storage"), eq(15), eq(0), isNull(), contains("categories:storage"));
    }

    @Test void arbitraryGoalDoesNotOverrideVetoAndItsMissingEvidenceIsRetained() {
        String result = arbitraryGoalBuild("[{\"slug\":\"warehouse-storage\",\"reason\":\"OFF_TOPIC\",\"explanation\":\"明确否决\"}]");
        assertEquals(Set.of("factory-machines", "power-machines"), Set.copyOf(Arrays.asList(
                result.substring(result.indexOf("<mods>") + 6, result.indexOf("</mods>")).split(","))), result);
        assertTrue(result.contains("NO_RETAINED_RETRIEVAL_HINT"));
    }

    private String addonBuild(boolean integrations, int count, boolean secondLinked, List<String> approved,
                              String rejected, boolean dropPartner) {
        return addonBuild(integrations, count, secondLinked, approved, rejected, dropPartner, true);
    }
    private String addonBuild(boolean integrations, int count, boolean secondLinked, List<String> approved,
                              String rejected, boolean dropPartner, boolean partnerInitiallyPresent) {
        return addonBuild(integrations, count, secondLinked, approved, rejected, dropPartner, partnerInitiallyPresent, false);
    }
    private String addonBuild(boolean integrations, int count, boolean secondLinked, List<String> approved,
                              String rejected, boolean dropPartner, boolean partnerInitiallyPresent, boolean range) {
        String prompt = integrations ? "扩展烹饪核心附属，特别是包内模组的联动附属" : "扩展烹饪核心附属";
        var anchor = RequirementPlanning.anchors(prompt).entrySet().iterator().next();
        var entry = new BuildRequirement(anchor.getKey(), anchor.getValue(), integrations
                ? BuildRequirement.Kind.PACK_INTEGRATIONS : BuildRequirement.Kind.ADDONS,
                List.of("cooking-core"), List.of(), List.of("cooking-core"));
        var intent = new BuildIntent(2, BuildIntent.Mode.NEW, "组合", "", BuildEnvironment.current("1.20.1", "fabric"),
                range ? new BuildIntent.CountTarget(count, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE, count, count + 2)
                        : new BuildIntent.CountTarget(count, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.EXACT),
                partnerInitiallyPresent ? List.of("cooking-core", "factory-content") : List.of("cooking-core"), List.of(), List.of(), List.of(), List.of("cooking-core"),
                BuildIntent.ReplacementPolicy.NONE, partnerInitiallyPresent ? List.of()
                        : List.of(new BuildIntent.SearchIntent("technology", 100, List.of("factory"))), List.of(), 2100000000L, ConstraintChanges.KEEP, List.of(entry));
        var json = new tools.jackson.databind.ObjectMapper();
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(new AgentContractCodec().write(intent)));
        when(api.getProjectInfo(anyString())).thenAnswer(call -> {
            String slug = call.getArgument(0);
            var info = json.createObjectNode().put("id", slug + "-id").put("slug", slug).put("title", slug)
                    .put("downloads", slug.equals("cooking-core") ? 100 : 1000);
            info.putArray("categories").add(slug.equals("factory-content") ? "technology" : "food");
            return info;
        });
        when(api.search(anyString(), eq(25), anyString())).thenAnswer(call -> {
            var page = json.createObjectNode();
            var hits = page.putArray("hits");
            hits.addObject().put("slug", "plain-addon").put("title", "Plain addon").put("downloads", 1000);
            hits.addObject().put("slug", "factory-cooking-addon").put("title", "Factory Cooking addon").put("downloads", 900);
            if (secondLinked) hits.addObject().put("slug", "second-cooking-addon").put("title", "Second Cooking addon").put("downloads", 100);
            return page;
        });
        when(api.searchPage(anyString(), eq(30), eq(0), isNull(), anyString())).thenReturn(json.readTree("""
                {"hits":[{"slug":"factory-content","title":"Factory Content","description":"Factory automation","downloads":1000}]}
                """));
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenAnswer(call -> {
            String slug = call.getArgument(0);
            var version = json.createObjectNode().put("id", slug + "-version").put("project_id", slug + "-id");
            var dependencies = version.putArray("dependencies");
            if (slug.endsWith("addon")) dependencies.addObject().put("project_id", "cooking-core-id").put("dependency_type", "required");
            if (slug.equals("factory-cooking-addon") || slug.equals("second-cooking-addon"))
                dependencies.addObject().put("project_id", "factory-content-id").put("dependency_type", "optional");
            if (secondLinked && !range && slug.equals("factory-cooking-addon"))
                dependencies.addObject().put("project_id", "second-cooking-addon-id").put("dependency_type", "incompatible");
            return version;
        });
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(response(
                "{\"schemaVersion\":1,\"approved\":" + json.writeValueAsString(approved) + ",\"rejected\":" + rejected + ",\"allowBackfill\":false}"));
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            Set<String> roots = new LinkedHashSet<>(call.getArgument(0));
            var graph = new DependencyGraph();
            for (String slug : roots) if (!dropPartner || !slug.equals("factory-content")) {
                graph.allSlugs.add(slug);
                graph.selectedVersions.put(slug, api.getLatestCompatibleVersionBySlug(slug, "1.20.1", "fabric"));
            }
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        });
        return run(prompt);
    }

    @Test void ordinaryAddonUsesActualContentPartnerMultiplierWithoutAnotherModelCall() {
        String result = addonBuild(false, 3, false, List.of("plain-addon", "factory-cooking-addon"), "[]", false);
        assertTrue(result.contains("<mods>"), result);
        String mods = result.substring(result.indexOf("<mods>") + 6, result.indexOf("</mods>"));
        assertTrue(mods.contains("factory-cooking-addon") && !mods.contains("plain-addon"), result);
        assertTrue(result.contains("\"coefficient\":2.0") && result.contains("\"partners\":[\"factory-content\"]"));
        verify(agents, times(1)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void explicitIntegrationIsPreservedWhenCriticDoesNotSelectItAndBackfillIsForbidden() {
        String result = addonBuild(true, 3, false, List.of("plain-addon"), "[]", false);
        assertTrue(result.contains("<mods>"), result);
        String mods = result.substring(result.indexOf("<mods>") + 6, result.indexOf("</mods>"));
        assertTrue(mods.contains("factory-cooking-addon") && !mods.contains("plain-addon"), result);
        assertTrue(result.contains("EXPLICIT_INTEGRATION"));
    }

    @Test void explicitIntegrationVetoRequiresAUserDecisionInsteadOfSilentOmissionOrOverride() {
        String result = addonBuild(true, 3, false, List.of("plain-addon"),
                "[{\"slug\":\"factory-cooking-addon\",\"reason\":\"OFF_TOPIC\",\"explanation\":\"明确否决\"}]", false);
        assertTrue(result.contains("NEEDS_USER_DECISION") && result.contains("被审核明确否决"), result);
        assertFalse(result.contains("<mods>"));
        verifyNoInteractions(engine);
    }

    @Test void mandatoryIntegrationsCannotBePrunedToFitAStrictCount() {
        String result = addonBuild(true, 3, true, List.of(), "[]", false);
        assertTrue(result.contains("NEEDS_USER_DECISION") && result.contains("扩大数量上限"), result);
        assertFalse(result.contains("<mods>"));
        verifyNoInteractions(engine);
    }

    @Test void losingThePartnerDuringDependencyResolutionDoesNotClaimIntegrationCompletion() {
        String result = addonBuild(true, 3, false, List.of(), "[]", true);
        assertTrue(result.contains("未能保留完整关系") && result.contains("本轮未修改清单"), result);
        assertFalse(result.contains("<mods>"));
    }
    @Test void newlySelectedContentParticipatesInAddonScoringInsteadOfBeingStarvedByAddonReserve() {
        String result = addonBuild(false, 3, false, List.of("factory-content", "plain-addon", "factory-cooking-addon"), "[]", false, false);
        assertTrue(result.contains("<mods>"), result);
        String mods = result.substring(result.indexOf("<mods>") + 6, result.indexOf("</mods>"));
        assertEquals(Set.of("cooking-core", "factory-content", "factory-cooking-addon"), Set.copyOf(Arrays.asList(mods.split(","))), result);
        verify(agents, times(1)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }
    @Test void approximateRangeCanKeepMandatoryAddonsBeyondTheReferenceBudgetWithinTheUpperBound() {
        String result = addonBuild(true, 3, true, List.of(), "[]", false, true, true);
        assertTrue(result.contains("<mods>"), result);
        String mods = result.substring(result.indexOf("<mods>") + 6, result.indexOf("</mods>"));
        assertEquals(Set.of("cooking-core", "factory-content", "factory-cooking-addon", "second-cooking-addon"),
                Set.copyOf(Arrays.asList(mods.split(","))), result);
    }
    @Test void incompatibleMandatoryAddonsAreNotPublishedTogether() {
        String result = addonBuild(true, 4, true, List.of(), "[]", false);
        assertTrue(result.contains("NEEDS_USER_DECISION") && result.contains("未能保留完整关系"), result);
        assertFalse(result.contains("<mods>"));
    }

    @Test void unavailableRequiredCoreRequestsCandidateSelectionWithoutReplacingCurrentPack() {
        var original = new AgentContractCodec().readBuildIntent(decision("1.20.1", BuildIntent.Loader.FABRIC, false));
        var required = new BuildIntent(1, original.mode(), original.reply(), "", original.environment(), null,
                List.of("jade"), original.add(), original.remove(), List.of(), List.of(), original.replacementPolicy(), List.of(), List.of(), original.maxDownloads());
        when(api.getLatestCompatibleVersionBySlug("jade", "1.20.1", "fabric")).thenReturn(null);
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(new AgentContractCodec().write(required)));
        String result = run("保留jade并添加jei");
        assertTrue(result.contains("<replacement_options>"), result);
        assertTrue(result.contains("NO_COMPATIBLE_VERSION"), result);
        assertTrue(result.contains("NOT_STARTED"), result);
        assertFalse(result.contains("<mods>")); assertFalse(result.contains("<constraints>"));
        verifyNoInteractions(engine);
        verify(agents, never()).criticPools(anyString(), anyString(), anySet(), anyInt());
    }
    @Test void confirmedCandidateContinuesFrozenPlanWithoutCallingArchitectAgain() {
        var original = new AgentContractCodec().readBuildIntent(decision("1.20.1", BuildIntent.Loader.FABRIC, false));
        var required = new BuildIntent(1, original.mode(), original.reply(), "", original.environment(), null,
                List.of("jade"), original.add(), original.remove(), List.of(), List.of(), original.replacementPolicy(), List.of(), List.of(), original.maxDownloads());
        when(api.getLatestCompatibleVersionBySlug("jade", "1.20.1", "fabric")).thenReturn(null);
        var json = new tools.jackson.databind.ObjectMapper();
        var search = json.createObjectNode();
        search.putArray("hits").addObject().put("slug", "jade-continued").put("title", "Jade Continued");
        when(api.search(eq("jade"), eq(40), anyString())).thenReturn(search);
        when(api.getProjectInfo("jade")).thenReturn(json.createObjectNode().put("id", "Original"));
        when(api.getProjectInfo("jade-continued")).thenReturn(json.createObjectNode().put("id", "Port").put("slug", "jade-continued"));
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(new AgentContractCodec().write(required)));
        String first = run("保留jade并添加jei");
        var match = java.util.regex.Pattern.compile("<replacement_options>(.*?)</replacement_options>", java.util.regex.Pattern.DOTALL).matcher(first);
        assertTrue(match.find(), first);
        String ticket = json.readTree(match.group(1)).path("ticket").asText();
        var payload = new HashMap<>(Map.of("prompt", "保留jade并添加jei", "currentMods", "create", "uuid", "fixture-session",
                "mcVersion", "1.20.1", "loader", "Fabric", "replacementTicket", ticket,
                "replacementChoices", "{\"jade\":{\"slugs\":[\"jade-continued\"],\"drop\":false,\"manualSlug\":\"\"}}"));
        String result = ReflectionTestUtils.invokeMethod(controller, "doChat", payload, "fixture-token", null, null);
        assertTrue(result.contains("<mods>"), result);
        var selection = java.util.regex.Pattern.compile("<replacement_choices>(.*?)</replacement_choices>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(selection.find(), result);
        assertEquals("jade-continued", json.readTree(selection.group(1)).path("choices").path("jade").get(0).asText());
        assertFalse(selection.group(1).contains("icon"));
        assertTrue(result.contains("\"mustHave\":[\"jade-continued\"]"), result);
        verify(agents, times(1)).planBlueprint(anyString(), anyString());
        verify(engine).resolveCachedResult(eq(Set.of("jade-continued", "jei")), eq("fabric"), eq("1.20.1"), any());
        String replay = ReflectionTestUtils.invokeMethod(controller, "doChat", payload, "fixture-token", null, null);
        assertFalse(replay.contains("<mods>"));
        verify(agents, times(1)).planBlueprint(anyString(), anyString());
    }
    @Test void explicitAdditionAlsoOffersCandidatesEvenWhenMustHaveIsEmpty() {
        var original = new AgentContractCodec().readBuildIntent(decision("1.20.1", BuildIntent.Loader.FABRIC, false));
        var addition = new BuildIntent(1, original.mode(), original.reply(), "", original.environment(), null,
                List.of(), List.of("jade"), original.remove(), List.of(), List.of(), original.replacementPolicy(), List.of(), List.of(), original.maxDownloads());
        when(api.getLatestCompatibleVersionBySlug("jade", "1.20.1", "fabric")).thenReturn(null);
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(new AgentContractCodec().write(addition)));
        String result = run("添加jade");
        assertTrue(result.contains("<replacement_options>"), result);
        assertFalse(result.contains("<mods>"));
        verify(api).search(eq("jade"), eq(40), anyString());
        verifyNoInteractions(engine);
    }
    @Test void requiredCoreNetworkFailureIsUnverifiedAndDoesNotChangePack() {
        var original = new AgentContractCodec().readBuildIntent(decision("1.20.1", BuildIntent.Loader.FABRIC, false));
        var required = new BuildIntent(1, original.mode(), original.reply(), "", original.environment(), null,
                List.of("jade"), original.add(), original.remove(), List.of(), List.of(), original.replacementPolicy(), List.of(), List.of(), original.maxDownloads());
        when(api.getLatestCompatibleVersionBySlug("jade", "1.20.1", "fabric"))
                .thenThrow(new ModrinthApiClient.UnavailableException("private-network-detail"));
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(new AgentContractCodec().write(required)));
        String result = run("保留jade并添加jei");
        assertTrue(result.contains("UNVERIFIED"), result);
        assertTrue(result.contains("不能据此判断不兼容"), result);
        assertFalse(result.contains("private-network-detail")); assertFalse(result.contains("<mods>"));
        verifyNoInteractions(engine);
    }

    @Test void sparseNewBuildReturnsConfirmationBeforeAnyModrinthOrAssemblyWorkAndReleasesRound() {
        basicInputs.when(() -> BasicBuildInputs.confirmation(anyString(), any())).thenCallRealMethod();
        when(users.validateToken("fixture-token")).thenReturn(1003L);
        var blueprint = new BuildIntent(1, BuildIntent.Mode.NEW, "冒险组合", "冒险包",
                BuildEnvironment.current("1.20.1", "fabric"), new BuildIntent.CountTarget(100, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(new BuildIntent.SearchIntent("adventure", 100, List.of("adventure"))), List.of(), 2100000000L);
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(new AgentContractCodec().write(blueprint)));
        for (int i = 0; i < 2; i++) {
            String result = run("我想玩一个冒险整合包");
            assertTrue(result.contains("80～120"), result);
            assertTrue(result.contains("<clarification_context>"), result);
            assertTrue(result.contains("\"branch\":\"AWAITING_INPUT\""), result);
            assertTrue(result.contains("\"resolution\":{\"status\":\"NOT_STARTED\""), result);
            assertFalse(result.contains("<mods>"), result);
            assertFalse(result.contains("上一轮构筑还在进行"));
        }
        verifyNoInteractions(api, engine, versions);
        verify(agents, times(2)).planBlueprint(anyString(), anyString());
    }

    @Test void cancelledConfirmationDoesNotCallAnyAgentOrModifyPack() {
        assertTrue(run("取消构建").contains("已取消"));
        verifyNoInteractions(agents, engine, api);
    }

    @Test void acceptingSuggestedParametersResumesOriginalThemeWithoutAnotherConfirmation() {
        basicInputs.when(() -> BasicBuildInputs.confirmation(anyString(), any())).thenCallRealMethod();
        when(users.validateToken("fixture-token")).thenReturn(1003L);
        var range = new BuildIntent.CountTarget(100, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE, 80, 120);
        var blueprint = new BuildIntent(1, BuildIntent.Mode.NEW, "冒险组合", "冒险包", BuildEnvironment.current("1.20.1", "fabric"),
                range, List.of("jei"), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L);
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(new AgentContractCodec().write(blueprint)));
        String original = "我想玩一个冒险整合包";
        String first = run(original);
        var context = new tools.jackson.databind.ObjectMapper().readTree(first.substring(first.indexOf("<clarification_context>") + 23,
                first.indexOf("</clarification_context>")));
        var pending = (PlanningClarifications)ReflectionTestUtils.getField(controller, "planningClarifications");
        var reply = Map.of("prompt", "好的", "currentMods", "create", "uuid", "fixture-session", "mcVersion", "1.20.1", "loader", "Fabric");
        String result = pending.admit(context.path("ticket").asText(), 1003L, null, reply, prepared -> ReflectionTestUtils.invokeMethod(controller,
                "doChat", prepared, "fixture-token", null, null, null, prepared.get("clarificationQuestion"),
                Boolean.parseBoolean(prepared.get("basicParametersOffered"))));
        assertFalse(result.contains("<clarification_context>"), result);
        assertTrue(result.contains("<mods>jei</mods>"), result);
        verify(agents).planBlueprint(argThat(input -> input.contains(original) && input.contains("用户答复：好的")
                && input.contains("【上一轮待确认说明】")), anyString());
        verify(engine).resolveCachedResult(eq(Set.of("jei")), eq("fabric"), eq("1.20.1"), any());
    }

    @Test void missingGenericChildGetsOneBoundedSearchAndReviewThenTrialPreservesExistingVersion() {
        String prompt = "新建工厂自动化和大型仓储整合包，NeoForge1.21.1，约2～5个模组";
        var searches = List.of(new BuildIntent.SearchIntent("technology", 100, List.of("automation")));
        var storage = List.of(new BuildIntent.SearchIntent("storage", 100, List.of("mass storage")));
        var criteria = List.of(new RequirementCriterion("工厂自动化", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), searches),
                new RequirementCriterion("大型仓储", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), storage),
                new RequirementCriterion("NeoForge1.21.1", BuildRequirement.Kind.GENERAL, List.of(), List.of(), List.of()),
                new RequirementCriterion("约2～5个模组", BuildRequirement.Kind.GENERAL, List.of(), List.of(), List.of()));
        var requirement = new BuildRequirement(RequirementPlanning.anchors(prompt).keySet().iterator().next(), prompt, BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(), SelectionPreferences.DEFAULT, criteria);
        var intent = new BuildIntent(3, BuildIntent.Mode.NEW, "组合", "工厂包", BuildEnvironment.current("1.21.1", "neoforge"),
                new BuildIntent.CountTarget(2, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE, 2, 5),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2100000000L, ConstraintChanges.KEEP, List.of(requirement));
        var codec = new AgentContractCodec(); var mapper = new tools.jackson.databind.ObjectMapper();
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(codec.write(intent)));
        when(versions.supports("neoforge", "1.21.1")).thenReturn(true);
        var reviewed = new java.util.concurrent.atomic.AtomicInteger();
        when(api.searchPage(anyString(), eq(15), eq(0), isNull(), anyString())).thenAnswer(call -> {
            String query = call.getArgument(0); var page = mapper.createObjectNode(); var hits = page.putArray("hits");
            if (query.equals("automation")) hits.addObject().put("slug", "factory").put("title", "Factory machines")
                    .put("description", "Factory automation machines").put("downloads", 1000);
            if (query.equals("mass storage") && reviewed.get() > 0) hits.addObject().put("slug", "warehouse").put("title", "Mass storage")
                    .put("description", "Mass storage for items").put("downloads", 100);
            return page;
        });
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenAnswer(call -> {
            int phase = reviewed.incrementAndGet();
            var rows = phase == 1 ? List.of(new CriterionReview("工厂自动化", List.of(new RequirementReview.Contribution("factory", "Factory automation machines", "自动化")), ""),
                    new CriterionReview("大型仓储", List.of(), "尚缺仓储依据"), new CriterionReview("NeoForge1.21.1", List.of(), ""), new CriterionReview("约2～5个模组", List.of(), ""))
                    : List.of(new CriterionReview("大型仓储", List.of(new RequirementReview.Contribution("warehouse", "Mass storage for items", "仓储")), ""));
            return response(codec.write(new ReviewDecision(1, List.of(phase == 1 ? "factory" : "warehouse"), List.of(), false,
                    List.of(new RequirementReview(requirement.id(), List.of(), "", rows)))));
        });
        java.util.function.Function<Set<String>, ResolutionResult> resolved = roots -> {
            var graph = new DependencyGraph(); graph.allSlugs.addAll(roots);
            roots.forEach(s -> graph.selectedVersions.put(s, mapper.createObjectNode().put("id", s + "V").put("project_id", s)));
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        };
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> resolved.apply(call.getArgument(0)));
        when(engine.resolveUsingFacts(anySet(), anyString(), anyString(), any(), anyMap())).thenAnswer(call -> resolved.apply(call.getArgument(0)));
        String result = ReflectionTestUtils.invokeMethod(controller, "doChat", Map.of("prompt", prompt, "uuid", "fixture-session",
                "loader", "neoforge", "mcVersion", "1.21.1", "currentMods", ""), "fixture-token", null, null);
        assertTrue(result.contains("warehouse"), result);
        assertTrue(result.contains("APPLIED_ONE_PASS"), result);
        assertTrue(result.contains("\"status\":\"SUPPORTING_EVIDENCE\""), result);
        assertEquals(2, reviewed.get());
        verify(engine).resolveUsingFacts(eq(Set.of("factory", "warehouse")), eq("neoforge"), eq("1.21.1"), any(),
                argThat(facts -> facts.size() == 1 && facts.get("factory").path("id").asText().equals("factoryV")));
    }

    @Test void singleEditDoesNotRepairUnreviewedHistoricalCriteriaOrLaunchNewRecommendationCalls() {
        String oldSource = "工厂自动化和仓储";
        var criteria = List.of(new RequirementCriterion("工厂自动化", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(),
                        List.of(new BuildIntent.SearchIntent("technology", 100, List.of("automation")))),
                new RequirementCriterion("仓储", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(),
                        List.of(new BuildIntent.SearchIntent("storage", 100, List.of("storage")))));
        var prior = new BuildConstraints(3, List.of(), List.of(), List.of(), null, BuildIntent.ReplacementPolicy.NONE, 2100000000L,
                List.of(new BuildRequirement(RequirementPlanning.anchors(oldSource).keySet().iterator().next(), oldSource,
                        BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(), List.of(), SelectionPreferences.DEFAULT, criteria)));
        String prompt = "只加jade，不额外推荐";
        var fresh = new BuildRequirement(RequirementPlanning.anchors(prompt).keySet().iterator().next(), prompt,
                BuildRequirement.Kind.PROJECTS, List.of("jade"), List.of());
        var intent = new BuildIntent(3, BuildIntent.Mode.EDIT, "添加项目", "", BuildEnvironment.current("1.20.1", "fabric"), null,
                List.of(), List.of("jade"), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(),
                2100000000L, ConstraintChanges.KEEP, List.of(fresh));
        var codec = new AgentContractCodec();
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(codec.write(intent)));
        String result = ReflectionTestUtils.invokeMethod(controller, "doChat", Map.of("prompt", prompt, "currentMods", "factory", "uuid", "fixture-session",
                "loader", "fabric", "mcVersion", "1.20.1", "constraints", codec.write(prior)), "fixture-token", null, null);
        assertTrue(result.contains("<mods>"), result);
        assertEquals(Set.of("factory", "jade"), Set.copyOf(Arrays.asList(result.substring(result.indexOf("<mods>") + 6, result.indexOf("</mods>")).split(","))));
        verify(api, never()).searchPage(anyString(), anyInt(), anyInt(), any(), anyString());
        verify(agents, never()).criticPools(anyString(), anyString(), anySet(), anyInt());
    }
    @Test void finalOverlapGatePublishesNoManifestAndReleasesAccountRoundGuard() {
        var json = new tools.jackson.databind.ObjectMapper();
        when(users.validateToken("fixture-token")).thenReturn(1L);
        var intent = new BuildIntent(1, BuildIntent.Mode.NEW, "生态组合", "生态", BuildEnvironment.current("1.20.1", "fabric"),
                new BuildIntent.CountTarget(2, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of("animals-a", "animals-b"), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2100000000L);
        when(agents.planBlueprint(anyString(), anyString())).thenReturn(response(new AgentContractCodec().write(intent)));
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenAnswer(call -> json.createObjectNode()
                .put("id", "V" + call.getArgument(0)).put("project_id", "P" + call.getArgument(0)).set("dependencies", json.createArrayNode()));
        var facts = new HashMap<String, tools.jackson.databind.JsonNode>();
        for (String slug : List.of("animals-a", "animals-b")) facts.put(slug, json.createObjectNode().put("id", "P" + slug)
                .put("slug", slug).put("title", slug).put("description", "Adds real-world animals").put("downloads", 100));
        when(api.getProjectsBatch(anyCollection())).thenReturn(facts);
        when(api.getProjectInfo(anyString())).thenAnswer(call -> facts.get(call.getArgument(0)));
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            Set<String> roots = call.getArgument(0); var graph = new DependencyGraph(); graph.allSlugs.addAll(roots);
            roots.forEach(slug -> graph.selectedVersions.put(slug, json.createObjectNode().put("id", "V" + slug)
                    .put("project_id", "P" + slug).set("dependencies", json.createArrayNode())));
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        });
        String result = run("构建一个生态组合，包含 animals-a 和 animals-b");
        assertTrue(result.contains("replacement_options"), result);
        assertTrue(result.contains("OVERLAP"), result);
        assertFalse(result.contains("<mods>"), result);
        var guard = (yagen.waitmydawn.maa.runtime.RoundGuard) ReflectionTestUtils.getField(controller, "roundGuard");
        assertTrue(guard.begin("another-session", 1L)); guard.end("another-session");
        verify(agents, times(1)).planBlueprint(anyString(), anyString());
        verify(agents, never()).criticPools(anyString(), anyString(), anySet(), anyInt());
    }


    @Test void completeBuildReportsOnlyOptimizationRootsWithSelectedVersions() {
        var json = new tools.jackson.databind.ObjectMapper();
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            Set<String> roots = new LinkedHashSet<>(call.getArgument(0)); var graph = new DependencyGraph();
            graph.allSlugs.addAll(roots);
            for (String slug : roots) graph.selectedVersions.put(slug, json.createObjectNode().put("id", "V-" + slug)
                    .put("project_id", "P-" + slug).set("dependencies", json.createArrayNode()));
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        });
        String result = goalBuild("[]");
        var match = java.util.regex.Pattern.compile("<build_report>(.*?)</build_report>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(match.find()); var report = json.readTree(match.group(1)).path("optimization");
        assertEquals(1, report.path("modCount").asInt());
        assertEquals("sodium", report.path("entries").get(0).path("slug").asText());
        assertEquals("V-sodium", report.path("entries").get(0).path("versionId").asText());
        assertTrue(report.path("entries").get(0).path("roles").has("MEMORY"));
        assertFalse(result.contains("iconUrl"));
        verify(agents, times(1)).planBlueprint(anyString(), anyString());
        verify(agents, times(1)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }
}
