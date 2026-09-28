package yagen.waitmydawn.maa.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希：PBKDF2-HMAC-SHA256 + 每用户随机盐，并兼容老的"无盐 SHA-256"。
 *
 * <p><b>为什么要换掉老算法</b>：老实现是 {@code Base64(SHA-256(密码))} —— 没有盐、单次哈希。
 * 这不会让密码被"数学还原"，但会让穷举便宜到可以忽略：常见密码的"预计算表 / 彩虹表"网上现成，
 * 拿到库文件就能批量反查出弱密码；而且同一密码在两个账号里哈希值完全相同，一眼能看出谁和谁同密码。
 * 真正要防的是<b>撞库</b>：用户在很多站点用同一个密码，一个库泄露就连带炸一片。
 *
 * <p><b>为什么用 PBKDF2 而不是引 BCrypt/Argon2</b>：PBKDF2 是 JDK 自带的标准 KDF
 * （NIST SP 800-132、OWASP 都认可），不需要往这个项目里加任何依赖；本项目已经在直接用 JCA
 * （{@code Cipher}/{@code MessageDigest}），风格一致。真要更强的抗 GPU 能力（Argon2）再换不迟，
 * 而且下面的存储格式已经预留了算法前缀与迭代次数，将来能平滑迁移。
 *
 * <p><b>存储格式</b>：{@code pbkdf2$<迭代次数>$<盐 base64>$<摘要 base64>}。
 * 迭代次数写进每一条记录里，所以以后把默认值调高<b>不会</b>让老用户登不上——校验时读的是记录里的值。
 */
@Component
public class PasswordHasher {

    /** 格式前缀。老记录没有这个前缀 → 按老算法校验（迁移期用）。 */
    private static final String PBKDF2_PREFIX = "pbkdf2$";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    /** 迭代次数下限：防止配置手滑写成 1（那等于退回老算法） */
    private static final int MIN_ITERATIONS = 10_000;
    /** 默认迭代次数（可用 maa.auth.pbkdf2-iterations 覆盖） */
    private static final int DEFAULT_ITERATIONS = 210_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;

    public PasswordHasher(@Value("${maa.auth.pbkdf2-iterations:" + DEFAULT_ITERATIONS + "}")
                          int iterations) {
        // 上线后如果发现登录明显变慢（比如 2 核小机器），把这个值调小即可，
        // 老用户不受影响（他们的记录里存着自己那份迭代次数）
        this.iterations = Math.max(MIN_ITERATIONS, iterations);
    }

    /** 生成新哈希（注册、改密码走这里）。 */
    public String hash(String rawPassword) {
        if (rawPassword == null) return null;
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = pbkdf2(rawPassword.toCharArray(), salt, iterations);
        return PBKDF2_PREFIX + iterations + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(key);
    }

    /**
     * 校验密码。
     *
     * <p>两种格式都认：{@code pbkdf2$...} 走 PBKDF2；其它一律按老的 SHA-256 校验
     * （迁移期必须保留这条路，否则所有老用户会被锁在门外）。比较用定长比较。
     */
    public boolean verify(String rawPassword, String stored) {
        if (rawPassword == null || stored == null || stored.isEmpty()) return false;
        if (stored.startsWith(PBKDF2_PREFIX)) return verifyPbkdf2(rawPassword, stored);
        byte[] expected = legacySha256(rawPassword).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, stored.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 这条记录是不是老格式（登录成功后要顺手重写成新格式）。
     *
     * <p>为什么要"登录时顺手升级"而不是"强制全员改密码"：后者会让所有人（包括只是很久没登录的）
     * 突然用不了，还得解释；前者用户完全无感 —— 一次成功登录就自动搬到新格式，长期不登录的账号
     * 因为登录不成功也无所谓（它们本来就没在用）。
     */
    public boolean needsUpgrade(String stored) {
        return stored != null && !stored.isEmpty() && !stored.startsWith(PBKDF2_PREFIX);
    }

    /** 老算法（只用于校验历史记录，绝不用于新密码） */
    static String legacySha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // 唯一可能：环境不支持 SHA-256（不可能发生）。宁可抛出去也不要静默返回一个"看起来像哈希"的串
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private boolean verifyPbkdf2(String rawPassword, String stored) {
        try {
            String[] parts = stored.split("\\$");
            // pbkdf2$<iter>$<salt>$<hash> → split 出 4 段
            if (parts.length != 4) return false;
            int iter = Integer.parseInt(parts[1]);
            if (iter < MIN_ITERATIONS) return false;   // 记录被人改小了 → 当作无效，不能按弱参数校验
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iter);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;   // 格式坏了就当校验失败，不抛异常（避免把内部细节泄给调用方）
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();   // 别把口令留在堆里
            }
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }
}
