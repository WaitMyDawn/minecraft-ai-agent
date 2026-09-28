package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮箱归一化与格式校验测试。
 *
 * <p>归一化是"一个邮箱一个账号"的前提：不转小写会出现"同一个人注册两个号"和"验证码明明收到了却
 * 提示错误"（签发和校验的 key 不一致）。这两条都用测试钉住。
 */
class EmailNormalizerTest {

    @Test
    @DisplayName("trim + 转小写；null 原样返回")
    void normalize() {
        assertEquals("user@example.com", EmailNormalizer.normalize("  User@Example.COM  "));
        assertNull(EmailNormalizer.normalize(null));
        assertEquals("", EmailNormalizer.normalize("   "));
    }

    @Test
    @DisplayName("只折叠大小写和空白，不动点号和 + 后缀（那是不同服务商的规则，做了会误合并）")
    void doesNotTouchLocalPartSemantics() {
        assertEquals("a.b+tag@gmail.com", EmailNormalizer.normalize("A.B+TAG@Gmail.com"));
    }

    @Test
    @DisplayName("格式校验：挡明显不是邮箱的，放过各种真实写法")
    void formatChecks() {
        assertTrue(EmailNormalizer.isValidFormat("user@example.com"));
        assertTrue(EmailNormalizer.isValidFormat("  User@Sub.Example.CO.UK "));
        assertTrue(EmailNormalizer.isValidFormat("a.b+tag@qq.com"));

        assertFalse(EmailNormalizer.isValidFormat(null));
        assertFalse(EmailNormalizer.isValidFormat(""));
        assertFalse(EmailNormalizer.isValidFormat("no-at-sign"));
        assertFalse(EmailNormalizer.isValidFormat("@example.com"));
        assertFalse(EmailNormalizer.isValidFormat("user@nodot"));
        assertFalse(EmailNormalizer.isValidFormat("a@@b.com"));
        assertFalse(EmailNormalizer.isValidFormat("user name@example.com"));
        assertFalse(EmailNormalizer.isValidFormat("user@.com"));
        assertFalse(EmailNormalizer.isValidFormat("user@example."));
        assertFalse(EmailNormalizer.isValidFormat("x".repeat(200) + "@example.com"), "超长要挡（列宽 190）");
    }
}
