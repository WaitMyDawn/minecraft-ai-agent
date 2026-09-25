package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.runtime.DelegationContext.Answer;
import yagen.waitmydawn.maa.runtime.DelegationContext.Kind;
import yagen.waitmydawn.maa.runtime.DelegationContext.Outcome;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委派上下文的离线测试。
 *
 * <p>这个类决定了"浏览器没来会发生什么"，所以三类结果必须严格区分、绝不能糊在一起：
 * ① 客户端带回了数据 → 用它的；② 客户端说上游没有 → 当作"确定不存在"；
 * ③ 客户端没来 → 超时自抓。<b>把 ③ 当成 ② 会让服务器得出"这个模组不存在"的错误结论，
 * 进而生成缺模组的包</b>——这正是本项目里 {@code UnavailableException} 一直强调要分开的那条线。
 */
class DelegationContextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode obj() throws Exception {
        return mapper.readTree("{\"id\":\"P123\",\"slug\":\"create\"}");
    }

    @Test
    @DisplayName("没人回传：超时并交回调用方自抓，而不是当成不存在")
    void timesOutAndLetsCallerFetch() {
        DelegationContext ctx = new DelegationContext();

        Answer answer = ctx.await(Kind.PROJECT, "create", 80);

        assertEquals(Outcome.TIMEOUT, answer.outcome(), "超时必须明确告调用方自己抓");
        assertNull(answer.data());
        assertEquals(1, ctx.timedOutCount());
        assertEquals(0, ctx.servedCount());
    }

    @Test
    @DisplayName("挂起期间收到回传：唤醒并带回数据")
    void wakesUpWhenFactsArrive() throws Exception {
        DelegationContext ctx = new DelegationContext();
        JsonNode data = obj();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Answer> pending = ex.submit(() -> ctx.await(Kind.PROJECT, "create", 3_000));

            // 等它先登记需求（轮询而不是 sleep 固定值，避免机器快慢导致偶发失败）
            for (int i = 0; i < 400 && !ctx.hasPending(); i++) {
                Thread.sleep(5);
            }
            assertTrue(ctx.hasPending(), "await 应该先登记需求再挂起");
            assertEquals(1, ctx.pendingWants().size());
            assertEquals("create", ctx.pendingWants().get(0).key());

            ctx.deliver(Kind.PROJECT, "create", data);

            Answer answer = pending.get(2, TimeUnit.SECONDS);
            assertEquals(Outcome.GOT, answer.outcome());
            assertEquals("create", answer.data().path("slug").asText());
            assertEquals(1, ctx.servedCount());
        }
    }

    @Test
    @DisplayName("客户端明确说上游没有：算 ABSENT，不能和超时混为一谈")
    void absentIsDistinctFromTimeout() throws Exception {
        DelegationContext ctx = new DelegationContext();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Answer> pending = ex.submit(() -> ctx.await(Kind.PROJECT, "ghost-mod", 3_000));
            for (int i = 0; i < 400 && !ctx.hasPending(); i++) {
                Thread.sleep(5);
            }
            ctx.deliver(Kind.PROJECT, "ghost-mod", null);

            Answer answer = pending.get(2, TimeUnit.SECONDS);
            assertEquals(Outcome.ABSENT, answer.outcome());
            assertNull(answer.data());
            assertEquals(0, ctx.timedOutCount());
        }
    }

    @Test
    @DisplayName("回传早于询问：结果留在已满足表，下次问同一键直接命中、不再挂起")
    void lateArrivalIsReused() throws Exception {
        DelegationContext ctx = new DelegationContext();
        JsonNode data = obj();

        ctx.deliver(Kind.PROJECT, "create", data);   // 没人等着

        Answer answer = ctx.await(Kind.PROJECT, "create", 50);
        assertEquals(Outcome.GOT, answer.outcome());
        assertNotNull(answer.data());
        assertEquals(1, ctx.servedCount());
        assertEquals(0, ctx.timedOutCount(), "命中已满足表不应产生超时");
    }

    @Test
    @DisplayName("取消任务：唤醒所有挂起线程，不留永远醒不来的虚拟线程")
    void cancelWakesEveryone() throws Exception {
        DelegationContext ctx = new DelegationContext();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Answer> a = ex.submit(() -> ctx.await(Kind.PROJECT, "a", 5_000));
            Future<Answer> b = ex.submit(() -> ctx.await(Kind.ENV_VERSION, "b", 5_000));
            for (int i = 0; i < 400 && ctx.pendingWants().size() < 2; i++) {
                Thread.sleep(5);
            }
            assertEquals(2, ctx.pendingWants().size());

            ctx.cancelAll();

            assertEquals(Outcome.CANCELLED, a.get(2, TimeUnit.SECONDS).outcome());
            assertEquals(Outcome.CANCELLED, b.get(2, TimeUnit.SECONDS).outcome());
            assertFalse(ctx.hasPending());
        }
    }

    @Test
    @DisplayName("退回服务器兜底：立刻放行等待线程自己抓，绝不等到超时")
    void handBackLetsCallerFetchImmediately() throws Exception {
        DelegationContext ctx = new DelegationContext();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            // 预算给 10 秒：如果走的是"等超时"这条路，这个测试会明显变慢
            Future<Answer> pending = ex.submit(() -> ctx.await(Kind.PROJECT, "offline", 10_000));
            for (int i = 0; i < 400 && !ctx.hasPending(); i++) {
                Thread.sleep(5);
            }
            assertTrue(ctx.hasPending());

            long start = System.nanoTime();
            ctx.handBack(Kind.PROJECT, "offline");
            Answer answer = pending.get(2, TimeUnit.SECONDS);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(Outcome.TIMEOUT, answer.outcome(),
                    "退回必须映射成 TIMEOUT —— 调用方据此自己抓，绝不能理解成 ABSENT（上游没有）");
            assertTrue(elapsedMs < 1_000, "应该是立刻放行，实际等了 " + elapsedMs + "ms");
            assertEquals(1, ctx.timedOutCount(), "退回要给兜底次数计数，方便观察客户端网络状况");
        }
    }
}
