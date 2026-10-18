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
 * <p>{@code maa.delegation.enabled} 仅控制是否向浏览器下发取数需求；关闭时构筑仍经过有界队列，
 * 由服务器取数并直接返回最终文本。
 */
@Service
public class DelegationTasks {
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

    /** 等待浏览器回传的静默窗口：最后一个需求登记之后再安静这么久，才认定"这批要完了" */
    private static final long QUIET_WINDOW_MS = 60;
    private final boolean enabled;
    /** 单个挂起最多等多久，超时就退回服务器自抓（取值见 maa.delegation.per-call-budget-ms） */
    private final long callBudgetMs;
    private final Cache<String, Task> tasks = Caffeine.newBuilder()
            .expireAfterWrite(20, TimeUnit.MINUTES)
            .maximumSize(2_000)
            .<String, Task>removalListener((key, task, cause) -> {
                if (task != null && !task.isDone() && cause.wasEvicted()) cancel(task);
            })
            .build();
    /** 长生命周期：挂起的任务要活过发起它的那次 HTTP 请求 */
    private final java.util.concurrent.ThreadPoolExecutor executor;
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("maa-task-deadlines").factory());
    private final java.util.concurrent.ConcurrentMap<Long, String> owners = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean closed;

    @jakarta.annotation.PreDestroy
    public synchronized void close() {
        if (closed) return;
        closed = true;
        tasks.asMap().values().forEach(this::cancel);
        executor.shutdownNow();
        deadlines.shutdownNow();
        tasks.invalidateAll();
        owners.clear();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DelegationTasks(@Value("${maa.delegation.enabled:true}") boolean enabled,
                           @Value("${maa.delegation.per-call-budget-ms:120000}") long callBudgetMs,
                           @Value("${maa.build.active-limit:8}") int activeLimit,
                           @Value("${maa.build.queue-limit:128}") int queueLimit) {
        this.enabled = enabled;
        this.callBudgetMs = Math.max(50, callBudgetMs);
        int workers = Math.max(1, Math.min(64, activeLimit));
        this.executor = new java.util.concurrent.ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(Math.max(1, Math.min(256, queueLimit))),
                Thread.ofVirtual().name("maa-build-", 0).factory(), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    public DelegationTasks(boolean enabled, long callBudgetMs) {
        this(enabled, callBudgetMs, 8, 128);
    }

    /** 测试与简单装配用：默认 5 秒预算 */
    public DelegationTasks(boolean enabled) {
        this(enabled, 5_000);
    }

    /** 取数委派开关；关闭时门面层只使用服务器出网层。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 单次委派等待预算（毫秒） */
    public long callBudgetMs() {
        return callBudgetMs;
    }

    /** 一个在后台跑的构筑任务 */
    public static final class Task {
        private final long sequence = SEQUENCE.incrementAndGet();
        private final String taskId;
        /** 提交时刻：僵尸轮次回收要算它跑了多久 */
        private final long createdAtMillis = System.currentTimeMillis();
        /** 发起这次构筑的会话标识（前端 uuid），用于 /api/chat/abort 立即叫停挂起的线程 */
        private final String userKey;
        /** 发起者账号 id；匿名 = null。用户回执端点用它做归属校验（别人的 taskId 不该能往里塞数据） */
        private final Long ownerId;
        private final DelegationContext context = new DelegationContext(180_000);
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private volatile java.util.concurrent.Future<?> worker;
        private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile long lastReceiptMillis = System.currentTimeMillis();

        public void received() { lastReceiptMillis = System.currentTimeMillis(); }
        public void dispatched() {
            if (context.dispatchedCount() == 0) received();
        }

        private void stop() {
            // 先固定结果状态，避免唤醒等待者后由工作线程抢先提交成功结果。
            result.cancel(false);
            context.cancelAll();
            var current = worker;
            if (current != null) current.cancel(true);
        }
        /** 提交时捕获的请求作用域：任务里的出网计数都记在它上面，收口时要读它而不是当前线程的 */
        private RequestScope scope;

        private Task(String taskId, String userKey, Long ownerId) {
            this.taskId = taskId;
            this.userKey = userKey;
            this.ownerId = ownerId;
        }

        public String taskId() {
            return taskId;
        }

        public String userKey() {
            return userKey;
        }

        /** 发起者账号 id（匿名 = null） */
        public Long ownerId() {
            return ownerId;
        }

        public RequestScope scope() {
            return scope;
        }

        /** 提交时刻（毫秒） */
        public long createdAtMillis() {
            return createdAtMillis;
        }

        public DelegationContext context() {
            return context;
        }

        public boolean isDone() {
            return result.isDone();
        }

        public boolean hasStarted() { return started.get(); }

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
        return submit(taskId, null, userKey, scope, work);
    }

    /** 登记归属账号的那一版（登录用户走这条） */
    public Task submit(String taskId, Long ownerId, String userKey, RequestScope scope,
                       Callable<String> work) {
        return submit(taskId, ownerId, userKey, scope, work, true);
    }

    public synchronized Task submit(String taskId, Long ownerId, String userKey, RequestScope scope,
                       Callable<String> work, boolean exclusiveOwner) {
        if (closed) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "服务正在停止，请稍后重新提交");
        Task task = new Task(taskId == null || taskId.isBlank()
                ? UUID.randomUUID().toString() : taskId, userKey, ownerId);
        if (exclusiveOwner && ownerId != null && owners.putIfAbsent(ownerId, task.taskId) != null) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "已有构筑任务正在进行或等待执行");
        }
        task.scope = scope;
        tasks.put(task.taskId, task);
        try {
        task.worker = executor.submit(() -> RequestScope.runWith(scope, () -> DelegationContext.runWith(task.context, () -> {
            task.started.set(true);
            task.context.progress().enter("构建任务开始执行");
            try {
                task.context.checkActive();
                task.result.complete(work.call());
            } catch (Throwable e) {
                task.result.completeExceptionally(e);
            } finally {
                var upstream = scope == null ? new RequestScope.Snapshot(0, 0, 0, 0) : scope.snapshotOf();
                System.out.println("[Modrinth task] taskId=" + task.taskId() + " user=" + task.userKey()
                        + " browserHttp=" + task.context.browserHttpCount()
                        + " browserFacts=" + task.context.servedCount() + " fallbackFacts=" + task.context.timedOutCount()
                        + " queueTimeout=" + task.context.queueTimeoutCount() + " browserTimeout=" + task.context.browserTimeoutCount()
                        + " handBack=" + task.context.handBackCount()
                        + " serverHttp=" + upstream.http() + " server429=" + upstream.rateLimited()
                        + " serverRetries=" + upstream.retries() + " throttleMs=" + upstream.throttleWaitMs()
                        + " clientVia=" + task.context.clientProviders() + " clientCacheHits=" + task.context.clientCacheHits()
                        + " clientCoalesced=" + task.context.clientCoalesced() + " clientFailures=" + task.context.clientFailureCounts());
                task.context.releaseFacts();
                if (ownerId != null) owners.remove(ownerId, task.taskId);
            }
        })));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            tasks.invalidate(task.taskId);
            if (ownerId != null) owners.remove(ownerId, task.taskId);
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "构筑队列已满，请稍后重试");
        }
        var timeout = deadlines.schedule(() -> { if (!task.isDone()) cancel(task); }, 20, TimeUnit.MINUTES);
        task.result.whenComplete((value, failure) -> timeout.cancel(false));
        return task;
    }

    public Task find(String taskId) {
        return taskId == null ? null : tasks.getIfPresent(taskId);
    }

    /** 已交付结果仅保留短暂重试窗口，避免大图谱在任务表中滞留二十分钟。 */
    public void resultDelivered(Task task) {
        if (task.isDone() && task.delivered.compareAndSet(false, true)) {
            deadlines.schedule(() -> tasks.invalidate(task.taskId()), 60, TimeUnit.SECONDS);
        }
    }

    /** 排位是当前快照；已开始的任务不计入等待人数。 */
    public int queuePosition(Task task) {
        if (task.hasStarted() || task.isDone()) return 0;
        int position = 1;
        for (Task other : tasks.asMap().values()) {
            if (!other.hasStarted() && !other.isDone() && other.sequence < task.sequence) position++;
        }
        return position;
    }

    /** 只读遍历当前任务表（僵尸轮次回收用） */
    public java.util.Collection<Task> all() {
        return tasks.asMap().values();
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
            task.stop();
            if (task.worker instanceof Runnable runnable) executor.remove(runnable);
            if (task.ownerId != null && task.worker != null && !task.started.get()) {
                // 尚未执行的任务没有 finally 回收入口；运行中的账号租约由工作线程释放。
                owners.remove(task.ownerId, task.taskId);
            }
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
                cancel(t);
                n++;
            }
        }
        return n;
    }

    /**
     * 按账号终止该用户所有在等的任务。
     *
     * <p>为什么按账号而不是按 {@code userKey}：userKey 里带着 uuid，而前端 uuid 每次刷新都会变，
     * 于是"刷新页面后点终止"永远打不中原来那个任务；按账号找就没这个问题（同一个人同时只跑一轮）。
     */
    public int cancelByOwner(Long ownerId) {
        if (ownerId == null) return 0;
        int n = 0;
        for (Task t : tasks.asMap().values()) {
            if (ownerId.equals(t.ownerId()) && !t.isDone()) {
                cancel(t);
                n++;
            }
        }
        return n;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void expireDisconnected() {
        long now = System.currentTimeMillis();
        for (Task task : tasks.asMap().values()) {
            if (!task.isDone() && now - task.lastReceiptMillis > 120_000) cancel(task);
        }
    }
}
