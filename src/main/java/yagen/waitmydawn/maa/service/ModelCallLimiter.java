package yagen.waitmydawn.maa.service;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** 模型调用的墙钟期限与在途上限，不依赖上游停止发送保活数据。 */
public final class ModelCallLimiter {
    public static final class DeadlineExceeded extends RuntimeException {
        public DeadlineExceeded() { super("模型调用超过等待期限"); }
    }
    public static final class CapacityExceeded extends RuntimeException {
        public CapacityExceeded() { super("模型调用名额暂时已满"); }
    }
    private final Semaphore slots;
    public ModelCallLimiter(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("模型调用上限必须为正数");
        slots = new Semaphore(maximum);
    }
    public <T> T call(Supplier<T> operation, long budgetMs) {
        if (budgetMs < 1) throw new IllegalArgumentException("模型调用期限必须为正数");
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("模型调用已取消");
        if (!slots.tryAcquire()) throw new CapacityExceeded();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        var future = new CompletableFuture<T>();
        Thread worker;
        try {
            worker = Thread.ofVirtual().name("model-io").start(() -> {
                T value = null; Throwable failure = null;
                // 即使等待者超时，未退出的底层调用仍占名额，避免失联请求无限累积。
                try {
                    if (future.isCancelled()) throw new CancellationException();
                    value = operation.get();
                } catch (Throwable failed) { failure = failed; }
                finally { slots.release(); }
                if (failure == null) future.complete(value); else future.completeExceptionally(failure);
            });
        } catch (RuntimeException | Error startFailure) { slots.release(); throw startFailure; }
        try {
            return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException expired) {
            future.cancel(true); worker.interrupt();
            throw new DeadlineExceeded();
        } catch (InterruptedException canceled) {
            future.cancel(true); worker.interrupt(); Thread.currentThread().interrupt();
            throw new CancellationException("模型调用已取消");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("模型调用失败", failed.getCause());
        }
    }
}
