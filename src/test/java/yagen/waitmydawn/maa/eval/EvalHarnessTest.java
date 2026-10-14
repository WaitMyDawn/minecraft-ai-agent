package yagen.waitmydawn.maa.eval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EvalHarnessTest {
    @Test void queuedChatIsSubmittedOnceAndFactsAreDeliveredWithoutKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger submissions = new AtomicInteger(), polls = new AtomicInteger(), facts = new AtomicInteger();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean authenticated = "test-token".equals(exchange.getRequestHeaders().getFirst("X-Auth-Token"));
            String body;
            int status = authenticated ? 200 : 401;
            if (path.equals("/api/meta/categories")) body = "[]";
            else if (path.equals("/api/chat")) {
                submissions.incrementAndGet();
                if (!"test-key".equals(exchange.getRequestHeaders().getFirst("X-LLM-Api-Key"))) status = 400;
                body = "{\"taskId\":\"task-1\",\"stage\":\"queued\"}";
                if (status == 200) status = 202;
            } else {
                if (exchange.getRequestHeaders().getFirst("X-LLM-Api-Key") != null) status = 400;
                if (input.contains("answered")) {
                    facts.incrementAndGet();
                    body = "<mods>jei</mods>";
                } else {
                    body = polls.incrementAndGet() == 1 ? "{\"taskId\":\"task-1\",\"stage\":\"running\"}"
                            : "{\"taskId\":\"task-1\",\"stage\":\"need-data\",\"wanted\":[{\"kind\":\"project\",\"key\":\"jei\"}]}";
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new EvalTaskClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token", "test-key",
                    Duration.ofSeconds(5), 1, wanted -> {
                        assertEquals("jei", wanted.get(0).path("key").asText());
                        var receipt = new ObjectMapper().createObjectNode();
                        receipt.putArray("answered").addObject().put("kind", "project").put("key", "jei");
                        return receipt;
                    });
            assertEquals(200, client.checkReady().statusCode());
            assertEquals("<mods>jei</mods>", client.post("/api/chat", "{}").body());
            assertEquals(1, submissions.get());
            assertEquals(2, polls.get());
            assertEquals(1, facts.get());
        } finally { server.stop(0); }
    }

    @Test void operationTimeoutCancelsExistingTask() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger cancellations = new AtomicInteger(), submissions = new AtomicInteger();
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/cancel")) cancellations.incrementAndGet();
            if (path.equals("/api/modpack/preview")) submissions.incrementAndGet();
            byte[] bytes = "{\"taskId\":\"task-2\",\"stage\":\"running\",\"operation\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new EvalTaskClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token", "",
                    Duration.ofMillis(800), 20, wanted -> { throw new AssertionError("不应取数"); });
            assertThrows(Exception.class, () -> client.post("/api/modpack/preview", "{}"));
            assertEquals(1, submissions.get());
            assertEquals(1, cancellations.get());
        } finally { server.stop(0); }
    }

    @Test void missingAuthenticationFailsBeforeNetwork() {
        var client = new EvalTaskClient("http://127.0.0.1:1", "", "");
        assertThrows(IllegalStateException.class, client::checkReady);
    }

    @Test void operationPollUsesGetAndReturnsFinalGraph() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger getPolls = new AtomicInteger();
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean submit = exchange.getRequestURI().getPath().equals("/api/modpack/preview");
            if (!submit && exchange.getRequestMethod().equals("GET")) getPolls.incrementAndGet();
            byte[] bytes = (submit ? "{\"taskId\":\"task-3\",\"stage\":\"queued\",\"operation\":true}"
                    : "{\"nodes\":[{\"slug\":\"jei\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(submit ? 202 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new EvalTaskClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token", "",
                    Duration.ofSeconds(5), 1, wanted -> { throw new AssertionError("不应取数"); });
            assertTrue(client.post("/api/modpack/preview", "{}").body().contains("nodes"));
            assertEquals(1, getPolls.get());
        } finally { server.stop(0); }
    }

    @Test void upstreamAddressCannotTargetApplicationOrCredentials() {
        var mapper = new ObjectMapper();
        var wanted = mapper.createArrayNode();
        wanted.addObject().put("kind", "search").put("key", "http://127.0.0.1:8080/api/user");
        assertThrows(IllegalArgumentException.class, () -> new EvalTaskClient.ModrinthFacts().fulfil(wanted));
    }

    @Test void repetitionsKeepFailureAndSumEveryAttemptCost() {
        var first = new GoldenSetEval.CaseRun();
        first.failures.add("缺少核心");
        first.elapsedMs = 100;
        first.inTokens = 10;
        first.modelCalls = 2;
        var second = new GoldenSetEval.CaseRun();
        second.elapsedMs = 300;
        second.inTokens = 30;
        second.modelCalls = 3;
        var summary = GoldenSetEval.summarize(List.of(first, second));
        assertFalse(summary.failures.isEmpty());
        assertEquals(1, summary.passedRuns);
        assertEquals(2, summary.repeat);
        assertEquals(200, summary.avgMs);
        assertEquals(40, summary.inTokens);
        assertEquals(5, summary.modelCalls);
        assertEquals(List.of(first, second), summary.attempts);
    }
}
