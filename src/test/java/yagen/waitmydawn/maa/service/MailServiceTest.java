package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 邮件发送测试（用 mock 的 SMTP，不发真信）。
 *
 * <p>盯三件事：邮件内容对不对（收件人/标题/正文里的码和用途）；<b>没配好时不能假装发出去</b>；
 * 日志里不能出现完整邮箱。真机发信要等服务器配好 DirectMail 再验一次（那一步只能人工做）。
 */
class MailServiceTest {

    /** 手写一个最小 ObjectProvider：生产代码只用 getIfAvailable() */
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getObject() {
                if (sender == null) throw new IllegalStateException("no mail sender");
                return sender;
            }

            @Override
            public JavaMailSender getIfAvailable() {
                return sender;
            }
        };
    }

    @Test
    @DisplayName("正常路径：收件人/发件人/标题/正文都对")
    void sendsCodeMail() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService svc = new MailService(provider(sender), true, "noreply@example.com", "MAA");
        assertTrue(svc.isConfigured());

        svc.sendCode("user@example.com", "注册账号", "123456", 5);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertEquals("user@example.com", msg.getTo()[0]);
        assertEquals("MAA <noreply@example.com>", msg.getFrom());
        assertEquals("【MAA】你的验证码是 123456", msg.getSubject());
        assertTrue(msg.getText().contains("注册账号"), "正文要写清用途，用户才知道这封信为什么来");
        assertTrue(msg.getText().contains("123456"));
        assertTrue(msg.getText().contains("5 分钟"));
    }

    @Test
    @DisplayName("没开开关 / 没配发件人 / 容器里没有 mail bean → 一律明确报错，不许假装成功")
    void notConfiguredNeverPretendsToSend() {
        JavaMailSender sender = mock(JavaMailSender.class);

        MailService disabled = new MailService(provider(sender), false, "noreply@example.com", "MAA");
        assertTrue(!disabled.isConfigured());
        assertThrows(MailService.NotConfiguredException.class,
                () -> disabled.sendCode("u@example.com", "注册账号", "123456", 5));

        MailService noFrom = new MailService(provider(sender), true, "  ", "MAA");
        assertThrows(MailService.NotConfiguredException.class,
                () -> noFrom.sendCode("u@example.com", "注册账号", "123456", 5));

        MailService noBean = new MailService(provider(null), true, "noreply@example.com", "MAA");
        assertThrows(MailService.NotConfiguredException.class,
                () -> noBean.sendCode("u@example.com", "注册账号", "123456", 5));
    }

    @Test
    @DisplayName("发件人名字留空时兜底成 MAA")
    void blankFromNameFallsBack() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService svc = new MailService(provider(sender), true, "noreply@example.com", "   ");
        svc.sendCode("user@example.com", "重置密码", "654321", 5);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertEquals("MAA <noreply@example.com>", captor.getValue().getFrom());
    }

    @Test
    @DisplayName("日志脱敏：完整邮箱不落盘")
    void maskHidesFullEmail() {
        assertEquals("us***@example.com", MailService.mask("user@example.com"));
        assertEquals("***@example.com", MailService.mask("@example.com"));
        assertEquals("null", MailService.mask(null));
    }
}
