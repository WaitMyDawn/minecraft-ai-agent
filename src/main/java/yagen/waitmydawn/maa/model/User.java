package yagen.waitmydawn.maa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "maa_user")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String accountNumber;  // 1000 起始

    @Column(unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private double preferenceWeight = 0.3;  // 偏好影响 0~1

    @Column(length = 1000)
    private String encryptedApiKey;  // AES 加密后的 API Key

    private boolean enableBlacklist = false;        // 是否启用模组黑名单
    private boolean enableUserFeedbackRules = false; // 是否参考用户反馈的模组关系规则

    /**
     * 邮箱（**归一化后的小写**，注册/绑定必填，唯一）。
     *
     * <p>唯一约束交给数据库：代码里"先查再插"在并发下必然漏。老账号这里为 NULL —— SQL 里 NULL
     * 不参与唯一约束，所以加这一列不需要给存量数据做任何迁移。
     */
    @Column(unique = true, length = 190)
    private String email;
    /**
     * 邮箱已验证。目前没有"填了但没验证"的中间态：注册和换绑都是"验证码过了才写库"。
     *
     * <p>为什么要写 {@code columnDefinition}：这个字段是 {@code boolean} 原语，Hibernate 会给它加
     * {@code not null}，而 {@code maa_user} 表里已经有数据 —— H2 拒绝"给已有表加没有默认值的 NOT NULL 列"，
     * 报 {@code NULL not allowed for column "EMAILVERIFIED"}，于是这一列<b>根本建不出来</b>，
     * 之后任何 SELECT 碰到它都是 "Column not found"（表现为登录/注册直接 500）。
     * 加上 {@code default false} 之后，存量行会被填成 false，ALTER 就能过。
     */
    @Column(columnDefinition = "boolean default false not null")
    private boolean emailVerified = false;
    /** 最近一次绑定/换绑时间，出问题能追溯（什么时候换的、换了几次） */
    private LocalDateTime emailBoundAt;

    public User() {}
    public User(String accountNumber, String username, String passwordHash) {
        this.accountNumber = accountNumber;
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String v) { this.accountNumber = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public double getPreferenceWeight() { return preferenceWeight; }
    public void setPreferenceWeight(double v) { this.preferenceWeight = v; }
    public String getEncryptedApiKey() { return encryptedApiKey; }
    public void setEncryptedApiKey(String v) { this.encryptedApiKey = v; }
    public boolean isEnableBlacklist() { return enableBlacklist; }
    public void setEnableBlacklist(boolean v) { this.enableBlacklist = v; }
    public boolean isEnableUserFeedbackRules() { return enableUserFeedbackRules; }
    public void setEnableUserFeedbackRules(boolean v) { this.enableUserFeedbackRules = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean v) { this.emailVerified = v; }
    public LocalDateTime getEmailBoundAt() { return emailBoundAt; }
    public void setEmailBoundAt(LocalDateTime v) { this.emailBoundAt = v; }
}
