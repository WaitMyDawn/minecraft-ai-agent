package yagen.waitmydawn.maa.check;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import yagen.waitmydawn.maa.service.AiAgentService;
import yagen.waitmydawn.maa.service.DependencyEngine;
import yagen.waitmydawn.maa.model.DependencyGraph;
import yagen.waitmydawn.maa.service.ModrinthApiClient;
import yagen.waitmydawn.maa.service.ModrinthDownloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 自愈引擎 — 减法模式
 *
 * 准备轮 (1次): BFS解析依赖 → 全量下载 → 建 modid→(slug, jarFile) 映射表
 * 操作轮 (最多20轮): 启动沙盒 → 崩溃 → AI诊断 → 按modid删jar → 重启 (不重新下载/不重新BFS)
 * 安全熔断: 连续两轮 jar 文件集合完全相同 → 中止
 */
@Service
public class SelfHealingEngine {

    private final DependencyEngine dependencyEngine;
    private final SandboxTesterService sandboxTester;
    private final ModrinthDownloader downloader;
    private final CrashLogParser logParser;
    private final AiAgentService aiAgent;
    private final ModrinthApiClient apiClient;

    private record Diagnosis(String action, String target, String reason) {}
    private record ChangeRecord(int round, String action, String target, String reason) {}

    public SelfHealingEngine(DependencyEngine dependencyEngine, SandboxTesterService sandboxTester,
                             ModrinthDownloader downloader, CrashLogParser logParser,
                             AiAgentService aiAgent, ModrinthApiClient apiClient) {
        this.dependencyEngine = dependencyEngine;
        this.sandboxTester = sandboxTester;
        this.downloader = downloader;
        this.logParser = logParser;
        this.aiAgent = aiAgent;
        this.apiClient = apiClient;
    }

    // ===== 入口 =====

    public Set<String> runAutoHealingCycle(Set<String> inputCoreSlugs, String mcVersion, String loader, String userApiKey) {
        return runAutoHealingCycle(inputCoreSlugs, mcVersion, loader, "server", (p, d) -> {}, userApiKey);
    }

    public Set<String> runAutoHealingCycle(Set<String> inputCoreSlugs, String mcVersion, String loader,
                                           HealingProgressCallback callback, String userApiKey) {
        return runAutoHealingCycle(inputCoreSlugs, mcVersion, loader, "server", callback, userApiKey);
    }

