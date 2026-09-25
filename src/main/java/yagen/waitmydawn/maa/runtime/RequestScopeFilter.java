package yagen.waitmydawn.maa.runtime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 给每个进入的 HTTP 请求开一个 {@link RequestScope}，请求结束（含异常）时关闭。
 *
 * <p>放在过滤器层而不是控制器里，是为了让所有端点自动获得这个上下文——控制器和
 * service 都不用显式管理，也不会漏掉某个新加的端点。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestScopeFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        RequestScope.open();
        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestScope.close();
        }
    }
}
