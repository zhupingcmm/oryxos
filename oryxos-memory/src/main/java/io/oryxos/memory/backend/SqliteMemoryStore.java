package io.oryxos.memory.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryException;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.repository.MemoryEntryEntity;
import io.oryxos.memory.repository.MemoryEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 结构化长期层后端（006-memory-layer US-3）。
 *
 * <p>存储介质：与宪法 §VI 同库的 {@code agent_memories} 表（V4__add_agent_memories.sql）。
 * 索引：
 * <ul>
 *   <li>{@code idx_agent_memories_scope_created (scope, created_at DESC)} —— 主查询索引（C-SQ-06）</li>
 *   <li>{@code idx_agent_memories_tags} —— tags 子串扫描（C-SQ-06 辅助）</li>
 * </ul>
 *
 * <p>契约条款（[contracts/sqlite-backend.md §2](../../../../../specs/006-memory-layer/contracts/sqlite-backend.md)）：
 * <ul>
 *   <li>C-SQ-02 archive lazy trim —— {@code save(archive)} 后 count &gt; maxEntries 则 trim 最旧多余</li>
 *   <li>C-SQ-03 core MUST NOT trim —— {@code save(core)} 永不触发 trim（CLAUDE.md §9.6 契约 ②）</li>
 *   <li>C-SQ-04 tags JSON-as-TEXT —— tags 序列化为 JSON 数组字符串（research R-02）</li>
 *   <li>C-SQ-05 LIKE 子串匹配 —— recallByKeyword 用 JPA 参数化 LIKE，**不**字符串拼接（C-SQ-10）</li>
 *   <li>C-SQ-06 created_at DESC 索引排序</li>
 *   <li>C-SQ-07 scope CHECK 约束 —— DB 层 'core'|'archive' 拒收其他值</li>
 *   <li>C-SQ-08 事务边界 —— save + trimArchive 在同一 {@code @Transactional}（C-SQ-08）</li>
 *   <li>C-SQ-09 busy 重试 —— 启动期遇到 SQLITE_BUSY 抛 MemoryException，不静默吞</li>
 *   <li>C-SQ-10 参数化查询 —— JPA 派生方法 + {@code @Param} 绑定</li>
 * </ul>
 *
 * <p>{@link #clear(MemoryScope)} 对 CORE 抛 {@link IllegalStateException}（C-LT-05 硬约束，
 * 门面层 {@code DefaultMemoryService} 也再校验一次）。
 */
@Component("sqliteMemoryStore")
public class SqliteMemoryStore implements LongTermMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteMemoryStore.class);

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final MemoryEntryRepository repository;
    private final ObjectMapper objectMapper;
    private final int archiveMaxEntries;

    @PersistenceContext
    private EntityManager entityManager;

    public SqliteMemoryStore(
        MemoryEntryRepository repository,
        ObjectMapper objectMapper,
        @Value("${oryxos.memory.archive.max-entries:1000}") int archiveMaxEntries
    ) {
        if (repository == null) {
            throw new IllegalArgumentException("repository must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        if (archiveMaxEntries < 1) {
            archiveMaxEntries = 1000;
        }
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.archiveMaxEntries = archiveMaxEntries;
    }

    /**
     * 包级别可见的注入工厂 —— 让测试能用同一个 ObjectMapper 实例化（避免 @SpringBootTest
     * 上下文对象与手动 new 不一致）。生产路径走 Spring @Component 自动注入。
     * <p>{@link EntityManager} 走 {@code @PersistenceContext} 由 Spring 注入。
     */
    static SqliteMemoryStore forTest(
        MemoryEntryRepository repository, ObjectMapper objectMapper, int archiveMaxEntries
    ) {
        return new SqliteMemoryStore(repository, objectMapper, archiveMaxEntries);
    }

    @Override
    @Transactional
    public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        List<String> safeTags = tags == null ? List.of() : List.copyOf(tags);

        String entryId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        String tagsJson = serializeTags(safeTags);

        MemoryEntryEntity entity = new MemoryEntryEntity(
            entryId, scope, content, tagsJson, scope.name(), createdAt.toEpochMilli());
        repository.save(entity);
        // 显式 flush：保证 INSERT 在 countByScope 之前落到 DB，
        // 避免 Hibernate AUTO flush 在嵌套事务边界下不触发导致 trim 不发生。
        repository.flush();

        // C-SQ-02 archive lazy trim：core MUST NOT trim（C-SQ-03 / CLAUDE.md §9.6 契约 ②）
        if (scope == MemoryScope.ARCHIVE) {
            long count = repository.countByScope(MemoryScope.ARCHIVE);
            if (count > archiveMaxEntries) {
                int toDelete = (int) (count - archiveMaxEntries);
                // 用 JPA EntityManager 显式 remove 避免 SQL 跨方言兼容问题；
                // findOldestArchiveIds 走 LIMIT 子查询（read-only，跨方言安全）
                List<String> oldestIds = repository.findOldestArchiveIds(toDelete);
                if (!oldestIds.isEmpty()) {
                    // 走 EntityManager.remove 保证同事务内原子性（C-SQ-08）
                    for (String id : oldestIds) {
                        MemoryEntryEntity ref = entityManager.getReference(MemoryEntryEntity.class, id);
                        entityManager.remove(ref);
                    }
                    entityManager.flush();
                    log.debug("SqliteMemoryStore.archive lazy trim: count={} -> deleted {} oldest rows (max={})",
                        count, oldestIds.size(), archiveMaxEntries);
                }
            }
        }

        return new MemoryEntry(entryId, scope, content, safeTags, createdAt, scope.name().toLowerCase());
    }

    @Override
    public List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = normalizeTopK(topK);
        List<MemoryEntryEntity> entities = new ArrayList<>();
        // C-SQ-05 LIKE 子串匹配（参数化，防 SQL 注入 C-SQ-10）
        if (scopeFilter == null) {
            // 跨 scope：分别查 core + archive 然后合并（每个 scope 限制 topK → 总数 ≤ 2*topK）
            entities.addAll(repository.findByScopeAndContentLike(
                MemoryScope.CORE, query, PageRequest.of(0, limit)));
            entities.addAll(repository.findByScopeAndContentLike(
                MemoryScope.ARCHIVE, query, PageRequest.of(0, limit)));
            // 合并后按 createdAt DESC 排序 + 限 topK
            entities.sort((a, b) -> Long.compare(b.getCreatedAtMillis(), a.getCreatedAtMillis()));
            if (entities.size() > limit) {
                entities = new ArrayList<>(entities.subList(0, limit));
            }
        } else {
            entities.addAll(repository.findByScopeAndContentLike(
                scopeFilter, query, PageRequest.of(0, limit)));
        }
        return entities.stream().map(e -> e.toMemoryEntry(deserializeTags(e.getTagsJson()))).toList();
    }

    @Override
    public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        int limit = normalizeTopK(topK);
        List<MemoryEntryEntity> entities = repository.findByScopeOrderByCreatedAtMillisDesc(
            scope, PageRequest.of(0, limit));
        return entities.stream().map(e -> e.toMemoryEntry(deserializeTags(e.getTagsJson()))).toList();
    }

    @Override
    public boolean delete(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return false;
        }
        if (!repository.existsById(entryId)) {
            return false;
        }
        repository.deleteById(entryId);
        return true;
    }

    @Override
    @Transactional
    public void clear(MemoryScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        // C-LT-05 硬约束：core 永不被 clear（CLAUDE.md §9.6 契约 ②）
        if (scope == MemoryScope.CORE) {
            throw new IllegalStateException(
                "clear(core) is forbidden: core scope is never truncated (CLAUDE.md §9.6 契约 ②)");
        }
        long deleted = repository.deleteByScope(scope);
        log.debug("SqliteMemoryStore.clear(scope={}) deleted {} rows", scope, deleted);
    }

    @Override
    public boolean isHealthy() {
        try {
            // 触发一次 query 验证数据库连接（V4 DDL + JPA Entity 装配成功）
            repository.count();
            return true;
        } catch (RuntimeException ex) {
            log.warn("SqliteMemoryStore.isHealthy() failed: {}", ex.getMessage());
            return false;
        }
    }

    // ===== 内部工具（C-SQ-04 tags JSON-as-TEXT） =====

    /** 序列化 tags 列表为 JSON 数组字符串（research R-02）。 */
    String serializeTags(List<String> tags) {
        try {
            return tags == null || tags.isEmpty() ? "[]" : objectMapper.writeValueAsString(tags);
        } catch (Exception ex) {
            throw new MemoryException("failed to serialize tags: " + ex.getMessage(), ex);
        }
    }

    /** 反序列化 JSON 数组字符串为 tags 列表；解析失败返回空列表（不让 recall 崩）。 */
    List<String> deserializeTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank() || "[]".equals(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, STRING_LIST_TYPE);
        } catch (Exception ex) {
            log.warn("SqliteMemoryStore.deserializeTags failed (json='{}'): {}",
                tagsJson, ex.getMessage());
            return List.of();
        }
    }

    private static int normalizeTopK(int topK) {
        if (topK <= 0) return 1;
        if (topK > 100) return 100; // C-MS-07 上限
        return topK;
    }
}