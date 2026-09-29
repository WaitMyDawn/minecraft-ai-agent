package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.KnowledgeRule;
import yagen.waitmydawn.maa.model.User;
import yagen.waitmydawn.maa.service.KnowledgeDb;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识库视图的 DTO 契约：界面上的颜色、双计数、按钮按下态全靠这几个字段。
 *
 * <p>顺带守住一条隐私底线：接口是公开可读的，**不能**把认可/不认可名单（别人的账号号）吐出去。
 */
class KnowledgeControllerTest {

    private static final String TOKEN = "token-1";
    private static final String ACCOUNT = "100009";

    private KnowledgeDb db;
    private UserController users;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        db = mock(KnowledgeDb.class);
        users = mock(UserController.class);
        controller = new KnowledgeController(db, users);
    }

    private static KnowledgeRule userRule(String approved, String disapproved) {
        KnowledgeRule r = new KnowledgeRule("neoforge-1.21.1", "aaa", "bbb", "DEPENDS_ON",
                KnowledgeRule.SourceType.USER_FEEDBACK,
                KnowledgeRule.splitUsers(approved).size() - KnowledgeRule.splitUsers(disapproved).size(),
                approved);
        r.id = 1L;
        r.disapprovedUsers = disapproved;
        return r;
    }

    @Test
    @DisplayName("用户规则：双计数 + 净值 + 生效标记，且不吐名单（账号号不能外泄）")
    void userRuleDtoHasCountsButNoAccountLists() {
        when(db.getAllRules()).thenReturn(List.of(userRule("u1,u2,u3", "u4")));

        Map<String, Object> dto = controller.getAllRules(null).get(0);

        assertEquals(3, dto.get("approveCount"));
        assertEquals(1, dto.get("disapproveCount"));
        assertEquals(2, dto.get("confirmCount"));   // 净值 3 - 1 = 2，不足 3 → 未生效
        assertEquals(false, dto.get("effective"));
        assertNull(dto.get("myVote"));             // 没带 token → 画不出按下态
        assertFalse(dto.containsKey("approvedUsers"), "名单不该出现在公开接口里：" + dto.keySet());
        assertFalse(dto.containsKey("disapprovedUsers"), "名单不该出现在公开接口里：" + dto.keySet());
    }

    @Test
    @DisplayName("净值达到 3 才算生效（认可 4 不认可 1 → 生效）")
    void netScoreAtLeastThreeIsEffective() {
        when(db.getAllRules()).thenReturn(List.of(userRule("u1,u2,u3,u4", "u5")));
        Map<String, Object> dto = controller.getAllRules(null).get(0);
        assertEquals(3, dto.get("confirmCount"));
        assertEquals(true, dto.get("effective"));
    }

    @Test
    @DisplayName("带 token 时给出我自己的投票状态，按钮才能画出按下态")
    void myVoteReflectsCurrentUser() {
        User user = mock(User.class);
        when(user.getAccountNumber()).thenReturn(ACCOUNT);
        when(users.getUserByToken(TOKEN)).thenReturn(Optional.of(user));
        when(db.getAllRules()).thenReturn(List.of(userRule("u1," + ACCOUNT, "u2")));

        assertEquals("APPROVE", controller.getAllRules(TOKEN).get(0).get("myVote"));
    }

    @Test
    @DisplayName("官方规则恒生效，且没有投票计数")
    void officialRuleIsAlwaysEffective() {
        KnowledgeRule admin = new KnowledgeRule("neoforge-1.21.1", "aaa", "bbb", "CONFLICTS_WITH",
                KnowledgeRule.SourceType.ADMIN, 999);
        admin.id = 2L;
        when(db.getAllRules()).thenReturn(List.of(admin));

        Map<String, Object> dto = controller.getAllRules(null).get(0);

        assertEquals("ADMIN", dto.get("sourceType"));
        assertTrue((Boolean) dto.get("effective"));
        assertEquals(0, dto.get("approveCount"));
        assertEquals(0, dto.get("disapproveCount"));
    }
}
