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
import java.util.Map;
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

    /**
     * 008-agent-scheduler 阶段新增 —— Session 元数据 JSON 字段。
     *
     * <p>对应 SQLite {@code sessions.metadata} TEXT 列（已在 DDL 中，JPA 实体之前未映射）。
     * 由 {@code SessionFactoryImpl.create()} 在 scheduler 触发时写入
     * {@code task_id} / {@code source} / {@code started_at} 三个键（per [data-model.md §实体 4](../../../../../../specs/008-agent-scheduler/data-model.md)）。
     *
     * <p>键名固定（[data-model.md §实体 4 JSON shape 字节级契约](../../../../../../specs/008-agent-scheduler/data-model.md)）：
     * <ul>
     *   <li>{@code task_id} (String) — 仅 {@code source="scheduler"} 时填；映射 {@code scheduled_tasks.task_id}</li>
     *   <li>{@code source} (String) — 三选一：{@code "cli"} / {@code "web"} / {@code "scheduler"}</li>
     *   <li>{@code started_at} (String ISO-8601 UTC) — 可选；触发起始时间</li>
     * </ul>
     */
    @Type(JsonType.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private Map<String, Object> metadata;

    /**
     * 008-agent-web-service 阶段新增 —— 软删除时间戳.
     *
     * <p>{@code null} = 活跃;非空 = 软删除时刻 (UTC).
     * 软删除契约 (per [data-model.md §端点 5](../../../../../../specs/008-agent-web-service/data-model.md)) :
     * DELETE /api/v1/sessions/{id} → {@code UPDATE sessions SET deleted_at = now() WHERE id = ?}
     * 而非真删,后续 GET 返回 404 session_not_found.
     *
     * <p>对齐 006-memory-layer 删除契约 (C-MD / C-SQ 后端都支持软删或真删,核心阶段统一为软删).
     */
    @Column(name = "deleted_at", columnDefinition = "TEXT")
    private Instant deletedAt;

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

    /**
     * 工厂方法 —— 创建 Session 同时写入 metadata JSON。
     *
     * <p>用于 Scheduler 触发场景（data-model.md §实体 4）—— 一次性写入
     * {@code task_id} + {@code source} + {@code started_at} 三个键，缺一抛
     * {@link IllegalArgumentException}（fail-closed per data-model.md 字节级契约）。
     *
     * @param id            Session UUID
     * @param profileName   Profile 名
     * @param taskId        {@code <profileName>:<id>} 形式；仅在 {@code source="scheduler"} 时必填
     * @param source        三选一：{@code "cli"} / {@code "web"} / {@code "scheduler"}
     * @param startedAtUtc  触发起始时间（UTC）
     * @return 新 Session
     */
    public static SessionEntity createWithMetadata(
        UUID id, String profileName, String taskId, String source, Instant startedAtUtc
    ) {
        if (source == null
            || !(source.equals("cli") || source.equals("web") || source.equals("scheduler"))) {
            throw new IllegalArgumentException(
                "source must be one of {cli, web, scheduler}, got: " + source);
        }
        if ("scheduler".equals(source) && (taskId == null || taskId.isBlank())) {
            throw new IllegalArgumentException(
                "taskId is required when source=\"scheduler\" (data-model.md §实体 4 字节级契约)");
        }
        SessionEntity entity = create(id, profileName);
        entity.metadata = new java.util.HashMap<>();
        entity.metadata.put("source", source);
        if (taskId != null) {
            entity.metadata.put("task_id", taskId);
        }
        if (startedAtUtc != null) {
            entity.metadata.put("started_at", startedAtUtc.toString());
        }
        return entity;
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

    // --- metadata JSON helper（008-agent-scheduler 阶段新增） ---

    /** 读取 metadata JSON（不可变视图；{@code null} 表示 session 无元数据）。 */
    public Map<String, Object> getMetadata() {
        return metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 写入整个 metadata map（覆盖语义）。 */
    public void setMetadata(Map<String, Object> m) {
        this.metadata = m == null ? null : new java.util.HashMap<>(m);
    }

    /** 取 metadata 中某个键（{@code null} 安全；键不存在返回 {@code null}）。 */
    public Object getMetadataValue(String key) {
        return metadata == null ? null : metadata.get(key);
    }

    /** 写入单个 metadata 键（增量语义）。 */
    public void setMetadataValue(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new java.util.HashMap<>();
        }
        if (value == null) {
            this.metadata.remove(key);
        } else {
            this.metadata.put(key, value);
        }
    }

    // --- 008-agent-web-service 软删除 (T025) ---

    /** 读取软删除时间戳;{@code null} = 活跃会话. */
    public Instant getDeletedAt() {
        return deletedAt;
    }

    /**
     * 标记软删除. {@code deletedAt} 写入当前 UTC 时刻,JPA 脏检查自动 flush.
     *
     * <p>幂等: 二次调用会刷新 deletedAt,但 {@code GET /api/v1/sessions/{id}} 已
     * 在控制器层按 deletedAt is null 过滤,因此二次删除对业务方无感知.
     */
    @Transactional
    public void markDeleted() {
        this.deletedAt = Instant.now();
    }

    /** 当前会话是否已被软删除. */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
