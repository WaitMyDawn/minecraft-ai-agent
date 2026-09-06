package yagen.waitmydawn.maa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.KnowledgeRule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    @Value("${maa.rules.path:maa_db/rules.json}")
    private String rulesPath;

    public KnowledgeDb(KnowledgeRuleRepo repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
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

    // 获取当前环境下所有生效的规则
    public List<KnowledgeRule> getActiveRules(String environment) {
        return repo.findByEnvironment(environment).stream()
                .filter(r -> r.confirmCount >= 3 || r.sourceType == KnowledgeRule.SourceType.ADMIN || r.sourceType == KnowledgeRule.SourceType.MODRINTH)
                .toList();
    }

    public List<KnowledgeRule> getAllRules() {
        return repo.findAll();
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

    /** 获取活跃规则，可按 sourceType 过滤 (null=全部) */
    public List<KnowledgeRule> getActiveRules(String environment, KnowledgeRule.SourceType excludeSource) {
        return repo.findByEnvironment(environment).stream()
                .filter(r -> r.confirmCount >= 3 || r.sourceType == KnowledgeRule.SourceType.ADMIN
                        || r.sourceType == KnowledgeRule.SourceType.MODRINTH)
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
