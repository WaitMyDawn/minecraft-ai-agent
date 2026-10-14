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
    private ChatController controller;
    private DependencyEngine engine;
    private AiAgentService agents;
    private ModrinthApiClient api;

    @BeforeEach void setup() {
        engine = mock(DependencyEngine.class);
        agents = mock(AiAgentService.class);
        api = mock(ModrinthApiClient.class);
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

    @Test void editAddsOnlyDeltaAndDoesNotGrowToDefaultHundred() {
        String result = assemble(intent(BuildIntent.Mode.EDIT, List.of("journeymap"), List.of(), null), "jei,create");
        assertTrue(result.contains("<mods>create,jei,journeymap</mods>"), result);
        assertTrue(result.contains("\"target\":3"), result);
        verifyNoInteractions(agents, api);
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

    @Test void explicitVetoCannotBeReintroducedByBackfill() {
        prepareAddonPool(new AgentContractCodec().write(new ReviewDecision(1, List.of("create-good"),
                List.of(new ReviewDecision.Rejection("create-vetoed", ReviewDecision.Reason.OFF_TOPIC, "主题不符")), true)));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertFalse(result.contains("<mods>create,create-good,create-vetoed</mods>"), result);
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
        assertTrue(result.contains("\"critic\":{\"ms\":3,\"calls\":2,\"in\":30,\"out\":30}"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents).criticPools(contains("修正以下上一响应，返回完整 JSON 对象：\n" + invalid),
                anyString(), anySet(), anyInt());
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
