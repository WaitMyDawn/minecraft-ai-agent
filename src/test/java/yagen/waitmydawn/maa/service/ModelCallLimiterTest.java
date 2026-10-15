package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Timeout(5)
class ModelCallLimiterTest {
    @Test void timeoutReturnsWhileUncooperativeCallStillHoldsCapacity() throws Exception {
        var limiter = new ModelCallLimiter(1);
        var release = new CountDownLatch(1); var exited = new CountDownLatch(1);
        try {
            assertThrows(ModelCallLimiter.DeadlineExceeded.class, () -> limiter.call(() -> {
                try {
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    return "late";
                } finally { exited.countDown(); }
            }, 150));
            assertThrows(ModelCallLimiter.CapacityExceeded.class, () -> limiter.call(() -> "cannot start", 1000));
            release.countDown(); assertTrue(exited.await(2, TimeUnit.SECONDS));
            String result = null;
            for (int i = 0; i < 100 && result == null; i++) {
                try { result = limiter.call(() -> "recovered", 1000); }
                catch (ModelCallLimiter.CapacityExceeded finishing) { Thread.sleep(5); }
            }
            assertEquals("recovered", result);
        } finally { release.countDown(); }
    }
    @Test void cancellationInterruptsNestedCallAndPreservesCallerInterrupt() throws Exception {
        var limiter = new ModelCallLimiter(1); var entered = new CountDownLatch(1); var stopped = new CountDownLatch(1);
        var result = new AtomicReference<Throwable>(); var interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        var caller = Thread.ofVirtual().start(() -> {
            try {
                limiter.call(() -> {
                    entered.countDown();
                    try { Thread.sleep(10000); return "late"; }
                    catch (InterruptedException canceled) { stopped.countDown(); throw new CancellationException(); }
                }, 5000);
            } catch (Throwable failed) { result.set(failed); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); caller.interrupt(); caller.join(2000);
            assertFalse(caller.isAlive()); assertInstanceOf(CancellationException.class, result.get());
            assertTrue(interrupted.get()); assertTrue(stopped.await(2, TimeUnit.SECONDS));
        } finally { caller.interrupt(); caller.join(2000); }
    }
    @Test void normalResultAndOriginalFailureArePreserved() {
        var limiter = new ModelCallLimiter(1);
        assertEquals("result", limiter.call(() -> "result", 1000));
        var expected = new IllegalArgumentException("fixture");
        assertSame(expected, assertThrows(IllegalArgumentException.class, () -> limiter.call(() -> { throw expected; }, 1000)));
        assertEquals("next", limiter.call(() -> "next", 1000));
    }
}