    public Set<String> runAutoHealingCycle(Set<String> inputCoreSlugs, String mcVersion, String loader,
                                           String testMode, HealingProgressCallback callback, String userApiKey) {
        boolean isServerMode = !"client".equalsIgnoreCase(testMode);
        List<ChangeRecord> allChanges = new ArrayList<>();
        System.out.println(isServerMode ? "🖥️ 减法模式 · 服务端" : "🎮 减法模式 · 客户端");

        // ==========================================
        // 准备轮
        // ==========================================
        System.out.println("\n🔧 === 准备轮: 解析依赖 + 下载 ===");
        callback.onPhase("resolving", inputCoreSlugs.size());

        DependencyGraph graph = dependencyEngine.resolveFullDependenciesWithGraph(
                inputCoreSlugs, loader, mcVersion);
        Set<String> fullSlugList = graph.getOrderedSlugs();

        Set<String> skippedClient = new LinkedHashSet<>();
        if (isServerMode) {
            for (String slug : new LinkedHashSet<>(fullSlugList)) {
                try {
                    JsonNode info = apiClient.getProjectInfo(slug);
                    if (info != null && "unsupported".equalsIgnoreCase(
                            info.path("server_side").asText())) {
                        skippedClient.add(slug);
                        fullSlugList.remove(slug);
                    }
                } catch (Exception ignored) {}
            }
        }
        if (!skippedClient.isEmpty()) {
            System.out.println("🚫 排除仅客户端模组 (" + skippedClient.size() + "个)");
            callback.onPhase("summary",
                    "🚫 排除仅客户端模组: " + String.join(", ", skippedClient));
        }

        System.out.println("✅ 依赖解析完成: " + fullSlugList.size() + " 个模组");
        callback.onPhase("resolved", fullSlugList.size());

        String testId = UUID.randomUUID().toString().substring(0, 8);
        Path sandboxDir = Paths.get(System.getProperty("java.io.tmpdir"), "maa-sandbox-" + testId);
        try {
            sandboxTester.prepareSandboxEnvironment(sandboxDir);
        } catch (IOException e) {
            System.err.println("沙盒准备失败: " + e.getMessage());
            callback.onPhase("failed", fullSlugList, 0, "沙盒准备失败");
            return fullSlugList;
        }

        Path modsDir = sandboxDir.resolve("mods");
        System.out.println("📦 全量下载 " + fullSlugList.size() + " 个模组...");
        callback.onPhase("downloading", 0, fullSlugList.size());

        // 🔥 slug → jarFileName 映射 (下载时构建, 作为精确的 slug→文件 对应表)
        Map<String, String> slugToJarName = new LinkedHashMap<>();
        downloadWithRetry(fullSlugList, mcVersion, loader, modsDir, callback, slugToJarName);
        System.out.println("✅ 下载完成 (追踪 " + slugToJarName.size() + " 个文件)");

        // 构建 modid → (slug, jarFileName) 映射表
        Map<String, ModIdEntry> modidMap = buildModidMapping(modsDir, fullSlugList);
        System.out.println("📋 modid→jar 映射: " + modidMap.size() + " 个条目");
        // 打印前10条供调试
        modidMap.entrySet().stream().limit(10).forEach(e ->
                System.out.println("  " + e.getKey() + " → " + e.getValue().jarName));
        System.out.println();

        Set<String> aliveSlugs = new LinkedHashSet<>(fullSlugList);
        Set<String> previousJarSet = null;

        // ==========================================
        // 操作轮
        // ==========================================
        int maxOpRounds = 20;
        for (int round = 1; round <= maxOpRounds; round++) {
            System.out.println("\n🔪 === 操作轮 " + round + " ===");

            Set<String> currentJarSet = listJarFiles(modsDir);

            if (previousJarSet != null && previousJarSet.equals(currentJarSet)) {
                System.out.println("🛑 连续两轮 jar 文件完全相同 (" + currentJarSet.size()
                        + " 个) — 安全熔断！");
                callback.onPhase("stuck", aliveSlugs, round, "连续两轮无变化");
                printFinalReport(allChanges, round, callback);
                break;
            }
            previousJarSet = currentJarSet;

            // 🔥 关键: 在启动 MC 之前清理旧日志, 确保不会读到上一轮的崩溃报告
            clearSandboxLogs(sandboxDir);

            System.out.println("🚀 启动沙盒 (操作轮" + round + ", " + currentJarSet.size() + " 个jar)");
            callback.onPhase("testing");
            boolean isSuccess = sandboxTester.runSandboxTest(sandboxDir,
                    line -> {
                        if (line.contains("Done (") || line.contains("Error") ||
                                line.contains("FAILED") || line.contains("Exception") ||
                                line.contains("crash") || line.contains("Mod loading") ||
                                line.contains("requires ") || line.contains("Missing")) {
                            callback.onPhase("test-output", line);
                        }
                    });

            if (isSuccess) {
                System.out.println("🎉 校验通过！");
                printFinalReport(allChanges, round, callback);
                callback.onPhase("complete", aliveSlugs, skippedClient, round);
                sandboxTester.destroySandbox(sandboxDir);
                return aliveSlugs;
            }

            // 🔥 部分成功: 服务器能走到 Preparing level 但卡在创建世界 (Done 没出现)
            if (sandboxTester.isLastRunHadPreparingLevel()) {
                System.out.println("⚠️ 部分成功: 服务器能启动但无法完成世界生成 (可能出现重复日志循环)");
                System.out.println("   已进行 " + round + " 轮, 存活 " + aliveSlugs.size() + " 个模组");
                printFinalReport(allChanges, round, callback);
                callback.onPhase("complete", aliveSlugs, skippedClient, round,
                        "服务器可启动但世界生成可能卡死, 需要手动排查。"
                                + "已进行 " + round + " 轮修复, 剩余 " + aliveSlugs.size() + " 个模组。");
                sandboxTester.destroySandbox(sandboxDir);
                return aliveSlugs;
            }

            System.out.println("💥 崩溃！");

            // 🔥 预处理: 移除加载器不兼容的模组 (Fabric/Forge 在 NeoForge 上)
            // 这些是附加操作, 不计入每轮一个 REMOVE 的限制
            List<String[]> incompatible = logParser.extractIncompatibleMods(sandboxDir);
            for (String[] entry : incompatible) {
                String badFile = entry[0];
                String reason = entry[1];
                Path jarPath = modsDir.resolve(badFile);
                if (Files.exists(jarPath)) {
                    try {
                        Files.delete(jarPath);
                        String foundSlug = findByJarName(badFile, slugToJarName);
                        if (foundSlug != null) aliveSlugs.remove(foundSlug);
                        allChanges.add(new ChangeRecord(round, "REMOVE(兼容)", badFile, reason));
                        System.out.println("  🧹 已移除不兼容模组: " + badFile + " → " + reason);
                    } catch (IOException e) {
                        System.err.println("  ⚠️ 移除不兼容模组失败: " + badFile);
                    }
                }
            }

            // 🔥 五级诊断管道 — 逐级降级, 能代码处理就不走 LLM
            List<Diagnosis> diagnoses = null;

            // Level 1: crash-report "Mod loading issue for: X" (含 Mixin 检测)
            String modid = logParser.extractFirstFailingModid(sandboxDir);
            if (modid != null) {
                System.out.println("✅ Level 1 命中: " + modid);
                diagnoses = List.of(new Diagnosis("REMOVE", modid,
                        "crash-report 中第一个 Mod loading issue"));
            }

            // Level 2+3: Caused-by 链 "from mod X" (crash-report 或日志)
            if (diagnoses == null) {
                modid = logParser.extractModidFromCausedBy(sandboxDir);
                if (modid != null) {
                    System.out.println("✅ Level 2/3 命中: " + modid);
                    diagnoses = List.of(new Diagnosis("REMOVE", modid,
                            "Caused-by 链 from mod 指出"));
                }
            }

            // Level 4: 版本号匹配 — Empty pre-release 用版本号找 JAR
            if (diagnoses == null) {
                modid = logParser.extractModidByVersionString(sandboxDir, modsDir);
                if (modid != null) {
                    System.out.println("✅ Level 4 命中 (版本号匹配): " + modid);
                    diagnoses = List.of(new Diagnosis("REMOVE", modid,
                            "版本号格式异常 (Empty pre-release)"));
                    // 通知前端: 这是异常版本号模组
                    callback.onPhase("healing", round, "REMOVE(bad-version)", modid,
                            "JAR 版本号不符合 NeoForge 规范");
                }
            }

            // Level 5: LLM 兜底
            if (diagnoses == null) {
                String slimLog = logParser.extractCriticalLog(sandboxDir);
                System.out.println("📄 日志(" + slimLog.length() + "字符)");
                callback.onPhase("crash", slimLog);

                if (slimLog.length() < 50 || slimLog.equals("No log found.")) {
                    callback.onPhase("failed", aliveSlugs, round, "未提取到有效日志");
                    break;
                }

                String jarContext = buildJarContext(modsDir);

                // bootstrap 崩溃 → 给 LLM 版本号匹配引导
                String bootstrapHint = "";
                if (slimLog.contains("Empty pre-release")
                        || slimLog.contains("IllegalArgumentException")
                        || slimLog.contains("JarModuleFinder")) {
                    bootstrapHint = "⚠️ JVM 模块扫描阶段崩溃 (版本号格式异常)。\n"
                            + "请在 JAR 列表中找到【文件名含非标准版本号】的模组 (如 V1-1.21+)。\n"
                            + "提示: 看文件名中是否包含 'V' 开头 + 数字, 或 '+' 后缀的版本段。\n"
                            + "找到后 REMOVE, 找不到则 ABORT。\n\n";
                }

                String prompt = jarContext + "\n" + bootstrapHint + "--- 崩溃关键信息 ---\n" + slimLog;

                System.out.println("🤖 Level 5: Doctor Agent 诊断...");
                String diagnosisXml = aiAgent.diagnoseCrash(prompt, userApiKey);
                System.out.println("📝 诊断: " + diagnosisXml.trim());

                diagnoses = parseDiagnoses(diagnosisXml);
                if (diagnoses.isEmpty()) {
                    callback.onPhase("failed", aliveSlugs, round, "无法识别病因");
                    break;
                }
            }

            // 提取后再清一次 (保险)
            clearSandboxLogs(sandboxDir);

            // 执行删除
            int deletedThisRound = 0;
            for (Diagnosis d : diagnoses) {
                if ("ABORT".equals(d.action)) {
                    callback.onPhase("aborted", aliveSlugs, d.reason);
                    sandboxTester.destroySandbox(sandboxDir);
                    return aliveSlugs;
                }
                if (!"REMOVE".equals(d.action)) continue;

                for (String target : d.target.split("[,，]")) {
                    target = target.trim().toLowerCase();
                    if (target.isEmpty()) continue;

                    System.out.println("🔍 查找: \"" + target + "\" ...");
                    boolean deleted = false;

                    // 方式1: modidMap 精确查找 (规范化匹配)
                    String matchedModid = lookupModidInMap(target, modidMap);
                    System.out.println("  modidMap匹配: " + (matchedModid != null ? matchedModid : "无"));

                    if (matchedModid != null) {
                        ModIdEntry entry = modidMap.get(matchedModid);
                        Path jarPath = modsDir.resolve(entry.jarName);
                        if (Files.exists(jarPath)) {
                            try {
                                Files.delete(jarPath);
                                System.out.println("  ✅ 已删除! " + entry.jarName);
                                // 用 slugToJarName 精确查 slug, 回退到 entry.slug
                                String resolvedSlug = findByJarName(entry.jarName, slugToJarName);
                                if (resolvedSlug == null) resolvedSlug = entry.slug;
                                aliveSlugs.remove(resolvedSlug);
                                if (!resolvedSlug.equals(entry.slug)) aliveSlugs.remove(entry.slug);
                                modidMap.remove(matchedModid);
                                slugToJarName.remove(resolvedSlug);
                                allChanges.add(new ChangeRecord(round, "REMOVE", target, d.reason));
                                callback.onPhase("healing", round, "REMOVE", target, d.reason);
                                deletedThisRound++;
                                deleted = true;
                            } catch (Exception e) {
                                System.err.println("  ❌ 删除异常: " + e.getMessage());
                            }
                        }
                    }

                    // 方式2: slugToJarName 回退 (下载时构建的精确映射)
                    if (!deleted) {
                        String matchedSlug = findByTargetInSlugMap(target, slugToJarName);
                        if (matchedSlug != null) {
                            String jarName = slugToJarName.get(matchedSlug);
                            Path jarPath = modsDir.resolve(jarName);
                            System.out.println("  slugMap回退: " + matchedSlug + " → " + jarName
                                    + " 存在=" + Files.exists(jarPath));
                            if (Files.exists(jarPath)) {
                                try {
                                    Files.delete(jarPath);
                                    System.out.println("  ✅ 已删除! " + jarName);
                                    aliveSlugs.remove(matchedSlug);
                                    slugToJarName.remove(matchedSlug);
                                    allChanges.add(new ChangeRecord(round, "REMOVE", matchedSlug, d.reason));
                                    callback.onPhase("healing", round, "REMOVE", matchedSlug, d.reason);
                                    deletedThisRound++;
                                    deleted = true;
                                } catch (Exception e) {
                                    System.err.println("  ❌ 删除异常: " + e.getMessage());
                                }
                            }
                        }
                    }

                    // 方式3: 暴力扫描目录 (规范化文件名匹配)
                    if (!deleted) {
                        try (var files = Files.list(modsDir)) {
                            for (Path f : files.toList()) {
                                String fn = f.getFileName().toString();
                                if (!fn.endsWith(".jar")) continue;
                                String fnNorm = norm(fn);
                                String targetNorm = norm(target);
                                if (fnNorm.startsWith(targetNorm)
                                        || fnNorm.contains(targetNorm)
                                        || targetNorm.contains(fnNorm)) {
                                    System.out.println("  匹配: " + fn + " (norm)");
                                    Files.delete(f);
                                    String foundSlug = findByJarName(fn, slugToJarName);
                                    if (foundSlug == null) foundSlug = findSlugForModid(target, fullSlugList, modidMap);
                                    if (foundSlug != null) aliveSlugs.remove(foundSlug);
                                    allChanges.add(new ChangeRecord(round, "REMOVE", target, d.reason));
                                    callback.onPhase("healing", round, "REMOVE", target, d.reason);
                                    deletedThisRound++;
                                    deleted = true;
                                    break;
                                }
                            }
                        } catch (IOException e) {
                            System.err.println("  ❌ 目录扫描异常: " + e.getMessage());
                        }
                    }

                    if (!deleted) {
                        System.out.println("  ❌ 未找到匹配! 前5个jar:");
                        try (var files2 = Files.list(modsDir)) {
                            files2.filter(f -> f.toString().endsWith(".jar"))
                                    .limit(5)
                                    .forEach(f -> System.out.println("    - " + f.getFileName()));
                        } catch (Exception ignored) {}
                    }
                }
            }

            System.out.println("📊 本轮删除 " + deletedThisRound + " 个, 沙盒剩余 "
                    + listJarFiles(modsDir).size() + " 个jar, 存活slug " + aliveSlugs.size());

            if (deletedThisRound == 0) {
                // 记录本轮诊断了但未能执行的操作
                for (Diagnosis d : diagnoses) {
                    if ("REMOVE".equals(d.action)) {
                        for (String t : d.target.split("[,，]")) {
                            if (!t.trim().isEmpty()) {
                                allChanges.add(new ChangeRecord(round, "SKIP(" + d.reason + ")", t.trim(), "未找到文件"));
                            }
                        }
                    }
                }
                printFinalReport(allChanges, round, callback);
                callback.onPhase("failed", aliveSlugs, round, "本轮无有效删除");
                break;
            }
        }

        sandboxTester.destroySandbox(sandboxDir);
        System.out.println("☠️ 达到最大轮次或无法继续");
        printFinalReport(allChanges, maxOpRounds, callback);
        callback.onPhase("failed", aliveSlugs, maxOpRounds, "达到最大轮次");
        return aliveSlugs;
    }

