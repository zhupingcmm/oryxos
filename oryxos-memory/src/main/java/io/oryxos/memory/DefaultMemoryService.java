package io.oryxos.memory;

import io.oryxos.memory.backend.LongTermMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link MemoryService} 默认实现（006-memory-layer spec FR-001 / FR-006—FR-010）。
 *
 * <p>职责：
 * <ul>
 *   <li>为 ReAct 循环 + Memory Tool + 业务方提供统一的 Memory 门面</li>
 *   <li>委派 {@link #save} / {@link #recallByKeyword} / {@link #recallByScope} / {@link #delete} / {@link #clear}
 *       到选定的 {@link LongTermMemoryStore} 后端（Markdown / SQLite / Mem0）</li>
 *   <li>码化 4 条核心契约（CLAUDE.md §9.6）：
 *     <ol>
 *       <li>**不缓存** —— recallByKeyword 直接委派（C-MS-01）</li>
 *       <li>**core 永不被截断** —— save(core) 不触发 trim（C-MS-02 / FR-009）</li>
 *       <li>**scope 必填** —— save(scope=null) 抛 IllegalArgumentException（C-MS-03 / FR-008）</li>
 *       <li>**关键词检索** —— 不引入向量检索（C-MS-06）</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p>与会话层（{@link SessionManager}）的关系：
 * <ul>
 *   <li>本类**不**持有 {@link SessionManager} 引用 —— 会话层与长期层边界分明（spec FR-002）</li>
 *   <li>{@link SessionManager} 也不调用本类 —— Agent 必须经 {@code save_memory} / {@code recall_memory}
 *       Tool 显式触发持久化</li>
 * </ul>
 *
 * <p>详见 [contracts/memory-service.md §1+§3](../../../../../specs/006-memory-layer/contracts/memory-service.md)。
 */
@Component
public class DefaultMemoryService implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryService.class);

    private final LongTermMemoryStore longTermStore;

    /**
     * 构造器注入长期层后端。
     *
     * <p>{@code SessionManager} 不通过构造器注入 —— 本门面与会话层边界分明（spec FR-002）：
     * <ul>
     *   <li>本门面负责"已持久化的长期记忆" —— 由 Agent 显式经 Tool 触发</li>
     *   <li>{@link SessionManager} 负责"当前对话回合的消息" —— ReAct 循环自动累积</li>
     * </ul>
     */
    public DefaultMemoryService(LongTermMemoryStore longTermStore) {
        if (longTermStore == null) {
            throw new IllegalArgumentException(
                "longTermStore must not be null — Phase 5 MemoryBackendSelector selects markdown|sqlite|mem0");
        }
        this.longTermStore = longTermStore;
        log.info("DefaultMemoryService initialized with longTermStore={}",
            longTermStore.getClass().getSimpleName());
    }

    @Override
    public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
        // 契约 ③（C-MS-03 / FR-008）—— scope 必填；不提供默认值
        if (scope == null) {
            throw new IllegalArgumentException(
                "scope must not be null — CLAUDE.md §9.6 契约 ③ 禁止隐式默认");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        // 契约 ②（C-MS-02 / FR-009）—— core 永不被截断；save(core) 委派即可，后端 MUST NOT trim
        // Markdown / Sqlite / Mem0 三后端 MUST 在 save(core) 路径不触发任何 trim / delete
        return longTermStore.save(scope, content, tags);
    }

    @Override
    public List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter) {
        // 契约 ④（C-MS-06）—— 关键词检索不做复杂化；不引入向量
        // 契约 ①（C-MS-01 / FR-007）—— 不缓存；直接委派后端，命中按 createdAt DESC
        // 空 query → 空集合（不抛异常，spec C-MS-04）
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int normalizedTopK = topK <= 0 ? 1 : (topK > 100 ? 100 : topK); // C-MS-07
        return longTermStore.recallByKeyword(query, normalizedTopK, scopeFilter);
    }

    @Override
    public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        int normalizedTopK = topK <= 0 ? 1 : (topK > 100 ? 100 : topK);
        return longTermStore.recallByScope(scope, normalizedTopK);
    }

    @Override
    public boolean delete(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return false;
        }
        return longTermStore.delete(entryId);
    }

    @Override
    public void clear(MemoryScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        // 契约 ②（C-LT-05 / CLAUDE.md §9.6）—— clear(core) MUST 拒绝；
        // 不依赖后端正确性，本门面统一守卫
        if (scope == MemoryScope.CORE) {
            throw new IllegalStateException(
                "clear(core) is forbidden: core scope is never truncated (CLAUDE.md §9.6 契约 ②)");
        }
        longTermStore.clear(scope);
    }
}