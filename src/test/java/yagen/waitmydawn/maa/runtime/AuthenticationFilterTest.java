package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import yagen.waitmydawn.maa.controller.UserController;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationFilterTest {
    @Test @org.junit.jupiter.api.Timeout(5)
    void authenticationConcurrencyGateRejectsOverflowAndReleasesAfterFailure() throws Exception {
        var users = mock(UserController.class);
        when(users.validateToken(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(null);
        var filter = new AuthenticationFilter(users);
        var entered = new java.util.concurrent.CountDownLatch(4); var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (int i = 0; i < 4; i++) results.add(executor.submit(() -> {
                    try {
                        filter.doFilter(new MockHttpServletRequest("POST", "/api/user/login"), new MockHttpServletResponse(), (req, reply) -> {
                            entered.countDown();
                            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            throw new java.io.IOException("fixture authentication failure");
                        });
                        fail("应保留原始异常");
                    } catch (java.io.IOException expected) { }
                    catch (jakarta.servlet.ServletException failure) { throw new RuntimeException(failure); }
                }));
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                var rejected = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("POST", "/api/user/login"), rejected, (req, reply) -> fail("超出认证并发"));
                assertEquals(429, rejected.getStatus()); assertEquals("5", rejected.getHeader("Retry-After"));
            } finally { release.countDown(); }
            for (var result : results) result.get(2, java.util.concurrent.TimeUnit.SECONDS);
            var recovered = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", "/api/user/login"), recovered, (req, reply) -> {});
            assertEquals(200, recovered.getStatus());
        }
    }
    @Test void rateLimitsUseIndependentContainerAddressesNotClientForwardedAddresses() throws Exception {
        AuthenticationFilter filter = new AuthenticationFilter(mock(UserController.class));
        for (String address : java.util.List.of("192.0.2.1", "192.0.2.2")) for (int i = 0; i < 31; i++) {
            var request = new MockHttpServletRequest("POST", "/api/user/email-code"); request.setRemoteAddr(address);
            request.addHeader("X-Forwarded-For", "198.51.100." + i);
            request.addHeader("Forwarded", "for=198.51.100." + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, reply) -> {});
            assertEquals(i < 30 ? 200 : 429, response.getStatus());
            if (i == 30) assertEquals("60", response.getHeader("Retry-After"));
        }
    }
    @Test
    void allDeclaredBusinessRoutesRejectMissingAndInvalidTokensBeforeDispatch() throws Exception {
        var publicPaths = java.util.Set.of("/api/user/login", "/api/user/register", "/api/user/email-code",
                "/api/user/reset-password", "/api/chat/abort", "/api/health");
        var controllers = java.util.List.of(
                UserController.class,
                yagen.waitmydawn.maa.controller.ChatController.class,
                yagen.waitmydawn.maa.controller.PreferencesController.class,
                yagen.waitmydawn.maa.controller.ModpackController.class,
                yagen.waitmydawn.maa.controller.BrowserOperationsController.class,
                yagen.waitmydawn.maa.controller.MetaController.class,
                yagen.waitmydawn.maa.controller.KnowledgeController.class,
                yagen.waitmydawn.maa.controller.KnowledgeFeedbackController.class);
        UserController users = mock(UserController.class);
        when(users.validateToken(org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(null);
        AuthenticationFilter filter = new AuthenticationFilter(users);
        var checked = new java.util.HashSet<String>();
        for (var controller : controllers) {
            var prefix = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(controller,
                    org.springframework.web.bind.annotation.RequestMapping.class);
            for (var method : controller.getDeclaredMethods()) {
                var route = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(method,
                        org.springframework.web.bind.annotation.RequestMapping.class);
                if (route == null) continue;
                String[] suffixes = route.value().length == 0 ? new String[]{""} : route.value();
                for (String suffix : suffixes) {
                    String path = prefix.value()[0] + suffix;
                    if (publicPaths.contains(path)) continue;
                    checked.add(path);
                    for (String token : new String[]{null, "invalid-fixture"}) {
                        var request = new MockHttpServletRequest("POST", path.replaceAll("\\{[^}]+}", "fixture"));
                        if (token != null) request.addHeader("X-Auth-Token", token);
                        var response = new MockHttpServletResponse();
                        filter.doFilter(request, response, (req, reply) -> fail("未经登录放行：" + path));
                        assertEquals(401, response.getStatus(), path);
                    }
                }
            }
        }
        assertTrue(checked.containsAll(java.util.Set.of("/api/chat", "/api/operations/{id}/facts",
                "/api/modpack/build", "/api/prefs/conversations/{id}/messages")));
        assertTrue(checked.size() >= 30);
    }
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
