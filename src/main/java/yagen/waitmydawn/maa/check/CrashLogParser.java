package yagen.waitmydawn.maa.check;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 崩溃日志智能提取器
 * <p>
 * 职责: 从 crash-reports / latest.log / mc-output.log 中提取诊断关键信息。
 * 优先级: crash-reports > logs/latest.log > mc-output.log
 */
@Component
public class CrashLogParser {

    // 在日志中应该被排除的非致命错误行关键词
    private static final String[] SKIP_KEYWORDS = {
            "RecipeManager", "TagLoader", "NoSuchFileException",
            "LootDataType", "AdvancementTree", "BlockAttachedEntity",
            "ReloadableServerRegistries", "detected.*registered with",
            "is not a registered slot type", "VersionChecker",
            "Configuration file.*is not correct", "Incorrect key",
            "ConfigTracker", "Not a valid resource location"
    };

    /**
     * Level 1: 从 crash-report 确定性提取第一个失败的 modid。
     * 如果第一个 Mod loading issue 是由其他模组的 Mixin 引发的,
     * 则返回 Mixin 所属的模组 (真正的根因), 而非被 Mixin 修改的模组。
     *
     * @return 第一个失败的 modid, 如果没有 crash-report 则返回 null
     */
    public String extractFirstFailingModid(Path sandboxDir) {
        try {
            Path crashReportDir = sandboxDir.resolve("crash-reports");
            if (!Files.exists(crashReportDir)) return null;

            List<Path> crashes;
            try (Stream<Path> stream = Files.list(crashReportDir)) {
                crashes = new ArrayList<>(stream.toList());
            }
            if (crashes.isEmpty()) return null;

            crashes.sort(Comparator.comparingLong(
                    (Path a) -> a.toFile().lastModified()).reversed());
            String content = Files.readString(crashes.get(0));

            // 1. 找第一个 "Mod loading issue for: <modid>"
            Matcher issueMatcher = Pattern.compile("Mod loading issue for:\\s*(\\S+)")
                    .matcher(content);
            if (!issueMatcher.find()) return null;

            String firstIssueModid = issueMatcher.group(1).replaceAll("[-_]$", "").toLowerCase();

            // 2. 检查这个 Mod loading issue 是否由 MixinApplyError 引起
            //    格式: Mixin [xxx from mod <modid>] ... FAILED during APPLY
            Matcher mixinMatcher = Pattern.compile(
                    "Mixin\\s*\\[[^\\]]*from\\s+mod\\s+(\\S+)\\]",
                    Pattern.CASE_INSENSITIVE).matcher(content);
            if (mixinMatcher.find()) {
                String mixinSourceModid = mixinMatcher.group(1).replaceAll("[-_]$", "").toLowerCase();
                if (!mixinSourceModid.equals(firstIssueModid)) {
                    System.out.println("🎯 确定性提取: Mixin [" + mixinMatcher.group()
                            + "] 来自 " + mixinSourceModid + " (非被修改的 " + firstIssueModid + ")");
                    return mixinSourceModid;
                }
            }

            // 3. 检查 Caused-by 链中的 Mixin 错误
            //    格式: Mixin [...] from mod <modid> FAILED during APPLY
            Matcher causedByMixinMatcher = Pattern.compile(
                    "Mixin\\s+\\[[^\\]]+\\]\\s+from\\s+mod\\s+(\\S+)\\s+FAILED\\s+during\\s+APPLY",
                    Pattern.CASE_INSENSITIVE).matcher(content);
            while (causedByMixinMatcher.find()) {
                String mixinSource = causedByMixinMatcher.group(1).replaceAll("[-_]$", "").toLowerCase();
                if (!mixinSource.equals(firstIssueModid)) {
                    System.out.println("🎯 确定性提取: Mixin FAILED from " + mixinSource
                            + " (根源, 非被修改的 " + firstIssueModid + ")");
                    return mixinSource;
                }
            }

            // 4. 🆕 检查 MixinPreProcessorException，提取根源 modid
            //    格式: Attach error for xxx.mixins.json:XXX from mod <modid>
            //    或者: MixinPreProcessorException: Attach error for ... from mod <modid>
            Matcher preProcessorMatcher = Pattern.compile(
                    "(?i)(?:MixinPreProcessorException|Attach error).*?from\\s+mod\\s+(\\S+)",
                    Pattern.DOTALL).matcher(content);

            String bestMatch = null;
            int bestPosition = Integer.MAX_VALUE;

            while (preProcessorMatcher.find()) {
                String culpritModid = preProcessorMatcher.group(1).replaceAll("[-_]$", "").toLowerCase();
                int position = preProcessorMatcher.start();
                // 选择最早出现的（最接近错误根源）
                if (position < bestPosition) {
                    bestPosition = position;
                    bestMatch = culpritModid;
                }
            }

            if (bestMatch != null && !bestMatch.equals(firstIssueModid)) {
                System.out.println("🎯 确定性提取: MixinPreProcessorException 根源模组 = " + bestMatch
                        + " (而非表面失败的 " + firstIssueModid + ")");
                return bestMatch;
            }

            // 5. 没有 Mixin 干扰, 按原逻辑返回第一个 Mod loading issue 的 modid
            System.out.println("🎯 确定性提取: 首个失败模组 = " + firstIssueModid);
            return firstIssueModid;

        } catch (Exception e) {
            System.err.println("⚠️ 确定性提取失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 🔥 从 crash-report 中提取加载器不兼容的模组 (Fabric/Forge 模组在 NeoForge 上)。
     * 这些模组可以直接移除, 不计入每轮一个的限制。
     *
     * @return List of [filename, reason] pairs, 没有则返回空列表
     */
    public List<String[]> extractIncompatibleMods(Path sandboxDir) {
        List<String[]> result = new ArrayList<>();
        try {
            Path crashDir = sandboxDir.resolve("crash-reports");
            if (!Files.exists(crashDir)) return result;

            List<Path> crashes;
            try (Stream<Path> s = Files.list(crashDir)) { crashes = new ArrayList<>(s.toList()); }
            if (crashes.isEmpty()) return result;
            crashes.sort(Comparator.comparingLong((Path a) -> a.toFile().lastModified()).reversed());

            String content = Files.readString(crashes.get(0));

            // 直接匹配 "Failure message: File .../mods/<filename>.jar is ... and cannot be loaded"
            // 实际格式: Failure message: File D:\...\mods\xxx.jar is a Fabric mod and cannot be loaded
            //           Failure message: File ...\mods\xxx.jar is for Minecraft Forge..., and cannot be loaded
            Pattern p = Pattern.compile(
                    "Failure message:\\s*File\\s+[^\\n]*?[/\\\\]mods[/\\\\]([^/\\s]+\\.jar)\\s+is\\s+(?:for\\s+)?(.+?)(?:,\\s*)?and cannot be loaded",
                    Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(content);
            while (m.find()) {
                String filename = m.group(1);
                String reasonRaw = m.group(2).trim();
                String reason;
                if (reasonRaw.toLowerCase().contains("fabric")) {
                    reason = "Fabric 模组, 无法在 NeoForge 加载";
                } else if (reasonRaw.toLowerCase().contains("forge") || reasonRaw.toLowerCase().contains("older")) {
                    reason = "Forge/旧版 NeoForge 模组, 与当前加载器不兼容";
                } else {
                    reason = "加载器不兼容: " + reasonRaw;
                }
                result.add(new String[]{filename, reason});
                System.out.println("🧹 不兼容模组: " + filename + " → " + reason);
            }
        } catch (Exception e) {
            System.err.println("⚠️ 不兼容模组提取异常: " + e.getMessage());
        }
        return result;
    }

    /**
     * 🔥 Level 2+3: 从 Caused-by 链提取第一个明确的模组名。
     * 搜索 "Caused by ... from mod <X>" 模式。
     * 优先级: crash-reports > latest.log > mc-output.log
     *
     * @return 第一个被 Caused-by 指出的 modid, 没有则返回 null
     */
    public String extractModidFromCausedBy(Path sandboxDir) {
        try {
            // 1. crash-report
            Path crashDir = sandboxDir.resolve("crash-reports");
            if (Files.exists(crashDir)) {
                List<Path> crashes;
                try (Stream<Path> s = Files.list(crashDir)) { crashes = new ArrayList<>(s.toList()); }
                if (!crashes.isEmpty()) {
                    crashes.sort(Comparator.comparingLong((Path a) -> a.toFile().lastModified()).reversed());
                    String modid = extractFirstCausedByModid(Files.readString(crashes.get(0)));
                    if (modid != null) return modid;
                }
            }
            // 2. latest.log
            Path latestLog = sandboxDir.resolve("logs/latest.log");
            if (Files.exists(latestLog)) {
                String modid = extractFirstCausedByModid(Files.readString(latestLog));
                if (modid != null) return modid;
            }
            // 3. mc-output.log
            Path mcOut = sandboxDir.resolve("mc-output.log");
            if (Files.exists(mcOut)) {
                String modid = extractFirstCausedByModid(Files.readString(mcOut));
                if (modid != null) return modid;
            }
        } catch (Exception e) { /* fallthrough */ }
        return null;
    }

    /**
     * 🔥 Level 4: 从 Empty pre-release 崩溃中提取版本号, 匹配 JAR 文件名找到问题模组。
     * 搜索日志中的版本号字符串 (如 1-V1-1.21+), 与 modsDir 中的 JAR 文件名比对。
     *
     * @return 匹配到的 modid, 没找到则返回 null
     */
    public String extractModidByVersionString(Path sandboxDir, Path modsDir) {
        try {
            // 1. 从日志文件中提取版本号字符串
            String versionStr = null;
            for (Path logFile : new Path[]{
                    sandboxDir.resolve("mc-output.log"),
                    sandboxDir.resolve("logs").resolve("latest.log")
            }) {
                if (!Files.exists(logFile)) continue;
                String content = Files.readString(logFile);
                Matcher vm = Pattern.compile(
                        "IllegalArgumentException:\\s*([^:\\s]+):\\s*Empty pre-release"
                ).matcher(content);
                if (vm.find()) {
                    versionStr = vm.group(1).trim();
                    break;
                }
            }
            if (versionStr == null) return null;

            System.out.println("🔍 版本号匹配: 错误版本 = " + versionStr);

            // 2. 构建版本号的搜索片段列表: [完整字符串, 缩短版(保留含V/.的部分)]
            List<String> searchTokens = new ArrayList<>();
            searchTokens.add(versionStr); // 完整字符串

            // 缩短: 按 - 分割, 保留含 V/v 或 . 的片段
            String[] parts = versionStr.split("-");
            List<String> versionParts = new ArrayList<>();
            for (String p : parts) {
                if (p.matches(".*[vV].*") || p.contains(".")) {
                    versionParts.add(p);
                }
            }
            if (!versionParts.isEmpty()) {
                searchTokens.add(String.join("-", versionParts)); // V1-1.21+
            }

            // 3. 扫描 JAR 文件, 按匹配度排序: 完整匹配 > 部分匹配
            String bestMatch = null;
            int bestScore = 0;

            try (var files = Files.list(modsDir)) {
                for (Path jar : files.toList()) {
                    String fn = jar.getFileName().toString();
                    if (!fn.endsWith(".jar")) continue;
                    String fnLower = fn.toLowerCase();

                    for (String token : searchTokens) {
                        String tokenLower = token.toLowerCase();
                        int score = 0;
                        if (fnLower.contains(tokenLower)) {
                            score = token.length() * 10; // 完整包含, 分数高
                        } else {
                            // 检查 token 的每个片段是否都在文件名中
                            boolean allPartsFound = true;
                            for (String part : tokenLower.split("-")) {
                                if (part.isEmpty()) continue;
                                if (!fnLower.contains(part)) {
                                    allPartsFound = false;
                                    break;
                                }
                            }
                            if (allPartsFound) score = token.length() * 3;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            bestMatch = fn;
                        }
                    }
                }
            }

            if (bestMatch != null) {
                String modid = extractModid(bestMatch);
                System.out.println("🎯 版本号匹配: " + versionStr + " → " + bestMatch + " (modid=" + modid + ")");
                return modid;
            }
        } catch (Exception e) {
            System.err.println("⚠️ 版本号匹配异常: " + e.getMessage());
        }
        return null;
    }

    /** 从文件名提取 modid (与 SelfHealingEngine.extractModid 逻辑一致, 简单版) */
    private static String extractModid(String filename) {
        String name = filename.replace(".jar", "");
        String[] parts = name.split("[-_]");
        int end = parts.length;
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].toLowerCase();
            if (p.matches("^[0-9.+]+$") || p.matches("^mc[0-9].*") ||
                    p.matches("^(forge|fabric|neoforge|quilt)$") || p.matches("^v[0-9].*") ||
                    p.matches("^(ver|b|a|release|beta|alpha|snapshot|build)$")) {
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

    /** 在文本中搜索第一条含 from mod 的 Caused by: */
    private String extractFirstCausedByModid(String text) {
        // 模式: "from mod <modid>" 出现在 Caused by 或 Mixin 上下文中
        // 例如: Mixin [... from mod tensura_iron_spells] FAILED during APPLY
        // 例如: Attach error for xxx.mixins.json:MixinAbstractSpell from mod tensura_iron_spells during activity
        Pattern causedByPattern = Pattern.compile(
                "Caused by:[\\s\\S]*?(?=\\nCaused by:|\\Z)",
                Pattern.CASE_INSENSITIVE);

        Matcher causedByMatcher = causedByPattern.matcher(text);

        while (causedByMatcher.find()) {

            String block = causedByMatcher.group();

            Matcher modMatcher = Pattern.compile(
                            "from\\s+mod\\s+(\\S+)",
                            Pattern.CASE_INSENSITIVE)
                    .matcher(block);

            if (modMatcher.find()) {

                String modid = modMatcher.group(1)
                        .replaceAll("[\\]\\[,;:]$", "")
                        .toLowerCase();

                System.out.println(
                        "🎯 第一条含 from mod 的 Caused by: " + modid);

                return modid;
            }
        }
        return null;
    }

    /**
     * 提取崩溃日志核心诊断信息 (LLM 兜底用 — Level 5)。
     * 只提取 crash-report 的 Mod loading issue 摘要 / latest.log 的第一个致命错误段。
     */
    public String extractCriticalLog(Path sandboxDir) {
        try {
            Path crashReportDir = sandboxDir.resolve("crash-reports");

            // 1. crash-report: 只提取 Mod loading issue 摘要段 (最精准)
            if (Files.exists(crashReportDir)) {
                List<Path> crashes;
                try (Stream<Path> stream = Files.list(crashReportDir)) {
                    crashes = new ArrayList<>(stream.toList());
                }
                if (!crashes.isEmpty()) {
                    crashes.sort(Comparator.comparingLong(
                            (Path a) -> a.toFile().lastModified()).reversed());
                    String content = Files.readString(crashes.get(0));
                    String extracted = extractModIssuesFromCrashReport(content);
                    if (!extracted.isEmpty()) return extracted;
                }
            }

            // 2. latest.log: 只提取第一个 [main/ERROR] 段
            Path latestLog = sandboxDir.resolve("logs/latest.log");
            if (Files.exists(latestLog)) {
                String extracted = extractFirstErrorBlock(Files.readString(latestLog));
                if (!extracted.isEmpty()) return extracted;
            }

            // 3. mc-output.log
            Path mcOutput = sandboxDir.resolve("mc-output.log");
            if (Files.exists(mcOutput)) {
                String extracted = extractFirstErrorBlock(Files.readString(mcOutput));
                if (!extracted.isEmpty()) return extracted;
            }
        } catch (Exception e) {
            return "Failed to read log: " + e.getMessage();
        }
        return "No log found.";
    }

    /**
     * 从日志中提取第一个致命错误段 (Level 4 LLM 兜底用)。
     * 优先提取含 modid 的诊断行, 跳过纯栈帧和噪音。
     */
    private String extractFirstErrorBlock(String logContent) {
        String[] lines = logContent.split("\n");

        // 找第一个错误起始行
        int startLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("[main/ERROR]") || line.contains("[main/FATAL]")
                    || line.contains("Failed to start the minecraft server")
                    || line.contains("net.neoforged.fml.ModLoadingException")
                    || line.contains("Exception in thread")
                    || line.contains("Caused by:")) {
                startLine = i;
                break;
            }
        }
        if (startLine < 0) {
            int s = Math.max(0, lines.length - 30);
            return "Log tail:\n" + String.join("\n",
                    Arrays.copyOfRange(lines, s, lines.length));
        }

        // 提取: 优先 modid 行 + 错误行, 最大 1500 字符
        StringBuilder sb = new StringBuilder();
        int chars = 0;
        for (int i = startLine; i < lines.length && chars < 1500; i++) {
            String line = lines[i];
            // 跳过噪音
            boolean skip = false;
            for (String sk : SKIP_KEYWORDS) {
                if (line.contains(sk)) { skip = true; break; }
            }
            if (skip) continue;
            // 跳过纯栈帧 (at xxx)
            if (line.matches("^\\s+at\\s+.+")) continue;
            // 跳过 "X more" 截断行
            if (line.matches("^\\s+\\.\\.\\.\\s*\\d+\\s+more.*")) continue;
            // 跳过纯数字时间戳行
            if (line.matches("^\\[[0-9:]+\\]\\s*$")) continue;
            // 跳过空行
            if (line.isBlank()) continue;

            sb.append(line).append("\n");
            chars += line.length() + 1;
        }
        return sb.toString().trim();
    }

    /**
     * 从 crash-report 提取 Mod loading issue 摘要 (Level 4 兜底)。
     * 含 Description + Mod loading issue 块 + Caused-by 摘要。
     */
    private String extractModIssuesFromCrashReport(String content) {
        StringBuilder sb = new StringBuilder();

        // Description
        Matcher descMatcher = Pattern.compile("Description:\\s*(.+)").matcher(content);
        if (descMatcher.find()) {
            sb.append("Description: ").append(descMatcher.group(1).trim()).append("\n\n");
        }

        // Mod loading issue for: X 块 (去栈帧, 去空行)
        Pattern issueBlock = Pattern.compile(
                "-- Mod loading issue (?:for: (\\S+))?.*?\\n" +
                "(?:.*?Failure message:[^\\n]*\\n)?" +
                "(?:.*?Exception message:[^\\n]*\\n)?",
                Pattern.DOTALL);
        Matcher im = issueBlock.matcher(content);
        int count = 0;
        while (im.find() && count < 8) {
            String block = im.group().trim();
            block = block.replaceAll("\\n\\s+at\\s+[^\\n]+", "");
            block = block.replaceAll("\\n{3,}", "\n\n");
            if (!block.isBlank()) {
                sb.append(block).append("\n\n");
                count++;
            }
        }

        // Loading errors encountered 汇总
        Matcher le = Pattern.compile(
                "Loading errors encountered:([^\\n]*(?:\\n\\s*-\\s+[^\\n]+){0,10})").matcher(content);
        if (le.find()) {
            sb.append(le.group().trim()).append("\n");
        }

        // 如果没有 Mod loading issue 块, 提取 Caused-by 行 (不含栈帧)
        if (count == 0) {
            Matcher cb = Pattern.compile("Caused by:\\s*([^\\n]+)").matcher(content);
            int cbCount = 0;
            while (cb.find() && cbCount < 5) {
                sb.append("Caused by: ").append(cb.group(1).trim()).append("\n");
                cbCount++;
            }
        }

        return sb.toString().trim();
    }
}
