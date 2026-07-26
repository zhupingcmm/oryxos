# 数据模型：Memory 层（让 Agent 记得住事的可插拔记忆层）

**目的**：把 [spec.md §关键实体](./spec.md) 与 [research.md](./research.md) 决策点收敛成可执行的 JPA Entity + SQL DDL + Java record 定义
**分支**：`006-memory-layer` | **日期**：2026-07-26 | **Spec**：[spec.md](./spec.md) | **Plan**：[plan.md](./plan.md) | **Research**：[research.md](./research.md)

---

## 1. 实体清单（5 个核心实体 + 1 个 V4 DDL 表）

| # | 实体 | 形态 | 归属模块 | 文件路径 |
|---|------|------|----------|----------|
| 1 | `MemoryEntry` | Java record | `oryxos-memory` | `oryxos-memory/src/main/java/io/oryxos/memory/MemoryEntry.java` |
| 2 | `MemoryScope` | Java enum | `oryxos-memory` | `oryxos-memory/src/main/java/io/oryxos/memory/MemoryScope.java` |
| 3 | `MemoryException` | RuntimeException | `oryxos-memory` | `oryxos-memory/src/main/java/io/oryxos/memory/MemoryException.java` |
| 4 | `MemoryProperties` | `@ConfigurationProperties` record | `oryxos-memory` | `oryxos-memory/src/main/java/io/oryxos/memory/MemoryProperties.java` |
| 5 | `MemoryBackendSelector` | `@Component` | `oryxos-boot` | `oryxos-boot/src/main/java/io/oryxos/boot/config/MemoryBackendSelector.java` |
| 6 | `agent_memories` 表 | SQLite DDL | `oryxos-storage` | `oryxos-storage/src/main/resources/db/migration/V4__add_agent_memories.sql` |

> **关于 `MemoryEntry` / `MemoryScope` / `MemoryException` 的"已落地"标注**：这些实体在 005-tool-system 之前的早期迭代**已落地**，本 spec **不**重写定义，只在 §2—§3 给出**契约级**定义供 `tasks.md` 阶段验证；任何字段不匹配契约的修改放 006 tasks 阶段处理。

---

## 2. Java record / enum 定义（契约级）

### 2.1 `MemoryEntry` record

```java
package io.oryxos.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 单条长期记忆记录。契约级定义见 spec.md FR / 关键实体。
 * - id: UUID v4（MemoryService.save 入口生成，三后端统一主键）
 * - scope: 显式枚举值；不允许 null（spec FR-008）
 * - content: 主内容；Markdown/SQLite/Mem0 后端都按字面字符串保存
 * - tags: 可选标签列表；Markdown 后端 informational（不参与查询）；SQLite 后端 JSON-as-TEXT（research R-02）
 * - createdAt: Instant；时区无关（UTC）；按此字段排序
 * - source: "core" | "archive"，与 scope 同义（冗余字段，SQLite 后端方便索引）
 */
public record MemoryEntry(
    String id,              // UUID v4
    MemoryScope scope,      // 非 null
    String content,         // 非 null
    List<String> tags,      // nullable；空集合等价于 null
    Instant createdAt,      // Instant.now()
    String source           // "core" | "archive"（与 scope.name() 同值）
) {}
```

**约束**（spec FR / NFR 推导）：

- `id` 必须非 null 且满足 `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`
- `scope` 必须非 null（spec FR-008）
- `content` 必须非 null，可为空字符串（业务方传空 = 显式"无内容"，区别于 null）
- `tags` nullable；空集合 `List.of()` 等价于 null
- `createdAt` 必须非 null（默认 `Instant.now()`）
- `source` 与 `scope.name()` 同值（冗余字段，SQLite CHECK 约束冗余）

### 2.2 `MemoryScope` enum

