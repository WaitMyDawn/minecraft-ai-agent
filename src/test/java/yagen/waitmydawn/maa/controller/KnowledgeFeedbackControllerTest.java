package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import yagen.waitmydawn.maa.model.User;
import yagen.waitmydawn.maa.service.KnowledgeDb;
import yagen.waitmydawn.maa.service.LoaderVersionService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则编辑器 / 投票两个入口的守门测试。
 *
 * <p>重点是三件"写进库里就删不掉"的错：环境串拼错（规则永远命中不了）、官方规则被用户票覆盖、
 * 没登录也能投票。
 */
class KnowledgeFeedbackControllerTest {

    private static final String TOKEN = "token-1";
    private static final String ACCOUNT = "100001";
    private static final String ENV = "neoforge-1.21.1";

    private KnowledgeDb db;
    private UserController users;
    private LoaderVersionService loaders;
    private KnowledgeFeedbackController controller;

    @BeforeEach
    void setUp() {
        db = mock(KnowledgeDb.class);
        users = mock(UserController.class);
        loaders = mock(LoaderVersionService.class);
        controller = new KnowledgeFeedbackController(db, users, loaders);

        User user = mock(User.class);
        when(user.getAccountNumber()).thenReturn(ACCOUNT);
        when(users.validateToken(TOKEN)).thenReturn(1L);
        when(users.getUserByToken(TOKEN)).thenReturn(Optional.of(user));

        when(loaders.supportedLoaders()).thenReturn(List.of("neoforge", "fabric"));
        when(loaders.availableMcVersions("neoforge")).thenReturn(List.of("1.20.1", "1.21.1"));
        // fabric 在版本表里只有通配项 "*"：可选版本借其它加载器的并集
        when(loaders.availableMcVersions("fabric")).thenReturn(List.of("1.20.1", "1.21.1"));
    }

    private Map<String, String> addBody(String environment, String modA, String modB) {
        Map<String, String> body = new HashMap<>();
        body.put("environment", environment);
        body.put("modA", modA);
        body.put("modB", modB);
        body.put("relationType", "DEPENDS_ON");
        return body;
    }

    private static Map<String, Object> voteBody(Object ruleId, String vote) {
        Map<String, Object> body = new HashMap<>();
        body.put("ruleId", ruleId);
        body.put("vote", vote);
        return body;
    }

    @Test
    @DisplayName("加载器下拉按用户侧名字返回（fabric 不能因为表里键叫 fabric-loader 就消失）")
    void loaderEnvsUsesUserSideLoaderNames() {
        var body = controller.getLoaderEnvs().getBody();

        assertNotNull(body);
        assertEquals(List.of("neoforge", "fabric"), List.copyOf(body.keySet()));
        assertEquals(2, body.get("neoforge").size());
    }

    @Test
    @DisplayName("环境不在加载器版本表里 → 拒收，且不写库")
    void addRejectsUnknownEnvironment() {
        var res = controller.addFeedback(TOKEN, addBody("neoforge-9.9.9", "aaa", "bbb"));

        assertTrue(String.valueOf(res.getBody().get("error")).contains("不支持的环境"));
        verify(db, never()).addUserRule(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("fabric 的通配版本表也不能放过 fabric-9.9.9")
    void addRejectsBogusFabricVersion() {
        var res = controller.addFeedback(TOKEN, addBody("fabric-9.9.9", "aaa", "bbb"));
        assertTrue(String.valueOf(res.getBody().get("error")).contains("不支持的环境"));
    }

    @Test
    @DisplayName("同一个模组不能跟自己建立关系")
    void addRejectsIdenticalMods() {
        var res = controller.addFeedback(TOKEN, addBody(ENV, "aaa", "AAA"));
        assertEquals("两个模组不能相同", res.getBody().get("error"));
    }

    @Test
    @DisplayName("合法提交：按登录账号写入认可名单，返回净值")
    void addDelegatesWithAccountNumber() {
        when(db.addUserRule(ENV, "aaa", "bbb", "DEPENDS_ON", ACCOUNT))
                .thenReturn(new KnowledgeDb.FeedbackOutcome(true, null, "规则已添加", 1, 0, 1));

        var res = controller.addFeedback(TOKEN, addBody(ENV, "AAA", "BBB"));

        assertTrue((Boolean) res.getBody().get("ok"));
        assertEquals(1, res.getBody().get("confirmCount"));
        verify(db).addUserRule(ENV, "aaa", "bbb", "DEPENDS_ON", ACCOUNT);
    }

    @Test
    @DisplayName("官方规则不能被用户覆盖，错误原样透出")
    void addOnOfficialRuleReturnsError() {
        when(db.addUserRule(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new KnowledgeDb.FeedbackOutcome(false, "该规则已存在且为官方规则，无法覆盖", null, 0, 0, 0));

        var res = controller.addFeedback(TOKEN, addBody(ENV, "aaa", "bbb"));

        assertTrue(String.valueOf(res.getBody().get("error")).contains("官方规则"));
        assertFalse(res.getBody().containsKey("ok"));
    }

    @Test
    @DisplayName("没登录不能投票")
    void voteRequiresLogin() {
        when(users.validateToken("bad")).thenReturn(null);
        var res = controller.vote("bad", voteBody(7, "APPROVE"));
        assertEquals(401, res.getStatusCode().value());
        verify(db, never()).vote(any(), anyString(), any());
    }

    @Test
    @DisplayName("投票类型只认 APPROVE / DISAPPROVE / CLEAR")
    void voteRejectsUnknownAction() {
        var res = controller.vote(TOKEN, voteBody(7, "MAYBE"));
        assertTrue(String.valueOf(res.getBody().get("error")).contains("投票类型"));
        verify(db, never()).vote(any(), anyString(), any());
    }

    @Test
    @DisplayName("缺少 ruleId 直接打回")
    void voteRequiresRuleId() {
        var res = controller.vote(TOKEN, voteBody(null, "APPROVE"));
        assertEquals("缺少 ruleId", res.getBody().get("error"));
    }

    @Test
    @DisplayName("投票按登录账号下发，CLEAR 的 myVote 是 null（按钮弹回未按下）")
    void voteDelegatesWithAccountNumber() {
        when(db.vote(7L, ACCOUNT, KnowledgeDb.Vote.CLEAR))
                .thenReturn(new KnowledgeDb.FeedbackOutcome(true, null, "已记录你的投票", 2, 1, 1));

        ResponseEntity<Map<String, Object>> res = controller.vote(TOKEN, voteBody(7L, "CLEAR"));

        assertTrue((Boolean) res.getBody().get("ok"));
        assertEquals(1, res.getBody().get("confirmCount"));
        assertTrue(res.getBody().containsKey("myVote"));
        assertEquals(null, res.getBody().get("myVote"));
        verify(db).vote(eq(7L), eq(ACCOUNT), eq(KnowledgeDb.Vote.CLEAR));
    }

    @Test
    @DisplayName("官方规则不可投票：错误原样透出（前端 alert 里能看到原因）")
    void voteOnOfficialRuleReturnsError() {
        when(db.vote(any(), anyString(), any()))
                .thenReturn(new KnowledgeDb.FeedbackOutcome(false, "官方规则不可投票", null, 0, 0, 0));

        var res = controller.vote(TOKEN, voteBody(7L, "APPROVE"));

        assertEquals("官方规则不可投票", res.getBody().get("error"));
    }
}
