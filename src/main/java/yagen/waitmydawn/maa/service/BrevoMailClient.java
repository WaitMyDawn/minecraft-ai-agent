package yagen.waitmydawn.maa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Brevo（原 Sendinblue）发信客户端：HTTP API 发信 + 查额度。
 *
 * <p>为什么用 API 而不是把 Brevo 也当 SMTP：<b>SMTP 查不到剩余额度</b>，而免费额度（300 封/天）
 * 用完之后再发就会被拒 —— 我们要的是"提前知道还剩多少、用完了干脆不发并给出明确提示"，
 * 这只有 API 能做到（{@code GET /v3/account} 里的 {@code plan[].credits}）。
 *
 * <p>复用项目里那把 {@code RestClient}（8 秒连接 / 15 秒读取超时）：HTTP 客户端统一走同一套超时，
 * 不额外引依赖。
 */
@Component
public class BrevoMailClient {

    private static final String API_BASE = "https://api.brevo.com/v3";

    /** 发信/查额度失败。带上状态码与响应体，运维端点会把它原样显示出来。 */
    public static class BrevoException extends RuntimeException {
        private final int statusCode;

        BrevoException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String from;
    private final String fromName;

    public BrevoMailClient(RestClient http,
                           @Value("${maa.mail.brevo.api-key:}") String apiKey,
                           @Value("${maa.mail.brevo.from:}") String from,
                           @Value("${maa.mail.brevo.from-name:MAA}") String fromName) {
        this.http = http;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from == null ? "" : from.trim();
        this.fromName = fromName == null || fromName.isBlank() ? "MAA" : fromName.trim();
    }

    /** api-key 和发件人都配了才算可用 */
    public boolean isConfigured() {
        return !apiKey.isEmpty() && !from.isEmpty();
    }

    /** 不可用时的具体原因（给运维端点/错误提示用） */
    public String diagnose() {
        if (apiKey.isEmpty()) return "Brevo 未配置：.env 里缺少 MAA_BREVO_API_KEY";
        if (from.isEmpty()) return "Brevo 未配置发件人：.env 里缺少 MAA_BREVO_FROM";
        return null;
    }

    public String fromAddress() {
        return from;
    }

    /**
     * 发一封纯文本邮件。
     *
     * @throws BrevoException 任何失败（含 401 key 错、400 发件人未验证、402/429 额度或频率超限）
     */
    public void send(String to, String subject, String text) {
        ObjectNode body = mapper.createObjectNode();
        body.putObject("sender").put("name", fromName).put("email", from);
        body.putArray("to").addObject().put("email", to);
        body.put("subject", subject);
        body.put("textContent", text);

        try {
            http.post()
                    .uri(API_BASE + "/smtp/email")
                    .header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new BrevoException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new BrevoException(-1, e.getMessage());
        }
    }

    /** 账户信息（原样返回）。运维端点会把它打印出来，方便核对字段是否和下面解析的一致。 */
    public JsonNode account() {
        try {
            return http.get()
                    .uri(API_BASE + "/account")
                    .header("api-key", apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new BrevoException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new BrevoException(-1, e.getMessage());
        }
    }

    /**
     * 从账户信息里取出"还能发多少封"。
     *
     * <p>Brevo 的返回形如 {@code {"plan":[{"type":"free","creditsType":"sendLimit","credits":281}]}}，
     * 但字段名在不同计划下会有出入（还有 smsCredits / ipCredits 等）。所以这里优先找
     * {@code creditsType} 里带 "send" 的那条，找不到就退而取第一条带 credits 的。
     *
     * <p>取不到时返回 {@code null} —— 调用方**不要**因此拒绝发信（额度查不出来是小事，
     * 把注册流程弄挂是大事）。
     */
    public static Integer remainingCredits(JsonNode account) {
        if (account == null) return null;
        JsonNode plan = account.path("plan");
        if (!plan.isArray()) return null;
        Integer fallback = null;
        for (JsonNode entry : plan) {
            JsonNode credits = entry.path("credits");
            if (!credits.isNumber()) continue;
            String type = entry.path("creditsType").asText("").toLowerCase(java.util.Locale.ROOT);
            if (type.contains("send")) return credits.asInt();
            if (fallback == null) fallback = credits.asInt();
        }
        return fallback;
    }
}