```java
package io.oryxos.memory;

/**
 * 长期记忆的范围枚举。spec FR-008 硬约束：只接受 core / archive 两值。
 * - core: 永不被截断 / 自动清理 / 容量上限裁剪（CLAUDE.md §9.6 契约 ②）
 * - archive: 可被容量上限裁剪（research R-06）
 */
public enum MemoryScope {
    core,
    archive;

    /** 从字符串解析；非法字符串抛 IllegalArgumentException */
    public static MemoryScope fromString(String s) {
        if (s == null) throw new IllegalArgumentException("scope must not be null");
        return switch (s.toLowerCase()) {
            case "core" -> core;
            case "archive" -> archive;
            default -> throw new IllegalArgumentException("invalid memory scope: " + s);
        };
    }

    /** SQLite CHECK 约束校验器 */
    public static boolean isValid(String s) {
        return "core".equals(s) || "archive".equals(s);
    }
}
```

### 2.3 `MemoryException`

```java
package io.oryxos.memory;

/**
 * 底层 IO 异常 / 后端错误统一转 MemoryException（spec FR-013）。
 * - RuntimeException 子类 → Tool 层捕获转 ToolResult.error(...)；不进 LLM 上下文
 * - 携带 cause 以便日志输出（NFR-004 stack trace 进 .oryxos/logs/）
 */
public class MemoryException extends RuntimeException {
    public MemoryException(String message) { super(message); }
    public MemoryException(String message, Throwable cause) { super(message, cause); }
}
```

### 2.4 `MemoryProperties`（@ConfigurationProperties）

```java
package io.oryxos.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Memory 层的全局配置。绑定 application.yaml 的 oryxos.memory.* 字段。
 * - backend: "markdown"（默认）| "sqlite" | "mem0"
 * - archive.max-entries: 仅 sqlite 后端生效（research R-06）；默认 1000
 * - mem0.base-url: 仅 mem0 后端生效（research R-03）；默认 http://localhost:8000
 * - mem0.timeout-seconds: 默认 5
 * - markdown.path: 默认 .oryxos/memory/MEMORY.md
 */
@ConfigurationProperties(prefix = "oryxos.memory")
public record MemoryProperties(
    String backend,                  // 默认 "markdown"
    ArchiveConfig archive,
    Mem0Config mem0,
    MarkdownConfig markdown
) {
    public record ArchiveConfig(Integer maxEntries) {}            // 默认 1000
    public record Mem0Config(String baseUrl, Integer timeoutSeconds) {} // 默认 http://localhost:8000, 5s
    public record MarkdownConfig(String path) {}                  // 默认 .oryxos/memory/MEMORY.md
}
```

### 2.5 `MemoryBackendSelector`（@Component，DI 装配）

```java
package io.oryxos.boot.config;

import io.oryxos.memory.*;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import io.oryxos.memory.backend.SqliteMemoryStore;
import io.oryxos.memory.backend.Mem0MemoryStore;
import org.springframework.stereotype.Component;

/**
 * 按 Profile.memo.backend 选择 LongTermMemoryStore 实现（research R-08）。
 * 单一 Profile → 单 LongTermMemoryStore；切换 backend = 重启 + 重选 Bean。
 */
@Component
public class MemoryBackendSelector {

    private final MarkdownMemoryStore markdown;
    private final SqliteMemoryStore sqlite;
    private final Mem0MemoryStore mem0;

    public MemoryBackendSelector(
        MarkdownMemoryStore markdown,
        SqliteMemoryStore sqlite,
        Mem0MemoryStore mem0
    ) {
        this.markdown = markdown;
        this.sqlite = sqlite;
        this.mem0 = mem0;
    }

    public LongTermMemoryStore select(String backendName) {
        return switch (backendName == null ? "markdown" : backendName.toLowerCase()) {
            case "markdown" -> markdown;
            case "sqlite"   -> sqlite;
            case "mem0"     -> mem0;
            default -> throw new IllegalArgumentException("unknown memo backend: " + backendName);
        };
    }
}
```

---

## 3. SQLite 表 DDL（V4 migration）

### 3.1 文件路径

