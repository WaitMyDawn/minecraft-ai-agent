package yagen.waitmydawn.maa.controller;

import org.springframework.web.bind.annotation.*;
import yagen.waitmydawn.maa.model.KnowledgeRule;
import yagen.waitmydawn.maa.model.User;
import yagen.waitmydawn.maa.service.KnowledgeDb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeDb knowledgeDb;
    private final UserController userController;

    public KnowledgeController(KnowledgeDb knowledgeDb, UserController userController) {
        this.knowledgeDb = knowledgeDb;
        this.userController = userController;
    }

    /**
     * 知识库管理视图的数据源。
     *
     * <p>业务登录由 AuthenticationFilter 统一校验；DTO 仅返回计数与当前账号的投票，
     * 避免将实体中的 approvedUsers / disapprovedUsers 账号名单暴露给其它用户。
     */
    @GetMapping
    public List<Map<String, Object>> getAllRules(
            @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        String accountNumber = null;
        if (token != null && !token.isBlank()) {
            accountNumber = userController.getUserByToken(token).map(User::getAccountNumber).orElse(null);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (KnowledgeRule rule : knowledgeDb.getAllRules()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rule.id);
            m.put("environment", rule.environment);
            m.put("modA", rule.modA);
            m.put("modB", rule.modB);
            m.put("relationType", rule.relationType);
            m.put("sourceType", rule.sourceType == null ? null : rule.sourceType.name());
            m.put("confirmCount", rule.confirmCount);       // 净值 = 认可数 − 不认可数
            m.put("approveCount", rule.approveCount());
            m.put("disapproveCount", rule.disapproveCount());
            m.put("effective", rule.isEffective());
            m.put("myVote", rule.voteOf(accountNumber));
            out.add(m);
        }
        return out;
    }
}
