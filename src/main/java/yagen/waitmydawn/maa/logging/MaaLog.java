package yagen.waitmydawn.maa.logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MAA 日志系统（总日志 + 用户日志，按 YYYY-MM-DD 分目录）。
 *
 * <p>结构（LOG_DIR 默认与 maa_db 同级下的 logs）：
 * <pre>
 * logs/2026-09-05/app-2026-09-05.log
 * logs/2026-09-05/user-3.log
 * logs/2026-09-05/anon-abc12345.log
 * </pre>
 *
 * <p>职责划分：
 * <ul>
 *   <li>app-*.log：启动/请求/阶段/限流等事件摘要 + 全量控制台输出（stdout/stderr tee）；</li>
 *   <li>user-*.log：单个用户的 Agent 完整输入输出、Tool 明细、校验细节与完整异常堆栈。</li>
 * </ul>
 *
 * <p>环境变量：LOG_DIR（日志根目录）、LOG_RETENTION_DAYS（默认 14，自动清理过期日期目录）。
 */
public final class MaaLog {

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter LINE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static volatile Core core;
    private static final ThreadLocal<String> USER_KEY = new ThreadLocal<>();

    private MaaLog() {
    }

    /** 应用启动时调用：读取 LOG_DIR/LOG_RETENTION_DAYS 并替换 System.out/err 为 tee */
    public static synchronized void install() {
        if (core != null) return;
        Path root = resolveRoot();
        int retention = resolveRetention();
        core = new Core(root, retention);
        core.logSystem("[app] 日志系统启动 root=" + root + " retention=" + retention + "d");
        System.setOut(core.createTee(System.out, false));
        System.setErr(core.createTee(System.err, true));
    }

    private static Path resolveRoot() {
        String p = System.getProperty("LOG_DIR");
        if (p == null || p.isBlank()) p = System.getenv("LOG_DIR");
        if (p == null || p.isBlank()) p = "logs";
        return Paths.get(p).toAbsolutePath().normalize();
    }

    private static int resolveRetention() {
        String v = System.getProperty("LOG_RETENTION_DAYS");
        if (v == null || v.isBlank()) v = System.getenv("LOG_RETENTION_DAYS");
        if (v == null || v.isBlank()) return 14;
        try {
            int n = Integer.parseInt(v.trim());
            return n > 0 ? n : 14;
        } catch (NumberFormatException e) {
            return 14;
        }
    }

    // ================= 请求级用户上下文 =================

    public static void setUserKey(String key) {
        USER_KEY.set(key);
    }

    public static void clearUserKey() {
        USER_KEY.remove();
    }

    public static String userKey() {
        return USER_KEY.get();
    }

    // ================= 对外日志方法 =================

    /** 写入当前用户日志（无用户上下文时只写 app 摘要） */
    public static void user(String text) {
        Core c = core;
        String key = USER_KEY.get();
        if (c == null) return;
        if (key != null) {
            c.append(key + ".log", "[" + LocalDateTime.now().format(LINE_TIME) + "] " + text + "\n");
        } else {
            c.logSystem("[user?] " + text);
        }
    }

    /** 写入总日志（带时间戳） */
    public static void app(String text) {
        if (core != null) {
            String key = USER_KEY.get();
            core.logSystem("[app]" + (key != null ? " [u=" + key + "]" : "") + " " + text);
        }
    }

    /** 在指定用户上下文中执行任务（用于异步线程：捕获主线程 key 后设置/清除） */
    public static void runWithUser(String key, Runnable task) {
        String prev = USER_KEY.get();
        if (key != null) USER_KEY.set(key);
        try {
            task.run();
        } finally {
            if (key != null) {
                if (prev != null) USER_KEY.set(prev);
                else USER_KEY.remove();
            }
        }
    }

