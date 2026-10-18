package yagen.waitmydawn.maa.eval;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModrinthFactsTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test @SuppressWarnings("unchecked")
    void environmentKindsShareOneRequestAndUseTheBrowserTimeoutWithoutApplicationCredentials() throws Exception {
        var http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("[{\"id\":\"V1\",\"project_id\":\"P\",\"game_versions\":[\"1.21.1\"],\"loaders\":[\"neoforge\"]}]");
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            assertEquals(Duration.ofSeconds(10), request.timeout().orElseThrow());
            assertTrue(request.headers().firstValue("X-Auth-Token").isEmpty());
            assertTrue(request.headers().firstValue("X-LLM-Api-Key").isEmpty());
            return CompletableFuture.completedFuture(response);
        });
        var wanted = json.readTree("""
                [{"kind":"env_version","key":"one","project":"core","mc":"1.21.1","loader":"neoforge"},
                 {"kind":"env_versions","key":"two","project":"core","mc":"1.21.1","loader":"neoforge"}]
                """);
        var result = new EvalTaskClient.ModrinthFacts(http).fulfil(wanted);
        assertEquals(2, result.path("answered").size()); assertEquals(1, result.path("client").path("requests").asInt());
        assertEquals(1, result.path("client").path("coalesced").asInt());
        assertEquals("V1", result.path("answered").get(0).path("data").path("id").asText());
        assertTrue(result.path("answered").get(1).path("data").isArray());
        verify(http, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
    @Test @SuppressWarnings("unchecked")
    void timeoutsHaveAReasonAndDoNotBecomeMissingFacts() throws Exception {
        var http = mock(HttpClient.class);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(call -> CompletableFuture.failedFuture(new HttpTimeoutException("timeout")));
        var result = new EvalTaskClient.ModrinthFacts(http).fulfil(json.readTree("[{\"kind\":\"project\",\"key\":\"core\"}]"));
        assertTrue(result.path("missing").isEmpty());
        assertEquals("REQUEST_TIMEOUT", result.path("unavailable").get(0).path("reason").asText());
    }
    @Test @SuppressWarnings("unchecked")
    void upstreamErrorsAndMalformedBodiesStayDistinctFromConfirmedAbsence() throws Exception {
        for (String body : new String[]{"{bad", "{}", "[{}]"}) {
            var http = mock(HttpClient.class); HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(body);
            when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> CompletableFuture.completedFuture(response));
            var result = new EvalTaskClient.ModrinthFacts(http).fulfil(json.readTree("[{\"kind\":\"project\",\"key\":\"core\"}]"));
            assertTrue(result.path("missing").isEmpty());
            assertEquals("INVALID_RESPONSE", result.path("unavailable").get(0).path("reason").asText());
        }
        var http = mock(HttpClient.class); HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> CompletableFuture.completedFuture(response));
        var result = new EvalTaskClient.ModrinthFacts(http).fulfil(json.readTree("[{\"kind\":\"version\",\"key\":\"V1\"}]"));
        assertEquals("UPSTREAM_HTTP", result.path("unavailable").get(0).path("reason").asText());
        assertTrue(result.path("missing").isEmpty());
    }
}
