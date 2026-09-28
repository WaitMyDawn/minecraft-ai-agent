package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import yagen.waitmydawn.maa.model.ChatMessageRepository;
import yagen.waitmydawn.maa.model.ConversationRepository;
import yagen.waitmydawn.maa.model.User;
import yagen.waitmydawn.maa.model.UserRepository;
import yagen.waitmydawn.maa.service.EmailCodeService;
import yagen.waitmydawn.maa.service.MailService;
import yagen.waitmydawn.maa.service.PasswordHasher;
import yagen.waitmydawn.maa.service.PasswordPolicy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 邮箱注册/登录/找回密码的流程测试。
 *
 * <p>仓储用"内存版"替身（不是纯 stub）：这样测试能像真实用户一样走完整流程 ——
 * 注册完能用邮箱登录、改了密码能用新密码登录 —— 而不是只断言"某个方法被调用过"。
 *
 * <p>重点覆盖那些**只在真机才会踩到**的边界：大小写不同的邮箱是同一个账号、验证码被用途串用、
 * 密码不合规时不能白吃掉验证码、重置密码要能把账号号告诉用户。
 */
class EmailAuthFlowTest {

    private static final String EMAIL = "user@example.com";
    private static final String PW = "abcd1234";

    private final Map<String, User> byEmail = new LinkedHashMap<>();
    private UserRepository userRepo;
    private EmailCodeService codes;
    private UserController controller;
    private JavaMailSender mailSender;
    /** 假时钟：只有推进它才能在同一封邮箱上连发两次码（否则被 60 秒冷却挡住——那是设计如此） */
    private long now = 1_700_000_000_000L;

    @BeforeEach
    void setUp() {
        byEmail.clear();
        userRepo = mock(UserRepository.class);

        // 内存版仓储：够用就好，重点是让流程真的跑通
        when(userRepo.existsByEmail(anyString())).thenAnswer(i -> byEmail.containsKey(i.getArgument(0)));
        when(userRepo.existsByUsername(anyString())).thenReturn(false);
        when(userRepo.existsByAccountNumber(anyString())).thenReturn(false);
        when(userRepo.findMaxAccountNumber()).thenReturn(1000L);
        when(userRepo.findByEmail(anyString())).thenAnswer(i -> Optional.ofNullable(byEmail.get(i.getArgument(0))));
        when(userRepo.findByAccountNumber(anyString()))
                .thenAnswer(i -> byEmail.values().stream()
                        .filter(u -> u.getAccountNumber().equals(i.getArgument(0))).findFirst());
        when(userRepo.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            byEmail.put(u.getEmail(), u);
            return u;
        });

