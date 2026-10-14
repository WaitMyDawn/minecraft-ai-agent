package yagen.waitmydawn.maa.runtime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import yagen.waitmydawn.maa.controller.UserController;

import java.io.IOException;
import java.util.Set;

/** 业务接口必须登录；账户恢复和运维探针采用独立访问规则。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_PATHS = Set.of("/api/user/login", "/api/user/register",
            "/api/user/email-code", "/api/user/reset-password", "/api/health", "/api/chat/abort");
    private final UserController users;
    private final java.util.concurrent.Semaphore authenticationRequests = new java.util.concurrent.Semaphore(4);
    private record Window(long startedAt, int count) {}
    private final com.github.benmanes.caffeine.cache.Cache<String, Window> authenticationRates =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(20_000)
                    .expireAfterWrite(java.time.Duration.ofMinutes(2)).build();

    public AuthenticationFilter(UserController users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path;
        try {
            String raw = request.getServletPath();
            if (raw == null || raw.isBlank()) raw = request.getRequestURI().substring(request.getContextPath().length());
            // 与路由的解码、矩阵参数和路径规范化语义对齐，避免原始 URI 绕过 /api/ 判定。
            path = java.net.URLDecoder.decode(raw.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll(";[^/]*", "").replaceAll("/{2,}", "/");
            path = java.net.URI.create(path).normalize().getPath();
        } catch (IllegalArgumentException e) {
            response.setStatus(400);
            return;
        }
        // sendBeacon 的 token 位于请求体中，终止接口由控制器验证登录态。
        Long owner = users.validateToken(request.getHeader("X-Auth-Token"));
        if (path.startsWith("/api/") && !PUBLIC_PATHS.contains(path) && !path.startsWith("/api/ops/")
                && owner == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"未登录，请重新登录\"}");
            return;
        }
        boolean authentication = Set.of("/api/user/login", "/api/user/register", "/api/user/email-code",
                "/api/user/reset-password").contains(path);
        if (authentication) {
            long now = System.currentTimeMillis();
            // 只信任容器提供的来源地址，避免任意 X-Forwarded-For 绕过限制。
            Window window = authenticationRates.asMap().compute(request.getRemoteAddr(), (key, old) ->
                    old == null || now - old.startedAt() >= 60_000 ? new Window(now, 1)
                            : new Window(old.startedAt(), Math.min(31, old.count() + 1)));
            if (window.count() > 30) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"认证请求过于频繁，请稍后重试\"}");
                return;
            }
        }
        var gate = authentication ? authenticationRequests : null;
        if (gate != null && !gate.tryAcquire()) {
            response.setStatus(429);
            response.setHeader("Retry-After", "5");
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请求繁忙，请稍后重试\"}");
            return;
        }
        java.util.concurrent.locks.ReentrantLock accountLock = null;
        boolean write = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
                && (path.startsWith("/api/prefs/") || path.startsWith("/api/user/"));
        // 固定条带避免锁对象淘汰时生成第二把锁，也避免按账号无限增长。
        if (owner != null && write) accountLock = AccountWriteLocks.forOwner(owner);
        boolean locked = false;
        try {
            if (accountLock != null) {
                try { locked = accountLock.tryLock(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (!locked) {
                    response.setStatus(409);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"账户数据正在更新，请稍后重试\"}");
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            if (locked) accountLock.unlock();
            if (gate != null) gate.release();
        }
    }
}
