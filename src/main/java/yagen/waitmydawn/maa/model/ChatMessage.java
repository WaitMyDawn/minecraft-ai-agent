package yagen.waitmydawn.maa.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "maa_chat_message")
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long conversationId;

    @Column(nullable = false)
    private String role;  // "user" or "ai"

    @Column(length = 5000)
    private String content;

    private boolean hasData;
    private String thinkTime;

    @Column(length = 10000)
    private String modSlugs;  // 当时的模组列表快照 (245个slug可达~4500字符)

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public ChatMessage() {}
    public ChatMessage(Long conversationId, String role, String content, boolean hasData, String thinkTime, String modSlugs) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.hasData = hasData;
        this.thinkTime = thinkTime;
        this.modSlugs = modSlugs;
    }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long v) { this.conversationId = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public boolean isHasData() { return hasData; }
    public void setHasData(boolean v) { this.hasData = v; }
    public String getThinkTime() { return thinkTime; }
    public void setThinkTime(String v) { this.thinkTime = v; }
    public String getModSlugs() { return modSlugs; }
    public void setModSlugs(String v) { this.modSlugs = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
