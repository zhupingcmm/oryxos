package io.oryxos.storage.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import io.oryxos.core.Message;
import io.oryxos.core.Session;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Type;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 会话持久化实体 —— JPA 映射到 {@code sessions} 表。
 *
 * <p>实现 {@link io.oryxos.core.Session} 接口（接口在 core；实现在 storage）。messages
 * 以 JSON 列存，保留完整 Session 对话历史以便回放（spec FR-016）。
 *
 * <p>详见 [data-model.md §3.2.2](../../../../../specs/002-react-loop/data-model.md)。
 */
@Entity
@Table(
    name = "sessions",
    indexes = {
        @Index(name = "idx_profile", columnList = "profile_name"),
        @Index(name = "idx_updated", columnList = "updated_at")
    }
)
@Check(constraints = "length(id) > 0")
@Check(constraints = "length(profile_name) > 0")
public class SessionEntity implements Session {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "TEXT")
    private UUID id;

    @Column(name = "profile_name", nullable = false, columnDefinition = "TEXT")
    private String profileName;

    /** JSON list of {@link Message}；按 spec FR-016 保留完整会话历史。 */
    @Type(JsonType.class)
    @Column(name = "messages", nullable = false, columnDefinition = "TEXT")
    private List<Message> messages = new ArrayList<>();

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    private Instant updatedAt;

    // --- 构造器 / 工厂 ---

    /** JPA 用的 protected no-arg 构造器。 */
    protected SessionEntity() {
        // JPA
    }

    private SessionEntity(UUID id, String profileName, Instant createdAt) {
        this.id = id;
        this.profileName = profileName;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** 工厂方法 —— 新建一个空 Session。 */
    public static SessionEntity create(UUID id, String profileName) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName must not be blank");
        }
        return new SessionEntity(id, profileName, Instant.now());
    }

    // --- Session 接口实现 ---

    @Override public UUID id()              { return id; }
    @Override public String profileName()   { return profileName; }
    @Override public Instant createdAt()    { return createdAt; }
    @Override public Instant updatedAt()    { return updatedAt; }

    @Override
    public List<Message> messages() {
        // 不可变视图 —— 调用方不应修改
        return List.copyOf(messages);
    }

    @Override
    @Transactional
    public void appendMessage(Message m) {
        if (m == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        messages.add(m);
        this.updatedAt = Instant.now();
        // Spring Data JPA 脏检查会自动 flush；本类不显式 save()
    }

    // --- JPA 需要的 getter（如有 Hibernate 反射依赖） ---

    @Transient
    public List<Message> getMessagesInternal() {
        return messages;
    }

    /** 测试用 setter —— 抑制 JPA-only 反射写入警告。 */
    void setMessagesForTesting(List<Message> m) {
        this.messages = new ArrayList<>(m);
    }
}
