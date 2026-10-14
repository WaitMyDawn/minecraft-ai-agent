package yagen.waitmydawn.maa.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.runtime.DelegationContext;
import yagen.waitmydawn.maa.runtime.RequestScope;
import yagen.waitmydawn.maa.service.DelegationTasks;
import yagen.waitmydawn.maa.service.MrpackParser;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** 短业务任务独立排队，避免模型长调用占满预览与元数据读取的执行名额。 */
@RestController
@RequestMapping("/api")
public class BrowserOperationsController {
    private final DelegationTasks tasks;
    private final ModpackController modpacks;
    private final PreferencesController preferences;
    private final ChatController chat;
    private final UserController users;
    private final MrpackParser parser;
    private final ObjectMapper mapper;
    private final java.util.concurrent.Semaphore imports = new java.util.concurrent.Semaphore(2);
    private final java.util.concurrent.ConcurrentMap<Long, Integer> admissions = new java.util.concurrent.ConcurrentHashMap<>();

    public BrowserOperationsController(ModpackController modpacks, PreferencesController preferences,
            ChatController chat, UserController users, MrpackParser parser, ObjectMapper mapper,
            @Value("${maa.delegation.enabled:true}") boolean enabled,
            @Value("${maa.delegation.per-call-budget-ms:120000}") long budget,
            @Value("${maa.operations.active-limit:8}") int active,
            @Value("${maa.operations.queue-limit:128}") int queue) {
        this.tasks = new DelegationTasks(enabled, budget, active, queue);
        this.modpacks = modpacks;
        this.preferences = preferences;
        this.chat = chat;
        this.users = users;
        this.parser = parser;
        this.mapper = mapper;
    }

    @jakarta.annotation.PreDestroy
    public void close() { tasks.close(); }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public void expireDisconnected() { tasks.expireDisconnected(); }

    @PostMapping("/modpack/preview")
    public ResponseEntity<Object> preview(@RequestHeader("X-Auth-Token") String token,
            @RequestBody ModpackController.ModpackRequest request) {
        return launch(token, () -> modpacks.previewPack(token, request), false);
    }

    @PostMapping("/modpack/suggest")
    public ResponseEntity<Object> suggest(@RequestHeader("X-Auth-Token") String token,
            @RequestBody ModpackController.SuggestionRequest request) {
        return launch(token, () -> modpacks.suggestMods(token, request), false);
    }

