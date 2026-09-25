package yagen.waitmydawn.maa.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import yagen.waitmydawn.maa.runtime.DelegationContext;
import yagen.waitmydawn.maa.runtime.RequestScope;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 委派任务注册表：管理"跑到一半、等着浏览器取数"的构筑任务。
 *
 * <p>为什么要它：挂起的虚拟线程必须活得比这次 HTTP 请求长——请求返回 {@code need-data} 之后，
 * 那些线程还 park 在那里等下一轮 {@code /facts}。所以流程不能跑在 Tomcat 的请求线程上，
 * 得提交给一个长生命周期的虚拟线程执行器，由这里按 taskId 记住它。
 *
 * <p>这里用的是 Caffeine 而不是 ConcurrentHashMap：任务是临时的，客户端关掉页面后
 * 必须能自动过期回收。它是<b>任务表</b>不是元数据缓存——刻意与本项目那几层 Modrinth 元数据
 * 缓存分开，因为里面装着活的线程与不可信输入，语义完全不同。
 *
 * <p>整个功能由 {@code maa.delegation.enabled} 控制；为 false 时后端走原路径，
 * 前端行为零变化（见 {@code ModpackController}/{@code ChatController} 的分支）。
 */
@Service
public class DelegationTasks {

    /** 等待浏览器回传的静默窗口：最后一个需求登记之后再安静这么久，才认定"这批要完了" */
    private static final long QUIET_WINDOW_MS = 60;
    private final boolean enabled;
    /** 单个挂起最多等多久，超时就退回服务器自抓（用户确认：单轮 5 秒） */
    private final long callBudgetMs;
    private final Cache<String, Task> tasks = Caffeine.newBuilder()
            .expireAfterWrite(20, TimeUnit.MINUTES)
            .maximumSize(2_000)
            .build();
    /** 长生命周期：挂起的任务要活过发起它的那次 HTTP 请求 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @org.springframework.beans.factory.annotation.Autowired
    public DelegationTasks(@Value("${maa.delegation.enabled:false}") boolean enabled,
                           @Value("${maa.delegation.per-call-budget-ms:5000}") long callBudgetMs) {
        this.enabled = enabled;
        // 下限 50ms：预算给成 0 会让每一次委派都立刻超时，退化成"服务器全包"，
        // 表面上没坏、实际上功能白做，所以宁可直接拒绝这种配置
        this.callBudgetMs = Math.max(50, callBudgetMs);
    }

    /** 测试与简单装配用：默认 5 秒预算 */
    public DelegationTasks(boolean enabled) {
        this(enabled, 5_000);
    }

    /** 总开关。false 时调用方必须走原来的同步路径，行为与改造前完全一致。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 单次委派等待预算（毫秒） */
    public long callBudgetMs() {
        return callBudgetMs;
    }

    /** 一个在后台跑的构筑任务 */
    public static final class Task {
        private final String taskId;
        /** 发起这次构筑的会话标识（前端 uuid），用于 /api/chat/abort 立即叫停挂起的线程 */
        private final String userKey;
        private final DelegationContext context = new DelegationContext();
        private final CompletableFuture<String> result = new CompletableFuture<>();

        private Task(String taskId, String userKey) {
            this.taskId = taskId;
            this.userKey = userKey;
        }

        public String taskId() {
            return taskId;
        }

        public String userKey() {
            return userKey;
        }

        public DelegationContext context() {
            return context;
        }

        public boolean isDone() {
            return result.isDone();
        }

        public CompletableFuture<String> result() {
            return result;
        }
    }

    /**
     * 提交一个构筑任务。
     *
     * <p>{@code scope} 必须在<b>提交时</b>从请求线程捕获：任务体里要用它把上游请求计数
     * 记回发起请求的那一份 {@code <trace>}，否则委派走的那些请求会统计不到。
     */
    public Task submit(String taskId, String userKey, RequestScope scope, Callable<String> work) {
        Task task = new Task(taskId == null || taskId.isBlank()
                ? UUID.randomUUID().toString().substring(0, 8) : taskId, userKey);
        tasks.put(task.taskId, task);
        executor.execute(() -> RequestScope.runWith(scope, () -> DelegationContext.runWith(task.context, () -> {
            try {
                task.result.complete(work.call());
            } catch (Throwable e) {
                task.result.completeExceptionally(e);
            }
        })));
        return task;
    }

    public Task find(String taskId) {
        return taskId == null ? null : tasks.getIfPresent(taskId);
    }

    /**
     * 等第一个事件：要么任务跑完了（不需要外部数据，或委派已经兜底自抓），要么出现了一批需求。
     *
     * <p>静默窗口的作用是<b>自动批量</b>：BFS 一层里的几十个节点几乎同时登记需求，
     * 等一下就能把它们打包成一份清单发出去，而不是一个一个来回。这里正是"能批量就批量"
     * 的发生地——业务代码仍然是一行一个 {@code getProjectInfo}。
     *
     * @return true = 有需求要下发；false = 任务已完成，直接取结果
     */
    public boolean awaitFirstDemand(Task task, long maxWaitMs) {
        long quietNanos = QUIET_WINDOW_MS * 1_000_000L;
        long deadline = System.nanoTime() + maxWaitMs * 1_000_000L;
        while (true) {
            if (task.isDone()) return false;
            if (task.context().hasPending()
                    && System.nanoTime() - task.context().lastWantNanos() >= quietNanos) {
                return true;
            }
            if (System.nanoTime() > deadline) {
                // 兜底：等太久了，把还没下发的需求先发出去（总比继续干等好）
                return task.context().hasPending();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return task.context().hasPending();
            }
        }
    }

    /** 任务被用户主动终止（{@code /api/chat/abort}） */
    public void cancel(Task task) {
        if (task != null) {
            task.context().cancelAll();
        }
    }

    /**
     * 按会话标识叫停该会话下所有在跑的任务。
     *
     * <p>为什么需要：挂起的线程在等浏览器，最多要等 {@code per-call-budget} 才自己醒来。
     * 用户点"终止思考"时不该再等这几秒——直接把它们唤醒放行。
     * 任务表有界（≤2000），直接扫一遍就够了，不值得再维护一张倒排索引。
     */
    public int cancelByUser(String userKey) {
        if (userKey == null) return 0;
        int n = 0;
        for (Task t : tasks.asMap().values()) {
            if (userKey.equals(t.userKey()) && !t.isDone()) {
                t.context().cancelAll();
                n++;
            }
        }
        return n;
    }
}
