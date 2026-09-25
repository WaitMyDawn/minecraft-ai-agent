package yagen.waitmydawn.maa.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次 HTTP 请求的作用域上下文。
 *
 * <p>要解决的问题：{@code <trace>} 里一直只有耗时和业务计数，没有"这一次构筑到底向上游发了
 * 多少个请求"。于是排查限流时只能靠"版本校验用了 8.7 秒"这种耗时反推，说不清是请求多、还是
 * 网络慢、还是退避睡掉了时间。
 *
 * <p>做法：每个进来的请求开一个作用域（见 {@link RequestScopeFilter}），
 * {@code ModrinthApiClient} 在真正发出请求 / 吃到 429 / 重试 / 被令牌闸排队时各自打点。
 * 一次构筑会派生几十个虚拟线程并发打点，所以计数用 Atomic*；子线程靠
 * {@link ScopedExecutors} 在<b>提交时</b>捕获作用域，否则统计值会比真实值小一大截，
 * 比不统计更误导。
 *
 * <p>无作用域时（例如离线建库器、定时任务）所有打点静默跳过，不抛异常。
 */
public final class RequestScope {

    private static final ThreadLocal<RequestScope> CURRENT = new ThreadLocal<>();

    private final AtomicInteger upstreamRequests = new AtomicInteger();
    private final AtomicInteger rateLimited = new AtomicInteger();
    private final AtomicInteger retries = new AtomicInteger();
    private final AtomicLong throttleWaitMs = new AtomicLong();

    private RequestScope() {
    }

    /** 当前线程所属的请求作用域；不在任何请求里返回 null */
    public static RequestScope current() {
        return CURRENT.get();
    }

    /** 在当前线程开启作用域并设为当前。调用方必须在 finally 里 {@link #close()}。 */
    public static RequestScope open() {
        RequestScope scope = new RequestScope();
        CURRENT.set(scope);
        return scope;
    }

    public static void close() {
        CURRENT.remove();
    }

    /** 在指定作用域中执行任务（子线程继承用）；scope 为 null 时按"无作用域"执行 */
    public static void runWith(RequestScope scope, Runnable task) {
        RequestScope prev = CURRENT.get();
        if (scope != null) {
            CURRENT.set(scope);
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

    // ================= 打点入口（无作用域时静默） =================

    /** 真的向上游发出了一次 HTTP */
    public static void countUpstream() {
        RequestScope s = CURRENT.get();
        if (s != null) s.upstreamRequests.incrementAndGet();
    }

    /** 吃到一次 429 */
    public static void countRateLimited() {
        RequestScope s = CURRENT.get();
        if (s != null) s.rateLimited.incrementAndGet();
    }

    /** 因 429 / 5xx / 超时而重试一次 */
    public static void countRetry() {
        RequestScope s = CURRENT.get();
        if (s != null) s.retries.incrementAndGet();
    }

    /** 被全局令牌闸排队等待 */
    public static void addThrottleWait(long millis) {
        RequestScope s = CURRENT.get();
        if (s != null && millis > 0) s.throttleWaitMs.addAndGet(millis);
    }

    // ================= 导出 =================

    /** 结构化快照：<b>http</b>=真实发出的请求数，<b>rateLimited</b>=被限流次数，
     *  <b>retries</b>=重试次数，<b>throttleWaitMs</b>=被令牌闸扣掉的累计等待毫秒 */
    public record Snapshot(int http, int rateLimited, int retries, long throttleWaitMs) {
    }

    /** 取当前请求的计数快照；无作用域时全零 */
    public static Snapshot snapshot() {
        RequestScope s = CURRENT.get();
        if (s == null) return new Snapshot(0, 0, 0, 0);
        return new Snapshot(s.upstreamRequests.get(), s.rateLimited.get(),
                s.retries.get(), s.throttleWaitMs.get());
    }

    /**
     * 供 {@code <trace>} 使用的紧凑 JSON（纯整数，无嵌套）。
     * 语义：{@code http}=真实发出的请求数，{@code 429}=被限流次数，
     * {@code retry}=重试次数，{@code throttleMs}=被令牌闸扣掉的等待毫秒。
     */
    public static String toTraceJson() {
        Snapshot s = snapshot();
        return "{\"http\":" + s.http()
                + ",\"429\":" + s.rateLimited()
                + ",\"retry\":" + s.retries()
                + ",\"throttleMs\":" + s.throttleWaitMs()
                + "}";
    }
}
