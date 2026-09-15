package yagen.waitmydawn.maa.cache;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * ModrinthCache 全量抓取脚本（独立入口，不依赖 Spring 容器）。
 *
 * <p>职责：通过 Modrinth search API 分页抓取全部 mod 项目（约 7.5 万个），
 * 写入独立 H2 数据库 modrinth_cache.mv.db。每次运行都是"清空 + 全量重抓"，
 * 使用单事务替换：中途失败自动回滚，不会留下半截数据。
 *
 * <p>限速策略：默认 2 请求/秒（顺序请求 + 抖动），读取 Retry-After /
 * X-Ratelimit-Reset 头做退避，5xx 指数退避重试，防止被 429 或封 IP。
 *
 * <p>运行示例（项目根目录，需先停止正在使用该库的 App）：
 * <pre>
 *   mvnw -q compile exec:java -Dexec.mainClass=yagen.waitmydawn.maa.cache.ModrinthCacheBuilder ^
 *        -Dexec.args="--db maa_db/modrinth_cache.mv.db --qps 2"
 * </pre>
 */
public class ModrinthCacheBuilder {

    /** search 接口单页最大条数（Modrinth 上限 100） */
    private static final int PAGE_SIZE = 100;

    /** 需要从 categories 中剥离并单独存为 loaders 的加载器标签 */
    private static final Set<String> LOADER_TAGS = Set.of(
            "fabric", "forge", "neoforge", "quilt", "liteloader", "rift");

    /**
     * 正式版 MC 版本号（1.20 / 1.21.1 这种），用于过滤掉快照与预发布：
     * 24w14a、1.21.5-rc1、26.1-snapshot-3 等一律不落库。
     *
     * <p>只留正式版是因为 game_versions 只用于"本地否定"（列表里没有目标版本 → 一定不兼容），
     * 而目标版本本身也只会是正式版。
     */
    private static final Pattern RELEASE_VERSION = Pattern.compile("^\\d+\\.\\d+(\\.\\d+)?$");

    private static final String SEARCH_URL =
            "https://api.modrinth.com/v2/search?query=&limit=100&offset=%d&facets=%s";

    private static final String USER_AGENT =
            "MAA-CacheBuilder/1.0 (Minecraft-AI-Agent; contact@example.com)";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .build();

    // 运行配置
    private String dbPath = "maa_db/modrinth_cache.mv.db";
    private double qps = 2.0;
    private boolean dryRun = false;
    private boolean compactOnly = false;
    private boolean ensureSchemaOnly = false;
    private String resetVersionsMode = null;

    // 统计信息
    private int requestCount = 0;
    private int rateLimitCount = 0;
    private int serverErrorCount = 0;

