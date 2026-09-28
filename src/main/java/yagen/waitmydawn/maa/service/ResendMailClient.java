package yagen.waitmydawn.maa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Resend 发信客户端（HTTP API）。
 *
 * <p>和 Brevo 的差别，选它之前要心里有数：
 * <ul>
 *   <li><b>不需要逐个验证发件人地址</b>：域名 verified 之后，该域下任意地址都能当发件人
 *       （Brevo 要单独验证每个 sender，还卡手机号）；</li>
 *   <li><b>没有额度查询接口</b>：免费 100 封/天、3000 封/月，但 API 查不到"还剩多少"。
 *       所以这里的策略是"发的时候看响应"——额度/频率超了会返回 429，我们把它翻译成
 *       "额度用完"给人看，而不是笼统的发送失败。</li>
 * </ul>
 *
 * <p>复用项目里那把 {@code RestClient}（8s/15s 超时），零新增依赖。
 */
@Component
public class ResendMailClient {

    private static final String API_BASE = "https://api.resend.com";

    /** 发信失败。带上状态码与响应体，运维端点会原样显示。 */
    public static class ResendException extends RuntimeException {
        private final int statusCode;

        ResendException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }

        /**
         * 是不是"频率/额度超限"。
         *
         * <p>主要看 429（Resend 把"今天发满了"和"发太快了"都归到这一类）。但额度类错误在不同
         * 时期/不同计划下不一定都用 429 表达，所以再兜一层：响应体里出现 quota / limit / too many
         * 这类字样也算 —— 这样即使它变成 403+quota 描述，用户看到的仍然是"额度用完、明天再试"，
         * 而不是笼统的"发送失败"。反之，403 "domain not verified" 不含这些词，不会被误判。
         */
        public boolean isRateOrQuotaLimited() {
            if (statusCode == 429) return true;
            String msg = getMessage();
            if (msg == null) return false;
            String lower = msg.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("quota") || lower.contains("limit") || lower.contains("too many");
        }
    }

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String from;
    private final String fromName;

    public ResendMailClient(RestClient http,
                            @Value("${maa.mail.resend.api-key:}") String apiKey,
                            @Value("${maa.mail.resend.from:}") String from,
                            @Value("${maa.mail.resend.from-name:MAA}") String fromName) {
        this.http = http;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from == null ? "" : from.trim();
        this.fromName = fromName == null || fromName.isBlank() ? "MAA" : fromName.trim();
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty() && !from.isEmpty();
    }

    public String diagnose() {
        if (apiKey.isEmpty()) return "Resend 未配置：.env 里缺少 MAA_RESEND_API_KEY";
        if (from.isEmpty()) return "Resend 未配置发件人：.env 里缺少 MAA_RESEND_FROM（必须是已验证域名下的地址）";
        return null;
    }

    public String fromAddress() {
        return from;
    }

    /**
     * 发一封纯文本邮件。
     *
     * @throws ResendException 任何失败（401 key 错、403 域名/发件人不对、429 频率或额度超限）
     */
    public void send(String to, String subject, String text) {
        ObjectNode body = mapper.createObjectNode();
        // Resend 的 from 支持 "显示名 <地址>" 写法
        body.put("from", fromName + " <" + from + ">");
        body.put("to", to);
        body.put("subject", subject);
        body.put("text", text);

        try {
            http.post()
                    .uri(API_BASE + "/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new ResendException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new ResendException(-1, e.getMessage());
        }
    }
}
