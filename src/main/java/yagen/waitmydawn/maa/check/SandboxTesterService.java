package yagen.waitmydawn.maa.check;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class SandboxTesterService {

    @Value("${maa.sandbox.base-server-dir}")
    private String baseServerDir;

    @Value("${maa.sandbox.server-memory:4G}")
    private String serverMemory;

    @Value("${maa.sandbox.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${maa.sandbox.max-concurrent:1}")
    public int maxConcurrent;  // SandboxController 需要读取

    private Semaphore sandboxLock;  // 在 @PostConstruct 中初始化

    // 上一次运行是否检测到 Preparing level (服务器能启动但可能卡在创建世界)
    private volatile boolean lastRunHadPreparingLevel = false;

    @PostConstruct
    void init() {
        this.sandboxLock = new Semaphore(maxConcurrent);
        System.out.println("沙盒并发槽位数: " + maxConcurrent);
    }

    /** 尝试获取沙盒槽位（立即返回，不阻塞） */
    public boolean tryAcquireSlot() {
        return sandboxLock.tryAcquire();
    }

    /** 释放沙盒槽位 */
    public void releaseSlot() {
        sandboxLock.release();
    }

    /** 获取当前可用槽位数 */
    public int availableSlots() {
        return sandboxLock.availablePermits();
    }
    public boolean isLastRunHadPreparingLevel() { return lastRunHadPreparingLevel; }

    /**
     * 准备沙盒环境: 复制底座服务端, 创建 mods 目录, 写入 eula 和 server.properties
     */
    public void prepareSandboxEnvironment(Path sandboxDir) throws IOException {
        Path baseDir = Paths.get(baseServerDir);
        if (!Files.exists(baseDir)) {
            throw new RuntimeException("找不到服务端底座: " + baseServerDir);
        }

        // 预检查启动脚本是否存在
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String scriptName = isWindows ? "run.bat" : "run.sh";
        if (!Files.exists(baseDir.resolve(scriptName))) {
            throw new RuntimeException("服务端底座缺少启动脚本: " + baseDir.resolve(scriptName) +
                    "。请确认 " + baseServerDir + " 目录下存在 " + scriptName);
        }

        // 预检查 Java
        try {
            new ProcessBuilder("java", "-version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("无法执行 java -version，请确认 Java 已安装并加入 PATH 环境变量");
        }

        // 预检查 libraries 目录和关键依赖库
        Path librariesDir = baseDir.resolve("libraries");
        if (!Files.exists(librariesDir)) {
            throw new RuntimeException("服务端底座缺少 libraries 目录！\n"
                    + "请使用 NeoForge 官方安装器 (https://neoforged.net/) 正确安装服务端，\n"
                    + "确保 " + baseServerDir + " 下存在完整的 libraries/ 文件夹。\n"
                    + "当前路径: " + baseDir.toAbsolutePath());
        }
        System.out.println("🔍 正在预检底座依赖库: " + librariesDir.toAbsolutePath());
        // 检查 Apache Commons Compress (NeoForge 21.1+ 必需, 路径深度约5层)
        try (var libStream = Files.walk(librariesDir)) {
            boolean hasCommonsCompress = libStream.anyMatch(p ->
                    p.getFileName().toString().startsWith("commons-compress-") &&
                    p.getFileName().toString().endsWith(".jar"));
            if (!hasCommonsCompress) {
                throw new RuntimeException("服务端底座 libraries/ 缺少 Apache Commons Compress！\n"
                        + "NeoForge 需要 commons-compress >= 1.26.0。\n"
                        + "请重新运行 NeoForge 安装器生成完整 libraries 目录，\n"
                        + "或从正常运行的客户端 .minecraft/libraries/ 复制 commons-compress 到此处。\n"
                        + "当前 checking 路径: " + librariesDir.toAbsolutePath());
            }
        }

        FileSystemUtils.copyRecursively(baseDir, sandboxDir);

        Path modsDir = sandboxDir.resolve("mods");
        Files.createDirectories(modsDir);

        Files.writeString(sandboxDir.resolve("eula.txt"), "eula=true");

        String properties = """
                server-port=0
                online-mode=false
                enable-query=false
                max-tick-time=-1
                """;
        Files.writeString(sandboxDir.resolve("server.properties"), properties);
    }

    // === 无回调版本 (保持向后兼容) ===
    public boolean runSandboxTest(Path sandboxDir) {
        return runSandboxTest(sandboxDir, line -> {});
    }

    /**
     * 启动无头服务端进行沙盒测试
     * @param sandboxDir 沙盒目录
     * @param outputConsumer 接收 MC 服务端每行 stdout (同时打印到 System.out)
     * @return true 启动成功, false 崩溃或超时
     */
    public boolean runSandboxTest(Path sandboxDir, Consumer<String> outputConsumer) {
        // 槽位由外层 SandboxController 管理，这里不再获取

        try {
            List<String> command = new ArrayList<>();
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                command.add("cmd.exe");
                command.add("/c");
                command.add("run.bat");
            } else {
                command.add("sh");
                command.add("run.sh");
            }

            System.out.println("🖥️ 沙盒工作目录: " + sandboxDir);
            System.out.println("🎮 分配内存: -Xmx" + serverMemory + " -Xms1G");
            System.out.println("⏱️ 超时限制: " + timeoutSeconds + " 秒");
            System.out.println("--- MC 服务端启动日志 ---");

            Path mcOutputFile = sandboxDir.resolve("mc-output.log");

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(sandboxDir.toFile());
            pb.redirectErrorStream(true);
            // 不重定向到文件 — 直接从 InputStream 实时读取, 同时边读边写 mc-output.log
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Xmx" + serverMemory + " -Xms1G");

            long startTime = System.currentTimeMillis();
            Process process = pb.start();
            AtomicBoolean isStartedSuccessfully = new AtomicBoolean(false);
            AtomicBoolean hasCrashed = new AtomicBoolean(false);
            final long[] crashDetectedAt = {0};

            // 🔥 直接从进程 InputStream 逐行实时读取 (比文件轮询快且不漏行)
            Map<String, Integer> logSigCounter = new HashMap<>(); // 重复日志计数器
            Thread reader = new Thread(() -> {
                try (BufferedReader br = process.inputReader();
                     BufferedWriter fileWriter = Files.newBufferedWriter(mcOutputFile)) {
                    String line;
                    int lineCount = 0;
                    boolean inCrash = false;
                    List<String> scannedMods = new ArrayList<>();
                    while ((line = br.readLine()) != null) {
                        lineCount++;
                        // 边读边写文件 (供事后 CrashLogParser 分析)
                        fileWriter.write(line);
                        fileWriter.newLine();

                        // 崩溃堆栈追踪
                        if (line.contains("Exception") || line.contains("Error") || line.startsWith("\tat ")) {
                            inCrash = true;
                        } else if (inCrash && !line.startsWith("\t") && !line.startsWith("at ")
                                && !line.isBlank() && !line.contains("Caused by:")) {
                            inCrash = false;
                        }

                        // 崩溃标志检测 (含 bootstrap 级别的 JVM 异常)
                        if (!hasCrashed.get() && (line.contains("Failed to start the minecraft server")
                                || line.contains("Crash report saved to")
                                || line.contains("net.neoforged.fml.ModLoadingException")
                                || line.contains("Exception in thread \"main\"")
                                || line.contains("Empty pre-release"))) {
                            hasCrashed.set(true);
                            crashDetectedAt[0] = System.currentTimeMillis();
                            System.out.println("💥 检测到服务端崩溃标志! 行 " + lineCount);
                        }

                        // 模组扫描汇总
                        if (line.contains("[SCAN]: Found mod file")) {
                            scannedMods.add(line.replaceAll(".*Found mod file \"([^\"]+)\".*", "$1"));
                            continue;
                        }
                        if (line.contains("[SCAN]: Found library file")) continue;

                        // 打印关键行到控制台
                        if (inCrash || line.contains("Done (")
                                || line.contains("FAILED") || line.contains("Mod loading")
                                || line.contains("FATAL") || line.contains("[main/ERROR]")
                                || line.contains("Loading errors")
                                || line.contains("Missing or unsupported")) {
                            System.out.println("  [MC:" + lineCount + "] " + line);
                        }

                        try { outputConsumer.accept(line); } catch (Exception ignored) {}

                        // 🔥 Preparing level 检测 — 服务器能走到这里说明模组加载基本OK
                        if (line.contains("Preparing level")
                                && line.contains("[minecraft/DedicatedServer]")) {
                            lastRunHadPreparingLevel = true;
                            System.out.println("🌍 检测到 Preparing level — 服务器已进入世界生成阶段");
                        }

                        // 🔥 重复日志检测 — 同一来源重复输出 >50 次 = 卡死循环
                        if (lastRunHadPreparingLevel && !isStartedSuccessfully.get()) {
                            String sig = extractLogSignature(line);
                            if (sig.length() > 10) {
                                int count = logSigCounter.merge(sig, 1, Integer::sum);
                                if (count >= 50) {
                                    hasCrashed.set(true);
                                    crashDetectedAt[0] = System.currentTimeMillis();
                                    System.out.println("🔁 检测到重复日志循环: " + sig + " (×" + count + ")");
                                }
                            }
                        }

                        // 成功启动检测
                        if (line.contains("Done (") && line.contains("For help, type")) {
                            isStartedSuccessfully.set(true);
                            System.out.println("✅ 服务端启动成功！检测到: " + line.trim());
                        }
                    }
                    if (!scannedMods.isEmpty()) {
                        System.out.println("📦 扫描到 " + scannedMods.size() + " 个模组: "
                                + String.join(", ", scannedMods));
                    }
                } catch (IOException e) {
                    // 进程结束或流关闭, 正常退出
                }
            }, "mc-reader");
            reader.setDaemon(true);
            reader.start();

            // 等待: 成功启动 / 崩溃宽限期到 / 进程退出 / 超时
            while (reader.isAlive() && process.isAlive()) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                if (isStartedSuccessfully.get()) break;

                if (hasCrashed.get()) {
                    long crashAge = (System.currentTimeMillis() - crashDetectedAt[0]) / 1000;
                    if (crashAge >= 5) {
                        System.out.println("💥 崩溃后 5 秒宽限期到, 强制终止 MC 进程");
                        break;
                    }
                }

                if (elapsed >= timeoutSeconds) {
                    System.out.println("⏱️ 超时 (" + timeoutSeconds + "s), 终止 MC");
                    break;
                }
                Thread.sleep(1000);
            }

            // 🔥 清理进程: 先杀整个进程树 (防止孤儿 java 进程), 再 destroyForcibly 清理残留
            if (process.isAlive()) {
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    try {
                        new ProcessBuilder("taskkill", "/F", "/T", "/PID",
                                String.valueOf(process.pid())).start().waitFor(5, TimeUnit.SECONDS);
                    } catch (Exception ignored) {}
                }
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }

            reader.interrupt();
            reader.join(3000);

            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            System.out.println("--- MC 测试结束 (耗时 " + elapsed + " 秒) ---");
            System.out.println("📄 输出保存: " + mcOutputFile);

            if (!isStartedSuccessfully.get()) {
                int exitCode = -1;
                try { exitCode = process.exitValue(); } catch (Exception ignored) {}
                System.out.println("❌ 服务端异常退出, exitCode=" + exitCode
                        + ", 耗时=" + elapsed + "s");
            }

            return isStartedSuccessfully.get();

        } catch (Exception e) {
            System.err.println("❌ 沙盒异常: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            // 槽位由外层 SandboxController 释放
        }
    }

    /**
     * 销毁沙盒, 释放磁盘空间
     */
    public void destroySandbox(Path sandboxDir) {
        System.out.println("🧹 正在清理并销毁沙盒...");
        try {
            Thread.sleep(1500);
            FileSystemUtils.deleteRecursively(sandboxDir.toFile());
        } catch (Exception e) {
            System.err.println("⚠️ 销毁沙盒失败 (可能文件被占用，请后续手动清理): " + sandboxDir);
        }
    }

    /**
     * 提取日志行的签名 — 去掉时间戳/坐标/物品ID等变量部分, 用于检测重复输出
     */
    private static String extractLogSignature(String line) {
        // 取日志类来源和消息开头
        String stripped = line
                .replaceAll("\\[\\d{2,4}[./-]\\d{2}[./-]\\d{2,4}\\s+[\\d:]+\\]", "") // 时间戳
                .replaceAll("\\[\\d+:\\d+:\\d+[.\\d]*\\]", "")                           // 另一种时间戳
                .replaceAll("\\d+ minecraft:\\w+", "X MINECRAFT:ITEM")                   // 物品ID
                .replaceAll("BlockPos\\{[^}]+}", "BlockPos{...}")                         // 坐标
                .replaceAll("at position \\d+,\\d+,\\d+", "at position ...")              // 位置
                .replaceAll("\\d+ms", "Xms")                                              // 耗时
                .replaceAll("\\b\\d{3,}\\b", "X")                                         // 大数字
                .trim();
        // 截取前80字符作为签名
        return stripped.length() > 80 ? stripped.substring(0, 80) : stripped;
    }

    /**
     * 定时清理废弃沙盒 (每小时执行一次)
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanupAbandonedSandboxes() {
        try {
            Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
            if (!Files.exists(tmpDir)) return;

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir, "maa-sandbox-*")) {
                for (Path sandboxPath : stream) {
                    if (Files.isDirectory(sandboxPath)) {
                        long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(sandboxPath).toMillis();
                        if (ageMs > 3600000) {
                            System.out.println("🧹 清理废弃沙盒: " + sandboxPath.getFileName());
                            FileSystemUtils.deleteRecursively(sandboxPath.toFile());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("定时清理沙盒异常: " + e.getMessage());
        }
    }
}
