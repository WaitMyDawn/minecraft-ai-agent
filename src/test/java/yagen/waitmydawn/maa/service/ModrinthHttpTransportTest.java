package yagen.waitmydawn.maa.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.runtime.RequestScope;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ModrinthHttpTransportTest {
    @Test void taskCancellationInterruptsBodyWaitWithoutWaitingForDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.createContext("/", e -> {
            e.sendResponseHeaders(200, 0); e.getResponseBody().write('{'); e.getResponseBody().flush(); received.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            e.close();
        }); server.start();
        var context = new yagen.waitmydawn.maa.runtime.DelegationContext();
        var transport = new ModrinthHttpTransport(new ObjectMapper(), 1);
        try {
            var task = executor.submit(() -> yagen.waitmydawn.maa.runtime.DelegationContext.runWith(context,
                    () -> transport.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), 5000, false)));
            assertTrue(received.await(2, TimeUnit.SECONDS)); context.cancelAll();
            var error = assertThrows(ExecutionException.class, () -> task.get(1500, TimeUnit.MILLISECONDS));
            assertInstanceOf(CancellationException.class, error.getCause());
        } finally { release.countDown(); server.stop(0); executor.close(); }
    }
    @Test void fullBodyDeadlineCancelsSlowStreamAndReleasesSlot() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        var release = new CountDownLatch(1);
        server.createContext("/slow", e -> {
            e.sendResponseHeaders(200, 0); e.getResponseBody().write("{".getBytes(StandardCharsets.UTF_8)); e.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            e.close();
        });
        server.createContext("/fast", e -> {
            byte[] body = "{\"id\":\"v\"}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, body.length); e.getResponseBody().write(body); e.close();
        });
        server.start();
        var transport = new ModrinthHttpTransport(new ObjectMapper(), 1);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        RequestScope.open();
        try {
            long started = System.nanoTime();
            assertThrows(ModrinthApiClient.UnavailableException.class, () -> transport.get(URI.create(base + "/slow"), 300, false));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000);
            assertEquals("v", transport.get(URI.create(base + "/fast"), 2000, true).path("id").asText());
            assertEquals(2, RequestScope.snapshot().http()); assertEquals(1, RequestScope.snapshot().retries());
        } finally { release.countDown(); server.stop(0); executor.close(); RequestScope.close(); }
    }
    @Test void emptySuccessfulResponseIsUnverifiedInsteadOfAbsent() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> { e.sendResponseHeaders(200, -1); e.close(); }); server.start();
        try {
            var transport = new ModrinthHttpTransport(new ObjectMapper(), 1);
            assertThrows(ModrinthApiClient.UnavailableException.class,
                    () -> transport.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), 2000, false));
        } finally { server.stop(0); }
    }
}
