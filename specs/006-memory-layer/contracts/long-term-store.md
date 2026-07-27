# 契约：LongTermMemoryStore 长期层接口

**目的**：定义 `LongTermMemoryStore` 接口契约（[spec.md FR-003](../spec.md)），三后端实现共享同一接口
**归属模块**：`oryxos-memory`
**位置**：`oryxos-memory/src/main/java/io/oryxos/memory/backend/LongTermMemoryStore.java`
**关联契约**：[memory-service.md](./memory-service.md) | [markdown-backend.md](./markdown-backend.md) | [sqlite-backend.md](./sqlite-backend.md) | [mem0-backend.md](./mem0-backend.md)

---

## 1. 接口签名

```java
package io.oryxos.memory.backend;

import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryException;
import io.oryxos.memory.MemoryScope;
import java.util.List;

/**
 * 长期层统一接口（spec FR-003）。
 * - 3 个 builtin 实现：MarkdownMemoryStore / SqliteMemoryStore / Mem0MemoryStore
 * - 由 Profile YAML `memo.backend` 字段选择（research R-08）
 * - 实现 MUST 遵循 4 条契约（不缓存 / core 永不被截断 / scope 显式 / keyword-only 检索）
 */
public interface LongTermMemoryStore {

    /**
     * 保存一条记录（spec FR-005）。
     *
     * @return 新建的 MemoryEntry（含生成 id + createdAt）
     * @throws MemoryException 底层 IO 错误
     */
    MemoryEntry save(MemoryScope scope, String content, List<String> tags);

    /**
     * 按 keyword recall（spec FR-006）。
     *
     * @return 按 createdAt DESC 排序的前 topK 条；空集合表示未命中
     */
    List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter);

    /**
     * 按 scope 拉取最近记录。
     */
    List<MemoryEntry> recallByScope(MemoryScope scope, int topK);

    /**
     * 删除单条记录。
     *
     * @return true = 找到并删除；false = 未找到
     */
    boolean delete(String entryId);

    /**
     * 清空某 scope 全部记录。
     *
     * 实现 MUST 拒绝清空 core scope（research R-05）。
     */
    void clear(MemoryScope scope);

    /**
     * 后端健康检查（Mem0 不可达场景；spec 边界情况 4）。
     *
     * @return true = 后端可达；false = 不可达 / degraded
     */
    default boolean isHealthy() { return true; }
}
```

---

## 2. 契约条款（三后端共享）

| 编号 | 条款 | 验证手段 |
|------|------|---------|
| C-LT-01 | **不缓存**：实现 MUST 不持有任何内存索引 / 缓存（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ①） | 测试用 Mockito 验证 `save` 后立即 `recallByKeyword` 命中 |
| C-LT-02 | **core 永不被截断**：`save(MemoryScope.core, ...)` MUST 不触发 trim / delete 逻辑（spec FR-009） | 测试用 N=1500 条 core 写入后 recallByKeyword 命中全部 |
| C-LT-03 | **scope 必填**：`save(null, ...)` MUST 抛 IllegalArgumentException（spec FR-008） | 单测断言 |
| C-LT-04 | **trim 仅作用于 archive**：`save(MemoryScope.archive, ...)` 写入前如 `count > maxEntries` 则按 `created_at ASC` 裁剪最旧 N 条（research R-06） | SQLite 单测：1500 条 archive + maxEntries=1000 → recall 命中最新 1000 条 |
| C-LT-05 | **clear(core) 拒绝**：实现 MUST 在 `clear(MemoryScope.core)` 时抛 IllegalStateException | 单测断言 |
| C-LT-06 | **MemoryException 抛出**：所有底层 IO 错误（磁盘满 / SQLite busy / Mem0 不可达） MUST 转 `MemoryException`（spec FR-013） | 单测用 stub 模拟 IOException 验证抛 `MemoryException` |
| C-LT-07 | **id 生成**：实现 MUST 在 `save` 入口用 `UUID.randomUUID().toString()` 生成 id（research R-07） | 单测断言 `entry.id()` 满足 UUID v4 正则 |
| C-LT-08 | **createdAt 默认**：实现 MUST 用 `Instant.now()` 设置 createdAt（research R-07） | 单测断言 `entry.createdAt()` 与调用时间差 ≤ 100ms |

