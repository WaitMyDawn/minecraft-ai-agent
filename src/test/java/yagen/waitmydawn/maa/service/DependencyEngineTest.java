package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.KnowledgeRule;
import yagen.waitmydawn.maa.model.ResolutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 依赖引擎的离线评测（评审方案 §7.1 的「Java 执行层」）。
 *
 * <p>为什么必须有这批测试：P6 要修的边界（前置被删、前置无本环境版本、只有 version_id 的依赖、
 * 互斥、深链、预算触顶）**在真实 Modrinth 数据上碰不到**，靠黄金测试集无法验证，
 * 只能用固定元数据（stub 掉 ModrinthApiClient）来断言。
 */
class DependencyEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MC = "1.21.1";
    private static final String NEOFORGE = "neoforge";

    private ModrinthApiClient api;
    private KnowledgeDb knowledgeDb;
    private DependencyEngine engine;

    @BeforeEach
    void setUp() {
        api = mock(ModrinthApiClient.class);
        knowledgeDb = mock(KnowledgeDb.class);
        when(knowledgeDb.getActiveRules(anyString())).thenReturn(List.of());
        engine = new DependencyEngine(api, knowledgeDb);
    }

    // ===== fixtures =====

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode projectJson(String id, String slug) {
        return json("{\"id\":\"" + id + "\",\"slug\":\"" + slug + "\"}");
    }

    /** 构造一个版本对象；deps 形如 "required:PROJECT_ID" 或 "required-version:VERSION_ID" */
    private static JsonNode versionJson(String versionId, List<String> deps) {
        StringBuilder depArr = new StringBuilder();
        for (String d : deps) {
            if (!depArr.isEmpty()) depArr.append(',');
            String[] parts = d.split(":", 2);
            String type = parts[0].startsWith("required") ? "required"
                    : parts[0].startsWith("incompatible") ? "incompatible" : parts[0];
            String byVersion = parts[0].endsWith("-version") ? "1" : "0";
            String pid = "1".equals(byVersion) ? "null" : "\"" + parts[1] + "\"";
            String vid = "1".equals(byVersion) ? "\"" + parts[1] + "\"" : "null";
            depArr.append("{\"project_id\":").append(pid)
                    .append(",\"version_id\":").append(vid)
                    .append(",\"dependency_type\":\"").append(type).append("\"}");
        }
        return json("{\"id\":\"" + versionId + "\",\"project_id\":\"P-" + versionId + "\","
                + "\"version_number\":\"" + versionId + "\","
                + "\"game_versions\":[\"" + MC + "\"],\"loaders\":[\"" + NEOFORGE + "\"],"
                + "\"files\":[{\"filename\":\"" + versionId + ".jar\",\"url\":\"http://x\","
                + "\"hashes\":{\"sha1\":\"a\",\"sha512\":\"b\"},\"size\":1}],"
                + "\"dependencies\":[" + depArr + "]}");
    }

    /** 注册一个"存在且在当前环境有版本"的模组 */
    private void register(String slug, String projectId, List<String> deps) {
        when(api.getProjectInfo(slug)).thenReturn(projectJson(projectId, slug));
        when(api.getProjectInfo(projectId)).thenReturn(projectJson(projectId, slug));
        when(api.getLatestVersion(eq(projectId), eq(MC), anyString()))
                .thenReturn(versionJson(slug + "-v1", deps));
    }

    private ResolutionResult resolve(String... slugs) {
        return engine.resolveWithReport(Set.of(slugs), NEOFORGE, MC);
    }

    // ===== 用例 =====

    @Test
    @DisplayName("正常链：a 的必需前置 b 会被解析进来，且结果完整")
    void resolvesRequiredChain() {
        register("a", "PA", List.of("required:PB"));
        register("b", "PB", List.of());

        ResolutionResult r = resolve("a");

        assertTrue(r.orderedSlugs().containsAll(Set.of("a", "b")), "应包含 a 与 b，实际=" + r.orderedSlugs());
        assertTrue(r.unresolved().isEmpty(), "不应有未解析项，实际=" + r.unresolved());
        assertTrue(r.isComplete(), "应为 COMPLETE");
    }

    @Test
    @DisplayName("前置被删（404）：非核心的依赖方被剔除，且不留下悬空边")
    void missingPrerequisiteDropsNonRootDependent() {
        // root 依赖 a，a 依赖已消失的 b → a 必须被剔除；root 是用户点名的，按规则 1 保留并上报
        register("root", "PR", List.of("required:PA"));
        register("a", "PA", List.of("required:PB"));
        when(api.getProjectInfo("PB")).thenReturn(null);

        ResolutionResult r = resolve("root");

        assertTrue(r.orderedSlugs().contains("root"), "用户点名的 root 按规则 1 应保留");
        assertFalse(r.orderedSlugs().contains("a"), "缺前置的非核心依赖方 a 应被剔除，实际=" + r.orderedSlugs());
        assertFalse(r.orderedSlugs().contains("PB"), "缺失的前置不该出现在结果里");
        assertTrue(r.graph().getDependenciesOf("root").isEmpty(), "不应留下指向空气的边");
        assertTrue(r.dropped().stream().anyMatch(d -> d.slug().equals("a")
                        && d.reason() == ResolutionResult.Reason.DEPENDENT_OF_DROPPED),
                "应记录 a 因缺前置被连带剔除，实际=" + r.dropped());
        assertTrue(r.unresolved().stream().anyMatch(u -> u.reason() == ResolutionResult.Reason.PROJECT_NOT_FOUND),
                "应记录 PROJECT_NOT_FOUND，实际=" + r.unresolved());
        assertFalse(r.isComplete(), "应为 INCOMPLETE");
    }

    @Test
    @DisplayName("规则 1：用户点名的核心模组缺前置时不静默剔除，而是保留并上报")
    void rootWithMissingPrerequisiteIsKeptAndReported() {
        register("core", "PC", List.of("required:PB"));
        when(api.getProjectInfo("PB")).thenReturn(null);

        ResolutionResult r = resolve("core");

        assertTrue(r.orderedSlugs().contains("core"), "用户点名的核心不该被自动剔除");
        assertTrue(r.unresolved().stream().anyMatch(u -> "core".equals(u.requiredBy())
                        && u.reason() == ResolutionResult.Reason.PROJECT_NOT_FOUND),
                "必须留下'core 需要 PB 但拿不到'的诊断，实际=" + r.unresolved());
        assertFalse(r.isComplete(), "有未满足的必需依赖 → INCOMPLETE");
    }

    @Test
    @DisplayName("前置存在但当前环境无版本：非核心依赖方被剔除并区分原因")
    void prerequisiteWithoutVersionForEnv() {
        register("root", "PR", List.of("required:PA"));
        register("a", "PA", List.of("required:PB"));
        when(api.getProjectInfo("b")).thenReturn(projectJson("PB", "b"));
        when(api.getProjectInfo("PB")).thenReturn(projectJson("PB", "b"));
        when(api.getLatestVersion(eq("PB"), eq(MC), anyString())).thenReturn(null);

        ResolutionResult r = resolve("root");

        assertFalse(r.orderedSlugs().contains("a"), "缺前置的 a 应被剔除");
        assertTrue(r.unresolved().stream().anyMatch(u -> u.reason() == ResolutionResult.Reason.NO_VERSION_FOR_ENV),
                "应记录 NO_VERSION_FOR_ENV，实际=" + r.unresolved());
    }

    @Test
    @DisplayName("前置查询失败（网络/限流）：不剔除依赖方，只标记 UPSTREAM_UNAVAILABLE")
    void upstreamUnavailableKeepsDependent() {
        register("a", "PA", List.of("required:PB"));
        when(api.getProjectInfo("PB")).thenThrow(new ModrinthApiClient.UnavailableException("boom"));

        ResolutionResult r = resolve("a");

        assertTrue(r.orderedSlugs().contains("a"), "查不通不该等同于不存在，a 应保留");
        assertTrue(r.unresolved().stream().anyMatch(u -> u.reason() == ResolutionResult.Reason.UPSTREAM_UNAVAILABLE),
                "应记录 UPSTREAM_UNAVAILABLE，实际=" + r.unresolved());
        assertFalse(r.isComplete(), "有未确定项时不能自称完整");
    }

    @Test
    @DisplayName("只给 version_id 的依赖：必须能解析出来（旧实现整条跳过）")
    void resolvesVersionIdDependency() {
        when(api.getProjectInfo("a")).thenReturn(projectJson("PA", "a"));
        when(api.getLatestVersion(eq("PA"), eq(MC), anyString()))
                .thenReturn(versionJson("a-v1", List.of("required-version:V9")));
        when(api.getProjectInfo("PB")).thenReturn(projectJson("PB", "b"));
        when(api.getVersionById("V9")).thenReturn(json("{\"id\":\"V9\",\"project_id\":\"PB\","
                + "\"game_versions\":[\"" + MC + "\"],\"loaders\":[\"" + NEOFORGE + "\"],"
                + "\"dependencies\":[]}"));
        when(api.getLatestVersion(eq("PB"), eq(MC), anyString())).thenReturn(versionJson("b-v1", List.of()));

        ResolutionResult r = resolve("a");

        assertTrue(r.orderedSlugs().containsAll(Set.of("a", "b")),
                "version_id 型前置必须被解析，实际=" + r.orderedSlugs());
    }

    @Test
    @DisplayName("严格 loader：只有别的加载器有版本时剔除，且不做跨加载器回退")
    void crossLoaderIsStrict() {
        when(api.getProjectInfo("a")).thenReturn(projectJson("PA", "a"));
        when(api.getLatestVersion(eq("PA"), eq(MC), anyString())).thenReturn(null);

        ResolutionResult r = resolve("a");

        assertFalse(r.orderedSlugs().contains("a"), "neoforge 环境不应接受只有 forge 版的模组");
        assertTrue(r.unresolved().stream().anyMatch(u ->
                        u.reason() == ResolutionResult.Reason.NO_VERSION_FOR_ENV
                                || u.reason() == ResolutionResult.Reason.LOADER_MISMATCH),
                "应记录原因，实际=" + r.unresolved());
        verify(api, never()).getLatestVersion(anyString(), anyString(), eq("[\"forge\"]"));
    }

    @Test
    @DisplayName("深链：15 层合法依赖必须完整解析（不再有 8 层截断）")
    void deepChainIsFullyResolved() {
        int depth = 15;
        for (int i = 0; i < depth; i++) {
            List<String> deps = i < depth - 1 ? List.of("required:P" + (i + 1)) : List.of();
            register("m" + i, "P" + i, deps);
        }

        ResolutionResult r = resolve("m0");

        assertEquals(depth, r.orderedSlugs().size(), "15 层链应全部解析，实际=" + r.orderedSlugs());
        assertTrue(r.isComplete());
    }

    @Test
    @DisplayName("节点预算触顶：返回 INCOMPLETE，而不是假装成功")
    void nodeBudgetExhaustedIsReported() {
        DependencyEngine small = new DependencyEngine(api, knowledgeDb, 5);
        for (int i = 0; i < 12; i++) {
            List<String> deps = i < 11 ? List.of("required:P" + (i + 1)) : List.of();
            register("m" + i, "P" + i, deps);
        }

        ResolutionResult r = small.resolveWithReport(Set.of("m0"), NEOFORGE, MC);

        assertTrue(r.budgetExhausted(), "应标记触顶");
        assertFalse(r.isComplete(), "触顶时不能自称完整");
        assertTrue(r.orderedSlugs().size() <= 6, "结果不应超过预算，实际=" + r.orderedSlugs().size());
    }

    @Test
    @DisplayName("互斥：非核心的一方被剔除，核心保留")
    void conflictRemovesNonRootSide() {
        // core 是用户点名的根；rival 是它拉进来的依赖 → 冲突时保核心、剔非核心
        register("core", "PC", List.of("required:PR"));
        register("rival", "PR", List.of());
        when(knowledgeDb.getActiveRules(anyString())).thenReturn(List.of(
                new KnowledgeRule(NEOFORGE + "-" + MC, "core", "rival",
                        "CONFLICTS_WITH", KnowledgeRule.SourceType.ADMIN, 999)));

        ResolutionResult r = resolve("core");

        assertTrue(r.orderedSlugs().contains("core"), "核心应保留");
        assertFalse(r.orderedSlugs().contains("rival"), "互斥的非核心应被剔除");
        assertTrue(r.dropped().stream().anyMatch(d -> d.reason() == ResolutionResult.Reason.CONFLICT_WITH_KEPT),
                "应记录剔除原因，实际=" + r.dropped());
    }

    @Test
    @DisplayName("两个根模组互斥：不擅自裁决，报冲突让用户决定")
    void conflictBetweenRootsIsReported() {
        register("rootA", "PA", List.of());
        register("rootB", "PB", List.of());
        when(knowledgeDb.getActiveRules(anyString())).thenReturn(List.of(
                new KnowledgeRule(NEOFORGE + "-" + MC, "rootA", "rootB",
                        "CONFLICTS_WITH", KnowledgeRule.SourceType.ADMIN, 999)));

        ResolutionResult r = resolve("rootA", "rootB");

        assertTrue(r.unresolved().stream()
                        .anyMatch(u -> u.reason() == ResolutionResult.Reason.CONFLICT_BETWEEN_ROOTS),
                "两个根互斥应报给用户，实际=" + r.unresolved());
    }

    @Test
    @DisplayName("确定性：同一输入多次解析结果一致")
    void resolutionIsDeterministic() {
        register("a", "PA", List.of("required:PB"));
        register("b", "PB", List.of("required:PC"));
        register("c", "PC", List.of());

        List<String> first = new ArrayList<>(resolve("a").orderedSlugs());
        List<String> second = new ArrayList<>(resolve("a").orderedSlugs());

        assertEquals(first, second, "同一输入的解析结果必须一致");
    }
}