`oryxos-storage/src/main/resources/db/migration/V4__add_agent_memories.sql`

### 3.2 DDL 内容

```sql
-- V4: 新增 agent_memories 表（spec FR-014）
-- 长期层 SqliteMemoryStore 的存储介质；与宪法 §VI 五张表并列
-- SQLite DDL 演进路径：手动脚本（宪法 Additional Constraints 第 3 条）

CREATE TABLE IF NOT EXISTS agent_memories (
    id          TEXT PRIMARY KEY,                                -- UUID v4
    scope       TEXT NOT NULL                                    -- 'core' | 'archive'
                CHECK (scope IN ('core', 'archive')),
    content     TEXT NOT NULL,
    tags        TEXT NOT NULL DEFAULT '[]',                      -- JSON 数组（research R-02）
    source      TEXT NOT NULL                                    -- 'core' | 'archive'（与 scope 同值）
                CHECK (source IN ('core', 'archive')),
    created_at  INTEGER NOT NULL                                 -- epoch millis（SQLite 标准）
);

-- 索引 1：按 scope + created_at DESC 查询（spec FR-006 + 边界情况"recallByKeyword 按 created_at DESC"）
CREATE INDEX IF NOT EXISTS idx_agent_memories_scope_created
    ON agent_memories (scope, created_at DESC);

-- 索引 2：tags 子串匹配（research R-02）
CREATE INDEX IF NOT EXISTS idx_agent_memories_tags
    ON agent_memories (tags);

-- DOWN rollback（迁移脚本用；spec NFR-005 双向迁移支持）
-- DROP INDEX IF EXISTS idx_agent_memories_tags;
-- DROP INDEX IF EXISTS idx_agent_memories_scope_created;
-- DROP TABLE IF EXISTS agent_memories;
```

### 3.3 JPA Entity `MemoryEntryEntity`（位于 `oryxos-memory/src/main/java/io/oryxos/memory/repository/MemoryEntryEntity.java`）

```java
package io.oryxos.memory.repository;

import io.oryxos.memory.MemoryScope;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_memories")
public class MemoryEntryEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private MemoryScope scope;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "tags", nullable = false, columnDefinition = "TEXT DEFAULT '[]'")
    private String tags;        // JSON 数组字符串

    @Column(name = "source", nullable = false, length = 16)
    private String source;      // 'core' | 'archive'

    @Column(name = "created_at", nullable = false)
    private Long createdAt;     // epoch millis

    // 构造器 / getter / setter / toMemoryEntry() / fromMemoryEntry()
    // —— tasks.md 阶段实现
}
```

### 3.4 JPA Repository

```java
package io.oryxos.memory.repository;

import io.oryxos.memory.MemoryScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface MemoryEntryRepository extends JpaRepository<MemoryEntryEntity, String> {

    /** recallByKeyword：按 scope 过滤 + content 子串匹配 + created_at DESC */
    @Query("""
        SELECT m FROM MemoryEntryEntity m
        WHERE m.scope = :scope
          AND LOWER(m.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
        ORDER BY m.createdAt DESC
        """)
    List<MemoryEntryEntity> findByScopeAndContentLike(
        @Param("scope") MemoryScope scope,
        @Param("keyword") String keyword,
        org.springframework.data.domain.Pageable pageable);

    /** recallByScope：按 scope 过滤 + created_at DESC（无 keyword） */
    List<MemoryEntryEntity> findByScopeOrderByCreatedAtDesc(
        MemoryScope scope,
        org.springframework.data.domain.Pageable pageable);

    /** 标签子串匹配（research R-02）：LIKE '%"<tag>"%' */
    @Query(value = """
        SELECT * FROM agent_memories
        WHERE tags LIKE CONCAT('%\"', :tag, '\"%')
        ORDER BY created_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<MemoryEntryEntity> findByTag(
        @Param("tag") String tag,
        @Param("limit") int limit);

    /** archive 容量上限裁剪（research R-06） */
    @Modifying
    @Query(value = """
        DELETE FROM agent_memories
        WHERE scope = 'archive'
          AND id NOT IN (
            SELECT id FROM agent_memories
            WHERE scope = 'archive'
            ORDER BY created_at DESC
            LIMIT :keepCount
          )
        """, nativeQuery = true)
    int trimArchive(@Param("keepCount") int keepCount);
}
```

