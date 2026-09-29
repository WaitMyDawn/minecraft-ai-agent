package yagen.waitmydawn.maa.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import yagen.waitmydawn.maa.logging.MaaLog;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.runtime.SessionRegistry;
import yagen.waitmydawn.maa.service.EmailCodeService;
import yagen.waitmydawn.maa.service.EmailNormalizer;
import yagen.waitmydawn.maa.service.MailService;
import yagen.waitmydawn.maa.service.PasswordHasher;
import yagen.waitmydawn.maa.service.PasswordPolicy;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "*")
public class UserController {

    private final UserRepository userRepo;
    private final ConversationRepository convRepo;
    private final ChatMessageRepository msgRepo;
    /** token → userId 的会话表，带空闲有效期（见 SessionRegistry 的取舍说明） */
    private final SessionRegistry sessions;
    private final String encryptionSecret;
    private final boolean allowRegistration;
    /** 密码哈希（PBKDF2 + 每用户随机盐），并负责把老格式在登录时静默升级 */
    private final PasswordHasher passwordHasher;
    /** 新密码的规则（8~64 位、必须含字母和数字）。只在注册和改密码时用，登录不校验。 */
    private final PasswordPolicy passwordPolicy;
    /** 邮箱验证码：签发/校验/限流 */
    private final EmailCodeService emailCodeService;
    /** 发验证码邮件 */
    private final MailService mailService;
    /** 发号用的游标：只在 nextAccountNumber() 的临界区里读写 */
    private long lastAccountNumber = 0;

    public UserController(UserRepository userRepo, ConversationRepository convRepo,
                          ChatMessageRepository msgRepo,
                          @Value("${maa.encryption.secret}") String encryptionSecret,
                          @Value("${maa.allow-registration:true}") boolean allowRegistration,
                          @Value("${maa.session.ttl-days:30}") long sessionTtlDays,
                          PasswordHasher passwordHasher,
                          PasswordPolicy passwordPolicy,
                          EmailCodeService emailCodeService,
                          MailService mailService) {
        this.userRepo = userRepo;
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.encryptionSecret = encryptionSecret;
        this.allowRegistration = allowRegistration;
        this.passwordHasher = passwordHasher;
        this.passwordPolicy = passwordPolicy;
        this.emailCodeService = emailCodeService;
        this.mailService = mailService;
        // 天数 → 毫秒。给下限 1 天：配成 0 或负数会让所有人都登不进来（400 起不来比"token 不过期"更难排查）
        this.sessions = new SessionRegistry(Math.max(1, sessionTtlDays) * 24L * 60 * 60 * 1000);
    }

    /**
     * 发验证码。
     *
     * <p>五个用途，分两类：
     * <ul>
     *   <li><b>未登录</b>：register（发给待注册邮箱）、reset（发给已注册邮箱）；</li>
     *   <li><b>已登录</b>：change_password（发给<b>当前绑定邮箱</b>）、bind_new（发给待绑定的新邮箱）。</li>
     * </ul>
     *
     * <p>登录态那两个用途<b>不接受前端指定收件人</b>（change_password 的邮箱直接从登录态推导）——
     * 否则任何登录用户都能拿这个接口给任意地址发信，等于把"按邮箱日限 3"这道防线绕开。
     */
    @PostMapping("/email-code")
    public ResponseEntity<Map<String, Object>> sendEmailCode(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Auth-Token", required = false) String authToken) {
        String email = EmailNormalizer.normalize(body.get("email"));
        String raw = body.getOrDefault("purpose", "");
        EmailCodeService.Purpose purpose;
        try {
            purpose = EmailCodeService.Purpose.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of("error", "不支持的验证码用途: " + raw));
        }

