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
            String planningSystem = request.get().path("messages").get(0).path("content").asText();
            assertTrue(planningSystem.contains("requirements") && planningSystem.contains("addonCores"));
            assertTrue(planningSystem.contains("\"min\"") && planningSystem.contains("\"max\""));
            assertEquals(1, architect.modelCalls());
            assertEquals(17, architect.inputTokens());
            var critic = service.criticPools("候选池", "fixture-key", Set.of("create-good"), 1);
            assertWireRequest(request.get(), "maa-review-decision-v1");
            String system = request.get().path("messages").get(0).path("content").asText();
            assertTrue(system.contains("本轮 approved 硬上限=1"));
            assertTrue(system.contains("\"maxItems\":1"));
            assertTrue(system.contains("\"enum\":[\"create-good\"]"));
            assertEquals(9, critic.outputTokens());
            var total = architect.followedBy(critic);
            assertEquals(2, total.modelCalls());
            assertEquals(34, total.inputTokens());
            assertEquals(18, total.outputTokens());
        } finally {
            server.stop(0);
        }
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
