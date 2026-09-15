package yagen.waitmydawn.maa.cache;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.CategoryRegistry;
import yagen.waitmydawn.maa.model.RetrievalScorer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 统一 19 类规范名（唯一权威源见 {@link yagen.waitmydawn.maa.model.CategoryRegistry}） */
    public static final Set<String> CATEGORIES = CategoryRegistry.CATEGORY_SET;

    /** 内存条目（versions 列暂不参与 M1a 聚合，后续 M1b 使用） */
    public record ModEntry(String slug, String title, String description,
                           long downloads, List<String> categories, List<String> loaders) {
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${maa.modrinth-cache.db-path:maa_db/modrinth_cache.mv.db}")
    private String dbPath;

    /** 本地版本预过滤开关：一旦发现误杀可立即关掉（见 application.properties） */
    @Value("${maa.cache.game-version-prefilter:true}")
    private boolean gameVersionPrefilterEnabled;

    private volatile boolean available = false;
    /** game_versions 列是否可用（老库未重建时为 false，此时自动跳过本地预过滤） */
    private volatile boolean gameVersionsReady = false;
    private int size = 0;
    /** game_versions 只用于"否定"，因此目标版本必须是正式版格式（1.20 / 1.21.1） */
    private static final java.util.regex.Pattern RELEASE_VERSION =
            java.util.regex.Pattern.compile("^\\d+\\.\\d+(\\.\\d+)?$");
    private Map<String, ModEntry> bySlug = Map.of();
    private List<ModEntry> allEntries = List.of();
    private Map<String, List<ModEntry>> byCategory = Map.of();
    private Map<String, List<ModEntry>> byLoader = Map.of();
    /**
     * slug -> (loader -> 已确认存在的 MC 版本集合)，来自 versions 列 + 运行时回填。
     *
     * <p>运行期会被虚拟线程并发读写（recordVersion 写、hasKnownVersion 读），
     * 因此内外层容器与内层集合全部使用并发实现，避免 HashMap 并发读写的丢更新/结构损坏。
     */
    private final Map<String, Map<String, Set<String>>> knownVersions = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        load();
    }

    public boolean isAvailable() {
        return available;
    }

    /** 本地版本预过滤是否可用（开关开启 + 缓存可用 + game_versions 列有数据） */
    public boolean isGameVersionFilterReady() {
        return gameVersionPrefilterEnabled && available && gameVersionsReady;
    }

    public int size() {
        return size;
    }

    /**
     * 本地版本预过滤 —— <b>只做否定</b>，用于在发请求之前剔除"确定不支持目标 MC 版本"的候选。
     *
     * <p>数据来源是建库时从 Modrinth search 响应直接落库的 game_versions
     * （项目级支持版本，不产生额外请求，见 ModrinthCacheBuilder#collectReleaseVersions）。
     * 它只是跨 loader 的并集，所以<b>只能用来排除、不能用来放行</b>：
     * "支持 1.21.1"不代表"neoforge 下有 1.21.1 构建"，后者仍需运行时惰性探测。
     *
     * <p>安全边界（任何一条不满足就原样保留，绝不误杀）：
     * <ul>
     *   <li>库里查不到该 slug（新发布/未收录）</li>
     *   <li>game_versions 为 NULL 或空（老库未重建）</li>
     *   <li>目标版本不是正式版格式（如快照 24w14a）</li>
     *   <li>查询异常（fail-open）</li>
     * </ul>
     *
     * @return 经过否定过滤后应保留的 slug 子集
     */
    public Set<String> filterByGameVersion(Collection<String> slugs, String mcVersion) {
        Set<String> kept = new LinkedHashSet<>(slugs);
        if (!isGameVersionFilterReady() || kept.isEmpty()) return kept;
        String mc = mcVersion == null ? "" : mcVersion.trim();
        if (!RELEASE_VERSION.matcher(mc).matches()) return kept;

        List<String> list = new ArrayList<>(kept);
        StringBuilder sql = new StringBuilder("SELECT slug, game_versions FROM modrinth_cache WHERE slug IN (");
        for (int i = 0; i < list.size(); i++) {
            sql.append(i == 0 ? "?" : ",?");
        }
        sql.append(")");
        // JSON 数组形式的精确匹配：带引号可避免 "1.21.1" 误匹配 "1.21.10"
        String needle = "\"" + mc + "\"";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String s : list) ps.setString(idx++, s);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String slug = rs.getString(1);
                    String gameVersions = rs.getString(2);
                    if (gameVersions == null || gameVersions.isBlank()) continue; // 无数据 → 保留
                    if (!gameVersions.contains(needle)) kept.remove(slug);        // 明确不含 → 剔除
                }
            }
        } catch (Exception e) {
            log.warn("本地版本预过滤查询失败，本次不做过滤: {}", e.getMessage());
            return new LinkedHashSet<>(slugs);
        }
        return kept;
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
            Map<String, ModEntry> loaded = new HashMap<>();
            List<ModEntry> entries = new ArrayList<>();
            try (Connection conn = openConnection();
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
            // game_versions 列是后加的：老库（未重建）没有这列时自动降级，跳过本地预过滤而不是整体加载失败
            try (Connection conn = openConnection()) {
                gameVersionsReady = hasColumn(conn, "GAME_VERSIONS") && hasAnyGameVersions(conn);
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
            log.info("ModrinthCache 已加载: {} 条模组, 类别索引 {} 组, loader 索引 {} 组, 本地版本预过滤={}",
                    size, cats.size(), lods.size(),
                    isGameVersionFilterReady() ? "启用" : "关闭(需重建库或已被配置禁用)");
        } catch (Exception e) {
            available = false;
            gameVersionsReady = false;
            log.error("ModrinthCache 加载失败: {}", e.getMessage(), e);
        }
    }

    /** 打开缓存库连接（H2 会自动补 .mv.db 后缀，这里统一归一化） */
    private Connection openConnection() throws Exception {
        Class.forName("org.h2.Driver");
        String base = Paths.get(dbPath).toAbsolutePath().toString().replace('\\', '/');
        if (base.endsWith(".mv.db")) {
            base = base.substring(0, base.length() - ".mv.db".length());
        }
        return DriverManager.getConnection(
                "jdbc:h2:file:" + base + ";AUTO_SERVER=FALSE;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
    }

    private static boolean hasColumn(Connection conn, String column) throws Exception {
        try (ResultSet cols = conn.getMetaData().getColumns(null, null, "MODRINTH_CACHE", column)) {
            return cols.next();
        }
    }

    private static boolean hasAnyGameVersions(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM modrinth_cache WHERE game_versions IS NOT NULL")) {
            return rs.next() && rs.getLong(1) > 0;
        }
    }

    private void parseVersionsInto(String slug, String json) {
        if (json == null || json.isBlank()) return;
        try {
            JsonNode obj = mapper.readTree(json);
            if (!obj.isObject()) return;
            Map<String, Set<String>> byLoaderMap = new ConcurrentHashMap<>();
            obj.properties().forEach(p -> {
                Set<String> vers = ConcurrentHashMap.newKeySet();
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
                knownVersions.computeIfAbsent(slug, k -> new ConcurrentHashMap<>());
        Set<String> vers = byLoaderMap.computeIfAbsent(l, k -> ConcurrentHashMap.newKeySet());
        if (!vers.add(mcVersion)) {
            return; // 已存在，无需写库
        }
        persistVersions(slug, byLoaderMap);
    }

    private void persistVersions(String slug, Map<String, Set<String>> byLoaderMap) {
        try {
            tools.jackson.databind.node.ObjectNode obj = mapper.createObjectNode();
            byLoaderMap.forEach((loader, vers) -> {
                var arr = obj.putArray(loader);
                vers.stream().sorted().forEach(arr::add);
            });
            try (Connection conn = openConnection();
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

    /** 一条关键词命中：模组 + 该关键词的文本得分 + 命中的词元（供 Critic 上下文解释用） */
    public record EntryMatch(ModEntry entry, double score, List<String> terms) {
    }

    /**
     * 本地关键词检索：类别桶内按<b>词元</b>匹配 title/description/slug，按
     * {@link RetrievalScorer} 的字段权重打分，返回下载量降序（桶内前缀）。
     *
     * <p><b>过滤顺序很关键</b>：桶是按下载量降序预排序的，必须
     * 「先判 loader + 下载量上限，命中关键词后再截断」。
     * 旧实现是「先按下载量截断 120 条，再在调用方过滤 loader」，
     * 热门大类桶里其它 loader 的条目会挤占名额，把目标 loader 的候选饿死。
     *
     * <p><b>未知类别一律返回空</b>：旧实现在类别不在 19 类内时会回退到 allEntries，
     * 等于拿关键词在 7.5 万条模组里做全库搜索——大模型幻觉出一个类别名也不会被发现。
     *
     * <p><b>为什么入参是词元而不是原字符串</b>：词元化 + AND 判定让 "item transport" 这类
     * 短语不必再要求整串字面出现；字段权重（标题 2 / 简介 1 / slug 0.5）也在这一层算好，
     * 调用方只负责累加，避免 local / live 两条召回路径各写一套打分逻辑。
     *
     * @param category     规范类别名；null/空表示不按类别过滤
     * @param terms        关键词词元（{@link RetrievalScorer#tokenize(String)}）
     * @param loader       目标加载器，null/空表示不过滤
     * @param maxDownloads 下载量上限
     * @param limit        命中上限（在前两项过滤之后才生效）
     */
    public List<EntryMatch> matchByKeyword(String category, List<String> terms, String loader,
                                           long maxDownloads, int limit) {
        if (terms == null || terms.isEmpty()) return List.of();
        List<ModEntry> pool;
        if (category == null || category.isBlank()) {
            pool = allEntries;
        } else {
            pool = byCategory.get(category);
            if (pool == null) {
                log.warn("本地检索收到未知类别，已忽略该组关键词: category={}, terms={}", category, terms);
                return List.of();
            }
        }
        String targetLoader = (loader == null || loader.isBlank()) ? null : loader.toLowerCase();
        List<EntryMatch> result = new ArrayList<>();
        for (ModEntry e : pool) {
            if (e.downloads() > maxDownloads) continue;
            if (targetLoader != null && !e.loaders().contains(targetLoader)) continue;
            RetrievalScorer.Match m = RetrievalScorer.match(terms,
                    e.slug().toLowerCase(), e.title().toLowerCase(), e.description().toLowerCase());
            if (m.hit()) {
                result.add(new EntryMatch(e, m.score(), m.terms()));
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
            // 一个模组可能带多个类别标签（如 create = technology + decoration + utility），
            // 归属到第一个规范类别即可。旧实现把所有标签都计入，导致占比之和超过 100%
            // （实测：包里 1 个模组算出 decoration:100% | technology:100% | utility:100%）。
            String primary = null;
            for (String c : entry.categories()) {
                if (CATEGORIES.contains(c)) {
                    primary = c;
                    break;
                }
            }
            if (primary == null) unknown++;
            else counts.merge(primary, 1, Integer::sum);
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
        // 关键：必须把模组身份也带上。
        // 旧实现只给类别统计，模型根本不知道包里有哪些模组——实测"给机械动力加点附属"
        // 会因为它看不到 create 在包里而被拒答（明明包里就有）。清单过长时截断，
        // 完整清单由 Java 侧保留，这里只是给模型做增量判断的依据。
        int shown = Math.min(slugs.size(), 40);
        sb.append(" | 清单:").append(String.join(",", slugs.subList(0, shown)));
        if (slugs.size() > shown) sb.append(" 等 ").append(slugs.size()).append(" 个");
        return sb.toString();
    }
}