        // ---- 登录态用途：收件人由服务端决定，不看请求体 ----
        if (purpose != EmailCodeService.Purpose.REGISTER && purpose != EmailCodeService.Purpose.RESET) {
            Long uid = userIdForToken(authToken);
            if (uid == null) {
                return ResponseEntity.status(401).body(Map.of("error", "未登录，请重新登录"));
            }
            User me = userRepo.findById(uid).orElse(null);
            if (me == null) {
                return ResponseEntity.status(401).body(Map.of("error", "用户不存在"));
            }
            if (purpose == EmailCodeService.Purpose.BIND_NEW) {
                // 新邮箱允许指定，但要提前告诉用户"这个邮箱已经被别人用了"，别等他填完两个码才说
                if (!EmailNormalizer.isValidFormat(email)) {
                    return ResponseEntity.ok(Map.of("error", "请填写有效的邮箱地址"));
                }
                if (email.equals(me.getEmail())) {
                    return ResponseEntity.ok(Map.of("error", "这就是你当前绑定的邮箱，无需换绑"));
                }
                if (userRepo.existsByEmail(email)) {
                    return ResponseEntity.ok(Map.of("error", "该邮箱已被其它账号绑定"));
                }
            } else if (purpose == EmailCodeService.Purpose.CHANGE_PASSWORD) {
                // 只发给当前绑定邮箱
                if (me.getEmail() == null || me.getEmail().isBlank()) {
                    return ResponseEntity.ok(Map.of("error", "你还没绑定邮箱，请先绑定后再用这种方式验证"));
                }
                email = me.getEmail();
            } else {
                return ResponseEntity.ok(Map.of("error", "不支持的验证码用途: " + raw));
            }
        } else if (!EmailNormalizer.isValidFormat(email)) {
            return ResponseEntity.ok(Map.of("error", "请填写有效的邮箱地址"));
        }

        // 注册场景先挡掉"邮箱已被占用"，省得用户白等一封信（重置场景则要求邮箱必须存在）
        boolean registered = userRepo.existsByEmail(email);
        if (purpose == EmailCodeService.Purpose.REGISTER && registered) {
            return ResponseEntity.ok(Map.of("error", "该邮箱已注册，可直接登录或找回密码"));
        }
        if (purpose == EmailCodeService.Purpose.RESET && !registered) {
            return ResponseEntity.ok(Map.of("error", "该邮箱未注册"));
        }
        // 邮件没配好就别往下走了：先回一句明确的错，而不是先扣额度、先签个码，再发不出去
        // （否则用户会连收两个更莫名其妙的错误：先"发送失败"，再"发送太频繁"）
        // 放在"邮箱是否已注册"之后：那两个判断不消耗任何资源，信息量也更大。
        if (!mailService.isConfigured()) {
            // 把"缺哪一项"直接告诉运维/用户，别让人对着"暂不可用"猜
            return ResponseEntity.ok(Map.of("error", "邮件服务不可用：" + mailService.diagnose()));
        }

