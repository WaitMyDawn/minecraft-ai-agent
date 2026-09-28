package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
 * Brevo 客户端测试（用 MockRestServiceServer 假造 HTTP，不发真请求、不烧真额度）。
 *
 * <p>两件事要钉死：请求体是不是 Brevo 要求的形状（sender/to/subject/textContent + api-key 头）；
 * 以及余额解析 —— 额度字段名在不同计划下会变，解析不出来必须返回 null（= 不阻断发信），
 * 而不是返回 0（那会被当成"额度用完"，把注册流程整挂）。
 */
class BrevoMailClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static RestClient.Builder builderWith(MockRestServiceServer[] holder) {
        RestClient.Builder builder = RestClient.builder();
        holder[0] = MockRestServiceServer.bindTo(builder).build();
        return builder;
    }

    private JsonNode json(String s) {
        return mapper.readTree(s);
    }

    @Test
    @DisplayName("发信：POST /smtp/email，带 api-key 头，body 形状正确")
    void sendPostsExpectedPayload() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RestClient.Builder builder = builderWith(holder);
        BrevoMailClient brevo = new BrevoMailClient(builder.build(), "xkeysib-abc", "noreply@maa.dev", "MAA");

        holder[0].expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "xkeysib-abc"))
                .andExpect(content().string(containsString("\"email\":\"noreply@maa.dev\"")))
                .andExpect(content().string(containsString("\"email\":\"user@example.com\"")))
                .andExpect(content().string(containsString("\"subject\"")))
                .andExpect(content().string(containsString("\"textContent\"")))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"messageId\":\"1\"}"));

        brevo.send("user@example.com", "【MAA】你的验证码是 123456", "正文");
        holder[0].verify();
    }

    @Test
    @DisplayName("发信失败：把状态码和响应体原样带出来（401 key 错 / 400 发件人未验证都靠它排查）")
    void sendSurfacesErrorBody() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        RestClient.Builder builder = builderWith(holder);
        BrevoMailClient brevo = new BrevoMailClient(builder.build(), "bad-key", "noreply@maa.dev", "MAA");

        holder[0].expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}"));

        BrevoMailClient.BrevoException e = assertThrows(BrevoMailClient.BrevoException.class,
                () -> brevo.send("user@example.com", "s", "t"));
        assertEquals(401, e.statusCode());
        assertTrue(e.getMessage().contains("Key not found"), "响应体要带出来：" + e.getMessage());
    }

    @Test
    @DisplayName("余额解析：优先 creditsType 含 send 的那条，取不到就退而取第一条带 credits 的")
    void remainingCreditsParsing() {
        assertEquals(281, BrevoMailClient.remainingCredits(json(
                "{\"plan\":[{\"type\":\"free\",\"creditsType\":\"sendLimit\",\"credits\":281}]}")));
        // 多条计划：sms 在前、send 在后 → 必须取 send 那条
        assertEquals(42, BrevoMailClient.remainingCredits(json(
                "{\"plan\":[{\"creditsType\":\"smsCredits\",\"credits\":999},"
                        + "{\"creditsType\":\"Send Limit\",\"credits\":42}]}")));
        // 没有 creditsType：退而取第一条带 credits 的
        assertEquals(7, BrevoMailClient.remainingCredits(json("{\"plan\":[{\"credits\":7}]}")));
        // 解析不出来 → null（调用方据此"不阻断发送"），绝不能返回 0
        assertNull(BrevoMailClient.remainingCredits(json("{\"plan\":[]}")));
        assertNull(BrevoMailClient.remainingCredits(json("{}")));
        assertNull(BrevoMailClient.remainingCredits(json("{\"plan\":\"weird\"}")));
        assertNull(BrevoMailClient.remainingCredits(null));
    }

    @Test
    @DisplayName("配置检查：缺 api-key / 缺发件人时给出具体原因")
    void diagnose() {
        RestClient http = RestClient.builder().build();
        assertFalse(new BrevoMailClient(http, "", "noreply@maa.dev", "MAA").isConfigured());
        assertTrue(new BrevoMailClient(http, "", "noreply@maa.dev", "MAA").diagnose().contains("MAA_BREVO_API_KEY"));
        assertTrue(new BrevoMailClient(http, "k", "", "MAA").diagnose().contains("MAA_BREVO_FROM"));
        assertTrue(new BrevoMailClient(http, "k", "noreply@maa.dev", "MAA").isConfigured());
        assertNull(new BrevoMailClient(http, "k", "noreply@maa.dev", "MAA").diagnose());
    }
}
