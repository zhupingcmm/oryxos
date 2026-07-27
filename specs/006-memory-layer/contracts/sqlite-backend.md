# 契约：SqliteMemoryStore（结构化查询后端）

**目的**：定义 `SqliteMemoryStore` 实现契约（[spec.md FR-014](../spec.md)），落 `agent_memories` 表
**归属模块**：`oryxos-memory`
**位置**：`oryxos-memory/src/main/java/io/oryxos/memory/backend/SqliteMemoryStore.java`
**关联契约**：[long-term-store.md §C-LT](./long-term-store.md) | [data-model.md §3](../data-model.md) | [migration-scripts.md](./migration-scripts.md)

---

## 1. 类签名

```java
package io.oryxos.memory.backend;

import io.oryxos.memory.*;
import io.oryxos.memory.repository.MemoryEntryRepository;
import io.oryxos.memory.repository.MemoryEntryEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/**
 * 结构化长期层后端（spec FR-014）。
 *
 * 存储介质：与宪法 §VI 同库的 agent_memories 表（data-model §3）
 * 索引：idx_agent_memories_scope_created (scope, created_at DESC) + idx_agent_memories_tags
 *
 * 特性：
 * - core 永不被 trim（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ②）
 * - archive 在 save 时 lazy trim（research R-06）：count > maxEntries 则按 created_at ASC 裁剪
 * - tags 用 JSON-as-TEXT 存储（research R-02）
 */
@Component("sqliteMemoryStore")
public class SqliteMemoryStore implements LongTermMemoryStore {

    private final MemoryEntryRepository repository;
    private final ObjectMapper objectMapper;
    private final int archiveMaxEntries;

    public SqliteMemoryStore(
        MemoryEntryRepository repository,
        ObjectMapper objectMapper,
        @Value("${oryxos.memory.archive.max-entries:1000}") int archiveMaxEntries
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.archiveMaxEntries = archiveMaxEntries;
    }

    @Override
    @Transactional
    public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        String entryId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        String tagsJson = serializeTags(tags);

        MemoryEntryEntity entity = new MemoryEntryEntity(
            entryId, scope, content, tagsJson, scope.name(), createdAt.toEpochMilli()
        );
        repository.save(entity);

        // archive lazy trim（research R-06）；core MUST NOT trim（C-LT-02）
        if (scope == MemoryScope.archive) {
            long count = repository.countByScope(MemoryScope.archive);
            if (count > archiveMaxEntries) {
                int toDelete = (int) (count - archiveMaxEntries);
                repository.trimArchive(archiveMaxEntries);
            }
        }

        return new MemoryEntry(entryId, scope, content, tags, createdAt, scope.name());
    }

    @Override
    public List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter) {
        if (query == null || query.isBlank()) return List.of();
        // 派生 query：scopeFilter == null → 同时查 core + archive
        List<MemoryEntryEntity> entities = new ArrayList<>();
        if (scopeFilter == null) {
            entities.addAll(repository.findByScopeAndContentLike(
                MemoryScope.core, query, PageRequest.of(0, topK)));
            entities.addAll(repository.findByScopeAndContentLike(
                MemoryScope.archive, query, PageRequest.of(0, topK)));
        } else {
            entities.addAll(repository.findByScopeAndContentLike(
                scopeFilter, query, PageRequest.of(0, topK)));
        }
        return entities.stream().map(MemoryEntryEntity::toMemoryEntry).toList();
    }

    @Override
    public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
        return repository.findByScopeOrderByCreatedAtDesc(scope, PageRequest.of(0, topK))
            .stream().map(MemoryEntryEntity::toMemoryEntry).toList();
    }

    @Override
    public boolean delete(String entryId) {
        if (!repository.existsById(entryId)) return false;
        repository.deleteById(entryId);
        return true;
    }

    @Override
    @Transactional
    public void clear(MemoryScope scope) {
        if (scope == MemoryScope.core) {
            throw new IllegalStateException("core scope cannot be cleared");
        }
        repository.deleteByScope(scope);
    }

    @Override
    public boolean isHealthy() {
        try {
            repository.count();   // 触发一次 query，验证数据库连接
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String serializeTags(List<String> tags) {
        try {
            return tags == null || tags.isEmpty() ? "[]" : objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            throw new MemoryException("failed to serialize tags", e);
        }
    }
}
```

---

## 2. 契约条款（Sqlite 后端专属）

