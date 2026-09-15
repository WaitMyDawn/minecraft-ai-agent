package yagen.waitmydawn.maa.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 模组别名词典（中文黑话 → Modrinth slug）的唯一权威源。
 *
 * <p>此前这份词典有两份副本：Architect 系统提示词里写死的 8 条，以及
 * {@code ArchitectPackTools.ALIASES} 里的 9 条 Map——加一个词要改两处、还要重新编译。
 * 现在统一由 {@code maa_db/aliases.json} 管理：与 {@code rules.json} 同样的运维方式，
 * 改完文件重启即可生效，无需改代码。
 *
 * <p>文件缺失或解析失败时回退到内置兜底词典，保证功能不会因为一个配置问题整体消失。
 */
@Service
public class ModAliasRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModAliasRegistry.class);

    /** 内置兜底词典（与 maa_db/aliases.json 的初始内容一致，仅用于文件不可用时兜底） */
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("铁魔法", "irons-spells-n-spellbooks"),
            Map.entry("灾变", "l_enders-cataclysm"),
            Map.entry("冰火传说社区版", "iceandfire-ce"),
            Map.entry("地牢浮现之时", "when-dungeons-arise"),
            Map.entry("农夫乐事", "farmers-delight"),
            Map.entry("机械动力", "create"),
            Map.entry("rs存储", "refined-storage"),
            Map.entry("应用能源2", "ae2"),
            Map.entry("ae2", "ae2"));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${maa.aliases.path:maa_db/aliases.json}")
    private String aliasesPath;

    /** 加载后的词典；键已统一小写，值是规范 slug。volatile 保证 reload 后其它线程立刻可见 */
    private volatile Map<String, String> aliases = Map.of();

    @PostConstruct
    void init() {
        reload();
    }

    /** 重新读取词典文件（改完 aliases.json 调一次即可生效，无需重启进程） */
    public void reload() {
        Path file = Paths.get(aliasesPath);
        if (!Files.exists(file)) {
            log.warn("别名词典文件不存在({}), 使用内置兜底词典 {} 条", file.toAbsolutePath(), DEFAULTS.size());
            aliases = DEFAULTS;
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readAllBytes(file));
            JsonNode node = root.path("aliases");
            // LinkedHashMap 保留文件里的书写顺序，提示词输出才稳定
            Map<String, String> loaded = new LinkedHashMap<>();
            if (node.isObject()) {
                node.properties().forEach(p -> {
                    String key = p.getKey() == null ? "" : p.getKey().trim().toLowerCase(Locale.ROOT);
                    String slug = p.getValue().asText("").trim();
                    if (!key.isEmpty() && !slug.isEmpty()) loaded.put(key, slug);
                });
            }
            if (loaded.isEmpty()) {
                log.warn("别名词典 {} 里没有有效条目, 使用内置兜底词典", file.toAbsolutePath());
                aliases = DEFAULTS;
            } else {
                aliases = Collections.unmodifiableMap(loaded);
                log.info("别名词典已加载: {} 条 ({})", loaded.size(), file.toAbsolutePath());
            }
        } catch (Exception e) {
            log.error("别名词典解析失败({}), 使用内置兜底词典: {}", file.toAbsolutePath(), e.getMessage());
            aliases = DEFAULTS;
        }
    }

    /**
     * 归一化：去空格 + 转小写后查词典。
     *
     * @return 命中的规范 slug；未命中时返回小写去空格的原文（保持原有语义，不做猜测）
     */
    public String resolve(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.isEmpty()) return "";
        String lower = t.toLowerCase(Locale.ROOT);
        String hit = aliases.get(lower);
        return hit != null ? hit : lower;
    }

    /** 全部词条（只读，键为小写别名） */
    public Map<String, String> all() {
        return aliases;
    }

    public int size() {
        return aliases.size();
    }

    /**
     * 生成注入对话上下文的【中文黑话词典】区块。
     * 放在用户指令附近，比写死在系统提示词里更容易被遵循。
     */
    public String promptBlock() {
        Map<String, String> snapshot = aliases;
        if (snapshot.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【中文黑话词典】（用户说到下列说法时，必须直接使用对应的英文 slug，不要自行翻译）\n");
        snapshot.forEach((k, v) -> sb.append(k).append(" -> ").append(v).append("\n"));
        return sb.toString();
    }
}
