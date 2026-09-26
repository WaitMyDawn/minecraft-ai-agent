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
