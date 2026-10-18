package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class JsonBodyLimitFilterTest {
    @Test void accountLimitUsesTheSameNormalizedPathAsAuthentication() throws Exception {
        for (String path : new String[]{"/api/user/register", "/api;matrix/user/register", "/%61pi/user/register", "/api/ops/../user/register"}) {
            var request = new MockHttpServletRequest("POST", path); request.setContentType("application/json");
            request.setContent(new byte[64 * 1024 + 1]); var response = new MockHttpServletResponse();
            new JsonBodyLimitFilter().doFilter(request, response, (req, reply) -> fail("账户超大请求不应进入解析"));
            assertEquals(413, response.getStatus(), path);
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class Echo {
        @org.springframework.web.bind.annotation.PostMapping("/api/user/echo")
        public java.util.Map<String, String> echo(@org.springframework.web.bind.annotation.RequestBody java.util.Map<String, String> body) { return body; }
    }

    @Test void unknownLengthOversizeIs413AtTheMvcBoundaryAndMalformedJsonIsGeneric400() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new Echo())
                .setControllerAdvice(new yagen.waitmydawn.maa.controller.ConcurrencyExceptionHandler())
                .addFilters(new JsonBodyLimitFilter()).build();
        byte[] bytes = ("{\"value\":\"" + "x".repeat(64 * 1024) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/user/echo").with(original -> {
            var request = new MockHttpServletRequest(original.getServletContext(), "POST", "/api/user/echo") {
                @Override public long getContentLengthLong() { return -1; }
                @Override public int getContentLength() { return -1; }
            };
            request.setContentType("application/json"); request.setContent(bytes); return request;
        })).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(413));
        String response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/user/echo")
                .contentType("application/json").content("{\"value\":\"sk-offline-fixture-key\",broken"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("sk-offline")); assertTrue(response.contains("JSON"));
    }
    @Test
    void rejectsKnownOversizeBeforeDeserialization() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/chat");
        request.setContentType("application/json");
        request.setContent(new byte[8 * 1024 * 1024 + 1]);
        var response = new MockHttpServletResponse();
        new JsonBodyLimitFilter().doFilter(request, response, (req, reply) -> fail("超大请求不应进入控制器"));
        assertEquals(413, response.getStatus());
    }

    @Test
    void unknownLengthCannotBypassStreamingLimit() {
        var request = new MockHttpServletRequest("POST", "/api/chat") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContentType("application/json");
        request.setContent(new byte[8 * 1024 * 1024 + 1]);
        assertThrows(IOException.class, () -> new JsonBodyLimitFilter().doFilter(request,
                new MockHttpServletResponse(), (req, reply) -> req.getInputStream().readAllBytes()));
    }
}
