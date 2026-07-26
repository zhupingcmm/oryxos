package io.oryxos.memory.repository;

import io.oryxos.memory.MemoryScope;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * memory_index 表 JPA Repository（006-memory-layer US-3 / T033）。
 *
 * <p>Mem0 后端专用 —— Mem0MemoryStore 用本地索引实现不可达降级 + 启动期回填。
 *
 * <p>方法语义（[data-model.md §6](../specs/006-memory-layer/data-model.md)）：
 * <ol>
 *   <li>{@link #findByLocalId} —— 单条查</li>
 *   <li>{@link #findByMem0Id} —— Mem0 服务回包后用 mem0_id 找本地条目</li>
 *   <li>{@link #findByScopeAndPendingFalseOrderByCreatedAtMillisDesc} —— 已同步条目按 scope 倒序（recall）</li>
 *   <li>{@link #findByContentLikeAndPendingFalse} —— 已同步条目按 content 子串（recallByKeyword 降级）</li>
 *   <li>{@link #findByPendingTrue} —— 待同步条目（启动期回填）</li>
 *   <li>{@link #deleteByScope} —— clear(scope)</li>
 * </ol>
 */
@Repository
public interface MemoryEntryIndexRepository extends JpaRepository<MemoryEntryIndexEntity, String> {

    Optional<MemoryEntryIndexEntity> findByLocalId(String localId);

    Optional<MemoryEntryIndexEntity> findByMem0Id(String mem0Id);

    /**
     * 已同步条目（pending=false）按 scope 倒序 + topK；recallByScope 主路径。
     */
    List<MemoryEntryIndexEntity> findByScopeAndPendingFalseOrderByCreatedAtMillisDesc(
        MemoryScope scope, Pageable pageable);

    /**
     * 已同步条目按 content 子串匹配（recallByKeyword 降级路径；大小写不敏感）。
     * 用 LOWER() 兼容大小写（H2/SQLite 都支持）。
     */
    @Query("""
        SELECT e FROM MemoryEntryIndexEntity e
        WHERE e.pending = false
          AND LOWER(e.content) LIKE LOWER(CONCAT('%', :query, '%'))
        ORDER BY e.createdAtMillis DESC
        """)
    List<MemoryEntryIndexEntity> findByContentLikeAndPendingFalse(
        @Param("query") String query, Pageable pageable);

    /**
     * 待同步条目（启动期 Mem0 健康恢复后回填）。
     */
    List<MemoryEntryIndexEntity> findByPendingTrue(Pageable pageable);

    /**
     * 删 scope 全部条目（clear(scope)）。
     */
    long deleteByScope(MemoryScope scope);
}