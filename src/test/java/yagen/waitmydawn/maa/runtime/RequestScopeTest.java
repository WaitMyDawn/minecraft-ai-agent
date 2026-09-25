package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 请求作用域与子线程继承的离线测试。
 *
 * <p>这套埋点的价值全在"数字准不准"上：一次构筑会派生几十个虚拟线程并发打点，
 * 如果子线程拿不到父线程的作用域，统计出来的上游请求数会比真实值小一大截——
 * 那比不统计更误导，因为它会让人以为限流问题已经解决了。
 */
class RequestScopeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("子线程继承作用域：虚拟线程里打的点算在父请求上")
    void childThreadInheritsScope() throws Exception {
        RequestScope.open();
        try {
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletableFuture<Void> a = ScopedExecutors.runAsync(RequestScope::countUpstream, executor);
                CompletableFuture<Void> b = ScopedExecutors.runAsync(() -> {
                    RequestScope.countUpstream();
                    RequestScope.countRateLimited();
                }, executor);
                CompletableFuture.allOf(a, b).join();
            }

            JsonNode trace = mapper.readTree(RequestScope.toTraceJson());
            assertEquals(2, trace.path("http").asInt(), "两个子线程各发一次，应累加到父请求");
            assertEquals(1, trace.path("429").asInt(), "子线程吃到的 429 也要记在父请求上");
        } finally {
            RequestScope.close();
        }
    }

    @Test
    @DisplayName("无作用域：打点静默，导出为零值而不是 null")
    void outsideRequestIsSilent() throws Exception {
        RequestScope.countUpstream();
        RequestScope.countRateLimited();
        RequestScope.countRetry();
        RequestScope.addThrottleWait(1234);

        JsonNode trace = mapper.readTree(RequestScope.toTraceJson());
        assertEquals(0, trace.path("http").asInt());
        assertEquals(0, trace.path("429").asInt());
        assertEquals(0, trace.path("retry").asInt());
        assertEquals(0, trace.path("throttleMs").asInt(),
                "没有作用域时不能把等待时间算到别人头上");
    }

    @Test
    @DisplayName("作用域关闭后不再接收打点，避免上下文泄漏到线程池里的下一个请求")
    void scopeIsClearedOnClose() throws Exception {
        RequestScope.open();
        RequestScope.countUpstream();
        RequestScope.close();

        // 关闭后同一个线程上的后续打点不应再落进旧作用域
        RequestScope.countUpstream();
        JsonNode trace = mapper.readTree(RequestScope.toTraceJson());
        assertEquals(0, trace.path("http").asInt(), "close() 必须清掉 ThreadLocal");
    }
}
