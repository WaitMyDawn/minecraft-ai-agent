package yagen.waitmydawn.maa.model.agent;

import java.util.*;

/** 将已校验的意图映射为本轮执行参数，不向旧 XML 解析器回写业务字段。 */
public record BuildExecutionPlan(BuildIntent intent, Set<String> original, Set<String> blocked) {
    public BuildExecutionPlan {
        original = Collections.unmodifiableSet(new LinkedHashSet<>(original));
        var exclusions = new LinkedHashSet<>(blocked);
        exclusions.addAll(intent.exclude());
        exclusions.addAll(intent.remove());
        blocked = Collections.unmodifiableSet(exclusions);
        if (intent.mustHave().stream().anyMatch(blocked::contains)
                || intent.add().stream().anyMatch(blocked::contains)) {
            throw new IllegalArgumentException("计划与现有排除项冲突");
        }
    }

    public Set<String> initial() {
        var result = new LinkedHashSet<String>();
        if (intent.mode() == BuildIntent.Mode.EDIT) result.addAll(original);
        result.removeAll(blocked);
        result.addAll(intent.mustHave());
        result.addAll(intent.add());
        return result;
    }

    public int targetCount(int initialCount) {
        if (intent.count() != null) {
            return intent.count().scope() == BuildIntent.CountScope.ADDED_ROOTS
                    ? retainedOriginal().size() + intent.count().value() : intent.count().value();
        }
        // 无数量的编辑只提供小规模推荐；纯删除、改名或明确新增不会启动补量。
        return initialCount + (intent.searchIntents().isEmpty() ? 0 : 10);
    }

    public int rootBudget(int initialCount, double expansionFactor) {
        int needed = Math.max(0, targetCount(initialCount) - initialCount);
        if (intent.count() == null || intent.count().scope() != BuildIntent.CountScope.TOTAL_FILES) return needed;
        // 小规模组合的膨胀比不稳定，保持既有的小需求不折算策略。
        return needed <= 10 ? needed : Math.max(1, (int) Math.round(needed / Math.max(1, expansionFactor)));
    }

    private Set<String> retainedOriginal() {
        var retained = new LinkedHashSet<>(original);
        retained.removeAll(blocked);
        return retained;
    }

    public List<String> diagnostics(Set<String> roots, Set<String> files) {
        return diagnostics(roots, files, false);
    }
    public List<String> diagnostics(Set<String> roots, Set<String> files, boolean rootsKnown) {
        return diagnostics(roots, files, rootsKnown, Map.of());
    }
    public List<String> diagnostics(Set<String> roots, Set<String> files, boolean rootsKnown, Map<String, String> identities) {
        var notes = new ArrayList<String>();
        var missing = intent.mustHave().stream().filter(s -> !files.contains(identities.getOrDefault(s, s))).toList();
        if (!missing.isEmpty()) notes.add("未满足必选模组：" + String.join("、", missing) + "，不能视为完整满足需求。");
        if (intent.count() != null && intent.count().value() > 0) {
            int actual = actualCount(roots, files);
            boolean missed = !intent.count().accepts(actual);
            if (missed) notes.add("数量约束 " + intent.count().scope() + "：目标 "
                    + intent.count().targetLabel() + "，实际 " + actual
                    + (intent.count().precision() == BuildIntent.Precision.EXACT ? "；严格数量尚未满足。" : "。"));
            if (!rootsKnown && intent.mode() == BuildIntent.Mode.EDIT && intent.count().scope() == BuildIntent.CountScope.ROOT_MODS)
                notes.add("当前清单未区分历史根模组与自动前置，根模组数量按本轮提交给解析引擎的项目统计，不能作为精确根数量保证。");
        }
        if (intent.replacementPolicy() == BuildIntent.ReplacementPolicy.COMPATIBLE_PORTS)
            notes.add("移植关系尚无可靠证据校验；本轮不会仅凭名称相似自动替换为社区移植版。");
        if (!intent.excludedCategories().isEmpty())
            notes.add("类别排除已用于本轮推荐候选筛选；现有清单、点名模组及自动前置未按类别自动剔除，需要结合最终清单复核。");
        return notes;
    }

    public int actualCount(Set<String> roots, Set<String> files) {
        if (intent.count() == null) return files.size();
        return switch (intent.count().scope()) {
            case TOTAL_FILES -> files.size();
            case ROOT_MODS -> (int) roots.stream().filter(files::contains).count();
            case ADDED_ROOTS -> (int) roots.stream().filter(files::contains).filter(s -> !original.contains(s)).count();
        };
    }

    /** 保留前端历史消息的标签协议；模型文本不能注入清单、环境或 trace 标签。 */
    public static String display(BuildIntent intent) {
        return escape(intent.reply()) + (intent.name().isEmpty() ? "" : "\n<name>" + escape(intent.name()) + "</name>")
                + (intent.environment() == null ? "" : "\n<mc>" + escape(intent.environment().mcVersion())
                + "</mc>\n<loader>" + intent.environment().loader().name().toLowerCase(Locale.ROOT) + "</loader>");
    }

    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
