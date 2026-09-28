package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import yagen.waitmydawn.maa.model.ChatMessageRepository;
import yagen.waitmydawn.maa.model.ConversationRepository;
import yagen.waitmydawn.maa.model.User;
import yagen.waitmydawn.maa.model.UserRepository;
import yagen.waitmydawn.maa.service.EmailCodeService;
import yagen.waitmydawn.maa.service.MailService;
import yagen.waitmydawn.maa.service.PasswordHasher;
import yagen.waitmydawn.maa.service.PasswordPolicy;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录之后的邮箱操作：改密码（要邮箱验证码）、绑定 / 换绑邮箱（两个码）。
 *
 * <p>这几条路径都要先拿到**登录令牌**，而令牌里存的是 userId —— 所以假仓储要自带自增 id
 * （用反射设，因为 User 没有 setId）。这样测的就是真实调用链：注册/登录拿令牌 → 带令牌调接口。
 *
 * <p>重点覆盖最容易出事的地方：老用户（没邮箱）改密码不该被验证码卡住；换绑必须验旧邮箱，
 * 否则拿到别人会话就能把账号抢走；新密码不合规 / 验证码不对时不能被白白消费；
 * 以及"改密码的码只能发到绑定邮箱，不能听请求体的"。
 */
class EmailBindFlowTest {

    private static final String PW = "abcd1234";

    private final Map<String, User> byEmail = new LinkedHashMap<>();
    private final Map<Long, User> byId = new LinkedHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(100);
    private long now = 1_700_000_000_000L;

    private UserRepository userRepo;
    private EmailCodeService codes;
    private UserController controller;
    private JavaMailSender mailSender;

    @BeforeEach
    void setUp() {
        byEmail.clear();
        byId.clear();
        idSeq.set(100);
        now = 1_700_000_000_000L;
        userRepo = mock(UserRepository.class);
        mailSender = mock(JavaMailSender.class);

        when(userRepo.existsByEmail(anyString())).thenAnswer(i -> byEmail.containsKey(i.getArgument(0)));
        when(userRepo.existsByUsername(anyString())).thenReturn(false);
        when(userRepo.existsByAccountNumber(anyString())).thenReturn(false);
        when(userRepo.findMaxAccountNumber()).thenReturn(1000L);
        when(userRepo.findByEmail(anyString())).thenAnswer(i -> Optional.ofNullable(byEmail.get(i.getArgument(0))));
        when(userRepo.findById(anyLong()))
                .thenAnswer(i -> Optional.ofNullable(byId.get(((Number) i.getArgument(0)).longValue())));
        when(userRepo.findByAccountNumber(anyString()))
                // 用 byId 遍历：老用户没有邮箱，不在 byEmail 索引里，拿 byEmail 会漏掉他们
                .thenAnswer(i -> byId.values().stream()
                        .filter(u -> u.getAccountNumber().equals(i.getArgument(0))).findFirst());
        when(userRepo.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            if (u.getId() == null) setId(u, idSeq.incrementAndGet());
            byId.put(u.getId(), u);
            // 每次保存都按 id 重建邮箱索引：换绑之后旧邮箱必须从索引里消失，
            // 否则"旧邮箱还能查到人"会掩盖真实行为（我第一版就是因为这个把断言写反了）
            byEmail.clear();
            byId.values().forEach(u2 -> { if (u2.getEmail() != null) byEmail.put(u2.getEmail(), u2); });
            return u;
        });

