package io.oryxos.memory;

import java.util.List;

/**
 * 长期记忆门面 —— 暴露给 ReAct loop 的统一接口（US-1 / 005-tool-system 中的
 * {@code save_memory} / {@code recall_memory} Tool 也只面向本门面）。
 *
 * <p>四条契约（CLAUDE.md §9.6）：
 * <ol>
 *   <li>不缓存 —— 每次 {@link #recallByKeyword} 直接走底层 store</li>
 *   <li>核心区（{@link MemoryScope#CORE}）永不被截断 —— 删除 / 压缩只针对 {@link MemoryScope#ARCHIVE}</li>
 *   <li>写核心还是写归档由 Agent 经 {@code scope} 显式指定</li>
 *   <li>{@link #recallByKeyword} 是关键词检索 —— 不引入向量检索</li>
 * </ol>
 *
 * <p>核心阶段当前仅落到 {@link MarkdownMemoryStore}（默认 backend）；
 * 扩展阶段再加 {@code SqliteMemoryStore} / {@code Mem0MemoryStore} 路由。
 */
public interface MemoryService {

    /**
     * 写入一条记忆。
     *
     * @param content  内容（必非空）
     * @param scope    写入分区（{@link MemoryScope#CORE} / {@link MemoryScope#ARCHIVE}）
     * @return 实际写入的条目（含 {@code createdAt} 时间戳）
     */
    MemoryEntry save(String content, MemoryScope scope);

    /**
     * 按关键词检索记忆（核心 + 归档全部搜索；返回按时间倒序）。
     *
     * @param query 关键词（必非空）
     * @param topK  返回最大条数（{@code <= 0} 视作 1）
     */
    List<MemoryEntry> recallByKeyword(String query, int topK);
}

