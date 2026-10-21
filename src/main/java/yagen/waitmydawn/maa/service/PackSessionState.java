package yagen.waitmydawn.maa.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 单回合内的包状态可变副本（M2）。
 *
 * <p>状态权威仍在前端（packData 全量回传），本对象只在一次 /api/chat 请求内存在，
 * 承载结构化计划执行器及历史 Tool 路径产生的变更：删除项、数量、热度、包名与类别目标。
 * 回合结束后这些变更由 ChatController 应用到 initialMods / 蓝图参数，
 * 并以 {@code <ops_summary>}（结构化 JSON）随响应返回，供前端渲染"本轮变更"卡片。
 *
 * <p><b>为什么是 JSON 而不是人话</b>：摘要必须反映"系统真的改了什么"，
 * 所以它由这里的字段直接生成（单一事实来源），而不是由模型复述——
 * 模型漏说一次，用户就会以为什么都没发生。
 */
public class PackSessionState {
    private Map<String,Integer> familiarity = Map.of();
    private double preferenceInfluence;
    private final Map<String,yagen.waitmydawn.maa.model.RemainingBudgetRanking.Report> remainingRanking = new LinkedHashMap<>();
    public Map<String,Integer> getFamiliarity() { return familiarity; }
    public void setFamiliarity(Map<String,Integer> value) { familiarity = Map.copyOf(value); }
    public double getPreferenceInfluence() { return preferenceInfluence; }
    public void setPreferenceInfluence(double value) { preferenceInfluence = value; }
    public Map<String,yagen.waitmydawn.maa.model.RemainingBudgetRanking.Report> getRemainingRanking() { return remainingRanking; }
    private yagen.waitmydawn.maa.model.SelectionCoverage selectionCoverage;
    public yagen.waitmydawn.maa.model.SelectionCoverage getSelectionCoverage() { return selectionCoverage; }
    public void setSelectionCoverage(yagen.waitmydawn.maa.model.SelectionCoverage value) { selectionCoverage = value; }
    private boolean addonSelectionEnabled;
    private Set<String> recommendedAddonRoots = Set.of();
    public boolean isAddonSelectionEnabled() { return addonSelectionEnabled; }
    public void setAddonSelectionEnabled(boolean value) { addonSelectionEnabled = value; }
    public Set<String> getRecommendedAddonRoots() { return recommendedAddonRoots; }
    public void setRecommendedAddonRoots(Set<String> value) { recommendedAddonRoots = Set.copyOf(value); }
    private yagen.waitmydawn.maa.model.RetrievalBudget retrievalBudget;
    private Map<String, java.util.List<String>> confirmedGeneration = Map.of();
    private Map<String, String> generationFacts = Map.of();
    public yagen.waitmydawn.maa.model.RetrievalBudget getRetrievalBudget() { return retrievalBudget; }
    public void setRetrievalBudget(yagen.waitmydawn.maa.model.RetrievalBudget value) { retrievalBudget = value; }
    public Map<String, java.util.List<String>> getConfirmedGeneration() { return confirmedGeneration; }
    private Map<String, java.util.List<String>> offeredGeneration = Map.of();
    public Map<String, java.util.List<String>> getOfferedGeneration() { return offeredGeneration; }
    public void setOfferedGeneration(java.util.List<yagen.waitmydawn.maa.model.ContentOverlap.Group> groups) {
        var offered = new java.util.LinkedHashMap<String, java.util.List<String>>();
        groups.forEach(g -> offered.put(g.original(), g.candidates().stream().map(yagen.waitmydawn.maa.model.ContentOverlap.Candidate::slug).toList()));
        offeredGeneration = Map.copyOf(offered);
    }
    public void setConfirmedGeneration(Map<String, java.util.List<String>> value) { confirmedGeneration = Map.copyOf(value); }
    public Map<String, String> getGenerationFacts() { return generationFacts; }
    public void setGenerationFacts(Map<String, String> value) { generationFacts = Map.copyOf(value); }
    private yagen.waitmydawn.maa.model.AssemblySnapshot finishedAssembly;
    private yagen.waitmydawn.maa.model.DependencyClosure finishedClosure;
    public yagen.waitmydawn.maa.model.AssemblySnapshot getFinishedAssembly() { return finishedAssembly; }
    public void setFinishedAssembly(yagen.waitmydawn.maa.model.AssemblySnapshot value) { finishedAssembly = value; }
    public yagen.waitmydawn.maa.model.DependencyClosure getFinishedClosure() { return finishedClosure; }
    public void setFinishedClosure(yagen.waitmydawn.maa.model.DependencyClosure value) { finishedClosure = value; }