---

## 3. 后端选择契约

```java
package io.oryxos.boot.config;

import io.oryxos.memory.backend.*;
import org.springframework.stereotype.Component;

/**
 * 按 Profile.memo.backend 选择 LongTermMemoryStore 实现（research R-08）。
 *
 * 装配时机：Spring Boot 启动期；Profile YAML 加载完成后
 * 切换方式：修改 Profile YAML 的 memo.backend 字段 + 重启 OryxOS
 */
@Component
public class MemoryBackendSelector {
    private final MarkdownMemoryStore markdown;
    private final SqliteMemoryStore sqlite;
    private final Mem0MemoryStore mem0;

    public MemoryBackendSelector(
        @org.springframework.beans.factory.annotation.Qualifier("markdownMemoryStore") MarkdownMemoryStore markdown,
        @org.springframework.beans.factory.annotation.Qualifier("sqliteMemoryStore") SqliteMemoryStore sqlite,
        @org.springframework.beans.factory.annotation.Qualifier("mem0MemoryStore") Mem0MemoryStore mem0
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

## 4. 三后端实现对照

| 后端 | 存储介质 | 实现文件 | spec FR 引用 |
|------|---------|---------|--------------|
| `MarkdownMemoryStore` | `.oryxos/memory/MEMORY.md` 文件 | `oryxos-memory/src/main/java/io/oryxos/memory/backend/MarkdownMemoryStore.java` | [spec FR-004/FR-005](../spec.md) |
| `SqliteMemoryStore` | `agent_memories` 表（同 SQLite 库） | `oryxos-memory/src/main/java/io/oryxos/memory/backend/SqliteMemoryStore.java` | [spec FR-014](../spec.md) |
| `Mem0MemoryStore` | Mem0 服务 + `memory_index` 映射表 | `oryxos-memory/src/main/java/io/oryxos/memory/backend/Mem0MemoryStore.java` | [spec FR-015](../spec.md) |

各后端详细契约见：

- [markdown-backend.md](./markdown-backend.md)
- [sqlite-backend.md](./sqlite-backend.md)
- [mem0-backend.md](./mem0-backend.md)

---

## 5. 测试用例（三后端共享 + 各自后端专属）

| TestID | 后端 | 场景 | 断言 |
|--------|------|------|------|
| LT-IT-01 | ALL | save + 立即 recallByKeyword | 命中（C-LT-01） |
| LT-IT-02 | ALL | save(core) 1500 条 | recall 命中全部（C-LT-02） |
| LT-IT-03 | ALL | save(null scope) | IllegalArgumentException（C-LT-03） |
| LT-IT-04 | sqlite | save(archive) 1500 条 + maxEntries=1000 | recall 命中 1000 条（C-LT-04） |
| LT-IT-05 | ALL | clear(core) | IllegalStateException（C-LT-05） |
| LT-IT-06 | ALL | 底层 IOException → MemoryException | 抛 `MemoryException` 且 cause 是 IOException（C-LT-06） |
| LT-IT-07 | ALL | save 后 entry.id() | UUID v4 正则匹配（C-LT-07） |
| LT-IT-08 | ALL | save 后 entry.createdAt() | 与 `Instant.now()` 差 ≤ 100ms（C-LT-08） |

---

## 6. 与既有契约的关系

| 既有契约 | 关系 |
|----------|------|
| [CLAUDE.md §9.6](../CLAUDE.md) | 4 条契约码化为 C-LT-01/02/03/04 |
| [spec.md FR-003—FR-010](../spec.md) | 接口签名与方法语义一一对应 |
| [research.md R-05—R-08](./research.md) | core 契约验证点 / archive trim 时机 / id 生成 / 后端选择策略 |