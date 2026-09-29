package yagen.waitmydawn.maa.model;

import jakarta.persistence.*;

import java.util.Collection;
import java.util.LinkedHashSet;

@Entity
@Table(name = "knowledge_rules")
public class KnowledgeRule {

    public enum SourceType {
        ADMIN,          // 管理员人工录入（最高优先级，不可被爬虫覆盖）
        MODRINTH,       // 从 Modrinth 官方同步
        USER_FEEDBACK   // 用户反馈累计
    }

    /** 用户来源规则的生效门槛：认可用户数 − 不认可用户数 达到这个值才算生效。 */
    public static final int EFFECTIVE_SCORE = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String environment; // 如 neoforge-1.21.1
    public String modA;        // 需求者 (如 irons-spellbooks)
    public String modB;        // 被依赖者 (如 geckolib)
    public String relationType;// "DEPENDS_ON" 或 "CONFLICTS"
    @Enumerated(EnumType.STRING)
    public SourceType sourceType;

    /**
     * 用户来源的信任度：<b>净值</b> = 认可用户数 − 不认可用户数。
     *
     * <p>ADMIN / MODRINTH 没有投票这一说，这里存一个远超门槛的固定值（999 / 100），
     * 于是「净值 ≥ {@link #EFFECTIVE_SCORE} 才生效」这条判定对三种来源统一成立。
     */
    public int confirmCount;

    /**
     * 认可该规则的用户（accountNumber，逗号分隔）。
     *
     * <p>列名刻意保留为 {@code users}：这一列在旧版本里就叫 users，存的正是"添加过这条规则的人"，
     * 语义恰好等于现在的"认可"。显式绑定旧列名，H2 里已有的这列数据不用迁移、也不会被废弃。
     */
    @Column(name = "users", length = 5000)
    public String approvedUsers;

    /**
     * 不认可该规则的用户（accountNumber，逗号分隔）。
     *
     * <p>与 {@link #approvedUsers} 互斥：同一个账号同时出现在两个列表里就是 bug，
     * 写入口会先把旧的一边移除、再加到新的一边（投票是"二选一"而不是"两票"）。
     */
    @Column(length = 5000)
    public String disapprovedUsers;

    // JPA 必须的无参构造函数
    public KnowledgeRule() {}

    public KnowledgeRule(String environment, String modA, String modB, String relationType, SourceType sourceType, int confirmCount) {
        this(environment, modA, modB, relationType, sourceType, confirmCount, null);
    }

    public KnowledgeRule(String environment, String modA, String modB, String relationType, SourceType sourceType, int confirmCount, String approvedUsers) {
        this.environment = environment;
        this.modA = modA;
        this.modB = modB;
        this.relationType = relationType;
        this.sourceType = sourceType;
        this.confirmCount = confirmCount;
        this.approvedUsers = approvedUsers;
    }

    // ==================== 认可 / 不认可 ====================

    /** 认可用户集合（去重，保持写入顺序） */
    public LinkedHashSet<String> approvedUserSet() {
        return splitUsers(approvedUsers);
    }

    /** 不认可用户集合（去重，保持写入顺序） */
    public LinkedHashSet<String> disapprovedUserSet() {
        return splitUsers(disapprovedUsers);
    }

    public int approveCount() {
        return approvedUserSet().size();
    }

    public int disapproveCount() {
        return disapprovedUserSet().size();
    }

    /**
     * 这条规则当前是否生效。官方来源（ADMIN / MODRINTH）恒生效；
     * 用户来源看净值是否达到 {@link #EFFECTIVE_SCORE}。
     */
    public boolean isEffective() {
        return sourceType == SourceType.ADMIN || sourceType == SourceType.MODRINTH
                || confirmCount >= EFFECTIVE_SCORE;
    }

    /**
     * 指定账号对这条规则投过的票：{@code APPROVE} / {@code DISAPPROVE} / {@code null}（没投过）。
     * 前端用它把按钮画成"按下去"的样子。
     */
    public String voteOf(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) return null;
        if (approvedUserSet().contains(accountNumber)) return "APPROVE";
        if (disapprovedUserSet().contains(accountNumber)) return "DISAPPROVE";
        return null;
    }

    /** 把"逗号分隔的账号列表"解析成集合；空/空白项直接丢弃，避免出现空账号把计数抬上去。 */
    public static LinkedHashSet<String> splitUsers(String csv) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    /** 集合 → 逗号分隔字符串；空集合写成空串（而不是 null，省得调用方到处判空）。 */
    public static String joinUsers(Collection<String> users) {
        if (users == null || users.isEmpty()) return "";
        return String.join(",", users);
    }
}
