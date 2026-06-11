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
}
