package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证码服务测试：签发、校验、限流、用途隔离、大小写归一。
 *
 * <p>用注入的假时钟（不是 sleep）："冷却 60 秒""一天 3 次""跨天重置"这些规则必须能精确断言，
 * 靠 sleep 会又慢又飘。
 */
class EmailCodeServiceTest {

    private static final String EMAIL = "user@example.com";

    private long now = 1_700_000_000_000L;
    private EmailCodeService svc;

    @BeforeEach
    void setUp() {
        svc = new EmailCodeService(3, 60_000L, () -> now);
    }

    private void advance(long ms) {
        now += ms;
    }

    @Test
    @DisplayName("签发 → 校验通过 → 同一个码不能再用第二次（一次性）")
    void issueAndVerifyOnce() {
        String code = svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        assertEquals(6, code.length(), "必须是 6 位数字：" + code);
        assertTrue(code.matches("\\d{6}"));
        assertTrue(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, code));
        assertFalse(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, code), "用过的码必须失效");
    }

    @Test
    @DisplayName("错的码不通过；连错 5 次后作废，正确的码也不再认")
    void fiveWrongAttemptsInvalidate() {
        String code = svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        for (int i = 1; i <= 4; i++) {
            assertFalse(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, "000000"), "第 " + i + " 次错的码不该过");
        }
        assertFalse(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, "000000"), "第 5 次错的码不该过");
        assertFalse(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, code), "错 5 次之后连正确的码也必须作废");
    }

    @Test
    @DisplayName("冷却 60 秒：期间再发被拒，过了就能再发")
    void cooldown() {
        svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        EmailCodeService.RateLimitedException ex = assertThrows(EmailCodeService.RateLimitedException.class,
                () -> svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL));
        assertTrue(ex.getMessage().contains("秒后再试"), "提示要能直接给用户看：" + ex.getMessage());

        advance(60_000);
        svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);   // 正好 60 秒应放行
    }

    @Test
    @DisplayName("一个邮箱一天最多 3 个码（注册/重置/换绑共用额度）")
    void dailyCapSharedAcrossPurposes() {
        svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        advance(61_000);
        svc.issue(EmailCodeService.Purpose.RESET, EMAIL);
        advance(61_000);
        svc.issue(EmailCodeService.Purpose.BIND_NEW, EMAIL);
        assertEquals(3, svc.sentToday(EMAIL));

        advance(61_000);
        EmailCodeService.RateLimitedException ex = assertThrows(EmailCodeService.RateLimitedException.class,
                () -> svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL));
        assertTrue(ex.getMessage().contains("今天"), ex.getMessage());
        assertEquals(3, svc.sentToday(EMAIL), "被拒的那次不能算进额度");
    }

    @Test
    @DisplayName("跨天后额度重置")
    void quotaResetsNextDay() {
        svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        advance(61_000);
        svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        advance(61_000);
        svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        assertEquals(3, svc.sentToday(EMAIL));

        advance(24 * 60 * 60 * 1000L);   // 拨到第二天
        assertEquals(0, svc.sentToday(EMAIL));
        svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);   // 不该再被"今天已 3 次"挡住
        assertEquals(1, svc.sentToday(EMAIL));
    }

    @Test
    @DisplayName("用途隔离：注册的码不能拿去重置密码，反之亦然")
    void purposeIsolation() {
        String code = svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        assertFalse(svc.verify(EmailCodeService.Purpose.RESET, EMAIL, code), "换用途必须无效");
        assertFalse(svc.verify(EmailCodeService.Purpose.CHANGE_PASSWORD, EMAIL, code));
        assertTrue(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, code), "原用途仍然有效");
    }

    @Test
    @DisplayName("大小写/空格等价：按 Bob@QQ.com 发码，用 bob@qq.com 也能校验（同一个收件箱）")
    void emailCaseAndSpaceInsensitive() {
        String code = svc.issue(EmailCodeService.Purpose.REGISTER, "  Bob@QQ.com ");
        assertTrue(svc.verify(EmailCodeService.Purpose.REGISTER, "bob@qq.com", code));
        // 额度也按归一化后的邮箱算：换个大小写不能绕过日限
        assertEquals(1, svc.sentToday("BOB@qq.COM"));
    }

    @Test
    @DisplayName("重发会覆盖旧码：旧码立刻失效，避免两个码都能用")
    void reissueInvalidatesPreviousCode() {
        String first = svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        advance(61_000);
        String second = svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        assertFalse(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, first), "旧码应失效");
        assertTrue(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, second));
    }

    @Test
    @DisplayName("没签发过 / 空码 → 直接失败，不抛异常")
    void noCodeIssued() {
        assertFalse(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, "123456"));
        svc.issue(EmailCodeService.Purpose.REGISTER, EMAIL);
        assertFalse(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, ""));
        assertFalse(svc.verify(EmailCodeService.Purpose.REGISTER, EMAIL, null));
    }

    @Test
    @DisplayName("换个邮箱不影响彼此的额度与验证码")
    void twoEmailsAreIndependent() {
        String a = svc.issue(EmailCodeService.Purpose.REGISTER, "a@example.com");
        String b = svc.issue(EmailCodeService.Purpose.REGISTER, "b@example.com");   // 不受 a 的冷却影响
        assertTrue(svc.verify(EmailCodeService.Purpose.REGISTER, "a@example.com", a));
        assertTrue(svc.verify(EmailCodeService.Purpose.REGISTER, "b@example.com", b));
        assertFalse(svc.verify(EmailCodeService.Purpose.REGISTER, "a@example.com", b), "码不能串邮箱");
    }
}
