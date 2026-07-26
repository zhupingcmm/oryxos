# 契约：MarkdownMemoryStore（默认后端）

**目的**：定义 `MarkdownMemoryStore` 实现契约（[spec.md FR-004/FR-005](../spec.md)），落 `.oryxos/memory/MEMORY.md` 文件
**归属模块**：`oryxos-memory`
**位置**：`oryxos-memory/src/main/java/io/oryxos/memory/backend/MarkdownMemoryStore.java`
**关联契约**：[long-term-store.md §C-LT](./long-term-store.md) | [migration-scripts.md](./migration-scripts.md)

---

## 1. 类签名

```java
package io.oryxos.memory.backend;

import io.oryxos.memory.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

/**
 * 默认长期层后端：.oryxos/memory/MEMORY.md 文件（spec FR-004）。
 *
 * 文件结构：
 *   # MEMORY
 *
 *   ## Core
 *   - [2026-07-26T10:00:00Z] user prefers PR tags = bug+enhancement
 *   - [2026-07-26T10:05:00Z] timezone = Asia/Shanghai
 *
 *   ## Archive
 *   - [2026-07-26T11:00:00Z] fetched GitHub PR #1234 (transient)
 *   - [2026-07-26T11:01:00Z] fetched GitHub PR #1235 (transient)
 *
 * 特性：
 * - core 段永不被 trim（[CLAUDE.md §9.6](../CLAUDE.md) 契约 ②；research R-05）
 * - archive 段不主动 trim（research R-06：Markdown 后端默认无上限）
 * - 单 JVM 内 synchronized 串行化写（research R-04）
 * - ATOMIC_MOVE 防止崩溃损坏（spec NFR-003）
 */
@Component("markdownMemoryStore")
public class MarkdownMemoryStore implements LongTermMemoryStore {

    private final Path filePath;
    private final Object writeLock = new Object();

    public MarkdownMemoryStore(
        @Value("${oryxos.memory.markdown.path:.oryxos/memory/MEMORY.md}") String path
    ) {
        this.filePath = Paths.get(path);
    }

    @Override
    public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        String entryId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        String line = formatLine(entryId, scope, content, tags, createdAt);
        synchronized (writeLock) {
            appendLine(scope, line);
        }
        return new MemoryEntry(entryId, scope, content, tags, createdAt, scope.name());
    }

    @Override
    public List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter) {
        // 字面 keyword 匹配（spec FR-005）；不引入正则
        // 按 createdAt DESC 排序（spec FR-006）
        // —— tasks.md 阶段实现
        return List.of();
    }

    @Override
    public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
        // 按 scope 段读取，按 createdAt DESC 排序
        // —— tasks.md 阶段实现
        return List.of();
    }

    @Override
    public boolean delete(String entryId) {
        // 按 entryId 定位行并删除；行格式 `- [<entryId>] <content>`
        // —— tasks.md 阶段实现
        return false;
    }

    @Override
    public void clear(MemoryScope scope) {
        if (scope == MemoryScope.core) {
            throw new IllegalStateException("core scope cannot be cleared");
        }
        synchronized (writeLock) {
            // 重写文件，仅保留 core 段；archive 段清空
            // —— tasks.md 阶段实现
        }
    }

    @Override
    public boolean isHealthy() {
        return Files.isWritable(filePath.getParent() == null ? Paths.get(".") : filePath.getParent());
    }

    private String formatLine(String id, MemoryScope scope, String content, List<String> tags, Instant ts) {
        StringBuilder sb = new StringBuilder();
        sb.append("- [").append(ts.toString()).append("] ");
        sb.append("[").append(id).append("] ");
        sb.append(content);
        if (tags != null && !tags.isEmpty()) {
            sb.append(" #tags=").append(String.join(",", tags));
        }
        return sb.toString();
    }

    private void appendLine(MemoryScope scope, String line) {
        // 1. 读全文
        // 2. 若文件不存在 → 创建 + 写 ## Core + ## Archive 默认 heading + 追加行
        // 3. 找到 ## Core 或 ## Archive 段尾 → 追加
        // 4. ATOMIC_MOVE 写回（research R-04）
        // —— tasks.md 阶段实现
    }
}
```

---

## 2. 文件结构契约

```markdown
# MEMORY

## Core
- [2026-07-26T10:00:00Z] [550e8400-e29b-41d4-a716-446655440000] user prefers PR tags = bug+enhancement #tags=preference,github
- [2026-07-26T10:05:00Z] [550e8400-e29b-41d4-a716-446655440001] timezone = Asia/Shanghai

## Archive
- [2026-07-26T11:00:00Z] [550e8400-e29b-41d4-a716-446655440002] fetched GitHub PR #1234
- [2026-07-26T11:01:00Z] [550e8400-e29b-41d4-a716-446655440003] fetched GitHub PR #1235
```

**契约**：

