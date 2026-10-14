package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import yagen.waitmydawn.maa.controller.UserController;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationFilterTest {
    @Test
    void businessEndpointsRequireLoginIncludingNewRoutes() throws Exception {
        UserController users = mock(UserController.class);
        when(users.validateToken(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(null);
        AuthenticationFilter filter = new AuthenticationFilter(users);
        AtomicInteger admitted = new AtomicInteger();
        for (String path : new String[]{"/api/modpack/preview", "/api/meta/categories", "/api/prefs/mod-info/create", "/api/future-feature",
                "/api;parameter/meta/categories", "/%61pi/meta/categories", "//api/meta/categories", "/api/ops/../meta/categories"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", path), response, (request, reply) -> admitted.incrementAndGet());
            assertEquals(401, response.getStatus());
        }
        assertEquals(0, admitted.get());
        when(users.validateToken("fixture-token")).thenReturn(1L);
        var request = new MockHttpServletRequest("GET", "/api/meta/categories");
        request.addHeader("X-Auth-Token", "fixture-token");
        filter.doFilter(request, new MockHttpServletResponse(), (req, reply) -> admitted.incrementAndGet());
        assertEquals(1, admitted.get());
    }

    @Test
    void publicAuthenticationIsRateLimitedWithoutTrustingForwardedHeaders() throws Exception {
        AuthenticationFilter filter = new AuthenticationFilter(mock(UserController.class));
        AtomicInteger admitted = new AtomicInteger();
        for (int i = 0; i < 31; i++) {
            var request = new MockHttpServletRequest("POST", "/api/user/login");
            request.addHeader("X-Forwarded-For", "192.0.2." + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, reply) -> admitted.incrementAndGet());
            assertEquals(i < 30 ? 200 : 429, response.getStatus());
        }
        assertEquals(30, admitted.get());
    }
}