    @PostMapping("/modpack/parse-mrpack")
    public ResponseEntity<Object> parse(@RequestHeader("X-Auth-Token") String token,
            @RequestParam("file") MultipartFile file) throws Exception {
        owner(token);
        if (!imports.tryAcquire(2, TimeUnit.SECONDS)) return ResponseEntity.status(429)
                .body(Map.of("error", "上传解析繁忙，请稍后重试"));
        JsonNode index;
        try { index = parser.readIndex(file); }
        catch (java.io.IOException | IllegalArgumentException invalid) { return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage())); }
        finally { imports.release(); }
        // MultipartFile 的临时文件会随 HTTP 请求结束被删除；队列只保留有上限的索引。
        return launch(token, () -> {
            try { return ResponseEntity.ok(parser.parse(index)); }
            catch (IllegalArgumentException invalid) { return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage())); }
        }, false);
    }

    @PostMapping("/prefs/update-on-build")
    public ResponseEntity<Object> learn(@RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, Object> body) {
        return launch(token, () -> preferences.updateOnBuild(token, body), false);
    }

    @GetMapping("/prefs/mod-info/{slug}")
    public ResponseEntity<Object> info(@RequestHeader("X-Auth-Token") String token, @PathVariable String slug) {
        if (!yagen.waitmydawn.maa.service.ModrinthIdentifiers.isProjectReference(slug)) return ResponseEntity.badRequest().body(Map.of("error", "模组标识无效"));
        return launch(token, () -> preferences.modInfo(slug), true);
    }

    private Long owner(String token) {
        Long uid = users.validateToken(token);
        if (uid == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "未登录");
        return uid;
    }

    private ResponseEntity<Object> launch(String token, Callable<? extends ResponseEntity<?>> work, boolean read) {
        Long uid = owner(token);
        var admitted = new java.util.concurrent.atomic.AtomicBoolean();
        admissions.compute(uid, (key, count) -> {
            if (count != null && count >= 8) return count;
            admitted.set(true);
            return count == null ? 1 : count + 1;
        });
        if (!admitted.get()) return ResponseEntity.status(429).body(Map.of("error", "同时提交的任务过多，请等待已有任务完成"));
        DelegationTasks.Task task;
        try {
            task = tasks.submit(null, uid, "user-" + uid, RequestScope.current(), () -> {
                owner(token);
                ResponseEntity<?> response = work.call();
                return mapper.writeValueAsString(Map.of("status", response.getStatusCode().value(),
                        "body", response.getBody() == null ? Map.of() : response.getBody()));
            }, !read);
        } catch (RuntimeException e) { releaseAdmission(uid); throw e; }
        task.result().whenComplete((value, error) -> releaseAdmission(uid));
        return progress(task);
    }

    private void releaseAdmission(Long uid) {
        admissions.computeIfPresent(uid, (key, count) -> count <= 1 ? null : count - 1);
    }

    private DelegationTasks.Task owned(String id, String token) {
        Long uid = owner(token);
        var task = tasks.find(id);
        if (task == null || !uid.equals(task.ownerId())) throw new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "任务不存在或已过期");
        task.received();
        return task;
    }

    @GetMapping("/operations/{id}")
    public ResponseEntity<Object> poll(@PathVariable String id, @RequestHeader("X-Auth-Token") String token) {
        return progress(owned(id, token));
    }

    @PostMapping("/operations/{id}/facts")
    public ResponseEntity<Object> facts(@PathVariable String id, @RequestHeader("X-Auth-Token") String token,
            @RequestBody JsonNode body) {
        var task = owned(id, token);
        if (!task.isDone()) chat.acceptTaskFacts(task.context(), body);
        return progress(task);
    }

    @PostMapping("/operations/{id}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable String id, @RequestHeader("X-Auth-Token") String token) {
        tasks.cancel(owned(id, token));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private ResponseEntity<Object> progress(DelegationTasks.Task task) {
        tasks.awaitFirstDemand(task, 1000);
        if (task.isDone()) {
            tasks.resultDelivered(task);
            try {
                JsonNode result = mapper.readTree(task.result().join());
                return ResponseEntity.status(result.path("status").asInt()).body(result.path("body"));
            } catch (Exception e) {
                return ResponseEntity.status(503).body(Map.of("error", "任务未能完成，请稍后重试"));
            }
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("taskId", task.taskId());
        envelope.put("operation", true);
        envelope.put("queuePosition", tasks.queuePosition(task));
        List<Map<String, Object>> wanted = new ArrayList<>();
        for (var want : task.context().dispatchWants()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", want.kind().name().toLowerCase(Locale.ROOT));
            item.put("key", want.key());
            if (want.kind() == DelegationContext.Kind.ENV_VERSION || want.kind() == DelegationContext.Kind.ENV_VERSIONS) {
                String[] parts = want.key().split("\\|", 3);
                if (parts.length == 3) {
                    item.put("project", parts[0]); item.put("mc", parts[1]);
                    item.put("loader", parts[2].replaceAll("[\\[\\]\"]", "").trim());
                }
            }
            wanted.add(item);
        }
        if (!wanted.isEmpty()) {
            task.dispatched();
            task.context().countDispatched(wanted.size());
        }
        envelope.put("stage", !wanted.isEmpty() ? "need-data" : task.hasStarted() ? "running" : "queued");
        envelope.put("wanted", wanted);
        envelope.put("perCallBudgetMs", tasks.callBudgetMs());
        return ResponseEntity.accepted().body(envelope);
    }
}
