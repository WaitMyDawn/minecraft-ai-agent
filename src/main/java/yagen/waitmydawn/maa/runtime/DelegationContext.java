package yagen.waitmydawn.maa.runtime;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次构筑任务的「取数委派」上下文。
 *
 * <p>要解决的问题：服务器代所有用户向 Modrinth 发请求，用户一多必然撞 300 次/分钟/出口 IP
 * 的限额（实测）。解法是把"发请求"这件事交给用户自己的浏览器做——用户的出口 IP 各自独立，
 * 配额随用户数线性增长，而服务器侧归零。
 *
 * <p>工作方式：{@code ModrinthApiClient} 每次准备出网前先问这里一句。命中"已满足表"就直接返回；
 * 否则登记一个需求（{@link Want}）并<b>挂起当前虚拟线程</b>，等下一次
 * {@code POST /api/chat/task/{id}/facts} 把结果送回来。挂起是虚拟线程的 park，
 * 一次构筑挂几十上百个也不占 OS 线程。
 *
 * <p><b>超时即自抓</b>：未下发事实受独立波次等待上限保护，实际下发后最多等 {@code budgetMs}（取值见
 * {@code maa.delegation.per-call-budget-ms}），两者都受任务整体期限约束。等不到就返回
 * {@link Outcome#TIMEOUT}，调用方照常自己发请求。所以"浏览器没来"不会拖死流程，
 * 只会退化成改造前的行为——这也是这条链路上唯一的兜底，必须有。
 *
 * <p><b>客户端数据不进共享缓存</b>：回传内容来自不可信通道，只活在本次任务的这个对象里，
 * 不写 Caffeine、更不写 H2 的 versions 列。理由：一份被改过的 version 依赖列表足以让
 * 别人生成的整合包变成断链包——那是跨用户污染，不是"他自己改自己的包"。
 *
 * <p>线程安全：一次 BFS 会派生几十个虚拟线程同时打点，所以内部一律用 Concurrent*。
 */
public final class DelegationContext {

    private static final ThreadLocal<DelegationContext> CURRENT = new ThreadLocal<>();
    private final BuildProgress progress = new BuildProgress();
    public BuildProgress progress() { return progress; }

    /** 当前线程所属的委派上下文；不在委派任务里返回 null（此时所有取数都归服务器自己做） */
    public static DelegationContext current() {
        return CURRENT.get();
    }

    /** 在指定上下文里跑任务（委派任务体用）；ctx 为 null 时按"无委派"执行 */
    public static void runWith(DelegationContext ctx, Runnable task) {
        DelegationContext prev = CURRENT.get();
        if (ctx != null) {
            CURRENT.set(ctx);
        }
        try {
            task.run();
        } finally {
            if (prev != null) {
                CURRENT.set(prev);
            } else {
                CURRENT.remove();
            }
        }
    }

    /** 服务器要的"事实"种类，恰好对应 Modrinth 的四类查询 */
    public enum Kind {
        /** 项目元数据（slug 或 id 都能查） */
        PROJECT,
        /** 按 version_id 精确取版本 */
        VERSION,
        /** 某模组在某 (mc, loader) 下的最新版本——Modrinth 没有批量等价物，只能逐个查 */
        ENV_VERSION,
        /** 有界兼容版本候选列表，用于静态版本协调。 */
        ENV_VERSIONS,
        /** 关键词搜索（key 用 wantId，把查询参数当事实描述） */
        SEARCH
    }

    public enum Outcome {
        /** 客户端带回了数据 */
        GOT,
        /** 客户端明确回报上游没有这个东西（≠ 没抓到） */
        ABSENT,
        /** 等超时了，调用方自己抓 */
        TIMEOUT,
        /** 任务被取消 */
        CANCELLED
    }

    public record Answer(Outcome outcome, JsonNode data) {
    }

    /** 一个待取事实。{@code wantId} 用于回执配对（搜索这种带参数的用它当 key）。 */
    public record Want(String wantId, Kind kind, String key) {
    }

    private final Map<String, JsonNode> satisfied = new ConcurrentHashMap<>();
    private final Set<String> absent = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<Answer>> waiting = new ConcurrentHashMap<>();
    private final Map<String, Want> waitingMeta = new ConcurrentHashMap<>();
    private final Map<String, Long> sentAt = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> dispatchSignals = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> consumers = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final long queueBudgetMs;
    public DelegationContext() { this(0); }
    public DelegationContext(long queueBudgetMs) { this.queueBudgetMs = Math.max(0, queueBudgetMs); }
    private final AtomicLong lastWantNanos = new AtomicLong();
    private final AtomicInteger served = new AtomicInteger();
    private final AtomicInteger browserHttp = new AtomicInteger();
    private final AtomicInteger clientCacheHits = new AtomicInteger(), clientCoalesced = new AtomicInteger();
    private final Set<String> clientProviders = ConcurrentHashMap.newKeySet();
    public enum ClientFailure { REQUEST_TIMEOUT, BUDGET_EXHAUSTED, RATE_LIMITED, UPSTREAM_HTTP, NETWORK_OR_CORS,
        NETWORK_ERROR, INVALID_RESPONSE, RECEIPT_REJECTED, CLIENT_ABORTED, UNSPECIFIED }
    private final Map<ClientFailure, AtomicInteger> clientFailures = new ConcurrentHashMap<>();

    /** 客户端自报的诊断只用于观测，不改变数据校验、配额或回退权限。 */
    public void recordClientMetrics(JsonNode client) {
        if (!client.isObject()) return;
        recordBrowserHttp(client.path("requests").asInt(0));
        int hits = client.path("cacheHits").asInt(0), combined = client.path("coalesced").asInt(0);
        if (hits >= 0 && hits <= 10000) clientCacheHits.addAndGet(hits);
        if (combined >= 0 && combined <= 10000) clientCoalesced.addAndGet(combined);
        String via = client.path("via").asText("");
        clientProviders.add(Set.of("browser", "eval-jvm").contains(via) ? via : "unknown");
    }
    public int clientCacheHits() { return clientCacheHits.get(); }
    public int clientCoalesced() { return clientCoalesced.get(); }
    public Set<String> clientProviders() { return Set.copyOf(clientProviders); }
    public Map<String, Integer> clientFailureCounts() {
        var counts = new java.util.TreeMap<String, Integer>();
        clientFailures.forEach((reason, count) -> counts.put(reason.name(), count.get()));
        return Map.copyOf(counts);
    }

    public void recordBrowserHttp(int requests) {
        if (requests >= 0 && requests <= 10000) browserHttp.addAndGet(requests);
    }

    public int browserHttpCount() { return browserHttp.get(); }
    private final AtomicInteger timedOut = new AtomicInteger();
    private final AtomicInteger queueTimeouts = new AtomicInteger();
    private final AtomicInteger browserTimeouts = new AtomicInteger();
    private final AtomicInteger handBacks = new AtomicInteger();
    public int queueTimeoutCount() { return queueTimeouts.get(); }
    public int browserTimeoutCount() { return browserTimeouts.get(); }
    public int handBackCount() { return handBacks.get(); }
    /** 累计下发给浏览器的需求数（每次打包下发时累加，可能重复计入同一 key） */
    private final AtomicInteger dispatched = new AtomicInteger();
    /** 下发轮次 */
    private final AtomicInteger rounds = new AtomicInteger();
    private volatile boolean cancelled;
    private final long deadlineNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(20);

    public void checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadlineNanos) {
            throw new java.util.concurrent.CancellationException("任务已取消或超过执行期限");
        }
    }

    /** 挂起等待某个事实；超时或取消时返回 TIMEOUT/CANCELLED，由调用方自己出网 */
    public Answer await(Kind kind, String key, long budgetMs) {
        String id = id(kind, key);
        if (cancelled) return new Answer(Outcome.CANCELLED, null);

        // 读表不删：同一个键在一次任务里可能被问多次（BFS 下一层又碰到同一个前置、
        // 或两个分支共用一个依赖）。删了就会重新登记 → 再次下发，
        // 而那份回执到达时必然"没人等"、被当成过期丢弃。
        JsonNode ready = satisfied.get(id);
        if (ready != null) {
            served.incrementAndGet();
            return new Answer(Outcome.GOT, ready);
        }
        if (absent.contains(id)) {
            served.incrementAndGet();
            return new Answer(Outcome.ABSENT, null);
        }
        if (cancelled) {
            return new Answer(Outcome.CANCELLED, null);
        }

        CompletableFuture<Answer> future;
        CompletableFuture<Void> dispatchedSignal;
        AtomicInteger consumer;
        synchronized (this) {
            if (cancelled) return new Answer(Outcome.CANCELLED, null);
            ready = satisfied.get(id);
            if (ready != null || absent.contains(id)) {
                served.incrementAndGet();
                return new Answer(ready == null ? Outcome.ABSENT : Outcome.GOT, ready);
            }
            future = waiting.computeIfAbsent(id, k -> new CompletableFuture<>());
            dispatchedSignal = dispatchSignals.computeIfAbsent(id, k -> new CompletableFuture<>());
            consumer = consumers.computeIfAbsent(id, k -> new AtomicInteger());
            consumer.incrementAndGet();
            waitingMeta.computeIfAbsent(id, k -> new Want("w" + sequence.incrementAndGet(), kind, key));
        }
        lastWantNanos.set(System.nanoTime());
        long queuedDeadline = Math.min(deadlineNanos, System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(queueBudgetMs, budgetMs)));
        boolean browserWaiting = false;
        try {
            while (!future.isDone()) {
                Long sent = sentAt.get(id);
                browserWaiting = sent != null;
                long deadline = sent == null ? queuedDeadline : Math.min(deadlineNanos, sent + TimeUnit.MILLISECONDS.toNanos(budgetMs));
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                if (sent == null) CompletableFuture.anyOf(future, dispatchedSignal).get(remaining, TimeUnit.NANOSECONDS);
                else future.get(remaining, TimeUnit.NANOSECONDS);
            }
            Answer answer = future.join();
            if (answer.outcome() == Outcome.GOT || answer.outcome() == Outcome.ABSENT) {
                served.incrementAndGet();
            }
            return answer;
        } catch (TimeoutException e) {
            synchronized (this) {
                if (future.complete(new Answer(Outcome.TIMEOUT, null))) {
                    timedOut.incrementAndGet();
                    (browserWaiting ? browserTimeouts : queueTimeouts).incrementAndGet();
                }
            }
            return future.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Answer(Outcome.CANCELLED, null);
        } catch (ExecutionException e) {
            return new Answer(Outcome.TIMEOUT, null);
        } finally {
            synchronized (this) {
                if (consumer.decrementAndGet() == 0 && waiting.remove(id, future)) {
                    waitingMeta.remove(id);
                    sentAt.remove(id);
                    dispatchSignals.remove(id);
                    consumers.remove(id, consumer);
                }
            }
        }
    }

    /**
     * 投递一份回传结果。
     *
     * @param data null 表示"上游明确没有这个项目/版本"（批量端点对无效 id 是静默丢弃的，
     *             所以这个区分必须由客户端显式告诉服务器，否则服务器会把不存在的模组当正常结果收下）
     */
    public synchronized void deliver(Kind kind, String key, JsonNode data) {
        String id = id(kind, key);
        CompletableFuture<Answer> future = waiting.get(id);
        if (cancelled || future == null || future.isDone()) return;
        // 先留底、再唤醒。留底是给"以后还会问同一个键"的调用用的：
        // 只在有人等着的时候交付，后面再问同一个键就会重新登记→再次下发，
        // 而第二份回执到达时等待者已经被第一份放走了，只能被当成"过期"丢弃
        // （实测正是这样白发过 17 次请求）。
        if (data == null) {
            absent.add(id);
        } else {
            satisfied.put(id, data);
        }
        if (future != null) {
            future.complete(new Answer(data == null ? Outcome.ABSENT : Outcome.GOT, data));
        }
    }

    /** 当前还没被满足的需求（用于组装下发给浏览器的清单） */
    public List<Want> pendingWants() {
        return waitingMeta.values().stream().filter(want -> {
            var future = waiting.get(id(want.kind(), want.key()));
            return future != null && !future.isDone();
        }).toList();
    }

    /** 原子认领有界波次；登记等待与已下发等待分开计时，轮询不会重复下发在途事实。 */
    public synchronized List<Want> dispatchWants() {
        var batch = new ArrayList<Want>();
        int singles = (int) pendingWants().stream().filter(w -> sentAt.containsKey(id(w.kind(), w.key())))
                .filter(w -> w.kind() == Kind.ENV_VERSION || w.kind() == Kind.ENV_VERSIONS || w.kind() == Kind.SEARCH).count();
        for (var want : pendingWants().stream().sorted(java.util.Comparator.comparingLong(w -> Long.parseLong(w.wantId().substring(1)))).toList()) {
            String id = id(want.kind(), want.key());
            if (sentAt.containsKey(id)) continue;
            if (want.kind() == Kind.ENV_VERSION || want.kind() == Kind.ENV_VERSIONS || want.kind() == Kind.SEARCH) {
                if (singles >= 6) continue;
                singles++;
            }
            if (batch.size() >= 100) break;
            sentAt.put(id, System.nanoTime());
            dispatchSignals.get(id).complete(null);
            batch.add(want);
        }
        return List.copyOf(batch);
    }

    /**
     * 这个键此刻是不是真的有人在等。
     *
     * <p>回执只收"服务器确实要过"的键，靠的就是它——否则客户端可以往表里塞任意数据。
     * 晚到的回执（对应线程已经超时自抓了）会被丢弃，这是对的：那份数据已经没有消费者。
     */
    public boolean isPending(Kind kind, String key) {
        return waitingMeta.containsKey(id(kind, key));
    }

    /**
     * 把某个需求"退回去"：这次没拿到，让等待的线程<b>立刻</b>自己抓，而不是干等到超时。
     *
     * <p>场景：浏览器的网络到不了 Modrinth（没挂代理、扩展拦截、断网）。这种情况必须和
     * "上游确实没有这个项目"严格分开——后者会让服务器得出"该模组不存在"的结论，
     * 进而产出一个缺模组的包。端到端探针跑出了这个洞：前端把抓取失败也记成 missing，
     * 服务器照着 missing 当作确定不存在，兜底就失效了。
     */
    public synchronized void handBack(Kind kind, String key) {
        handBack(kind, key, "UNSPECIFIED");
    }
    public synchronized void handBack(Kind kind, String key, String reason) {
        String id = id(kind, key);
        CompletableFuture<Answer> future = waiting.get(id);
        if (future != null) {
            if (future.complete(new Answer(Outcome.TIMEOUT, null))) {
                timedOut.incrementAndGet(); handBacks.incrementAndGet();
                ClientFailure failure;
                try { failure = ClientFailure.valueOf(reason); }
                catch (IllegalArgumentException | NullPointerException invalid) { failure = ClientFailure.UNSPECIFIED; }
                clientFailures.computeIfAbsent(failure, unused -> new AtomicInteger()).incrementAndGet();
            }
        }
    }

    public boolean hasPending() {
        return waiting.values().stream().anyMatch(future -> !future.isDone());
    }

    public long lastWantNanos() {
        return lastWantNanos.get();
    }

    /** 任务被终止时唤醒所有挂起线程，避免留下永远不会醒的虚拟线程 */
    public synchronized void cancelAll() {
        cancelled = true;
        for (CompletableFuture<Answer> f : waiting.values()) {
            f.complete(new Answer(Outcome.CANCELLED, null));
        }
        waiting.clear();
        waitingMeta.clear();
        sentAt.clear();
        dispatchSignals.clear();
        consumers.clear();
    }

    /** 任务完成后只保留计数，及时释放回传 JSON，避免结果留存期间继续占用大块堆内存。 */
    public void releaseFacts() {
        cancelAll();
        satisfied.clear();
        absent.clear();
    }

    /** 由浏览器满足的需求数（观测用：这个数越大，说明服务器省下的请求越多） */
    public int servedCount() {
        return served.get();
    }

    // ---------- 客户端跑路标记 ----------
    // 发出需求之后一个回执都没回来，说明浏览器已经不在了（刷新/关标签/崩溃/断网）。
    // 这一轮继续跑下去毫无意义：它只会挨个等预算超时、然后回源去挤那把全局令牌闸，
    // 还占着"同一账号同时只跑一轮"的名额不放。由 ChatController 的定时回收打上这个标记。

    private volatile boolean abandoned;

    /** true = 已被判定为"客户端已离开" */
    public boolean abandoned() {
        return abandoned;
    }

    /** 判定客户端已离开（由回收任务调用） */
    public void markAbandoned() {
        this.abandoned = true;
    }

    /** 超时及交还的总数；必须结合分阶段计数归因，不能仅凭总数判断网络故障。 */
    public int timedOutCount() {
        return timedOut.get();
    }

    /** 记一次打包下发，返回本次的轮次（从 1 开始） */
    public int countDispatched(int wantCount) {
        dispatched.addAndGet(wantCount);
        return rounds.incrementAndGet();
    }

    /** 累计下发给浏览器的需求总数 */
    public int dispatchedCount() {
        return dispatched.get();
    }

    /** 已经下发过几轮 */
    public int roundCount() {
        return rounds.get();
    }

    private static String id(Kind kind, String key) {
        return kind + "|" + key;
    }
}
