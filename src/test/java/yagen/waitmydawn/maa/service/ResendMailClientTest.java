package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Resend 客户端测试（MockRestServiceServer 假造 HTTP，不发真信）。
 *
 * <p>要点：请求形状（Bearer 鉴权 + from 带显示名 + text 纯文本字段）；以及 429 的判定 ——
 * Resend 免费额度发满时返回的就是 429，上层要靠这个把"发送失败"翻译成"额度已用完"，
 * 所以这里必须钉住 {@code isRateOrQuotaLimited()} 的行为。
 */
class ResendMailClientTest {

    private static RestClient.Builder builderWith(MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        return builder;
    }

    @Test
    @DisplayName("发信：POST /emails，Bearer 鉴权，from 带显示名，正文字段是 text")
    void sendPostsExpectedPayload() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RestClient.Builder builder = builderWith(holder);
        ResendMailClient resend = new ResendMailClient(builder.build(), "re_test_key",
                "noreply@send.minecraft-ai-agent.com", "MAA");

        holder[0].expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_test_key"))
                .andExpect(content().string(containsString("\"from\":\"MAA <noreply@send.minecraft-ai-agent.com>\"")))
                .andExpect(content().string(containsString("\"to\":\"user@example.com\"")))
                .andExpect(content().string(containsString("\"text\"")))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"abc\"}"));

        resend.send("user@example.com", "【MAA】你的验证码是 123456", "正文");
        holder[0].verify();
    }

    @Test
    @DisplayName("429（额度/频率超限）：标记成 rateOrQuotaLimited，消息带出响应体")
    void quotaLimitIsRecognised() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RestClient.Builder builder = builderWith(holder);
        ResendMailClient resend = new ResendMailClient(builder.build(), "re_test_key",
                "noreply@send.minecraft-ai-agent.com", "MAA");

        holder[0].expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"You have reached your daily quota\"}"));

        ResendMailClient.ResendException e = assertThrows(ResendMailClient.ResendException.class,
                () -> resend.send("user@example.com", "s", "t"));
        assertEquals(429, e.statusCode());
        assertTrue(e.isRateOrQuotaLimited());
        assertTrue(e.getMessage().contains("daily quota"), e.getMessage());
    }

    @Test
    @DisplayName("401/403（key 错、发件人未验证）：原样带出状态码与响应体")
    void authErrorsSurfaceBody() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RestClient.Builder builder = builderWith(holder);
        ResendMailClient resend = new ResendMailClient(builder.build(), "bad", "noreply@send.minecraft-ai-agent.com", "MAA");

        holder[0].expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"The domain is not verified\"}"));

        ResendMailClient.ResendException e = assertThrows(ResendMailClient.ResendException.class,
                () -> resend.send("user@example.com", "s", "t"));
        assertEquals(403, e.statusCode());
        assertFalse(e.isRateOrQuotaLimited(), "域名没验证不该被误判成额度用尽");
        assertTrue(e.getMessage().contains("not verified"), e.getMessage());
    }

    @Test
    @DisplayName("额度类错误即使不是 429（响应体里出现 quota/limit）也要能被识别")
    void quotaKeywordIsAlsoRecognised() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RestClient.Builder builder = builderWith(holder);
        ResendMailClient resend = new ResendMailClient(builder.build(), "re_test_key",
                "noreply@send.minecraft-ai-agent.com", "MAA");

        // 假设某天 Resend 用 403 + daily quota 描述表达额度用尽
        holder[0].expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Daily quota exceeded for this account\"}"));

        ResendMailClient.ResendException e = assertThrows(ResendMailClient.ResendException.class,
                () -> resend.send("user@example.com", "s", "t"));
        assertEquals(403, e.statusCode());
        assertTrue(e.isRateOrQuotaLimited(), "响应体里写着 quota，就该按额度用尽处理");
    }

    @Test
    @DisplayName("配置检查：缺 key / 缺发件人时给出具体原因")
    void diagnose() {
        RestClient http = RestClient.builder().build();
        assertFalse(new ResendMailClient(http, "", "noreply@send.minecraft-ai-agent.com", "MAA").isConfigured());
        assertTrue(new ResendMailClient(http, "", "noreply@send.minecraft-ai-agent.com", "MAA")
                .diagnose().contains("MAA_RESEND_API_KEY"));
        assertTrue(new ResendMailClient(http, "k", "", "MAA").diagnose().contains("MAA_RESEND_FROM"));
        assertNull(new ResendMailClient(http, "k", "noreply@send.minecraft-ai-agent.com", "MAA").diagnose());
    }
}
