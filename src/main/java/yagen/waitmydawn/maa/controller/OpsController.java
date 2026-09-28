package yagen.waitmydawn.maa.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import yagen.waitmydawn.maa.service.BackupService;
import yagen.waitmydawn.maa.service.BrevoMailClient;
import yagen.waitmydawn.maa.service.EmailCodeService;
import yagen.waitmydawn.maa.service.EmailNormalizer;
import yagen.waitmydawn.maa.service.MailService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维端点。目前只有一个：在线备份数据库。
 *
 * <p>为什么要做成端点而不是"让脚本直接进容器跑 H2 工具"：镜像里只有 JRE（没有 jar/unzip 命令），
 * 而 H2 埋在 fat jar 深层；从应用内部发 {@code BACKUP TO} 用的正是同一个数据库引擎，
 * 既不需要额外装东西，也不会出现"备份工具的 H2 版本比应用新/旧"的坑。
 *
 * <p>安全口径：{@code maa.ops.token}（环境变量 {@code MAA_OPS_TOKEN}）没设 → 端点<b>当作不存在</b>返回 404，
 * 功能默认关闭。设了就必须带 {@code X-Ops-Token} 头，比对用定长比较（避免按字符提前返回泄露长度信息）。
 * 这个端点只读文件系统不删东西，最坏情况是"备份文件被塞满磁盘"，所以脚本侧还有保留策略兜着。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final BackupService backupService;
    private final MailService mailService;
    private final BrevoMailClient brevo;
    private final String token;

    public OpsController(BackupService backupService, MailService mailService, BrevoMailClient brevo,
                         @Value("${maa.ops.token:}") String token) {
        this.backupService = backupService;
        this.mailService = mailService;
        this.brevo = brevo;
        this.token = token == null ? "" : token.trim();
    }

    /**
     * 邮件通道状态：现在用哪条通道、还剩多少额度、查不到额度时是什么原因。
     *
     * <p>带 {@code raw=true} 时把 Brevo 账户接口的<b>原始返回</b>也带出来 —— 额度字段名在不同
     * 计划下会变（sendLimit / emailCredits 等），解析对不上时看原始响应最省事。
     */
    @GetMapping("/mail-status")
    public ResponseEntity<Map<String, Object>> mailStatus(
            @RequestHeader(value = "X-Ops-Token", required = false) String givenToken,
            @RequestParam(value = "raw", defaultValue = "false") boolean raw) {
        if (token.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        if (!tokenMatches(token, givenToken)) {
            return ResponseEntity.status(403).body(Map.of("error", "ops token 不正确"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", mailService.providerName());
        body.put("configured", mailService.isConfigured());
        body.put("diagnose", mailService.diagnose());       // 不可用时是原因，可用时是 null
        MailService.Quota q = mailService.quota();
        Map<String, Object> quota = new LinkedHashMap<>();
        quota.put("remaining", q.remaining());             // null = 查不到（此时不阻断发信）
        quota.put("exhausted", q.exhausted());
        quota.put("detail", q.detail());
        quota.put("checkedSecAgo", (System.currentTimeMillis() - q.checkedAtMillis()) / 1000);
        body.put("quota", quota);
        if (raw && "brevo".equals(mailService.providerName()) && brevo.isConfigured()) {
            try {
                body.put("brevoAccountRaw", brevo.account().toString());
            } catch (Exception e) {
                body.put("brevoAccountRaw", "查询失败: " + e.getMessage());
            }
        }
        return ResponseEntity.ok(body);
    }

    /**
     * 发信自检：确认 SMTP 凭据 / 端口 / 发件域名（SPF、DKIM）到底通不通。
     *
     * <p>为什么单独做这个端点：用户报"收不到验证码"时，可能的原因有五六种（授权码错、端口被封、
     * 发件地址没验证、SPF 没配导致进垃圾箱、对方服务器拒收……），而注册流程只会给一句笼统的失败。
     * 这个端点让你能把"发信链路"单独摘出来验一遍，不用走注册流程、不消耗验证码额度。
     *
     * <p>{@code to} 可以不传 —— 不传就发给发件人自己（最省事，也最容易先排除"目标邮箱拒收"这类干扰）。
     */
    @PostMapping("/mail-test")
    public ResponseEntity<Map<String, Object>> mailTest(
            @RequestHeader(value = "X-Ops-Token", required = false) String givenToken,
            @RequestBody(required = false) Map<String, String> body) {
        if (token.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        if (!tokenMatches(token, givenToken)) {
            return ResponseEntity.status(403).body(Map.of("error", "ops token 不正确"));
        }
        if (!mailService.isConfigured()) {
            return ResponseEntity.ok(Map.of("ok", false, "error", mailService.diagnose()));
        }
        String to = EmailNormalizer.normalize(body == null ? null : body.get("to"));
        if (to == null || to.isEmpty()) to = mailService.fromAddress();   // 默认发给自己
        if (!EmailNormalizer.isValidFormat(to)) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "收件人地址不合法：" + to));
        }
        try {
            mailService.sendCode(to, "发信自检", EmailCodeService.randomCode(), 5);
            MailService.Quota q = mailService.quota();
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("provider", mailService.providerName());
            ok.put("to", to);
            ok.put("remainingAfterSendHint", q.remaining());   // 发信后缓存被清，这里等于重新查一次
            ok.put("message", "已提交 " + mailService.providerName() + "。收不到就依次查：垃圾箱 → 发件人是否已验证 → SPF/DKIM 记录");
            return ResponseEntity.ok(ok);
        } catch (Exception e) {
            // 失败要把原始信息带出来，这正是这个端点存在的意义
            return ResponseEntity.ok(Map.of("ok", false,
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /**
     * 触发一次在线备份。
     *
     * @param tag 可选，存放目录名（默认当前时间戳）；由服务端净化后才参与拼路径
     */
    @PostMapping("/backup")
    public ResponseEntity<Map<String, Object>> backup(
            @RequestHeader(value = "X-Ops-Token", required = false) String givenToken,
            @RequestBody(required = false) Map<String, String> body) {
        if (token.isEmpty()) {
            // 没配 token = 这个功能没开。返回 404 而不是 403：不告诉探测者"这里有个端点"
            return ResponseEntity.status(404).body(Map.of("error", "not found"));
        }
        if (!tokenMatches(token, givenToken)) {
            return ResponseEntity.status(403).body(Map.of("error", "ops token 不正确"));
        }
        String tag = body == null ? null : body.get("tag");
        try {
            BackupService.Result r = backupService.backup(tag);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("tag", r.tag());
            ok.put("file", r.file());
            ok.put("bytes", r.bytes());
            ok.put("millis", r.millis());
            return ResponseEntity.ok(ok);
        } catch (Exception e) {
            // 失败必须说清楚：备份是"平时没人看、出事才用"的东西，静默失败等于没有备份
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    /** 定长比较：token 为空/不匹配都算失败 */
    static boolean tokenMatches(String expected, String given) {
        if (expected == null || expected.isEmpty() || given == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
    }
}
