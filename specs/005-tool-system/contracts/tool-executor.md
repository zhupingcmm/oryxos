# 契约：DefaultToolExecutor（派发 + 审计）

**目的**：定义 Tool 调用的统一入口契约 —— `DefaultToolExecutor.invoke()` 的派发顺序、审计语义、失败处理。这是 ReAct 主循环与 Tool 实现之间的"中间层"。
**创建日期**：2026-07-26
**特性**：[spec.md §FR-005 / §FR-007 / §FR-011 / §FR-012](../spec.md) | [research.md R-09](./../research.md)
**前置**：[DefaultToolExecutor.java](../../../oryxos-core/src/main/java/io/oryxos/core/DefaultToolExecutor.java) | [ToolRegistry.java](../../../oryxos-core/src/main/java/io/oryxos/core/tool/ToolRegistry.java) | [ToolAuditWriter.java](../../../oryxos-core/src/main/java/io/oryxos/core/ToolAuditWriter.java)

---

## 1. 入口签名

```java
public interface ToolExecutor {
    /**
     * 调用一个 Tool。
     *
     * @param toolName  Tool 名（与 {@code OryxTool.name()} 一致；与 Profile.tools[] 元素一致）
     * @param arguments  LLM 解析后的参数 map
     * @param profile   当前 Profile（用于权限过滤）
     * @return  ToolResult；异常一律包成 {@code ToolResult.error}，不抛 RuntimeException
     */
    ToolResult invoke(String toolName, Map<String, Object> arguments, Profile profile);
}
```

---

## 2. 派发顺序（5 步）

`DefaultToolExecutor.invoke()` 严格按以下顺序处理：

```text
┌─────────────────────────────────────────────────────┐
│ Step 1: 白名单检查（Profile.tools[] 包含 toolName?） │
│   └─ 不在 → ToolResult.error("tool not in profile")  │
│      + 审计行 success=false                           │
└─────────────────────────────────────────────────────┘
                          │ 在
                          ▼
┌─────────────────────────────────────────────────────┐
│ Step 2: ToolRegistry 查找（find(toolName)）          │
│   └─ 未注册 → ToolResult.error("tool not registered")│
│      + 审计行 success=false                           │
└─────────────────────────────────────────────────────┘
                          │ 已注册
                          ▼
┌─────────────────────────────────────────────────────┐
│ Step 3: resolveSource(tool) → builtin/mcp/java_bean  │
└─────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────┐
│ Step 4: 调用 tool.execute(arguments)                  │
│   ├─ 正常返回 → ToolResult 透传                       │
│   └─ 抛 RuntimeException → 包成 ToolResult.error     │
└─────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────┐
│ Step 5: 写审计行（success / errorMessage / duration / │
│              source / [notify] channel+status_code）   │
└─────────────────────────────────────────────────────┘
                          │
                          ▼
                    返回 ToolResult
```

---

## 3. 各 Step 详细语义

### 3.1 Step 1 — 白名单检查

**判定**：`profile.tools().contains(toolName)`

**通过**：进入 Step 2。

**失败**：
- `durationMs = 当前时间 - startedAtNanos`（含检查本身的耗时）
- `writeAudit(profile, toolName, arguments, success=false, errorMessage="tool not in profile: <name>", ..., source="builtin", channel=null, notifyStatusCode=null)`
- 返回 `ToolResult.error("tool not in profile: <name>")`

**理由**（spec FR-011）：即使 Tool 在 `ToolRegistry` 注册了，Profile 不允许用就不该被 LLM 看到；PromptBuilder 已经在生成 system prompt 时过滤，Step 1 是 belt-and-suspenders。

### 3.2 Step 2 — ToolRegistry 查找

**判定**：`toolRegistry.find(toolName)` 返回 `Optional<OryxTool>`

**通过**：进入 Step 3。

**失败**：
- 写审计 + 返回 `ToolResult.error("tool not registered: <name>")`

**Stale US-2 行为**：如果 `toolRegistry == null`（构造期未注入），保留 US-2 stub 语义（抛 `UnsupportedOperationException`，保留旧测试兼容）。US-4 起 Spring 必注入 `ToolRegistry`，所以生产路径不会到这里。

### 3.3 Step 3 — resolveSource

```java
private static String resolveSource(OryxTool tool) {
    String cn = tool.getClass().getName();
    if (cn.startsWith("io.oryxos.tool.mcp.")) return "mcp";
    if (cn.startsWith("io.oryxos.tool."))     return "builtin";
    return "java_bean";
}
```

**优先级**：`mcp` > `builtin` > `java_bean`（基于类路径前缀）

**不在审计**：`source` 不是 ToolResult 字段，而是审计行（`tool_invocations.source` 列）的字段。

### 3.4 Step 4 — 派发 + 异常处理

```java
ToolResult result;
try {
    result = tool.execute(arguments);
} catch (RuntimeException ex) {
    String message = "tool execution failed: " + ex.getMessage();
    writeAudit(profile, toolName, arguments, false, message, durationMs,
               startedAt, iteration, source, null, null);
    log.warn("tool.execute.failed profile={} tool={} error={}",
        profile.name(), toolName, ex.getMessage());
    return ToolResult.error(message);
}
```

**关键**：
- **`message` 不含 stack trace**（spec NFR-004）—— 只有异常 message
- **`stack trace` 进 log**（SLF4J `log.warn`）—— `.oryxos/logs/oryxos-cli-error.log`
- **不重新抛**（spec FR-012）—— ReAct 主循环不会被中断

