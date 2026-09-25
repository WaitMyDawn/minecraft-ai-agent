package yagen.waitmydawn.maa.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 让子任务继承父线程请求上下文的提交助手。
 *
 * <p>传播两样东西，缺一不可：
 * <ul>
 *   <li>{@link RequestScope}——子线程打的上游请求计数要记回发起请求的那份 {@code <trace>}；</li>
 *   <li>{@link DelegationContext}——否则子线程以为"没在委派任务里"，会全部退回服务器自己抓，
 *       委派就彻底失效了（这不是假设：端到端探针第一次跑就是 0 轮委派，栽在这里）。</li>
 * </ul>
 *
 * <p>两者都必须在<b>提交时</b>捕获——那时还在父线程上；一旦进了任务体再读，
 * 拿到的就是子线程自己的（空的）上下文。
 */
public final class ScopedExecutors {

    private ScopedExecutors() {
    }

    /** 等价于 {@link CompletableFuture#runAsync(Runnable, Executor)}，但让任务继承调用方的两层上下文 */
    public static CompletableFuture<Void> runAsync(Runnable task, Executor executor) {
        RequestScope scope = RequestScope.current();
        DelegationContext delegation = DelegationContext.current();
        return CompletableFuture.runAsync(
                () -> RequestScope.runWith(scope, () -> DelegationContext.runWith(delegation, task)),
                executor);
    }
}