---

## 4. 状态机

Memory 层无复杂状态机；`MemoryEntry` 是不可变 record（research R-07）。唯一的状态迁移：

```
[NEW MemoryEntry] ──save()──> [persisted in long-term store]
                                     │
                                     ├── delete(entryId) ──> [removed]
                                     └── scope=archive + count > maxEntries ──> [trim oldest N]
```

`core` scope **不**参与 trim 迁移（research R-05 硬约束）。

---

## 5. 关系图

```
┌────────────────────────────────────────────────────────────────────┐
│  MemoryService（统一门面；spec FR-001）                             │
│  ├── save(scope, content, tags?)                                   │
│  ├── recallByKeyword(query, topK, scopeFilter?)                    │
│  ├── recallByScope(scope, topK)                                    │
│  ├── delete(entryId)                                               │
│  └── clear(scope)                                                  │
└────────────────────┬───────────────────────────────────────────────┘
                     │ 内部委派
        ┌────────────┴────────────┐
        ▼                         ▼
┌──────────────────┐    ┌──────────────────────────────────┐
│ SessionManager   │    │ LongTermMemoryStore（接口）       │
│ （会话层）        │    │   ├── MarkdownMemoryStore [P1]   │
│ 跟随 Session     │    │   ├── SqliteMemoryStore   [P2]   │
│ 不落长期层       │    │   └── Mem0MemoryStore     [P2]   │
└──────────────────┘    └──────────────────────────────────┘
                                  │
                                  ▼
                    ┌─────────────────────────────┐
                    │ agent_memories（SQLite 表）  │
                    │   id / scope / content /    │
                    │   tags / source / created_at│
                    └─────────────────────────────┘
                                  ▲
                                  │ Mem0 后端另存
                                  │
                    ┌─────────────────────────────┐
                    │ memory_index（SQLite 映射表）│
                    │   local_id → mem0_id        │
                    └─────────────────────────────┘
                                  ▲
                                  │ HTTP
                                  │
                    ┌─────────────────────────────┐
                    │ Mem0 自托管服务              │
                    │   POST /memories            │
                    │   POST /memories/search     │
                    └─────────────────────────────┘
```

---

## 6. 演进路径

- **V4 DDL（本次落地）**：`agent_memories` 表 + 2 索引；不依赖 `hibernate.ddl-auto=update`
- **扩展阶段可能演进**（spec §"不在范围内"，**不**在 006 范围）：
  - `memory_tags` 独立表 + 标签规范化（突破 LIKE 子串匹配的 N+1 问题）
  - `memory_embeddings` 表 + 向量索引（语义检索）
  - `memory_versions` 表 + 事实版本链（冲突检测）
  - `memory_index` 表（Mem0 后端）正式化（含写入重试队列 / 状态机）

---

## 备注

- **不引入新 Maven 模块**（宪法 §I）— 所有实体落在既有 `oryxos-memory` + `oryxos-storage` + `oryxos-boot`
- **不引入新第三方依赖**（宪法 §I）— JPA / Jackson / HttpClient 全部沿用既有栈
- **不依赖 `hibernate.ddl-auto=update`**（宪法 "Additional Constraints" 第 3 条）— 手动 V4 DDL 脚本
- **核心契约硬约束**：①不缓存 ②core 永不被截断 ③scope 显式 ④keyword-only 检索（[CLAUDE.md §9.6](../CLAUDE.md)）— 这 4 条契约码化为 `MemoryProperties` + `MemoryScope.fromString()` + `MemoryBackendSelector` 的运行时校验