    public static void main(String[] args) {
        ModrinthCacheBuilder builder = new ModrinthCacheBuilder();
        builder.parseArgs(args);
        try {
            builder.run();
        } catch (Exception e) {
            System.err.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--db" -> dbPath = args[++i];
                case "--qps" -> qps = Double.parseDouble(args[++i]);
                case "--dry-run" -> dryRun = true;
                case "--compact-only" -> compactOnly = true;
                case "--ensure-schema" -> ensureSchemaOnly = true;
                case "--reset-versions" -> resetVersionsMode = args[++i];
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("未知参数: " + args[i]);
            }
        }
        if (qps <= 0 || qps > 4) {
            // 上限 4 是安全冗余：官方约 300 次/分钟，4 rps = 240/min 留足余量
            throw new IllegalArgumentException("--qps 需在 (0, 4] 区间（建议 2）");
        }
    }

    private static void printHelp() {
        System.out.println("""
                用法: ModrinthCacheBuilder [选项]
                  --db <path>      H2 数据库文件路径 (默认: maa_db/modrinth_cache.mv.db)
                  --qps <num>      每秒最大请求数, 范围 (0,4], 默认 2
                  --dry-run        只请求第一页打印 total_hits, 不写数据库
                  --ensure-schema  独立命令: 校验/补齐表结构与 versions/game_versions 列(不抓取,不删数据)
                  --compact-only   压缩并关闭数据库
                  --reset-versions <all|slug,slug,...>
                                   独立命令: 清空指定 slug 或全部模组的 versions 字段(不抓取)
                                   随后可再运行抓取脚本重建(versions 默认按 slug 保留)
                  --help           显示本帮助
                """);
    }

    private void run() throws Exception {
        ensureDriver();
        if (compactOnly) {
            compactDatabase();
            return;
        }
        if (ensureSchemaOnly) {
            ensureSchema();
            return;
        }
        if (resetVersionsMode != null) {
            resetVersions(resetVersionsMode);
            return;
        }
        log("=== ModrinthCache 全量抓取开始 ===");
        log("DB: %s | QPS: %.1f | dryRun: %s", dbPath, qps, dryRun);

        // 先抓第一页拿 total_hits（同时校验网络/接口正常）
        JsonNode firstPage = fetchPage(0);
        int totalHits = firstPage.path("total_hits").asInt(0);
        log("Modrinth project_type=mod 总数: %d", totalHits);

        if (dryRun) {
            log("dry-run 模式，未写入数据库。");
            return;
        }
        if (totalHits <= 0) {
            throw new IllegalStateException("total_hits <= 0，拒绝清空现有库，已中止。");
        }

        // 建目录与表，随后单事务替换
        Path dbFile = Paths.get(dbPath);
        if (dbFile.getParent() != null) {
            Files.createDirectories(dbFile.getParent());
        }

        int totalPages = (totalHits + PAGE_SIZE - 1) / PAGE_SIZE;
        log("预计分页数: %d（每页 %d）", totalPages, PAGE_SIZE);

        try (Connection conn = openConnection()) {
            createTableIfMissing(conn);
            ensureVersionsColumn(conn);
            ensureGameVersionsColumn(conn);
            conn.setAutoCommit(false);
            long t0 = System.currentTimeMillis();
            try {
                // 全量重抓前先按 slug 读取旧 versions，新快照写入后回填
                Map<String, String> preservedVersions = readVersions(conn);
                clearTable(conn);

                // 第一页已在事务外取过，这里合并处理：先清空再插入第一页数据
                insertPage(conn, firstPage);

                for (int offset = PAGE_SIZE; offset < totalHits; offset += PAGE_SIZE) {
                    JsonNode page = fetchPage(offset);
                    insertPage(conn, page);
                    if (offset % (PAGE_SIZE * 10) == 0) {
                        log("进度: %d/%d (%.1f%%) 已写入 %d 条", offset, totalHits,
                                offset * 100.0 / totalHits, insertedRows);
                    }
                }
                restoreVersions(conn, preservedVersions);
                conn.commit();
                long costSec = (System.currentTimeMillis() - t0) / 1000;
                long distinctRows = countRows(conn);
                log("✅ 全量抓取成功: 共 %d 条(去重后), 请求 %d 次(429=%d, 5xx=%d), 耗时 %ds",
                        distinctRows, requestCount, rateLimitCount, serverErrorCount, costSec);
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        // 单事务会产生大量临时页，收尾压缩释放空间
        compactDatabase();
    }

    private static long countRows(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM modrinth_cache")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 压缩 H2 文件并关闭数据库（SHUTDOWN COMPACT） */
    private void compactDatabase() throws Exception {
        Path dbFile = Paths.get(dbPath);
        if (!Files.exists(dbFile) && !Files.exists(Paths.get(dbPath + ".mv.db"))) {
            throw new IllegalStateException("数据库文件不存在: " + dbPath);
        }
        try (Connection conn = openConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("SHUTDOWN COMPACT");
            }
        }
        Path actual = dbFile.toString().endsWith(".mv.db") ? dbFile : Paths.get(dbPath + ".mv.db");
        if (Files.exists(actual)) {
            log("已压缩数据库: %s (%.1f MB)", actual, Files.size(actual) / 1048576.0);
        }
    }

    private static void ensureDriver() throws ClassNotFoundException {
        Class.forName("org.h2.Driver");
    }

    private Connection openConnection() throws SQLException {
        // H2 会自动追加 .mv.db 后缀，因此 URL 中要传"去掉扩展名"的库名，
        // 允许 --db 传入完整文件名（含 .mv.db）或裸库名，统一归一化。
        String base = Paths.get(dbPath).toAbsolutePath().toString().replace('\\', '/');
        if (base.endsWith(".mv.db")) {
            base = base.substring(0, base.length() - ".mv.db".length());
        }
        String url = "jdbc:h2:file:" + base + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE";
        return DriverManager.getConnection(url, "sa", "");
    }

    private static void createTableIfMissing(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS modrinth_cache (
                        slug        VARCHAR(64)  PRIMARY KEY,
                        title       VARCHAR(255) NOT NULL,
                        categories  VARCHAR(2048) NOT NULL,
                        description VARCHAR      NOT NULL,
                        downloads   BIGINT       NOT NULL,
                        loaders     VARCHAR(512) NOT NULL,
                        versions    VARCHAR,
                        game_versions VARCHAR,
                        updated_at  TIMESTAMP    NOT NULL
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_modrinth_cache_downloads ON modrinth_cache(downloads)");
        }
    }

    /** 老库升级：补 versions 列（JSON 对象，如 {"neoforge":["1.21.1"]}），先用元数据确认避免重复/未知列 */
    private static void ensureVersionsColumn(Connection conn) throws SQLException {
        boolean hasColumn = false;
        try (java.sql.ResultSet cols = conn.getMetaData()
                .getColumns(null, null, "MODRINTH_CACHE", "VERSIONS")) {
            hasColumn = cols.next();
        }
        if (!hasColumn) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE modrinth_cache ADD COLUMN versions VARCHAR");
            }
            log("已为旧库补充 versions 列");
        }
    }

    /**
     * 老库升级：补 game_versions 列（JSON 数组，如 ["1.20.1","1.21.1"]）。
     *
     * <p>与 versions 列的区别：versions 是"运行时惰性探测"到的 loader 级精确结论，
     * game_versions 是建库时从 search 响应直接拿到的"项目级支持版本"，两者语义不同，必须分开存。
     */
    private static void ensureGameVersionsColumn(Connection conn) throws SQLException {
        boolean hasColumn = false;
        try (java.sql.ResultSet cols = conn.getMetaData()
                .getColumns(null, null, "MODRINTH_CACHE", "GAME_VERSIONS")) {
            hasColumn = cols.next();
        }
        if (!hasColumn) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE modrinth_cache ADD COLUMN game_versions VARCHAR");
            }
            log("已为旧库补充 game_versions 列");
        }
    }

    /** 独立命令：确认表存在、versions 列存在，随后压缩关闭（不动任何数据） */
    private void ensureSchema() throws Exception {
        ensureDriver();
        Path dbFile = Paths.get(dbPath);
        if (dbFile.getParent() != null) {
            Files.createDirectories(dbFile.getParent());
        }
        try (Connection conn = openConnection()) {
            createTableIfMissing(conn);
            ensureVersionsColumn(conn);
            ensureGameVersionsColumn(conn);
            StringBuilder cols = new StringBuilder();
            try (java.sql.ResultSet rs = conn.getMetaData()
                    .getColumns(null, null, "MODRINTH_CACHE", null)) {
                while (rs.next()) {
                    if (cols.length() > 0) cols.append(", ");
                    cols.append(rs.getString("COLUMN_NAME"));
                }
            }
            log("schema OK, 当前列: %s", cols);
        }
        compactDatabase();
    }

    private static Map<String, String> readVersions(Connection conn) throws SQLException {
        Map<String, String> versions = new HashMap<>();
        try (Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT slug, versions FROM modrinth_cache WHERE versions IS NOT NULL")) {
            while (rs.next()) {
                versions.put(rs.getString(1), rs.getString(2));
            }
        }
        return versions;
    }

    /** 新快照写入后，把旧 versions 回填给仍存在的 slug */
    private static void restoreVersions(Connection conn, Map<String, String> preserved) throws SQLException {
        if (preserved.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE modrinth_cache SET versions = ? WHERE slug = ?")) {
            for (Map.Entry<String, String> e : preserved.entrySet()) {
                ps.setString(1, e.getValue());
                ps.setString(2, e.getKey());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 独立命令：清空全部或指定 slug 的 versions（JSON 置 NULL），随后压缩关闭 */
    private void resetVersions(String mode) throws Exception {
        ensureDriver();
        if (!Files.exists(Paths.get(dbPath)) && !Files.exists(Paths.get(dbPath + ".mv.db"))) {
            throw new IllegalStateException("数据库文件不存在: " + dbPath);
        }
        try (Connection conn = openConnection()) {
            ensureVersionsColumn(conn);
            int cleared;
            if ("all".equalsIgnoreCase(mode.trim())) {
                try (Statement st = conn.createStatement()) {
                    cleared = st.executeUpdate("UPDATE modrinth_cache SET versions = NULL WHERE versions IS NOT NULL");
                }
            } else {
                String[] slugs = mode.split(",");
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE modrinth_cache SET versions = NULL WHERE slug = ?")) {
                    for (String s : slugs) {
                        String slug = s.trim();
                        if (!slug.isEmpty()) {
                            ps.setString(1, slug);
                            ps.addBatch();
                        }
                    }
                    cleared = ps.executeBatch().length;
                }
            }
            log("已清空 versions 记录 %d 条 (mode=%s)", cleared, mode);
        }
        compactDatabase();
    }

    private static void clearTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM modrinth_cache");
        }
    }

    private int insertedRows = 0;

    private void insertPage(Connection conn, JsonNode page) throws SQLException {
        JsonNode hits = page.path("hits");
        if (!hits.isArray()) return;
        // 分页过程中 Modrinth 索引可能变化导致同一 slug 重复出现，用 MERGE 按主键覆盖即可幂等
        String sql = "MERGE INTO modrinth_cache(slug,title,categories,description,downloads,loaders,game_versions,updated_at)"
                + " KEY(slug) VALUES(?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            for (JsonNode hit : hits) {
                String slug = hit.path("slug").asText("").trim();
                if (slug.isEmpty()) continue;

                List<String> categories = new ArrayList<>();
                List<String> loaders = new ArrayList<>();
                JsonNode catArr = hit.path("categories");
                if (catArr.isArray()) {
                    for (JsonNode c : catArr) {
                        String cat = c.asText("").trim();
                        if (cat.isEmpty()) continue;
                        if (LOADER_TAGS.contains(cat)) {
                            loaders.add(cat);
                        } else {
                            categories.add(cat);
                        }
                    }
                }

                ps.setString(1, slug);
                ps.setString(2, hit.path("title").asText(""));
                ps.setString(3, mapper.writeValueAsString(categories));
                ps.setString(4, hit.path("description").asText(""));
                ps.setLong(5, hit.path("downloads").asLong(0));
                ps.setString(6, mapper.writeValueAsString(new TreeSet<>(loaders)));
                ps.setString(7, collectReleaseVersions(hit));
                ps.setTimestamp(8, now);
                ps.addBatch();
                insertedRows++;
            }
            ps.executeBatch();
        } catch (Exception e) {
            throw new SQLException("写库失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 search 响应里读出该项目支持的游戏版本（search hit 自带 versions 字段，不产生额外请求），
     * 过滤掉快照/预发布后以 JSON 数组形式落库；无可用数据时返回 null。
     */
    private String collectReleaseVersions(JsonNode hit) throws Exception {
        JsonNode arr = hit.path("versions");
        if (!arr.isArray()) return null;
        // LinkedHashSet 去重并保留 API 返回的先后顺序（大致按时间递增），便于人工排查
        java.util.LinkedHashSet<String> releases = new java.util.LinkedHashSet<>();
        for (JsonNode v : arr) {
            String s = v.asText("").trim();
            if (!s.isEmpty() && RELEASE_VERSION.matcher(s).matches()) releases.add(s);
        }
        return releases.isEmpty() ? null : mapper.writeValueAsString(releases);
    }

    /**
     * 抓取一页 search 结果。顺序限速 + 429/5xx 退避重试。
     */
    private JsonNode fetchPage(int offset) throws Exception {
        long baseIntervalMs = (long) (1000.0 / qps * (1 + ThreadLocalRandom.current().nextDouble() * 0.15));
        String facets = URLEncoder.encode("[[" + quoted("project_type:mod") + "]]", StandardCharsets.UTF_8);
        String url = String.format(SEARCH_URL, offset, facets);

        for (int attempt = 0; attempt < 6; attempt++) {
            sleepInterruptibly(baseIntervalMs);
            requestCount++;

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                serverErrorCount++;
                log("网络异常(第 %d 页, 尝试 %d): %s", offset, attempt + 1, e.getMessage());
                if (attempt == 5) throw e;
                sleepInterruptibly((long) (2000 * Math.pow(2, attempt)));
                continue;
            }

            int status = response.statusCode();
            if (status == 200) {
                return mapper.readTree(response.body());
            }
            if (status == 429) {
                rateLimitCount++;
                long waitSeconds = readResetSeconds(response) + 1;
                log("⚠️ 429 限流(第 %d 页), 等待 %ds 后重试...", offset, waitSeconds);
                sleepInterruptibly(waitSeconds * 1000L);
                continue;
            }
            if (status >= 500) {
                serverErrorCount++;
                long backoffMs = (long) (2000 * Math.pow(2, Math.min(attempt, 4)));
                log("服务端 %d(第 %d 页), %dms 后重试...", status, offset, backoffMs);
                sleepInterruptibly(backoffMs);
                continue;
            }
            throw new IllegalStateException("Modrinth 返回异常状态 " + status + ": " + url);
        }
        throw new IllegalStateException("第 " + offset + " 页重试次数耗尽");
    }

    /** Modrinth search 返回的限流头是秒数（X-Ratelimit-Reset 或 Retry-After） */
    private static long readResetSeconds(HttpResponse<?> response) {
        for (String header : new String[]{"X-Ratelimit-Reset", "Retry-After"}) {
            String value = response.headers().firstValue(header).orElse(null);
            if (value != null) {
                try {
                    return Long.parseLong(value.trim());
                } catch (NumberFormatException ignored) {
                    // 非数字（如日期）时忽略，走默认等待
                }
            }
        }
        return 30;
    }

    private static String quoted(String s) {
        return "\"" + s + "\"";
    }

    private static void sleepInterruptibly(long millis) throws InterruptedException {
        if (millis <= 0) return;
        Thread.sleep(millis);
    }

    private static void log(String format, Object... args) {
        System.out.println("[" + Instant.now().toString().substring(11, 19) + "] "
                + String.format(format, args));
    }
}
