package yagen.waitmydawn.maa.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import tools.jackson.databind.JsonNode;
import yagen.waitmydawn.maa.cache.ModrinthCacheService;
import yagen.waitmydawn.maa.logging.MaaLog;
import yagen.waitmydawn.maa.model.CategoryRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Architect 的 P0 五件套 Tool（M2）。
 *
 * <p>原则：LLM 只下发意图，Java 负责"校验 + 改状态"。
 * slug 必须真实存在（本地缓存优先，缺失走 live projectInfo），
 * 未知 slug 返回错误信息让 LLM 向用户澄清，绝不静默失败。
 */
public class ArchitectPackTools {

    private final PackSessionState state;
    private final ModrinthCacheService cache;
    private final ModrinthApiClient apiClient;
    /** 别名词典（唯一权威源见 ModAliasRegistry，Tool 参数统一先翻译） */
    private final ModAliasRegistry aliasRegistry;
    private int callCount = 0;

    public ArchitectPackTools(PackSessionState state, ModrinthCacheService cache,
                              ModrinthApiClient apiClient, ModAliasRegistry aliasRegistry) {
        this.state = state;
        this.cache = cache;
        this.apiClient = apiClient;
        this.aliasRegistry = aliasRegistry;
    }

    @Tool("从当前模组清单中移除指定的模组（按 slug）。必须真实存在的模组才能移除；找不到时向用户确认。")
    public String removeMods(
            @P("要移除的模组 slug 列表，例如: [\"create\", \"ae2\"]") List<String> slugs) {
        String denied = guard("removeMods", slugs);
        if (denied != null) return denied;
        if (slugs == null || slugs.isEmpty()) return "没有收到要移除的 slug。";
        List<String> removed = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String raw : slugs) {
            String slug = normalizeSlug(raw);
            if (!exists(slug)) {
                unknown.add(raw.trim());
                continue;
            }
            if (state.removeSlug(slug)) {
                removed.add(slug);
            }
        }
        if (!unknown.isEmpty()) {
            return "已移除: " + removed + "；以下名称不存在或无法解析，请向用户确认后重试: " + unknown;
        }
        return removed.isEmpty() ? "没有可移除的模组。" : "已移除: " + removed;
    }

    @Tool("调整包内类别目标配比。参数为 \"类别:增量\" 列表，如 [\"magic:+10\", \"technology:-5\"]。类别必须是 19 类之一。")
    public String adjustCategoryTargets(
            @P("类别与增量列表，格式 category:+N 或 category:-N") List<String> adjustments) {
        // ⚠️ 说真话（P3-7）：这个 Tool 只是把权重**记入本轮配额**，配额要落到候选上还需要该类别
        // 出现在 <search_intents> 里（配额决定"最多采纳几个"，关键词决定"能捞到什么"）。
        // 旧实现返回"已调整"却没有消费者，模型会以为改好了、不再补关键词 → 用户的调整被静默丢弃。
        String denied = guard("adjustCategoryTargets", adjustments);
        if (denied != null) return denied;
        if (adjustments == null || adjustments.isEmpty()) return "没有收到类别调整。";
        List<String> applied = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String raw : adjustments) {
            String[] parts = raw.split(":");
            if (parts.length != 2) {
                invalid.add(raw);
                continue;
            }
            // 统一走类别权威源：规范名直接通过，已知别名（tech/combat/...）归一化后通过，其余判非法
            String category = CategoryRegistry.normalize(parts[0]);
            if (category == null) {
                invalid.add(raw);
                continue;
            }
            try {
                int delta = Integer.parseInt(parts[1].trim());
                state.putCategoryTarget(category, delta);
                applied.add(category + (delta >= 0 ? "+" : "") + delta);
            } catch (NumberFormatException e) {
                invalid.add(raw);
            }
        }
        String hint = "已记录并将在本轮配额分配时并入。注意：配额只决定每类最多采纳几个，"
                + "还需在 <search_intents> 里带上该类别与关键词才能真正召回到候选模组"
                + "（若缺失，Java 会用该类别的权威推荐检索词兜底）。";
        if (!invalid.isEmpty()) {
            return "已记录: " + applied + "；无效参数: " + invalid + "（类别需为 19 类之一）。" + hint;
        }
        return applied.isEmpty() ? "没有任何有效调整。" : "已记录: " + applied + "。" + hint;
    }

    @Tool("设定整合包目标模组总数（范围 5~500）。")
    public String setTargetCount(@P("目标模组总数") int count) {
        String denied = guard("setTargetCount", count);
        if (denied != null) return denied;
        if (count < 5 || count > 500) return "数量需在 5~500 之间，请与用户确认。";
        state.setTargetCount(count);
        return "已将目标模组总数设为 " + count;
    }

    @Tool("设定模组热度上限（max_downloads）。默认 2100000000；用户要求冷门时可用 500000。")
    public String setMaxDownloads(@P("下载量上限，如 500000 或 2100000000") long threshold) {
        String denied = guard("setMaxDownloads", threshold);
        if (denied != null) return denied;
        if (threshold < 0) return "阈值不能为负数。";
        state.setMaxDownloads(threshold);
        return "已设定下载量上限为 " + threshold;
    }

    @Tool("设定整合包名称（长度不超过 60）。")
    public String setPackName(@P("整合包名称") String name) {
        String denied = guard("setPackName", name);
        if (denied != null) return denied;
        String raw = name == null ? "" : name.trim();
        String n = sanitizePackName(raw);
        if (n.isEmpty() || n.length() > 60) return "名称需为 1~60 个字符（且不能只由特殊字符组成）。";
        state.setPackName(n);
        // 说真话：清理过就告诉模型实际存的是什么，避免它以为原名生效了
        return n.equals(raw) ? "已将包名设为 " + n
                : "已将包名设为 " + n + "（原名称里的特殊字符已清理）";
    }

    /**
     * 包名净化：把会在下游出问题的字符挡在源头，而不是让它们一路传到正则替换 / XML / 渲染层。
     *
     * <p>拉黑三类：
     * <ul>
     *   <li>{@code $} 和 {@code \}：{@code Matcher.replaceAll} 里它们是分组引用/转义字符，
     *       包名含它们会让回填 {@code <name>} 时抛 {@code IllegalArgumentException}，整轮回复退化；</li>
     *   <li>{@code < > & " ' `}：XML 标签边界、实体符与引号/代码围栏，避免把 {@code <name>} 撕坏；</li>
     *   <li>控制字符与行分隔符：包名是单行标题，换行只会把响应切碎。</li>
     * </ul>
     * 净化后折叠连续空白。返回空串表示"只剩特殊字符"，由调用方按名称非法拒绝，不会静默存空包名。
     */
    static String sanitizePackName(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // 换行/制表/行分隔符按"空格"处理（"机械动力\n附属包" 该变成两个词，不是粘成一个）
            if (c == '\n' || c == '\r' || c == '\t' || c == 0x0B || c == 0x0C
                    || c == '\u2028' || c == '\u2029') {
                out.append(' ');
                continue;
            }
            // 其余控制字符直接丢弃
            if (c < 0x20 || c == 0x7F) continue;
            if ("$\\<>\"'`&".indexOf(c) >= 0) continue;
            out.append(c);
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** 词典 + 规范化：小写/去空格；本地缓存或 live 任一存在即视为真实 */
    private boolean exists(String slug) {
        if (slug == null || slug.isEmpty()) return false;
        if (cache.isAvailable() && cache.find(slug) != null) return true;
        try {
            JsonNode info = apiClient.getProjectInfo(slug);
            return info != null && info.has("id");
        } catch (Exception e) {
            return false;
        }
    }

    private String normalizeSlug(String raw) {
        return aliasRegistry.resolve(raw);
    }

    /** 单回合工具调用上限 + 审计日志 */
    private String guard(String tool, Object args) {
        synchronized (this) {
            callCount++;
        }
        // 过程指标：把真实调用次数回写到会话状态，供 <trace> 上报（评测要看"调了几次、有没有空转"）
        state.setToolCalls(callCount);
        System.out.println("🛠️ [Tool:" + tool + "] args=" + args + " (第 " + callCount + " 次调用)");
        MaaLog.user("Tool 调用 #" + callCount + " " + tool + " args=" + args);
        if (callCount > 12) {
            return "本回合工具调用已达上限，请把剩余需求一次性汇总后再次请求。";
        }
        return null;
    }
}