    // ========================================
    // modid → (slug, jarFileName) 映射表
    // ========================================

    private record ModIdEntry(String modid, String slug, String jarName) {}

    /** 构建 JAR 上下文: modid ← 文件名列表, 供 Doctor Agent 选择目标 */
    private String buildJarContext(Path modsDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前沙盒 mods 文件夹中的 JAR 包列表 — 你只能从中选择移除目标！】\n");
        sb.append("格式: modid ← 文件名\n");
        try (var files = Files.list(modsDir)) {
            files.filter(f -> f.toString().endsWith(".jar"))
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .forEach(f -> {
                        String fn = f.getFileName().toString();
                        // 优先从 JAR 元数据获取真实 modid
                        String modid = extractModidFromJarMetadata(f);
                        if (modid == null || modid.isEmpty()) {
                            modid = extractModidFromFilename(fn);
                        }
                        sb.append("  ").append(modid).append(" ← ").append(fn).append("\n");
                    });
        } catch (Exception ignored) {}
        sb.append("重要: 你的 target 必须是上面列表中的 modid (冒号左边那个)！禁止编造列表中不存在的名字！");
        return sb.toString();
    }

    /** 规范化字符串: 去连字符、下划线、点号, 全小写, 用于模糊匹配 */
    private static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[-_.]", "").toLowerCase();
    }

    /** 在 slug→jarName 表中查找 target 对应的 slug (规范化比较) */
    private static String findByTargetInSlugMap(String target, Map<String, String> slugMap) {
        String n = norm(target);
        if (n.isEmpty()) return null;
        for (String slug : slugMap.keySet()) {
            String ns = norm(slug);
            if (ns.equals(n)) return slug;
        }
        for (String slug : slugMap.keySet()) {
            String ns = norm(slug);
            if (ns.contains(n) || n.contains(ns)) return slug;
        }
        return null;
    }

    /** 从 slugToJarName 反向查找: 根据 jar 文件名找到 slug */
    private static String findByJarName(String jarName, Map<String, String> slugToJarName) {
        for (var e : slugToJarName.entrySet()) {
            if (e.getValue().equalsIgnoreCase(jarName)) return e.getKey();
        }
        // 模糊匹配
        String n = norm(jarName);
        for (var e : slugToJarName.entrySet()) {
            if (norm(e.getValue()).equals(n)) return e.getKey();
        }
        return null;
    }

    /**
     * 扫描 mods 目录, 优先解析 JAR 元数据获取真实 modid, 回退到文件名推测。
     */
    private Map<String, ModIdEntry> buildModidMapping(Path modsDir, Set<String> slugs) {
        Map<String, ModIdEntry> map = new LinkedHashMap<>();
        try (var files = Files.list(modsDir)) {
            files.filter(f -> f.toString().endsWith(".jar")).forEach(jar -> {
                String jarName = jar.getFileName().toString();
                // 优先: 从 JAR 元数据解析真实 modid
                String modid = extractModidFromJarMetadata(jar);
                if (modid == null || modid.isEmpty()) {
                    // 回退: 从文件名推测
                    modid = extractModidFromFilename(jarName);
                }
                if (modid.isEmpty()) return;
                String bestSlug = matchSlug(modid, slugs);
                if (bestSlug == null) bestSlug = modid;
                map.put(modid, new ModIdEntry(modid, bestSlug, jarName));
            });
        } catch (Exception ignored) {}
        return map;
    }

    /** 🔥 从 JAR 文件元数据中解析真实 modid (NeoForge / Fabric) */
    private String extractModidFromJarMetadata(Path jarFile) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jarFile.toFile())) {
            // 1. NeoForge: META-INF/neoforge.mods.toml 或 META-INF/mods.toml
            for (String tomlPath : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                var entry = zf.getEntry(tomlPath);
                if (entry != null) {
                    String content = new String(zf.getInputStream(entry).readAllBytes());
                    Matcher m = Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"").matcher(content);
                    if (m.find()) return m.group(1).toLowerCase();
                }
            }
            // 2. Fabric: fabric.mod.json
            var fabricEntry = zf.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                String content = new String(zf.getInputStream(fabricEntry).readAllBytes());
                Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
                if (m.find()) return m.group(1).toLowerCase();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 jar 文件名推测 modid (回退方案) */
    private String extractModidFromFilename(String filename) {
        String name = filename.replace(".jar", "");
        String[] parts = name.split("[-_]");
        int end = parts.length;
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].toLowerCase();
            if (p.matches("^[0-9.+]+$") || p.matches("^mc[0-9].*") ||
                    p.equals("forge") || p.equals("fabric") || p.equals("neoforge") ||
                    p.equals("quilt") || p.matches("^v[0-9].*") ||
                    p.equals("ver") || p.equals("b") || p.equals("a") ||
                    p.equals("release") || p.equals("beta") || p.equals("alpha") ||
                    p.equals("snapshot") || p.equals("build")) {
                end = i;
            } else {
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) sb.append("-");
            sb.append(parts[i]);
        }
        return sb.toString().toLowerCase();
    }

    /** 在 slug 集合中找最匹配的 (规范化比较, 解决 somakespells ↔ somake-spells-irons-spells-addon 不匹配) */
    private String matchSlug(String modid, Set<String> slugs) {
        String n = norm(modid);
        if (n.isEmpty()) return null;
        // 精确匹配
        for (String s : slugs) {
            if (norm(s).equals(n)) return s;
        }
        // 包含匹配
        for (String s : slugs) {
            String ns = norm(s);
            if (ns.contains(n) || n.contains(ns)) return s;
        }
        // 前缀匹配 (原逻辑保留)
        for (String s : slugs) {
            if (s.startsWith(modid) || modid.startsWith(s)) return s;
        }
        return null;
    }

    /** 根据 AI 输出的 target 在映射表中查找 modid (规范化比较) */
    private String lookupModidInMap(String target, Map<String, ModIdEntry> map) {
        String n = norm(target);
        if (n.isEmpty()) return null;
        // 精确匹配
        for (String modid : map.keySet()) {
            if (norm(modid).equals(n)) return modid;
        }
        // 包含匹配
        for (String modid : map.keySet()) {
            if (norm(modid).contains(n) || n.contains(norm(modid))) return modid;
        }
        // 检查 entry.slug
        for (var e : map.entrySet()) {
            if (norm(e.getValue().slug).contains(n)) return e.getKey();
        }
        return null;
    }

    /** 从已知数据中找到 target 对应的 slug (规范化比较) */
    private String findSlugForModid(String target, Set<String> fullSlugs, Map<String, ModIdEntry> map) {
        String matchedModid = lookupModidInMap(target, map);
        if (matchedModid != null) return map.get(matchedModid).slug;
        String n = norm(target);
        for (String s : fullSlugs) {
            if (norm(s).equals(n) || norm(s).contains(n) || n.contains(norm(s))) return s;
        }
        for (String s : fullSlugs) {
            if (s.equalsIgnoreCase(target) || s.startsWith(target) || target.startsWith(s)) return s;
        }
        return null;
    }

    /** 清除沙盒日志 (启动前调用, 确保不会读到上一轮的旧崩溃报告) */
    private void clearSandboxLogs(Path sandboxDir) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                FileSystemUtils.deleteRecursively(sandboxDir.resolve("logs"));
                FileSystemUtils.deleteRecursively(sandboxDir.resolve("crash-reports"));
                Files.deleteIfExists(sandboxDir.resolve("mc-output.log"));

                // 验证是否真的删干净了
                boolean logsGone = !Files.exists(sandboxDir.resolve("logs"));
                boolean crashGone = !Files.exists(sandboxDir.resolve("crash-reports"));
                boolean outputGone = !Files.exists(sandboxDir.resolve("mc-output.log"));
                if (logsGone && crashGone && outputGone) return; // 成功

                if (attempt < 5) {
                    System.out.println("  ⏳ 日志清理第" + attempt + "次未完全成功, 重试...");
                    Thread.sleep(800);
                }
            } catch (Exception e) {
                if (attempt >= 5) {
                    System.err.println("⚠️ 清理旧日志失败(已重试5次): " + e.getMessage());
                }
            }
        }
    }

    private Set<String> listJarFiles(Path modsDir) {
        try (var files = Files.list(modsDir)) {
            return files.filter(f -> f.toString().endsWith(".jar"))
                    .map(f -> f.getFileName().toString())
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            return Set.of();
        }
    }

    // ========================================
    // 下载 (带重试)
    // ========================================

    private void downloadWithRetry(Set<String> slugs, String mcVersion, String loader,
                                    Path modsDir, HealingProgressCallback callback,
                                    Map<String, String> slugToJarName) {
        int maxRetries = 3;
        Set<String> remaining = new LinkedHashSet<>(slugs);
        for (int attempt = 0; attempt < maxRetries && !remaining.isEmpty(); attempt++) {
            if (attempt > 0) {
                System.out.println("🔄 重试下载 " + remaining.size() + " 个, 第" + (attempt + 1) + "次");
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
            downloader.downloadModsToSandbox(remaining, mcVersion, loader, modsDir,
                    (done, all, slug) -> {
                        System.out.println("  ⬇ [" + done + "/" + all + "] " + slug
                                + (done == all ? " ✅" : ""));
                        callback.onPhase("downloading", done, all, slug);
                    });

            Set<String> failed = new LinkedHashSet<>();
            for (String slug : remaining) {
                try {
                    JsonNode proj = apiClient.getProjectInfo(slug);
                    if (proj == null) { failed.add(slug); continue; }
                    JsonNode ver = apiClient.getLatestVersion(proj.path("id").asText(),
                            mcVersion, "[\"" + loader.toLowerCase() + "\"]");
                    if (ver == null || !ver.has("files")) { failed.add(slug); continue; }
                    String fn = ver.path("files").get(0).path("filename").asText();
                    Path target = modsDir.resolve(fn);
                    if (!Files.exists(target)) { failed.add(slug); continue; }
                    if (!ModrinthDownloader.isValidZip(target)) {
                        Files.delete(target); failed.add(slug);
                    } else {
                        slugToJarName.put(slug, fn); // 🔥 记录 slug → 文件名
                    }
                } catch (Exception e) { failed.add(slug); }
            }
            if (failed.isEmpty()) break;
            remaining = failed;
        }
    }

    // ========================================
    // XML 解析
    // ========================================

    private List<Diagnosis> parseDiagnoses(String xml) {
        List<String> actions = extractAllTags(xml, "action");
        List<String> targets = extractAllTags(xml, "target");
        List<String> reasons = extractAllTags(xml, "reason");
        List<Diagnosis> results = new ArrayList<>();
        int count = Math.min(actions.size(), Math.min(targets.size(), reasons.size()));
        for (int i = 0; i < Math.max(count, 1); i++) {
            String a = i < actions.size() ? actions.get(i) : "";
            String t = i < targets.size() ? targets.get(i) : "";
            String r = i < reasons.size() ? reasons.get(i) : "";
            if (!a.isEmpty() && !t.isEmpty()) results.add(new Diagnosis(a.trim(), t.trim(), r.trim()));
        }
        return results;
    }

    private List<String> extractAllTags(String xml, String tag) {
        List<String> r = new ArrayList<>();
        Matcher m = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL).matcher(xml);
        while (m.find()) r.add(m.group(1).trim());
        return r;
    }

    private void printFinalReport(List<ChangeRecord> changes, int rounds, HealingProgressCallback cb) {
        if (changes.isEmpty()) { System.out.println("\n📊 报告: 未移除任何模组"); return; }
        StringBuilder sb = new StringBuilder();
        sb.append("\n📊 ====== 减法自愈报告 (").append(rounds).append("轮, ")
                .append(changes.size()).append("个操作) ======\n");
        var grouped = changes.stream()
                .collect(Collectors.groupingBy(c -> c.round, LinkedHashMap::new, Collectors.toList()));
        for (var entry : grouped.entrySet()) {
            sb.append("--- 第").append(entry.getKey()).append("轮 ---\n");
            for (ChangeRecord cr : entry.getValue()) {
                sb.append("  🔪 ").append(cr.target).append(" — ").append(cr.reason).append("\n");
            }
        }
        sb.append("📊 ===============================================\n");
        System.out.println(sb);
        cb.onPhase("report", sb.toString());
    }
}
