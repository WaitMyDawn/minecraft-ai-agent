package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 密码规则测试：8~24 位、必须同时含字母和数字。
 *
 * <p>边界要钉死，因为这条规则会挡在注册和改密码前面 —— 写错一个人家就注册不了。
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    private void ok(String pw) {
        assertNull(policy.validate(pw), "这本该通过：" + pw);
    }

    private void bad(String pw, String why) {
        assertNotNull(policy.validate(pw), "这本该被拒（" + why + "）：" + pw);
    }

    @Test
    @DisplayName("边界：正好 8 位通过、7 位被拒；正好 64 位通过、65 位被拒")
    void lengthBoundaries() {
        // 用 repeat 拼，别手数：上次手写"25 位"那串其实是 23 位，测试直接把这个错误抓出来了
        ok("a".repeat(7) + "1");        // 正好 8 位
        ok("a".repeat(24) + "1");       // 25 位（上限提到 64 之后，密码管理器生成的 32 位也装得下）
        ok("a".repeat(63) + "1");       // 正好 64 位
        bad("a".repeat(6) + "1", "只有 7 位");
        bad("a".repeat(64) + "1", "65 位（上限防的是「提交一个几 MB 的密码让 PBKDF2 白算」）");
    }

    @Test
    @DisplayName("必须同时含字母和数字，纯字母/纯数字都不行")
    void requiresLetterAndDigit() {
        bad("abcdefgh", "纯字母");
        bad("12345678", "纯数字");
        bad("!@#$%^&*()", "纯符号");
        ok("abc12345");
        ok("password1");
    }

    @Test
    @DisplayName("大小写字母都算字母；特殊字符允许但不必需")
    void lettersCaseAndSymbols() {
        ok("ABCD1234");
        ok("aBcD1234");
        ok("abcd1234!@#$");
        ok(" Passw0rd ");                     // 空格也算字符，不额外禁止（PBKDF2 原样处理）
    }

    @Test
    @DisplayName("中文不能顶替「字母」（Character.isLetter 会把中文当字母，这里故意不用它）")
    void chineseDoesNotCountAsLetter() {
        bad("密码密码密码12", "中文不算字母，只有数字 → 缺字母");
        bad("密码密码1", "只有数字 → 缺字母，且长度不足");
        ok("密码密码12ab");   // 8 个字符，中文不禁止，但"字母"由 ab 提供 → 通过
    }

    @Test
    @DisplayName("null / 空串被拒（不能让 null 走进 PBKDF2）")
    void nullAndEmptyRejected() {
        bad(null, "null");
        bad("", "空串");
    }

    @Test
    @DisplayName("老账号的短密码不受影响：登录路径不调用这个规则（这里只验证相关事实）")
    void legacyShortPasswordWouldFailPolicyButLoginDoesNotUseIt() {
        // 4 位是历史密码的常见长度：它一定过不了新规则，但登录只用 PasswordHasher.verify，
        // 不用 PasswordPolicy —— 这条测试把"新老并存"的边界写进代码，防止以后有人顺手把
        // 规则接到登录路径上，把老用户全体锁在门外。
        bad("1234", "4 位老密码过不了新规则（但登录不该用它校验）");
    }
}
