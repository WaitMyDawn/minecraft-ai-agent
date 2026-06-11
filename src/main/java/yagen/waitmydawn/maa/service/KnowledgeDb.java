package yagen.waitmydawn.maa.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import yagen.waitmydawn.maa.model.KnowledgeRule;

import java.util.List;
import java.util.Optional;

// 定义 JPA 仓库接口
interface KnowledgeRuleRepo extends JpaRepository<KnowledgeRule, Long> {
    List<KnowledgeRule> findByEnvironment(String environment);

    // 用于爬虫去重检查
    boolean existsByEnvironmentAndModAAndModB(String environment, String modA, String modB);

    // 🔥 修复：必须给删除操作显式打上 @Transactional 注解，让它在独立事务中安全执行
    @Transactional
    void deleteBySourceType(KnowledgeRule.SourceType sourceType);
}

@Service
// 🔥 修复：实现 CommandLineRunner 接口，确保在 Spring 完全启动后再执行数据库覆盖
public class KnowledgeDb implements CommandLineRunner {
    private final KnowledgeRuleRepo repo;

    public KnowledgeDb(KnowledgeRuleRepo repo) {
        this.repo = repo;
    }

    // 实现了 CommandLineRunner 的 run 方法，项目启动成功后会自动调用此方法
    @Override
    public void run(String... args) {
        initAdminRules();
    }

    // 去掉了 @PostConstruct，避免过早执行
    public void initAdminRules() {
        // 🔥 仅覆盖 ADMIN 来源的规则，USER_FEEDBACK 和 MODRINTH 来源不受影响
        long beforeCount = repo.count();
        repo.deleteBySourceType(KnowledgeRule.SourceType.ADMIN);
        long afterCount = repo.count();
        System.out.println("🧹 已清理 ADMIN 规则 (清除 " + (beforeCount - afterCount) + " 条), 保留非 ADMIN 规则 " + afterCount + " 条");

        // 2. 重新载入最新的管理员代码配置
        String env = "neoforge-1.21.1";
        repo.save(new KnowledgeRule(env, "irons-spells-n-spellbooks", "geckolib", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "irons-spells-n-spellbooks", "curios", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "irons-spells-n-spellbooks", "playeranimator", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "irons-spells-n-spellbooks", "irons-lib", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "regions-unexplored", "lithostitched", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "regions-unexplored", "biolith", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "l_enders-cataclysm", "lionfish-api", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "malum", "lodestonelib", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "quark", "zeta", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "supplementaries", "moonlight", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "fzzy-config", "kotlin-for-forge", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "item-highlighter", "iceberg", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "relics-mod", "octo-lib", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "vanillabackport", "platform", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "numismatic-bounties", "bountiful", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "esf", "entity-model-features", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "esf", "entitytexturefeatures", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "tensura-reincarnated", "manascore", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "tensura-reincarnated", "geckolib", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "tensura-reincarnated", "terrablender", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "twilight-delight", "twilightforest", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "expanded-delight", "farmers-delight", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "alshanexs-familiars", "familiarslib", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "bountiful", "kambrik", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "sodium-options-api", "reeses-sodium-options", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "dungeons-content-plus", "dungeons-content", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "pastel-mod", "databank", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "pastel-mod", "exclusions-lib", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "spells-gone-wrong", "jinxedlib", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "claim-my-land", "gottschcore", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "deeper-and-darker-spellbooks", "deeperdarker", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "deeper-and-darker-spellbooks", "irons-spells-n-spellbooks", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "compat-structure", "compat-api", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "epic-samurais", "terrablender", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "epic-samurais", "oh-the-trees-youll-grow", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "tensura-not-enough-bosses", "tensura-reincarnated", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "tensura-compat-open-parties-and-claims", "tensura-reincarnated", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "tr-addon", "tensura-reincarnated", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "tensura-better-subordinates", "tensura-reincarnated", "DEPENDS_ON", KnowledgeRule.SourceType.ADMIN, 999));

        repo.save(new KnowledgeRule(env, "optifine", "embeddium", "CONFLICTS_WITH", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "sodium", "embeddium", "CONFLICTS_WITH", KnowledgeRule.SourceType.ADMIN, 999));
        repo.save(new KnowledgeRule(env, "shoulder-surfing-reloaded", "better-third-person", "CONFLICTS_WITH", KnowledgeRule.SourceType.ADMIN, 999));

        System.out.println("💾 管理员硬编码知识库已在系统就绪后重新加载并覆盖写入！");
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

    /** 获取活跃规则，可按 sourceType 过滤 (null=全部, 用于排除某类来源) */
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