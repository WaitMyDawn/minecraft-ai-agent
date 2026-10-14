package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class JsonBodyLimitFilterTest {
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
