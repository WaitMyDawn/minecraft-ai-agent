package yagen.waitmydawn.maa.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单回合内的包状态可变副本（M2）。
 *
 * <p>状态权威仍在前端（packData 全量回传），本对象只在一次 /api/chat 请求内存在，
 * 承载 Architect 通过 Tool 下发的变更：删除的 slug、目标数量、热度上限、包名、类别目标。
 * 回合结束后这些变更由 ChatController 应用到 initialMods / 蓝图参数，
 * 并以 ops_summary 随响应返回。
 */
public class PackSessionState {

    private final Set<String> removedSlugs = new LinkedHashSet<>();
    private final Map<String, Integer> categoryTargets = new LinkedHashMap<>();
    private final List<String> ops = new ArrayList<>();

    private Integer targetCount;
    private Long maxDownloads;
    private String packName;

    public boolean removeSlug(String slug) {
        return removedSlugs.add(slug);
    }

    public Set<String> getRemovedSlugs() {
        return removedSlugs;
    }

    public void putCategoryTarget(String category, int delta) {
        categoryTargets.merge(category, delta, Integer::sum);
    }

    public Map<String, Integer> getCategoryTargets() {
        return categoryTargets;
    }

    public void addOp(String op) {
        ops.add(op);
    }

    public List<String> getOps() {
        return ops;
    }

    public boolean hasOps() {
        return !ops.isEmpty();
    }

    public Integer getTargetCount() {
        return targetCount;
    }

    public void setTargetCount(Integer targetCount) {
        this.targetCount = targetCount;
    }

    public Long getMaxDownloads() {
        return maxDownloads;
    }

    public void setMaxDownloads(Long maxDownloads) {
        this.maxDownloads = maxDownloads;
    }

    public String getPackName() {
        return packName;
    }

    public void setPackName(String packName) {
        this.packName = packName;
    }
}
