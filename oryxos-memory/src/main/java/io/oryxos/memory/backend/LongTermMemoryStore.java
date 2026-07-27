package io.oryxos.memory.backend;

import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;

import java.util.List;

/**
 * 长期层接口（006-memory-layer）。
 *
 * <p>本 spec 在既有 {@link io.oryxos.memory.MemoryService}（门面层，已于
 * 003-cli-commands 阶段落地，3 字段 MemoryEntry + MarkdownMemoryStore 唯一实现）
 * 之上，**新增**本接口 —— 为 SqliteMemoryStore / Mem0MemoryStore 提供统一抽象。
 *
 * <p>三条核心契约（[CLAUDE.md §9.6](../CLAUDE.md) + [spec.md FR-007—FR-010](../specs/006-memory-layer/spec.md)）：
 * <ol>
 *   <li>**不缓存** —— 每次 recallByKeyword MUST 直接读底层 IO（FR-007）</li>
 *   <li>**core 永不被截断** —— save(core) / trim / delete MUST NOT 触碰 core 区（FR-009）</li>
 *   <li>**scope 必填** —— save(scope=null) MUST 抛 IllegalArgumentException（FR-008）</li>
 * </ol>
 *
 * <p>三个 builtin 实现：
 * <ul>
 *   <li>MarkdownMemoryStore —— 既有，已于 003 阶段落地；Phase 4 适配本接口</li>
 *   <li>SqliteMemoryStore —— Phase 5 新增，落 agent_memories 表</li>
 *   <li>Mem0MemoryStore —— Phase 5 新增，HTTP 客户端</li>
 * </ul>
 *
 * <p>详见：[contracts/long-term-store.md](../specs/006-memory-layer/contracts/long-term-store.md)。
 */
public interface LongTermMemoryStore {

    /**
     * 写入一条记忆。
     *
     * @param scope   必填；core 永不被截断；archive 可被 lazy trim
     * @param content 必非空
     * @param tags    可选；用于 recall 过滤
     * @return 实际写入的 MemoryEntry（含自动生成的 id）
     * @throws IllegalArgumentException scope == null 或 content 空白
     * @throws MemoryStoreBackendException IO / DB / HTTP 不可达等底层错误（统一封装为 RuntimeException）
     */
    MemoryEntry save(MemoryScope scope, String content, List<String> tags);

    /**
     * 按关键词检索（spec FR-006：按 content 子串匹配 + created_at DESC）。
     *
     * @param query       必非空；空 / null MUST 返回空集合（不抛异常）
     * @param topK        返回最大条数；{@code <= 0} 视作 1
     * @param scopeFilter 可选；null = 跨 scope（core + archive）；否则限定到该 scope
     * @return 按 created_at DESC 排序的命中列表（不缓存 —— 契约 ①）
     */
    List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter);

    /**
     * 按 scope 取最近 topK 条（C-LT-04：走 idx_agent_memories_scope_created 索引）。
     *
     * @param scope 必填
     * @param topK  返回最大条数
     */
    List<MemoryEntry> recallByScope(MemoryScope scope, int topK);

    /**
     * 按 id 删除单条（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ③ —— Agent 显式操作）。
     *
     * @return true = 删了 1 行；false = id 不存在
     */
    boolean delete(String entryId);

    /**
     * 清空指定 scope 的全部条目（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ② 守卫）。
     *
     * @throws IllegalStateException scope == core（C-LT-05 硬约束 —— 违反则后端 MUST 拒绝启动）
     */
    void clear(MemoryScope scope);

    /**
     * 健康检查（spec FR-009 配套） —— 用于启动期 fail-fast 验证 +
     * 运维监控（扩展阶段）。
     *
     * <p>Markdown / Sqlite：检查文件 / DB 可读；Mem0：检查 /health 端点 2s 超时。
     */
    boolean isHealthy();
}