    private yagen.waitmydawn.maa.model.AssemblySnapshot assembly;
    public yagen.waitmydawn.maa.model.AssemblySnapshot getAssembly() { return assembly; }
    public void setAssembly(yagen.waitmydawn.maa.model.AssemblySnapshot assembly) { this.assembly = assembly; }
    private yagen.waitmydawn.maa.model.RequiredModsReport requiredMods;
    public yagen.waitmydawn.maa.model.RequiredModsReport getRequiredMods() { return requiredMods; }
    public void setRequiredMods(yagen.waitmydawn.maa.model.RequiredModsReport report) { requiredMods = report; }
    private java.util.List<yagen.waitmydawn.maa.model.agent.AgentContractCodec.Failure> plannerFailures = java.util.List.of();
    public java.util.List<yagen.waitmydawn.maa.model.agent.AgentContractCodec.Failure> getPlannerFailures() { return plannerFailures; }
    public void setPlannerFailures(java.util.List<yagen.waitmydawn.maa.model.agent.AgentContractCodec.Failure> failures) { plannerFailures = java.util.List.copyOf(failures); }
    private yagen.waitmydawn.maa.model.ImportedPack importedPack;
    public yagen.waitmydawn.maa.model.ImportedPack getImportedPack() { return importedPack; }
    public void setImportedPack(yagen.waitmydawn.maa.model.ImportedPack importedPack) { this.importedPack = importedPack; }

    private final Set<String> removedSlugs = new LinkedHashSet<>();
    private final Map<String, Integer> categoryTargets = new LinkedHashMap<>();

    private Integer targetCount;
    private Long maxDownloads;
    private String packName;

    /** setEnvironment 工具切换后的环境（null = 本轮没切换）；由 ChatController 读取并压过模型回填 */
    private String envMc;
    private String envLoader;
    /** 本回合 Tool 被调用的次数（过程指标：评测要看的"走了几步、有没有空转"） */
    private int toolCalls;

    public int getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(int toolCalls) {
        this.toolCalls = toolCalls;
    }

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

    /** 本回合是否有可展示的变更；无变更时不返回 ops_summary。 */
    public boolean hasChanges() {
        return !removedSlugs.isEmpty() || !categoryTargets.isEmpty()
                || targetCount != null || maxDownloads != null
                || (packName != null && !packName.isBlank())
                || (envChange != null && !envChange.isBlank());
    }

    /**
     * 本回合变更的结构化摘要（JSON）。
     *
     * <p>只输出"确实发生"的字段：Tool 校验失败时不会写状态，所以卡片不会虚报。
     * 手写而非走 Jackson，是沿用 {@code buildTrace} 的做法——这里只有一层扁平结构，
     * 且 {@link #jsonEscape} 已覆盖包名可能带的引号/换行。
     */
    public String toOpsJson() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"removed\":[");
        int i = 0;
        for (String slug : removedSlugs) {
            if (i++ > 0) sb.append(',');
            sb.append('"').append(jsonEscape(slug)).append('"');
        }
        sb.append("],\"categoryTargets\":{");
        int j = 0;
        for (Map.Entry<String, Integer> e : categoryTargets.entrySet()) {
            if (j++ > 0) sb.append(',');
            sb.append('"').append(jsonEscape(e.getKey())).append("\":").append(e.getValue());
        }
        sb.append('}');
        if (targetCount != null) sb.append(",\"targetCount\":").append(targetCount);
        if (maxDownloads != null) sb.append(",\"maxDownloads\":").append(maxDownloads);
        if (packName != null && !packName.isBlank()) {
            sb.append(",\"name\":\"").append(jsonEscape(packName)).append('"');
        }
        if (envChange != null && !envChange.isBlank()) {
            sb.append(",\"env\":\"").append(jsonEscape(envChange)).append('"');
        }
        return sb.append('}').toString();
    }

    /** 最小 JSON 字符串转义：引号、反斜杠、控制字符 */
    private static String jsonEscape(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
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

    /** setEnvironment 工具切到的 MC 版本（null = 本轮没切换环境） */
    public String getEnvMc() {
        return envMc;
    }

    /** setEnvironment 工具切到的加载器（null = 沿用当前加载器） */
    public String getEnvLoader() {
        return envLoader;
    }

    /** 环境切换的展示文本，例如 "1.21.1 → 26.2"（只在真的切换时写入，供"本轮变更"卡片用） */
    private String envChange;

    /**
     * 记录"本轮发生了环境切换"（用户看得到的那种变更：卡片里一行 `环境：旧 → 新`）。
     *
     * <p>为什么单独存一份文本，而不在渲染时用 envMc/envLoader 拼：卡片要显示**从哪个环境切过来的**，
     * 而"旧环境"只存在于请求里，state 自己不知道。切换判定发生在 ChatController，那里两个值都有。
     */
    public void setEnvChange(String text) {
        this.envChange = text;
    }

    public String getEnvChange() {
        return envChange;
    }

    public void setEnv(String mcVersion, String loader) {
        this.envMc = mcVersion;
        this.envLoader = loader;
    }
}
