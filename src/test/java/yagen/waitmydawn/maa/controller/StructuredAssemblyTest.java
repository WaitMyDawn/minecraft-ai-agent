package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
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

class StructuredAssemblyTest {
    @Test void tailStagesSeparateValidationPresentationAndSerializationWithoutInventingARepair() {
        var context = new yagen.waitmydawn.maa.runtime.DelegationContext(1000);
        var plan = intent(BuildIntent.Mode.NEW, List.of("ordinary-core"), List.of(),
                new BuildIntent.CountTarget(1, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT));
        var output = new java.util.concurrent.atomic.AtomicReference<String>();
        yagen.waitmydawn.maa.runtime.DelegationContext.runWith(context, () -> output.set(assemble(plan, "")));
        assertTrue(output.get().contains("<mods>ordinary-core</mods>"), output.get());
        var titles = context.progress().snapshot().stream().map(yagen.waitmydawn.maa.runtime.BuildProgress.Entry::title).toList();
        var tail = List.of("解析依赖与协调版本", "核对需求覆盖与数量", "复核最终附属与生成准入",
                "整理类别与依赖问题", "核对逐项目标与用途证据", "汇总构建来源与诊断", "序列化构建结果");
        assertEquals(tail, titles.subList(titles.indexOf(tail.getFirst()), titles.size()));
        context.progress().finish();
        assertTrue(context.progress().snapshot().stream().allMatch(entry -> entry.endedAt() != null));
        verifyNoInteractions(agents);
    }

    @Test void partialAdmissionFactsStillBlockAnUnrequestedExtremePresetAndRemainReusable() {
        var json = new tools.jackson.databind.ObjectMapper();
        var known = json.createObjectNode().put("id", "Preset01").put("slug", "world-preset")
                .put("description", "Classic Skyblock with infinite void generation");
        when(api.getProjectsBatch(anyCollection())).thenThrow(new ModrinthApiClient.PartialProjectBatchException(Map.of("world-preset", known)));
        Map<String, String> facts = new LinkedHashMap<>();
        Map<String, ?> blocked = ReflectionTestUtils.invokeMethod(controller, "generationAdmission",
                List.of("world-preset", "unknown"), "冒险整合包", facts);
        assertTrue(blocked.containsKey("world-preset"));
        assertFalse(blocked.containsKey("unknown"), "缺失资料只能视为未核实，不能编造准入依据");
        assertEquals(facts.get("world-preset"), facts.get("Preset01"));
        ReflectionTestUtils.invokeMethod(controller, "generationAdmission", List.of("world-preset", "Preset01"), "冒险整合包", facts);
        verify(api, times(1)).getProjectsBatch(anyCollection());
    }

    @Test void admissionCancellationCannotBeDowngradedIntoMissingDescriptions() {
        when(api.getProjectsBatch(anyCollection())).thenThrow(new java.util.concurrent.CancellationException());
        assertThrows(java.util.concurrent.CancellationException.class, () -> ReflectionTestUtils.invokeMethod(controller,
                "generationAdmission", List.of("ordinary-core"), "冒险整合包", new LinkedHashMap<String, String>()));
    }

    @Test void reportAlignmentRepairIsTargetedAndDoesNotReauthorizeSelection() {
        checkReportAlignmentRepair(false, false);
    }

    @Test void reportRepairChangingPermissionsKeepsOriginalVetoAndBackfillRestriction() {
        checkReportAlignmentRepair(true, false);
    }

    @Test void repeatedAlignmentFailureStillBuildsFromLegalSelectionWithoutAThirdRepair() {
        checkReportAlignmentRepair(false, true);
    }

