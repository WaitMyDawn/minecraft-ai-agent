package yagen.waitmydawn.maa.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "同一会话同时只跑一轮"的守卫。
 *
 * <p>为什么需要：一轮构筑要跑几十秒到几分钟，期间状态（候选池、勾选、清单快照）都是**按轮**算的。
 * 同一个 {@code uuid} 并发两轮时，后到的回复会覆盖先到的状态；多标签页/手动重放都能触发。
 * 前端 {@code isLoading} 只挡得住同一标签页的重复点击，挡不住这些。
 *
 * <p>为什么要 TTL 抢占：守卫是内存态，如果进程被杀或某一轮异常卡住（没有走 finally），
 * 那个 uuid 会被永久锁死 —— 用户除非换页面（换 uuid）否则再也发不出指令。
 * 所以超过 {@code ttlMs} 就直接抢占，宁可偶尔放两轮进来，也不能把用户卡住。
 *
 * <p>为什么要按<b>账号</b>再占一个名额：前端那个 uuid 是<b>每次打开页面新生成</b>的
 * （{@code store.js} 里 sessionUuid 没有持久化），所以"多开一个标签页 / 换个浏览器 / 刷新一下"
 * 拿到的是另一个 uuid —— 只按 uuid 拦，等于没拦。登录用户额外占住 {@code account:<userId>} 一个名额，
 * 这样同一个人同时只能跑一轮，不管他开多少个窗口。
 */
public class RoundGuard {

    /**
     * 一条占用记录。
     *
     * <p>三个字段都要留着：{@code startedAt} 判 TTL，{@code ownerId} 让"按账号终止"能找回是谁的轮次，
     * {@code uuid} 让 {@code end} 能安全地只释放自己那一份账号名额（值相等才删，避免误删别人新占的）。
     */
    private record Entry(long startedAt, Long ownerId, String uuid) {}

    private final Map<String, Entry> running = new ConcurrentHashMap<>();
    private final long ttlMs;

    public RoundGuard(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /**
     * 尝试开始一轮。
     *
     * @return true = 拿到了（没有别的轮次在跑，或上一轮已超 TTL 被抢占）；false = 同一会话或同一账号已有轮次在跑
     */
    public boolean begin(String uuid) {
        return begin(uuid, null);
    }

    /**
     * 尝试开始一轮（登录用户额外占一个账号名额）。
     *
     * @param ownerId 账号 id；匿名传 null（只按 uuid 拦）
     */
    public boolean begin(String uuid, Long ownerId) {
        if (uuid == null || uuid.isBlank()) return true;      // 没有会话标识就不拦（匿名直调接口的场景）
        long now = System.currentTimeMillis();
        sweepIfCrowded(now);
        if (!tryAcquire(uuid, now, ownerId, uuid)) return false;
        if (ownerId != null && !tryAcquire(ownerKey(ownerId), now, ownerId, uuid)) {
            // 账号名额没拿到 → 把刚占的会话名额还回去，不能留半占用
            running.remove(uuid);
            return false;
        }
        return true;
    }

    private boolean tryAcquire(String key, long now, Long ownerId, String uuid) {
        Entry prev = running.putIfAbsent(key, new Entry(now, ownerId, uuid));
        if (prev == null) return true;
        if (now - prev.startedAt() < ttlMs) return false;
        running.put(key, new Entry(now, ownerId, uuid));     // 超时抢占
        return true;
    }

    /**
     * 一轮结束（必须在 finally 里调用，否则要靠 TTL 才解锁）。
     *
     * <p>账号名额靠 entry 里记的 owner 找回；用"值相等才删"这一版，避免把抢占了同一账号的新轮次误删。
     */
    public void end(String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        Entry e = running.remove(uuid);
        if (e == null || e.ownerId() == null) return;
        running.remove(ownerKey(e.ownerId()), e);
    }

    /** 这个 uuid 现在是否真的有轮次在跑（abort 用它判断"这一下有没有东西可终止"） */
    public boolean isRunning(String uuid) {
        if (uuid == null || uuid.isBlank()) return false;
        Entry e = running.get(uuid);
        return e != null && System.currentTimeMillis() - e.startedAt() < ttlMs;
    }

    /** 这个 uuid 当前轮次的归属账号（匿名 = null，没在跑也是 null） */
    public Long ownerOf(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        Entry e = running.get(uuid);
        return e == null ? null : e.ownerId();
    }

    /** 某账号当前在跑的 uuid 列表（"终止我这一轮"要按账号找，因为 uuid 可能已经换了） */
    public List<String> activeUuidsOf(Long ownerId) {
        List<String> out = new ArrayList<>();
        if (ownerId == null) return out;
        long now = System.currentTimeMillis();
        running.forEach((k, e) -> {
            // 只数会话条目：账号条目里记的 uuid 和会话条目是同一个，不排掉就会重复（[s1, s1]）
            if (!k.startsWith("account:") && ownerId.equals(e.ownerId())
                    && now - e.startedAt() < ttlMs) {
                out.add(e.uuid());
            }
        });
        return out;
    }

    /** 当前在跑的轮次数（排查用）。只数会话条目，不能把账号名额也算进去，否则会翻倍 */
    public int size() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Map.Entry<String, Entry> e : running.entrySet()) {
            if (!e.getKey().startsWith("account:") && now - e.getValue().startedAt() < ttlMs) n++;
        }
        return n;
    }

    /** 账号名额的键。前缀里的冒号不可能出现在 uuid 里（前端只生成 [A-Za-z0-9._-]），不会撞键 */
    private static String ownerKey(Long ownerId) {
        return "account:" + ownerId;
    }

    /**
     * 惰性清理：只在写入口、且表已经很大时扫一遍过期条目。
     *
     * <p>为什么需要：被 TTL 抢占过、或者轮次异常结束没走 finally 的条目，没有人会来删它
     * （{@code end} 只删自己那一份）。条目很小，但"永远不清理"的 Map 迟早会被看见。
     */
    private void sweepIfCrowded(long now) {
        if (running.size() < 256) return;
        running.entrySet().removeIf(e -> now - e.getValue().startedAt() >= ttlMs);
    }
}
