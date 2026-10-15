package yagen.waitmydawn.maa.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AiAgentStructuredOutputTest {
    @Test void criticLimitResolvesProcessAndDotEnvPropertyNamesWithoutChangingPlanningLimit() throws Exception {
        var field = AiAgentService.class.getDeclaredField("criticMaxTokens");
        String expression = field.getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
        var environment = new org.springframework.mock.env.MockEnvironment();
        assertEquals("32768", environment.resolveRequiredPlaceholders(expression));
        environment.setProperty("AI_CRITIC_MAX_TOKENS", "16384");
        assertEquals("16384", environment.resolveRequiredPlaceholders(expression));
        environment.setProperty("ai.critic-max-tokens", "24576");
        assertEquals("24576", environment.resolveRequiredPlaceholders(expression));
        assertEquals(8192, ReflectionTestUtils.getField(new AiAgentService(), "maxTokens"));
    }

    @Test @org.junit.jupiter.api.Timeout(5)
    void keepaliveWithoutFinalJsonCannotExtendModelCallDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int i = 0; i < 200; i++) {
                    exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
                    Thread.sleep(10);
                }
            } catch (java.io.IOException closed) { }
            catch (InterruptedException canceled) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var service = new AiAgentService();
            ReflectionTestUtils.setField(service, "apiUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "modelName", "fixture-model");
            ReflectionTestUtils.setField(service, "callBudgetMs", 250L);
            assertThrows(ModelCallLimiter.DeadlineExceeded.class, () -> service.planBlueprint("fixture", "fixture-key"));
        } finally { server.stop(0); }
    }
    @Test void sendsJsonObjectSchemaAndNoMutatingToolsForBothAgents() throws Exception {
        var request = new AtomicReference<JsonNode>();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {"id":"fixture","object":"chat.completion","created":1,"model":"fixture",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"{}"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":17,"completion_tokens":9,"total_tokens":26}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var service = new AiAgentService();
            ReflectionTestUtils.setField(service, "apiUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "modelName", "fixture-model");
            var architect = service.planBlueprint("新增小地图", "fixture-key");
            assertWireRequest(request.get(), "maa-build-intent-v3");
            assertEquals(8192, request.get().path("max_tokens").asInt());
            String planningSystem = request.get().path("messages").get(0).path("content").asText();
            assertTrue(planningSystem.contains("requirements") && planningSystem.contains("addonCores"));
            assertTrue(planningSystem.contains("\"min\"") && planningSystem.contains("\"max\""));
            assertEquals(1, architect.modelCalls());
            assertEquals(17, architect.inputTokens());
            var critic = service.criticPools("候选池", "fixture-key", Set.of("create-good"), 1);
            assertWireRequest(request.get(), "maa-review-decision-v1");
            assertEquals(32768, request.get().path("max_tokens").asInt());
            String system = request.get().path("messages").get(0).path("content").asText();
            assertTrue(system.contains("本轮 approved 硬上限=1"));
            assertTrue(system.contains("最短连续原文"));
            assertTrue(system.contains("\"maxItems\":1"));
            assertTrue(system.contains("\"enum\":[\"create-good\"]"));
            assertEquals(9, critic.outputTokens());
            assertEquals(0, critic.truncatedCalls());
            var total = architect.followedBy(critic);
            assertEquals(2, total.modelCalls());
            assertEquals(34, total.inputTokens());
            assertEquals(18, total.outputTokens());
            service.criticPools("仅复核已有项目用途", "fixture-key", Set.of(), 0);
            String evidenceOnly = request.get().path("messages").get(0).path("content").asText();
            var schema = mapper.readTree(evidenceOnly.substring(evidenceOnly.indexOf("JSON Schema:\n") + "JSON Schema:\n".length()));
            for (String field : Set.of("approved", "rejected")) {
                assertEquals(0, schema.path("properties").path(field).path("maxItems").asInt(-1));
                assertFalse(schema.path("properties").path(field).path("items").has("enum"));
            }
            Set<String> largePool = java.util.stream.IntStream.range(0, 500)
                    .mapToObj(i -> "candidate-" + i).collect(java.util.stream.Collectors.toSet());
            service.criticPools("large candidate fixture", "fixture-key", largePool, 500);
            assertEquals(32768, request.get().path("max_tokens").asInt());
            String largeSystem = request.get().path("messages").get(0).path("content").asText();
            var largeSchema = mapper.readTree(largeSystem.substring(largeSystem.indexOf("JSON Schema:\n") + "JSON Schema:\n".length()));
            assertEquals(500, largeSchema.path("properties").path("approved").path("maxItems").asInt());
            assertEquals(500, largeSchema.path("properties").path("approved").path("items").path("enum").size());
        } finally {
            server.stop(0);
        }
    }

    @Test void lengthFinishReasonIsTrackedWithoutRetryingOrSalvagingPartialJson() throws Exception {
        var request = new AtomicReference<JsonNode>();
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            request.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {"id":"fixture","object":"chat.completion","created":1,"model":"fixture",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"{\\\"approved\\\":["},"finish_reason":"length"}],
                     "usage":{"prompt_tokens":17,"completion_tokens":32768,"total_tokens":32785}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var service = new AiAgentService();
            ReflectionTestUtils.setField(service, "apiUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(service, "modelName", "fixture-model");
            for (int configured : new int[]{16384, 100000, 1}) {
                ReflectionTestUtils.setField(service, "criticMaxTokens", configured);
                var result = service.criticPools("fixture", "fixture-key");
                assertEquals(Math.max(512, Math.min(32768, configured)), request.get().path("max_tokens").asInt());
                assertEquals("{\"approved\":[", result.text());
                assertEquals(1, result.truncatedCalls());
                var total = result.followedBy(new AiAgentService.AgentCallResult("{}", 2, 1, 3, 4));
                assertEquals(1, total.truncatedCalls());
                assertEquals(32772, total.outputTokens());
                assertEquals(2, total.modelCalls());
                assertEquals(2, result.followedBy(result).truncatedCalls());
            }
            assertEquals(3, requests.get());
        } finally { server.stop(0); }
    }

    private void assertWireRequest(JsonNode body, String schemaTitle) {
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertEquals("fixture-model", body.path("model").asText());
        assertFalse(body.hasNonNull("tools"));
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        String system = body.path("messages").get(0).path("content").asText();
        assertTrue(system.contains(schemaTitle));
        assertTrue(system.contains("additionalProperties"));
        assertTrue(system.contains("JSON"));
    }
}
