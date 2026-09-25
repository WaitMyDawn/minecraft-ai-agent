package yagen.waitmydawn.maa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import yagen.waitmydawn.maa.runtime.RequestScope;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Modrinth 全局出网令牌闸。
 *
 * <p>要解决的问题：改造前有四个互不知情的局部信号量（{@code DependencyEngine} 5、
 * {@code ChatController} 两处 5、{@code ModpackController} 10），加起来瞬时能放 25 个并发出去；
 * 而实测限额是 300 次/分钟/出口 IP，相当于 5 次/秒。只要每个请求 100ms，实际就是 250 次/秒——
 * 超了 50 倍。局部信号量管不住总量，所以需要一个跨调用点、跨用户的全局闸。
 *
 * <p>算法用 GCRA（虚拟调度）：维护一个"下一个出发时刻"游标，每次 {@code acquire} 只把它往后推一个
 * 间隔，不预分配令牌、不在锁里自旋；等待在锁外 {@code sleep}，虚拟线程 sleep 几乎不占资源。
 *
 * <p>突发额度靠"信用"实现：游标允许落后于当前时间至多 {@code (burst-1)} 个间隔，
 * 于是最多 {@code burst} 个请求可以连发，之后回到稳态速率。没有这个信用，冷启动时第一个请求之后
 * 每一步都要等一个完整间隔，99 个候选要排队 25 秒——比"并发冲一把再退避"还慢，等于白做。
 *
 * <p>与 429 退避的分工：令牌闸负责"不冲出去"，{@code ModrinthApiClient} 里那套
 * {@code X-Ratelimit-Reset} + 指数退避负责"万一冲出去了怎么回来"。两者是两层保险，
 * 令牌闸<b>不能替代</b>退避——多实例部署时每个实例各有一把闸，出口 IP 却是共享的。
 *
 * <p>队列太长时直接放弃（返回 false）而不是无限期挂起：调用方会按"无法确定"处理，
 * 这比把一个 HTTP 请求挂死好得多。
 */
@Component
public class ModrinthThrottle {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** 每个出发间隔的纳秒数 */
    private final long intervalNanos;
    /** 突发信用：游标允许落后当前时间这么多纳秒，等价于"最多 burst 个请求连发" */
    private final long creditNanos;
    /** 单个请求最长愿意排队多久；超了就放弃 */
    private final long maxWaitMs;

    private final ReentrantLock lock = new ReentrantLock();
    /** 下一个可出发的时刻（System.nanoTime 基准） */
    private long nextSlotNanos;

    /** 观测用：累计排队时间与次数（闸门压不住时会明显增长） */
    private final AtomicLong totalWaitMs = new AtomicLong();
    private final AtomicLong waitCount = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();

    public ModrinthThrottle(@Value("${maa.modrinth.rate-per-minute:240}") int ratePerMinute,
                            @Value("${maa.modrinth.burst:40}") int burst,
                            @Value("${maa.modrinth.max-wait-ms:30000}") long maxWaitMs) {
        double perSecond = Math.max(0.1, ratePerMinute / 60.0);
        this.intervalNanos = (long) (NANOS_PER_SECOND / perSecond);
        this.creditNanos = (Math.max(1, burst) - 1) * intervalNanos;
        this.maxWaitMs = Math.max(0, maxWaitMs);
    }

    /**
     * 取一个出网许可。
     *
     * @return true = 可以发；false = 排队超出预算或线程被中断，本次不发
     */
    public boolean acquire() {
        long waitNanos;
        lock.lock();
        try {
            long now = System.nanoTime();
            // 游标不能落后太多，否则空闲一晚之后会攒出一大笔"信用"，
            // 下一秒就能连发几千个请求，闸门形同虚设。
            long floor = now - creditNanos;
            if (nextSlotNanos < floor) {
                nextSlotNanos = floor;
            }
            waitNanos = nextSlotNanos - now;
            nextSlotNanos += intervalNanos;

            if (waitNanos > maxWaitMs * 1_000_000L) {
                // 队伍太长：放弃，并把刚占的时隙还回去（否则会连累后面的请求）
                nextSlotNanos -= intervalNanos;
                rejectedCount.incrementAndGet();
                return false;
            }
        } finally {
            lock.unlock();
        }

        if (waitNanos > 0) {
            long ms = waitNanos / 1_000_000L;
            totalWaitMs.addAndGet(ms);
            waitCount.incrementAndGet();
            RequestScope.addThrottleWait(ms);
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 观测快照：{[排队次数, 累计排队毫秒, 因队伍过长被拒次数]} */
    public long[] stats() {
        return new long[]{waitCount.get(), totalWaitMs.get(), rejectedCount.get()};
    }
}
