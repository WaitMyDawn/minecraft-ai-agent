package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运维端点令牌校验测试。
 *
 * <p>这是"备份端点会不会被陌生人随便触发"的唯一一道门，逻辑必须钉死：
 * 没配 token（功能关闭）、token 为空、token 不匹配，三种都必须拒绝。
 */
class OpsControllerTest {

    @Test
    @DisplayName("token 一致才放行")
    void matchingTokenPasses() {
        assertTrue(OpsController.tokenMatches("s3cret-token", "s3cret-token"));
    }

    @Test
    @DisplayName("token 不对 / 缺失 / 大小写或长度不同 一律拒绝")
    void mismatchingTokenRejected() {
        assertFalse(OpsController.tokenMatches("s3cret-token", "s3cret-toke"));
        assertFalse(OpsController.tokenMatches("s3cret-token", "s3cret-token "), "末尾空格不能算匹配");
        assertFalse(OpsController.tokenMatches("s3cret-token", "S3CRET-TOKEN"));
        assertFalse(OpsController.tokenMatches("s3cret-token", null));
        assertFalse(OpsController.tokenMatches("s3cret-token", ""));
    }

    @Test
    @DisplayName("服务端没配 token（功能默认关闭）→ 任何输入都不放行")
    void unconfiguredTokenNeverPasses() {
        assertFalse(OpsController.tokenMatches("", ""));
        assertFalse(OpsController.tokenMatches("", "anything"));
        assertFalse(OpsController.tokenMatches(null, "anything"));
    }
}
