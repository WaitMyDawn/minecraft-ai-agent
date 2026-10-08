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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 侦测（建池）任务的去重与取消。
 *
 * <p>为什么必须有：建池是往上挖几十页 Modrinth 的重活。前端禁用按钮只挡得住当前标签页，
 * 连点、多标签页、刷新重发都会绕过它 —— 线上实测出现过同一用户 4 个建池同时跑。
 * 这里钉住三件事：同一用户并发只放一个进来、取消能让循环提前收工、任务结束后能再次侦测。
 */
class ModpackControllerSuggestTest {

    private ModrinthApiClient apiClient;
    private UserController userController;
    private ModpackController controller;

    @BeforeEach
    void setUp() {
        apiClient = mock(ModrinthApiClient.class);
        userController = mock(UserController.class);
        controller = new ModpackController(mock(DependencyEngine.class), apiClient, new ObjectMapper(),
                mock(KnowledgeDb.class), mock(RestClient.class), mock(MrpackParser.class),
                mock(LoaderVersionService.class), new PackManifestService(), userController);
    }

    private static ModpackController.SuggestionRequest req(String clientId, int page) {
        ModpackController.SuggestionRequest r = new ModpackController.SuggestionRequest();
        r.loader = "neoforge";
        r.mcVersion = "1.21.1";
        r.page = page;
        r.clientId = clientId;
        return r;
    }

    /** 一页搜索结果：每条都合格，于是循环会一直往下挖（不会因为"到手 15 个"提前结束） */
    private JsonNode pageWithOneHit() throws Exception {
        return new ObjectMapper().readTree(
                "{\"hits\":[{\"slug\":\"mod-x\",\"downloads\":1000000,\"title\":\"X\"}]}");
    }

    @Test
    @DisplayName("同一用户并发侦测：第二个直接 409，不启动第二个建池")
    void concurrentSuggestIsRejected() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(apiClient.searchPage(any(), anyInt(), anyInt(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return pageWithOneHit();
                });

        AtomicReference<ResponseEntity<Map<String, Object>>> firstResult = new AtomicReference<>();
        Thread first = new Thread(() -> firstResult.set(controller.suggestMods(null, req("tab-1", 0))));
        first.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一个任务应已进入建池循环");

        ResponseEntity<Map<String, Object>> second = controller.suggestMods(null, req("tab-1", 0));

        assertEquals(409, second.getStatusCode().value());
        assertEquals(Boolean.TRUE, second.getBody().get("busy"));

        // 收尾：取消 + 放行，确认第一个任务能正常结束（否则线程会挂到 JVM 退出）
        controller.cancelSuggest(null, req("tab-1", 0));
        release.countDown();
        first.join(5000);
        assertFalse(first.isAlive(), "第一个任务应已结束");
    }

    @Test
    @DisplayName("取消后提前收工：返回已建成的部分结果，并带上 cancelled 标记")
    void cancelStopsTheLoopAndReturnsPartialPool() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(apiClient.searchPage(any(), anyInt(), anyInt(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return pageWithOneHit();
                });

        AtomicReference<ResponseEntity<Map<String, Object>>> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(controller.suggestMods(null, req("tab-2", 0))));
        t.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        ResponseEntity<Map<String, Object>> cancel = controller.cancelSuggest(null, req("tab-2", 0));
        assertEquals(Boolean.TRUE, cancel.getBody().get("running"));
        release.countDown();
        t.join(5000);

        assertEquals(200, result.get().getStatusCode().value());
        assertEquals(Boolean.TRUE, result.get().getBody().get("cancelled"), "取消后要如实标记，前端据此提示");
        assertEquals(1, result.get().getBody().get("poolSize"), "已建成的部分结果要保留");
    }

    @Test
    @DisplayName("任务结束后可以再次侦测（登记表必须摘牌，否则永远 409）")
    void registryIsCleanedUpAfterFinish() throws Exception {
        when(apiClient.searchPage(any(), anyInt(), anyInt(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    JsonNode hits = pageWithOneHit();
                    // 让列表足够长：顺序模式要 15 个才停，这里给 20 个直接结束
                    StringBuilder sb = new StringBuilder("{\"hits\":[");
                    for (int i = 0; i < 20; i++) {
                        if (i > 0) sb.append(',');
                        sb.append("{\"slug\":\"mod-").append(i).append("\",\"downloads\":1000000}");
                    }
                    sb.append("]}");
                    return new ObjectMapper().readTree(sb.toString());
                });

        assertEquals(200, controller.suggestMods(null, req("tab-3", 0)).getStatusCode().value());
        assertEquals(200, controller.suggestMods(null, req("tab-3", 0)).getStatusCode().value());
    }

    @Test
    @DisplayName("任务归属键：登录用账号、未登录用 clientId、两者互不影响")
    void taskKeyDistinguishesUsers() {
        when(userController.validateToken("tok")).thenReturn(7L);

        assertEquals("u7", controller.suggestTaskKey("tok", req("tab-1", 0)));
        assertEquals("atab-1", controller.suggestTaskKey(null, req("tab-1", 0)));
        assertNotEquals(controller.suggestTaskKey(null, req("tab-1", 0)),
                controller.suggestTaskKey(null, req("tab-2", 0)));
        // 没带 clientId 的匿名请求共用一个桶：宁可相互挡一次，也不放它们同时挖上游
        assertEquals("anon", controller.suggestTaskKey(null, req(null, 0)));
        // clientId 来自前端，落键前必须净化：不允许出现分隔符/超长串
        assertEquals("a-x_y", controller.suggestTaskKey(null, req("-x_y!!", 0)));
    }
}
