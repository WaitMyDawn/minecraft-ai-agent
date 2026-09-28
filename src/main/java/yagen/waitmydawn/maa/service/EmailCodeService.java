package yagen.waitmydawn.maa.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 邮箱验证码：签发、校验、限流。
 *
 * <p><b>为什么放内存（Caffeine）而不是建表</b>：验证码 TTL 只有 5 分钟，重启丢掉的代价是"让用户再点一次
 * 发送"，而建表要额外处理清理策略、还要往业务库里塞短期数据。真需要"审计谁什么时候要过码"时再落库。
 *
 * <p><b>限流的口径（用户确认过的规则）</b>：
 * <ul>
 *   <li>同一个邮箱<b>一天最多 3 个码</b>，注册/重置/换绑三类<b>共用</b>这个额度；</li>
 *   <li>两次发送之间至少间隔 60 秒；</li>
 *   <li>校验失败 5 次即作废，成功即作废（一次性）。</li>
 * </ul>
 * 这条"按邮箱日限 3"顺手解决了一个本来很危险的问题：这个接口天然能给别人邮箱发信，
 * 没有限额就等于把自己的 SMTP 变成"邮件轰炸机"。限额之后，<b>每个目标邮箱一天最多收到你 3 封</b>。
 *
 * <p><b>验证码存明文</b>：6 位数字只有 100 万种可能，存哈希对"内存被 dump"这个场景帮助有限，
 * 真正的防线是"5 次尝试上限 + 5 分钟 TTL"。存明文换来的是实现简单（重发、日志排查都直接）。
 */
@Service
public class EmailCodeService {

    /**
     * 验证码用途。用途参与缓存 key —— 注册的码不能拿去改密码，换绑的码不能拿去重置密码。
     */
    public enum Purpose {
        REGISTER("注册账号"),
        RESET("重置密码"),
        CHANGE_PASSWORD("修改密码"),
        BIND_NEW("绑定新邮箱");

        private final String label;

        Purpose(String label) {
            this.label = label;
        }

        /** 写进邮件正文的人话说法 */
        public String label() {
            return label;
        }
    }

    /** 被限流时抛这个，{@code getMessage()} 直接就是给用户看的话 */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    private static final int CODE_BOUND = 1_000_000;      // 6 位
    private static final int DEFAULT_TTL_MINUTES = 5;
    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_MAX_PER_DAY = 3;
    private static final long DEFAULT_COOLDOWN_MS = 60_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 待校验的验证码：key = purpose|email */
    private final Cache<String, Pending> pending = Caffeine.newBuilder()
            .expireAfterWrite(DEFAULT_TTL_MINUTES, TimeUnit.MINUTES)
            .maximumSize(20_000)
            .build();

