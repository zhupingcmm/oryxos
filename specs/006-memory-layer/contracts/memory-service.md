# 契约：MemoryService 统一门面

**目的**：定义 `MemoryService` 的接口契约（[spec.md FR-001](../spec.md)），供 ReAct 循环 + Memory Tool + 业务方调用
**归属模块**：`oryxos-memory`
**位置**：`oryxos-memory/src/main/java/io/oryxos/memory/MemoryService.java`
**关联契约**：[long-term-store.md](./long-term-store.md) | [data-model.md §2.1](../data-model.md)

---

## 1. 接口签名

```java
package io.oryxos.memory;

import java.util.List;

/**
 * Memory 层统一门面（spec FR-001）。
 * - 内部委派 SessionManager（会话层）+ LongTermMemoryStore（长期层）
 * - 对 ReAct 暴露 save / recallByKeyword / recallByScope / delete / clear 5 个操作
 * - 严格遵循 [CLAUDE.md §9.6](../CLAUDE.md) 4 条契约（不缓存 / core 永不被截断 / scope 显式 / keyword-only 检索）
 */
public interface MemoryService {

    /**
     * 保存一条长期记忆。
     *
     * @param scope   必填；core 永不被截断，archive 可被裁剪（spec FR-008/FR-009/FR-010）
     * @param content 必填；非 null
     * @param tags    可选；标签列表（Markdown 后端 informational；SQLite 后端 JSON-as-TEXT）
     * @return 新建的 MemoryEntry（含生成的 UUID id + createdAt）
     * @throws MemoryException 底层 IO 错误（磁盘满 / SQLite busy / Mem0 不可达）
     */
    MemoryEntry save(MemoryScope scope, String content, List<String> tags);

    /**
     * 按 keyword 子串匹配 recall（spec FR-006）。
     *
     * @param query        必填；非空字符串
     * @param topK         默认 10；上限 100（防滥用）
     * @param scopeFilter  可选；null = 不限定 scope
     * @return 按 createdAt DESC 排序的前 topK 条；空集合表示未命中
     */
    List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter);

    /**
     * 按 scope 拉取最近记录（无 keyword；spec FR-001 派生）。
     *
     * @param scope 必填
     * @param topK  默认 10
     */
    List<MemoryEntry> recallByScope(MemoryScope scope, int topK);

    /**
     * 删除单条记录。
     *
     * @param entryId 必填；UUID v4 字符串
     * @return true = 找到并删除；false = 未找到
     */
    boolean delete(String entryId);

    /**
     * 清空某 scope 全部记录。
     *
     * @param scope 必填；core 调用 MUST 抛 IllegalStateException（违反契约 ②）
     */
    void clear(MemoryScope scope);
}
```

---

## 2. 契约条款（来自 spec FR / NFR）

| 编号 | 条款 | 违反后果 |
|------|------|---------|
| C-MS-01 | **不缓存**：每次 recall 直接读后端，不持有内存索引（spec FR-007） | 违反 = 写后立刻读会失命中（read-after-write 不一致）；测试用 Mockito 验证 `LongTermMemoryStore.recallByKeyword` 被实时调用 |
| C-MS-02 | **core 永不被截断**：`save(MemoryScope.core, ...)` MUST 不触发 trim 逻辑（spec FR-009） | 违反 = 用户偏好消失（P0 灾难）；测试用 N=1500 条断言 |
| C-MS-03 | **scope 必填**：`save` 的 scope 参数 MUST 非 null（spec FR-008） | 违反 = 隐式 archive/core 行为；测试传 null 抛 IllegalArgumentException |
| C-MS-04 | **不抛异常到 ReAct**：底层 `MemoryException` 必被 Tool 层捕获转 `ToolResult.error(...)`（spec FR-013） | 违反 = ReAct 主循环崩溃；测试用 stub `LongTermMemoryStore` 抛 `MemoryException` 验证 ToolResult.success=false |
| C-MS-05 | **clear(core) 拒绝**：`clear(MemoryScope.core)` MUST 抛 IllegalStateException（CLAUDE.md §9.6 契约 ②） | 违反 = 核心记忆被清空；测试断言 |
| C-MS-06 | **keyword 非空**：`recallByKeyword("", 10, null)` MUST 返回空集合（不抛异常，spec FR-005） | 违反 = 误返回全表；测试断言 |
| C-MS-07 | **topK 上限**：`topK > 100` 时 MUST 截断到 100（防滥用，spec FR-006 派生） | 违反 = 全表 dump；测试断言 |
| C-MS-08 | **错误信息不携带 stack trace**：`MemoryException` 转 `ToolResult.error(...)` 时 MUST 不带 `at io.oryxos.*` / `Exception:` 模式（spec NFR-004） | 违反 = LLM 上下文污染；测试断言 |

