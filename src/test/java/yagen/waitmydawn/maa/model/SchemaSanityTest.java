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

/**
 * 数据库形态体检：**真连库跑一遍**，不靠 mock。
 *
 * <p>为什么必须有这个测试：上面两轮一共踩了三个只有真库才暴露的坑 ——
 * <ol>
 *   <li>原生 SQL 里列名写成 {@code account_number}，实际是 {@code ACCOUNTNUMBER}（camelCase，
 *       因为本项目在 {@code MainDataSourceConfig} 里手搓 EntityManagerFactory，没启用 Spring Boot 的
 *       下划线命名策略）。走 mock 的单元测试完全看不出来，真机上"第一次注册"就会抛 Column not found；</li>
 *   <li>{@code emailVerified} 因为"NOT NULL 且无默认值"加不进已有数据的表，那一列压根没建成；</li>
 *   <li>新建列的 DDL 失败时 Hibernate 只打一条 WARN 就继续启动，应用看起来是好的，直到有人真的查询。</li>
 * </ol>
 * 所以这里断言三件事：新增列/约束真的存在、按字段名派生的查询能跑通、原生 SQL 能跑通。
 *
 * <p>用的是项目自带的数据源（本地开发库），只做只读查询；结构变更本来就会在上下文启动时由
 * {@code ddl-auto=update} 完成，和跑一次应用没有区别。
 */
@SpringBootTest
class SchemaSanityTest {

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
}
