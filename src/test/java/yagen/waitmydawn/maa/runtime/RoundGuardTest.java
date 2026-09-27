package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "同一会话同时只跑一轮"的守卫测试。
 *
 * <p>盯三件事：同 uuid 不能并发两轮、不同 uuid 互不影响、**超 TTL 必须能抢占**
 * （最后一条是防"进程被杀/异常没走 finally → 用户被永久锁死"的兜底）。
 */
class RoundGuardTest {

    @Test
    @DisplayName("同 uuid 第二轮被拒；释放后可以再开")
    void sameUuidIsSerialized() {
        RoundGuard guard = new RoundGuard(60_000);
        assertTrue(guard.begin("s1"), "第一轮应拿到");
        assertFalse(guard.begin("s1"), "同一会话的第二轮必须被拒");
        guard.end("s1");
        assertTrue(guard.begin("s1"), "上一轮结束后应能再开");
    }

    @Test
    @DisplayName("不同 uuid 互不影响（多标签页/多用户各跑各的）")
    void differentUuidIndependent() {
        RoundGuard guard = new RoundGuard(60_000);
        assertTrue(guard.begin("a"));
        assertTrue(guard.begin("b"));
        assertTrue(guard.begin("c"));
        assertFalse(guard.begin("a"));
    }

    @Test
    @DisplayName("超过 TTL 直接抢占：宁放两轮进来，也不把用户永久锁死")
    void staleRoundIsTakenOver() throws Exception {
        RoundGuard guard = new RoundGuard(50);        // TTL 50ms
        assertTrue(guard.begin("s1"));
        assertFalse(guard.begin("s1"), "TTL 内仍应拦住");
        Thread.sleep(80);
        assertTrue(guard.begin("s1"), "超过 TTL 必须能抢占（否则那一轮异常没释放 = 用户卡死）");
    }

    @Test
    @DisplayName("没有 uuid 时不拦（匿名直调接口的场景）")
    void blankUuidNeverBlocks() {
        RoundGuard guard = new RoundGuard(60_000);
        assertTrue(guard.begin(null));
        assertTrue(guard.begin(""));
        assertTrue(guard.begin(null));
        guard.end(null);       // 不能抛
    }

    @Test
    @DisplayName("同一账号换个 uuid 也拦得住（多开标签页/换浏览器/刷新都算）")
    void sameAccountDifferentUuidIsBlocked() {
        RoundGuard guard = new RoundGuard(60_000);
        assertTrue(guard.begin("session-a", 7L), "第一个窗口应拿到");
        assertFalse(guard.begin("session-b", 7L), "同一账号的第二个窗口必须被拒（uuid 不同也不行）");
        assertTrue(guard.begin("session-c", 8L), "另一个账号不受影响");
        guard.end("session-a");
        assertTrue(guard.begin("session-b", 7L), "第一轮结束后，同一账号应能再开");
    }

    @Test
    @DisplayName("账号名额没拿到时不能留下半占用（否则那个 uuid 会被自己锁死）")
    void failedAccountAcquireReleasesUuid() {
        RoundGuard guard = new RoundGuard(60_000);
        assertTrue(guard.begin("a1", 7L));
        assertFalse(guard.begin("a2", 7L), "账号名额已被 a1 占住");
        // a2 那一份会话名额必须已经还回去：现在用 a2 配另一个账号应当能拿到
        assertTrue(guard.begin("a2", 9L), "a2 不该被上一次失败的尝试占着");
    }

    @Test
    @DisplayName("匿名（ownerId=null）只按 uuid 拦，多个匿名会话可以并行")
    void anonymousSessionsAreIndependent() {
        RoundGuard guard = new RoundGuard(60_000);
        assertTrue(guard.begin("anon-1", null));
        assertTrue(guard.begin("anon-2", null));
        assertFalse(guard.begin("anon-1", null), "同一个匿名会话仍然只能跑一轮");
    }

    @Test
    @DisplayName("能按账号找回正在跑的 uuid / 判断归属（终止与鉴权要用）")
    void ownerLookup() {
        RoundGuard guard = new RoundGuard(60_000);
        assertTrue(guard.begin("s1", 7L));
        assertTrue(guard.isRunning("s1"));
        assertEquals(7L, guard.ownerOf("s1"));
        assertEquals(java.util.List.of("s1"), guard.activeUuidsOf(7L));
        assertTrue(guard.activeUuidsOf(8L).isEmpty(), "别的账号不该看到这条轮次");
        guard.end("s1");
        assertFalse(guard.isRunning("s1"));
        assertNull(guard.ownerOf("s1"));
        assertTrue(guard.activeUuidsOf(7L).isEmpty());
    }

    @Test
    @DisplayName("size 只数轮次，不把账号名额算成两份")
    void sizeCountsRoundsOnly() {
        RoundGuard guard = new RoundGuard(60_000);
        guard.begin("s1", 7L);
        assertEquals(1, guard.size());
        guard.begin("s2", 8L);
        assertEquals(2, guard.size());
    }
}
