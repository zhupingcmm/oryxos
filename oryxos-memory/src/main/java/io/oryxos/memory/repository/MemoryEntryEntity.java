package io.oryxos.memory.repository;

import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.List;

/**
 * agent_memories 表的 JPA 实体（006-memory-layer）。
 *
 * <p>字段定义见 V4__add_agent_memories.sql + data-model.md §3.3。
 * tags 用 JSON-as-TEXT 存储（research R-02）；{@link #toMemoryEntry()}
 * 解析 tags JSON 为 {@code List<String>}。
 *
 * <p>本实体专用于 SqliteMemoryStore；不与 MarkdownMemoryStore 共享。
 */
@Entity
@Table(name = "agent_memories")
public class MemoryEntryEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private MemoryScope scope;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "tags", nullable = false, columnDefinition = "TEXT")
    private String tagsJson;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private long createdAtMillis;

    public MemoryEntryEntity() {
        // JPA required
    }

    public MemoryEntryEntity(String id, MemoryScope scope, String content, String tagsJson,
                             String source, long createdAtMillis) {
        this.id = id;
        this.scope = scope;
        this.content = content;
        this.tagsJson = tagsJson;
        this.source = source;
        this.createdAtMillis = createdAtMillis;
    }

    public String getId() { return id; }
    public MemoryScope getScope() { return scope; }
    public String getContent() { return content; }
    public String getTagsJson() { return tagsJson; }
    public String getSource() { return source; }
    public long getCreatedAtMillis() { return createdAtMillis; }

    public void setId(String id) { this.id = id; }
    public void setScope(MemoryScope scope) { this.scope = scope; }
    public void setContent(String content) { this.content = content; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }
    public void setSource(String source) { this.source = source; }
    public void setCreatedAtMillis(long createdAtMillis) { this.createdAtMillis = createdAtMillis; }

    /**
     * 转 MemoryEntry record（Phase 3 DefaultMemoryService 用）。
     * tags JSON 由调用方提前用 ObjectMapper 反序列化；本方法不做解析以避免 JPA 实体引入 Jackson 依赖。
     */
    public MemoryEntry toMemoryEntry(List<String> tags) {
        return new MemoryEntry(id, scope, content, tags, Instant.ofEpochMilli(createdAtMillis), source);
    }
}