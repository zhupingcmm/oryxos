package io.oryxos.memory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 单条长期记忆条目 —— {@code LongTermMemoryStore} 的最小存储单元。
 *
 * <p>字段（spec data-model.md §2.1）：
 * <ul>
 *   <li>{@code id} —— UUID v4（OryxOS 本地主键；Mem0 后端另存映射表）</li>
 *   <li>{@code scope} —— 核心区（永不被截断）或归档区（可被裁剪）</li>
 *   <li>{@code content} —— 记忆文本内容</li>
 *   <li>{@code tags} —— 可选标签列表（用于 recall 过滤 / 审计维度）</li>
 *   <li>{@code createdAt} —— 写入时间（用于 debug / 排序）</li>
 *   <li>{@code source} —— 写入来源（save_memory Tool / Profile name / migration）</li>
 * </ul>
 *
 * <p>既有 3 字段构造器保留向后兼容（{@code MarkdownMemoryStore} 既有调用点 +
 * 005-tool-system 测试），新增字段由 canonical constructor 自动填充默认值。
 *
 * <p>核心阶段 Agent 经 {@code save_memory(content, scope)} 显式指定；
 * 默认 {@code CORE}。详见 [CLAUDE.md §9.6](../CLAUDE.md) Memory 四条契约。
 */
public record MemoryEntry(
    String id,
    MemoryScope scope,
    String content,
    List<String> tags,
    Instant createdAt,
    String source
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
        // id 缺省 → 自动生成 UUID v4（SqliteMemoryStore / MarkdownMemoryStore 写入时自填）
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        // tags 缺省 → 空列表；不可变化（防御性 copy）
        if (tags == null) {
            tags = List.of();
        } else {
            tags = List.copyOf(tags);
        }
        // source 缺省 → scope 名小写
        if (source == null || source.isBlank()) {
            source = scope.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 向后兼容构造器 —— 既有 003-cli-commands / 005-tool-system 阶段
     * {@code MarkdownMemoryStore} 与测试使用的 3 字段形式。
     *
     * <p>id 自动生成；tags 为空列表；source 默认为 scope 名小写。
     */
    public MemoryEntry(String content, MemoryScope scope, Instant createdAt) {
        this(null, scope, content, null, createdAt, null);
    }

    /**
     * 带 tags 的向后兼容构造器 —— SqliteMemoryStore.save() 走此形式。
     */
    public MemoryEntry(String content, MemoryScope scope, Instant createdAt, List<String> tags) {
        this(null, scope, content, tags, createdAt, null);
    }
}