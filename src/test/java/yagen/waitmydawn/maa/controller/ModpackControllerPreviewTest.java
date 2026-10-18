package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.service.DependencyEngine;
import yagen.waitmydawn.maa.service.KnowledgeDb;
import yagen.waitmydawn.maa.service.LoaderVersionService;
import yagen.waitmydawn.maa.service.ModrinthApiClient;
import yagen.waitmydawn.maa.service.MrpackParser;
import yagen.waitmydawn.maa.service.PackManifestService;
import yagen.waitmydawn.maa.model.DependencyGraph;
import yagen.waitmydawn.maa.model.ResolutionResult;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 预览接口的输入契约。
 *
 * <p>两个必须钉住的行为：
 * <ol>
 *   <li><b>空清单合法</b>——"生成空图谱"就是拿空清单换一张空图 + 一份清单快照，
 *       用户随后在装配台里逐个添加模组；</li>
 *   <li><b>环境非法要当场报错</b>——旧实现照单全收，{@code neoforge-9.9.9} 会安静地
 *       返回空图，用户以为创建成功、之后却永远加不进模组。</li>
 * </ol>
 */
class ModpackControllerPreviewTest {

    private DependencyEngine dependencyEngine;
    private ModrinthApiClient apiClient;
    private LoaderVersionService loaderVersionService;
    private ModpackController controller;

    @BeforeEach
    void setUp() {
        dependencyEngine = mock(DependencyEngine.class);
        apiClient = mock(ModrinthApiClient.class);
        KnowledgeDb knowledgeDb = mock(KnowledgeDb.class);
        UserController userController = mock(UserController.class);
        loaderVersionService = mock(LoaderVersionService.class);

        controller = new ModpackController(dependencyEngine, apiClient, new ObjectMapper(), knowledgeDb,
                mock(RestClient.class), mock(MrpackParser.class), loaderVersionService,
                new PackManifestService(), userController);

        when(loaderVersionService.supportedLoaders()).thenReturn(List.of("neoforge", "forge", "fabric"));
        when(loaderVersionService.availableMcVersions("neoforge")).thenReturn(List.of("1.20.1", "1.21.1"));
        when(knowledgeDb.getActiveRules(anyString(), any())).thenReturn(List.of());
        // 注意：先把 ResolutionResult 造好再 stub。把 resolution(...) 直接塞进 thenReturn 会触发
        // Mockito 的嵌套 stub（UnfinishedStubbing），因为造它的过程本身也在 when(...)
        ResolutionResult empty = emptyResolution();
        when(dependencyEngine.resolveCachedResult(any(), anyString(), anyString(), any())).thenReturn(empty);
    }

    /** 空解析结果：没有节点、也没有诊断 */
    private static ResolutionResult emptyResolution() {
        return resolution(List.of(), List.of());
    }

    private static ResolutionResult resolution(List<ResolutionResult.Unresolved> unresolved,
                                              List<ResolutionResult.Dropped> dropped) {
        ResolutionResult r = mock(ResolutionResult.class);
        when(r.graph()).thenReturn(new DependencyGraph());
        when(r.unresolved()).thenReturn(unresolved);
        when(r.dropped()).thenReturn(dropped);
        return r;
    }

    private static ModpackController.ModpackRequest request(String loader, String mcVersion, List<String> slugs) {
        ModpackController.ModpackRequest r = new ModpackController.ModpackRequest();
        r.name = "空图谱";
        r.loader = loader;
        r.mcVersion = mcVersion;
        r.modSlugs = slugs;
        return r;
    }

    @Test
    @DisplayName("空清单（[]）返回空图 + 清单快照，这是生成空图谱的主路径")
    void emptySlugListReturnsEmptyGraph() {
        ResponseEntity<JsonNode> res = controller.previewPack(null, request("neoforge", "1.21.1", List.of()));

        assertEquals(200, res.getStatusCode().value());
        JsonNode body = res.getBody();
        assertEquals(0, body.path("nodes").size());
        assertEquals(0, body.path("edges").size());
        assertEquals(0, body.path("excludedCount").asInt());
        assertFalse(body.path("manifestId").asText().isBlank(), "必须登记清单快照，否则装配台无法导出");
        assertEquals(0, body.path("manifestSize").asInt());
    }

    @Test
    @DisplayName("modSlugs 为 null 也按空清单处理（老前端可能不带这个字段）")
    void nullSlugListIsTreatedAsEmpty() {
        ResponseEntity<JsonNode> res = controller.previewPack(null, request("neoforge", "1.21.1", null));

        assertEquals(200, res.getStatusCode().value());
        assertEquals(0, res.getBody().path("nodes").size());
    }

    @Test
    @DisplayName("缺加载器 / 缺版本分别给出明确 400，而不是空图")
    void missingEnvironmentIsRejected() {
        assertEquals(400, controller.previewPack(null, request(null, "1.21.1", List.of())).getStatusCode().value());
        assertEquals(400, controller.previewPack(null, request("  ", "1.21.1", List.of())).getStatusCode().value());
        assertEquals(400, controller.previewPack(null, request("neoforge", "  ", List.of())).getStatusCode().value());
        assertEquals(400, controller.previewPack(null, request("neoforge", null, List.of())).getStatusCode().value());
    }

