package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.runtime.DelegationContext.Kind;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子线程上下文继承的离线测试。
 *
 * <p>这个类是被一个真实 bug 逼出来的：委派上下文一开始只挂在 ThreadLocal 上，而 BFS 的每个节点
 * 都跑在 {@link ScopedExecutors} 派生的虚拟线程里——子线程读不到父线程的委派上下文，于是
 * 每一个取数都以为"没在委派任务里"，全部退回服务器自己抓。端到端探针第一次跑的结果就是
 * <b>0 轮委派、0 次客户端请求</b>，而当时所有单测都是绿的（它们都在同一个线程上跑）。
 *
 * <p>所以这条必须钉死：派生出去的任务要同时看见两层上下文。
 */
class ScopedExecutorsTest {

    @Test
    @DisplayName("子线程同时继承 RequestScope 与 DelegationContext")
    void childInheritsBothContexts() throws Exception {
        RequestScope.open();
        DelegationContext ctx = new DelegationContext();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicReference<DelegationContext> seen = new AtomicReference<>();
            AtomicReference<CompletableFuture<Void>> held = new AtomicReference<>();

            DelegationContext.runWith(ctx, () -> held.set(ScopedExecutors.runAsync(() -> {
                seen.set(DelegationContext.current());
                RequestScope.countUpstream();
            }, ex)));
            held.get().get(3, TimeUnit.SECONDS);

            assertSame(ctx, seen.get(),
                    "子线程看不到委派上下文 → 所有取数会退回服务器自己抓");
            assertTrue(RequestScope.toTraceJson().contains("\"http\":1"),
                    "子线程打的点要算回父请求");
        } finally {
            RequestScope.close();
        }
    }

    @Test
    @DisplayName("父线程没有委派上下文时，子线程也不该凭空造一个")
    void absentContextStaysAbsent() throws Exception {
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<DelegationContext> f =
                    ScopedExecutors.runAsync(() -> {
                    }, ex).thenApply(v -> DelegationContext.current());
            assertNull(f.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("子线程里登记的需求，父线程也看得见（打包下发靠的就是这一点）")
    void wantsRegisteredInChildAreVisibleToParent() throws Exception {
        DelegationContext ctx = new DelegationContext();
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicReference<CompletableFuture<Void>> held = new AtomicReference<>();
            DelegationContext.runWith(ctx, () -> held.set(
                    ScopedExecutors.runAsync(() -> ctx.await(Kind.PROJECT, "create", 3_000), ex)));

            for (int i = 0; i < 400 && !ctx.hasPending(); i++) {
                Thread.sleep(5);
            }
            assertTrue(ctx.hasPending(),
                    "父线程（HTTP 请求线程）必须能看到子线程登记的需求，否则打包下发无从谈起");

            ctx.deliver(Kind.PROJECT, "create", null);
            held.get().get(3, TimeUnit.SECONDS);
        }
    }
}