        String code;
        try {
            code = emailCodeService.issue(purpose, email);
        } catch (EmailCodeService.RateLimitedException e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));   // 提示语是给人看的，直接回
        }
        try {
            mailService.sendCode(email, purpose.label(), code, emailCodeService.ttlMinutes());
        } catch (MailService.NotConfiguredException e) {
            emailCodeService.rollback(purpose, email);   // 撤回码 + 把当天额度还回去
            return ResponseEntity.ok(Map.of("error", "邮件服务暂不可用，请稍后再试或联系站长"));
        } catch (MailService.QuotaExhaustedException e) {
            // 额度用完属于"今天就是发不了"，不是临时故障：把话说清楚，别让用户反复重试
            emailCodeService.rollback(purpose, email);
            return ResponseEntity.ok(Map.of("error", "今日邮件发送量已达上限，请明天再试；如急需请联系站长"));
        } catch (Exception e) {
            emailCodeService.rollback(purpose, email);
            MaaLog.error("验证码邮件发送失败: " + e.getMessage(), e);
            return ResponseEntity.ok(Map.of("error", "验证码发送失败，请稍后重试"));
        }
        MaaLog.user("已发送验证码邮件 purpose=" + purpose + " to=" + MailService.mask(email));
        return ResponseEntity.ok(Map.of("ok", true, "ttlSec", emailCodeService.ttlMinutes() * 60));
    }

    /** 从可选请求头里取登录用户 id（没登录/令牌失效返回 null，不抛异常） */
    private Long userIdForToken(String authToken) {
        if (authToken == null) return null;
        try {
            return sessions.touch(authToken);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 邮箱转成"能给前端用"的字符串：null → 空串。
     *
     * <p>为什么不让它保持 null：这些响应都是用 {@code Map.of(...)} 拼的，而 {@code Map.of} 对 null 值
     * 直接抛 NPE —— 老用户（还没绑邮箱）每次登录/校验令牌都会 500，属于"上线才发现"的那类事故。
     */
    private static String emailOrEmpty(String email) {
        return email == null ? "" : email;
    }

    /** 注册: 账号从 1000 起自动分配；必须提供邮箱 + 验证码 */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        if (!allowRegistration) {
            return ResponseEntity.ok(Map.of("error", "当前不开放公开注册，请等待内测邀请"));
        }
        String password = body.get("password");
        String username = body.getOrDefault("username", "").trim();
        String email = EmailNormalizer.normalize(body.get("email"));
        String code = body.get("code");

        // 校验顺序：先做"不改状态"的检查，再消费验证码，最后写库 ——
        // 反过来的话，用户名/邮箱重复这种错误会把验证码白白吃掉，用户干等 60 秒才能重试。
        String pwErr = passwordPolicy.validate(password);
        if (pwErr != null) {
            return ResponseEntity.ok(Map.of("error", pwErr));
        }
        if (!EmailNormalizer.isValidFormat(email)) {
            return ResponseEntity.ok(Map.of("error", "请填写有效的邮箱地址"));
        }
        if (userRepo.existsByEmail(email)) {
            return ResponseEntity.ok(Map.of("error", "该邮箱已注册，可直接登录或找回密码"));
        }
        // 默认用户名 = 账号
        boolean usernameAuto = username.isEmpty();
        if (!usernameAuto && userRepo.existsByUsername(username)) {
            return ResponseEntity.ok(Map.of("error", "用户名已被占用，换一个或留空（留空会用账号作为用户名）"));
        }
        if (!emailCodeService.verify(EmailCodeService.Purpose.REGISTER, email, code)) {
            return ResponseEntity.ok(Map.of("error", "邮箱验证码错误或已过期，请重新获取"));
        }

        // 分配账号：走发号器（见 nextAccountNumber），不再用 1000 + count()
        String accountNumber = nextAccountNumber();
        if (usernameAuto) username = accountNumber;
        User user = new User(accountNumber, username, passwordHasher.hash(password));
        user.setEmail(email);
        user.setEmailVerified(true);
        user.setEmailBoundAt(java.time.LocalDateTime.now());
        try {
            userRepo.save(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发下"先查再插"可能漏（两个请求同时通过 existsByEmail）——唯一约束会挡下第二个，
            // 这里翻译成人话，而不是把 500 抛给用户
            MaaLog.error("注册写库冲突（可能是邮箱/用户名并发占用）: " + e.getMessage(), e);
            return ResponseEntity.ok(Map.of("error", "该邮箱或用户名刚被占用，请重试"));
        }

        String token = sessions.issue(user.getId());

        return ResponseEntity.ok(Map.of(
                "token", token,
                "accountNumber", user.getAccountNumber(),
                "username", user.getUsername(),
                "preferenceWeight", user.getPreferenceWeight(),
                "hasApiKey", user.getEncryptedApiKey() != null && !user.getEncryptedApiKey().isEmpty(),
                "enableBlacklist", user.isEnableBlacklist(),
                "enableUserFeedbackRules", user.isEnableUserFeedbackRules(),
                // ⚠️ 必须给非 null 值：Map.of 不接受 null，而老用户的 email 就是 null ——
                // 直接塞 user.getEmail() 会让老用户登录/校验令牌时抛 NPE
                "email", emailOrEmpty(user.getEmail())
        ));
    }

    /** 登录: 账号号 或 邮箱 + 密码 */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String account = body.get("account");
        String password = body.get("password");
        // 先按账号号查（老用户的习惯），查不到再按邮箱查（新人记不住 1005 这种号）
        var userOpt = userRepo.findByAccountNumber(account == null ? null : account.trim());
        if (userOpt.isEmpty()) {
            userOpt = userRepo.findByEmail(EmailNormalizer.normalize(account));
        }
        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "账号或密码错误"));
        }
        User user = userOpt.get();
        if (!passwordHasher.verify(password, user.getPasswordHash())) {
            return ResponseEntity.ok(Map.of("error", "账号或密码错误"));
        }
        // 登录成功 → 顺手把老的"无盐 SHA-256"升级成 PBKDF2（用户无感，不需要改密码）
        if (passwordHasher.needsUpgrade(user.getPasswordHash())) {
            user.setPasswordHash(passwordHasher.hash(password));
            userRepo.save(user);
            MaaLog.user("密码哈希已从旧格式升级为 PBKDF2（account=" + user.getAccountNumber() + "）");
        }
        String token = sessions.issue(user.getId());

        return ResponseEntity.ok(Map.of(
                "token", token,
                "accountNumber", user.getAccountNumber(),
                "username", user.getUsername(),
                "preferenceWeight", user.getPreferenceWeight(),
                "hasApiKey", user.getEncryptedApiKey() != null && !user.getEncryptedApiKey().isEmpty(),
                "enableBlacklist", user.isEnableBlacklist(),
                "enableUserFeedbackRules", user.isEnableUserFeedbackRules(),
                "email", emailOrEmpty(user.getEmail())
        ));
    }

    /**
     * 用邮箱验证码重置密码。
     *
     * <p>两步：先校验新密码的规则（不改状态），再消费验证码 —— 反过来的话，用户填了个不合规的新密码
     * 会把验证码白吃掉，然后要等 60 秒才能再要一个。
     *
     * <p>成功后做两件事：<b>把账号号告诉用户</b>（他八成忘了，这正是"不需要填账号号"的原因），
     * 以及<b>踢掉该账号所有旧会话</b>（改密码的场景通常是"我怀疑密码泄露"，不踢会话等于白改）。
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody Map<String, String> body) {
        String email = EmailNormalizer.normalize(body.get("email"));
        if (!EmailNormalizer.isValidFormat(email)) {
            return ResponseEntity.ok(Map.of("error", "请填写有效的邮箱地址"));
        }
        var userOpt = userRepo.findByEmail(email);
        if (userOpt.isEmpty()) {
            // 按用户要求明确提示（代价是这个接口能被用来查"某邮箱是否是本站用户"）
            return ResponseEntity.ok(Map.of("error", "该邮箱未注册"));
        }
        String newPw = body.get("newPassword");
        String pwErr = passwordPolicy.validate(newPw);
        if (pwErr != null) {
            return ResponseEntity.ok(Map.of("error", pwErr));
        }
        if (!emailCodeService.verify(EmailCodeService.Purpose.RESET, email, body.get("code"))) {
            return ResponseEntity.ok(Map.of("error", "邮箱验证码错误或已过期，请重新获取"));
        }
        User user = userOpt.get();
        user.setPasswordHash(passwordHasher.hash(newPw));
        userRepo.save(user);
        int kicked = sessions.revokeAllOf(user.getId());
        MaaLog.user("邮箱重置密码成功 account=" + user.getAccountNumber()
                + "，已失效该账号旧会话 " + kicked + " 个");
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "accountNumber", user.getAccountNumber(),
                "username", user.getUsername(),
                "message", "密码已重置。你的账号是 " + user.getAccountNumber() + "，请用新密码登录。"
        ));
    }

    /** 更新用户名 */
    @PutMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, String> body) {
        Long userId = sessions.touch(token);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "未登录，请重新登录"));
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) {
            sessions.revoke(token);
            return ResponseEntity.status(401).body(Map.of("error", "用户不存在"));
        }
        User user = userOpt.get();
        if (body.containsKey("username")) user.setUsername(body.get("username"));
        if (body.containsKey("preferenceWeight")) {
            user.setPreferenceWeight(Double.parseDouble(body.get("preferenceWeight")));
        }
        if (body.containsKey("apiKey")) {
            user.setEncryptedApiKey(encrypt(body.get("apiKey")));
        }
        if (body.containsKey("enableBlacklist")) {
            user.setEnableBlacklist(Boolean.parseBoolean(body.get("enableBlacklist")));
        }
        if (body.containsKey("enableUserFeedbackRules")) {
            user.setEnableUserFeedbackRules(Boolean.parseBoolean(body.get("enableUserFeedbackRules")));
        }
        userRepo.save(user);
        return ResponseEntity.ok(Map.of(
                "username", user.getUsername(),
                "preferenceWeight", user.getPreferenceWeight(),
                "hasApiKey", user.getEncryptedApiKey() != null && !user.getEncryptedApiKey().isEmpty()
        ));
    }

    /**
     * 修改密码。
     *
     * <p>验证方式按"有没有绑邮箱"二选一，不搞双重验证：
     * <ul>
     *   <li><b>绑了邮箱</b> → 只验邮箱验证码（发到绑定邮箱）。理由和"找回密码"一致：
     *       能收到那封信就已经证明是本人；再要求输一遍当前密码，对用户是纯负担
     *       （很多人记不住当前密码，正是因为记不住才来改密码）；</li>
     *   <li><b>没绑邮箱</b>（老用户）→ 只能用当前密码证明身份，否则谁都能改别人的密码。</li>
     * </ul>
     *
     * <p>校验顺序有讲究：先做"零成本"的检查（老用户的当前密码、新密码规则），
     * <b>最后</b>才消费邮箱验证码 —— 否则用户仅仅是新密码不合规，就把验证码白白用掉了，
     * 还得等 60 秒再要一个。
     */
    @PutMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, String> body) {
        Long userId = sessions.touch(token);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "用户不存在"));
        User user = userOpt.get();

        String newPw = body.get("newPassword");
        boolean hasEmail = user.getEmail() != null && !user.getEmail().isBlank();

        if (!hasEmail) {
            // 没绑邮箱：只能靠当前密码证明身份
            String oldPw = body.get("oldPassword");
            if (oldPw == null || !passwordHasher.verify(oldPw, user.getPasswordHash())) {
                return ResponseEntity.ok(Map.of("error", "当前密码错误"));
            }
        }
        // 新密码走统一规则（8~64 位 + 字母数字）。老密码不受影响：登录不校验规则，否则 4 位老密码
        // 的人会被锁在门外。
        String pwErr = passwordPolicy.validate(newPw);
        if (pwErr != null) {
            return ResponseEntity.ok(Map.of("error", pwErr));
        }
        // 邮箱验证码放在最后：它是唯一"会被消费"的东西，前面任何校验失败都不该动它
        if (hasEmail) {
            if (!emailCodeService.verify(EmailCodeService.Purpose.CHANGE_PASSWORD, user.getEmail(), body.get("code"))) {
                return ResponseEntity.ok(Map.of("error", "邮箱验证码错误或已过期；验证码已发到你的绑定邮箱"));
            }
        }
        user.setPasswordHash(passwordHasher.hash(newPw));
        userRepo.save(user);
        // 改密码的意图通常是"我怀疑密码泄露了"：把其它设备全踢掉，但保留当前这台（否则用户刚改完
        // 就被自己踢下线，体验莫名其妙）。reset-password 那条路径没有"当前会话"的概念，所以全踢。
        int kicked = sessions.revokeAllOf(user.getId(), token);
        if (kicked > 0) MaaLog.user("改密码成功，已失效该账号其它会话 " + kicked + " 个");
        return ResponseEntity.ok(Map.of("ok", true, "message", "密码修改成功"));
    }

    /**
     * 绑定 / 换绑邮箱（需登录，只验新邮箱的验证码）。
     *
     * <p>按用户要求去掉了"旧邮箱验证码"这道关（换绑时少一次收信），只保留新邮箱的验证码 ——
     * 新邮箱的码仍然是必要的：它证明这个地址真的能收到信，否则打错一个字母就把找回通道绑到
     * 别人的地址上了。
     *
     * <p><b>要清楚的残留风险</b>：拿到别人会话（例如公用电脑没退登录）的人，可以把邮箱换绑成自己的，
     * 之后用"邮箱 + 验证码"重置密码，从而永久接管这个账号。以前那道旧邮箱验证码就是防这个的。
     * 内测阶段用户量小、风险可控；如果以后要对外，建议在这一步补一个"再输一次登录密码"的校验
     * （比收邮件便宜，也能挡住会话被盗）。要加的话告诉我。
     */
    @PostMapping("/bind-email")
    public ResponseEntity<Map<String, Object>> bindEmail(
            @RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, String> body) {
        Long userId = sessions.touch(token);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "用户不存在"));
        User user = userOpt.get();

        String newEmail = EmailNormalizer.normalize(body.get("newEmail"));
        if (!EmailNormalizer.isValidFormat(newEmail)) {
            return ResponseEntity.ok(Map.of("error", "请填写有效的邮箱地址"));
        }
        boolean hasOld = user.getEmail() != null && !user.getEmail().isBlank();
        if (hasOld && newEmail.equals(user.getEmail())) {
            return ResponseEntity.ok(Map.of("error", "这就是你当前绑定的邮箱，无需换绑"));
        }
        // 先查"新邮箱是否被别的账号占用"（纯查询，不消费验证码）；并发下唯一约束兜底
        if (userRepo.existsByEmail(newEmail)) {
            return ResponseEntity.ok(Map.of("error", "该邮箱已被其它账号绑定"));
        }
        if (!emailCodeService.verify(EmailCodeService.Purpose.BIND_NEW, newEmail, body.get("newCode"))) {
            return ResponseEntity.ok(Map.of("error", "新邮箱的验证码错误或已过期"));
        }

        String oldEmail = user.getEmail();
        user.setEmail(newEmail);
        user.setEmailVerified(true);
        user.setEmailBoundAt(java.time.LocalDateTime.now());
        try {
            userRepo.save(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            MaaLog.error("换绑邮箱写库冲突: " + e.getMessage(), e);
            return ResponseEntity.ok(Map.of("error", "该邮箱刚被其它账号绑定，请换一个"));
        }
        // 和改密码同理：换绑邮箱等于换了找回通道，别的设备上的会话应当失效（当前这台留着）
        int kicked = sessions.revokeAllOf(user.getId(), token);
        MaaLog.user("换绑邮箱成功 account=" + user.getAccountNumber()
                + " " + MailService.mask(oldEmail) + " -> " + MailService.mask(newEmail)
                + "，已失效其它会话 " + kicked + " 个");
        return ResponseEntity.ok(Map.of("ok", true, "email", user.getEmail(),
                "message", hasOld ? "邮箱已换绑" : "邮箱已绑定"));
    }

    /**
     * 解绑邮箱（需登录，一次点击即可，按用户要求不再要验证码）。
     *
     * <p>代价说清楚：解绑后忘记密码就无法自助找回了（只能人工处理），而且拿到别人会话的人
     * 也能一键解绑——相当于把号主"以后找回密码"的路堵死。前端有醒目提示，日志留痕。
     */
    @PostMapping("/unbind-email")
    public ResponseEntity<Map<String, Object>> unbindEmail(
            @RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, String> body) {
        Long userId = sessions.touch(token);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "用户不存在"));
        User user = userOpt.get();

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return ResponseEntity.ok(Map.of("error", "你还没绑定邮箱"));
        }
        String removed = user.getEmail();
        user.setEmail(null);
        user.setEmailVerified(false);
        user.setEmailBoundAt(null);
        userRepo.save(user);
        int kicked = sessions.revokeAllOf(user.getId(), token);
        MaaLog.user("解绑邮箱 account=" + user.getAccountNumber() + " " + MailService.mask(removed)
                + "，已失效其它会话 " + kicked + " 个");
        return ResponseEntity.ok(Map.of("ok", true, "email", "",
                "message", "邮箱已解绑。解绑后忘记密码将无法自助找回，请牢记密码或尽快重新绑定。"));
    }

    /** 验证 token 并返回 userId, null 表示无效 */
    public Long validateToken(String token) {
        return sessions.touch(token);   // 内部已挡 null；顺便滑动续期
    }

    /** 根据 token 获取用户对象 (用于获取 accountNumber 等) */
    public Optional<User> getUserByToken(String token) {
        Long uid = sessions.touch(token);
        if (uid == null) return Optional.empty();
        return userRepo.findById(uid);
    }

    /**
     * 这次请求该不该"参考用户反馈规则"（{@code USER_FEEDBACK} 来源的知识库规则）。
     *
     * <p><b>口径只在这里一处</b>：未登录 / token 失效 = 不参考；登录了就看用户设置里的开关。
     * 依赖穿透（chat）与预览画边都从这里取，两条链路才不会各拿一份规则集
     * —— 之前"用户规则把节点拉进图里、却不画线"就是两边口径不一致造成的。
     *
     * <p>不再采信请求体里的 {@code excludeUserFeedbackRules}：那是客户端根据 localStorage 里的
     * 旧快照算出来的，服务端设置改了它就过期了。
     *
     * @return true = 本次解析要排除 USER_FEEDBACK 来源
     */
    public boolean excludesUserFeedbackRules(String token) {
        Long uid = sessions.touch(token);
        if (uid == null) return true;
        return userRepo.findById(uid)
                .map(user -> !user.isEnableUserFeedbackRules())
                .orElse(true);
    }

    /**
     * 退出登录：服务端立刻作废这个 token。
     *
     * <p>为什么要有：前端 {@code logout} 只删了 localStorage，token 本身还是有效的 ——
     * 网络抓包、共用电脑、浏览器同步都会把它带出去。没有这个端点，"退出登录"是假的。
     *
     * <p>token 缺失也返回 ok：调用方唯一想听的就是"你已经退出了"，为此报错没有意义。
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessions.revoke(token);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 前端页面加载时校验 token 是否有效，同步登录状态 */
    @GetMapping("/check-token")
    public ResponseEntity<Map<String, Object>> checkToken(@RequestHeader("X-Auth-Token") String token) {
        Long userId = sessions.touch(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "未登录"));
        }
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) {
            sessions.revoke(token);
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "用户不存在"));
        }
        User user = userOpt.get();
        return ResponseEntity.ok(Map.of(
                "valid", true,
                "accountNumber", user.getAccountNumber(),
                "username", user.getUsername(),
                "preferenceWeight", user.getPreferenceWeight(),
                "hasApiKey", user.getEncryptedApiKey() != null && !user.getEncryptedApiKey().isEmpty(),
                "enableBlacklist", user.isEnableBlacklist(),
                "enableUserFeedbackRules", user.isEnableUserFeedbackRules(),
                // 邮箱给前端用来显示"当前绑定邮箱"，以及判断要不要提示老用户去绑定
                "email", emailOrEmpty(user.getEmail())
        ));
    }

    /** 根据 token 获取用户解密后的 API Key */
    public String getUserApiKey(String token) {
        Long uid = sessions.touch(token);
        if (uid == null) return null;
        return userRepo.findById(uid)
                .map(u -> decrypt(u.getEncryptedApiKey()))
                .orElse(null);
    }

    /**
     * 分配下一个账号号。
     *
     * <p>老实现是 {@code 1000 + userRepo.count()}，有两个真实的坑：
     * <ul>
     *   <li><b>并发撞号</b>：两个注册请求同时读到 count=5，都发 1005。accountNumber 有唯一约束，
     *       所以不会是"两个人共用一个号"，而是后保存的那个抛异常 —— 用户看到 500。更糟的是
     *       如果约束被人为去掉，两个账号同号会让 {@code findByAccountNumber} 直接抛
     *       {@code IncorrectResultSizeDataAccessException}，<b>两个人都登不上</b>。</li>
     *   <li><b>删号后回退</b>：删掉一个账号后 count 变小，会发出一个<b>已经存在</b>的号 —— 单线程也能撞。</li>
     * </ul>
     *
     * <p>现在改成：进程内游标 + 首次启动时从库里"历史最大号"往后接，再逐个跳过已被占用的号。
     * 用 {@code synchronized} 保证"取号"这个动作在单进程内是原子的（单实例部署，这就是全部竞争面）；
     * 数据库上的唯一约束继续当最后一道保险 —— 万一将来变成多实例，冲突会明确报错，而不是静默发重号。
     */
    private synchronized String nextAccountNumber() {
        if (lastAccountNumber == 0) {
            Long max = userRepo.findMaxAccountNumber();
            lastAccountNumber = Math.max(999L, max == null ? 0L : max);
        }
        while (true) {
            lastAccountNumber++;
            String candidate = String.valueOf(lastAccountNumber);
            if (!userRepo.existsByAccountNumber(candidate)) return candidate;
        }
    }

    // AES 加密/解密 (密钥 = SHA-256 of server-side secret)
    private SecretKeySpec getKey() throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptionSecret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    private String encrypt(String plain) {
        if (plain == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, getKey());
            String result = Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
//            System.out.println("AES 加密成功 (前6位): " + result.substring(0, Math.min(6, result.length())) + "...");
            System.out.println("AES 加密成功！");
            return result;
        } catch (Exception e) {
            System.err.println("AES 加密失败: " + e.getMessage());
            return null;
        }
    }

    private String decrypt(String encrypted) {
        if (encrypted == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, getKey());
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("AES 解密失败（可能是加密密钥已变更，请用户重新设置 API Key）: " + e.getMessage());
            return null;
        }
    }
}