    /**
     * 异常记录：有用户上下文 → 用户日志写完整堆栈；总日志写原因摘要，
     * 无用户上下文（系统级异常）→ 总日志写完整堆栈。
     */
    public static void error(String message, Throwable t) {
        if (core == null) return;
        String key = USER_KEY.get();
        if (key != null) {
            String stack = stackToString(t);
            core.append(key + ".log", "[" + LocalDateTime.now().format(LINE_TIME) + "] [ERROR] "
                    + message + "\n" + stack + "\n");
            core.logSystem("[app] [ERROR] user=" + key + " " + message
                    + " cause=" + rootMessage(t));
        } else {
            core.logSystem("[app] [ERROR] " + message + "\n" + stackToString(t));
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String m = cur.getMessage();
        return cur.getClass().getSimpleName() + (m != null ? ": " + m : "");
    }

    private static String stackToString(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    // ================= 核心实现 =================

    private static final class Core {

        private final Path root;
        private final int retentionDays;
        private final Object lock = new Object();
        private final Map<String, Writer> openWriters = new LinkedHashMap<>();
        private LocalDate day = LocalDate.now();

        Core(Path root, int retentionDays) {
            this.root = root;
            this.retentionDays = retentionDays;
            try {
                Files.createDirectories(root);
                cleanupOldDirectories();
            } catch (IOException e) {
                System.err.println("[MaaLog] 无法创建日志目录: " + e.getMessage());
            }
        }

        PrintStream createTee(PrintStream original, boolean isErr) {
            return new PrintStream(new TeeOutputStream(original, isErr), true, StandardCharsets.UTF_8);
        }

        /** 系统/控制台行进入 app 日志（内部调用，避免经过被替换的 out） */
        void logSystem(String line) {
            synchronized (lock) {
                try {
                    Writer w = writer("app-" + dayName() + ".log");
                    w.write(line);
                    if (!line.endsWith("\n")) w.write("\n");
                    w.flush();
                } catch (IOException e) {
                    System.err.println("[MaaLog] app 日志写入失败: " + e.getMessage());
                }
            }
        }

        /** 追加用户/其他日志行 */
        void append(String fileName, String content) {
            synchronized (lock) {
                try {
                    Writer w = writer(fileName);
                    w.write(content);
                    w.flush();
                } catch (IOException e) {
                    System.err.println("[MaaLog] 日志写入失败 " + fileName + ": " + e.getMessage());
                }
            }
        }

        private String dayName() {
            return LocalDate.now().format(FILE_DATE);
        }

        private void ensureDay() throws IOException {
            LocalDate today = LocalDate.now();
            if (!today.equals(day)) {
                for (Writer w : openWriters.values()) {
                    try {
                        w.close();
                    } catch (IOException ignored) {
                    }
                }
                openWriters.clear();
                day = today;
                cleanupOldDirectories();
            }
        }

        private Writer writer(String name) throws IOException {
            ensureDay();
            Writer w = openWriters.get(name);
            if (w == null) {
                Path dir = root.resolve(dayName());
                Files.createDirectories(dir);
                w = Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                openWriters.put(name, w);
                if (openWriters.size() > 128) {
                    // 防止大量用户句柄常驻：关闭最旧的一个
                    String oldest = openWriters.keySet().iterator().next();
                    openWriters.remove(oldest).close();
                }
            }
            return w;
        }

        private void cleanupOldDirectories() throws IOException {
            LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path p : ds) {
                    if (!Files.isDirectory(p)) continue;
                    String name = p.getFileName().toString();
                    try {
                        LocalDate d = LocalDate.parse(name, FILE_DATE);
                        if (d.isBefore(cutoff)) {
                            deleteRecursively(p);
                        }
                    } catch (java.time.format.DateTimeParseException ignored) {
                        // 非日期目录不清理
                    }
                }
            }
        }

        private void deleteRecursively(Path dir) throws IOException {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path child : ds) {
                    if (Files.isDirectory(child)) deleteRecursively(child);
                    else Files.deleteIfExists(child);
                }
            }
            Files.deleteIfExists(dir);
        }

        /**
         * 字节级 tee：转发给原始 stdout/stderr，同时把按行缓冲的内容写入 app 日志。
         * 使用增量 CharsetDecoder 处理跨 write 的多字节 UTF-8 切分。
         */
        private final class TeeOutputStream extends OutputStream {

            private final PrintStream target;
            private final boolean isErr;
            private final CharsetDecoder decoder =
                    StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(CodingErrorAction.REPLACE);
            private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream(256);

            TeeOutputStream(PrintStream target, boolean isErr) {
                this.target = target;
                this.isErr = isErr;
            }

            @Override
            public void write(int b) {
                byte[] one = {(byte) b};
                write(one, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                target.write(b, off, len);
                if (len <= 0) return;
                lineBuffer.write(b, off, len);
                byte[] buf = lineBuffer.toByteArray();
                int lastNl = -1;
                for (int i = 0; i < buf.length; i++) {
                    if (buf[i] == '\n') lastNl = i;
                }
                if (lastNl < 0) return;
                String chunk = decode(buf, 0, lastNl + 1);
                emitLine(chunk);
                int remain = buf.length - (lastNl + 1);
                if (remain > 0) {
                    byte[] rest = new byte[remain];
                    System.arraycopy(buf, lastNl + 1, rest, 0, remain);
                    lineBuffer.reset();
                    lineBuffer.write(rest, 0, remain);
                } else {
                    lineBuffer.reset();
                }
            }

            private String decode(byte[] bytes, int off, int len) {
                try {
                    return decoder.decode(java.nio.ByteBuffer.wrap(bytes, off, len)).toString();
                } catch (CharacterCodingException e) {
                    return new String(bytes, off, len, StandardCharsets.UTF_8);
                } finally {
                    decoder.reset();
                }
            }

            private void emitLine(String raw) {
                String line = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                String key = MaaLog.USER_KEY.get();
                logSystem("[" + (isErr ? "ERR " : "") + LocalDateTime.now().format(LINE_TIME) + "]"
                        + (key != null ? " [u=" + key + "]" : "") + " " + line);
            }

            @Override
            public void flush() {
                target.flush();
                if (lineBuffer.size() > 0) {
                    byte[] buf = lineBuffer.toByteArray();
                    String tail = decode(buf, 0, buf.length);
                    if (!tail.isEmpty()) emitLine(tail);
                    lineBuffer.reset();
                }
            }
        }
    }
}
