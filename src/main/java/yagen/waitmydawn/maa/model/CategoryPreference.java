package yagen.waitmydawn.maa.model;

import jakarta.persistence.*;

@Entity
@Table(name = "maa_category_pref")
public class CategoryPreference {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String category;  // adventure, magic, worldgen, etc.

    private int rank;  // 1=最偏好

    public CategoryPreference() {}
    public CategoryPreference(Long userId, String category, int rank) {
        this.userId = userId; this.category = category; this.rank = rank;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public int getRank() { return rank; }
    public void setRank(int v) { this.rank = v; }
}
