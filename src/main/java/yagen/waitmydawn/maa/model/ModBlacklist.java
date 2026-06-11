package yagen.waitmydawn.maa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "maa_mod_blacklist")
public class ModBlacklist {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String source = "user";  // "user"(手动slug) 或 "agent"(筛选选取)

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public ModBlacklist() {}
    public ModBlacklist(Long userId, String slug, String source) {
        this.userId = userId; this.slug = slug; this.source = source;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