    @Test
    @DisplayName("不支持的加载器与未维护的 MC 版本都要报错，并把可选值告诉用户")
    void unknownLoaderAndVersionAreRejected() {
        JsonNode badLoader = controller.previewPack(null, request("quilt", "1.21.1", List.of())).getBody();
        assertTrue(badLoader.path("error").asText().contains("不支持的加载器"), badLoader.toString());

        JsonNode badVersion = controller.previewPack(null, request("neoforge", "9.9.9", List.of())).getBody();
        assertTrue(badVersion.path("error").asText().contains("9.9.9"), badVersion.toString());
        assertTrue(badVersion.path("error").asText().contains("1.21.1"), "错误里要带上可选版本：" + badVersion);
    }

    @Test
    @DisplayName("版本表还没加载时只校验加载器名，不能把所有请求都判死")
    void emptyVersionTableDoesNotBlockRequests() {
        when(loaderVersionService.supportedLoaders()).thenReturn(List.of());
        when(loaderVersionService.availableMcVersions("neoforge")).thenReturn(List.of());

        ResponseEntity<JsonNode> res = controller.previewPack(null, request("neoforge", "1.21.1", List.of()));

        assertEquals(200, res.getStatusCode().value());
    }

    @Test
    @DisplayName("加载器大小写与空格要归一化，别因为 ' NeoForge ' 就拒绝")
    void loaderIsNormalized() {
        ResponseEntity<JsonNode> res = controller.previewPack(null, request(" NeoForge ", "1.21.1", List.of()));

        assertEquals(200, res.getStatusCode().value());
    }

    @Test
    @DisplayName("解析诊断要回给前端：找不到的模组 / 被剔除的模组都要带原因")
    void resolutionDiagnosticsAreReturned() {
        ResolutionResult withDiagnostics = resolution(
                List.of(new ResolutionResult.Unresolved("not-a-mod", "(用户点名)",
                        ResolutionResult.Reason.PROJECT_NOT_FOUND, "Modrinth 上没有这个项目")),
                List.of(new ResolutionResult.Dropped("rival",
                        ResolutionResult.Reason.CONFLICT_WITH_KEPT, "与保留的 core 互斥，已剔除")));
        when(dependencyEngine.resolveCachedResult(any(), anyString(), anyString(), any()))
                .thenReturn(withDiagnostics);

        JsonNode body = controller.previewPack(null, request("neoforge", "1.21.1", List.of("not-a-mod")))
                .getBody();

        assertEquals(1, body.path("unresolved").size());
        assertEquals("not-a-mod", body.path("unresolved").get(0).path("slug").asText());
        assertEquals("PROJECT_NOT_FOUND", body.path("unresolved").get(0).path("reason").asText());
        assertEquals(1, body.path("dropped").size());
        assertEquals("CONFLICT_WITH_KEPT", body.path("dropped").get(0).path("reason").asText());
    }
    @Test void removedDependencyIdentitySurvivesPreviewAuditWithoutDuplicateMissingId() {
        var json = new ObjectMapper();
        var graph = new DependencyGraph(); graph.allSlugs.add("structures");
        graph.projectAliases.put("MISSING", "decoration");
        var version = json.readTree("""
                {"id":"V1","project_id":"ROOT","version_number":"1.0",
                 "dependencies":[{"dependency_type":"required","project_id":"MISSING"}],
                 "files":[{"filename":"structures.jar","url":"https://cdn.modrinth.com/data/ROOT/versions/V1/structures.jar",
                           "size":1,"hashes":{"sha1":"1111111111111111111111111111111111111111"}}]}
                """);
        graph.selectedVersions.put("structures", version);
        var result = new ResolutionResult(graph, ResolutionResult.Status.INCOMPLETE, Set.of("structures"),
                List.of(new ResolutionResult.Unresolved("decoration", "structures", ResolutionResult.Reason.DEPENDENT_OF_DROPPED, "前置已剔除")),
                List.of(), 500, false);
        when(dependencyEngine.resolveCachedResult(any(), anyString(), anyString(), any())).thenReturn(result);
        when(apiClient.getProjectInfo("structures")).thenReturn(json.readTree("{\"id\":\"ROOT\",\"slug\":\"structures\",\"title\":\"Structures\"}"));
        var response = controller.previewPack(null, request("neoforge", "1.21.1", List.of("structures")));
        assertEquals(200, response.getStatusCode().value());
        var closure = response.getBody().path("closure");
        assertEquals("INCOMPLETE", closure.path("status").asText());
        assertEquals(1, closure.path("issues").size());
        assertEquals("decoration", closure.path("issues").get(0).path("dependency").asText());
        assertEquals("DEPENDENT_OF_DROPPED", closure.path("issues").get(0).path("code").asText());
    }
}
