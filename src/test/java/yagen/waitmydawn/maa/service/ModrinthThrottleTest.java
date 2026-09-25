package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局出网令牌闸的离线测试。
 *
 * <p>这个类存在的意义是"把并发打出去之前先自己踩一脚刹车"，所以它有两件事必须成立：
 * ① 突发额度内不能拖慢（否则冷启动比改造前还慢，等于白做）；
 * ② 队伍真的排不下时要能<b>放弃</b>而不是无限期挂起（否则会把 HTTP 请求吊死，
 *    比吃到 429 更难排查）。
 *
 * <p>计时断言只卡下界、上界放得很宽——过紧的时间断言在 CI 上必然翻车。
 */
class ModrinthThrottleTest {

    @Test
    @DisplayName("突发额度内：连续取用不等待")
    void burstIsNotPaced() {
        // 600/分钟 = 10/秒，但突发额度 5：前 5 个应该全部立即通过
        ModrinthThrottle throttle = new ModrinthThrottle(600, 5, 5_000);

        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            assertTrue(throttle.acquire(), "突发额度内的第 " + (i + 1) + " 个请求不应被拒绝");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 300,
                "突发额度内应几乎不等待，实际 " + elapsedMs + "ms");
    }

    @Test
    @DisplayName("排队超出预算：放弃而不是挂起")
    void givesUpInsteadOfHanging() {
        // 60/分钟 = 1/秒，突发 1，最多只肯等 100ms
        ModrinthThrottle throttle = new ModrinthThrottle(60, 1, 100);

        assertTrue(throttle.acquire(), "第一个请求应立刻通过");
        // 第二个要等到 1 秒之后，超过 100ms 的等待预算 → 必须放弃
        assertFalse(throttle.acquire(), "超出 maxWaitMs 应返回 false，不能挂住调用方");
    }

    @Test
    @DisplayName("超出突发额度后按稳态速率放行")
    void pacesAfterBurst() {
        // 600/分钟 = 10/秒，突发 1：每秒 10 个，除去第一个，之后每个约等 100ms
        ModrinthThrottle throttle = new ModrinthThrottle(600, 1, 2_000);

        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            assertTrue(throttle.acquire(), "第 " + (i + 1) + " 个请求不应被拒绝");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 4 个间隔 × 100ms = 400ms，只卡下界（上界放开，避免 CI 抖动导致假失败）
        assertTrue(elapsedMs >= 300,
                "超出突发额度后应被稳定限速，实际只用了 " + elapsedMs + "ms");
    }
}
