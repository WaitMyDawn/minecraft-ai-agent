package yagen.waitmydawn.maa.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import yagen.waitmydawn.maa.service.KnowledgeDb;
import yagen.waitmydawn.maa.service.LoaderVersionService;

import java.util.*;

@RestController
@RequestMapping("/api/knowledge/feedback")
@CrossOrigin(origins = "*")
public class KnowledgeFeedbackController {

    private final KnowledgeDb knowledgeDb;
    private final UserController userController;
    private final LoaderVersionService loaderVersionService;

    public KnowledgeFeedbackController(KnowledgeDb knowledgeDb, UserController userController,
                                       LoaderVersionService loaderVersionService) {
        this.knowledgeDb = knowledgeDb;
        this.userController = userController;
        this.loaderVersionService = loaderVersionService;
    }

    /** 列出所有已知环境 */
    @GetMapping("/environments")
    public ResponseEntity<List<String>> getEnvironments() {
        return ResponseEntity.ok(knowledgeDb.getAllRules().stream()
                .map(r -> r.environment)
                .distinct().sorted().toList());
    }

    /**
     * 规则编辑器用：加载器 → 该加载器已维护的 MC 版本。
     *
     * <p>以前编辑器只能选"已经存在规则的 environment"，于是规则库里只有 neoforge-1.21.1 时
     * 就永远只能选它 —— 想给 forge / fabric 或别的 MC 版本加规则必须先手改 rules.json。
     * 现在直接从 loader-versions.json（加载器版本表的唯一权威源）里列，选项不会再漏。
     */
    @GetMapping("/loader-envs")
    public ResponseEntity<Map<String, List<String>>> getLoaderEnvs() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String loader : loaderVersionService.supportedLoaders()) {
            out.put(loader, loaderVersionService.availableMcVersions(loader));
        }
        return ResponseEntity.ok(out);
    }

    /** 添加用户反馈规则 */
    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addFeedback(
            @RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, String> body) {
        Long uid = userController.validateToken(token);
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "未登录"));

        var userOpt = userController.getUserByToken(token);
        if (userOpt.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "用户不存在"));
        String accountNumber = userOpt.get().getAccountNumber();

        String environment = body.get("environment");
        String modA = body.get("modA").trim().toLowerCase();
        String modB = body.get("modB").trim().toLowerCase();
        String relationType = body.get("relationType"); // DEPENDS_ON or CONFLICTS_WITH

        if (modA.isEmpty() || modB.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "模组 slug 不能为空"));
        }
        if (modA.equals(modB)) {
            return ResponseEntity.ok(Map.of("error", "两个模组不能相同"));
        }
        String envError = validateEnvironment(environment);
        if (envError != null) {
            return ResponseEntity.ok(Map.of("error", envError));
        }

        // 读改写（查重 + 写入认可名单）收在 KnowledgeDb 里加锁执行，避免并发添加互相覆盖
        var outcome = knowledgeDb.addUserRule(environment, modA, modB, relationType, accountNumber);
        if (!outcome.ok()) {
            return ResponseEntity.ok(Map.of("error", outcome.error()));
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("message", outcome.notice());
        ok.put("confirmCount", outcome.netScore());
        ok.put("approveCount", outcome.approveCount());
        ok.put("disapproveCount", outcome.disapproveCount());
        return ResponseEntity.ok(ok);
    }

    /**
     * 认可 / 不认可一条用户来源规则（知识库管理视图里的两个按钮）。
     *
     * <p>body: {@code {"ruleId": 12, "vote": "APPROVE" | "DISAPPROVE" | "CLEAR"}}。
     * CLEAR = 取消自己之前的投票（按钮弹回未按下状态）。
     */
    @PostMapping("/vote")
    public ResponseEntity<Map<String, Object>> vote(
            @RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, Object> body) {
        Long uid = userController.validateToken(token);
        if (uid == null) return ResponseEntity.status(401).body(Map.of("error", "未登录"));

        var userOpt = userController.getUserByToken(token);
        if (userOpt.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "用户不存在"));
        String accountNumber = userOpt.get().getAccountNumber();

        Long ruleId = asLong(body.get("ruleId"));
        if (ruleId == null) return ResponseEntity.ok(Map.of("error", "缺少 ruleId"));
        KnowledgeDb.Vote vote = parseVote(body.get("vote"));
        if (vote == null) return ResponseEntity.ok(Map.of("error", "投票类型不正确（APPROVE / DISAPPROVE / CLEAR）"));

        var outcome = knowledgeDb.vote(ruleId, accountNumber, vote);
        if (!outcome.ok()) {
            return ResponseEntity.ok(Map.of("error", outcome.error()));
        }
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("message", outcome.notice());
        ok.put("confirmCount", outcome.netScore());
        ok.put("approveCount", outcome.approveCount());
        ok.put("disapproveCount", outcome.disapproveCount());
        ok.put("myVote", vote == KnowledgeDb.Vote.CLEAR ? null : vote.name());
        return ResponseEntity.ok(ok);
    }

    /**
     * 校验 environment：必须是 {@code 加载器-版本}，且该加载器在这个 MC 版本下有维护的版本。
     *
     * <p>否则前端被绕过后能写入 {@code neoforge-9.9.9} 这种环境 —— 它永远不会被依赖引擎命中
     * （那边按 {@code loader-mcVersion} 拼键），白白在知识库里堆垃圾规则。
     *
     * @return null 表示合法；否则返回给用户看的错误文案
     */
    private String validateEnvironment(String environment) {
        if (environment == null || environment.isBlank()) return "环境不能为空";
        int dash = environment.indexOf('-');
        if (dash <= 0 || dash == environment.length() - 1) {
            return "环境格式不正确（应为 加载器-版本，例如 neoforge-1.21.1）";
        }
        String loader = environment.substring(0, dash);
        String mcVersion = environment.substring(dash + 1);
        // 用 availableMcVersions 而不是 supports()：fabric 的表里只有通配项 "*"，
        // supports() 对任何 MC 版本都会返回 true（包括 fabric-9.9.9 这种），挡不住脏环境。
        if (!loaderVersionService.availableMcVersions(loader).contains(mcVersion)) {
            return "不支持的环境: " + environment + "（" + loader + " 未维护 MC " + mcVersion + "）";
        }
        return null;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static KnowledgeDb.Vote parseVote(Object value) {
        if (value == null) return null;
        String s = value.toString().trim().toUpperCase(Locale.ROOT);
        for (KnowledgeDb.Vote v : KnowledgeDb.Vote.values()) {
            if (v.name().equals(s)) return v;
        }
        return null;
    }
}
