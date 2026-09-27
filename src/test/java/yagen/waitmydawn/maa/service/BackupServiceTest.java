package yagen.waitmydawn.maa.service;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在线备份测试。
 *
 * <p>对着真实的 H2 文件库跑（不是 mock）：这个功能的价值全在"备出来的东西真能当库用"，
 * 用假对象测等于什么都没测。这里验四件事：zip 真的产出了、里面就是我们平时看到的那份
 * {@code knowledge.mv.db}、同名不覆盖、目录名做了净化（挡住 {@code ../..} 这类穿目录）。
 *
 * <p>连接方式刻意与生产同构：Hikari 连接池 + {@code AUTO_SERVER=TRUE} 的文件库 ——
 * 换成正连的 {@code JdbcDataSource} 跑通说明不了生产路径也对。
 */
class BackupServiceTest {

    @TempDir
    Path tmp;

    private HikariDataSource freshDatabase(String name) throws Exception {
        Path dir = tmp.resolve("db");
        Files.createDirectories(dir);
        // 与 application.properties 的 spring.datasource.url 同形
        String url = "jdbc:h2:file:" + dir.resolve(name).toString().replace('\\', '/') + ";AUTO_SERVER=TRUE";
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setDriverClassName("org.h2.Driver");
        ds.setUsername("sa");
        ds.setPassword("");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE maa_user(ID BIGINT PRIMARY KEY, NAME VARCHAR(50))");
            st.execute("INSERT INTO maa_user VALUES(1,'a'),(2,'b'),(3,'c')");
        }
        return ds;
    }

    /** H2 会一直占着文件句柄，测试结束前必须关掉，否则 @TempDir 清理会失败 */
    private void shutdown(HikariDataSource ds) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("SHUTDOWN");
        } catch (Exception ignored) {
        } finally {
            ds.close();
        }
    }

    @Test
    @DisplayName("备份产出可读 zip，里面是 knowledge.mv.db")
    void backupProducesUsableZip() throws Exception {
        HikariDataSource ds = freshDatabase("knowledge");
        try {
            BackupService svc = new BackupService(ds, tmp.resolve("backups").toString());
            BackupService.Result r = svc.backup("20260927-0400");

            assertEquals("20260927-0400", r.tag());
            Path zip = Path.of(r.file());
            assertTrue(Files.exists(zip), "备份文件必须真的落盘：" + zip);
            assertTrue(r.bytes() > 0, "备份文件不能是 0 字节");
            assertEquals(Files.size(zip), r.bytes(), "回报的字节数要和实际文件一致");

            try (ZipFile zf = new ZipFile(zip.toFile())) {
                boolean hasDb = zf.stream().map(ZipEntry::getName)
                        .anyMatch(n -> n.equals("knowledge.mv.db"));
                assertTrue(hasDb, "zip 里必须是 knowledge.mv.db（恢复就是把它解压回 maa_db）");
            }
        } finally {
            shutdown(ds);
        }
    }

    @Test
    @DisplayName("同名备份不覆盖：宁可报错，也不能把上一份好备份踩掉")
    void refusesToOverwrite() throws Exception {
        HikariDataSource ds = freshDatabase("knowledge");
        try {
            BackupService svc = new BackupService(ds, tmp.resolve("backups").toString());
            svc.backup("same-tag");
            assertThrows(IllegalStateException.class, () -> svc.backup("same-tag"));
        } finally {
            shutdown(ds);
        }
    }

    @Test
    @DisplayName("目录名净化：../../evil 不许穿出备份根目录")
    void tagCannotEscapeBackupRoot() throws Exception {
        HikariDataSource ds = freshDatabase("knowledge");
        try {
            Path root = tmp.resolve("backups").toAbsolutePath().normalize();
            BackupService svc = new BackupService(ds, root.toString());
            BackupService.Result r = svc.backup("../../evil");

            Path written = Path.of(r.file()).toAbsolutePath().normalize();
            assertTrue(written.startsWith(root), "备份必须落在根目录里，实际：" + written);
            assertEquals("evil", r.tag(), "'.' 和 '/' 都应被剥掉");
        } finally {
            shutdown(ds);
        }
    }

    @Test
    @DisplayName("空 tag 用时间戳兜底，不会把文件写到根目录本身")
    void emptyTagFallsBackToTimestamp() throws Exception {
        HikariDataSource ds = freshDatabase("knowledge");
        try {
            Path root = tmp.resolve("backups").toAbsolutePath().normalize();
            BackupService svc = new BackupService(ds, root.toString());
            BackupService.Result r = svc.backup("  ");

            assertTrue(r.tag().matches("\\d{8}-\\d{6}"), "兜底 tag 应是 yyyyMMdd-HHmmss，实际：" + r.tag());
            assertTrue(Path.of(r.file()).toAbsolutePath().normalize().startsWith(root.resolve(r.tag())));
        } finally {
            shutdown(ds);
        }
    }
}
