package yagen.waitmydawn.maa.runtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录会话表（token → userId），带**空闲有效期**。
 *
 * <p>为什么必须有过期：旧实现里 token 一旦签发就是**永久有效**的，前端"退出登录"只删本地 localStorage，
 * 服务端那一条永远活着。被截图、被日志、被共用电脑带走的 token 可以一直用下去 —— 这是审计里最刺眼的洞。
 *
 * <p>为什么是"滑动"续期而不是"签发后固定 N 天过期"：固定过期的代价是"用户天天在用，第 31 天突然被踢下线"。
 * 这里每次有效使用都把到期时间往后顺延，只要还在用就不会掉线；真正停下来才失效。
 *
 * <p>为什么没有后台清理线程：会话表是内存态，条目极小（几十字节），靠"过期条目被再次访问时顺手删" +
 * "写入口在水位线上扫一遍"就够了。引入调度线程只会多一个要维护生命周期的东西（见 RoundGuard 的同款取舍）。
 */
public class SessionRegistry {

    /** token → 会话条目。ConcurrentHashMap 不接受 null key/value，所有入口都要先挡 null。 */
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final long ttlMs;

    /** 惰性清理的水位线：超过它才在写入口扫全表，避免每次登录都 O(n) */
    private static final int SWEEP_THRESHOLD = 256;

    /** 一条会话：用户 + 到期时刻（毫秒） */
    public record Entry(Long userId, long expiresAtMillis) {}

    public SessionRegistry(long ttlMs) {
        this.ttlMs = Math.max(1, ttlMs);
    }

    /** 建会话并返回新 token（登录 / 注册成功时调用）。 */
    public String issue(Long userId) {
        return issueAt(userId, System.currentTimeMillis());
    }

    /**
     * 取 token 对应的 userId，并**滑动续期**。
     *
     * @return userId；token 为 null / 不存在 / 已过期都返回 null（过期条目顺手删掉）
     */
    public Long touch(String token) {
        return touchAt(token, System.currentTimeMillis());
    }

    /** 退出登录：立刻作废。前端清 localStorage ≠ 登出，服务端删掉这条才算。 */
    public void revoke(String token) {
        if (token != null) sessions.remove(token);
    }

    /**
     * 作废某个账号的<b>全部</b>会话（改密码、换绑邮箱后调用）。
     *
     * <p>为什么必须做：改密码的场景是"我觉得密码可能被别人知道了"。如果只改密码不踢会话，
     * 对方手里那个 token 还能继续用（我们发的 token 是独立凭据，不随密码变化），改密码就等于白改。
     */
    public int revokeAllOf(Long userId) {
        return revokeAllOf(userId, null);
    }

    /**
     * 作废该账号的会话，但保留一个（用于"改密码后当前设备不用重登，其它设备掉线"）。
     *
     * @param keepToken 要保留的 token；null = 全踢
     */
    public int revokeAllOf(Long userId, String keepToken) {
        if (userId == null) return 0;
        int[] n = {0};
        sessions.entrySet().removeIf(e -> {
            if (e.getKey().equals(keepToken)) return false;
            boolean hit = userId.equals(e.getValue().userId());
            if (hit) n[0]++;
            return hit;
        });
        return n[0];
    }

    /** 当前会话数（排查 / 测试用） */
    public int size() {
        return sessions.size();
    }

    // ---------- 以下两个注入时钟的重载包级可见，专供单测，避免用 sleep 赌时序 ----------

    String issueAt(Long userId, long now) {
        sweepIfCrowded(now);
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Entry(userId, now + ttlMs));
        return token;
    }

    Long touchAt(String token, long now) {
        if (token == null) return null;                 // ConcurrentHashMap.get(null) 会 NPE
        Entry entry = sessions.get(token);
        if (entry == null) return null;
        if (now >= entry.expiresAtMillis()) {
            // 过期的删掉。用 (key, expectedValue) 这一版：万一这期间被别的线程续过期，就不该误删。
            sessions.remove(token, entry);
            return null;
        }
        sessions.replace(token, entry, new Entry(entry.userId(), now + ttlMs));
        return entry.userId();
    }

    private void sweepIfCrowded(long now) {
        if (sessions.size() < SWEEP_THRESHOLD) return;
        sessions.entrySet().removeIf(e -> now >= e.getValue().expiresAtMillis());
    }
}
