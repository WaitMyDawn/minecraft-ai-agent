package yagen.waitmydawn.maa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 数据库健康探针（结果带缓存）。
 *
 * <p>为什么不让 {@code /api/health} 每次都真查库：健康检查是每 30 秒一次的固定流量，
 * 每次都查库等于给数据库挂上一份与业务无关的常态负载；更要紧的是，数据库一旦变慢，
 * 每 30 秒一次的探针会把"慢"放大成"检查超时 → 容器被判不健康"，反而制造假故障。
 * 这里缓存 {@code ttlMs}（默认 60 秒），稳态下每分钟只查一次。
 *
 * <p>为什么查的是 {@code maa_user} 而不是 {@code SELECT 1}：{@code SELECT 1} 只证明"能建连接"，
 * 而且 H2 对<b>不存在的文件会直接新建一个空库</b>——库文件被误删时 {@code SELECT 1} 依然成功，
 * 探针会一路报 up，等用户来告诉你"我的账号没了"。查一张真实的业务表才能照出这类事故。
 *
 * <p>三态：{@code unknown}（还没探过）/ {@code up} / {@code down}。失败也把时刻记下来，
 * 否则库挂了以后每一次健康检查都会去敲一次已经挂掉的库。
 */
@Component
public class DbHealthProbe {

    /** 探针 SQL：走真实业务表，顺带验证连接池也还好使 */
    private static final String PROBE_SQL = "SELECT COUNT(*) FROM maa_user";
    /** 查询超时（秒）：库卡住时不能让健康检查跟着一起挂住 */
    private static final int QUERY_TIMEOUT_SECONDS = 3;

    private final DataSource dataSource;
    private final long ttlMs;

    private volatile String state = "unknown";
    private volatile String lastError;
    private volatile long checkedAtMillis;

    public DbHealthProbe(DataSource dataSource,
                         @Value("${maa.db.health.ttl-ms:60000}") long ttlMs) {
        this.dataSource = dataSource;
        this.ttlMs = Math.max(0, ttlMs);
    }

    /** "up" / "down" / "unknown"，必要时顺带刷新一次 */
    public String state() {
        refreshIfStale();
        return state;
    }

    /** 上次探测时刻（毫秒时间戳，0 = 从未探测） */
    public long checkedAtMillis() {
        refreshIfStale();
        return checkedAtMillis;
    }

    /** 失败原因的**一句话摘要**（成功时为 null）。故意不带栈：这个端点是对公网开放的。 */
    public String lastError() {
        refreshIfStale();
        return lastError;
    }

    /** 强制立刻探一次（不带缓存）。恢复数据库之后可以调它把状态掰回来，测试也用它。 */
    public void refresh() {
        long now = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            try (ResultSet rs = st.executeQuery(PROBE_SQL)) {
                rs.next();
            }
            state = "up";
            lastError = null;
        } catch (Exception e) {
            state = "down";
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            checkedAtMillis = now;
        }
    }

    private synchronized void refreshIfStale() {
        long now = System.currentTimeMillis();
        // 在锁内重新读一次：并发请求一起进来时，只有第一个真的去查库
        if (checkedAtMillis != 0 && now - checkedAtMillis < ttlMs) return;
        refresh();
    }
}
