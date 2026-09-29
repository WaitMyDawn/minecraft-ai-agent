package yagen.waitmydawn.maa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.KnowledgeRule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

// 定义 JPA 仓库接口
interface KnowledgeRuleRepo extends JpaRepository<KnowledgeRule, Long> {
    List<KnowledgeRule> findByEnvironment(String environment);

    // 用于爬虫去重检测
    boolean existsByEnvironmentAndModAAndModB(String environment, String modA, String modB);

    // 修复：必须给删除操作显式打上 @Transactional 注解，让它在独立事务中安全执行
    @Transactional
    void deleteBySourceType(KnowledgeRule.SourceType sourceType);
}

/**
 * ADMIN 知识规则完全由外部 JSON 文件管理：maa_db/rules.json。
 * 直接修改该文件后重启即可生效，无需重新编译；
 * 每次启动都会先清空 ADMIN 规则再从 JSON 重新导入，
 * USER_FEEDBACK 与 MODRINTH 来源的规则不受影响。
 */
@Service
public class KnowledgeDb implements CommandLineRunner {
    private final KnowledgeRuleRepo repo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    /**
     * 用户投票/添加规则的串行锁。
     *
     * <p>认可与不认可名单是"读出整列 → 改集合 → 写回整列"，不是数据库层面的原子自增：
     * 两个用户同时投票时，后写的那次会把先写的那次整列覆盖掉（丢票）。所以这类读改写必须串行。
     * 本项目是单实例部署（docker compose 一个服务），实例内的锁就够；将来要多实例，
     * 得换成数据库级原子更新（例如投票另开一张 (rule_id, account) 唯一的表）。
     */
    private final Object voteLock = new Object();

