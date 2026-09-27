package yagen.waitmydawn.maa.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库健康探针测试。
 *
 * <p>盯三件事：真实表在→up；表没了→down（这正是"库文件被删/被换成空库"的照妖镜，
 * 用 {@code SELECT 1} 是照不出来的）；探测结果在 TTL 内被缓存，不能每次健康检查都去敲库。
 */
class DbHealthProbeTest {

    private static JdbcDataSource dataSource(String url) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static void exec(JdbcDataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    @DisplayName("真实业务表在 → up，且记下探测时刻")
    void reportsUp() throws Exception {
        JdbcDataSource ds = dataSource("jdbc:h2:mem:probe-up;DB_CLOSE_DELAY=-1");
        exec(ds, "CREATE TABLE maa_user(ID BIGINT PRIMARY KEY)");
        exec(ds, "INSERT INTO maa_user VALUES(1)");

        DbHealthProbe probe = new DbHealthProbe(ds, 60_000);
        assertEquals("up", probe.state());
        assertNull(probe.lastError());
        assertTrue(probe.checkedAtMillis() > 0, "探测时刻必须落下来，否则缓存无从判断新鲜度");
    }

    @Test
    @DisplayName("表消失 → 缓存内仍报 up（不追着库敲），强制刷新后变 down")
    void cachingThenDown() throws Exception {
        JdbcDataSource ds = dataSource("jdbc:h2:mem:probe-down;DB_CLOSE_DELAY=-1");
        exec(ds, "CREATE TABLE maa_user(ID BIGINT PRIMARY KEY)");

        DbHealthProbe probe = new DbHealthProbe(ds, 60_000);   // TTL 60 秒，测试里不会过期
        assertEquals("up", probe.state());

        exec(ds, "DROP TABLE maa_user");
        assertEquals("up", probe.state(), "TTL 内必须走缓存：健康检查每 30 秒一次，不能每次都查库");

        probe.refresh();
        assertEquals("down", probe.state());
        assertNotNull(probe.lastError(), "失败必须留下原因，否则线上只能看到 down 不知道为什么");
    }

    @Test
    @DisplayName("连不上数据库 → down，且不抛异常（探针自己不能把健康检查带崩）")
    void unreachableDatabaseIsDown() {
        JdbcDataSource ds = dataSource("jdbc:h2:tcp://127.0.0.1:9/nope;NETWORK_TIMEOUT=300");
        DbHealthProbe probe = new DbHealthProbe(ds, 60_000);
        assertEquals("down", probe.state());
        assertNotNull(probe.lastError());
    }
}
