package yagen.waitmydawn.maa.logging;

import java.util.regex.Pattern;

/** 对可识别的凭据格式作日志兜底；调用方仍不得主动记录凭据或请求鉴权头。 */
public final class SecretRedactor {
    private static final Pattern KEY = Pattern.compile("sk-[A-Za-z0-9_-]{8,}");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern CIPHER = Pattern.compile("gcm1:[A-Za-z0-9+/=]+");
    private static final Pattern FIELD = Pattern.compile("(?i)((?:x-auth-token|x-llm-api-key|api[-_]?key|encryptedApiKey|MAA_ENCRYPTION_SECRET|encryptionSecret|authorization)[\"']?\\s*[:=]\\s*[\"']?)[^\\s,\"'\\]}]+");
    private SecretRedactor() {}
    public static String redact(String text) {
        if (text == null) return null;
        String result = KEY.matcher(text).replaceAll("[REDACTED_KEY]");
        result = BEARER.matcher(result).replaceAll("$1[REDACTED_TOKEN]");
        result = CIPHER.matcher(result).replaceAll("[REDACTED_CIPHERTEXT]");
        return FIELD.matcher(result).replaceAll("$1[REDACTED]");
    }
}