        codes = new EmailCodeService(3, 60_000, () -> now);
        controller = build(null);
    }

    /** 造一个控制器；传了 sender 就是"邮件已配置"，否则邮件不可用 */
    private UserController build(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = new ObjectProvider<>() {
            @Override public JavaMailSender getObject() {
                if (sender == null) throw new IllegalStateException("no mail");
                return sender;
            }
            @Override public JavaMailSender getIfAvailable() { return sender; }
        };
        return new UserController(userRepo, mock(ConversationRepository.class),
                mock(ChatMessageRepository.class), "test-secret", true, 30,
                new PasswordHasher(1), new PasswordPolicy(), codes,
                new MailService(provider, sender != null, sender == null ? "" : "noreply@example.com", "MAA"));
    }

    private static void setId(User u, long id) {
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 取码并把"时间"推过 60 秒冷却（同一邮箱才能再发一个码） */
    private String codeFor(EmailCodeService.Purpose purpose, String email) {
        String c = codes.issue(purpose, email);
        now += 61_000L;
        return c;
    }

    private String registerAndGetToken(String email) {
        return registerAndGetToken(controller, email);
    }

    /** 必须用**同一个控制器实例**注册：会话表（SessionRegistry）是每个控制器实例自己的一份 */
    private String registerAndGetToken(UserController c, String email) {
        String code = codeFor(EmailCodeService.Purpose.REGISTER, email);
        Map<String, Object> body = c.register(Map.of(
                "email", email, "username", "u-" + email, "password", PW, "code", code)).getBody();
        assertNull(body.get("error"), "注册本该成功：" + body);
        return String.valueOf(body.get("token"));
    }

    /** 老用户：直接塞进仓储，没有邮箱 */
    private User legacyUser(String accountNumber) {
        User u = new User(accountNumber, "legacy-" + accountNumber, new PasswordHasher(1).hash(PW));
        setId(u, idSeq.incrementAndGet());
        byId.put(u.getId(), u);
        return u;
    }

    private String loginTokenFor(String account) {
        Map<String, Object> body = controller.login(Map.of("account", account, "password", PW)).getBody();
        assertNull(body.get("error"), "登录本该成功：" + body);
        return String.valueOf(body.get("token"));
    }

    private String err(Map<String, Object> body) {
        Object e = body.get("error");
        return e == null ? null : String.valueOf(e);
    }

    // ---------- 绑定 / 换绑 ----------

    @Test
    @DisplayName("首次绑定邮箱：只需要新邮箱的码（老用户没有旧邮箱可验）")
    void firstTimeBindNeedsOnlyNewCode() {
        User legacy = legacyUser("1005");
        String token = loginTokenFor("1005");
        String newCode = codeFor(EmailCodeService.Purpose.BIND_NEW, "new@example.com");

        Map<String, Object> body = controller.bindEmail(token,
                Map.of("newEmail", "new@example.com", "newCode", newCode)).getBody();

        assertNull(err(body), "首次绑定本该成功：" + body);
        assertEquals("new@example.com", body.get("email"));
        assertEquals("new@example.com", legacy.getEmail());
        assertTrue(legacy.isEmailVerified());
    }

    @Test
    @DisplayName("换绑：只需要新邮箱的验证码（按用户要求去掉了旧邮箱那道关）")
    void rebindNeedsOnlyNewCode() {
        String token = registerAndGetToken("old@example.com");

        // 不给新邮箱的码 → 拒（这一步仍然必要：它证明这个地址真的能收信，否则打错一个字母
        // 就把找回通道绑到别人邮箱上了）
        assertTrue(String.valueOf(err(controller.bindEmail(token,
                Map.of("newEmail", "new@example.com")).getBody())).contains("验证码"));
        // 码错 → 拒
        assertTrue(String.valueOf(err(controller.bindEmail(token,
                Map.of("newEmail", "new@example.com", "newCode", "000000")).getBody())).contains("验证码"));

        String newCode = codeFor(EmailCodeService.Purpose.BIND_NEW, "new@example.com");
        Map<String, Object> ok = controller.bindEmail(token,
                Map.of("newEmail", "new@example.com", "newCode", newCode)).getBody();
        assertNull(err(ok), "带对新邮箱的码就该成功：" + ok);
        assertEquals("new@example.com", ok.get("email"));
        assertFalse(byEmail.containsKey("old@example.com"), "旧邮箱应已解绑：" + byEmail.keySet());
    }

    @Test
    @DisplayName("换绑到别人的邮箱 / 换绑成当前邮箱 → 提前拒绝（不浪费验证码）")
    void rebindGuards() {
        String tokenA = registerAndGetToken("a@example.com");
        registerAndGetToken("b@example.com");

        assertEquals("该邮箱已被其它账号绑定", err(controller.bindEmail(tokenA,
                Map.of("newEmail", "b@example.com", "newCode", "123456")).getBody()));
        assertTrue(String.valueOf(err(controller.bindEmail(tokenA,
                Map.of("newEmail", "a@example.com", "newCode", "123456")).getBody())).contains("当前绑定"));
    }

    @Test
    @DisplayName("未登录不许绑定")
    void bindRequiresLogin() {
        assertEquals(401, controller.bindEmail("bogus-token",
                Map.of("newEmail", "x@example.com", "newCode", "1")).getStatusCode().value());
    }

    // ---------- 解绑 ----------

    @Test
    @DisplayName("解绑：一次点击即可（不再要验证码）；email 清空，且旧邮箱可以被别人再绑定")
    void unbindEmailTakesOneClick() {
        String token = registerAndGetToken("me@example.com");

        Map<String, Object> ok = controller.unbindEmail(token, Map.of()).getBody();
        assertNull(err(ok), "不带验证码就该能解绑：" + ok);
        assertEquals("", ok.get("email"));
        assertFalse(byEmail.containsKey("me@example.com"), "解绑后这个邮箱不该再绑着人：" + byEmail.keySet());

        // 解绑之后这个邮箱可以被另一个账号绑定（唯一约束只限制"同时被一个人占着"）
        String otherToken = registerAndGetToken("other@example.com");
        String newCode = codeFor(EmailCodeService.Purpose.BIND_NEW, "me@example.com");
        Map<String, Object> rebind = controller.bindEmail(otherToken,
                Map.of("newEmail", "me@example.com", "newCode", newCode)).getBody();
        assertNull(err(rebind), "空出来的邮箱应该能被别人绑定：" + rebind);
    }

    @Test
    @DisplayName("解绑：没绑邮箱时明确提示")
    void unbindGuards() {
        legacyUser("1009");
        String token = loginTokenFor("1009");
        assertTrue(String.valueOf(err(controller.unbindEmail(token, Map.of()).getBody()))
                .contains("还没绑定邮箱"));
    }

    // ---------- 改密码 ----------

    @Test
    @DisplayName("有邮箱：只认邮箱验证码 —— 不带当前密码也能改（不需要它）")
    void changePasswordNeedsEmailCodeWhenBound() {
        String token = registerAndGetToken("user@example.com");

        // 不带码 → 拒，且密码不变（说明"没带当前密码"不是拒绝原因）
        Map<String, Object> noCode = controller.changePassword(token,
                Map.of("newPassword", "newpass123")).getBody();
        assertTrue(String.valueOf(err(noCode)).contains("验证码"), noCode.toString());
        assertNull(err(controller.login(Map.of("account", "user@example.com", "password", PW)).getBody()),
                "没带验证码时旧密码必须还能登录（说明密码没被改掉）");

        String code = codeFor(EmailCodeService.Purpose.CHANGE_PASSWORD, "user@example.com");
        // 关键：**不带 oldPassword** 也要能改成功（前端有邮箱时就不再显示/发送当前密码）
        Map<String, Object> ok = controller.changePassword(token,
                Map.of("newPassword", "newpass123", "code", code)).getBody();
        assertNull(err(ok), "有邮箱时只凭验证码就该能改：" + ok);
        assertNull(err(controller.login(Map.of("account", "user@example.com", "password", "newpass123")).getBody()));
    }

    @Test
    @DisplayName("有邮箱：给了错误的当前密码也不该影响（后端压根不看它）")
    void changePasswordIgnoresOldPasswordWhenBound() {
        String token = registerAndGetToken("user@example.com");
        String code = codeFor(EmailCodeService.Purpose.CHANGE_PASSWORD, "user@example.com");
        Map<String, Object> ok = controller.changePassword(token,
                Map.of("oldPassword", "definitely-wrong", "newPassword", "newpass123", "code", code)).getBody();
        assertNull(err(ok), "有邮箱时当前密码不参与校验：" + ok);
    }

    @Test
    @DisplayName("老用户（没邮箱）：当前密码必填，且不会去看验证码")
    void legacyUserStillNeedsOldPassword() {
        legacyUser("1010");
        String token = loginTokenFor("1010");
        Map<String, Object> noOld = controller.changePassword(token,
                Map.of("newPassword", "newpass123")).getBody();
        assertTrue(String.valueOf(err(noOld)).contains("当前密码"), noOld.toString());

        Map<String, Object> wrongOld = controller.changePassword(token,
                Map.of("oldPassword", "wrong-abcd1", "newPassword", "newpass123")).getBody();
        assertTrue(String.valueOf(err(wrongOld)).contains("当前密码"), wrongOld.toString());
    }

    @Test
    @DisplayName("没邮箱的老用户：只验旧密码就能改（否则他们永远换不了密码）")
    void legacyUserCanChangePasswordWithoutCode() {
        legacyUser("1006");
        String token = loginTokenFor("1006");

        Map<String, Object> ok = controller.changePassword(token,
                Map.of("oldPassword", PW, "newPassword", "newpass123")).getBody();

        assertNull(err(ok), "老用户本该能直接改：" + ok);
        assertNull(err(controller.login(Map.of("account", "1006", "password", "newpass123")).getBody()));
    }

    @Test
    @DisplayName("新密码不合规时不消费验证码（改完密码还能用同一个码）")
    void badNewPasswordDoesNotConsumeCode() {
        String token = registerAndGetToken("user@example.com");
        String code = codeFor(EmailCodeService.Purpose.CHANGE_PASSWORD, "user@example.com");

        Map<String, Object> bad = controller.changePassword(token,
                Map.of("oldPassword", PW, "newPassword", "12345678", "code", code)).getBody();
        assertTrue(String.valueOf(err(bad)).contains("字母和数字"), bad.toString());

        Map<String, Object> ok = controller.changePassword(token,
                Map.of("oldPassword", PW, "newPassword", "newpass123", "code", code)).getBody();
        assertNull(err(ok), "同一个码应该还能用：" + ok);
    }

    // ---------- 发码接口（登录态用途）----------

    @Test
    @DisplayName("改密码的验证码只发到当前绑定邮箱，请求体里指定的地址会被忽略")
    void changePasswordCodeGoesToBoundMailboxOnly() {
        UserController withMail = build(mailSender);
        String token = registerAndGetToken(withMail, "me@example.com");

        Map<String, Object> body = withMail.sendEmailCode(
                Map.of("purpose", "change_password", "email", "attacker@example.com"), token).getBody();
        assertNull(err(body), "发码本该成功：" + body);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertEquals("me@example.com", captor.getValue().getTo()[0],
                "收件人必须是登录账号的绑定邮箱，不能听请求体的");
    }

    @Test
    @DisplayName("未登录不许要改密码/换绑的验证码")
    void loggedInPurposesRequireLogin() {
        assertEquals(401, controller.sendEmailCode(
                Map.of("purpose", "change_password"), null).getStatusCode().value());
        assertEquals(401, controller.sendEmailCode(
                Map.of("purpose", "bind_new", "email", "x@example.com"), null).getStatusCode().value());
    }

    @Test
    @DisplayName("没绑邮箱却要改密码的验证码 → 明确提示先绑定")
    void changePasswordCodeWithoutBoundEmail() {
        legacyUser("1007");
        String token = loginTokenFor("1007");
        assertTrue(String.valueOf(err(controller.sendEmailCode(
                Map.of("purpose", "change_password"), token).getBody())).contains("还没绑定邮箱"));
    }

    // ---------- 响应字段 ----------

    @Test
    @DisplayName("老用户没邮箱时登录/校验令牌不能因为 email 为 null 而 500")
    void nullEmailDoesNotBreakResponses() {
        legacyUser("1008");
        String token = loginTokenFor("1008");
        assertEquals("", controller.login(Map.of("account", "1008", "password", PW)).getBody().get("email"),
                "没邮箱要返回空串 —— Map.of 不接受 null，塞 null 会直接 NPE");
        assertEquals("", controller.checkToken(token).getBody().get("email"));
    }

    @Test
    @DisplayName("绑了邮箱之后 check-token 会带上它（设置页显示用）")
    void checkTokenReturnsEmail() {
        String token = registerAndGetToken("me@example.com");
        assertEquals("me@example.com", controller.checkToken(token).getBody().get("email"));
    }
}
