package yagen.waitmydawn.maa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.*;
import tools.jackson.databind.*;
import yagen.waitmydawn.maa.runtime.RequestScope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 完整响应体共用调用截止时间；超时取消真实 HTTP future，不遗留后台取数任务。 */
@Component
public final class ModrinthHttpTransport {
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper mapper;
    private final Semaphore slots;
    public ModrinthHttpTransport(ObjectMapper mapper, @Value("${maa.modrinth.server-concurrency:8}") int concurrency) {
        this.mapper = mapper; slots = new Semaphore(Math.max(1, Math.min(32, concurrency)), true);
    }
    JsonNode get(URI uri, long remainingMs, boolean retry) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMs);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        boolean acquired = false;
        try {
            while (!acquired && deadline > System.nanoTime()) {
                checkActive();
                acquired = slots.tryAcquire(Math.min(deadline - System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS);
            }
            long left = deadline - System.nanoTime();
            if (!acquired || left <= 0) throw new TimeoutException();
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(left))
                    .header("User-Agent", "MAA-Pro-Agent/5.0").GET().build();
            RequestScope.countUpstream();
            if (retry) RequestScope.countRetry();
            pending = client.sendAsync(request, info -> new LimitedBody());
            var response = awaitResponse(pending, deadline);
            int status = response.statusCode();
            HttpHeaders headers = new HttpHeaders();
            response.headers().map().forEach(headers::put);
            if (status >= 400 && status < 500) throw HttpClientErrorException.create(HttpStatusCode.valueOf(status), "Modrinth", headers, new byte[0], StandardCharsets.UTF_8);
            if (status >= 500) throw HttpServerErrorException.create(HttpStatusCode.valueOf(status), "Modrinth", headers, new byte[0], StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) throw new ModrinthApiClient.UnavailableException("Modrinth 响应状态不可用");
            JsonNode decoded = mapper.readTree(response.body());
            if (decoded == null || decoded.isNull() || decoded.isMissingNode()) throw failure("EMPTY_RESPONSE");
            return decoded;
        } catch (TimeoutException e) {
            throw failure("RESPONSE_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CancellationException("请求已取消");
        } catch (ExecutionException e) {
            String code = "NETWORK_FAILURE";
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof java.net.http.HttpConnectTimeoutException) { code = "CONNECT_TIMEOUT"; break; }
                if (cause instanceof java.net.http.HttpTimeoutException) { code = "RESPONSE_TIMEOUT"; break; }
                if (cause instanceof javax.net.ssl.SSLException) { code = "TLS_FAILURE"; break; }
                if (cause instanceof java.net.UnknownHostException) { code = "DNS_FAILURE"; break; }
                if (cause instanceof BodyLimitExceeded) { code = "BODY_LIMIT"; break; }
            }
            throw failure(code);
        } catch (tools.jackson.core.JacksonException e) {
            throw failure("INVALID_JSON");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (acquired) slots.release();
        }
    }
    private static ModrinthApiClient.UnavailableException failure(String code) {
        // 固定分类不记录 URL、响应内容或原始异常链。
        System.err.println("[Modrinth transport] " + code);
        return new ModrinthApiClient.UnavailableException("Modrinth 传输不可用: " + code);
    }
    private static final class BodyLimitExceeded extends RuntimeException {}
    private static void checkActive() {
        var context = yagen.waitmydawn.maa.runtime.DelegationContext.current();
        if (context != null) context.checkActive();
    }
    private static HttpResponse<byte[]> awaitResponse(CompletableFuture<HttpResponse<byte[]>> pending, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        for (;;) {
            checkActive();
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new TimeoutException();
            try { return pending.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(250)), TimeUnit.NANOSECONDS); }
            catch (TimeoutException waiting) { if (deadline <= System.nanoTime()) throw waiting; }
        }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long bytes;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription s) { subscription = s; delegate.onSubscribe(s); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) bytes += buffer.remaining();
            if (bytes > MAX_BODY_BYTES) {
                subscription.cancel(); delegate.onError(new BodyLimitExceeded());
            } else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