    private void checkReportAlignmentRepair(boolean changePermissions, boolean stillInvalid) {
        var json = new tools.jackson.databind.ObjectMapper();
        var criteria = List.of(new RequirementCriterion("物流", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()),
                new RequirementCriterion("仓储", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()));
        var requirement = new BuildRequirement("r-0000000000000001", "物流和仓储", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(), SelectionPreferences.DEFAULT, criteria);
        var base = addonIntent();
        var plan = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(),
                base.add(), base.remove(), base.exclude(), base.expandAddons(), base.replacementPolicy(), base.searchIntents(),
                base.excludedCategories(), base.maxDownloads(), base.constraintChanges(), List.of(requirement));
        var selection = new ReviewResponse(1, List.of("create-good", "create-vetoed"),
                List.of(new ReviewDecision.Rejection("create-vetoed", ReviewDecision.Reason.OFF_TOPIC, "不符合主题")), false,
                List.of(new RequirementReview(requirement.id(), List.of(), "错误的父项缺口",
                        List.of(new CriterionReview("物流", List.of(), "缺少依据")))));
        String invalid = json.writeValueAsString(selection);
        var corrected = (tools.jackson.databind.node.ObjectNode) json.readTree(invalid);
        corrected.set("requirements", json.valueToTree(List.of(new RequirementReview(requirement.id(), List.of(), "",
                criteria.stream().map(c -> new CriterionReview(c.source(), List.of(), "缺少可核实依据")).toList()))));
        if (changePermissions) { corrected.putArray("rejected"); corrected.put("allowBackfill", true); }
        prepareAddonPool(invalid);
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(invalid, 1, 1, 10, 10),
                new AiAgentService.AgentCallResult(stillInvalid ? invalid : corrected.toString(), 1, 1, 10, 10));
        String result = assemble(plan, "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"allowBackfill\":false"), result);
        assertTrue(result.contains("\"criticCoverageDegraded\":" + (changePermissions || stillInvalid)), result);
        verify(agents, times(2)).criticPools(anyString(), anyString(), anySet(), anyInt());
        verify(agents).criticPools(argThat((String context) -> context.contains("【用途报告定向修正】")
                && context.contains("MISSING_CRITERION") && context.contains("PARENT_GAP")
                && context.contains("【审核子目标结构骨架】")), anyString(), anySet(), anyInt());
    }
    @Test void confirmedProjectFactsReachFinalCoverageEvenWhenNamedOnlyBuildSkipsCritic() {
        var json = new tools.jackson.databind.ObjectMapper();
        var base = intent(BuildIntent.Mode.NEW, List.of("terrain-core"), List.of(),
                new BuildIntent.CountTarget(1, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT));
        var requirement = new BuildRequirement("r-0000000000000001", "丰富下界生态", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of());
        var plan = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(), base.add(),
                base.remove(), base.exclude(), base.expandAddons(), base.replacementPolicy(), base.searchIntents(), base.excludedCategories(),
                base.maxDownloads(), base.constraintChanges(), List.of(requirement));
        var version = json.createObjectNode().put("id", "VCore").put("project_id", "PCore"); version.putArray("dependencies");
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(version);
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            var graph = new DependencyGraph(); graph.allSlugs.add("terrain-core"); graph.selectedVersions.put("terrain-core", version);
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of("terrain-core"), List.of(), List.of(), 500, false);
        });
        var state = new PackSessionState(); state.setGenerationFacts(Map.of("terrain-core", "Overhauls Nether terrain with many new biomes"));
        String result = ReflectionTestUtils.invokeMethod(controller, "processAndAssembleBlueprint", new AgentContractCodec().write(plan), "",
                "仅添加terrain-core，不额外推荐", "fixture-key", "fixture-session", state, Set.<String>of(), KnowledgeRule.SourceType.ADMIN,
                new AiAgentService.AgentCallResult("", 1, 1, 10, 10), "1.21.1", "neoforge", null);
        var match = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(match.find(), result);
        var row = json.readTree(match.group(1)).path("requirements").get(0);
        assertEquals("SUPPORTING_EVIDENCE", row.path("status").asText());
        assertEquals("PROJECT_GENERATION", row.path("mods").get(0).path("evidenceType").asText());
        verifyNoInteractions(agents);
    }
    @Test void dimensionCoreRemainsInTheCompatiblePoolAheadOfPopularLocalStructures() {
        var json = new tools.jackson.databind.ObjectMapper();
        var hits = json.createObjectNode(); var values = hits.putArray("hits");
        for (int i = 0; i < 20; i++) values.addObject().put("slug", "nether-structure-" + i).put("title", "Nether structures")
                .put("description", "Adds Nether structures and fortresses").put("downloads", 10000000);
        values.addObject().put("slug", "terrain-core").put("title", "Dimension Expansion")
                .put("description", "Nether expansion with custom terrain and new biomes").put("downloads", 100);
        when(api.searchPage(anyString(), anyInt(), anyInt(), nullable(String.class), anyString())).thenReturn(hits);
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(json.createObjectNode().put("id", "compatible-version"));
        var requirement = new BuildRequirement("r-0000000000000001", "丰富下界生态", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(new BuildIntent.SearchIntent("adventure", 100, List.of("nether structures"))));
        var base = intent(BuildIntent.Mode.NEW, List.of(), List.of(), new BuildIntent.CountTarget(20, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE));
        var plan = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(), base.add(),
                base.remove(), base.exclude(), base.expandAddons(), base.replacementPolicy(), base.searchIntents(), base.excludedCategories(),
                base.maxDownloads(), base.constraintChanges(), List.of(requirement));
        var hints = new LinkedHashMap<String, Set<String>>(); var budget = new RetrievalBudget();
        List<?> pool = ReflectionTestUtils.invokeMethod(controller, "buildRequirementPool", List.of(requirement), Set.of(), "neoforge", "1.21.1",
                base.maxDownloads(), new BuildExecutionPlan(plan, Set.of(), Set.of()), budget, "fixture-session", hints);
        assertNotNull(pool);
        assertTrue(hints.containsKey("terrain-core"), hints.toString());
        assertTrue(hints.get("terrain-core").contains(requirement.id() + "::generation:nether"));
        var searches = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(api, atLeastOnce()).searchPage(searches.capture(), anyInt(), anyInt(), nullable(String.class), anyString());
        assertEquals("nether terrain overhaul", searches.getAllValues().getFirst());
        assertTrue(budget.snapshot().get("searchQueries") <= RetrievalBudget.MAX_SEARCHES);
        assertTrue(pool.size() <= 8);
    }
    @Test void namedOnlyCountTargetCannotTriggerFillersButStillResolvesRequiredDependencies() {
        var base = intent(BuildIntent.Mode.NEW, List.of("core-mod"), List.of(),
                new BuildIntent.CountTarget(14, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE, 8, 20));
        var p = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(), base.add(),
                base.remove(), base.exclude(), List.of(), base.replacementPolicy(), List.of(new BuildIntent.SearchIntent("worldgen", 100, List.of("biomes"))),
                base.excludedCategories(), base.maxDownloads());
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(
                new tools.jackson.databind.ObjectMapper().createObjectNode().put("id", "core-version").put("project_id", "core-project"));
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            var graph = new DependencyGraph(); graph.allSlugs.addAll(Set.of("core-mod", "library")); graph.addEdge("core-mod", "library");
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of("core-mod"), List.of(), List.of(), 500, false);
        });
        String result = ReflectionTestUtils.invokeMethod(controller, "processAndAssembleBlueprint", new AgentContractCodec().write(p), "",
                "包含core-mod，不额外推荐", "fixture-key", "fixture-session", new PackSessionState(), Set.<String>of(), KnowledgeRule.SourceType.ADMIN,
                new AiAgentService.AgentCallResult("", 1, 1, 10, 10), "1.21.1", "neoforge", null);
        assertTrue(result.contains("<mods>core-mod,library</mods>"), result);
        assertTrue(result.contains("数量要求尚未满足"), result);
        verify(api, never()).search(anyString(), anyInt(), anyString());
        verifyNoInteractions(agents);
    }
    private ChatController controller;
    private DependencyEngine engine;
    private AiAgentService agents;
    private ModrinthApiClient api;

    @BeforeEach void setup() {
        engine = mock(DependencyEngine.class);
        agents = mock(AiAgentService.class);
        api = mock(ModrinthApiClient.class);
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var facts = new LinkedHashMap<String, tools.jackson.databind.JsonNode>();
            for (String slug : (Collection<String>) call.getArgument(0)) facts.put(slug,
                    new tools.jackson.databind.ObjectMapper().createObjectNode().put("slug", slug).put("description", "Adds ordinary content"));
            return facts;
        });
        var expansion = mock(ExpansionTracker.class);
        when(expansion.factor(anyString(), anyString())).thenReturn(2.0);
        controller = new ChatController(agents, mock(ModrinthCacheService.class), null, engine, api,
                null, null, null, null, null, null, null, expansion, null, mock(LoaderVersionService.class));
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            Set<String> roots = new LinkedHashSet<>(call.getArgument(0));
            var graph = new DependencyGraph();
            graph.allSlugs.addAll(roots);
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        });
    }

    private BuildIntent intent(BuildIntent.Mode mode, List<String> add, List<String> remove, BuildIntent.CountTarget count) {
        return new BuildIntent(1, mode, "计划说明", "", new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, false),
                count, add, add, remove, List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2_100_000_000L);
    }

    private String assemble(BuildIntent intent, String current) {
        return ReflectionTestUtils.invokeMethod(controller, "processAndAssembleBlueprint",
                new AgentContractCodec().write(intent), current, "需求", "fixture-key", "fixture-session", new PackSessionState(),
                Set.<String>of(), KnowledgeRule.SourceType.ADMIN,
                new AiAgentService.AgentCallResult("", 1, 1, 10, 10), "1.21.1", "neoforge", null);
    }

    @Test void inferredExtremeRootIsExcludedButExplicitSkyblockRequestCanKeepIt() {
        var json = new tools.jackson.databind.ObjectMapper();
        when(api.getProjectsBatch(anyCollection())).thenReturn(Map.of("world-preset",
                json.createObjectNode().put("slug", "world-preset").put("description", "Classic Skyblock with infinite void generation")));
        var plan = intent(BuildIntent.Mode.NEW, List.of("world-preset"), List.of(),
                new BuildIntent.CountTarget(1, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT));
        String result = assemble(plan, "");
        assertFalse(result.contains("<mods>world-preset</mods>"), result);
        var state = new PackSessionState();
        String explicit = ReflectionTestUtils.invokeMethod(controller, "processAndAssembleBlueprint", new AgentContractCodec().write(plan), "",
                "构建一个空岛整合包", "fixture-key", "fixture-session", state, Set.<String>of(), KnowledgeRule.SourceType.ADMIN,
                new AiAgentService.AgentCallResult("", 1, 1, 10, 10), "1.21.1", "neoforge", null);
        assertTrue(explicit.contains("<mods>world-preset</mods>"), explicit);
    }

    @Test void dependencyIntroducedExtremePresetStopsPublicationWithoutRemovingItsRequiredEdge() {
        var json = new tools.jackson.databind.ObjectMapper();
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var facts = new LinkedHashMap<String, tools.jackson.databind.JsonNode>();
            for (String slug : (Collection<String>) call.getArgument(0)) facts.put(slug, json.createObjectNode().put("slug", slug)
                    .put("description", slug.equals("hidden-preset") ? "Creates worlds with missing terrain" : "Adds an adventure system"));
            return facts;
        });
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            var graph = new DependencyGraph(); graph.allSlugs.addAll(Set.of("adventure-core", "hidden-preset"));
            graph.addEdge("adventure-core", "hidden-preset");
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of("adventure-core"), List.of(), List.of(), 500, false);
        });
        var plan = intent(BuildIntent.Mode.NEW, List.of("adventure-core"), List.of(),
                new BuildIntent.CountTarget(2, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE));
        String result = assemble(plan, "");
        assertFalse(result.contains("<mods>"), result);
        assertTrue(result.contains("hidden-preset"), result);
        assertTrue(result.contains("本轮未修改清单"), result);
    }

    @Test void bodyOnlySubtractiveAddonCannotReachCriticOrBackfill() {
        prepareAddonPool(new AgentContractCodec().write(new ReviewDecision(1, List.of("create-good"), List.of(), true)));
        var json = new tools.jackson.databind.ObjectMapper();
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var facts = new LinkedHashMap<String, tools.jackson.databind.JsonNode>();
            for (String slug : (Collection<String>) call.getArgument(0)) facts.put(slug, json.createObjectNode().put("slug", slug)
                    .put("description", "Adds content").put("body", slug.equals("create-vetoed") ? "Removes structures, mobs and bosses from a core mod" : "Adds machines"));
            return facts;
        });
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        verify(agents).criticPools(anyString(), anyString(), eq(Set.of("create-good")), anyInt());
    }

    @Test void editAddsOnlyDeltaAndDoesNotGrowToDefaultHundred() {
        String result = assemble(intent(BuildIntent.Mode.EDIT, List.of("journeymap"), List.of(), null), "jei,create");
        assertTrue(result.contains("<mods>create,jei,journeymap</mods>"), result);
        assertTrue(result.contains("\"target\":3"), result);
        verifyNoInteractions(agents);
        verify(api, never()).search(anyString(), anyInt(), anyString());
        verify(api).getProjectsBatch(List.of("journeymap"));
    }

    @Test void newBuildDoesNotCarryOldPackOrSilentlyCapNamedModsAtThirty() {
        List<String> named = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "mod-" + i).toList();
        var count = new BuildIntent.CountTarget(40, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT);
        String result = assemble(intent(BuildIntent.Mode.NEW, named, List.of(), count), "old-pack-mod");
        assertFalse(result.contains("old-pack-mod"), result);
        assertTrue(result.contains("mod-39"), result);
        assertFalse(result.contains("严格数量尚未满足"), result);
    }

    @Test void emptyClearsExistingPackAndDoesNotMisclassifyAsFormatError() {
        var count = new BuildIntent.CountTarget(0, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.EXACT);
        String result = assemble(intent(BuildIntent.Mode.EMPTY, List.of(), List.of(), count), "create,jei");
        assertTrue(result.contains("<mods></mods>"), result);
        assertFalse(result.contains("未输出构筑标签"), result);
        verifyNoInteractions(engine, agents, api);
    }
    @Test void totalUpstreamFailureCannotPublishAnEmptyPackOrOverwriteAnEdit() {
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> new ResolutionResult(
                new DependencyGraph(), ResolutionResult.Status.INCOMPLETE, call.getArgument(0),
                List.of(new ResolutionResult.Unresolved("candidate", "", ResolutionResult.Reason.UPSTREAM_UNAVAILABLE, "fixture network failure")),
                List.of(), 500, false));
        for (var mode : List.of(BuildIntent.Mode.NEW, BuildIntent.Mode.EDIT)) {
            var count = mode == BuildIntent.Mode.NEW
                    ? new BuildIntent.CountTarget(1, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT) : null;
            String result = assemble(intent(mode, List.of("candidate"), List.of(), count), "old-mod");
            assertFalse(result.contains("<mods>"), result);
            assertTrue(result.contains("本轮未修改整合包清单"), result);
            assertTrue(result.contains("\"status\":\"INCOMPLETE\""), result);
            assertFalse(result.contains("<constraints>"), result);
        }
    }

    @Test void removedModCannotReturnAsDependencyAndMissingRequiredModIsReported() {
        String result = assemble(intent(BuildIntent.Mode.EDIT, List.of("journeymap"), List.of("create"), null), "create,jei");
        assertTrue(result.contains("<mods>jei,journeymap</mods>"), result);
        assertTrue(result.contains("\"removed\":[\"create\"]"), result);
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenReturn(
                new ResolutionResult(new DependencyGraph(), ResolutionResult.Status.INCOMPLETE,
                        Set.of("journeymap"), List.of(), List.of(), 500, false));
        assertTrue(assemble(intent(BuildIntent.Mode.EDIT, List.of("journeymap"), List.of(), null), "")
                .contains("未满足必选模组：journeymap"));
    }

    private BuildIntent addonIntent() {
        var original = intent(BuildIntent.Mode.EDIT, List.of(), List.of(), null);
        return new BuildIntent(1, original.mode(), original.reply(), "", original.environment(), null,
                List.of("create"), List.of(), List.of(), List.of(), List.of("create"), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2_100_000_000L);
    }

    private void prepareAddonPool(String decision) {
        var mapper = new tools.jackson.databind.ObjectMapper();
        when(api.getProjectInfo(anyString())).thenAnswer(call -> mapper.createObjectNode()
                .put("id", "fixture-id").put("slug", (String) call.getArgument(0)));
        var version = mapper.createObjectNode().put("id", "fixture-version");
        version.putArray("dependencies").addObject().put("project_id", "fixture-id").put("dependency_type", "required");
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(version);
        when(api.search(anyString(), anyInt(), anyString())).thenReturn(mapper.readTree("""
                {"hits":[{"slug":"create-good","title":"Good addon","downloads":10000},
                         {"slug":"create-vetoed","title":"Rejected addon","downloads":100000}]}
                """));
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt()))
                .thenReturn(new AiAgentService.AgentCallResult(decision, 1, 1, 10, 10));
    }

    @Test void explicitlyNamedBridgeWithMissingPartnerPausesBeforeDependencyAssembly() {
        var json = new tools.jackson.databind.ObjectMapper();
        var core = json.createObjectNode().put("id", "CORE").put("slug", "cooking-core").put("title", "Cooking Core");
        core.putArray("categories").add("food");
        var peer = json.createObjectNode().put("id", "PEER").put("slug", "magic-core").put("title", "Magic Core");
        peer.putArray("categories").add("magic");
        var bridge = json.createObjectNode().put("id", "BRIDGE").put("slug", "bridge")
                .put("description", "Integration between cooking-core and magic-core");
        bridge.putArray("categories").add("food");
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var facts = new HashMap<String, tools.jackson.databind.JsonNode>();
            var all = Map.of("cooking-core", core, "bridge", bridge, "PEER", peer, "CORE", core);
            for (String key : (Collection<String>) call.getArgument(0)) if (all.containsKey(key)) facts.put(key, all.get(key));
            return facts;
        });
        var version = json.createObjectNode().put("id", "bridge-v").put("project_id", "BRIDGE");
        var deps = version.putArray("dependencies");
        deps.addObject().put("project_id", "CORE").put("dependency_type", "required");
        deps.addObject().put("project_id", "PEER").put("dependency_type", "optional");
        when(api.getLatestCompatibleVersionBySlug(eq("bridge"), anyString(), anyString())).thenReturn(version);
        String result = assemble(intent(BuildIntent.Mode.EDIT, List.of("bridge"), List.of(), null), "cooking-core");
        assertTrue(result.contains("magic-core"), result);
        assertTrue(result.contains("本轮未修改清单"), result);
        assertFalse(result.contains("<mods>"), result);
        verifyNoInteractions(engine);
    }
    @Test void explicitVetoCannotBeReintroducedByBackfill() {
        prepareAddonPool(new AgentContractCodec().write(new ReviewDecision(1, List.of("create-good"),
                List.of(new ReviewDecision.Rejection("create-vetoed", ReviewDecision.Reason.OFF_TOPIC, "主题不符")), true)));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertFalse(result.contains("<mods>create,create-good,create-vetoed</mods>"), result);
    }

    @Test void contradictoryVetoAndPurposeEvidenceAreRepairedOnceBeforeAssembly() {
        String contradictory = """
                {"schemaVersion":1,"approved":[],"rejected":[{"slug":"create-vetoed","reason":"OFF_TOPIC","explanation":"不相关"}],
                 "allowBackfill":true,"requirements":[{"id":"r-0000000000000001","evidence":[{"slug":"create-vetoed","quote":"Adds automation","purpose":"自动化"}],"gap":""}]}
                """;
        var corrected = new AgentContractCodec().write(new ReviewDecision(1, List.of("create-good"),
                List.of(new ReviewDecision.Rejection("create-vetoed", ReviewDecision.Reason.OFF_TOPIC, "不相关")), false));
        prepareAddonPool(contradictory);
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(contradictory, 1, 1, 10, 10), new AiAgentService.AgentCallResult(corrected, 1, 1, 10, 10));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents, times(2)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void failedRepairKeepsParsedVetoAndBackfillOptOutWithoutSalvagingBrokenJson() {
        for (boolean allowBackfill : List.of(false, true)) {
            String contradictory = """
                    {"schemaVersion":1,"approved":[],"rejected":[{"slug":"create-vetoed","reason":"OFF_TOPIC","explanation":"不相关"}],
                     "allowBackfill":%s,"requirements":[{"id":"r-0000000000000001","evidence":[{"slug":"create-vetoed","quote":"Adds automation","purpose":"自动化"}],"gap":""}]}
                    """.formatted(allowBackfill);
            prepareAddonPool(contradictory);
            when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                    new AiAgentService.AgentCallResult(contradictory, 1, 1, 10, 10), new AiAgentService.AgentCallResult("{broken", 1, 1, 10, 10));
            String result = assemble(addonIntent(), "create");
            String mods = result.split("<mods>")[1].split("</mods>")[0];
            assertFalse(mods.contains("create-vetoed"), result);
            if (!allowBackfill) assertEquals("create", mods);
            assertTrue(result.contains("\"criticDegraded\":false"), result);
            assertTrue(result.contains("\"criticCoverageDegraded\":true"), result);
            assertTrue(result.contains("\"allowBackfill\":" + allowBackfill), result);
        }
    }

    @Test void invalidCoverageCannotDiscardLegalSelectionOrReenableBackfillAfterBrokenRepair() {
        String invalidReport = """
                {"schemaVersion":1,"approved":["create-good","create-vetoed"],
                 "rejected":[{"slug":"create-vetoed","reason":"OFF_TOPIC","explanation":"主题不符"}],
                 "allowBackfill":false,"requirements":[{"id":"r-0000000000000001","evidence":"wrong-type"}]}
                """;
        prepareAddonPool(invalidReport);
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(invalidReport, 1, 1, 10, 10),
                new AiAgentService.AgentCallResult("{broken", 1, 1, 10, 10));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        assertTrue(result.contains("\"criticCoverageDegraded\":true"), result);
        assertTrue(result.contains("\"allowBackfill\":false"), result);
        verify(agents, times(2)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void invalidCoverageDoesNotPermitForeignCandidatesOrUnknownRootFields() {
        for (String invalid : List.of(
                "{\"schemaVersion\":1,\"approved\":[\"foreign-mod\"],\"rejected\":[],\"allowBackfill\":false,\"requirements\":\"bad\"}",
                "{\"schemaVersion\":1,\"approved\":[\"create-good\"],\"rejected\":[],\"allowBackfill\":false,\"unknown\":true,\"requirements\":\"bad\"}")) {
            prepareAddonPool(invalid);
            String result = assemble(addonIntent(), "create");
            assertTrue(result.contains("\"criticDegraded\":true"), result);
            assertTrue(result.contains("\"criticCoverageDegraded\":false"), result);
            assertFalse(result.contains("foreign-mod"), result);
        }
    }

    @Test void explicitCoreAddonSurvivesBroadGoalsExhaustingTheSharedSearchBudget() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        when(api.getProjectInfo(anyString())).thenAnswer(call -> {
            String slug = call.getArgument(0);
            return mapper.createObjectNode().put("id", slug.equals("core-mod") ? "core-project" : slug + "-project")
                    .put("slug", slug).put("title", slug.equals("core-mod") ? "Core Mod" : slug).put("body", "Provides automation gameplay");
        });
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenAnswer(call -> {
            String slug = call.getArgument(0);
            var version = mapper.createObjectNode().put("id", "version-" + slug).put("project_id", slug + "-project");
            var deps = version.putArray("dependencies");
            if (slug.equals("linked-extension")) deps.addObject().put("project_id", "core-project").put("dependency_type", "required");
            return version;
        });
        var addonSearch = mapper.createObjectNode();
        addonSearch.putArray("hits").addObject().put("slug", "linked-extension").put("title", "Core Mod Extension")
                .put("description", "Adds automation features to Core Mod").put("downloads", 5000);
        when(api.search(eq("Core Mod"), anyInt(), anyString())).thenReturn(addonSearch);
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        when(api.searchPage(anyString(), anyInt(), anyInt(), nullable(String.class), anyString())).thenAnswer(call -> {
            var result = mapper.createObjectNode(); var hits = result.putArray("hits");
            int group = sequence.incrementAndGet();
            hits.addObject().put("slug", "linked-extension").put("title", "Core Mod Automation Extension")
                    .put("description", "Provides automation gameplay").put("downloads", 5000).putArray("versions").add("1.21.1");
            for (int i = 0; i < 14; i++) hits.addObject().put("slug", "automation-" + group + "-" + i)
                    .put("title", "Automation gameplay").put("description", "Provides automation gameplay")
                    .put("downloads", 10000).putArray("versions").add("1.21.1");
            return result;
        });
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenAnswer(call -> {
            Set<String> candidates = call.getArgument(2);
            return new AiAgentService.AgentCallResult(new AgentContractCodec().write(new ReviewDecision(1,
                    candidates.stream().sorted().toList(), List.of(), false)), 1, 1, 10, 10);
        });
        var requirements = new ArrayList<BuildRequirement>();
        requirements.add(new BuildRequirement("r-0000000000000001", "扩展核心模组附属", BuildRequirement.Kind.ADDONS,
                List.of("core-mod"), List.of(), List.of("core-mod")));
        for (int i = 0; i < 6; i++) {
            int goal = i;
            var keywords = java.util.stream.IntStream.range(0, 7).mapToObj(k -> "automation " + goal + " " + k).toList();
            requirements.add(new BuildRequirement(String.format("r-%016x", i + 2), "自动化玩法 " + i,
                    BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(),
                    List.of(new BuildIntent.SearchIntent("technology", 100, keywords))));
        }
        var base = intent(BuildIntent.Mode.NEW, List.of("core-mod"), List.of(),
                new BuildIntent.CountTarget(150, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE));
        var plan = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(),
                base.add(), base.remove(), base.exclude(), List.of("core-mod"), base.replacementPolicy(), List.of(),
                List.of(), base.maxDownloads(), base.constraintChanges(), requirements);
        String result = assemble(plan, "");
        String mods = result.split("<mods>")[1].split("</mods>")[0];
        assertTrue(Arrays.asList(mods.split(",")).contains("linked-extension"), result);
        assertEquals(1, Arrays.stream(mods.split(",")).filter("linked-extension"::equals).count());
        assertTrue(sequence.get() >= 30, "Broad goals must exercise the shared query budget");
        var trace = mapper.readTree(result.split("<trace>")[1].split("</trace>")[0]);
        assertEquals(RetrievalBudget.MAX_SEARCHES, trace.path("retrieval").path("searchQueries").asInt());
        assertTrue(trace.path("retrieval").path("candidateVersions").asInt()
                <= trace.path("retrieval").path("versionLimit").asInt());
        assertTrue(trace.has("candidatePool"));
        assertTrue(trace.path("candidatePool").asInt() <= 150);
        verify(api).search(eq("Core Mod"), anyInt(), anyString());
    }

    @Test void explicitWholePoolVetoDoesNotTriggerFallback() {
        prepareAddonPool(new AgentContractCodec().write(new ReviewDecision(1, List.of(), List.of(), false)));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create</mods>"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        assertTrue(result.contains("\"backfilled\":0"), result);
    }

    @Test void conflictingApprovalCannotOverrideVetoEvenDuringBackfill() {
        prepareAddonPool("""
                {"schemaVersion":1,"approved":["create-good","create-vetoed"],
                 "rejected":[{"slug":"create-vetoed","reason":"CONSTRAINT","explanation":"明确排除"}],"allowBackfill":true}
                """);
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"overlapRemoved\":1"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents, times(1)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void wholePoolConflictAndVetoDoesNotRefillTheRejectedCandidates() {
        prepareAddonPool("""
                {"schemaVersion":1,"approved":["create-good","create-vetoed"],"rejected":[
                 {"slug":"create-good","reason":"CONSTRAINT","explanation":"明确排除"},
                 {"slug":"create-vetoed","reason":"CONSTRAINT","explanation":"明确排除"}],"allowBackfill":true}
                """);
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create</mods>"), result);
        assertTrue(result.contains("\"backfilled\":0"), result);
        assertTrue(result.contains("\"overlapRemoved\":2"), result);
    }

    @Test void excessiveApprovalsAreRankedAndBudgetedWithoutLosingVetoOrBackfillOptOut() {
        prepareAddonPool("""
                {"schemaVersion":1,"approved":["addon-0","addon-1","addon-2","addon-3","addon-4","addon-5"],
                 "rejected":[{"slug":"addon-vetoed","reason":"CONSTRAINT","explanation":"明确排除"}],"allowBackfill":false}
                """);
        var mapper = new tools.jackson.databind.ObjectMapper();
        var hits = mapper.createObjectNode();
        var array = hits.putArray("hits");
        for (int i = 0; i < 6; i++) array.addObject().put("slug", "addon-" + i).put("title", "Create addon")
                .put("downloads", (i + 1) * 10000);
        array.addObject().put("slug", "addon-vetoed").put("title", "Create veto").put("downloads", 900000);
        array.addObject().put("slug", "addon-unselected").put("title", "Create unselected").put("downloads", 800000);
        when(api.search(anyString(), anyInt(), anyString())).thenReturn(hits);
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>addon-1,addon-2,addon-3,addon-4,addon-5,create</mods>"), result);
        assertTrue(result.contains("\"excessApproved\":1"), result);
        assertTrue(result.contains("\"allowBackfill\":false"), result);
        assertTrue(result.contains("\"backfilled\":0"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents, times(1)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void invalidDecisionIsRepairedBeforeBackfillAndBothCallsAreCounted() {
        var codec = new AgentContractCodec();
        String invalid = codec.write(new ReviewDecision(1, List.of("outside-pool"), List.of(), false));
        prepareAddonPool(invalid);
        String corrected = codec.write(new ReviewDecision(1, List.of("create-good"), List.of(), false));
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(invalid, 1, 1, 10, 10),
                new AiAgentService.AgentCallResult(corrected, 2, 1, 20, 20));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"critic\":{\"ms\":3,\"calls\":2,\"in\":30,\"out\":30,\"truncatedCalls\":0}"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents).criticPools(contains("修正以下上一响应，返回完整 JSON 对象：\n" + invalid),
                anyString(), anySet(), anyInt());
    }

    @Test void truncatedReviewIsRepairedOnceWithoutExecutingPartialPermissions() {
        String truncated = "{\"schemaVersion\":1,\"approved\":[\"create-vetoed\"],\"rejected\":[";
        prepareAddonPool(truncated);
        String corrected = new AgentContractCodec().write(new ReviewDecision(1, List.of("create-good"), List.of(), false));
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(truncated, 1, 1, 10, 32768, 1),
                new AiAgentService.AgentCallResult(corrected, 2, 1, 20, 20));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"out\":32788,\"truncatedCalls\":1"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents, times(2)).criticPools(anyString(), anyString(), anySet(), anyInt());
        verify(agents).criticPools(contains("finish_reason=length"), anyString(), anySet(), anyInt());
    }

    @Test void legacyXmlStillProducesReadablePackReply() {
        String result = ReflectionTestUtils.invokeMethod(controller, "processAndAssembleBlueprint",
                "旧计划<name>旧包</name><mc>1.21.1</mc><loader>neoforge</loader><target_count>1</target_count>"
                        + "<core_mods>jei</core_mods><expand_addons></expand_addons><search_intents></search_intents>",
                "", "需求", "fixture-key", "fixture-session", new PackSessionState(), Set.<String>of(),
                KnowledgeRule.SourceType.ADMIN, new AiAgentService.AgentCallResult("", 1, 1, 10, 10),
                "1.21.1", "neoforge", null);
        assertTrue(result.contains("<mods>jei</mods>"), result);
        assertTrue(result.contains("<name>旧包</name>"), result);
    }

    @Test void upstreamFailureIsNotEvidenceThatNamedModDoesNotExist() {
        when(api.getProjectInfo("create")).thenThrow(new ModrinthApiClient.UnavailableException("fixture unavailable"));
        Set<String> unverified = new LinkedHashSet<>();
        Set<String> sanitized = ReflectionTestUtils.invokeMethod(controller, "sanitizePackInitialMods", Set.of("create"),
                "neoforge", "1.21.1", unverified, new ArrayList<>(), Set.of(), BuildIntent.ReplacementPolicy.NONE);
        assertEquals(Set.of("create"), sanitized);
        assertEquals(Set.of("create"), unverified);
        verify(api, never()).search(anyString(), anyInt(), anyString());
    }

    @Test void unspecifiedEditQuantityDoesNotSuggestDeletingRequiredLibrariesToMeetInventedGoal() {
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            Set<String> roots = new LinkedHashSet<>(call.getArgument(0));
            var graph = new DependencyGraph();
            graph.allSlugs.addAll(roots);
            graph.allSlugs.addAll(Set.of("library-one", "library-two", "library-three"));
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        });
        String result = assemble(intent(BuildIntent.Mode.EDIT, List.of("journeymap"), List.of("create"), null), "create,jei");
        assertFalse(result.contains("超过你期望的"), result);
        assertFalse(result.contains("建议优先移除"), result);
        assertTrue(result.contains("\"quantity\":null"), result);
    }

    @Test void relativeSearchWeightsAreNormalizedAndKeywordsAreNotSplitAgain() {
        List<ChatController.SearchIntent> intents = ChatController.structuredIntents(List.of(
                new BuildIntent.SearchIntent("magic", 100, List.of("spell", "mana:ritual")),
                new BuildIntent.SearchIntent("adventure", 100, List.of("boss, dungeon"))));
        assertEquals(50, intents.get(0).ratio());
        assertEquals(50, intents.get(1).ratio());
        assertEquals(List.of("mana:ritual"), intents.get(0).keywords().subList(1, 2));
        assertEquals(List.of("boss, dungeon"), intents.get(1).keywords());
        assertTrue(ChatController.structuredIntents(List.of()).isEmpty());
    }
}
