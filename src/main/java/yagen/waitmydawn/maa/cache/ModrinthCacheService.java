package yagen.waitmydawn.maa.cache;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * ModrinthCache 运行时内存加载器（M1a）。
 *
 * <p>启动时若数据库存在则把 modrinth_cache 全量读入内存，并构建
 * slug 主表 / 类别索引 / loader 索引；聚合 current_intents 时只认 19 类，
 * 其余标签忽略，库外或无 19 类的模组计入 unknown。
 *
 * <p>数据库缺失或读取失败时服务保持"不可用"状态，调用方回退 live 检索。
 */
@Service
public class ModrinthCacheService {

    private static final Logger log = LoggerFactory.getLogger(ModrinthCacheService.class);

    /** 统一 19 类规范名（与库内存储的 API slug 一致） */
    public static final Set<String> CATEGORIES = Set.of(
            "adventure", "cursed", "decoration", "economy", "equipment", "food",
            "game-mechanics", "library", "magic", "management", "minigame", "mobs",
            "optimization", "social", "storage", "technology", "transportation",
            "utility", "worldgen");

    /** 内存条目（versions 列暂不参与 M1a 聚合，后续 M1b 使用） */
    public record ModEntry(String slug, String title, String description,
                           long downloads, List<String> categories, List<String> loaders) {
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${maa.modrinth-cache.db-path:maa_db/modrinth_cache.mv.db}")
    private String dbPath;

    private volatile boolean available = false;
    private int size = 0;
    private Map<String, ModEntry> bySlug = Map.of();
    private List<ModEntry> allEntries = List.of();
    private Map<String, List<ModEntry>> byCategory = Map.of();
    private Map<String, List<ModEntry>> byLoader = Map.of();
    /** slug -> (loader -> 已确认存在的 MC 版本集合)，来自 versions 列 + 运行时回填 */
    private Map<String, Map<String, Set<String>>> knownVersions = new HashMap<>();

    @PostConstruct
    void init() {
        load();
    }

    public boolean isAvailable() {
        return available;
    }

    public int size() {
        return size;
    }

    public ModEntry find(String slug) {
        return bySlug.get(slug);
    }

    public boolean supportsLoader(String slug, String loader) {
        ModEntry e = bySlug.get(slug);
        return e != null && e.loaders().contains(loader.toLowerCase());
    }

    /** 取某 loader 的候选列表（桶内已按 downloads 降序），供平替守卫做强词/词缀打分 */
    public List<ModEntry> candidatesByLoader(String loader) {
        return byLoader.getOrDefault(loader.toLowerCase(), List.of());
    }

    private void load() {
        Path file = Paths.get(dbPath);
        if (!Files.exists(file)) {
            log.warn("ModrinthCache 数据库不存在 ({}), 缓存不可用, 将回退 live 检索", file.toAbsolutePath());
            return;
        }
        try {
            Class.forName("org.h2.Driver");
            String base = file.toAbsolutePath().toString().replace('\\', '/');
            if (base.endsWith(".mv.db")) {
                base = base.substring(0, base.length() - ".mv.db".length());
            }
            String url = "jdbc:h2:file:" + base + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE";

            Map<String, ModEntry> loaded = new HashMap<>();
            List<ModEntry> entries = new ArrayList<>();
            try (Connection conn = DriverManager.getConnection(url, "sa", "");
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT slug,title,categories,description,downloads,loaders,versions FROM modrinth_cache")) {
                while (rs.next()) {
                    String slug = rs.getString(1);
                    List<String> cats = parseArray(rs.getString(3));
                    List<String> lods = parseArray(rs.getString(6));
                    ModEntry entry = new ModEntry(
                            slug,
                            rs.getString(2),
                            rs.getString(4),
                            rs.getLong(5),
                            cats,
                            lods);
                    loaded.put(slug, entry);
                    entries.add(entry);
                    parseVersionsInto(slug, rs.getString(7));
                }
            }
            // 桶内按 downloads 降序预排序，后续检索/截断直接取前缀
            entries.sort((a, b) -> Long.compare(b.downloads(), a.downloads()));

            Map<String, List<ModEntry>> cats = new HashMap<>();
            Map<String, List<ModEntry>> lods = new HashMap<>();
            for (ModEntry e : entries) {
                for (String c : e.categories()) {
                    if (CATEGORIES.contains(c)) cats.computeIfAbsent(c, k -> new ArrayList<>()).add(e);
                }
                for (String l : e.loaders()) lods.computeIfAbsent(l, k -> new ArrayList<>()).add(e);
            }

            bySlug = Map.copyOf(loaded);
            allEntries = List.copyOf(entries);
            byCategory = cats;
            byLoader = lods;
            size = entries.size();
            available = true;
            log.info("ModrinthCache 已加载: {} 条模组, 类别索引 {} 组, loader 索引 {} 组",
                    size, cats.size(), lods.size());
        } catch (Exception e) {
            available = false;
            log.error("ModrinthCache 加载失败: {}", e.getMessage(), e);
        }
    }

    private void parseVersionsInto(String slug, String json) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode obj = mapper.readTree(json);
            if (!obj.isObject()) return;
            Map<String, Set<String>> byLoaderMap = new HashMap<>();
            obj.properties().forEach(p -> {
                Set<String> vers = new HashSet<>();
                if (p.getValue().isArray()) {
                    p.getValue().forEach(v -> {
                        String s = v.asText("");
                        if (!s.isEmpty()) vers.add(s);
                    });
                }
                if (!vers.isEmpty()) byLoaderMap.put(p.getKey(), vers);
            });
            if (!byLoaderMap.isEmpty()) knownVersions.put(slug, byLoaderMap);
        } catch (Exception e) {
            log.warn("versions 解析失败 slug={}: {}", slug, e.getMessage());
        }
    }

    /** 已知版本命中：versions 记录中存在 loader+mc，则直接视为兼容（无需 live） */
    public boolean hasKnownVersion(String slug, String loader, String mcVersion) {
        Map<String, Set<String>> byLoaderMap = knownVersions.get(slug);
        if (byLoaderMap == null) return false;
        Set<String> vers = byLoaderMap.get(loader.toLowerCase());
        return vers != null && vers.contains(mcVersion);
    }

    /**
     * 校验通过后回填 versions（内存 + 写回 H2）。
     * 幂等：同 slug+loader 追加小版本，不重复写入。
     */
    public synchronized void recordVersion(String slug, String loader, String mcVersion) {
        String l = loader.toLowerCase();
        Map<String, Set<String>> byLoaderMap =
                knownVersions.computeIfAbsent(slug, k -> new HashMap<>());
        Set<String> vers = byLoaderMap.computeIfAbsent(l, k -> new HashSet<>());
        if (!vers.add(mcVersion)) {
            return; // 已存在，无需写库
        }
        persistVersions(slug, byLoaderMap);
    }

    private void persistVersions(String slug, Map<String, Set<String>> byLoaderMap) {
        try {
            Class.forName("org.h2.Driver");
            String base = Paths.get(dbPath).toAbsolutePath().toString().replace('\\', '/');
            if (base.endsWith(".mv.db")) {
                base = base.substring(0, base.length() - ".mv.db".length());
            }
            String url = "jdbc:h2:file:" + base + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE";
            tools.jackson.databind.node.ObjectNode obj = mapper.createObjectNode();
            byLoaderMap.forEach((loader, vers) -> {
                var arr = obj.putArray(loader);
                vers.stream().sorted().forEach(arr::add);
            });
            try (Connection conn = DriverManager.getConnection(url, "sa", "");
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE modrinth_cache SET versions = ? WHERE slug = ?")) {
                ps.setString(1, mapper.writeValueAsString(obj));
                ps.setString(2, slug);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.warn("versions 写库失败 slug={}: {}（内存已更新，重启后可能丢失）", slug, e.getMessage());
        }
    }

    /** 本地关键词检索：类别桶内按 slug/title/description 包含匹配，下载量过滤，返回下载量降序 */
    public List<ModEntry> matchByKeyword(String category, String keyword, long maxDownloads, int limit) {
        List<ModEntry> pool = (category != null && byCategory.containsKey(category))
                ? byCategory.get(category)
                : allEntries;
        String kw = keyword.trim().toLowerCase();
        if (kw.isEmpty()) return List.of();
        List<ModEntry> result = new ArrayList<>();
        for (ModEntry e : pool) {
            if (e.downloads() > maxDownloads) continue;
            String slug = e.slug().toLowerCase();
            String title = e.title().toLowerCase();
            String desc = e.description().toLowerCase();
            if (slug.contains(kw) || title.contains(kw) || desc.contains(kw)) {
                result.add(e);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    /** 本地附属检索：与核心同 loader、标题/简介含核心关键字的候选（粗召回，交给校验与 Critic） */
    public List<ModEntry> findAddons(String coreSlug, String loader, int limit) {
        List<ModEntry> pool = byLoader.getOrDefault(loader.toLowerCase(), List.of());
        String core = coreSlug.toLowerCase().replace('-', ' ');
        String[] tokens = core.split("\\s+");
        List<ModEntry> result = new ArrayList<>();
        for (ModEntry e : pool) {
            if (e.slug().equalsIgnoreCase(coreSlug)) continue;
            String hay = (e.title() + " " + e.slug()).toLowerCase();
            boolean hit = false;
            for (String t : tokens) {
                if (t.length() >= 3 && hay.contains(t)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                result.add(e);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    private List<String> parseArray(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isBlank()) return result;
        try {
            JsonNode arr = mapper.readTree(json);
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String v = n.asText("");
                    if (!v.isEmpty()) result.add(v);
                }
            }
        } catch (Exception e) {
            log.warn("ModrinthCache JSON 解析失败: {}", json);
        }
        return result;
    }

    /**
     * 汇总当前模组清单的类别画像（current_intents）。
     *
     * @param rawSlugs 逗号分隔的 slug 原文（前端 currentMods）
     * @return 形如 "总数:32 | technology:8:25% | ... | unknown:2:6%" 的文本；
     *         缓存不可用或清单为空时返回 null
     */
    public String summarizePack(String rawSlugs) {
        if (!available || rawSlugs == null || rawSlugs.isBlank()) return null;
        List<String> slugs = new ArrayList<>();
        for (String s : rawSlugs.split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !slugs.contains(t)) slugs.add(t);
        }
        if (slugs.isEmpty()) return null;

        int total = slugs.size();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int unknown = 0;
        for (String slug : slugs) {
            ModEntry entry = bySlug.get(slug);
            if (entry == null || entry.categories().isEmpty()) {
                unknown++;
                continue;
            }
            boolean matched = false;
            for (String c : entry.categories()) {
                if (CATEGORIES.contains(c)) {
                    counts.merge(c, 1, Integer::sum);
                    matched = true;
                }
            }
            if (!matched) unknown++;
        }

        StringBuilder sb = new StringBuilder("总数:").append(total);
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> sb.append(" | ")
                        .append(e.getKey()).append(':').append(e.getValue()).append(':')
                        .append(Math.round(100.0 * e.getValue() / total)).append('%'));
        if (unknown > 0) {
            sb.append(" | unknown:").append(unknown).append(':')
                    .append(Math.round(100.0 * unknown / total)).append('%');
        }
        return sb.toString();
    }
}