| 编号 | 条款 | 验证手段 |
|------|------|---------|
| C-SQ-01 | **DDL 手动管理**：表 + 索引必须由 V4 迁移脚本管理（宪法 "Additional Constraints" 第 3 条） | 测试启动期 hibernate.ddl-auto=none + 应用已跑过 V4 |
| C-SQ-02 | **archive lazy trim**：`save(archive)` 写入后若 count > maxEntries 则 trim 最旧多余条目（research R-06） | 测试：save(archive) 1500 条 + maxEntries=1000 → count = 1000 |
| C-SQ-03 | **core 不 trim**：`save(core)` MUST NOT 触发 trim（C-LT-02） | 测试：save(core) 1500 条 + maxEntries=1000 → count = 1500 |
| C-SQ-04 | **tags JSON-as-TEXT**：tags 列表 MUST 序列化为 JSON 数组字符串（research R-02） | 测试：save(tags=[t1,t2]) → 数据库 `tags` 列 = `["t1","t2"]` |
| C-SQ-05 | **LIKE 子串匹配**：recallByKeyword MUST 用 SQL `LIKE '%query%'`（大小写不敏感） | 测试命中 `Bug` keyword 匹配 `bug+enhancement` |
| C-SQ-06 | **createdAt 索引排序**：recallByKeyword/recallByScope MUST 用 `idx_agent_memories_scope_created` 索引按 created_at DESC 排序 | 性能测试 P95 ≤ 200ms（NFR-001） |
| C-SQ-07 | **scope CHECK 约束**：scope 列 MUST 是 `'core' \| 'archive'`（违反则数据库拒收） | 测试：insert scope='CACHE' → SQL exception |
| C-SQ-08 | **事务边界**：`save` + `trimArchive` MUST 在同一 `@Transactional` 中（C-SQ-02 + 数据一致性） | 测试：save 触发 trim 中断 → 数据库无脏数据 |
| C-SQ-09 | **SQLite busy 重试**：启动期遇到 `SQLITE_BUSY` MUST 重试 3 次 × 200ms（spec 边界情况 3） | 测试：并发启动 2 个 OryxOS 实例 → 一个 3 次重试后报错 |
| C-SQ-10 | **参数化查询**：recallByKeyword MUST 用 `@Query` 参数绑定，**不**拼接 SQL（spec 边界情况 6 防 SQL 注入） | 测试：recallByKeyword("'; DROP TABLE agent_memories; --") → 不抛 SQL exception |

---

## 3. 性能特征

| 操作 | 量级 | P95 wall-time |
|------|------|---------------|
| save | N=1 条 | ≤ 10ms（含 trim 触发时） |
| recallByKeyword | N=1000 条 core | ≤ 50ms（索引命中） |
| recallByScope | N=1000 条 archive | ≤ 30ms |
| delete | N=1 条 | ≤ 5ms |
| clear(archive) | N=1000 条 | ≤ 100ms |

> **索引策略**：`idx_agent_memories_scope_created (scope, created_at DESC)` 是核心索引；同时覆盖 `WHERE scope = ? AND content LIKE ? ORDER BY created_at DESC` 查询模式。`idx_agent_memories_tags` 辅助 LIKE 子串匹配。

---

## 4. 测试用例

| TestID | 场景 | 断言 |
|--------|------|------|
| SQ-IT-01 | save(core) 1 条 → 数据库含 1 行 | row count = 1 |
| SQ-IT-02 | save(archive) 1500 条 + maxEntries=1000 → row count = 1000（C-SQ-02 lazy trim） | count = 1000 |
| SQ-IT-03 | save(core) 1500 条 + maxEntries=1000 → row count = 1500（C-SQ-03 core 不 trim） | count = 1500 |
| SQ-IT-04 | save(tags=[t1,t2]) → 数据库 tags 列 = `["t1","t2"]`（C-SQ-04） | 列值匹配 |
| SQ-IT-05 | recallByKeyword("Bug") 命中 `bug+enhancement`（C-SQ-05） | 命中 1 条 |
| SQ-IT-06 | recallByKeyword("'; DROP TABLE agent_memories; --") 不抛 SQL exception（C-SQ-10） | 返回空集合 |
| SQ-IT-07 | 启动期 hibernate.ddl-auto=none + V4 已跑 → 启动成功（C-SQ-01） | 启动不报错 |
| SQ-IT-08 | clear(core) → IllegalStateException（C-LT-05） | 抛异常 |
| SQ-IT-09 | save 后 recallByKeyword 命中（C-LT-01 read-after-write） | 命中 |
| SQ-IT-10 | 并发启动 2 实例 → 一实例 SQLITE_BUSY 3 次后报错（C-SQ-09） | 错误日志含 "SQLITE_BUSY" |
| SQ-IT-11 | 性能：recallByKeyword P95 ≤ 200ms（NFR-001） | 100 次 recall P95 < 200ms |

---

## 5. 与既有契约的关系

| 既有契约 | 关系 |
|----------|------|
| [CLAUDE.md §9.6](../CLAUDE.md) | 4 条契约码化为 C-SQ-03 + C-LT-01/02/03/05 |
| [spec.md FR-014](../spec.md) | agent_memories 表 DDL + Repository 模式 |
| [data-model.md §3](../data-model.md) | V4 DDL + JPA Entity + Repository 完整定义 |
| [research.md R-02/R-06](./research.md) | tags JSON-as-TEXT + archive lazy trim |
| [migration-scripts.md](./migration-scripts.md) | Markdown ↔ SQLite 双向迁移 |
| [005-tool-system V3 DDL](../005-tool-system/spec.md) | 沿用既有 `tool_invocations` 表；agent_memories **不**复用到该表 |

---

## 6. 备注

- **`agent_memories` 表与五张核心表并列**：宪法 §VI 列出五张 day-one 表；本 spec 新增的 `agent_memories` **不是**第六张 day-one 表，而是"长期层 SqliteMemoryStore 的存储介质"，与宪法 §VI 不冲突。
- **DDL 不复用 V3 tool_invocations**：避免审计表与业务表语义混用；agent_memories 是**长期记忆**而非**审计**。