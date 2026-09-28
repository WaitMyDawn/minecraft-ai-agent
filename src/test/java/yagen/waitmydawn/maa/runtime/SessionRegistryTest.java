package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录会话表的测试。
 *
 * <p>盯四件事：正常的签发/读取、**过期必须失效**（旧实现是永久有效）、**用着不能掉线**（滑动续期）、
 * **退出登录要立刻作废**（前端清 localStorage 是不够的）。
 *
 * <p>用注入时钟的重载而不是 sleep：过期判断的边界（正好到期这一毫秒）必须能精确断言，sleep 是赌时序。
 */
class SessionRegistryTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    @Test
    @DisplayName("签发后能读回同一个 userId")
    void issueThenTouch() {
        SessionRegistry reg = new SessionRegistry(30 * DAY);
        String token = reg.issueAt(42L, 1_000L);
        assertEquals(42L, reg.touchAt(token, 1_100L));
    }

    @Test
    @DisplayName("未知 token 和 null token 都返回 null，且不抛 NPE")
    void unknownAndNullToken() {
        SessionRegistry reg = new SessionRegistry(30 * DAY);
        assertNull(reg.touchAt(null, 1_000L), "null token 不能抛（ConcurrentHashMap.get(null) 会 NPE）");
        assertNull(reg.touchAt("no-such-token", 1_000L));
        reg.revoke(null);            // 不能抛
        reg.revoke("no-such-token"); // 不能抛
    }

    @Test
    @DisplayName("停止使用超过 TTL 后失效；边界（正好到期）算过期")
    void expiresAfterIdle() {
        SessionRegistry reg = new SessionRegistry(1_000L);
        // 先验边界：issue 在 10_000、TTL 1_000 → 到期时刻正好是 11_000，这一刻就必须不认
        String idle = reg.issueAt(8L, 10_000L);
        assertNull(reg.touchAt(idle, 11_000L), "正好到 TTL 必须失效（不能用 > 而不是 >=）");
        assertEquals(0, reg.size(), "过期条目应在被访问时顺手删掉");

        // 再验 TTL 内仍有效（单独一条，避免被上面的续期干扰）
        String stillFresh = reg.issueAt(7L, 20_000L);
        assertEquals(7L, reg.touchAt(stillFresh, 20_999L), "TTL 内仍有效");
    }

    @Test
    @DisplayName("滑动续期：一直用就一直有效，不会被签发的绝对时间踢下线")
    void slidingRenewal() {
        SessionRegistry reg = new SessionRegistry(1_000L);
        String token = reg.issueAt(9L, 0L);
        // 每 900ms 用一次，连用 10 次 = 名义活了 9000ms，远超单次 TTL 1000ms
        long now = 0L;
        for (int i = 0; i < 10; i++) {
            now += 900L;
            assertEquals(9L, reg.touchAt(token, now), "第 " + (i + 1) + " 次使用不该掉线");
        }
    }

    @Test
    @DisplayName("退出登录后立刻失效（不依赖 TTL）")
    void revokeIsImmediate() {
        SessionRegistry reg = new SessionRegistry(30 * DAY);
        String token = reg.issueAt(1L, 0L);
        assertEquals(1L, reg.touchAt(token, 1L));
        reg.revoke(token);
        assertNull(reg.touchAt(token, 2L), "登出必须立刻作废，不能等 TTL");
    }

    @Test
    @DisplayName("两次登录签发不同 token，互不影响")
    void tokensAreIndependent() {
        SessionRegistry reg = new SessionRegistry(30 * DAY);
        String a = reg.issueAt(1L, 0L);
        String b = reg.issueAt(1L, 0L);
        assertNotEquals(a, b);
        reg.revoke(a);
        assertNull(reg.touchAt(a, 1L));
        assertEquals(1L, reg.touchAt(b, 1L), "踢掉一个设备的 token 不该影响另一个");
    }

    @Test
    @DisplayName("会话表不会无限增长：写入口在水位线之上会扫掉过期条目")
    void sweepKeepsTableSmall() {
        SessionRegistry reg = new SessionRegistry(1_000L);
        long now = 0L;
        for (int i = 0; i < 300; i++) reg.issueAt((long) i, now);
        assertEquals(300, reg.size());
        // 全部过期后再签发一条：应触发清扫，只剩新签发的这一条
        reg.issueAt(999L, 10_000L);
        assertEquals(1, reg.size(), "过期条目应被写入口扫掉，不能让 Map 一直涨");
        assertTrue(reg.size() < 300);
    }

    @Test
    @DisplayName("revokeAllOf：踢掉该账号全部会话（改密码/重置密码用）")
    void revokeAllOfKicksEverySession() {
        SessionRegistry reg = new SessionRegistry(30 * DAY);
        String a = reg.issueAt(1L, 0L);   // 同一账号三台设备
        String b = reg.issueAt(1L, 0L);
        String c = reg.issueAt(2L, 0L);   // 别人的会话
        assertEquals(2, reg.revokeAllOf(1L));
        assertNull(reg.touchAt(a, 1L));
        assertNull(reg.touchAt(b, 1L));
        assertEquals(2L, reg.touchAt(c, 1L), "不能误伤别的账号");
    }

    @Test
    @DisplayName("revokeAllOf 可以保留一个（改密码后当前设备不用重登）")
    void revokeAllOfKeepsGivenToken() {
        SessionRegistry reg = new SessionRegistry(30 * DAY);
        String keep = reg.issueAt(1L, 0L);
        String other = reg.issueAt(1L, 0L);
        assertEquals(1, reg.revokeAllOf(1L, keep));
        assertEquals(1L, reg.touchAt(keep, 1L), "当前设备的会话要留着");
        assertNull(reg.touchAt(other, 1L), "其它设备要掉线");
    }
}
