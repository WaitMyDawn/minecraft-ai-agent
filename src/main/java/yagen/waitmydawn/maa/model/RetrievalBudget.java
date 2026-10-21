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
    private boolean relationPhase;
    private boolean complementaryPhase;
    private int complementarySearchReserve, complementaryVersionReserve;
    private final Set<String> complementarySearches = new LinkedHashSet<>(), complementaryVersions = new LinkedHashSet<>();
    private int relationSearchReserve, relationVersionReserve;
    private final Set<String> relationSearches=new LinkedHashSet<>(), relationVersions=new LinkedHashSet<>();
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
    public synchronized void beginRepair() { repairPhase = true; relationPhase = false; }
    public synchronized void reserveForRelations() { relationSearchReserve=6; relationVersionReserve=24; }
    public synchronized void beginRelations() { repairPhase=false; relationPhase=true; }
    /** 互补召回后置，但不能被明确需求的过采样耗尽全部剩余资源。 */
    public synchronized void reserveForComplementary(int slots) {
        if (slots > 0) { complementarySearchReserve = 3; complementaryVersionReserve = Math.min(64, slots * 2); }
    }
    public synchronized void beginComplementary() { complementaryPhase = true; }
    public synchronized void endComplementary() {
        complementaryPhase = false; complementarySearchReserve = 0; complementaryVersionReserve = 0;
    }
    public synchronized boolean search(String query) {
        if (repairPhase && !reserve(repairSearches, query, 4, "定向补缺检索达到4项上限")) return false;
        if (relationPhase && !reserve(relationSearches,query,6,"包内联动检索达到6项上限")) return false;
        if (complementaryPhase && !reserve(complementarySearches,query,3,"互补召回检索达到3项上限")) return false;
        return reserve(searches, query, MAX_SEARCHES - (repairPhase || relationPhase ? 0 : reservedSearches) - (relationPhase ? 0 : relationSearchReserve)
                - (complementaryPhase ? 0 : complementarySearchReserve), "推荐检索达到本轮可用查询上限，剩余关键词未执行");
    }
    public synchronized boolean version(String slug) {
        if (repairPhase && !reserve(repairVersions, slug, 12, "定向补缺候选核实达到12项上限")) return false;
        if (relationPhase && !reserve(relationVersions,slug,24,"包内联动候选核实达到24项上限")) return false;
        if (complementaryPhase && !reserve(complementaryVersions,slug,64,"互补召回候选核实达到64项上限")) return false;
        return reserve(versions, slug, versionLimit - (repairPhase || relationPhase ? 0 : reservedVersions) - (relationPhase ? 0 : relationVersionReserve)
                - (complementaryPhase ? 0 : complementaryVersionReserve), "推荐候选校验达到本轮可用上限，剩余候选未核实");
    }
    public synchronized void unknown() { unknown++; }
    public synchronized List<String> diagnostics() {
        var result = new ArrayList<>(notes);
        if (unknown > 0) result.add("推荐召回中有 " + unknown + " 项因取数失败未核实；不能据此判断项目不存在或不兼容");
        return result;
    }
    public synchronized Map<String, Integer> snapshot() {
        return Map.of("addonCores", cores.size(), "searchQueries", searches.size(), "candidateVersions", versions.size(), "unverified", unknown, "versionLimit", versionLimit,
                "repairSearchQueries", repairSearches.size(), "repairCandidateVersions", repairVersions.size(),
                "relationSearchQueries",relationSearches.size(),"relationCandidateVersions",relationVersions.size());
    }
}
