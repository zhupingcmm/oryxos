package io.oryxos.memory;

import java.util.List;

/**
 * Memory 层统一门面 —— 暴露给 ReAct 循环 + Memory Tool + 业务方的唯一接口（spec FR-001）。
 *
 * <p>四条契约（CLAUDE.md §9.6）码化为方法约束：
 * <ol>
 *   <li>不缓存 —— 每次 {@link #recallByKeyword} 直接走底层 store（C-MS-01）</li>
 *   <li>核心区（{@link MemoryScope#CORE}）永不被截断 —— 删除 / 压缩只针对 {@link MemoryScope#ARCHIVE}（C-MS-02）</li>
 *   <li>写核心还是写归档由 Agent 经 {@code scope} 显式指定 —— {@code save(scope=null)} 抛异常（C-MS-03）</li>
 *   <li>{@link #recallByKeyword} 是关键词检索 —— 不引入向量检索（C-MS-06）</li>
 * </ol>
 *
 * <p>详见 [contracts/memory-service.md §1](../specs/006-memory-layer/contracts/memory-service.md)。
 */
public interface MemoryService {

    /**
     * 写入一条记忆（C-MS-03 scope 必填）。
     *
     * @param scope   必填；core 永不被截断；archive 可被 lazy trim
     * @param content 必非空
     * @param tags    可选标签列表（Markdown 后端 informational；SqliteMemoryStore 存 JSON-as-TEXT）
     * @return 新建的 MemoryEntry（含生成的 UUID id + createdAt）
     * @throws IllegalArgumentException scope / content 为 null 或空
     * @throws MemoryException 底层 IO 错误（磁盘满 / SQLite busy / Mem0 不可达等）
     */
    MemoryEntry save(MemoryScope scope, String content, List<String> tags);

    /**
     * 按关键词检索记忆（spec FR-006 + C-MS-06）。
     *
     * @param query        必非空；空 / null MUST 返回空集合（不抛异常）
     * @param topK         返回最大条数；{@code <= 0} 视作 1；{@code > 100} 截断到 100（C-MS-07）
     * @param scopeFilter  可选；null = 不限定 scope（跨 core + archive）
     * @return 按 createdAt DESC 排序的命中列表
     */
    List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter);

    /**
     * 按 scope 取最近 topK 条（C-LT-04：走索引按 createdAt DESC）。
     */
    List<MemoryEntry> recallByScope(MemoryScope scope, int topK);

    /**
     * 按 id 删除单条。
     *
     * @return true = 找到并删除；false = id 不存在
     */
    boolean delete(String entryId);

    /**
     * 清空某 scope 的全部记录（C-LT-05 / CLAUDE.md §9.6 契约 ②）。
     *
     * @throws IllegalStateException scope == core
     */
    void clear(MemoryScope scope);

    /**
     * 008-agent-web-service 阶段新增 —— 后端元数据汇总 (REST /api/v1/memory).
     *
     * <p>仅暴露非敏感元信息 (backend 类型 / 各 scope 条数 / 文件路径).
     * 内容读取需经 Agent 调 Tool (per CLAUDE.md §15 "核心阶段不做 Memory REST 详情").
     */
    default MemorySummary summary() {
        // Default no-op impl for 003 stub stage — 006 子类覆盖实现.
        return new MemorySummary(
            "unknown", 0, 0, null);
    }
}