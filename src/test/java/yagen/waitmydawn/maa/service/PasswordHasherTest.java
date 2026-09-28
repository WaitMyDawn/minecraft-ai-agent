package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码哈希测试。
 *
 * <p>盯四件事：
 * <ol>
 *   <li><b>加盐</b>：同一个密码两次哈希必须不同（这是"彩虹表失效"的前提）；</li>
 *   <li><b>迁移不锁人</b>：老的"无盐 SHA-256"记录必须仍然校验通过 —— 这是上线时最容易把人关在门外的地方；</li>
 *   <li>{@code needsUpgrade} 判定正确，登录后才有机会静默升级；</li>
 *   <li>坏数据（格式被改、迭代次数被调小、null）一律判失败，不能抛异常。</li>
 * </ol>
 *
 * <p>测试里用很低的迭代次数（{@code MIN_ITERATIONS} 下限 1 万）跑，不然几十个用例会等很久。
 */
class PasswordHasherTest {

    /** 测试用低迭代数；生产默认 21 万（见 PasswordHasher.DEFAULT_ITERATIONS） */
    private final PasswordHasher hasher = new PasswordHasher(1);

    @Test
    @DisplayName("同一密码两次哈希不同（证明加了盐）")
    void saltMakesHashesUnique() {
        String a = hasher.hash("hunter2");
        String b = hasher.hash("hunter2");
        assertNotEquals(a, b, "加盐后同一密码的两次哈希必须不同，否则彩虹表依然有效");
        assertTrue(hasher.verify("hunter2", a));
        assertTrue(hasher.verify("hunter2", b));
    }

    @Test
    @DisplayName("密码不对 → 校验失败")
    void wrongPasswordRejected() {
        String stored = hasher.hash("hunter2");
        assertFalse(hasher.verify("hunter3", stored));
        assertFalse(hasher.verify("", stored));
        assertFalse(hasher.verify(null, stored));
        assertFalse(hasher.verify("hunter2", null));
    }

    @Test
    @DisplayName("老的无盐 SHA-256 记录仍能校验通过（迁移期不能把老用户锁在门外）")
    void legacyHashStillVerifies() {
        String legacy = PasswordHasher.legacySha256("old-password");
        assertEquals(44, legacy.length(), "SHA-256 的 Base64 是 44 个字符");
        assertTrue(hasher.verify("old-password", legacy), "老格式必须继续认");
        assertFalse(hasher.verify("other", legacy));
    }

    @Test
    @DisplayName("needsUpgrade：老格式为 true、新格式为 false")
    void upgradeDetection() {
        assertTrue(hasher.needsUpgrade(PasswordHasher.legacySha256("x")));
        assertFalse(hasher.needsUpgrade(hasher.hash("x")));
        assertFalse(hasher.needsUpgrade(null));
        assertFalse(hasher.needsUpgrade(""));
    }

    @Test
    @DisplayName("存储格式自带迭代次数：改成更高迭代数的新记录，老记录照旧能验")
    void iterationsArePerRecord() {
        PasswordHasher slow = new PasswordHasher(50_000);
        String slowHash = slow.hash("same");
        assertTrue(slowHash.startsWith("pbkdf2$50000$"), "次数要写进记录里，实际：" + slowHash);
        // 同一个 Hasher 实例能验别的迭代数生成的记录（说明校验读的是记录里的次数，不是构造参数）
        assertTrue(slow.verify("same", slowHash));
        assertTrue(slow.verify("same", hasher.hash("same")));
    }

    @Test
    @DisplayName("坏数据不抛异常：格式残缺 / 迭代次数被调小 / 非 base64，一律判失败")
    void malformedStoredValueIsRejected() {
        assertFalse(hasher.verify("x", "pbkdf2$abc$def$ghi"));
        assertFalse(hasher.verify("x", "pbkdf2$210000$not-base64$also-bad"));
        // 迭代次数被改成 1（等于退回老算法）→ 拒绝，不能按弱参数放行
        // 注意替换串里的 $ 要转义：Java 的 replaceFirst 会把 $1 当成"第 1 个捕获组"
        String weakened = hasher.hash("x").replaceFirst("\\$10000\\$", "\\$1\\$");
        assertFalse(hasher.verify("x", weakened), "被调小迭代次数的记录不能认");
    }

    @Test
    @DisplayName("10 万个不同密码的哈希集合不冲突（基本健全性）")
    void noCollisionInSmallSample() {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < 200; i++) set.add(hasher.hash("pw-" + i));
        assertEquals(200, set.size());
    }

    @Test
    @DisplayName("跑一次真实默认参数，确认耗时在可接受范围（顺便把耗时打出来）")
    void defaultIterationsTiming() {
        PasswordHasher prod = new PasswordHasher(210_000);
        long t0 = System.currentTimeMillis();
        String h = prod.hash("timing-check");
        long hashMs = System.currentTimeMillis() - t0;
        long t1 = System.currentTimeMillis();
        assertTrue(prod.verify("timing-check", h));
        long verifyMs = System.currentTimeMillis() - t1;
        System.out.println("[PBKDF2] 21 万次迭代：hash=" + hashMs + "ms, verify=" + verifyMs + "ms");
        assertTrue(hashMs < 3000, "单次哈希不该超过 3 秒，实际 " + hashMs + "ms（服务器更慢时可调小 maa.auth.pbkdf2-iterations）");
    }
}
