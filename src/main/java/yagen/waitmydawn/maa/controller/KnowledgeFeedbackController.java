package yagen.waitmydawn.maa.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import yagen.waitmydawn.maa.model.KnowledgeRule;
import yagen.waitmydawn.maa.service.KnowledgeDb;

import java.util.*;

@RestController
@RequestMapping("/api/knowledge/feedback")
@CrossOrigin(origins = "*")
public class KnowledgeFeedbackController {

    private final KnowledgeDb knowledgeDb;
    private final UserController userController;

    public KnowledgeFeedbackController(KnowledgeDb knowledgeDb, UserController userController) {
        this.knowledgeDb = knowledgeDb;
        this.userController = userController;
    }

    /** 列出所有已知环境 */
    @GetMapping("/environments")
    public ResponseEntity<List<String>> getEnvironments() {
        return ResponseEntity.ok(knowledgeDb.getAllRules().stream()
                .map(r -> r.environment)
                .distinct().sorted().toList());
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

        // 查找已有规则
        var existingOpt = knowledgeDb.findRule(environment, modA, modB, relationType);
        if (existingOpt.isPresent()) {
            KnowledgeRule rule = existingOpt.get();
            if (rule.sourceType != KnowledgeRule.SourceType.USER_FEEDBACK) {
                return ResponseEntity.ok(Map.of("error", "该规则已存在且为官方规则，无法覆盖",
                        "sourceType", rule.sourceType.name()));
            }
            // 检查用户是否已添加过
            Set<String> userSet = new HashSet<>();
            if (rule.users != null && !rule.users.isEmpty()) {
                userSet.addAll(Arrays.asList(rule.users.split(",")));
            }
            if (userSet.contains(accountNumber)) {
                return ResponseEntity.ok(Map.of("ok", true, "message", "你已经添加过此规则"));
            }
            userSet.add(accountNumber);
            rule.users = String.join(",", userSet);
            rule.confirmCount = userSet.size();
            knowledgeDb.save(rule);
            return ResponseEntity.ok(Map.of("ok", true, "confirmCount", rule.confirmCount,
                    "message", "规则已更新，可信度: " + rule.confirmCount));
        }

        // 新建 USER_FEEDBACK 规则
        KnowledgeRule rule = new KnowledgeRule(environment, modA, modB, relationType,
                KnowledgeRule.SourceType.USER_FEEDBACK, 1, accountNumber);
        knowledgeDb.save(rule);
        return ResponseEntity.ok(Map.of("ok", true, "confirmCount", 1, "message", "规则已添加"));
    }
}
