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
@CrossOrigin(origins = "*")
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
     * <p>刻意不直接吐实体：实体里躺着 {@code approvedUsers} / {@code disapprovedUsers} 两个名单
     * （内容是别人的账号号），而这个接口是公开可读的 —— 直接把投票人的账号广播给所有人没必要。
     * 改成 DTO 只给计数 + 当前访问者自己的投票状态。
     *
     * <p>token 是<b>可选</b>的：不登录也能看知识库，只是 {@code myVote} 永远为 null（按钮画不出按下态）。
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
