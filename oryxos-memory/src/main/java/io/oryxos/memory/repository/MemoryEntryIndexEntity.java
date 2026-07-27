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
 * memory_index 表的 JPA 实体（006-memory-layer US-3 / FR-015）。
 *
 * <p>Mem0 后端专用 —— 当 Mem0 服务不可达时把条目落到本地索引（{@code pending=true}），
 * 后续 Mem0 恢复健康时回填；不可达期间 recallByKeyword 降级读本地。
 *
 * <p>字段对应 V5__add_memory_index.sql：
 * <ul>
 *   <li>{@code local_id} —— 本表 PK（UUID）</li>
 *   <li>{@code mem0_id} —— Mem0 服务端返回 ID（不可达时为 NULL）</li>
 *   <li>{@code scope} —— 枚举大写（{@code CORE} / {@code ARCHIVE}）</li>
 *   <li>{@code pending} —— {@code true} = 待同步；{@code false} = 已同步</li>
 *   <li>{@code created_at} —— 毫秒时间戳</li>
 * </ul>
 */
@Entity
@Table(name = "memory_index")
public class MemoryEntryIndexEntity {

    @Id
    @Column(name = "local_id", length = 36, nullable = false)
    private String localId;

    @Column(name = "mem0_id", length = 64)
    private String mem0Id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false)
    private MemoryScope scope;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "tags", nullable = false, columnDefinition = "TEXT")
    private String tagsJson;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "pending", nullable = false)
    private boolean pending;

    @Column(name = "created_at", nullable = false)
    private long createdAtMillis;

    public MemoryEntryIndexEntity() {
        // JPA required
    }

    public MemoryEntryIndexEntity(String localId, String mem0Id, MemoryScope scope,
                                   String content, String tagsJson, String source,
                                   boolean pending, long createdAtMillis) {
        this.localId = localId;
        this.mem0Id = mem0Id;
        this.scope = scope;
        this.content = content;
        this.tagsJson = tagsJson;
        this.source = source;
        this.pending = pending;
        this.createdAtMillis = createdAtMillis;
    }

    public String getLocalId() { return localId; }
    public String getMem0Id() { return mem0Id; }
    public MemoryScope getScope() { return scope; }
    public String getContent() { return content; }
    public String getTagsJson() { return tagsJson; }
    public String getSource() { return source; }
    public boolean isPending() { return pending; }
    public long getCreatedAtMillis() { return createdAtMillis; }

    public void setLocalId(String localId) { this.localId = localId; }
    public void setMem0Id(String mem0Id) { this.mem0Id = mem0Id; }
    public void setScope(MemoryScope scope) { this.scope = scope; }
    public void setContent(String content) { this.content = content; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }
    public void setSource(String source) { this.source = source; }
    public void setPending(boolean pending) { this.pending = pending; }
    public void setCreatedAtMillis(long createdAtMillis) { this.createdAtMillis = createdAtMillis; }

    /**
     * 转 MemoryEntry record（Mem0MemoryStore 用）。tags JSON 由调用方提前反序列化。
     */
    public MemoryEntry toMemoryEntry(List<String> tags) {
        return new MemoryEntry(localId, scope, content, tags,
            Instant.ofEpochMilli(createdAtMillis), source);
    }
}