        now = 1_700_000_000_000L;
        codes = new EmailCodeService(3, 60_000, () -> now);
        mailSender = mock(JavaMailSender.class);
        controller = new UserController(userRepo, mock(ConversationRepository.class),
                mock(ChatMessageRepository.class), "test-secret", true, 30,
                new PasswordHasher(1), new PasswordPolicy(), codes,
                new MailService(provider(null), false, "", "MAA"));      // 默认：邮件未配置
    }

    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        return new ObjectProvider<>() {
            @Override public JavaMailSender getObject() {
                if (sender == null) throw new IllegalStateException("no mail");
                return sender;
            }
            @Override public JavaMailSender getIfAvailable() { return sender; }
        };
    }

    /** 另造一个"邮件配好了"的控制器：用来测发送成功、以及发送之后的限流路径 */
    private UserController withMail() {
        return new UserController(userRepo, mock(ConversationRepository.class),
                mock(ChatMessageRepository.class), "test-secret", true, 30,
                new PasswordHasher(1), new PasswordPolicy(), codes,
                new MailService(provider(mailSender), true, "noreply@example.com", "MAA"));
    }

    private Map<String, Object> register(String email, String username, String password, String code) {
        return controller.register(Map.of(
                "email", email, "username", username, "password", password, "code", code)).getBody();
    }

    private Map<String, Object> registerOk(String email) {
        String code = codeFor(EmailCodeService.Purpose.REGISTER, email);
        Map<String, Object> body = register(email, "u-" + email, PW, code);
        assertNull(body.get("error"), "注册本该成功：" + body);
        return body;
    }

    /** 取码并把"时间"往前推 61 秒：模拟用户等过了冷却再点下一次（验证码 TTL 5 分钟，仍然有效） */
    private String codeFor(EmailCodeService.Purpose purpose, String email) {
        String code = codes.issue(purpose, email);
        now += 61_000L;
        return code;
    }

    @Test
    @DisplayName("注册成功：返回 token/账号号/邮箱，且邮箱归一化成小写存库")
    void registerStoresNormalizedEmail() {
        String code = codeFor(EmailCodeService.Purpose.REGISTER, "  User@Example.COM ");
        Map<String, Object> body = register("  User@Example.COM ", "alice", PW, code);

        assertNull(body.get("error"));
        assertEquals("1001", body.get("accountNumber"));
        assertEquals(EMAIL, body.get("email"));
        assertNotNull(body.get("token"));
        assertTrue(byEmail.containsKey(EMAIL), "库里应存小写：" + byEmail.keySet());
    }

    @Test
    @DisplayName("同一邮箱换个大小写再注册 → 被拒（数据库里就是同一个账号）")
    void sameMailboxDifferentCaseCannotRegisterTwice() {
        registerOk(EMAIL);
        String code = codeFor(EmailCodeService.Purpose.REGISTER, "USER@EXAMPLE.COM");
        Map<String, Object> body = register("USER@EXAMPLE.COM", "bob", PW, code);
        assertEquals("该邮箱已注册，可直接登录或找回密码", body.get("error"));
    }

    @Test
    @DisplayName("验证码错误 → 注册失败；密码不合规时不能白吃掉验证码")
    void codeAndPasswordChecks() {
        String code = codeFor(EmailCodeService.Purpose.REGISTER, EMAIL);

        Map<String, Object> badPw = register(EMAIL, "alice", "12345678", code);   // 纯数字
        assertTrue(String.valueOf(badPw.get("error")).contains("字母和数字"), "应提示密码规则：" + badPw);

        // 关键：上面那次失败不能把码用掉，用户改完密码还能用同一个码注册
        Map<String, Object> ok = register(EMAIL, "alice", PW, code);
        assertNull(ok.get("error"), "密码不合规不该消费验证码：" + ok);
    }

    @Test
    @DisplayName("密码规则：8 位通过；纯字母/纯数字/7 位被拒")
    void passwordPolicyEnforcedOnRegister() {
        for (String bad : new String[]{"abcdefgh", "12345678", "abc1234"}) {
            String email = bad + "@example.com";
            String code = codeFor(EmailCodeService.Purpose.REGISTER, email);
            assertNotNull(register(email, "u-" + bad, bad, code).get("error"), "这本该被拒：" + bad);
        }
        String code = codeFor(EmailCodeService.Purpose.REGISTER, "good@example.com");
        assertNull(register("good@example.com", "good", "abc12345", code).get("error"));
    }

    @Test
    @DisplayName("用途串用被拒：重置密码的码不能拿去注册")
    void purposeCannotBeSwapped() {
        registerOk(EMAIL);
        String resetCode = codeFor(EmailCodeService.Purpose.RESET, "new@example.com");
        Map<String, Object> body = register("new@example.com", "bob", PW, resetCode);
        assertTrue(String.valueOf(body.get("error")).contains("验证码"), "应用注册用途的码：" + body);
    }

    @Test
    @DisplayName("登录：账号号可以，邮箱可以，大小写不影响，密码错就拒")
    void loginByIdOrEmail() {
        Map<String, Object> reg = registerOk(EMAIL);
        String accountNumber = String.valueOf(reg.get("accountNumber"));

        assertNull(controller.login(Map.of("account", accountNumber, "password", PW)).getBody().get("error"));
        assertNull(controller.login(Map.of("account", EMAIL, "password", PW)).getBody().get("error"));
        assertNull(controller.login(Map.of("account", "USER@Example.com", "password", PW)).getBody().get("error"),
                "邮箱大写也要能登进来");
        assertEquals("账号或密码错误",
                controller.login(Map.of("account", EMAIL, "password", "wrong1234")).getBody().get("error"));
        assertEquals("账号或密码错误",
                controller.login(Map.of("account", "nobody@example.com", "password", PW)).getBody().get("error"));
    }

    @Test
    @DisplayName("找回密码：成功 → 返回账号号（用户忘了号）；随后新密码能登录、旧密码不能")
    void resetPasswordReturnsAccountNumber() {
        Map<String, Object> reg = registerOk(EMAIL);
        String accountNumber = String.valueOf(reg.get("accountNumber"));
        String code = codeFor(EmailCodeService.Purpose.RESET, EMAIL);

        Map<String, Object> body = controller.resetPassword(
                Map.of("email", EMAIL, "code", code, "newPassword", "newpass123")).getBody();

        assertNull(body.get("error"), "重置本该成功：" + body);
        assertEquals(accountNumber, body.get("accountNumber"), "必须把账号号告诉用户");
        assertTrue(String.valueOf(body.get("message")).contains(accountNumber));
        assertNull(controller.login(Map.of("account", EMAIL, "password", "newpass123")).getBody().get("error"));
        assertEquals("账号或密码错误",
                controller.login(Map.of("account", EMAIL, "password", PW)).getBody().get("error"));
    }

    @Test
    @DisplayName("找回密码：邮箱没注册过 → 明确提示（用户确认要这个行为）")
    void resetPasswordUnknownEmail() {
        Map<String, Object> body = controller.resetPassword(
                Map.of("email", "ghost@example.com", "code", "123456", "newPassword", "newpass123")).getBody();
        assertEquals("该邮箱未注册", body.get("error"));
    }

    @Test
    @DisplayName("找回密码：新密码不合规 / 验证码错 → 一律拒绝，且不消费验证码")
    void resetPasswordGuards() {
        registerOk(EMAIL);
        String code = codeFor(EmailCodeService.Purpose.RESET, EMAIL);

        Map<String, Object> badPw = controller.resetPassword(
                Map.of("email", EMAIL, "code", code, "newPassword", "short1")).getBody();
        assertTrue(String.valueOf(badPw.get("error")).contains("长度"), badPw.toString());

        assertNull(controller.resetPassword(
                Map.of("email", EMAIL, "code", code, "newPassword", "newpass123")).getBody().get("error"),
                "密码不合规不该把码用掉");

        // 码已经用掉了，再用同一个码（新流程）必须失败
        Map<String, Object> reuse = controller.resetPassword(
                Map.of("email", EMAIL, "code", code, "newPassword", "another123")).getBody();
        assertTrue(String.valueOf(reuse.get("error")).contains("验证码"), reuse.toString());
    }

    @Test
    @DisplayName("发码接口：注册时邮箱已占用 / 重置时邮箱不存在 / 用途未开放，都要给出明确提示")
    void emailCodeEndpointGuards() {
        registerOk(EMAIL);
        assertEquals("该邮箱已注册，可直接登录或找回密码",
                controller.sendEmailCode(Map.of("email", EMAIL, "purpose", "register"), null).getBody().get("error"));
        assertEquals("该邮箱未注册",
                controller.sendEmailCode(Map.of("email", "ghost@example.com", "purpose", "reset"), null).getBody().get("error"));
        // P1 之后 bind_new 是合法用途了，但它属于"登录态用途" → 没带令牌时要求登录
        assertEquals("未登录，请重新登录",
                controller.sendEmailCode(Map.of("email", EMAIL, "purpose", "bind_new"), null).getBody().get("error"));
        assertEquals("不支持的验证码用途: xyz",
                controller.sendEmailCode(Map.of("email", EMAIL, "purpose", "xyz"), null).getBody().get("error"));
        assertEquals("请填写有效的邮箱地址",
                controller.sendEmailCode(Map.of("email", "not-an-email", "purpose", "register"), null).getBody().get("error"));
    }

    @Test
    @DisplayName("发码遇到限流时，把限流原因直接回给用户")
    void rateLimitMessageIsUserFacing() {
        UserController withMail = withMail();
        Map<String, Object> first = withMail.sendEmailCode(
                Map.of("email", "fresh@example.com", "purpose", "register"), null).getBody();
        assertNull(first.get("error"), "第一次发码本该成功：" + first);
        assertEquals(300, first.get("ttlSec"));

        Map<String, Object> body = withMail.sendEmailCode(
                Map.of("email", "fresh@example.com", "purpose", "register"), null).getBody();
        assertTrue(String.valueOf(body.get("error")).contains("秒后再试"), body.toString());
    }

    @Test
    @DisplayName("发码成功：真的把码交给 SMTP，并返回有效期")
    void emailCodeSendsRealMailToSender() {
        UserController withMail = withMail();
        Map<String, Object> body = withMail.sendEmailCode(
                Map.of("email", "fresh@example.com", "purpose", "register"), null).getBody();
        assertNull(body.get("error"), body.toString());

        org.mockito.ArgumentCaptor<org.springframework.mail.SimpleMailMessage> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.mail.SimpleMailMessage.class);
        org.mockito.Mockito.verify(mailSender).send(captor.capture());
        String text = captor.getValue().getText();
        assertTrue(text.contains("注册账号"), "正文要写用途：" + text);
        // 邮件里的码必须正是注册时可用的那个：抽出来直接拿去注册
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{6})").matcher(captor.getValue().getSubject());
        assertTrue(m.find(), "标题里应有验证码：" + captor.getValue().getSubject());
        Map<String, Object> reg = register("fresh@example.com", "fresh", PW, m.group(1));
        assertNull(reg.get("error"), "邮件里的码必须能用：" + reg);
    }

    @Test
    @DisplayName("邮件未配置时不假装成功：明确回错误，并且不作废用户的下一次机会（码已撤回）")
    void mailNotConfiguredDoesNotPretend() {
        Map<String, Object> body = controller.sendEmailCode(
                Map.of("email", "fresh@example.com", "purpose", "register"), null).getBody();
        assertTrue(String.valueOf(body.get("error")).contains("邮件服务"), body.toString());
        // 刚才那次发送被撤回 → 立刻再点一次仍然是同样的错误，而不是被"60 秒冷却"挡住
        Map<String, Object> again = controller.sendEmailCode(
                Map.of("email", "fresh@example.com", "purpose", "register"), null).getBody();
        assertTrue(String.valueOf(again.get("error")).contains("邮件服务"), again.toString());
        assertFalse(String.valueOf(again.get("error")).contains("秒后再试"), "失败不该占用冷却额度");
    }
}
