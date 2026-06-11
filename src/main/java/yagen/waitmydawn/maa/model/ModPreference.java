package yagen.waitmydawn.maa.model;

import jakarta.persistence.*;

@Entity
@Table(name = "maa_mod_pref")
public class ModPreference {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    private String category;  // adventure, magic, technology...

    @Column(nullable = false)
    private String slug;

    private int buildCount;  // 参与构建次数

    @Column(nullable = false)
    private String source = "agent";  // "agent" 自动收集, "user" 手动添加

    public ModPreference() {}
    public ModPreference(Long userId, String category, String slug, int buildCount) {
        this(userId, category, slug, buildCount, "agent");
    }
    public ModPreference(Long userId, String category, String slug, int buildCount, String source) {
        this.userId = userId; this.category = category; this.slug = slug; this.buildCount = buildCount; this.source = source;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v; }
    public int getBuildCount() { return buildCount; }
    public void setBuildCount(int v) { this.buildCount = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
}
