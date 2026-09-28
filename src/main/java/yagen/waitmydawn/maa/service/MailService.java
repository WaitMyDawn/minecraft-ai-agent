package yagen.waitmydawn.maa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.InetAddress;

/**
 * 验证码邮件发送（通道路由）。
 *
 * <p>目前支持两条通道，由 {@code maa.mail.provider} 选择：
 * <ul>
 *   <li>{@code resend}（默认）：走 Resend 的 HTTP API。选它是因为域名 verified 之后
 *       <b>不需要逐个验证发件人地址</b>，注册门槛最低；代价是没有额度查询接口，
 *       只能靠 429 响应判断"发满了"；</li>
 *   <li>{@code brevo}：走 Brevo 的 HTTP API，<b>额度能查</b>（免费额度用完后可以提前拒绝）；</li>
 *   <li>{@code smtp}：原来的 SMTP（阿里云 DirectMail / QQ / 163 都行）。配置留着，随时一个
 *       {@code MAA_MAIL_PROVIDER=smtp} 就切回去。</li>
 * </ul>
 *
 * <p><b>额度检查的取舍</b>：只有 Brevo 能查余额，所以对 Brevo 会"发之前先看一眼余额"（结果缓存
 * 5 分钟，别每封都打一次 API）。余额查不出来时<b>照常发送</b> —— 查额度是锦上添花，
 * 不能因为它把注册流程弄挂。
 *
 * <p><b>为什么注入的是 {@code ObjectProvider<JavaMailSender>} 而不是直接注入</b>：Spring Boot 的
 * {@code MailSenderAutoConfiguration} 是"有 {@code spring.mail.host} 才装配"的条件配置。本项目的
 * {@code spring.mail.host=${MAA_MAIL_HOST:}} 在没配时是个空串，取到哪种情况都说不准 —— 直接注入的话
 * 一旦 bean 不存在，<b>整个应用都起不来</b>（而邮件只是附属功能）。用 ObjectProvider 拿到不确定的依赖，
 * 没配就优雅降级成"邮件功能未启用"。
 *
 * <p><b>失败必须让调用方知道</b>：{@link #sendCode} 失败时抛异常，绝不吞掉。验证码接口宁可回
 * "发送失败，请稍后重试"，也不能假装发出去了 —— 那会让用户在页面上干等一封永远不来的邮件。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** 邮件功能未启用（没配开关或没配发件人）。调用方据此回"稍后重试"或"功能未开放"。 */
    public static class NotConfiguredException extends RuntimeException {
        public NotConfiguredException(String message) {
            super(message);
        }
    }

    /** 额度用尽（目前只有 Brevo 能判断）。独立类型是为了让上层回一句人话而不是"发送失败"。 */
    public static class QuotaExhaustedException extends RuntimeException {
        public QuotaExhaustedException(String message) {
            super(message);
        }
    }

    private final ObjectProvider<JavaMailSender> senderProvider;
    private final boolean enabled;
    private final String from;
    private final String fromName;
    private final String provider;
    private final BrevoMailClient brevo;
    private final ResendMailClient resend;
    private final long quotaCacheMs;

    /** 缓存的额度查询结果 */
    private volatile Quota quota;
    private volatile long quotaCheckedAt;

    /**
     * 验收/评测专用：开成 true 时，每次发信都直接按"额度已用完"失败（不调用任何发信通道）。
     *
     * <p>为什么需要它：Resend 的免费额度一天 100 封，想验证"发满之后用户看到什么"没法真烧掉额度。
     * 和项目里 {@code maa.eval.force-critic-degraded} 是同一个套路 —— 只为了能确定性地复现一条路径。
     * <b>生产别打开。</b>
     *
     * <p>这个开关没走构造器：它是纯评测用的分支，塞进已经 9 个参数的构造器只会让签名更难维护。
     */
    @Value("${maa.mail.simulate-quota:false}")
    private boolean simulateQuota;

    @Autowired
    public MailService(ObjectProvider<JavaMailSender> senderProvider,
                       @Value("${maa.mail.enabled:false}") boolean enabled,
                       @Value("${maa.mail.from:}") String from,
                       @Value("${maa.mail.from-name:MAA}") String fromName,
                       @Value("${maa.mail.provider:resend}") String provider,
                       @Value("${maa.mail.brevo.quota-cache-ms:300000}") long quotaCacheMs,
                       BrevoMailClient brevo,
                       ResendMailClient resend) {
        this.senderProvider = senderProvider;
        this.enabled = enabled;
        this.from = from == null ? "" : from.trim();
        this.fromName = fromName == null || fromName.isBlank() ? "MAA" : fromName.trim();
        this.provider = provider == null ? "resend" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        this.quotaCacheMs = Math.max(0, quotaCacheMs);
        this.brevo = brevo;
        this.resend = resend;
    }

    /** 兼容用的旧构造器：等价于"用 SMTP 通道"（已有测试走这条，不必跟着改签名） */
    public MailService(ObjectProvider<JavaMailSender> senderProvider, boolean enabled,
                       String from, String fromName) {
        this(senderProvider, enabled, from, fromName, "smtp", 300_000L, null, null);
    }

    /** 带通道选择的构造器（测试用；Spring 走上面那个带 @Autowired 的） */
    public MailService(ObjectProvider<JavaMailSender> senderProvider, boolean enabled,
                       String from, String fromName, String provider, long quotaCacheMs,
                       BrevoMailClient brevo) {
        this(senderProvider, enabled, from, fromName, provider, quotaCacheMs, brevo, null);
    }

    /** 当前生效的通道名（brevo / smtp） */
    public String providerName() {
        return provider;
    }

    /** 一次额度查询的结果。remaining = null 表示"查不到"（此时不阻断发送）。 */
    public record Quota(String provider, Integer remaining, String detail, long checkedAtMillis) {
        public boolean exhausted() {
            return remaining != null && remaining <= 0;
        }
    }

    /** 邮件功能是否可用（开关开着 + 配了发件人 + 容器里真有 JavaMailSender） */
    public boolean isConfigured() {
        return diagnose() == null;
    }

    /**
     * 邮件不可用的<b>具体</b>原因；可用时返回 null。
     *
     * <p>为什么要有它：原来只有一句"邮件服务暂不可用"，把三种完全不同的原因糊在一起 ——
     * 少填开关、少填发件人、.env 没生效（restart 不重读 .env），排查起来全靠猜。
     * 现在直接把缺哪一项说出来。
     */
    public String diagnose() {
        if (!enabled) return "邮件功能未启用：.env 里需要 MAA_MAIL_ENABLED=true（改完要 docker compose up -d）";
        if ("resend".equals(provider)) {
            if (resend == null) return "Resend 客户端未装配（内部错误）";
            return resend.diagnose();
        }
        if ("brevo".equals(provider)) {
            if (brevo == null) return "Brevo 客户端未装配（内部错误）";
            return brevo.diagnose();
        }
        if (from.isEmpty()) return "未配置发件人地址：.env 里 MAA_MAIL_FROM 是空的";
        if (senderProvider.getIfAvailable() == null) {
            return "SMTP 未配置：.env 里的 MAA_MAIL_HOST 没生效（改过 .env 必须 docker compose up -d，restart 不重读）";
        }
        return null;
    }

    /** 发件人地址（运维自检端点用它做"发给我自己"的收件人） */
    public String fromAddress() {
        if ("resend".equals(provider) && resend != null) return resend.fromAddress();
        if ("brevo".equals(provider) && brevo != null) return brevo.fromAddress();
        return from;
    }

    /**
     * 当前额度（带缓存）。只有 Brevo 通道能查到；SMTP 通道返回 remaining=null。
     *
     * <p>查不到不抛异常 —— 把这个信息用 detail 带出去，让运维端点和日志能看见。
     */
    public Quota quota() {
        if ("resend".equals(provider)) {
            // Resend 没有额度查询接口。明确说出来，而不是让运维以为"查不到 = 没配好"。
            // 真实额度只能从发信响应看：发满了它返回 429，我们把它翻译成"额度用完"。
            return new Quota("resend", null,
                    "Resend 不提供额度查询接口；发满免费额度时发信会返回 429（会被翻译成「额度已用完」）",
                    System.currentTimeMillis());
        }
        if (!"brevo".equals(provider) || brevo == null || !brevo.isConfigured()) {
            return new Quota(provider, null, "当前通道不提供额度查询", System.currentTimeMillis());
        }
        Quota cached = quota;
        long now = System.currentTimeMillis();
        if (cached != null && now - quotaCheckedAt < quotaCacheMs) return cached;
        synchronized (this) {
            if (quota != null && System.currentTimeMillis() - quotaCheckedAt < quotaCacheMs) return quota;
            Quota fresh;
            try {
                Integer remaining = BrevoMailClient.remainingCredits(brevo.account());
                fresh = new Quota("brevo", remaining,
                        remaining == null ? "账户信息里没找到 sendLimit/credits 字段（看 /api/ops/mail-status 的原始响应）"
                                : "Brevo 账户查询成功",
                        System.currentTimeMillis());
            } catch (Exception e) {
                // 查额度失败不影响发信：给出原因，但 remaining 保持 null（= 不阻断）
                fresh = new Quota("brevo", null,
                        "额度查询失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        System.currentTimeMillis());
            }
            quota = fresh;
            quotaCheckedAt = fresh.checkedAtMillis();
            return fresh;
        }
    }

    /**
     * 发一封验证码邮件。
     *
     * @param to           收件人（调用方负责已归一化）
     * @param purposeLabel 用途的人话说法，例如"注册账号"——写进正文，用户收到"重置密码"的信但他没操作，
     *                     一眼就能判断是被人填了邮箱
     * @param code         6 位验证码
     * @param ttlMinutes   有效期（分钟）
     * @throws NotConfiguredException 邮件功能不可用
     * @throws org.springframework.mail.MailException SMTP 发送失败（超时/认证失败/被拒等）
     */
    public void sendCode(String to, String purposeLabel, String code, int ttlMinutes) {
        if (simulateQuota) {
            throw new QuotaExhaustedException("（模拟）今日邮件发送量已达上限");
        }
        // 先看额度（只对 Brevo 有效）。用完了就干脆不发 —— 让用户看到"额度用完"而不是"发送失败"，
        // 也符合"额度用完就不发"的要求。
        Quota q = quota();
        if (q.exhausted()) {
            throw new QuotaExhaustedException("邮件额度已用完（" + q.provider() + " 剩余 "
                    + q.remaining() + " 封），请稍后再试或联系站长");
        }

        String subject = "【" + fromName + "】你的验证码是 " + code;
        String text = """
                你正在执行「%s」。

                验证码：%s
                有效期：%d 分钟，请勿泄露给任何人。

                如果这不是你本人的操作，忽略本邮件即可（你的账号是安全的）。
                """.formatted(purposeLabel, code, ttlMinutes);

        if ("resend".equals(provider)) {
            if (resend == null || !resend.isConfigured()) {
                throw new NotConfiguredException(diagnose());
            }
            try {
                resend.send(to, subject, text);
            } catch (ResendMailClient.ResendException e) {
                if (e.isRateOrQuotaLimited()) {
                    // 429 = 频率或免费额度用满。分不清是哪种，但对用户来说都是"今天发不了了"
                    throw new QuotaExhaustedException("邮件额度或频率已达上限（Resend 429）：" + e.getMessage());
                }
                throw e;
            }
            log.info("验证码邮件已提交 Resend：to={} purpose={} host={}", mask(to), purposeLabel, hostName());
            return;
        }
        if ("brevo".equals(provider)) {
            if (brevo == null || !brevo.isConfigured()) {
                throw new NotConfiguredException(diagnose());
            }
            brevo.send(to, subject, text);
            log.info("验证码邮件已提交 Brevo：to={} purpose={} host={}", mask(to), purposeLabel, hostName());
            invalidateQuotaCache();   // 刚发了一封，缓存的余额已经不准了
            return;
        }

        JavaMailSender sender = senderProvider.getIfAvailable();
        if (!enabled || from.isEmpty() || sender == null) {
            throw new NotConfiguredException("邮件服务未配置，暂时无法发送验证码");
        }

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(fromName + " <" + from + ">");
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(text);
        sender.send(msg);
        // 顺手带上主机名：多台机器/多个环境共用一个发信地址时，能看出这封信是谁发的
        log.info("验证码邮件已提交 SMTP：to={} purpose={} host={}", mask(to), purposeLabel, hostName());
    }

    /** 发信后让缓存的余额失效，下次发信会重新查（否则会拿旧余额判断"还有额度"） */
    private void invalidateQuotaCache() {
        quota = null;
        quotaCheckedAt = 0L;
    }

    /** 只给测试用：打开/关闭"模拟额度用完"（生产由配置项 maa.mail.simulate-quota 控制） */
    void setSimulateQuotaForTest(boolean value) {
        this.simulateQuota = value;
    }

    /** 日志里别写完整邮箱：留前两位 + 域名，够定位问题又不算是明文个人信息 */
    public static String mask(String email) {
        if (email == null) return "null";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(at, 0));
        return email.substring(0, 2) + "***" + email.substring(at);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
