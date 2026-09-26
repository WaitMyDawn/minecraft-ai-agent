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
 * <p><b>超时即自抓</b>：每个挂起最多等 {@code budgetMs}（默认 5 秒）。等不到就返回
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
        /** 关键词搜索（key 用 wantId，把查询参数当事实描述） */
        SEARCH
    }

    public enum Outcome {
        /** 客户端带回了数据 */
        GOT,
        /** 客户端明确告诉我们上游没有这个东西（≠ 没抓到） */
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
    private final AtomicLong lastWantNanos = new AtomicLong();
    private final AtomicInteger served = new AtomicInteger();
    private final AtomicInteger timedOut = new AtomicInteger();
    /** 累计下发给浏览器的需求数（每次打包下发时累加，可能重复计入同一 key） */
    private final AtomicInteger dispatched = new AtomicInteger();
    /** 下发轮次 */
    private final AtomicInteger rounds = new AtomicInteger();
    private volatile boolean cancelled;

    /** 挂起等待某个事实；超时或取消时返回 TIMEOUT/CANCELLED，由调用方自己出网 */
    public Answer await(Kind kind, String key, long budgetMs) {
        String id = id(kind, key);

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

        Want want = new Want("w" + Math.abs(id.hashCode()), kind, key);
        CompletableFuture<Answer> future = waiting.computeIfAbsent(id, k -> new CompletableFuture<>());
        waitingMeta.put(id, want);
        lastWantNanos.set(System.nanoTime());
        try {
            Answer answer = future.get(budgetMs, TimeUnit.MILLISECONDS);
            waiting.remove(id);
            waitingMeta.remove(id);
            if (answer.outcome() == Outcome.GOT || answer.outcome() == Outcome.ABSENT) {
                served.incrementAndGet();
            }
            return answer;
        } catch (TimeoutException e) {
            waiting.remove(id);
            waitingMeta.remove(id);
            timedOut.incrementAndGet();
            return new Answer(Outcome.TIMEOUT, null);
        } catch (InterruptedException e) {
            waiting.remove(id);
            waitingMeta.remove(id);
            Thread.currentThread().interrupt();
            return new Answer(Outcome.CANCELLED, null);
        } catch (ExecutionException e) {
            waiting.remove(id);
            waitingMeta.remove(id);
            return new Answer(Outcome.TIMEOUT, null);
        }
    }

    /**
     * 投递一份回传结果。
     *
     * @param data null 表示"上游明确没有这个项目/版本"（批量端点对无效 id 是静默丢弃的，
     *             所以这个区分必须由客户端显式告诉服务器，否则服务器会把不存在的模组当正常结果收下）
     */
    public void deliver(Kind kind, String key, JsonNode data) {
        String id = id(kind, key);
        // 先留底、再唤醒。留底是给"以后还会问同一个键"的调用用的：
        // 只在有人等着的时候交付，后面再问同一个键就会重新登记→再次下发，
        // 而第二份回执到达时等待者已经被第一份放走了，只能被当成"过期"丢弃
        // （实测正是这样白发过 17 次请求）。
        if (data == null) {
            absent.add(id);
        } else {
            satisfied.put(id, data);
        }
        CompletableFuture<Answer> future = waiting.get(id);
        if (future != null) {
            future.complete(new Answer(data == null ? Outcome.ABSENT : Outcome.GOT, data));
        }
    }

    /** 当前还没被满足的需求（用于组装下发给浏览器的清单） */
    public List<Want> pendingWants() {
        return new ArrayList<>(waitingMeta.values());
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
    public void handBack(Kind kind, String key) {
        String id = id(kind, key);
        CompletableFuture<Answer> future = waiting.get(id);
        if (future != null) {
            future.complete(new Answer(Outcome.TIMEOUT, null));
        }
        waiting.remove(id);
        waitingMeta.remove(id);
        timedOut.incrementAndGet();
    }

    public boolean hasPending() {
        return !waitingMeta.isEmpty();
    }

    public long lastWantNanos() {
        return lastWantNanos.get();
    }

    /** 任务被终止时唤醒所有挂起线程，避免留下永远不会醒的虚拟线程 */
    public void cancelAll() {
        cancelled = true;
        for (CompletableFuture<Answer> f : waiting.values()) {
            f.complete(new Answer(Outcome.CANCELLED, null));
        }
        waiting.clear();
        waitingMeta.clear();
    }

    /** 由浏览器满足的需求数（观测用：这个数越大，说明服务器省下的请求越多） */
    public int servedCount() {
        return served.get();
    }

    /** 等超时、退回服务器自抓的次数（观测用：这个数大说明用户网络/浏览器不给力） */
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