### 3.5 Step 5 — 审计写入

**写入时机**：在 `invoke()` 返回给 LLM **之前**（C-TE-9）。

**审计字段**：

| 字段 | 来源 | 备注 |
|------|------|------|
| `id` | `UUID.randomUUID()` | 主键 |
| `session_id` | `ProfileContext.current().sessionId()` | 可空（CLI 直调无 session） |
| `profile_name` | `profile.name()` | |
| `tool_name` | `toolName` | |
| `arguments` | `arguments` 参数 | 序列化为 JSON |
| `success` | `result.success()` | |
| `error_message` | `result.errorMessage()` 或异常 message | 成功时为 null |
| `duration_ms` | `elapsedMs(startedAtNanos)` | |
| `started_at` | `Instant.now()` | 调用开始时间 |
| `session_iteration` | `ProfileContext.current().currentIteration()` | |
| `channel` | NotifyTool payload.channel | 仅 notify；其他为 null |
| `notify_status_code` | NotifyTool payload.status_code | 仅 notify；其他为 null |
| `source` | `resolveSource(tool)` | V3 新增（[data-model §7](../data-model.md)） |

**`writeAudit` 失败处理**：

```java
private void writeAudit(...) {
    try {
        auditWriter.record(...);
    } catch (RuntimeException ex) {
        // C-TE-9：审计写入失败不阻塞主流程
        log.warn("tool.audit.failed profile={} tool={} error={}",
            profile.name(), toolName, ex.getMessage());
    }
}
```

**理由**：审计系统异常不应让 Tool 调用失败；记录到 log 即可。

---

## 4. ToolResult 透传语义

### 4.1 Tool 返回成功

```java
// Tool 实现里
return ToolResult.ok("hello", Map.of("status_code", 200));

// DefaultToolExecutor 透传
return ToolResult.ok("hello", Map.of("status_code", 200));
```

LLM 下一轮看到 `success=true, content="hello", payload={"status_code": 200}`。

### 4.2 Tool 返回失败

```java
// Tool 实现里
return ToolResult.error("network unreachable");

// DefaultToolExecutor 透传
return ToolResult.error("network unreachable");
```

LLM 下一轮看到 `success=false, errorMessage="network unreachable"`。

### 4.3 Tool 抛 RuntimeException

```java
// Tool 实现里
throw new RuntimeException("kaboom");

// DefaultToolExecutor 捕获 + 包装
return ToolResult.error("tool execution failed: kaboom");
// stack trace 进 log
```

LLM 下一轮看到 `success=false, errorMessage="tool execution failed: kaboom"`（**不**含 stack trace，spec NFR-004）。

---

## 5. 审计不变量（Invariants）

- **I-TE-1**：每次 `invoke()` 调用 MUST 产生**恰好一行** `tool_invocations` 审计行（不论成功 / 失败 / 异常 / 白名单拒绝 / 未注册）。
- **I-TE-2**：审计行在 `invoke()` 返回给 LLM **之前**写入。
- **I-TE-3**：审计行的 `error_message` 与 ToolResult 的 `error_message` 内容一致（成功时均为 null）。
- **I-TE-4**：审计行的 `source` 列 = `resolveSource(tool)` 的返回值。
- **I-TE-5**：审计写入失败 MUST NOT 抛异常给调用方；仅记 log（不影响 ReAct 主循环）。

---

## 6. 性能特性

### 6.1 Wall-time（spec NFR-001）

- 单条 Tool 调用（不含沙箱校验本身）：P95 ≤ 30 秒
- **DefaultToolExecutor 开销**：< 1ms（仅 JDK 反射 + audit write）
- HTTP Tool：受 `HttpToolProperties.timeout-seconds: 5` 控制
- Shell Tool：受 `ShellToolProperties.timeout-seconds: 30` 控制

### 6.2 并发模型

- 同一 `DefaultToolExecutor` 实例可被多线程调用（ReAct 主循环同步派发，但 Tool 副作用在虚拟线程内）
- `ToolAuditWriter` 必须是线程安全（SQL 写入由 SQLite 驱动保证）
- `ToolRegistry` 是只读，无并发问题

---

## 7. 不在本契约范围

- ❌ Function Calling schema 生成（`ToolSchemaProvider` 的事）
- ❌ Tool 白名单的 Profile YAML 解析（`ConfigLoader` 的事，[specs/003-cli-commands](../003-cli-commands/spec.md)）
- ❌ Spring bean 装配路径（`oryxos-boot` 的事，[plan.md §4](../plan.md)）

---

## 8. 测试矩阵

| 测试 | 期望 |
|------|------|
| `invoke_builtin_tool_writes_audit` | 调用 `http_get`，1 行审计，`source='builtin'` |
| `invoke_mcp_tool_writes_audit` | 调用 MCP Tool，1 行审计，`source='mcp'` |
| `invoke_java_bean_writes_audit` | 调用自定义 Tool，1 行审计，`source='java_bean'` |
| `tool_not_in_profile` | ToolResult.error，审计 success=false |
| `tool_not_registered` | ToolResult.error，审计 success=false |
| `tool_throws_runtime_exception` | ToolResult.error("tool execution failed: ...")，审计 success=false，error_message 与 ToolResult 一致 |
| `audit_write_fails` | ToolResult 仍正常返回；log.warn 一行 |
| `notify_tool_extracts_channel` | audit 行 `channel='feishu', notify_status_code=200` |
| `duplicate_tool_name_fails_at_construction` | ToolRegistry.of 抛 IllegalStateException（spec FR-015）|
