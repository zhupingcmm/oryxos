# Contract: `LoopResult`

**Package**: `io.oryxos.core`
**Module**: `oryxos-core`
**Stability**: Stable
**Consumers**: `AgentService`（返回）；`CliChannel` / Web Controller / AgentScheduler 触发方（最终返回用户）

---

## 1. 类型签名

```java
package io.oryxos.core;

/**
 * ReActLoop 一次运行的最终结果（不可变 record）。
 *
 * <p>无论循环以哪种方式结束——(a) 拿到 LLM 的无 tool_call 文本响应，或
 * (b) 触发 {@code MAX_ITERATIONS} 上限——都返回同样的 {@code LoopResult}，
 * 区别仅在 {@link #terminatedAtMax}。
 */
public record LoopResult(
    String finalText,                // 用户最终可见的回复文本（必非 null）
    int iterations,                  // 实际迭代次数；0 <= iterations <= MAX_ITERATIONS + 1
                                     //   iter=0 仅出现在 MAX_ITERATIONS==0 路径（spec Edge case 5 / C-LR-7）
                                     //   iter>=1 出现在正常 Reason 路径（spec FR-013 (a) 或 (b)）
    boolean terminatedAtMax,         // true = 循环因 MAX_ITERATIONS 截断（spec FR-013 (b) 路径 + Edge case 5 静态退出）
    String profileName,              // 当前 Profile 名（也用于日志聚合）
    UUID sessionId                   // 当前 Session UUID
) {
    public LoopResult {
        Objects.requireNonNull(finalText, "finalText");
        if (finalText.isEmpty()) throw new IllegalArgumentException("finalText empty");
        if (iterations < 0) throw new IllegalArgumentException(
            "iterations must be >= 0 (loop ran negative iterations)");
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(sessionId, "sessionId");
        // C-LR-7：iter=0 仅在 MAX_ITERATIONS==0 的防御性退出路径出现
        if (iterations == 0 && !"loop not configured".equals(finalText)) {
            throw new IllegalArgumentException(
                "iterations=0 must be paired with finalText=\"loop not configured\" (C-LR-7)");
        }
    }
}
```

---

## 2. 契约条款

| ID | 条款 | 强制性 | 验证方式 |
| --- | --- | --- | --- |
| C-LR-1 | `finalText` 必非 null、非空字符串 | MUST | record compact constructor |
| C-LR-2 | `iterations >= 0`（`iter=0` 仅出现在 spec Edge case 5 `MAX_ITERATIONS==0` 的防御性退出；`iter>=1` 是正常 Reason 路径） | MUST | record compact constructor |
| C-LR-3 | `terminatedAtMax=true` 表示因 `MAX_ITERATIONS` 截断；`finalText` 是最后一次 `assistant(tool_call)` 的内容、或合成的"loop terminated at max_iterations"标记；`MAX_ITERATIONS==0` 时也是 `true`（见 C-LR-7） | MUST | spec FR-013 |
| C-LR-4 | `terminatedAtMax=false` 表示 LLM 给出了无 tool_call 的响应；`finalText` 是该次 assistant text 内容 | MUST | spec FR-013 |
| C-LR-5 | `profileName` + `sessionId` 用于审计员跨表 join | MUST | spec FR-021 |
| C-LR-6 | 不暴露 setter；不可变 record | MUST | 不变性 |
| C-LR-7 | `iterations == 0` ⇒ `finalText == "loop not configured"` 且 `terminatedAtMax == true`（spec Edge case 5 静态退出路径） | MUST | record compact constructor（显式校验） |

---

## 3. 与 spec 的对应

| spec 条目 | 对应契约 |
| --- | --- |
| spec FR-013：循环终止于无 tool_call 或 MAX_ITERATIONS | C-LR-3 / C-LR-4 |
| spec FR-015：至多 `MAX_ITERATIONS + 1` 次 LLM 调用 | `iterations <= maxIterations + 1`，由 `ReActLoop` 保证；`LoopResult` 不重复保证 |
| spec NFR-002：中断安全，审计行不丢 | `LoopResult` 是循环**结束后**的最终对象，与 NFR-002 通过 `LlmCallRecord` / `ToolInvocationRecord` day-one 持久化保证；`LoopResult` 自身不持久化 |

---

## 4. 调用约定

### 4.1 触发源如何把 LoopResult 暴露给用户

| 触发源 | 暴露方式 |
| --- | --- |
| CLI | 输出 `LoopResult.finalText()` 至 stdout；附带 `--json` 选项时输出 `{finalText, iterations, terminatedAtMax, profileName, sessionId}` |
| Web | HTTP 200 + JSON body 同上 |
| Scheduler | 写入 `task_executions` 表（US-5）；notify 出站通道（US-4）发送 `finalText()` |

### 4.2 异常路径不返回 LoopResult

`ProviderService.invoke` 抛 `LlmInvocationException` / `LlmResponse` 抛任何 unchecked 异常 → `AgentService.process` 不返回 `LoopResult`，而是让异常向上传播给触发源。`LlmInvocationException` 由 `DefaultAuditWriter` 已写入 `LlmCallRecord`（US-1 契约），调用方按异常消息向用户展示"调用失败"。

`MAX_ITERATIONS == 0`（Edge case 5）：循环**不调用 LLM**，返回 `LoopResult("loop not configured", 0, true, ...)`。这是 Edge case 5 的合法路径，由 C-LR-7 显式建模（C-LR-2 也已放宽到 `>= 0`），无需再作"调整"。

```java
// 实际 compact constructor（含 C-LR-2 + C-LR-7）：
if (iterations < 0) throw new IllegalArgumentException(
    "iterations must be >= 0 (loop ran negative iterations)");
if (iterations == 0 && !"loop not configured".equals(finalText)) {
    throw new IllegalArgumentException(
        "iterations=0 must be paired with finalText=\"loop not configured\" (C-LR-7)");
}
```

迭代 0 是合法值（对应"MAX_ITERATIONS == 0 时不调 LLM"的特殊路径）；迭代 < 0 非法。本 record 的 validation 在 `ReActLoop.run` 出口处保证 `0 <= iterations <= maxIterations`，且 `iter==0` ⇒ `finalText="loop not configured"`（C-LR-7）。

---

## 5. 测试义务

| 测试类 | 断言 |
| --- | --- |
| `ReActLoopTest#successLoopReturnsIter1` | LLM 一次返 text → `LoopResult(iter=1, terminatedAtMax=false, finalText=...)` |
| `ReActLoopTest#maxIterReturnsTerminatedAtMaxTrue` | LLM 每次都返 tool_call，跑满 10 次 → `LoopResult(iter=10, terminatedAtMax=true, finalText=last_assistant_tool_call)` |
| `ReActLoopTest#maxIter0ReturnsStatic` | `settings.maxIterations=0` → `LoopResult(iter=0, terminatedAtMax=true, finalText="loop not configured")` |
| `LoopResultValidationTest#rejectsNullFinalText` | 编译期 / record constructor 拒绝 |
| `LoopResultValidationTest#rejectsNegativeIterations` | 编译期 / record constructor 拒绝（`iter=-1` 抛 IllegalArgumentException） |
