# Contract: `ToolExecutor`

**Package**: `io.oryxos.core`
**Module**: `oryxos-core`
**Stability**: Stable — 任何变更需走 constitution amendment
**Consumers**: `ReActLoop`（US-2）
**Implementors**: US-2 `DefaultToolExecutor` stub（仅识别"tool not in profile"错误路径）、US-4 真实实现

---

## 1. 接口签名

```java
package io.oryxos.core;

public interface ToolExecutor {
    /**
     * 派发一次 Tool 调用。
     *
     * <p>实现必须做两件事：
     * <ol>
     *   <li>校验 {@code toolName} 是否在 {@code profile.tools()} 白名单；不在则返回
     *       {@code ToolResult.error("tool not in profile: " + toolName)}，且
     *       写入一行 {@code ToolInvocationRecord}（{@code success=false}）。</li>
     *   <li>无论成功失败，写入一行 {@code ToolInvocationRecord}（{@code session_id},
     *       {@code profile_name}, {@code tool_name}, {@code arguments},
     *       {@code success}, {@code error_message}, {@code duration_ms},
     *       {@code started_at}, {@code session_iteration}）。</li>
     * </ol>
     *
     * @param toolName  工具名；调用方保证已通过 LLM 检验
     * @param arguments 解析后的 JSON 参数
     * @param profile   当前 Profile（用于白名单校验 + 审计行的 profile_name）
     * @return 工具执行结果
     */
    ToolResult invoke(String toolName, Map<String,Object> arguments, Profile profile);
}

public record ToolResult(
    boolean success,
    Map<String,Object> payload,         // 成功时工具的实际输出；不可变
    String errorMessage                 // 成功时为 null；失败时非空
) {
    public static ToolResult ok(Map<String,Object> p) {
        return new ToolResult(true, Map.copyOf(p), null);
    }
    public static ToolResult error(String message) {
        return new ToolResult(false, null, Objects.requireNonNull(message));
    }
}
```

---

## 2. 契约条款

| ID | 条款 | 强制性 | 验证方式 |
|----|------|--------|----------|
| C-TE-1 | `toolName` 不在 `profile.tools()` 时返回 `ToolResult.error("tool not in profile: <name>")`；不抛异常 | MUST | spec FR-011 / SC-004 |
| C-TE-2 | 每次调用写入一行 `ToolInvocationRecord`，无论成功失败 | MUST | spec FR-010 / SC-004 / Constitution §VI |
| C-TE-3 | `ToolInvocationRecord.session_iteration` = `ProfileContext.current().currentIteration().get()` | MUST | 数据可重现 |
| C-TE-4 | `ToolInvocationRecord.started_at` 为本地时间（spec A-007） | MUST | 单测断言 |
| C-TE-5 | 工具自身抛 unchecked 异常时，捕获、返回 `ToolResult.error(ex.getMessage())` 并写入 `success=false` 审计行 | MUST | spec Edge case 2 |
| C-TE-6 | 实现必须可注入到 ReActLoop——通过 Spring `@Autowired` 或显式构造 | MUST | 单测 |
| C-TE-7 | 调用同步、非流式；与 `ProviderService.invoke` 同语义 | MUST | 接口签名 |
| C-TE-8 | 实现**不**依赖 Spring AI 或其 Agent 抽象 | MUST | Constitution §III/§IV |
| C-TE-9 | 写入 `ToolInvocationRecord` 不被 Spring 事务回滚（即使后续循环异常） | MUST | spec NFR-002 |

---

## 3. 与 spec 的对应

| spec 条目 | 对应契约 |
|----------|---------|
| spec FR-009 ~ FR-012：循环只通过 `ToolExecutor.invoke` 派发；白名单拒绝；空 profile.tools 跳过 | C-TE-1 / C-TE-8 |
| spec FR-010：失败也写审计行 | C-TE-2 |
| spec FR-011：拒绝 (tool not in profile) 也写审计行 | C-TE-1 + C-TE-2 |
| spec Edge case 2：Tool 抛异常被循环捕获 | C-TE-5 |
| spec Edge case 3：Sandbox 违例当作 Tool 失败 | C-TE-5（SandboxViolation 继承 RuntimeException） |
| Constitution §VI：day-one 审计地基 | C-TE-2 / C-TE-9 |

---

## 4. US-2 阶段要求（stub `DefaultToolExecutor`）

US-2 不实现真实工具（归 US-4）。US-2 的 `DefaultToolExecutor` 行为：

| 输入 | 输出 |
|------|------|
| `toolName` 在 `profile.tools()` | 抛 `UnsupportedOperationException("Default stub — Tool '{name}' not implemented in US-2")` |
| `toolName` 不在 `profile.tools()` | 返回 `ToolResult.error("tool not in profile: <name>")` |
| 无论哪种情况 | 写入一行 `ToolInvocationRecord`（stub 也写——保证 day-one 表存在 + 路径可测） |

US-4 替换为真实实现：解析 `arguments`、调用 `OryxTool.execute(...)`、捕获 unchecked 异常、返回 `ToolResult`。

---

## 5. 调用者（`ReActLoop`）用法约定

```java
// pseudocode
for (ToolCall tc : r.toolCalls()) {
    ToolResult tr = toolExecutor.invoke(tc.name(), tc.arguments(), profile);
    session.appendMessage(Message.toolResult(tc.id(), tc.name(), tr));
    // 失败不抛异常，由 LLM 决定下一步（spec FR-011）
}
```

- 调用方**禁止**在循环内 catch `ToolExecutor` 抛出的异常——`ToolExecutor` 必须把所有失败转成 `ToolResult.error(...)`。
- 调用方**禁止**抛异常退出循环——必须把所有异常路径转成 `tool` 消息（spec Edge case 2 + 3）。

---

## 6. 测试义务

| 测试类 | 断言 |
|--------|------|
| `DefaultToolExecutorTest#refusedToolReturnsError` | 不在白名单时返回 `success=false`，错误文本匹配 `"tool not in profile"` |
| `DefaultToolExecutorTest#refusedToolWritesAudit` | 调用后 `ToolInvocationRecordRepository.count()` 增长 1 且 `success=false` |
| `DefaultToolExecutorTest#allowedToolWritesAudit` | 抛 `UnsupportedOperationException` 后审计行被写入 |
| `ProfileContextTest#sessionIterationCaptured` | 调用前设置 `sessionIteration=3`，调用后 `ToolInvocationRecord.session_iteration == 3` |
