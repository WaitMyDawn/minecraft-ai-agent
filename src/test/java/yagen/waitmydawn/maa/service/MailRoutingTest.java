package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通道路由测试：目前只用 Brevo，余额用尽就"干脆不发"，查不到余额则照常发。
 *
 * <p>这三条边界是这次改造的核心取舍，必须钉住：
 * <ol>
 *   <li>余额 0 → 抛 {@code QuotaExhaustedException}（上层回"额度用完"，不是"发送失败"）；</li>
 *   <li>余额查询<b>失败</b> → 照常发送（查额度是锦上添花，不能因为它把注册流程弄挂）；</li>
 *   <li>发完一封要清掉余额缓存，否则下一封会拿旧余额判断"还有额度"。</li>
 * </ol>
 */
class MailRoutingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static ObjectProvider<JavaMailSender> noSmtp() {
        return new ObjectProvider<>() {
            @Override public JavaMailSender getObject() { throw new IllegalStateException("no smtp"); }
            @Override public JavaMailSender getIfAvailable() { return null; }
        };
    }

    private MailService brevoService(BrevoMailClient brevo, long quotaCacheMs) {
        // 走带 @Autowired 的那个构造器：provider=brevo
        return new MailService(noSmtp(), true, "", "MAA", "brevo", quotaCacheMs, brevo, null);
    }

    private BrevoMailClient brevoWithCredits(Integer credits, boolean accountThrows) {
        BrevoMailClient brevo = mock(BrevoMailClient.class);
        when(brevo.isConfigured()).thenReturn(true);
        when(brevo.fromAddress()).thenReturn("noreply@maa.dev");
        when(brevo.account()).thenAnswer(i -> {
            if (accountThrows) throw new BrevoMailClient.BrevoException(-1, "network down");
            return mapper.readTree("{\"plan\":[{\"creditsType\":\"sendLimit\",\"credits\":"
                    + credits + "}]}");
        });
        return brevo;
    }

    @Test
    @DisplayName("余额足够：走 Brevo 发送，并把额度状态暴露给运维端点")
    void sendsThroughBrevo() {
        BrevoMailClient brevo = brevoWithCredits(281, false);
        MailService svc = brevoService(brevo, 300_000L);

        assertEquals("brevo", svc.providerName());
        assertTrue(svc.isConfigured());
        assertEquals("noreply@maa.dev", svc.fromAddress());
        assertEquals(281, svc.quota().remaining());

        svc.sendCode("user@example.com", "注册账号", "123456", 5);
        verify(brevo).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("余额 0：不发信，抛 QuotaExhaustedException（上层会回「额度用完」）")
    void exhaustedQuotaBlocksSending() {
        BrevoMailClient brevo = brevoWithCredits(0, false);
        MailService svc = brevoService(brevo, 300_000L);

        assertTrue(svc.quota().exhausted());
        assertThrows(MailService.QuotaExhaustedException.class,
                () -> svc.sendCode("user@example.com", "注册账号", "123456", 5));
        verify(brevo, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("额度查询失败：不阻断发送（remaining=null，detail 里带原因）")
    void quotaLookupFailureDoesNotBlockSending() {
        BrevoMailClient brevo = brevoWithCredits(null, true);
        MailService svc = brevoService(brevo, 300_000L);

        MailService.Quota q = svc.quota();
        assertNull(q.remaining());
        assertFalse(q.exhausted());
        assertTrue(q.detail().contains("额度查询失败"), q.detail());

        svc.sendCode("user@example.com", "注册账号", "123456", 5);   // 不该抛
        verify(brevo).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("发完一封就清缓存：下一封会重新查余额（否则会拿旧余额判断「还有额度」）")
    void successfulSendInvalidatesQuotaCache() {
        BrevoMailClient brevo = brevoWithCredits(5, false);
        MailService svc = brevoService(brevo, 3_600_000L);   // 缓存 1 小时，正常不会再查

        svc.sendCode("a@example.com", "注册账号", "111111", 5);
        svc.sendCode("b@example.com", "注册账号", "222222", 5);

        verify(brevo, times(2)).account();   // 两次发送各查了一次余额
    }

    @Test
    @DisplayName("缓存生效：未发信时短时间内反复问额度只打一次 API")
    void quotaIsCached() {
        BrevoMailClient brevo = brevoWithCredits(9, false);
        MailService svc = brevoService(brevo, 300_000L);

        svc.quota();
        svc.quota();
        svc.quota();

        verify(brevo, times(1)).account();
    }

    @Test
    @DisplayName("未配置 Brevo 时：isConfigured=false 且 diagnose 指明缺哪一项，发送抛 NotConfigured")
    void notConfigured() {
        BrevoMailClient brevo = mock(BrevoMailClient.class);
        when(brevo.isConfigured()).thenReturn(false);
        when(brevo.diagnose()).thenReturn("Brevo 未配置：.env 里缺少 MAA_BREVO_API_KEY");
        MailService svc = brevoService(brevo, 300_000L);

        assertFalse(svc.isConfigured());
        assertTrue(svc.diagnose().contains("MAA_BREVO_API_KEY"));
        assertNotNull(svc.quota());
        assertThrows(MailService.NotConfiguredException.class,
                () -> svc.sendCode("u@example.com", "注册账号", "123456", 5));
    }

    @Test
    @DisplayName("切回 SMTP：provider=smtp 时走老的 JavaMailSender，不碰 Brevo")
    void canSwitchBackToSmtp() {
        JavaMailSender sender = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = new ObjectProvider<>() {
            @Override public JavaMailSender getObject() { return sender; }
            @Override public JavaMailSender getIfAvailable() { return sender; }
        };
        BrevoMailClient brevo = mock(BrevoMailClient.class);
        MailService svc = new MailService(provider, true, "noreply@maa.dev", "MAA", "smtp", 300_000L, brevo, null);

        assertEquals("smtp", svc.providerName());
        assertTrue(svc.isConfigured());
        svc.sendCode("user@example.com", "注册账号", "123456", 5);

        verify(sender).send(org.mockito.ArgumentMatchers.any(org.springframework.mail.SimpleMailMessage.class));
        verify(brevo, never()).send(anyString(), anyString(), anyString());
    }

    // ---------- Resend 通道 ----------

    private ResendMailClient resendMock(boolean configured) {
        ResendMailClient r = mock(ResendMailClient.class);
        when(r.isConfigured()).thenReturn(configured);
        when(r.fromAddress()).thenReturn("noreply@send.minecraft-ai-agent.com");
        if (!configured) when(r.diagnose()).thenReturn("Resend 未配置：.env 里缺少 MAA_RESEND_API_KEY");
        return r;
    }

    private MailService resendService(ResendMailClient resend) {
        return new MailService(noSmtp(), true, "", "MAA", "resend", 300_000L, null, resend);
    }

    @Test
    @DisplayName("Resend：正常发送，且额度状态如实说明「查不到额度、靠 429 判断」")
    void sendsThroughResend() {
        ResendMailClient resend = resendMock(true);
        MailService svc = resendService(resend);

        assertEquals("resend", svc.providerName());
        assertTrue(svc.isConfigured());
        assertEquals("noreply@send.minecraft-ai-agent.com", svc.fromAddress());

        MailService.Quota q = svc.quota();
        assertNull(q.remaining(), "Resend 查不到剩余额度");
        assertFalse(q.exhausted(), "查不到 ≠ 用完：绝不能因此拒发");
        assertTrue(q.detail().contains("不提供额度查询"), q.detail());

        svc.sendCode("user@example.com", "注册账号", "123456", 5);
        verify(resend).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Resend 返回 429（额度发满）：翻译成 QuotaExhaustedException，用户看到「额度用完」")
    void resendQuotaLimitBecomesUserFacingError() {
        ResendMailClient resend = resendMock(true);
        org.mockito.Mockito.doThrow(new ResendMailClient.ResendException(429, "daily quota reached"))
                .when(resend).send(anyString(), anyString(), anyString());
        MailService svc = resendService(resend);

        MailService.QuotaExhaustedException e = assertThrows(MailService.QuotaExhaustedException.class,
                () -> svc.sendCode("user@example.com", "注册账号", "123456", 5));
        assertTrue(e.getMessage().contains("429"), e.getMessage());
    }

    @Test
    @DisplayName("Resend 其他错误（如 403 域名没验证）：原样抛出去，由上层兜底提示")
    void resendOtherErrorsPropagate() {
        ResendMailClient resend = resendMock(true);
        org.mockito.Mockito.doThrow(new ResendMailClient.ResendException(403, "domain not verified"))
                .when(resend).send(anyString(), anyString(), anyString());
        MailService svc = resendService(resend);

        ResendMailClient.ResendException e = assertThrows(ResendMailClient.ResendException.class,
                () -> svc.sendCode("user@example.com", "注册账号", "123456", 5));
        assertEquals(403, e.statusCode());
    }

    @Test
    @DisplayName("Resend 未配置：发送抛 NotConfigured，并且 diagnose 指明缺哪一项")
    void resendNotConfigured() {
        MailService svc = resendService(resendMock(false));
        assertFalse(svc.isConfigured());
        assertTrue(svc.diagnose().contains("MAA_RESEND_API_KEY"));
        assertThrows(MailService.NotConfiguredException.class,
                () -> svc.sendCode("u@example.com", "注册账号", "123456", 5));
    }

    @Test
    @DisplayName("验收开关 maa.mail.simulate-quota：直接按额度用完失败，且不调用发信通道")
    void simulateQuotaSwitch() {
        ResendMailClient resend = resendMock(true);
        MailService svc = resendService(resend);
        svc.setSimulateQuotaForTest(true);

        MailService.QuotaExhaustedException e = assertThrows(MailService.QuotaExhaustedException.class,
                () -> svc.sendCode("user@example.com", "注册账号", "123456", 5));
        assertTrue(e.getMessage().contains("模拟"), e.getMessage());
        verify(resend, never()).send(anyString(), anyString(), anyString());
    }
}
