package yagen.waitmydawn.maa.model;

import jakarta.persistence.*;

@Entity
@Table(name = "knowledge_rules")
public class KnowledgeRule {

    public enum SourceType {
        ADMIN,          // 管理员人工录入（最高优先级，不可被爬虫覆盖）
        MODRINTH,       // 从 Modrinth 官方同步
        USER_FEEDBACK   // 用户反馈累计
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String environment; // 如 neoforge-1.21.1
    public String modA;        // 需求者 (如 irons-spellbooks)
    public String modB;        // 被依赖者 (如 geckolib)
    public String relationType;// "DEPENDS_ON" 或 "CONFLICTS"
    @Enumerated(EnumType.STRING)
    public SourceType sourceType;
    public int confirmCount;   // 信任度统计

    @Column(length = 5000)
    public String users;       // 逗号分隔的账号列表 (USER_FEEDBACK 来源), ADMIN/MODRINTH 为空

    // JPA 必须的无参构造函数
    public KnowledgeRule() {}

    public KnowledgeRule(String environment, String modA, String modB, String relationType, SourceType sourceType, int confirmCount) {
        this(environment, modA, modB, relationType, sourceType, confirmCount, null);
    }

    public KnowledgeRule(String environment, String modA, String modB, String relationType, SourceType sourceType, int confirmCount, String users) {
        this.environment = environment;
        this.modA = modA;
        this.modB = modB;
        this.relationType = relationType;
        this.sourceType = sourceType;
        this.confirmCount = confirmCount;
        this.users = users;
    }
}