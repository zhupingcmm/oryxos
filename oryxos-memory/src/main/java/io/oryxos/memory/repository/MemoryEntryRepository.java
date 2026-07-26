package io.oryxos.memory.repository;

import io.oryxos.memory.MemoryScope;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * agent_memories 表 JPA Repository（006-memory-layer + T007）。
 *
 * <p>索引策略（V4__add_agent_memories.sql）：
 * <ul>
 *   <li>idx_agent_memories_scope_created (scope, created_at DESC) —— 主查询</li>
 *   <li>idx_agent_memories_tags (tags) —— 标签子串</li>
 * </ul>
 *
 * <p>方法语义（data-model.md §3.4 + contracts/sqlite-backend.md §1）：
 * <ol>
 *   <li>{@link #findByScopeAndContentLike} —— recallByKeyword 单 scope（spec FR-006）</li>
 *   <li>{@link #findByScopeOrderByCreatedAtDesc} —— recallByScope（C-LT-04）</li>
 *   <li>{@link #findByTag} —— 按 tag 子串（C-SQ-06）</li>
 *   <li>{@link #trimArchive} —— archive lazy trim（C-SQ-02）</li>
 *   <li>{@link #countByScope} —— trim 触发判断</li>
 *   <li>{@link #deleteByScope} —— clear(archive)（C-LT-05 core 拒）</li>
 * </ol>
 *
 * <p>所有查询 MUST 参数化（[spec 边界情况 6](../specs/006-memory-layer/spec.md) 防 SQL 注入）；
 * 用 {@code @Query} + {@code @Param} 绑定，**不**字符串拼接。
 */
@Repository
public interface MemoryEntryRepository extends JpaRepository<MemoryEntryEntity, String> {

    /**
     * recallByKeyword：单 scope 内按 content 子串匹配 + created_at DESC。
     * 用 Pageable 限制 topK。
     */
    @Query("""
        SELECT e FROM MemoryEntryEntity e
        WHERE e.scope = :scope AND LOWER(e.content) LIKE LOWER(CONCAT('%', :query, '%'))
        ORDER BY e.createdAtMillis DESC
        """)
    List<MemoryEntryEntity> findByScopeAndContentLike(
        @Param("scope") MemoryScope scope,
        @Param("query") String query,
        Pageable pageable);

    /**
     * recallByScope：单 scope 内按 created_at DESC 取 topK（C-LT-04 走 idx_agent_memories_scope_created）。
     */
    List<MemoryEntryEntity> findByScopeOrderByCreatedAtMillisDesc(
        MemoryScope scope, Pageable pageable);

    /**
     * 按 tag 子串（SQLite JSON-as-TEXT 子串匹配；research R-02）。
     * 简化：不做 JSON 解析，靠 LIKE 匹配整个 tags JSON 字符串。
     */
    @Query("""
        SELECT e FROM MemoryEntryEntity e
        WHERE e.tagsJson LIKE CONCAT('%', :tag, '%')
        ORDER BY e.createdAtMillis DESC
        """)
    List<MemoryEntryEntity> findByTag(@Param("tag") String tag, Pageable pageable);

    /**
     * archive lazy trim（research R-06）：保留最新 maxEntries 条；其余按 created_at ASC 删除。
     * 在同一 @Transactional 内（C-SQ-08 数据一致性）。
     */
    @Modifying
    @Query(value = """
        DELETE FROM agent_memories
        WHERE scope = 'archive'
          AND id NOT IN (
            SELECT id FROM agent_memories
            WHERE scope = 'archive'
            ORDER BY created_at DESC
            LIMIT :maxEntries
          )
        """, nativeQuery = true)
    int trimArchive(@Param("maxEntries") int maxEntries);

    /**
     * 统计指定 scope 的总条数（trim 触发判断 + 审计用）。
     */
    long countByScope(MemoryScope scope);

    /**
     * 删除指定 scope 的全部条目（C-LT-05 core 调用抛 IllegalStateException）。
     * 用派生方法而非 @Query —— 走 idx_agent_memories_scope_created 索引。
     */
    long deleteByScope(MemoryScope scope);
}