- 必须含 `# MEMORY` 顶层标题
- 必须含 `## Core` 和 `## Archive` 两段（spec FR-004 硬约束）
- `## Core` 段内容 MUST NOT 被任何 trim / clear 逻辑修改（spec FR-009）
- 行格式：`- [ISO-8601 timestamp] [UUID] <content> [#tags=tag1,tag2]`
- 写入顺序：append 至段尾（spec FR-005 "按追加方式"）
- 文件不存在 → 首次 save 创建 + 写默认 heading + 追加行
- 文件含两段但缺 `# MEMORY` 标题 → lenient recovery 写标题（spec 边界情况 1）

---

## 3. 契约条款（Markdown 后端专属）

| 编号 | 条款 | 验证手段 |
|------|------|---------|
| C-MD-01 | **追加方式**：`save` MUST 不重写已有内容；只 append 至对应段尾（spec FR-005） | 测试：写 3 条后读全文，断言前 2 条内容不变 |
| C-MD-02 | **字面 keyword 匹配**：`recallByKeyword` MUST 按 content 字面子串匹配，不引入正则（spec FR-005） | 测试：`recallByKeyword("bug+enhancement")` 命中 `content="user prefers PR tags = bug+enhancement"` |
| C-MD-03 | **空 query 返回空集合**：`recallByKeyword("", 10, null)` MUST 返回空集合（spec FR-005） | 单测断言 |
| C-MD-04 | **不持有文件句柄**：每次 read/write 立即 close（spec NFR-003） | 测试用 jdk 自带工具验证 |
| C-MD-05 | **ATOMIC_MOVE 写**：`save` 写文件 MUST 用 `Files.move(tmp, target, ATOMIC_MOVE)` 兜底（research R-04） | 测试模拟写入中崩溃 → 文件不损坏 |
| C-MD-06 | **同步串行化**：单 JVM 内并发 `save` MUST 串行化（`synchronized` 块；research R-04） | 测试用 N=10 线程并发 save 100 次，断言最终记录数 = 100 |
| C-MD-07 | **tags informational**：Markdown 后端 `tags` 字段不参与查询（research R-02） | 测试 `recallByKeyword` 不命中 tags |
| C-MD-08 | **lenient recovery**：外部删除 `## Core` 段时，下次 save 重建该段（spec 边界情况 1） | 测试手动删除 → save → 文件含两段 |
| C-MD-09 | **archive 不主动 trim**：Markdown 后端 MUST NOT 触发 archive 容量裁剪（research R-06） | 测试 save(archive) 1500 条无 trim，recall 命中全部 |

---

## 4. 性能特征

- **save**：单次约 5-50ms（视文件大小）；文件 ≤ 100 KB 时 O(file_size)
- **recallByKeyword**：O(file_size) 线性扫描 + 子串匹配；N=1000 时 < 50ms
- **delete**：O(file_size) 线性扫描 + 字符串替换
- **isHealthy**：O(1) 文件可写性检查

> **瓶颈预警**：Markdown 后端在 N > 5000 条时 recallByKeyword 超过 200ms；spec NFR-001 满足 N=1000 量级。扩展阶段建议业务方切到 SqliteMemoryStore 或 Mem0MemoryStore。

---

## 5. 测试用例

| TestID | 场景 | 断言 |
|--------|------|------|
| MD-IT-01 | save(core) → 文件含 ## Core 段 + 新行 | 文件包含 `- [...] [UUID] <content>` |
| MD-IT-02 | save(archive) → 文件含 ## Archive 段 + 新行 | 文件包含 `- [...] [UUID] <content>` |
| MD-IT-03 | save(archive) 后 recallByScope(archive) | 命中 1 条 |
| MD-IT-04 | save 3 条 core → recallByKeyword 命中前 2 条 + 第 3 条（C-MD-01 不重写） | 列表 size = 3 |
| MD-IT-05 | recallByKeyword("", 10, null) | 返回空集合（C-MD-03） |
| MD-IT-06 | save(core) 1500 条 → recallByKeyword 命中全部 1500 条 | C-MD-06 + C-LT-02 |
| MD-IT-07 | save(archive) 1500 条 + maxEntries=1000 → recall 命中全部 1500 条（C-MD-09 Markdown 不主动 trim） | 列表 size = 1500 |
| MD-IT-08 | 手动删 ## Core 段 → save → 文件含 ## Core 段（C-MD-08 lenient recovery） | 文件含两段 |
| MD-IT-09 | N=10 线程并发 save 100 次 → 最终 100 条（C-MD-06 同步串行化） | 文件非空行数 = 100 |
| MD-IT-10 | clear(core) → IllegalStateException（C-LT-05） | 抛异常 |
| MD-IT-11 | save 后立即 recallByKeyword 命中（C-LT-01 read-after-write） | 命中 |

---

## 6. 与既有契约的关系

| 既有契约 | 关系 |
|----------|------|
| [CLAUDE.md §9.6](../CLAUDE.md) | 4 条契约码化为 C-MD-01/02/03 + C-LT-01/02/03/05 |
| [spec.md FR-004/FR-005](../spec.md) | 文件结构 / 追加方式 / 字面匹配一一对应 |
| [research.md R-04/R-06](./research.md) | 同步串行化 + Markdown 不主动 trim |
| [migration-scripts.md](./migration-scripts.md) | 数据迁移脚本读取/写入同一文件 |