---

## 3. 实现类契约

```java
package io.oryxos.memory;

import io.oryxos.memory.backend.LongTermMemoryStore;
import io.oryxos.memory.backend.SessionManager;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * MemoryService 默认实现。
 * - 构造器注入 SessionManager + LongTermMemoryStore（spec FR-001 内部委派）
 * - 不持有任何缓存 / 索引（C-MS-01）
 * - save() 入口校验 scope 非空（C-MS-03）
 */
@Service
public class DefaultMemoryService implements MemoryService {

    private static final int TOPK_HARD_LIMIT = 100;

    private final SessionManager sessionManager;
    private final LongTermMemoryStore longTermStore;

    public DefaultMemoryService(SessionManager sessionManager, LongTermMemoryStore longTermStore) {
        this.sessionManager = sessionManager;
        this.longTermStore = longTermStore;
    }

    @Override
    public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        if (content == null) throw new IllegalArgumentException("content must not be null");
        return longTermStore.save(scope, content, tags);
    }

    @Override
    public List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter) {
        if (query == null || query.isBlank()) return List.of();
        int effectiveTopK = Math.min(Math.max(topK, 1), TOPK_HARD_LIMIT);
        return longTermStore.recallByKeyword(query, effectiveTopK, scopeFilter);
    }

    @Override
    public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
        int effectiveTopK = Math.min(Math.max(topK, 1), TOPK_HARD_LIMIT);
        return longTermStore.recallByScope(scope, effectiveTopK);
    }

    @Override
    public boolean delete(String entryId) {
        return longTermStore.delete(entryId);
    }

    @Override
    public void clear(MemoryScope scope) {
        if (scope == MemoryScope.core) {
            throw new IllegalStateException("core scope cannot be cleared (CLAUDE.md §9.6 contract ②)");
        }
        longTermStore.clear(scope);
    }
}
```

> **会话层委派**：本 spec **不**实现 SessionManager 全部接口；`SessionManager` 在既有 003-cli-commands spec 已落地（管理当前 Session 的对话消息）；MemoryService 仅注入它用于"Session 结束不自动落长期层"的契约断言，不在 save/recall 中调用 sessionManager。
>
> **架构简图**：
> ```
> MemoryService
>   ├── sessionManager: SessionManager（注入但本 spec 不在 save/recall 用）
>   └── longTermStore:  LongTermMemoryStore（注入 + save/recall/delete 委派）
>                       ↑ Runtime 时由 MemoryBackendSelector 按 Profile.memo.backend 选定
> ```

---

## 4. 测试用例（tasks.md 阶段落地）

| TestID | 场景 | 断言 |
|--------|------|------|
| MS-IT-01 | save(core) 1000 条 → recallByKeyword 命中全部 | 列表 size == 1000（C-MS-02） |
| MS-IT-02 | save 1 条 → 立即 recallByKeyword | 命中 1 条（C-MS-01 read-after-write） |
| MS-IT-03 | save(null scope) | 抛 IllegalArgumentException（C-MS-03） |
| MS-IT-04 | clear(core) | 抛 IllegalStateException（C-MS-05） |
| MS-IT-05 | recallByKeyword("", 10, null) | 返回空集合（C-MS-06） |
| MS-IT-06 | recallByKeyword("...", 9999, null) | 最多返回 100 条（C-MS-07） |
| MS-IT-07 | longTermStore 抛 MemoryException | MemoryService 不重新抛（C-MS-04；Tool 层负责转换） |
| MS-IT-08 | MemoryException message 不含 `at io.oryxos.*` | 字符串匹配断言（C-MS-08） |

---

## 5. 与既有契约的关系

| 既有契约 | 关系 |
|----------|------|
| [CLAUDE.md §9.6](../CLAUDE.md) | 4 条契约码化为 C-MS-01/02/03/06 + MemoryService 默认实现 |
| [spec.md FR-001—FR-010](../spec.md) | 接口签名与方法语义一一对应 |
| [005-tool-system spec FR-011/FR-012/FR-013](../005-tool-system/spec.md) | Memory Tool 集成契约（Tool ↔ MemoryService 的异常转换） |
| [005-tool-system spec FR-007](../005-tool-system/spec.md) | Memory Tool 走既有 `DefaultToolExecutor` 派发（不绕过 Tool 抽象） |