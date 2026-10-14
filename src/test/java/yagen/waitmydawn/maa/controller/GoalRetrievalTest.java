package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.cache.ModrinthCacheService;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.*;
import yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability;
import yagen.waitmydawn.maa.service.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GoalRetrievalTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ModrinthApiClient api = mock(ModrinthApiClient.class);
    private final ChatController controller = new ChatController(mock(AiAgentService.class), mock(ModrinthCacheService.class), null,
            mock(DependencyEngine.class), api, null, null, null, null, null, null, null,
            mock(ExpansionTracker.class), null, mock(LoaderVersionService.class));
    private BuildExecutionPlan plan(Set<String> blocked) {
        var intent = new BuildIntent(1, BuildIntent.Mode.NEW, "组合", "", BuildEnvironment.current("1.21.1", "neoforge"),
                new BuildIntent.CountTarget(100, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of(), List.of(), List.of(), List.copyOf(blocked), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2100000000L);
        return new BuildExecutionPlan(intent, Set.of(), blocked);
    }
    @Test void optimizationSeedsStillNeedCompatibleVersionsAndRespectExclusions() {
        when(api.getProjectInfo(anyString())).thenAnswer(call -> mapper.createObjectNode().put("id", "P-" + call.getArgument(0))
                .put("slug", (String) call.getArgument(0)).put("title", (String) call.getArgument(0)).put("description", "Improves performance and reduces memory").put("downloads", 100));
        when(api.searchPage(anyString(), eq(15), eq(0), isNull(), anyString())).thenReturn(mapper.readTree("{\"hits\":[]}"));
        when(api.getLatestCompatibleVersionBySlug(anyString(), eq("1.21.1"), eq("neoforge")))
                .thenAnswer(call -> call.getArgument(0).equals("sodium") ? null : mapper.createObjectNode().put("id", "version"));
        var roles = new HashMap<String, Set<Capability>>();
        var facets = new HashMap<String, Set<String>>();
        List<ChatController.CandidateMod> pool = ReflectionTestUtils.invokeMethod(controller, "buildGoalPool",
                List.of(GoalCatalog.profile(Capability.PERFORMANCE, 100)), Set.<String>of(), "neoforge", "1.21.1", 2100000000L,
                plan(Set.of("entityculling")), new RetrievalBudget(100), "fixture", roles, facets);
        assertEquals(Set.of("ferrite-core", "modernfix"), pool.stream().map(c -> c.slug).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(Capability.PERFORMANCE), roles.get("ferrite-core"));
        verify(api, never()).getLatestCompatibleVersionBySlug(eq("entityculling"), anyString(), anyString());
    }
    @Test void netherMobRecallDoesNotRequireWorldgenTagAndLibraryHitsAreRejected() {
        when(api.getProjectInfo("existing-biomes")).thenReturn(mapper.createObjectNode().put("id", "B1").put("slug", "existing-biomes")
                .put("title", "Nether Biomes").put("description", "Adds Nether biomes"));
        when(api.getProjectInfo("existing-structures")).thenReturn(mapper.createObjectNode().put("id", "S1").put("slug", "existing-structures")
                .put("title", "Nether Structures").put("description", "Adds Nether structures"));
        when(api.searchPage(anyString(), eq(15), eq(0), isNull(), anyString())).thenAnswer(call -> {
            String query = call.getArgument(0);
            return mapper.readTree(query.endsWith("mobs")
                    ? "{\"hits\":[{\"slug\":\"nether-beasts\",\"title\":\"Nether Beasts\",\"description\":\"Adds hostile mobs to the Nether\",\"downloads\":100},{\"slug\":\"nether-api\",\"title\":\"Nether API\",\"description\":\"Library for Nether mobs\",\"downloads\":10000}]}"
                    : "{\"hits\":[]}");
        });
        when(api.getLatestCompatibleVersionBySlug("nether-beasts", "1.21.1", "neoforge"))
                .thenReturn(mapper.createObjectNode().put("id", "version"));
        List<ChatController.CandidateMod> pool = ReflectionTestUtils.invokeMethod(controller, "buildGoalPool",
                List.of(GoalCatalog.profile(Capability.NETHER, 100)), Set.of("existing-biomes", "existing-structures"), "neoforge", "1.21.1", 2100000000L,
                plan(Set.of()), new RetrievalBudget(100), "fixture", new HashMap<String, Set<Capability>>(), new HashMap<String, Set<String>>());
        assertEquals(List.of("nether-beasts"), pool.stream().map(c -> c.slug).toList());
        verify(api).searchPage(eq("nether mobs"), eq(15), eq(0), isNull(), contains("categories:mobs"));
        verify(api, never()).getLatestCompatibleVersionBySlug(eq("nether-api"), anyString(), anyString());
    }
    @Test void oneCompoundRequirementKeepsCandidatesFromLaterQueriesAndDeduplicatesSharedQueries() {
        var searches = List.of(new BuildIntent.SearchIntent("technology", 100, List.of("automation", "logistics")));
        var a = new BuildRequirement("r-0000000000000001", "自动化和物流", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), searches);
        var b = new BuildRequirement("r-0000000000000002", "物流运输", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(new BuildIntent.SearchIntent("technology", 100, List.of("logistics"))));
        when(api.searchPage(anyString(), eq(15), eq(0), isNull(), anyString())).thenAnswer(call -> {
            String query = call.getArgument(0);
            var page = mapper.createObjectNode();
            var hits = page.putArray("hits");
            for (int i = 0; i < 8; i++) hits.addObject().put("slug", query + "-" + i).put("title", query + " machines")
                    .put("description", query + " content").put("downloads", 10000 - i);
            return page;
        });
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(mapper.createObjectNode().put("id", "version"));
        var hints = new HashMap<String, Set<String>>();
        List<ChatController.CandidateMod> pool = ReflectionTestUtils.invokeMethod(controller, "buildRequirementPool",
                List.of(a, b), Set.<String>of(), "neoforge", "1.21.1", 2100000000L, plan(Set.of()), new RetrievalBudget(), "fixture", hints);
        assertTrue(pool.stream().anyMatch(c -> c.slug.startsWith("logistics")));
        assertEquals(Set.of(a.id(), b.id()), hints.get("logistics-0"));
        assertEquals(12, pool.size());
        verify(api, times(1)).searchPage(eq("logistics"), eq(15), eq(0), isNull(), anyString());
    }
    @Test void sameKeywordDifferentScopesKeepSeparateChannelsAndColdRanking() {
        var cold = new SelectionPreferences(SelectionPreferences.Popularity.LESS_POPULAR, SelectionPreferences.Freshness.RECENT);
        var query = List.of(new BuildIntent.SearchIntent("technology", 100, List.of("automation")));
        var a = new BuildRequirement("r-0000000000000001", "较新小众自动化", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), query, cold);
        var b = new BuildRequirement("r-0000000000000002", "常规自动化", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), query);
        when(api.searchPage(eq("automation"), eq(15), eq(0), isNull(), anyString())).thenReturn(mapper.readTree("""
                {"hits":[{"slug":"popular-automation","title":"Automation","downloads":10000000}]}
                """));
        when(api.searchPage(eq("automation"), eq(15), eq(0), eq("newest"), contains("downloads <= 500000")))
                .thenReturn(mapper.createObjectNode().set("hits", mapper.createArrayNode().add(mapper.createObjectNode()
                        .put("slug", "niche-automation").put("title", "Automation").put("downloads", 100)
                        .put("date_created", java.time.Instant.now().minusSeconds(86400).toString()))));
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString()))
                .thenReturn(mapper.createObjectNode().put("id", "version"));
        var hints = new HashMap<String, Set<String>>();
        var budget = new RetrievalBudget();
        List<ChatController.CandidateMod> pool = ReflectionTestUtils.invokeMethod(controller, "buildRequirementPool",
                List.of(a, b), Set.<String>of(), "neoforge", "1.21.1", 2100000000L, plan(Set.of()), budget, "fixture", hints);
        assertEquals("niche-automation", pool.getFirst().slug);
        assertEquals(Set.of(a.id()), hints.get("niche-automation"));
        assertEquals(Set.of(a.id(), b.id()), hints.get("popular-automation"));
        verify(api, times(2)).searchPage(eq("automation"), eq(15), eq(0), isNull(), anyString());
        assertEquals(3, budget.snapshot().get("searchQueries"));
    }


    private tools.jackson.databind.JsonNode optimizationInfo(String slug, String description, String client, String server) {
        return mapper.createObjectNode().put("slug", slug).put("title", slug).put("description", description)
                .put("client_side", client).put("server_side", server).put("downloads", 100);
    }
    private List<ChatController.CandidateMod> optimizationPool(String request, Set<String> blocked, RetrievalBudget budget) {
        var requirement = new BuildRequirement("r-0000000000000001", request, BuildRequirement.Kind.CAPABILITY, List.of(), List.of(Capability.PERFORMANCE));
        return ReflectionTestUtils.invokeMethod(controller, "buildOptimizationPool", List.of(OptimizationPortfolio.goal(requirement).orElseThrow()),
                Set.<String>of(), "neoforge", "1.21.1", 2100000000L, plan(blocked), budget, "fixture",
                new HashMap<String, Set<String>>(), new HashMap<String, OptimizationPortfolio.Fact>(), 100);
    }
    @Test void incompatibleSeedDoesNotBlockAlternativeFunctionRecall() {
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            Map<String, tools.jackson.databind.JsonNode> data = new HashMap<>();
            for (String slug : (Collection<String>) call.getArgument(0)) {
                if (slug.equals("sodium") || slug.equals("alternative-renderer")) data.put(slug,
                        optimizationInfo(slug, "Improves frame rates with a modern rendering engine", "required", "unsupported"));
            }
            return data;
        });
        when(api.searchPage(anyString(), eq(15), eq(0), isNull(), anyString())).thenAnswer(call -> mapper.readTree(
                call.getArgument(0).equals("rendering engine optimization")
                        ? "{\"hits\":[{\"slug\":\"alternative-renderer\",\"description\":\"Improves frame rates with a modern rendering engine\",\"downloads\":100}]}"
                        : "{\"hits\":[]}"));
        when(api.getLatestCompatibleVersionBySlug(eq("alternative-renderer"), anyString(), anyString())).thenReturn(mapper.createObjectNode().put("id", "V1"));
        var budget = new RetrievalBudget();
        var pool = optimizationPool("客户端渲染优化", Set.of(), budget);
        assertEquals(List.of("alternative-renderer"), pool.stream().map(c -> c.slug).toList());
        verify(api).getLatestCompatibleVersionBySlug("sodium", "1.21.1", "neoforge");
        verify(api).searchPage(eq("rendering engine optimization"), eq(15), eq(0), isNull(), anyString());
        assertTrue(budget.snapshot().get("searchQueries") <= 4);
    }
    @Test void scopedServerMemoryRejectsClientFactsAndHonorsBlockedSeeds() {
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            Map<String, tools.jackson.databind.JsonNode> data = new HashMap<>();
            for (String slug : (Collection<String>) call.getArgument(0)) if (slug.equals("ferrite-core"))
                data.put(slug, optimizationInfo(slug, "Reduces memory usage", "required", "unsupported"));
            return data;
        });
        when(api.searchPage(anyString(), eq(15), eq(0), isNull(), anyString())).thenReturn(mapper.readTree("{\"hits\":[]}"));
        assertTrue(optimizationPool("服务器内存优化", Set.of("modernfix"), new RetrievalBudget()).isEmpty());
        verify(api, never()).getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString());
        verify(api).searchPage(eq("memory usage optimization"), eq(15), eq(0), isNull(), anyString());
        verify(api, never()).searchPage(contains("rendering"), anyInt(), anyInt(), any(), anyString());
    }
    @Test void supplementarySearchUsesSharedBudgetAndCannotInventCompatibleVersions() {
        when(api.getProjectsBatch(anyCollection())).thenReturn(Map.of());
        var budget = new RetrievalBudget();
        for (int i=0; i<RetrievalBudget.MAX_SEARCHES; i++) assertTrue(budget.search("filled-" + i));
        assertTrue(optimizationPool("很多优化模组", Set.of(), budget).isEmpty());
        verify(api, never()).searchPage(anyString(), anyInt(), anyInt(), any(), anyString());
        assertEquals(RetrievalBudget.MAX_SEARCHES, budget.snapshot().get("searchQueries"));
    }

    @Test void optimizationOrderingPreservesDistinctLegacyCapabilitySlots() {
        String id = "r-0000000000000001";
        var req = new BuildRequirement(id, "优化和实用", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(Capability.PERFORMANCE, Capability.UTILITY));
        var goal = OptimizationPortfolio.goal(req).orElseThrow();
        var facts = Map.of("sodium", new OptimizationPortfolio.Fact("sodium", OptimizationPortfolio.Side.BOTH, Map.of(OptimizationPortfolio.Role.MEMORY, "Reduces memory"), false),
                "ferrite-core", new OptimizationPortfolio.Fact("ferrite-core", OptimizationPortfolio.Side.BOTH, Map.of(OptimizationPortfolio.Role.MEMORY, "Reduces memory"), false));
        var hints = Map.of("sodium", Set.of(id), "ferrite-core", Set.of(id), "appleskin", Set.of(id));
        Map<String, Set<String>> ordering = ReflectionTestUtils.invokeMethod(controller, "optimizationOrderingHints", hints,
                Map.of("appleskin", Set.of(Capability.UTILITY)), facts, List.of(goal));
        assertEquals(List.of("sodium", "appleskin", "ferrite-core"), OptimizationPortfolio.order(List.of("sodium", "ferrite-core", "appleskin"), ordering, Set.of(), facts, List.of(goal), 2));
        assertEquals(Set.of(id), hints.get("appleskin"));
    }
}