    /** 发送配额：key = email（跨用途共用），value 里带日期，跨天自动重置 */
    private final Cache<String, Quota> quota = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.DAYS)
            .maximumSize(20_000)
            .build();

    private final int maxPerDay;
    private final long cooldownMs;
    private final LongSupplier clock;

    @Autowired
    public EmailCodeService(@Value("${maa.email-code.max-per-day:3}") int maxPerDay,
                            @Value("${maa.email-code.cooldown-ms:60000}") long cooldownMs) {
        this(maxPerDay, cooldownMs, System::currentTimeMillis);
    }

    /**
     * 注入时钟的版本：测试用它把"冷却 60 秒""跨天重置"这类规则确定性地验出来（不用 sleep）。
     * Spring 用的是上面那个带 {@code @Value} 的构造器（有 {@code @Autowired} 标注，多构造器时以它为准）。
     */
    public EmailCodeService(int maxPerDay, long cooldownMs, LongSupplier clock) {
        this.maxPerDay = Math.max(1, maxPerDay);
        this.cooldownMs = Math.max(0, cooldownMs);
        this.clock = clock;
    }

    /** 验证码有效期（分钟），给调用方拼提示语用 */
    public int ttlMinutes() {
        return DEFAULT_TTL_MINUTES;
    }

    /**
     * 签发一个验证码。
     *
     * @return 6 位数字验证码（明文，由调用方发邮件）
     * @throws RateLimitedException 冷却中 / 当日额度用完
     */
    public String issue(Purpose purpose, String email) {
        String key = key(purpose, email);
        String quotaKey = EmailNormalizer.normalize(email);
        long now = clock.getAsLong();
        LocalDate today = today();

        // compute 里做判断并返回新配额：同一个邮箱并发发码时不会互相覆盖（Caffeine 的 asMap 支持原子 compute）
        Quota next = quota.asMap().compute(quotaKey, (k, old) -> {
            if (old == null || !old.day.equals(today)) {
                return new Quota(today, 1, now);
            }
            if (now - old.lastSentAt < cooldownMs) {
                long waitSec = (cooldownMs - (now - old.lastSentAt) + 999) / 1000;
                throw new RateLimitedException("发送太频繁，请 " + waitSec + " 秒后再试");
            }
            if (old.count >= maxPerDay) {
                throw new RateLimitedException("该邮箱今天已发送 " + maxPerDay + " 次验证码，请明天再试");
            }
            return new Quota(today, old.count + 1, now);
        });

        String code = randomCode();
        // 重新签发就覆盖旧的（用户点重发后，旧码立即失效，避免"两个码都能用"）
        pending.put(key, new Pending(code, 0));
        return code;
    }

    /**
     * 校验验证码。成功即作废（一次性）。
     *
     * @return true = 通过
     */
    public boolean verify(Purpose purpose, String email, String code) {
        if (code == null || code.isBlank()) return false;
        String key = key(purpose, email);
        String input = code.trim();

        boolean[] ok = {false};
        pending.asMap().compute(key, (k, cur) -> {
            if (cur == null) return null;                       // 没签发过 / 已过期
            if (cur.code.equals(input)) {
                ok[0] = true;
                return null;                                    // 一次性：用过就删
            }
            int attempts = cur.attempts + 1;
            return attempts >= DEFAULT_MAX_ATTEMPTS ? null : new Pending(cur.code, attempts);
        });
        return ok[0];
    }

    /** 作废某个用途的验证码（比如邮箱验证失败需要重新走流程时） */
    public void invalidate(Purpose purpose, String email) {
        pending.invalidate(key(purpose, email));
    }

    /**
     * 发信失败后的回滚：撤掉刚签发的码，并把"今天已发次数"还回去。
     *
     * <p>为什么必须回滚：用户什么都没收到，却要因为一次服务端故障被扣掉当天 1/3 的额度
     * （甚至被"60 秒冷却"再挡一道），这属于把我们的问题算在用户头上。
     *
     * <p>为什么<b>保留</b>冷却时间戳（不回滚 lastSentAt）：万一某个收件地址会让 SMTP 立即拒信，
     * 回滚冷却就能被用来"无限次触发发信尝试"。保留冷却 = 这种情况最多每分钟一次，仍然安全。
     */
    public void rollback(Purpose purpose, String email) {
        pending.invalidate(key(purpose, email));
        quota.asMap().computeIfPresent(EmailNormalizer.normalize(email), (k, old) -> {
            int left = old.count - 1;
            return left <= 0 ? null : new Quota(old.day, left, old.lastSentAt);
        });
    }

    /** 当前该邮箱今天已发了几次（前端要显示"今日剩余次数"或调试用） */
    public int sentToday(String email) {
        Quota q = quota.getIfPresent(EmailNormalizer.normalize(email));
        if (q == null || !q.day.equals(today())) return 0;
        return q.count;
    }

    /**
     * "今天"必须从同一个时钟推出来，不能直接写 {@code LocalDate.now()} ——
     * 否则测试里把假时钟往后拨一天，"跨天重置额度"这条规则就测不了（真实日期不会跟着动）。
     */
    private LocalDate today() {
        return LocalDate.ofInstant(Instant.ofEpochMilli(clock.getAsLong()), ZoneId.systemDefault());
    }

    private static String key(Purpose purpose, String email) {
        // 归一化必须在这里也做一遍：签发用 Bob@QQ.com、校验用 bob@qq.com 时 key 才算同一个
        return purpose.name() + "|" + EmailNormalizer.normalize(email);
    }

    /** 6 位数字验证码（SecureRandom）。运维自检端点也用它，保证"发出去的码长什么样"只有一处定义。 */
    public static String randomCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(CODE_BOUND));
    }

    private record Pending(String code, int attempts) {}

    private record Quota(LocalDate day, int count, long lastSentAt) {}
}
