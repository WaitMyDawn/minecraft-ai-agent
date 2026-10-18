package yagen.waitmydawn.maa.runtime;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** 在 JSON 反序列化前限制字节数；分块传输同样计数，不能绕过 Content-Length 检查。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JsonBodyLimitFilter extends OncePerRequestFilter {
    private static final long MAX_BYTES = 8 * 1024 * 1024L;
    private static final long ACCOUNT_MAX_BYTES = 64 * 1024L;
    public static final class LimitExceededException extends IOException {
        public LimitExceededException() { super("JSON 请求超过字节限额"); }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("json")) {
            chain.doFilter(request, response);
            return;
        }
        String path;
        try { path = AuthenticationFilter.normalizedPath(request); }
        catch (IllegalArgumentException invalid) { response.setStatus(400); return; }
        long limit = path.startsWith("/api/user/") ? ACCOUNT_MAX_BYTES : MAX_BYTES;
        if (request.getContentLengthLong() > limit) {
            response.setStatus(413);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请求内容超过字节限额\"}");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream limited;
            @Override public ServletInputStream getInputStream() throws IOException {
                if (limited != null) return limited;
                ServletInputStream input = super.getInputStream();
                limited = new ServletInputStream() {
                    private long consumed;
                    private void count(int size) throws IOException {
                        if (size > 0 && (consumed += size) > limit) throw new LimitExceededException();
                    }
                    @Override public int read() throws IOException { int value = input.read(); count(value < 0 ? 0 : 1); return value; }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                        int size = input.read(bytes, offset, length); count(size); return size;
                    }
                    @Override public boolean isFinished() { return input.isFinished(); }
                    @Override public boolean isReady() { return input.isReady(); }
                    @Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
                    @Override public void close() throws IOException { input.close(); }
                };
                return limited;
            }
            @Override public BufferedReader getReader() throws IOException {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }
}
