package yagen.waitmydawn.maa.model;

import java.util.*;

/** 单轮推荐召回的资源界限，不限制用户点名的模组与最终依赖解析。 */
public final class RetrievalBudget {
    public static final int MAX_CORES = 8, MAX_SEARCHES = 42, MAX_VERSIONS = 300;
    private final Set<String> cores = new LinkedHashSet<>(), searches = new LinkedHashSet<>(), versions = new LinkedHashSet<>();
    private final Set<String> notes = new LinkedHashSet<>();
    private int unknown;
    private final int versionLimit;
    private int reservedSearches, reservedVersions;
    private boolean repairPhase;
    private final Set<String> repairSearches = new LinkedHashSet<>(), repairVersions = new LinkedHashSet<>();
    public RetrievalBudget() { this.versionLimit = MAX_VERSIONS; }
    public RetrievalBudget(int targetFiles) { this.versionLimit = Math.min(600, Math.max(MAX_VERSIONS, targetFiles * 3)); }
    private synchronized boolean reserve(Set<String> used, String key, int cap, String note) {
        if (used.contains(key)) return true;
        if (used.size() >= cap) { notes.add(note); return false; }
        used.add(key); return true;
    }
    public boolean core(String slug) { return reserve(cores, slug, MAX_CORES, "附属核心召回达到单轮 8 个上限，剩余核心可分轮扩展"); }
    public synchronized void reserveForRepair() { reservedSearches = 4; reservedVersions = 12; }
    public synchronized void beginRepair() { repairPhase = true; }
    public synchronized boolean search(String query) {
        if (repairPhase && !reserve(repairSearches, query, 4, "定向补缺检索达到4项上限")) return false;
        return reserve(searches, query, MAX_SEARCHES - (repairPhase ? 0 : reservedSearches), "推荐检索达到本轮可用查询上限，剩余关键词未执行");
    }
    public synchronized boolean version(String slug) {
        if (repairPhase && !reserve(repairVersions, slug, 12, "定向补缺候选核实达到12项上限")) return false;
        return reserve(versions, slug, versionLimit - (repairPhase ? 0 : reservedVersions), "推荐候选校验达到本轮可用上限，剩余候选未核实");
    }
    public synchronized void unknown() { unknown++; }
    public synchronized List<String> diagnostics() {
        var result = new ArrayList<>(notes);
        if (unknown > 0) result.add("推荐召回中有 " + unknown + " 项因取数失败未核实；不能据此判断项目不存在或不兼容");
        return result;
    }
    public synchronized Map<String, Integer> snapshot() {
        return Map.of("addonCores", cores.size(), "searchQueries", searches.size(), "candidateVersions", versions.size(), "unverified", unknown, "versionLimit", versionLimit,
                "repairSearchQueries", repairSearches.size(), "repairCandidateVersions", repairVersions.size());
    }
}
