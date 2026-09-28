package yagen.waitmydawn.maa.service;

import org.springframework.stereotype.Component;

/**
 * 密码规则：8~24 位，且必须同时包含字母和数字。
 *
 * <p><b>为什么上限是 64 而不是"越长越好"</b>：上限防的是"提交一个几 MB 的密码"让 PBKDF2
 * 白算 21 万次（CPU 放大攻击），不是"密码太长不安全"。64 与 OWASP 的现行建议一致
 * （上限至少 64），也刚好容得下密码管理器默认生成的 20~32 位密码 —— 上限设成 24 会逼用户
 * 去调生成器、或者手动删字符，后者很容易造成"存的和填的不一致"从而登不上。
 *
 * <p><b>只对新密码生效</b>：登录路径不校验规则 —— 老账号里有 4 位密码，一校验规则他们就登不进来了。
 * 所以这里只被"注册"和"改密码"调用。
 *
 * <p>"字母"按 ASCII 判定（a-z/A-Z），不用 {@code Character.isLetter}：后者会把中文也算成字母，
 * 于是"密码密码密码"就能通过"必须含字母"。中文等其他字符不禁止，只是不能顶替字母和数字。
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    /**
     * 校验新密码。
     *
     * @return 合格返回 null；不合格返回给用户看的原因（直接回给前端，别自己拼提示语）
     */
    public String validate(String raw) {
        if (raw == null || raw.isEmpty()) return "密码不能为空";
        // 按码点计数：否则一个 emoji 会被算成 2 个"字符"，长度提示会让人困惑
        int length = raw.codePointCount(0, raw.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return "密码长度需在 " + MIN_LENGTH + "~" + MAX_LENGTH + " 位之间";
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) hasLetter = true;
            else if (c >= '0' && c <= '9') hasDigit = true;
        }
        if (!hasLetter || !hasDigit) return "密码需同时包含字母和数字";
        return null;
    }

    public boolean isValid(String raw) {
        return validate(raw) == null;
    }
}
