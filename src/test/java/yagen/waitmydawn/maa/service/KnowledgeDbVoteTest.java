package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.KnowledgeRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户来源规则的"认可 / 不认可"评测。
 *
 * <p>为什么必须有：这一版把"信任度 = 添加人数"改成了"净值 = 认可数 − 不认可数"，
 * 生效门槛（≥3）与生效判定的口径全变了；而投票是**读出一整列 → 改 → 写回一整列**，
 * 并发下最容易出现"后写的把先写的覆盖掉"（丢票）。这两件事靠手点页面都验不出来。
 */
class KnowledgeDbVoteTest {

    private static final String ENV = "neoforge-1.21.1";

    private KnowledgeRuleRepo repo;
    private KnowledgeDb db;
    /** 假数据库：findById 返回副本（模拟"读到的是库里那一份"），save 写回副本。 */
    private final Map<Long, KnowledgeRule> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        repo = mock(KnowledgeRuleRepo.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        db = new KnowledgeDb(repo, new ObjectMapper(), txManager);

        when(repo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))).map(KnowledgeDbVoteTest::copy));
        when(repo.save(any(KnowledgeRule.class))).thenAnswer(inv -> {
            KnowledgeRule r = inv.getArgument(0);
            store.put(r.id, copy(r));
            return r;
        });
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(store.values()));
        when(repo.findByEnvironment(anyString())).thenAnswer(inv -> store.values().stream()
                .filter(r -> r.environment.equals(inv.getArgument(0))).toList());
    }

    private static KnowledgeRule copy(KnowledgeRule r) {
        KnowledgeRule c = new KnowledgeRule(r.environment, r.modA, r.modB, r.relationType,
                r.sourceType, r.confirmCount, r.approvedUsers);
        c.id = r.id;
        c.disapprovedUsers = r.disapprovedUsers;
        return c;
    }

    private KnowledgeRule putUserRule(long id, String approved, String disapproved) {
        KnowledgeRule r = new KnowledgeRule(ENV, "mod-a", "mod-b", "DEPENDS_ON",
                KnowledgeRule.SourceType.USER_FEEDBACK,
                KnowledgeRule.splitUsers(approved).size() - KnowledgeRule.splitUsers(disapproved).size(),
                approved);
        r.id = id;
        r.disapprovedUsers = disapproved;
        store.put(id, copy(r));
        return r;
    }

    // ===== 净值与互斥 =====

    @Test
    @DisplayName("投认可：进入认可名单，净值 +1，不足 3 票不生效")
    void approveAddsToApprovedList() {
        putUserRule(1L, null, null);

        var outcome = db.vote(1L, "u1", KnowledgeDb.Vote.APPROVE);

        assertTrue(outcome.ok());
        assertEquals(1, outcome.approveCount());
        assertEquals(0, outcome.disapproveCount());
        assertEquals(1, outcome.netScore());
        KnowledgeRule saved = store.get(1L);
        assertEquals("u1", saved.approvedUsers);
        assertEquals("", saved.disapprovedUsers);
        assertFalse(saved.isEffective(), "1 票不该生效");
    }

    @Test
    @DisplayName("两个按钮互斥：改投不认可会把认可票摘掉，净值变负")
    void switchingVoteMovesBetweenLists() {
        putUserRule(1L, "u1,u2", null);

        var outcome = db.vote(1L, "u1", KnowledgeDb.Vote.DISAPPROVE);

        assertEquals(1, outcome.approveCount());
        assertEquals(1, outcome.disapproveCount());
        assertEquals(0, outcome.netScore());
        KnowledgeRule saved = store.get(1L);
        assertEquals("u2", saved.approvedUsers, "u1 必须从认可名单里消失");
        assertEquals("u1", saved.disapprovedUsers);
    }

    @Test
    @DisplayName("再点一次 = 取消投票：两个名单都不再包含这个人")
    void clearRemovesBothVotes() {
        putUserRule(1L, "u1", null);

        var outcome = db.vote(1L, "u1", KnowledgeDb.Vote.CLEAR);

        assertTrue(outcome.ok());
        assertEquals(0, outcome.approveCount());
        assertEquals(0, outcome.disapproveCount());
        assertEquals("", store.get(1L).approvedUsers);
        assertEquals("", store.get(1L).disapprovedUsers);
    }

    @Test
    @DisplayName("净值达到 3 才生效：3 认可 0 不认可 → 进 getActiveRules；2 认可 1 不认可 → 不进")
    void threeNetApprovalsMakeItEffective() {
        putUserRule(1L, "u1,u2", null);
        var third = db.vote(1L, "u3", KnowledgeDb.Vote.APPROVE);
        assertEquals(3, third.netScore());
        assertTrue(db.getActiveRules(ENV, null).stream().anyMatch(r -> r.id == 1L));

        putUserRule(2L, "u1,u2,u3", null);
        var down = db.vote(2L, "u3", KnowledgeDb.Vote.DISAPPROVE);
        assertEquals(1, down.netScore());
        assertFalse(db.getActiveRules(ENV, null).stream().anyMatch(r -> r.id == 2L));
    }

    @Test
    @DisplayName("官方规则不可投票，官方规则也不受净值影响")
    void officialRulesAreNotVotable() {
        KnowledgeRule admin = new KnowledgeRule(ENV, "a", "b", "CONFLICTS_WITH",
                KnowledgeRule.SourceType.ADMIN, 999);
        admin.id = 5L;
        store.put(5L, copy(admin));
        KnowledgeRule modrinth = new KnowledgeRule(ENV, "c", "d", "DEPENDS_ON",
                KnowledgeRule.SourceType.MODRINTH, 100);
        modrinth.id = 6L;
        store.put(6L, copy(modrinth));

        var rejected = db.vote(5L, "u1", KnowledgeDb.Vote.DISAPPROVE);

        assertFalse(rejected.ok());
        assertTrue(rejected.error().contains("不可投票"));
        assertEquals(2, db.getActiveRules(ENV, null).size(), "官方规则恒生效");
    }

    @Test
    @DisplayName("规则不存在时给出明确错误，而不是抛异常")
    void votingMissingRuleIsRejected() {
        var outcome = db.vote(404L, "u1", KnowledgeDb.Vote.APPROVE);
        assertFalse(outcome.ok());
        assertEquals("规则不存在", outcome.error());
    }

    // ===== 并发：不能丢票 =====

    @Test
    @DisplayName("8 个用户并发投票不能丢票（读改写必须串行）")
    void concurrentVotesDoNotLoseUpdates() throws Exception {
        putUserRule(1L, null, null);
        int voters = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < voters; i++) {
            String account = "u" + i;
            Thread t = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                db.vote(1L, account, KnowledgeDb.Vote.APPROVE);
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) t.join(10_000);

        KnowledgeRule saved = store.get(1L);
        assertEquals(voters, saved.approveCount(), "并发投票后有票被覆盖（丢票）");
        assertEquals(voters, saved.confirmCount);
    }

    // ===== 添加规则（编辑器那条路）=====

    @Test
    @DisplayName("新增规则：提交者记为认可，净值 1")
    void addUserRuleRecordsApprover() {
        var outcome = db.addUserRule(ENV, "x", "y", "DEPENDS_ON", "u1");

        assertTrue(outcome.ok());
        assertEquals(1, outcome.netScore());
        KnowledgeRule saved = store.values().iterator().next();
        assertEquals(KnowledgeRule.SourceType.USER_FEEDBACK, saved.sourceType);
        assertEquals("u1", saved.approvedUsers);
    }

    @Test
    @DisplayName("已存在的用户规则：再次添加 = 投一票认可；同一个人重复添加不重复计数")
    void addUserRuleOnExistingUserRule() {
        putUserRule(1L, "u1", null);
        KnowledgeRule existing = store.get(1L);
        existing.modA = "x";
        existing.modB = "y";

        var first = db.addUserRule(ENV, "x", "y", "DEPENDS_ON", "u2");
        assertEquals(2, first.netScore());

        var repeat = db.addUserRule(ENV, "x", "y", "DEPENDS_ON", "u2");
        assertTrue(repeat.ok());
        assertEquals(2, repeat.netScore(), "同一个人重复添加不该把净值抬上去");
    }

    @Test
    @DisplayName("官方规则不能被用户覆盖")
    void addUserRuleCannotOverrideOfficialRule() {
        KnowledgeRule admin = new KnowledgeRule(ENV, "x", "y", "DEPENDS_ON",
                KnowledgeRule.SourceType.ADMIN, 999);
        admin.id = 9L;
        store.put(9L, copy(admin));

        var outcome = db.addUserRule(ENV, "x", "y", "DEPENDS_ON", "u1");

        assertFalse(outcome.ok());
        assertTrue(outcome.error().contains("官方规则"));
        assertEquals(KnowledgeRule.SourceType.ADMIN, store.get(9L).sourceType);
    }

    // ===== 排序 / 解析 =====

    @Test
    @DisplayName("getAllRules 按 环境 → 关系类型 → 主模组 → 指向模组 字典序返回")
    void getAllRulesIsSorted() {
        store.clear();
        store.put(1L, rule(1L, ENV, "zulu", "a", "DEPENDS_ON"));
        store.put(2L, rule(2L, ENV, "alpha", "z", "DEPENDS_ON"));
        store.put(3L, rule(3L, ENV, "alpha", "b", "CONFLICTS_WITH"));
        store.put(4L, rule(4L, "forge-1.20.1", "aaa", "a", "DEPENDS_ON"));

        List<String> order = db.getAllRules().stream()
                .map(r -> r.environment + "|" + r.relationType + "|" + r.modA + "|" + r.modB)
                .toList();

        assertEquals(List.of(
                "forge-1.20.1|DEPENDS_ON|aaa|a",
                "neoforge-1.21.1|CONFLICTS_WITH|alpha|b",
                "neoforge-1.21.1|DEPENDS_ON|alpha|z",
                "neoforge-1.21.1|DEPENDS_ON|zulu|a"), order);
    }

    @Test
    @DisplayName("名单解析：空串 / 空项 / 重复项都不能影响计数")
    void userListParsingIsRobust() {
        assertTrue(KnowledgeRule.splitUsers(null).isEmpty());
        assertTrue(KnowledgeRule.splitUsers("").isEmpty());
        assertEquals(2, KnowledgeRule.splitUsers("u1,, u2 ,").size());
        assertEquals(1, KnowledgeRule.splitUsers("u1,u1").size());
        assertEquals("", KnowledgeRule.joinUsers(List.of()));
        assertEquals("u2,u1", KnowledgeRule.joinUsers(KnowledgeRule.splitUsers(" u2 , u1 ")));
    }

    private static KnowledgeRule rule(long id, String env, String modA, String modB, String relationType) {
        KnowledgeRule r = new KnowledgeRule(env, modA, modB, relationType,
                KnowledgeRule.SourceType.ADMIN, 999);
        r.id = id;
        return r;
    }
}
