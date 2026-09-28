package yagen.waitmydawn.maa.service;

import java.util.Locale;

/**
 * 邮箱归一化与格式校验。
 *
 * <p><b>为什么要转小写</b>：一个邮箱在不同大小写写法下是<b>同一个收件箱</b> —— 域名按 DNS 规范本就
 * 不区分大小写，而主流服务商（Gmail/QQ/163/Outlook/企业邮）对 {@code @} 前面那部分也一律不区分。
 * 不归一化会立刻踩三个坑：同一个人用 {@code Bob@qq.com} 和 {@code bob@qq.com} 注册出两个账号；
 * 发验证码时按 {@code Bob@qq.com} 存、校验时按 {@code bob@qq.com} 查 → 用户明明收到了码却提示"验证码错误"；
 * 找回密码时大小写不同就查不到账号。归一化折叠的是"同一个邮箱的不同写法"，不会把两个不同的人合并。
 *
 * <p><b>故意不做的事</b>：不去 Gmail 那种点号、不剥 {@code +} 后缀。不同服务商规则不同，
 * 那样做才会真把两个不同的邮箱合并成一个。
 */
public final class EmailNormalizer {

    private EmailNormalizer() {}

    /** trim + 转小写（null 原样返回）。存库、做 key、比对，一律用这个结果。 */
    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 粗略的格式校验。
     *
     * <p>故意宽松：邮箱能不能用，唯一可靠的判据是"能不能收到信"。这里只挡明显不是邮箱的输入
     * （没有 @、没有域名点号、带空格），不追求 RFC 级别的严格匹配 —— 严格正则误杀真实邮箱的
     * 概率比放过奇怪邮箱更高。
     */
    public static boolean isValidFormat(String raw) {
        String e = normalize(raw);
        if (e == null || e.isEmpty() || e.length() > 190) return false;
        int at = e.indexOf('@');
        if (at <= 0 || at != e.lastIndexOf('@')) return false;   // 必须有且只有一个 @，且前面非空
        String domain = e.substring(at + 1);
        int dot = domain.indexOf('.');
        if (dot <= 0 || dot == domain.length() - 1) return false; // 域名里必须有点号且点号不在首尾
        return e.indexOf(' ') < 0 && e.indexOf('\t') < 0;
    }
}