    /** 读层统一排序：环境 → 关系类型 → 主模组 → 指向模组，全部字典序（忽略大小写）。 */
    private static final Comparator<KnowledgeRule> RULE_ORDER = Comparator
            .comparing((KnowledgeRule r) -> blankIfNull(r.environment), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(r -> blankIfNull(r.relationType), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(r -> blankIfNull(r.modA), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(r -> blankIfNull(r.modB), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(r -> r.id == null ? 0L : r.id);

    private static String blankIfNull(String s) {
        return s == null ? "" : s;
    }

    @Value("${maa.rules.path:maa_db/rules.json}")
    private String rulesPath;

    public KnowledgeDb(KnowledgeRuleRepo repo, ObjectMapper objectMapper,
                       PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // 实现 CommandLineRunner 的 run 方法，项目启动成功后会自动调用
    @Override
    public void run(String... args) {
        initAdminRules();
    }

    public void initAdminRules() {
        // 仅清空 ADMIN 来源的规则，USER_FEEDBACK 和 MODRINTH 来源不受影响
        long beforeCount = repo.count();
        repo.deleteBySourceType(KnowledgeRule.SourceType.ADMIN);
        long afterCount = repo.count();
        System.out.println("已清理 ADMIN 规则 (清除 " + (beforeCount - afterCount)
                + " 条, 保留非 ADMIN 规则 " + afterCount + " 条)");

        JsonNode root = readRulesFile();
        int imported = importAdminRules(root);
        System.out.println("ADMIN 知识库已从 " + rulesPath + " 重新导入 " + imported + " 条规则并覆盖写入");
    }

    private JsonNode readRulesFile() {
        Path file = Paths.get(rulesPath);
        try {
            if (!Files.exists(file)) {
                throw new IllegalStateException("缺少 ADMIN 规则文件: " + file.toAbsolutePath()
                        + "（请创建该文件或检查 maa.rules.path 配置）");
            }
            System.out.println("使用规则文件: " + file.toAbsolutePath());
            return objectMapper.readTree(Files.readAllBytes(file));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取规则文件失败: " + file.toAbsolutePath(), e);
        }
    }

    private int importAdminRules(JsonNode root) {
        int imported = 0;
        if (root == null || !root.isObject()) {
            return 0;
        }
        for (String environment : root.propertyNames()) {
            if (environment.startsWith("_")) {
                continue; // 允许存放 _comment 等说明字段
            }
            JsonNode rules = root.get(environment);
            if (rules == null || !rules.isArray()) {
                continue;
            }
            for (JsonNode rule : rules) {
                String modA = rule.path("modA").asText("");
                String modB = rule.path("modB").asText("");
                String relationType = rule.path("relationType").asText("");
                if (modA.isBlank() || modB.isBlank() || relationType.isBlank()) {
                    throw new IllegalStateException(
                            "rules.json 规则缺少 modA/modB/relationType: " + environment);
                }
                int confirmCount = rule.path("confirmCount").asInt(999);
                repo.save(new KnowledgeRule(environment, modA, modB, relationType,
                        KnowledgeRule.SourceType.ADMIN, confirmCount));
                imported++;
            }
        }
        return imported;
    }

    /**
     * 全部规则（知识库管理视图用）。
     *
     * <p>在这里排序而不是在写入时排序：ADMIN 规则每次启动都会从 rules.json 整表重导，
     * 写入顺序完全取决于文件里的条目顺序；只有读层排一次，顺序才不依赖文件、也不怕后续追加。
     */
    public List<KnowledgeRule> getAllRules() {
        return repo.findAll().stream().sorted(RULE_ORDER).toList();
    }

    /** 按条件查找已有规则 (用于用户反馈去重) */
    public Optional<KnowledgeRule> findRule(String environment, String modA, String modB, String relationType) {
        return repo.findAll().stream()
                .filter(r -> r.environment.equals(environment)
                        && r.modA.equals(modA) && r.modB.equals(modB)
                        && r.relationType.equals(relationType))
                .findFirst();
    }

    /** 保存/更新规则 */
    public void save(KnowledgeRule rule) {
        repo.save(rule);
    }

    // ==================== 用户来源：认可 / 不认可 ====================

    /** 用户对一条规则的投票动作。CLEAR = 取消自己的投票（两个按钮都不按下）。 */
    public enum Vote { APPROVE, DISAPPROVE, CLEAR }

    /**
     * 添加规则 / 投票的结果。
     *
     * @param ok              是否写入成功
     * @param error           失败原因（ok=false 时才有值）
     * @param notice          给用户看的提示文案
     * @param approveCount    写入后的认可用户数
     * @param disapproveCount 写入后的不认可用户数
     * @param netScore        写入后的净值（= confirmCount）
     */
    public record FeedbackOutcome(boolean ok, String error, String notice,
                                  int approveCount, int disapproveCount, int netScore) {
        static FeedbackOutcome error(String message) {
            return new FeedbackOutcome(false, message, null, 0, 0, 0);
        }

        static FeedbackOutcome of(String notice, KnowledgeRule rule) {
            return new FeedbackOutcome(true, null, notice,
                    rule.approveCount(), rule.disapproveCount(), rule.confirmCount);
        }
    }

    /**
     * 用户从"模组关系规则编辑器"提交一条依赖/冲突关系。
     *
     * <ul>
     *   <li>新规则 → 建 USER_FEEDBACK 规则，提交者记为<b>认可</b>；</li>
     *   <li>已存在的 USER_FEEDBACK 规则 → 提交者补进认可名单（等价于投一票认可），
     *       若他原本在不认可名单里，则从不认可移到认可；</li>
     *   <li>已存在的官方规则（ADMIN / MODRINTH）→ 拒绝覆盖，保持原行为。</li>
     * </ul>
     *
     * <p>整个过程与 {@link #vote} 共用同一把锁 + 同一个事务：否则"两个人同时添加同一条新规则"
     * 会各自建一条，或者一个人读到旧名单把另一个人的票覆盖掉。
     */
    public FeedbackOutcome addUserRule(String environment, String modA, String modB,
                                       String relationType, String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) return FeedbackOutcome.error("账号缺失");
        synchronized (voteLock) {
            return txTemplate.execute(status -> {
                var existingOpt = findRule(environment, modA, modB, relationType);
                if (existingOpt.isEmpty()) {
                    KnowledgeRule rule = new KnowledgeRule(environment, modA, modB, relationType,
                            KnowledgeRule.SourceType.USER_FEEDBACK, 1, accountNumber);
                    repo.save(rule);
                    return FeedbackOutcome.of("规则已添加（你已为它投出 1 票认可）", rule);
                }
                KnowledgeRule rule = existingOpt.get();
                if (rule.sourceType != KnowledgeRule.SourceType.USER_FEEDBACK) {
                    return FeedbackOutcome.error("该规则已存在且为官方规则，无法覆盖");
                }
                LinkedHashSet<String> approve = rule.approvedUserSet();
                if (approve.contains(accountNumber)) {
                    return FeedbackOutcome.of("你已经添加过此规则，无需重复投票", rule);
                }
                LinkedHashSet<String> disapprove = rule.disapprovedUserSet();
                disapprove.remove(accountNumber);   // 本来是"不认可" → 现在改投认可
                approve.add(accountNumber);
                applyVotes(rule, approve, disapprove);
                repo.save(rule);
                return FeedbackOutcome.of("规则已更新：" + describeVotes(rule), rule);
            });
        }
    }

    /**
     * 在知识库管理视图里对一条 USER_FEEDBACK 规则投认可 / 不认可 / 取消投票。
     *
     * <p>两个按钮互斥：投认可会把自己从"不认可"里摘掉，反之亦然；CLEAR 则两边都摘掉。
     * 官方规则（ADMIN / MODRINTH）不可投票。
     */
    public FeedbackOutcome vote(Long ruleId, String accountNumber, Vote vote) {
        if (accountNumber == null || accountNumber.isBlank()) return FeedbackOutcome.error("账号缺失");
        if (ruleId == null) return FeedbackOutcome.error("规则不存在");
        Vote action = vote == null ? Vote.CLEAR : vote;
        synchronized (voteLock) {
            return txTemplate.execute(status -> {
                var opt = repo.findById(ruleId);
                if (opt.isEmpty()) return FeedbackOutcome.error("规则不存在");
                KnowledgeRule rule = opt.get();
                if (rule.sourceType != KnowledgeRule.SourceType.USER_FEEDBACK) {
                    return FeedbackOutcome.error("官方规则不可投票");
                }
                LinkedHashSet<String> approve = rule.approvedUserSet();
                LinkedHashSet<String> disapprove = rule.disapprovedUserSet();
                switch (action) {
                    case APPROVE -> {
                        disapprove.remove(accountNumber);
                        approve.add(accountNumber);
                    }
                    case DISAPPROVE -> {
                        approve.remove(accountNumber);
                        disapprove.add(accountNumber);
                    }
                    case CLEAR -> {
                        approve.remove(accountNumber);
                        disapprove.remove(accountNumber);
                    }
                }
                applyVotes(rule, approve, disapprove);
                repo.save(rule);
                return FeedbackOutcome.of("已记录你的投票：" + describeVotes(rule), rule);
            });
        }
    }

    /** 把两个名单和净值写回规则对象（净值 = 认可数 − 不认可数）。 */
    private static void applyVotes(KnowledgeRule rule, LinkedHashSet<String> approve,
                                   LinkedHashSet<String> disapprove) {
        rule.approvedUsers = KnowledgeRule.joinUsers(approve);
        rule.disapprovedUsers = KnowledgeRule.joinUsers(disapprove);
        rule.confirmCount = approve.size() - disapprove.size();
    }

    private static String describeVotes(KnowledgeRule rule) {
        return "净值 " + rule.confirmCount + "（认可 " + rule.approveCount()
                + " / 不认可 " + rule.disapproveCount() + "）"
                + (rule.isEffective() ? " ✅ 已生效" : " ⏳ 未生效");
    }

    /**
     * 获取某个环境下生效的规则。
     *
     * <p>{@code excludeSource} <b>故意不给默认值</b>：哪些来源用、哪些来源排除，直接决定
     * "谁进包"和"画哪条线"，必须由调用方当场说清楚。历史上正是因为这里有一个不带参数的
     * 重载，依赖穿透（调不带参数的）和预览画边（调带参数的）拿了两份不同的规则集，
     * 才出现"用户规则把节点拉进来了、却不画那条线"。
     *
     * @param excludeSource 要排除的来源，null = 全都要
     */
    public List<KnowledgeRule> getActiveRules(String environment, KnowledgeRule.SourceType excludeSource) {
        return repo.findByEnvironment(environment).stream()
                .filter(KnowledgeRule::isEffective)
                .filter(r -> excludeSource == null || r.sourceType != excludeSource)
                .toList();
    }

    // 供爬虫写入使用
    public void saveModrinthRule(String env, String modA, String modB, String relation) {
        if (!repo.existsByEnvironmentAndModAAndModB(env, modA, modB)) {
            repo.save(new KnowledgeRule(env, modA, modB, relation, KnowledgeRule.SourceType.MODRINTH, 100));
        }
    }
}
