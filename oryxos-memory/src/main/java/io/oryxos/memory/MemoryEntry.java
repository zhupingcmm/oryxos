package io.oryxos.memory;

import java.time.Instant;

/**
 * 单条长期记忆条目（{@code LongTermMemoryStore} 的最小存储单元）。
 *
 * <p>核心区（{@link MemoryScope#CORE}）永不被截断 —— 写入即留下；
 * 归档区（{@link MemoryScope#ARCHIVE}）可被压缩 / 归档。
 *
 * @param content   记忆文本内容
 * @param scope     写入分区（{@link MemoryScope#CORE} / {@link MemoryScope#ARCHIVE}）
 * @param createdAt 写入时间（本地时间；用于 debug / 排序）
 */
public record MemoryEntry(
    String content,
    MemoryScope scope,
    Instant createdAt
) {
    public MemoryEntry {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
    }
}

