package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 独立内存 H2 的结构与竞争回归：验证实体 DDL、原生及派生查询、约束和旧快照写入保护。 */
@SpringBootTest(properties = "spring.config.location=classpath:application-test.properties")
class SchemaSanityTest {
    @Autowired private ChatMessageRepository messages;

    @Test void constraintSnapshotsCanExceedLegacyCsvColumnCapacity() {
        var message = new ChatMessage(999L, "ai", "fixture", true, "", "jade");
        String snapshot = "{\"fixture\":\"" + "x".repeat(32_000) + "\"}";
        message.setPackState(snapshot);
        var saved = messages.saveAndFlush(message);
        try {
            org.junit.jupiter.api.Assertions.assertEquals(snapshot, messages.findById(saved.getId()).orElseThrow().getPackState());
        } finally { messages.deleteById(saved.getId()); }
    }
    @Test
    void staleAccountWritesAndCiphertextMigrationCannotOverwriteNewSettings() {
        User saved = userRepo.saveAndFlush(new User("900001", "concurrency-fixture", "fixture-hash"));
        try {
            User first = userRepo.findById(saved.getId()).orElseThrow();
            User stale = userRepo.findById(saved.getId()).orElseThrow();
            first.setEncryptedApiKey("fixture-old-ciphertext");
            userRepo.saveAndFlush(first);
            stale.setUsername("stale-name");
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
                    () -> userRepo.saveAndFlush(stale));
            org.junit.jupiter.api.Assertions.assertEquals(0, userRepo.upgradeApiKeyCiphertext(saved.getId(), "wrong", "replacement"));
            org.junit.jupiter.api.Assertions.assertEquals(1, userRepo.upgradeApiKeyCiphertext(saved.getId(), "fixture-old-ciphertext", "replacement"));
            org.junit.jupiter.api.Assertions.assertEquals(0, userRepo.upgradeApiKeyCiphertext(saved.getId(), "fixture-old-ciphertext", "other"));
            org.junit.jupiter.api.Assertions.assertEquals("replacement", userRepo.findById(saved.getId()).orElseThrow().getEncryptedApiKey());
        } finally { userRepo.deleteById(saved.getId()); }
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private yagen.waitmydawn.maa.service.LoaderVersionService loaderVersions;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private yagen.waitmydawn.maa.service.KnowledgeDb knowledgeDb;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private yagen.waitmydawn.maa.cache.ModrinthCacheService modrinthCache;

    @Autowired
    private JdbcTemplate jdbc;      // Spring Boot 按主数据源自动装配

    @Autowired
    private UserRepository userRepo;

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ?", String.class, table);
    }

    @Test
    @DisplayName("邮箱三列真的存在于 maa_user（DDL 失败会在这里暴露，而不是等用户登录时 500）")
    void emailColumnsExist() {
        List<String> cols = columnsOf("MAA_USER");
        assertTrue(cols.contains("EMAIL"), "缺 EMAIL 列：" + cols);
        assertTrue(cols.contains("EMAILVERIFIED"),
                "缺 EMAILVERIFIED 列 —— 说明“NOT NULL 无默认值”加列又被 H2 拒了。现有列：" + cols);
        assertTrue(cols.contains("EMAILBOUNDAT"), "缺 EMAILBOUNDAT 列：" + cols);
    }

    @Test
    @DisplayName("EMAIL 有唯一约束（一个邮箱一个号靠数据库兜底，不能只靠代码先查后插）")
    void emailIsUnique() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEX_COLUMNS " +
                "WHERE TABLE_NAME='MAA_USER' AND COLUMN_NAME='EMAIL' AND IS_UNIQUE=TRUE", Integer.class);
        assertTrue(n != null && n > 0, "EMAIL 上没有唯一索引/约束");
    }

    @Test
    @DisplayName("账号号列名对得上：原生 SQL MAX(CAST(accountNumber ...)) 能跑（写错列名这里就炸）")
    void maxAccountNumberQueryWorks() {
        assertDoesNotThrow(() -> userRepo.findMaxAccountNumber());
    }

    @Test
    @DisplayName("按 email 的派生查询能跑（列不存在的话这里会直接抛异常）")
    void emailDerivedQueriesWork() {
        assertDoesNotThrow(() -> userRepo.existsByEmail("definitely-not-registered@example.com"));
        assertDoesNotThrow(() -> userRepo.findByEmail("definitely-not-registered@example.com"));
    }

    @Test
    @DisplayName("知识库规则的认可名单仍绑在旧列 USERS 上（只改字段名不能把现网老数据的名单读丢）")
    void knowledgeRuleVoteColumns() {
        List<String> cols = columnsOf("KNOWLEDGE_RULES");
        assertTrue(cols.contains("USERS"),
                "认可名单沿用的是旧列 USERS，缺了说明 @Column(name=\"users\") 没生效、老票全读不到：" + cols);
        assertFalse(cols.contains("APPROVEDUSERS"),
                "不该另建 APPROVEDUSERS 列（会与 USERS 各存一半、数据看起来凭空少一半）：" + cols);
        assertTrue(cols.contains("DISAPPROVEDUSERS"), "缺 DISAPPROVEDUSERS 列：" + cols);
    }

    @Test
    @DisplayName("会话消息的版本化快照列真的存在（缺列 = 历史版本恢复变成静默失效）")
    void chatMessageSnapshotColumns() {
        List<String> cols = columnsOf("MAA_CHAT_MESSAGE");
        assertTrue(cols.contains("SNAPSHOTID"),
                "缺 SNAPSHOTID 列：消息上带不了版本号，所有历史按钮都会指向最新一版：" + cols);
        assertTrue(cols.contains("PACKSTATE"),
                "缺 PACKSTATE 列：只能恢复模组清单、恢复不了名称与环境：" + cols);
    }
}
