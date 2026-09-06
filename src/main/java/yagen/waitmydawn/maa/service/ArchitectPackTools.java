package yagen.waitmydawn.maa.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import tools.jackson.databind.JsonNode;
import yagen.waitmydawn.maa.cache.ModrinthCacheService;
import yagen.waitmydawn.maa.logging.MaaLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Architect 的 P0 五件套 Tool（M2）。
 *
 * <p>原则：LLM 只下发意图，Java 负责"校验 + 改状态"。
 * slug 必须真实存在（本地缓存优先，缺失走 live projectInfo），
 * 未知 slug 返回错误信息让 LLM 向用户澄清，绝不静默失败。
 */
public class ArchitectPackTools {

    /** 中文黑话词典（Java 权威，Tool 参数统一先翻译；提示词副本保留作引导） */
    private static final Map<String, String> ALIASES = Map.of(
            "铁魔法", "irons-spells-n-spellbooks",
            "灾变", "l_enders-cataclysm",
            "冰火传说社区版", "iceandfire-ce",
            "地牢浮现之时", "when-dungeons-arise",
            "农夫乐事", "farmers-delight",
            "机械动力", "create",
            "rs存储", "refined-storage",
            "应用能源2", "ae2",
            "ae2", "ae2");

    private final PackSessionState state;
    private final ModrinthCacheService cache;
    private final ModrinthApiClient apiClient;
    private int callCount = 0;

    public ArchitectPackTools(PackSessionState state, ModrinthCacheService cache,
                              ModrinthApiClient apiClient) {
        this.state = state;
        this.cache = cache;
        this.apiClient = apiClient;
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
        if (!removed.isEmpty()) {
            state.addOp("removed:" + String.join(",", removed));
        }
        if (!unknown.isEmpty()) {
            return "已移除: " + removed + "；以下名称不存在或无法解析，请向用户确认后重试: " + unknown;
        }
        return removed.isEmpty() ? "没有可移除的模组。" : "已移除: " + removed;
    }

    @Tool("调整包内类别目标配比。参数为 \"类别:增量\" 列表，如 [\"magic:+10\", \"technology:-5\"]。类别必须是 19 类之一。")
    public String adjustCategoryTargets(
            @P("类别与增量列表，格式 category:+N 或 category:-N") List<String> adjustments) {
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
            String category = parts[0].trim().toLowerCase(Locale.ROOT);
            if (!ModrinthCacheService.CATEGORIES.contains(category)) {
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
        if (!applied.isEmpty()) {
            state.addOp("adjusted:" + String.join(",", applied));
        }
        if (!invalid.isEmpty()) {
            return "已调整: " + applied + "；无效参数: " + invalid + "（类别需为 19 类之一）";
        }
        return applied.isEmpty() ? "没有任何有效调整。" : "已调整: " + applied;
    }

    @Tool("设定整合包目标模组总数（范围 5~500）。")
    public String setTargetCount(@P("目标模组总数") int count) {
        String denied = guard("setTargetCount", count);
        if (denied != null) return denied;
        if (count < 5 || count > 500) return "数量需在 5~500 之间，请与用户确认。";
        state.setTargetCount(count);
        state.addOp("target_count:" + count);
        return "已将目标模组总数设为 " + count;
    }

    @Tool("设定模组热度上限（max_downloads）。默认 2100000000；用户要求冷门时可用 500000。")
    public String setMaxDownloads(@P("下载量上限，如 500000 或 2100000000") long threshold) {
        String denied = guard("setMaxDownloads", threshold);
        if (denied != null) return denied;
        if (threshold < 0) return "阈值不能为负数。";
        state.setMaxDownloads(threshold);
        state.addOp("max_downloads:" + threshold);
        return "已设定下载量上限为 " + threshold;
    }

    @Tool("设定整合包名称（长度不超过 60）。")
    public String setPackName(@P("整合包名称") String name) {
        String denied = guard("setPackName", name);
        if (denied != null) return denied;
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 60) return "名称需为 1~60 个字符。";
        state.setPackName(n);
        state.addOp("name:" + n);
        return "已将包名设为 " + n;
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

    private static String normalizeSlug(String raw) {
        if (raw == null) return "";
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(t, t);
    }

    /** 单回合工具调用上限 + 审计日志 */
    private String guard(String tool, Object args) {
        synchronized (this) {
            callCount++;
        }
        System.out.println("🛠️ [Tool:" + tool + "] args=" + args + " (第 " + callCount + " 次调用)");
        MaaLog.user("Tool 调用 #" + callCount + " " + tool + " args=" + args);
        if (callCount > 12) {
            return "本回合工具调用已达上限，请把剩余需求一次性汇总后再次请求。";
        }
        return null;
    }
}
