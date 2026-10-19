package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.runtime.DelegationContext;
import yagen.waitmydawn.maa.runtime.DelegationContext.Kind;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门面分流的离线测试：决定"这次取数交给谁做"。
 *
 * <p>最关键的一条是 {@code 委派命中时绝不碰出网层}——它同时证明了两件事：
 * ① 服务器没为这次取数花配额；② 客户端给的数据没有机会进入共享 Caffeine 缓存
 * （缓存写在出网层里，不进那一层就写不进去）。跨用户污染就是靠这一点挡住的。
 *
 * <p>其余三条钉住退化的方向必须始终是"服务器自己抓"：开关关闭、没挂上下文、
 * 等超时——任何一种都不允许让流程在那里空等或直接失败。
 */
class ModrinthApiClientTest {
    @Test @org.junit.jupiter.api.Timeout(10)
    void partialBrowserBatchRetainsSuccessfulFactsAndReportsUnavailableInsteadOfAbsent() throws Exception {
        var fetcher = mock(ModrinthFetcher.class);
        when(fetcher.getProjectInfo("failed")).thenThrow(new ModrinthApiClient.UnavailableException("fixture failure"));
        var tasks = new DelegationTasks(true, 2000);
        var api = new ModrinthApiClient(fetcher, tasks);
        var ctx = new DelegationContext();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> DelegationContext.runWith(ctx, () -> {
                var error = org.junit.jupiter.api.Assertions.assertThrows(ModrinthApiClient.PartialProjectBatchException.class,
                        () -> api.getProjectsBatch(java.util.List.of("known", "failed")));
                assertEquals(java.util.Set.of("known"), error.projects().keySet());
            }));
            for (int i = 0; i < 400 && ctx.pendingWants().size() < 2; i++) Thread.sleep(2);
            assertEquals(2, ctx.pendingWants().size());
            ctx.deliver(Kind.PROJECT, "known", new ObjectMapper().createObjectNode().put("id", "P1").put("slug", "known"));
            ctx.handBack(Kind.PROJECT, "failed", "NETWORK_ERROR");
            future.get(2, TimeUnit.SECONDS);
            assertEquals(1, ctx.handBackCount());
            verify(fetcher).getProjectInfo("failed");
            verify(fetcher, never()).getProjectInfo("known");
        } finally { tasks.close(); }
    }
    @Test void disabledDelegationDoesNotAllowACancelledTaskToStartAServerBatch() {
        var fetcher = mock(ModrinthFetcher.class);
        var tasks = new DelegationTasks(false, 2000);
        var context = new DelegationContext(); context.cancelAll();
        try {
            var api = new ModrinthApiClient(fetcher, tasks);
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,
                    () -> DelegationContext.runWith(context, () -> api.getProjectsBatch(java.util.List.of("one", "two"))));
            org.mockito.Mockito.verifyNoInteractions(fetcher);
        } finally { tasks.close(); }
    }
    @Test @org.junit.jupiter.api.Timeout(10)
    void twelveProjectPipelineReusesIdVersionsWithoutAnotherDispatchWave() throws Exception {
        var fetcher = mock(ModrinthFetcher.class);
        var tasks = new DelegationTasks(true, 2000);
        var api = new ModrinthApiClient(fetcher, tasks);
        var ctx = new DelegationContext();
        var json = new ObjectMapper();
        var slugs = java.util.stream.IntStream.range(0, 12).mapToObj(i -> "mod-" + i).toList();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> DelegationContext.runWith(ctx, () -> {
                var checks = slugs.stream().map(slug -> yagen.waitmydawn.maa.runtime.ScopedExecutors.runAsync(
                        () -> api.getLatestCompatibleVersionBySlug(slug, "1.21.1", "neoforge"), executor)).toList();
                java.util.concurrent.CompletableFuture.allOf(checks.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
                assertEquals(12, api.getProjectsBatch(slugs).size());
                for (int i = 0; i < 12; i++) assertEquals("V" + i,
                        api.getLatestVersion("P" + i, "1.21.1", "[\"neoforge\"]").path("id").asText());
            }));
            for (int wave = 0; wave < 2; wave++) {
                for (int i = 0; i < 400 && ctx.pendingWants().size() < (wave == 0 ? 12 : 6); i++) Thread.sleep(2);
                var wants = ctx.dispatchWants();
                assertEquals(6, wants.size());
                for (var want : wants) {
                    String index = want.key().substring(4, want.key().indexOf('|'));
                    ctx.deliver(want.kind(), want.key(), json.createObjectNode().put("id", "V" + index).put("project_id", "P" + index));
                }
            }
            for (int i = 0; i < 400 && ctx.pendingWants().size() < 12; i++) Thread.sleep(2);
            var projects = ctx.dispatchWants();
            assertEquals(12, projects.size());
            for (var want : projects) {
                assertEquals(Kind.PROJECT, want.kind());
                String index = want.key().substring(4);
                ctx.deliver(want.kind(), want.key(), json.createObjectNode().put("id", "P" + index).put("slug", want.key()));
            }
            future.get(2, TimeUnit.SECONDS);
            assertTrue(ctx.pendingWants().isEmpty());
            assertEquals(12, ctx.factReuseCount());
            org.mockito.Mockito.verifyNoInteractions(fetcher);
        } finally { tasks.close(); }
    }
    @Test void disabledDelegationKeepsNativeProjectBatchEvenInsideATask() {
        var fetcher = mock(ModrinthFetcher.class);
        var refs = java.util.List.of("one", "two");
        var data = java.util.Map.of("one", new ObjectMapper().createObjectNode().put("slug", "one"));
        when(fetcher.getProjectsBatch(refs)).thenReturn(java.util.Map.copyOf(data));
        var tasks = new DelegationTasks(false, 2000);
        try {
            var api = new ModrinthApiClient(fetcher, tasks);
            DelegationContext.runWith(new DelegationContext(), () -> assertEquals(data, api.getProjectsBatch(refs)));
            verify(fetcher).getProjectsBatch(refs);
            verify(fetcher, never()).getProjectInfo(anyString());
        } finally { tasks.close(); }
    }

    @Test void slugCompatibilityThenIdResolutionUsesOnlyTwoBrowserFactsAndNoServerFetch() throws Exception {
        var fetcher = mock(ModrinthFetcher.class);
        var tasks = new DelegationTasks(true, 2000);
        var ctx = new DelegationContext();
        var api = new ModrinthApiClient(fetcher, tasks);
        var json = new ObjectMapper();
        var version = json.createObjectNode().put("id", "V1").put("project_id", "P1");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> DelegationContext.runWith(ctx, () -> {
                assertEquals(version, api.getLatestCompatibleVersionBySlug("test-mod", "1.21.1", "neoforge"));
                assertEquals("P1", api.getProjectInfo("test-mod").path("id").asText());
                assertEquals(version, api.getLatestVersion("P1", "1.21.1", "[\"neoforge\"]"));
                assertEquals("test-mod", api.getProjectInfo("P1").path("slug").asText());
            }));
            for (var kind : java.util.List.of(Kind.ENV_VERSION, Kind.PROJECT)) {
                String key = kind == Kind.PROJECT ? "test-mod" : "test-mod|1.21.1|[\"neoforge\"]";
                for (int i = 0; i < 400 && !ctx.isPending(kind, key); i++) Thread.sleep(2);
                assertTrue(ctx.isPending(kind, key));
                ctx.deliver(kind, key, kind == Kind.PROJECT ? json.createObjectNode().put("id", "P1").put("slug", "test-mod") : version);
            }
            future.get(2, TimeUnit.SECONDS);
            assertEquals(2, ctx.factReuseCount());
            assertTrue(ctx.pendingWants().isEmpty());
            org.mockito.Mockito.verifyNoInteractions(fetcher);
        } finally { tasks.close(); }
    }
    @Test void compatibleVersionListUsesTaskFactsWithoutServerFetchOrSharedCache() throws Exception {
        var fetcher = mock(ModrinthFetcher.class);
        var ctx = new DelegationContext();
        var api = client(fetcher, true, 3000);
        var got = new AtomicReference<JsonNode>();
        var versions = new ObjectMapper().createArrayNode().addObject().put("id", "V1");
        var data = new ObjectMapper().createArrayNode().add(versions);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var call = executor.submit(() -> DelegationContext.runWith(ctx,
                    () -> got.set(api.getCompatibleVersions("P1", "1.21.1", "[\"neoforge\"]"))));
            for (int i = 0; i < 400 && !ctx.hasPending(); i++) Thread.sleep(5);
            assertTrue(ctx.hasPending());
            assertEquals(Kind.ENV_VERSIONS, ctx.pendingWants().getFirst().kind());
            ctx.deliver(Kind.ENV_VERSIONS, "P1|1.21.1|[\"neoforge\"]", data);
            call.get(3, TimeUnit.SECONDS);
        }
        assertEquals(data, got.get());
        org.mockito.Mockito.verifyNoInteractions(fetcher);
    }
    @Test
    void cancellationNeverFallsBackToServerFetch() {
        ModrinthFetcher fetcher = mock(ModrinthFetcher.class);
        DelegationContext context = new DelegationContext();
        context.cancelAll();
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException.class,
                () -> DelegationContext.runWith(context, () -> client(fetcher, true, 100).getProjectInfo("create")));
        org.mockito.Mockito.verifyNoInteractions(fetcher);
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode project() throws Exception {
        return mapper.readTree("{\"id\":\"P1\",\"slug\":\"create\",\"title\":\"Create\"}");
    }

    private ModrinthApiClient client(ModrinthFetcher fetcher, boolean enabled, long budgetMs) {
        return new ModrinthApiClient(fetcher, new DelegationTasks(enabled, budgetMs));
    }

    @Test
    @DisplayName("开关关闭：完全走服务器自己抓，行为与改造前一致")
    void disabledGoesStraightToFetcher() throws Exception {
        ModrinthFetcher fetcher = mock(ModrinthFetcher.class);
        JsonNode data = project();
        when(fetcher.getProjectInfo("create")).thenReturn(data);

        assertEquals("P1", client(fetcher, false, 100).getProjectInfo("create").path("id").asText());
        verify(fetcher, times(1)).getProjectInfo("create");
    }

    @Test
    @DisplayName("开关开着但不在委派任务里：照样走服务器自己抓")
    void withoutContextGoesToFetcher() throws Exception {
        ModrinthFetcher fetcher = mock(ModrinthFetcher.class);
        when(fetcher.getProjectInfo("create")).thenReturn(project());

        assertNotNull(client(fetcher, true, 100).getProjectInfo("create"));
        verify(fetcher, times(1)).getProjectInfo("create");
    }

    @Test
    @DisplayName("浏览器带回数据：用它的，且一次都不碰出网层（= 不进缓存、不花服务器配额）")
    void clientDataSkipsFetcherEntirely() throws Exception {
        ModrinthFetcher fetcher = mock(ModrinthFetcher.class);
        DelegationContext ctx = new DelegationContext();
        ModrinthApiClient api = client(fetcher, true, 3_000);
        AtomicReference<JsonNode> got = new AtomicReference<>();

        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> f = ex.submit(() -> DelegationContext.runWith(ctx,
                    () -> got.set(api.getProjectInfo("create"))));

            for (int i = 0; i < 400 && !ctx.hasPending(); i++) {
                Thread.sleep(5);
            }
            assertTrue(ctx.hasPending(), "应先向浏览器登记需求并挂起");
            ctx.deliver(Kind.PROJECT, "create", project());
            f.get(3, TimeUnit.SECONDS);
        }

        assertNotNull(got.get());
        assertEquals("create", got.get().path("slug").asText());
        verify(fetcher, never()).getProjectInfo(anyString());
    }

    @Test
    @DisplayName("浏览器明确说上游没有：返回 null，同样不碰出网层")
    void clientSaysAbsentReturnsNull() throws Exception {
        ModrinthFetcher fetcher = mock(ModrinthFetcher.class);
        DelegationContext ctx = new DelegationContext();
        ModrinthApiClient api = client(fetcher, true, 3_000);
        AtomicReference<JsonNode> got = new AtomicReference<>();
        AtomicReference<Boolean> ran = new AtomicReference<>(false);

        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> f = ex.submit(() -> DelegationContext.runWith(ctx, () -> {
                got.set(api.getProjectInfo("ghost"));
                ran.set(true);
            }));
            for (int i = 0; i < 400 && !ctx.hasPending(); i++) {
                Thread.sleep(5);
            }
            ctx.deliver(Kind.PROJECT, "ghost", null);
            f.get(3, TimeUnit.SECONDS);
        }

        assertTrue(ran.get());
        assertNull(got.get(), "上游确实没有 → 返回 null，而不是再打一次请求");
        verify(fetcher, never()).getProjectInfo(anyString());
    }

    @Test
    @DisplayName("浏览器没来：超时后退回服务器自己抓（唯一正确方向）")
    void timeoutFallsBackToFetcher() throws Exception {
        ModrinthFetcher fetcher = mock(ModrinthFetcher.class);
        when(fetcher.getProjectInfo("create")).thenReturn(project());
        DelegationContext ctx = new DelegationContext();
        ModrinthApiClient api = client(fetcher, true, 80);
        AtomicReference<JsonNode> got = new AtomicReference<>();

        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> f = ex.submit(() -> DelegationContext.runWith(ctx,
                    () -> got.set(api.getProjectInfo("create"))));
            f.get(5, TimeUnit.SECONDS);
        }

        assertNotNull(got.get(), "超时后必须能拿到数据，不能空手而归");
        assertEquals(1, ctx.timedOutCount());
        verify(fetcher, times(1)).getProjectInfo("create");
    }

    @Test
    @DisplayName("委派上下文里的批量预取直接跳过：批量交给浏览器做，服务器别白发一次请求")
    void prefetchIsSkippedUnderDelegation() {
        ModrinthFetcher fetcher = mock(ModrinthFetcher.class);
        ModrinthApiClient api = client(fetcher, true, 100);

        DelegationContext.runWith(new DelegationContext(),
                () -> api.prefetchProjects(java.util.List.of("create", "jei")));

        verify(fetcher, never()).prefetchProjects(org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    @DisplayName("搜索也走委派：key 是完整 URL，服务器一次都不出网")
    void searchIsDelegatedToo() throws Exception {
        ModrinthFetcher fetcher = mock(ModrinthFetcher.class);
        // 门面自己拼 URL，所以这里用真实实现（静态方法），不 mock
        String expected = ModrinthFetcher.buildSearchUrl("create", 30, 0, null, "[[\"project_type:mod\"]]");
        DelegationContext ctx = new DelegationContext();
        ModrinthApiClient api = client(fetcher, true, 3_000);
        AtomicReference<JsonNode> got = new AtomicReference<>();

        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> f = ex.submit(() -> DelegationContext.runWith(ctx,
                    () -> got.set(api.search("create", 30, "[[\"project_type:mod\"]]"))));
            for (int i = 0; i < 400 && !ctx.hasPending(); i++) {
                Thread.sleep(5);
            }
            assertTrue(ctx.hasPending(), "搜索应先登记需求");
            assertEquals(expected, ctx.pendingWants().get(0).key(),
                    "下发的 key 必须是完整 URL，且与自抓路径拼出的完全一致");
            ctx.deliver(Kind.SEARCH, expected, mapper.readTree("{\"hits\":[]}"));
            f.get(3, TimeUnit.SECONDS);
        }

        assertTrue(got.get().path("hits").isArray());
        verify(fetcher, never()).searchByUrl(anyString());
    }
}
