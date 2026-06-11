package yagen.waitmydawn.maa.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.service.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/prefs")
@CrossOrigin(origins = "*")
public class PreferencesController {

    private final UserRepository userRepo;
    private final UserController userController;
    private final CategoryPreferenceRepository catRepo;
    private final ModPreferenceRepository modRepo;
    private final ModBlacklistRepository blacklistRepo;
    private final ConversationRepository convRepo;
    private final ChatMessageRepository msgRepo;
    private final ModrinthApiClient apiClient;

    public PreferencesController(UserRepository userRepo, UserController userController,
                                  CategoryPreferenceRepository catRepo, ModPreferenceRepository modRepo,
                                  ModBlacklistRepository blacklistRepo,
                                  ConversationRepository convRepo, ChatMessageRepository msgRepo,
                                  ModrinthApiClient apiClient) {
        this.userRepo = userRepo;
        this.userController = userController;
        this.catRepo = catRepo;
        this.modRepo = modRepo;
        this.blacklistRepo = blacklistRepo;
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.apiClient = apiClient;
    }

    private Long requireUser(String token) {
        Long uid = userController.validateToken(token);
        if (uid == null) throw new RuntimeException("未登录");
        return uid;
    }

    // ==================== 对话历史 ====================

    @GetMapping("/conversations")
    public ResponseEntity<List<Map<String, Object>>> listConversations(@RequestHeader("X-Auth-Token") String token) {
        Long uid = requireUser(token);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Conversation c : convRepo.findByUserIdOrderByUpdatedAtDesc(uid)) {
            list.add(Map.of("id", c.getId(), "title", c.getTitle(),
                    "createdAt", c.getCreatedAt().toString(), "updatedAt", c.getUpdatedAt().toString()));
        }
        return ResponseEntity.ok(list);
    }

    @PostMapping("/conversations")
    public ResponseEntity<Map<String, Object>> createConversation(
            @RequestHeader("X-Auth-Token") String token, @RequestBody Map<String, String> body) {
        Long uid = requireUser(token);
        Conversation c = new Conversation(uid, body.getOrDefault("title", "新对话"));
        convRepo.save(c);
        return ResponseEntity.ok(Map.of("id", c.getId(), "title", c.getTitle()));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Map<String, Object>> deleteConversation(
            @RequestHeader("X-Auth-Token") String token, @PathVariable Long id) {
        Long uid = requireUser(token);
        convRepo.findById(id).ifPresent(c -> {
            if (c.getUserId().equals(uid)) {
                msgRepo.deleteByConversationId(id);
                convRepo.delete(c);
            }
        });
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<Map<String, Object>>> getMessages(
            @RequestHeader("X-Auth-Token") String token, @PathVariable Long id) {
        requireUser(token);
        return ResponseEntity.ok(msgRepo.findByConversationIdOrderByCreatedAtAsc(id).stream()
                .map(m -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("role", m.getRole()); mm.put("content", m.getContent());
                    mm.put("hasData", m.isHasData());
                    mm.put("thinkTime", m.getThinkTime() != null ? m.getThinkTime() : "");
                    mm.put("modSlugs", m.getModSlugs() != null ? m.getModSlugs() : "");
                    return mm;
                }).collect(Collectors.toList()));
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<Map<String, Object>> saveMessage(
            @RequestHeader("X-Auth-Token") String token, @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        requireUser(token);
        ChatMessage msg = new ChatMessage(id,
                body.get("role").toString(), body.get("content").toString(),
                Boolean.TRUE.equals(body.get("hasData")),
                body.getOrDefault("thinkTime", "").toString(),
                body.getOrDefault("modSlugs", "").toString());
        msgRepo.save(msg);
        convRepo.findById(id).ifPresent(c -> {
            c.setUpdatedAt(java.time.LocalDateTime.now());
            if (body.containsKey("title")) c.setTitle(body.get("title").toString());
            convRepo.save(c);
        });
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ==================== 类别偏好 ====================

    @GetMapping("/categories")
    public ResponseEntity<List<Map<String, Object>>> getCategoryPrefs(@RequestHeader("X-Auth-Token") String token) {
        Long uid = requireUser(token);
        return ResponseEntity.ok(catRepo.findByUserIdOrderByRankAsc(uid).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId()); m.put("category", c.getCategory()); m.put("rank", c.getRank());
            return m;
        }).collect(Collectors.toList()));
    }

    @PostMapping("/categories")
    public ResponseEntity<Map<String, Object>> addCategoryPref(
            @RequestHeader("X-Auth-Token") String token, @RequestBody Map<String, String> body) {
        Long uid = requireUser(token);
        String cat = body.get("category");
        int targetRank = Integer.parseInt(body.getOrDefault("rank", "5"));
        targetRank = Math.max(1, Math.min(5, targetRank));

        List<CategoryPreference> existing = catRepo.findByUserIdOrderByRankAsc(uid);

        // 检查是否已存在此类别 → 更新其 rank
        var dup = existing.stream().filter(c -> c.getCategory().equals(cat)).findFirst();
        if (dup.isPresent()) {
            CategoryPreference toUpdate = dup.get();
            int oldRank = toUpdate.getRank();
            toUpdate.setRank(targetRank);
            // 将其他类别向下推移
            for (CategoryPreference c : existing) {
                if (c.getId().equals(toUpdate.getId())) continue;
                int r = c.getRank();
                if (r >= targetRank && r < oldRank) c.setRank(r + 1);
                else if (r > oldRank && r <= targetRank) c.setRank(r - 1);
            }
            for (CategoryPreference c : existing) catRepo.save(c);
            normalizeCategoryRanks(uid);
            return ResponseEntity.ok(Map.of("id", toUpdate.getId()));
        }

        // 新增类别: 如果 >=5 个, 优先替换 rank=5 或推挤
        if (existing.size() >= 5) {
            // 目标 rank 范围的所有现有类别向下推, 最后一个被挤出
            boolean pushedAny = false;
            for (CategoryPreference c : existing) {
                if (c.getRank() >= targetRank) {
                    if (c.getRank() < 5) { c.setRank(c.getRank() + 1); pushedAny = true; }
                    else catRepo.delete(c); // rank=5 被挤出
                }
            }
            if (!pushedAny) {
                // 没有可推的 (目标 rank 以下的都没被挤), 删除最高的
                existing.stream().max((a,b)->Integer.compare(a.getRank(),b.getRank()))
                    .ifPresent(catRepo::delete);
            }
            for (CategoryPreference c : existing) catRepo.save(c);
            // 清除被删除的引用
            existing.removeIf(c -> !catRepo.existsById(c.getId()));
        }

        CategoryPreference cp = new CategoryPreference(uid, cat, targetRank);
        catRepo.save(cp);
        normalizeCategoryRanks(uid);
        return ResponseEntity.ok(Map.of("id", cp.getId()));
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Map<String, Object>> removeCategoryPref(
            @RequestHeader("X-Auth-Token") String token, @PathVariable Long id) {
        requireUser(token);
        catRepo.deleteById(id);
        normalizeCategoryRanks(requireUser(token));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 将用户的类别偏好 rank 重新编号为 1,2,3,... (保持原有顺序) */
    private void normalizeCategoryRanks(Long uid) {
        List<CategoryPreference> sorted = catRepo.findByUserIdOrderByRankAsc(uid);
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getRank() != i + 1) {
                sorted.get(i).setRank(i + 1);
                catRepo.save(sorted.get(i));
            }
        }
    }

    // ==================== 模组偏好 ====================

    @GetMapping("/mods")
    public ResponseEntity<List<Map<String, Object>>> getModPrefs(
            @RequestHeader("X-Auth-Token") String token,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "includeLowCount", defaultValue = "false") boolean includeLowCount) {
        Long uid = requireUser(token);
        List<ModPreference> list = category != null
                ? modRepo.findByUserIdAndCategory(uid, category)
                : modRepo.findByUserIdOrderByBuildCountDesc(uid);

        return ResponseEntity.ok(list.stream()
                .filter(m -> includeLowCount || "user".equals(m.getSource()) || m.getBuildCount() >= 3)
                .map(m -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("id", m.getId()); mm.put("category", m.getCategory() != null ? m.getCategory() : "");
                    mm.put("slug", m.getSlug()); mm.put("buildCount", m.getBuildCount());
                    mm.put("source", m.getSource());
                    return mm;
                }).collect(Collectors.toList()));
    }

    @PostMapping("/mods")
    public ResponseEntity<Map<String, Object>> addModPref(
            @RequestHeader("X-Auth-Token") String token, @RequestBody Map<String, Object> body) {
        Long uid = requireUser(token);
        String slug = body.get("slug").toString();
        String cat = body.getOrDefault("category", "").toString();
        String source = body.getOrDefault("source", "user").toString();

        // user 来源: 构建次数设为当前全局最高 (最低 3)
        // agent 来源: 默认 1
        int initialBuildCount = 1;
        if ("user".equals(source)) {
            int maxBuildCount = modRepo.findByUserIdOrderByBuildCountDesc(uid).stream()
                    .mapToInt(ModPreference::getBuildCount).max().orElse(0);
            initialBuildCount = Math.max(3, maxBuildCount);
        }

        // 检查是否已存在
        var existing = modRepo.findByUserIdAndSlug(uid, slug);
        if (existing.isPresent()) {
            ModPreference mp = existing.get();
            mp.setSource("user"); // 已存在也标记为手动
            mp.setBuildCount(Math.max(mp.getBuildCount(), initialBuildCount));
            if (!cat.isEmpty()) mp.setCategory(cat);
            modRepo.save(mp);
            return ResponseEntity.ok(Map.of("id", mp.getId(), "source", mp.getSource(), "buildCount", mp.getBuildCount()));
        }

        ModPreference mp = new ModPreference(uid, cat, slug, initialBuildCount, source);
        modRepo.save(mp);
        return ResponseEntity.ok(Map.of("id", mp.getId(), "source", mp.getSource(), "buildCount", mp.getBuildCount()));
    }

    @PutMapping("/mods/{id}")
    public ResponseEntity<Map<String, Object>> updateModPref(
            @RequestHeader("X-Auth-Token") String token, @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long uid = requireUser(token);
        var opt = modRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.ok(Map.of("error", "not found"));
        ModPreference mp = opt.get();
        if (!mp.getUserId().equals(uid)) return ResponseEntity.ok(Map.of("error", "not yours"));

        // 修改构建次数
        if (body.containsKey("buildCount")) {
            mp.setBuildCount(((Number) body.get("buildCount")).intValue());
        }

        // 修改 source: user → agent 取消手动标记
        if (body.containsKey("source")) {
            String newSource = body.get("source").toString();
            // 转为 agent 且 buildCount < 3 → 删除 (不参与偏好)
            if ("agent".equals(newSource) && "user".equals(mp.getSource()) && mp.getBuildCount() < 3) {
                modRepo.delete(mp);
                return ResponseEntity.ok(Map.of("ok", true, "deleted", true));
            }
            mp.setSource(newSource);
        }

        modRepo.save(mp);
        return ResponseEntity.ok(Map.of("ok", true, "source", mp.getSource(), "buildCount", mp.getBuildCount()));
    }

    @DeleteMapping("/mods/{id}")
    public ResponseEntity<Map<String, Object>> removeModPref(
            @RequestHeader("X-Auth-Token") String token, @PathVariable Long id) {
        requireUser(token);
        modRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ==================== 下载时更新偏好 ====================

    @PostMapping("/update-on-build")
    public ResponseEntity<Map<String, Object>> updateOnBuild(
            @RequestHeader("X-Auth-Token") String token, @RequestBody Map<String, Object> body) {
        Long uid = requireUser(token);
        @SuppressWarnings("unchecked")
        List<String> slugs = (List<String>) body.get("slugs");
        if (slugs == null || slugs.isEmpty()) return ResponseEntity.ok(Map.of("ok", true));

        // 模组太多时采样 (防止 API 调用过量和 DB 膨胀)
        List<String> sample = slugs.size() > 200
                ? slugs.subList(0, 200)
                : new ArrayList<>(slugs);

        // 🔥 并发获取类别信息 (虚拟线程批量调用 Modrinth API)
        Map<String, String> slugCategories = new ConcurrentHashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = sample.stream()
                    .map(slug -> CompletableFuture.runAsync(() -> {
                        try {
                            var info = apiClient.getProjectInfo(slug);
                            if (info != null && info.has("categories")) {
                                var cats = info.path("categories");
                                if (cats.isArray() && cats.size() > 0) {
                                    slugCategories.put(slug, cats.get(0).asText());
                                }
                            }
                        } catch (Exception ignored) {}
                    }, executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // 更新类别偏好: 统计各类别模组数量, 取 top 5
        Map<String, Long> catCount = new LinkedHashMap<>();
        for (String slug : slugs) {
            String cat = slugCategories.getOrDefault(slug, "unknown");
            catCount.merge(cat, 1L, Long::sum);
        }
        catRepo.deleteByUserId(uid);
        int rank = 1;
        for (var entry : catCount.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5).toList()) {
            catRepo.save(new CategoryPreference(uid, entry.getKey(), rank++));
        }

        // 当前全局最高构建次数 (用于 user 来源模组同步)
        int globalMax = modRepo.findByUserIdOrderByBuildCountDesc(uid).stream()
                .mapToInt(ModPreference::getBuildCount).max().orElse(0);

        // 更新模组偏好
        int newMax = globalMax;
        for (String slug : sample) {
            String cat = slugCategories.getOrDefault(slug, "unknown");
            var existing = modRepo.findByUserIdAndSlug(uid, slug);
            if (existing.isPresent()) {
                ModPreference mp = existing.get();
                if ("user".equals(mp.getSource())) {
                    mp.setBuildCount(Math.max(globalMax + 1, mp.getBuildCount() + 1));
                } else {
                    mp.setBuildCount(mp.getBuildCount() + 1);
                }
                modRepo.save(mp);
                if (mp.getBuildCount() > newMax) newMax = mp.getBuildCount();
            } else {
                // 首次出现: 保存为 agent 来源, buildCount=1
                // 前端仅展示 buildCount≥3 的 agent 模组, 后台静默累积
                ModPreference mp = new ModPreference(uid, cat, slug, 1, "agent");
                modRepo.save(mp);
            }
        }

        // 复核: user 来源模组全部同步到本轮最高 (含新增的 user 模组)
        if (newMax > globalMax) {
            for (var mp : modRepo.findByUserIdAndSource(uid, "user")) {
                mp.setBuildCount(Math.max(mp.getBuildCount(), newMax));
                modRepo.save(mp);
            }
        }

        return ResponseEntity.ok(Map.of("ok", true, "categories", catCount));
    }

    // ==================== 模组黑名单 ====================

    @GetMapping("/blacklist")
    public ResponseEntity<List<Map<String, Object>>> getBlacklist(@RequestHeader("X-Auth-Token") String token) {
        Long uid = requireUser(token);
        return ResponseEntity.ok(blacklistRepo.findByUserIdOrderByCreatedAtDesc(uid).stream()
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", b.getId()); m.put("slug", b.getSlug()); m.put("source", b.getSource());
                    return m;
                }).toList());
    }

    @PostMapping("/blacklist")
    public ResponseEntity<Map<String, Object>> addBlacklist(
            @RequestHeader("X-Auth-Token") String token, @RequestBody Map<String, String> body) {
        Long uid = requireUser(token);
        String slug = body.get("slug").trim().toLowerCase();
        if (slug.isEmpty()) return ResponseEntity.ok(Map.of("error", "slug 不能为空"));
        if (blacklistRepo.findByUserIdAndSlug(uid, slug).isPresent()) {
            return ResponseEntity.ok(Map.of("ok", true, "message", "已存在"));
        }
        String source = body.getOrDefault("source", "user");
        blacklistRepo.save(new ModBlacklist(uid, slug, source));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/blacklist/{id}")
    public ResponseEntity<Map<String, Object>> removeBlacklist(
            @RequestHeader("X-Auth-Token") String token, @PathVariable Long id) {
        requireUser(token);
        blacklistRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/blacklist/clear")
    public ResponseEntity<Map<String, Object>> clearBlacklist(@RequestHeader("X-Auth-Token") String token) {
        Long uid = requireUser(token);
        blacklistRepo.deleteByUserId(uid);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ==================== 导出导入 ====================

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> exportPrefs(@RequestHeader("X-Auth-Token") String token) {
        Long uid = requireUser(token);
        var user = userRepo.findById(uid).orElseThrow();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("preferenceWeight", user.getPreferenceWeight());
        data.put("enableBlacklist", user.isEnableBlacklist());
        data.put("enableUserFeedbackRules", user.isEnableUserFeedbackRules());
        data.put("blacklist", blacklistRepo.findByUserIdOrderByCreatedAtDesc(uid).stream()
                .map(b -> Map.of("slug", b.getSlug(), "source", b.getSource()))
                .toList());
        data.put("categories", catRepo.findByUserIdOrderByRankAsc(uid).stream()
                .map(c -> Map.of("category", c.getCategory(), "rank", c.getRank()))
                .collect(Collectors.toList()));
        data.put("mods", modRepo.findByUserIdOrderByBuildCountDesc(uid).stream()
                .map(m -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("category", m.getCategory());
                    mm.put("slug", m.getSlug());
                    mm.put("buildCount", m.getBuildCount());
                    mm.put("source", m.getSource());
                    return mm;
                })
                .collect(Collectors.toList()));
        return ResponseEntity.ok(data);
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importPrefs(
            @RequestHeader("X-Auth-Token") String token, @RequestBody Map<String, Object> data) {
        Long uid = requireUser(token);
        var user = userRepo.findById(uid).orElseThrow();
        if (data.containsKey("preferenceWeight")) {
            user.setPreferenceWeight(((Number) data.get("preferenceWeight")).doubleValue());
        }
        if (data.containsKey("enableBlacklist")) {
            user.setEnableBlacklist(Boolean.TRUE.equals(data.get("enableBlacklist")));
        }
        if (data.containsKey("enableUserFeedbackRules")) {
            user.setEnableUserFeedbackRules(Boolean.TRUE.equals(data.get("enableUserFeedbackRules")));
        }
        userRepo.save(user);
        @SuppressWarnings("unchecked")
        var blacklist = (List<Map<String, Object>>) data.getOrDefault("blacklist", List.of());
        if (!blacklist.isEmpty()) {
            blacklistRepo.deleteByUserId(uid);
            for (var b : blacklist) {
                blacklistRepo.save(new ModBlacklist(uid,
                        b.get("slug").toString(),
                        b.getOrDefault("source", "user").toString()));
            }
        }
        @SuppressWarnings("unchecked")
        var cats = (List<Map<String, Object>>) data.getOrDefault("categories", List.of());
        if (!cats.isEmpty()) {
            catRepo.deleteByUserId(uid);
            for (var c : cats) {
                catRepo.save(new CategoryPreference(uid,
                        c.get("category").toString(),
                        ((Number) c.get("rank")).intValue()));
            }
        }
        @SuppressWarnings("unchecked")
        var mods = (List<Map<String, Object>>) data.getOrDefault("mods", List.of());
        if (!mods.isEmpty()) {
            modRepo.deleteByUserId(uid);
            for (var m : mods) {
                String source = m.getOrDefault("source", "agent").toString();
                modRepo.save(new ModPreference(uid,
                        m.getOrDefault("category", "").toString(),
                        m.get("slug").toString(),
                        ((Number) m.getOrDefault("buildCount", 1)).intValue(),
                        source));
            }
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/clear")
    public ResponseEntity<Map<String, Object>> clearPrefs(@RequestHeader("X-Auth-Token") String token) {
        Long uid = requireUser(token);
        catRepo.deleteByUserId(uid);
        modRepo.deleteByUserId(uid);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ==================== Modrinth 模组信息 (给前端卡片用) ====================

    @GetMapping("/mod-info/{slug}")
    public ResponseEntity<Map<String, Object>> modInfo(@PathVariable String slug) {
        try {
            var info = apiClient.getProjectInfo(slug);
            if (info == null) return ResponseEntity.ok(Map.of("error", "not found"));
            return ResponseEntity.ok(Map.of(
                    "slug", slug,
                    "title", info.path("title").asText(),
                    "icon", info.path("icon_url").asText(),
                    "description", info.path("description").asText(),
                    "downloads", info.path("downloads").asLong(),
                    "categories", info.path("categories").toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }
}
