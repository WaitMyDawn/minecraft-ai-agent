package yagen.waitmydawn.maa.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 依赖解析结果（P6-A）：把"解析完了"和"解析干净了"区分开。
 *
 * <p>背景：旧实现只返回一个 {@link DependencyGraph}，前置查不到时静默跳过，
 * 但边已经加进图里——于是返回 {@code {a}} 却带着 {@code a→b} 这条指向空气的边，
 * 调用方无法判断结果是否完整。
 *
 * <p>本对象额外承载：
 * <ul>
 *   <li>{@link #unresolved}：哪些前置没解析出来、是谁要的、为什么</li>
 *   <li>{@link #dropped}：哪些模组被剔除、原因（缺前置连带、loader 不匹配、互斥…）</li>
 *   <li>{@link #status}：COMPLETE / INCOMPLETE（后者表示有未解析项或触顶）</li>
 * </ul>
 */
public class ResolutionResult {

    public enum Status {
        /** 所有必需的依赖都解析成功 */
        COMPLETE,
        /** 有未解析项、有剔除、或触达预算上限 */
        INCOMPLETE
    }

    public enum Reason {
        /** 前置项目在 Modrinth 查不到（被作者删除/隐藏） */
        PROJECT_NOT_FOUND,
        /** 前置项目存在，但在当前 mc/loader 下没有任何版本 */
        NO_VERSION_FOR_ENV,
        /** 只有其它加载器的版本（严格 loader 策略下不接受） */
        LOADER_MISMATCH,
        /** 查询失败（网络/限流），无法确定——不剔除，仅标记 */
        UPSTREAM_UNAVAILABLE,
        /** 精确版本要求无法在当前环境满足 */
        VERSION_CONFLICT,
        /** 因为它依赖的模组被剔除而连带剔除 */
        DEPENDENT_OF_DROPPED,
        /** 与其它模组互斥，被剔除 */
        CONFLICT_WITH_KEPT,
        /** 两个根模组互斥，无法自动裁决，需要用户决定 */
        CONFLICT_BETWEEN_ROOTS,
        /** 触达节点/请求预算，未继续解析 */
        BUDGET_EXCEEDED
    }

    /** 未解析的依赖：谁要的（requiredBy）→ 要谁（slug）→ 为什么没成（reason） */
    public record Unresolved(String slug, String requiredBy, Reason reason, String detail) {
    }

    /** 被剔除的模组：为什么被剔除 */
    public record Dropped(String slug, Reason reason, String detail) {
    }

    private final DependencyGraph graph;
    private final Status status;
    private final Set<String> roots;
    private final List<Unresolved> unresolved = new ArrayList<>();
    private final List<Dropped> dropped = new ArrayList<>();
    private final int nodeBudget;
    private final boolean budgetExhausted;

    public ResolutionResult(DependencyGraph graph, Status status, Set<String> roots,
                            List<Unresolved> unresolved, List<Dropped> dropped,
                            int nodeBudget, boolean budgetExhausted) {
        this.graph = graph;
        this.status = status;
        this.roots = new LinkedHashSet<>(roots);
        this.unresolved.addAll(unresolved);
        this.dropped.addAll(dropped);
        this.nodeBudget = nodeBudget;
        this.budgetExhausted = budgetExhausted;
    }

    public DependencyGraph graph() {
        return graph;
    }

    public Status status() {
        return status;
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    public Set<String> roots() {
        return roots;
    }

    public List<Unresolved> unresolved() {
        return unresolved;
    }

    public List<Dropped> dropped() {
        return dropped;
    }

    public int nodeBudget() {
        return nodeBudget;
    }

    public boolean budgetExhausted() {
        return budgetExhausted;
    }

    public Set<String> orderedSlugs() {
        return graph.getOrderedSlugs();
    }

    /** 未解析项按原因归类，便于上报与断言 */
    public Map<Reason, List<Unresolved>> unresolvedByReason() {
        Map<Reason, List<Unresolved>> map = new LinkedHashMap<>();
        for (Unresolved u : unresolved) {
            map.computeIfAbsent(u.reason(), k -> new ArrayList<>()).add(u);
        }
        return map;
    }

    /** 面向用户/日志的一行摘要 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(status == Status.COMPLETE ? "完整" : "不完整")
                .append("：模组 ").append(graph.allSlugs.size())
                .append(" 个，未解析 ").append(unresolved.size())
                .append(" 项，剔除 ").append(dropped.size()).append(" 项");
        if (budgetExhausted) sb.append("，⚠️ 触达节点预算 ").append(nodeBudget);
        return sb.toString();
    }
}
