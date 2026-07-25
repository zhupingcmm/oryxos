# Contract: `ProfileContext`

**Package**: `io.oryxos.core`
**Module**: `oryxos-core`
**Stability**: Stable
**Consumers**: `ReActLoop`（写入 `sessionIteration`）、`ToolExecutor`（读取 `sessionIteration` 写入审计行）、未来 `OryxTool`（读取 profile/session）
**Implementors**: `ProfileContext`（final class，thread-local holder）

---

## 1. 类型签名

```java
package io.oryxos.core;

public final class ProfileContext {

    /** ProfileContext 持有的不可变快照 */
    public record Snapshot(
        String profileName,                  // 当前 Agent 的 Profile 名
        UUID sessionId,                      // 当前 Session UUID
        AtomicInteger currentIteration       // 当前迭代计数（循环可写、读）
    ) {
        public Snapshot {
            Objects.requireNonNull(profileName, "profileName");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(currentIteration, "currentIteration");
        }
    }

    private static final ThreadLocal<Snapshot> CTX = new ThreadLocal<>();

    private ProfileContext() {}             // 不可实例化

    /** 由 {@code AgentService.process} 唯一调用一次；其他模块禁止 set */
    public static void set(Snapshot s) {
        if (CTX.get() != null) {
            throw new IllegalStateException(
                "ProfileContext already set on this thread; clear() first to avoid leakage");
        }
        CTX.set(Objects.requireNonNull(s, "snapshot"));
    }

    /** 任何代码可读取；缺省时返回 Optional.empty（说明不在 loop 调用栈内） */
    public static Optional<Snapshot> current() {
        return Optional.ofNullable(CTX.get());
    }

    /** 由 {@code AgentService.process} 的 finally 块调用一次 */
    public static void clear() {
        CTX.remove();                       // remove 而非 set(null) 防止 ThreadLocal 内存泄漏
    }
}
```

---

## 2. 契约条款

| ID | 条款 | 强制性 | 验证方式 |
|----|------|--------|----------|
| C-PC-1 | `set` 一次（每条线程每次 `AgentService.process`）；不可重复 set，会抛 `IllegalStateException` | MUST | spec FR-017 / I-06 |
| C-PC-2 | `clear` 必须配对：每次 `set` 必须有 `clear` 跟在 finally 块 | MUST | spec FR-017 / I-06 |
| C-PC-3 | `current` 缺省返回 `Optional.empty()` 而非抛异常 | MUST | 单测 |
| C-PC-4 | `Snapshot` 在 `set` 那一刻快照；后续修改引用不影响已 set 的快照（`AtomicInteger` 是个例外——故意设计为 mutable 让循环内部 increment） | MUST | 不变性 |
| C-PC-5 | `AgentService.process` 抛异常路径同样要 `clear`（finally 块保证） | MUST | spec FR-017 / I-06 |
| C-PC-6 | `currentIteration` 的更新通过 `AtomicInteger.incrementAndGet()`，确保循环内部多线程协作时（理论上不存在）不丢更新 | SHOULD | R-7 论证 |
| C-PC-7 | 不暴露 `String get()` 或 `remove()` 之外的 ThreadLocal 操作 API；防止外部代码绕过 set/clear 协议 | MUST | 接口只有 4 个方法 |

---

## 3. 与 spec 的对应

| spec 条目 | 对应契约 |
|----------|---------|
| spec FR-017：set 入口 + `finally` clear | C-PC-1 / C-PC-2 / C-PC-5 |
| spec FR-018：线程隔离 | `ThreadLocal` 自然满足 |
| spec SC-003：N 个并发 process 零串扰 | 通过 ThreadLocal 而非共享变量 |
| spec A-006：ProfileContext 是 core 的契约 | 本契约即"core 契约"定义 |
| spec Edge case：线程复用 | C-PC-2 / C-PC-5 |

---

## 4. 调用约定

### 4.1 唯一合法的 set/clear 入口

**只有 `AgentService.process(...)` 应当调用 `set` 与 `clear`**。任何其它代码路径要使用 `ProfileContext`，**禁止**直接调用 `set`——它们只能 `current()` 读取。如果一个 OryxTool 需要"在另一个 thread 启动后台任务并保留 ProfileContext"，**必须**把 Snapshot 作为参数显式传递；不允许隐式继承（spec FR-018 / A-009）。

### 4.2 读取的合法性

```java
// OryxTool.execute(...) 内部
Optional<ProfileContext.Snapshot> ctxOpt = ProfileContext.current();
if (ctxOpt.isEmpty()) {
    throw new IllegalStateException(
        "OryxTool.execute called outside AgentService.process; " +
        "tools must be invoked from within the ReAct loop");
}
var ctx = ctxOpt.get();
String profileName = ctx.profileName();      // 写到审计行 / 拼日志 / 业务分支
UUID sessionId    = ctx.sessionId();
int  iteration    = ctx.currentIteration().get();   // 用于审计行交叉定位
```

### 4.3 写 iteration 的合法性

```java
// ReActLoop 每次进入新迭代前
ProfileContext.current()
    .orElseThrow(() -> new IllegalStateException("ProfileContext not set"))
    .currentIteration()
    .incrementAndGet();
```

---

## 5. 测试义务

| 测试类 | 断言 |
|--------|------|
| `ProfileContextTest#setAndClear` | set 后 current() 有值；clear 后 current() 为 Optional.empty |
| `ProfileContextTest#doubleSetThrows` | 同一线程连续两次 set 抛 IllegalStateException |
| `ProfileContextTest#clearWithoutSet` | clear 是 no-op（CTX.remove() 安全） |
| `ProfileContextTest#isolatedAcrossThreads` | 线程 A set "profile-A"，线程 B current() 是空 |
| `ProfileContextTest#currentIncrementIsAtomic` | 多线程并发 increment 计数无丢失（理论保险） |
| `ProfileContextTest#snapshotIsImmutable` | Snapshot 内部的 profileName / sessionId String 不可变；AtomicInteger 故意可变 |
| `AgentServiceE2EIT#contextClearedOnException` | ReActLoop 抛异常，AgentService 路径后 ProfileContext.current() == Optional.empty() |

---

## 6. 内存模型注意

- `ThreadLocal` 的 `remove()`（vs `set(null)`）确保 ThreadLocalMap 中的 entry 被释放，避免 class-loader 长期不卸载场景下的 OOM。`clear()` 内部用 `remove()` 满足这一最佳实践。
- Spring Boot 3.x 的虚拟线程（`Thread.ofVirtual()`）也支持 `ThreadLocal`，但 OryxOS 仅在同步入口内使用——任何 R-7 之外的扩展（async/parallel）必须重新审视本契约。
