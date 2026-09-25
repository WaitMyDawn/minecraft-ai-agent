package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.runtime.DelegationContext;
import yagen.waitmydawn.maa.runtime.DelegationContext.Kind;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 委派任务注册表的离线测试。
 *
 * <p>核心是那两条分支必须分得干净：任务<b>自己能跑完</b>时不该多发一次 need-data 往返，
 * 任务<b>跑到缺口</b>时必须及时把需求交出来。判错第一种会让每轮对话白多一次来回，
 * 判错第二种会让请求挂到超时——两种都是用户能直接感觉到的退化。
 *
 * <p>还要守住一条设计性质：<b>批量来自并发，不来自魔法</b>。一个线程同一时刻只能挂一个需求
 * （它 park 在那里走不到下一行），所以顺序循环的调用点只能一次一个来回；真正把几十个需求
 * 打包成一份清单的，是 BFS 一层里那几十个并发虚拟线程。下面两条用例分别钉住这两种形态。
 */
class DelegationTasksTest {

    @Test
    @DisplayName("开关默认关闭；关掉时后端必须走原路径")
    void disabledByDefault() {
        assertFalse(new DelegationTasks(false).isEnabled());
        assertTrue(new DelegationTasks(true).isEnabled());
    }

    @Test
    @DisplayName("任务不需要外部数据：不发 need-data，直接拿到结果")
    void fastTaskNeedsNoRoundTrip() {
        DelegationTasks tasks = new DelegationTasks(true);

        DelegationTasks.Task task = tasks.submit(null, null, null, () -> "包好了");

        assertFalse(tasks.awaitFirstDemand(task, 2_000), "任务已跑完就不该再下发需求");
        assertEquals("包好了", task.result().join());
        assertNotNull(task.taskId());
    }

    @Test
    @DisplayName("并发节点跑到缺口：静默窗口把它们打包成一批，回传后继续跑完")
    void handsOutWantsThenResumes() throws Exception {
        DelegationTasks tasks = new DelegationTasks(true);
        AtomicReference<DelegationContext> ctxRef = new AtomicReference<>();

        DelegationTasks.Task task = tasks.submit("t-1", "sess-1", null, () -> {
            // 等拿到自己的上下文（submit 返回与任务启动有竞态）
            while (ctxRef.get() == null) {
                Thread.sleep(2);
            }
            // 两个并发"节点"，模拟 BFS 一层里的并发取数
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> a = ex.submit(() -> ctxRef.get().await(Kind.PROJECT, "create", 3_000));
                Future<?> b = ex.submit(() -> ctxRef.get().await(Kind.PROJECT, "jei", 3_000));
                a.get(3, TimeUnit.SECONDS);
                b.get(3, TimeUnit.SECONDS);
            }
            return "继续跑完了";
        });
        ctxRef.set(task.context());

        assertTrue(tasks.awaitFirstDemand(task, 5_000), "有缺口时应下发需求");
        // 静默窗口会把几乎同时登记的需求打包成一批，而不是一个一个来回
        for (int i = 0; i < 200 && task.context().pendingWants().size() < 2; i++) {
            Thread.sleep(5);
        }
        assertEquals(2, task.context().pendingWants().size(), "并发登记的两个需求应被打成一批");

        task.context().deliver(Kind.PROJECT, "create", null);
        task.context().deliver(Kind.PROJECT, "jei", null);

        assertEquals("继续跑完了", task.result().get(3, TimeUnit.SECONDS));
        assertEquals(2, task.context().servedCount());
        assertEquals("t-1", task.taskId());
        assertNotNull(tasks.find("t-1"));
    }

    @Test
    @DisplayName("顺序调用：一次只能挂一个需求（批量靠并发，不靠魔法）")
    void sequentialCallSitesYieldOneWantAtATime() throws Exception {
        DelegationTasks tasks = new DelegationTasks(true);
        AtomicReference<DelegationContext> ctxRef = new AtomicReference<>();

        DelegationTasks.Task task = tasks.submit(null, null, null, () -> {
            while (ctxRef.get() == null) {
                Thread.sleep(2);
            }
            ctxRef.get().await(Kind.PROJECT, "first", 3_000);
            ctxRef.get().await(Kind.PROJECT, "second", 3_000);
            return "两个都拿到了";
        });
        ctxRef.set(task.context());

        assertTrue(tasks.awaitFirstDemand(task, 5_000));
        assertEquals(1, task.context().pendingWants().size(),
                "顺序调用时第二行还没执行，所以只可能挂着一个需求");
        assertEquals("first", task.context().pendingWants().get(0).key());

        task.context().deliver(Kind.PROJECT, "first", null);
        // 第一个放行之后才会走到第二个
        for (int i = 0; i < 400; i++) {
            var wants = task.context().pendingWants();
            if (wants.size() == 1 && "second".equals(wants.get(0).key())) break;
            Thread.sleep(5);
        }
        assertEquals("second", task.context().pendingWants().get(0).key());

        task.context().deliver(Kind.PROJECT, "second", null);
        assertEquals("两个都拿到了", task.result().get(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("任务被终止：挂起线程立刻放行，不留悬挂线程")
    void cancelReleasesWaiters() throws Exception {
        DelegationTasks tasks = new DelegationTasks(true);
        AtomicReference<DelegationContext> ctxRef = new AtomicReference<>();

        DelegationTasks.Task task = tasks.submit(null, "sess-2", null, () -> {
            while (ctxRef.get() == null) {
                Thread.sleep(2);
            }
            ctxRef.get().await(Kind.PROJECT, "slow", 60_000);
            return "被放行了";
        });
        ctxRef.set(task.context());

        assertTrue(tasks.awaitFirstDemand(task, 5_000));
        tasks.cancel(task);

        assertEquals("被放行了", task.result().get(3, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("按会话叫停：只放行该会话的任务，别人还在等")
    void cancelByUserOnlyHitsThatSession() throws Exception {
        DelegationTasks tasks = new DelegationTasks(true);
        AtomicReference<DelegationContext> mine = new AtomicReference<>();
        AtomicReference<DelegationContext> other = new AtomicReference<>();

        DelegationTasks.Task a = tasks.submit(null, "sess-mine", null, () -> {
            while (mine.get() == null) Thread.sleep(2);
            mine.get().await(Kind.PROJECT, "a", 30_000);
            return "a 放行";
        });
        mine.set(a.context());
        DelegationTasks.Task b = tasks.submit(null, "sess-other", null, () -> {
            while (other.get() == null) Thread.sleep(2);
            other.get().await(Kind.PROJECT, "b", 30_000);
            return "b 放行";
        });
        other.set(b.context());

        assertTrue(tasks.awaitFirstDemand(a, 5_000));
        assertTrue(tasks.awaitFirstDemand(b, 5_000));

        assertEquals(1, tasks.cancelByUser("sess-mine"), "只该命中一个任务");
        assertEquals("a 放行", a.result().get(3, TimeUnit.SECONDS));
        assertFalse(b.isDone(), "别的会话不该被误伤");

        tasks.cancel(b);
        assertEquals("b 放行", b.result().get(3, TimeUnit.SECONDS));
    }
}
