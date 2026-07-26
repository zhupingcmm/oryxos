# 数据模型：Tool 体系（Agent 的"双手"）

**目的**：定义 Tool spec 涉及的所有持久化实体与运行时数据结构，标注新增字段与 schema 演进风险。
**创建日期**：2026-07-26
**特性**：[spec.md](./spec.md) | [research.md](./research.md) | [plan.md](./plan.md)
**前置**：[specs/002-react-loop/data-model.md](../../002-react-loop/data-model.md) | [specs/004-notify-channel/data-model.md](../004-notify-channel/data-model.md) | [CLAUDE.md §13](../../CLAUDE.md)

> **与既有 data-model 的关系**：本文件聚焦本 spec 涉及的**新增**实体与 schema 演进；Notify 相关实体（`NotifyChannelConfig` / `NotifyResult` / `NotifyTool`）已在 [004-notify-channel/data-model.md](../004-notify-channel/data-model.md) 落地，**不**重复。本 spec 在 §7 给出 V3 DDL 唯一新增的 schema 变更。

---

## 1. 实体总览

| 实体 | 类型 | 生命周期 | 模块归属 |
|------|------|---------|---------|
| `OryxTool`（interface） | JDK 21 接口 | Spring bean | `oryxos-core`（已落地） |
| `ToolRegistration` | record（不可变） | Spring bean 注册期 | `oryxos-core`（已落地） |
| `ToolDefinition` | record（不可变） | CLI 展示 + schema 生成 | `oryxos-core`（已落地） |
| `ToolResult` | record（不可变） | 单次 Tool 调用返回值 | `oryxos-core`（已落地） |
| `SandboxAction` / `ActionType` / `SandboxViolationException` | record / enum / exception | 调用期 | `oryxos-tool/sandbox/`（已落地） |
| `FileToolResult` / `HttpToolResult` / `ShellToolResult` / `MemoryToolResult` | record（不可变） | 单次 Tool 调用返回值 | `oryxos-tool/{file,http,shell,memory}/`（[NEW]） |
| `McpServerConnection` | record（不可变） | MCP server 连接状态 | `oryxos-tool/mcp/`（[NEW]） |
| `McpTool` | class（闭包） | Spring bean 注册期 | `oryxos-tool/mcp/`（[NEW]） |
| `tool_invocations`（DB 表） | JPA entity（已有，**本 spec 加 1 列**） | 持久化 | `oryxos-storage` |

---

## 2. `OryxTool` 接口契约（已落地，引用）

**定义位置**：[`io.oryxos.core.OryxTool`](../../oryxos-core/src/main/java/io/oryxos/core/OryxTool.java)

```java
public interface OryxTool {
    String name();
    default String description() { return ""; }     // 新增（spec FR-001）
    ToolResult execute(Map<String, Object> arguments);
}
```

**三个内置 Tool 的 name 约定**（spec FR-003）：

| Tool 名 | 类 | description（建议默认值） |
|---------|----|------------------------|
| `file_read` | `FileReadTool` | 读取本地文本文件内容 |
| `file_write` | `FileWriteTool` | 写入本地文本文件 |
| `file_list` | `FileListTool` | 列出目录下条目 |
| `shell` | `ShellTool` | 在受限白名单内执行 shell 命令 |
| `http_get` | `HttpGetTool` | 发起 HTTP GET 请求（受沙箱校验） |
| `http_post` | `HttpPostTool` | 发起 HTTP POST 请求（受沙箱校验） |
| `notify` | `NotifyTool`（已落地） | 向已配置的群机器人 webhook 推送消息 |
| `save_memory` | `SaveMemoryTool` | 写入长期记忆 |
| `recall_memory` | `RecallMemoryTool` | 按关键词检索长期记忆 |

> **唯一性约束**（[research.md R-08](./research.md)）：所有 Tool 的 `name()` 在同一进程内必须唯一；冲突时 `ToolRegistry.of()` 抛 `IllegalStateException`，Spring Boot 启动失败（spec FR-015）。

---

## 3. 内置 Tool 返回值 record（[NEW]）

### 3.1 `FileToolResult`

**包路径**：`io.oryxos.tool.file.FileToolResult`

```java
public record FileToolResult(
    String path,           // 实际访问的路径（解析后的绝对路径）
    long sizeBytes,        // 字节数（read 模式为内容长度；list 模式为目录字节数或 null）
    String content,        // 文本内容（list 模式为 null）
    List<String> entries   // 目录条目（list 模式非空；read 模式为 null）
) { }
```

### 3.2 `ShellToolResult`

**包路径**：`io.oryxos.tool.shell.ShellToolResult`

```java
public record ShellToolResult(
    String command,        // 执行的命令字符串（便于审计 / 调试）
    int exitCode,          // 进程退出码
    String stdout,         // 标准输出（截断到 max-output-bytes）
    String stderr,         // 标准错误（截断到 max-output-bytes）
    long durationMs        // 进程执行耗时
) { }
```

### 3.3 `HttpToolResult`

**包路径**：`io.oryxos.tool.http.HttpToolResult`

```java
public record HttpToolResult(
    int statusCode,        // HTTP 状态码
    String contentType,    // 响应 Content-Type
    String body,           // 响应 body（截断到 max-response-bytes）
    long durationMs        // 请求耗时
) { }
```

### 3.4 `MemoryToolResult`

**包路径**：`io.oryxos.tool.memory.MemoryToolResult`

```java
public record MemoryToolResult(
    String operation,      // "save" 或 "recall"
    String scope,          // "core" / "archive"
    int entryCount,        // recall 时为命中条数；save 时为 1
    List<String> snippets  // recall 时为命中条目的截断内容；save 时为 null
) { }
```

### 3.5 不入库约束

`FileToolResult` / `ShellToolResult` / `HttpToolResult` / `MemoryToolResult` 均**不**入库；它们通过 `ToolResult.payload()` 透传给 LLM（与既有 `NotifyResult` 模式一致，[004-notify-channel/data-model.md §3](../004-notify-channel/data-model.md)）。审计信息（成功 / 失败 / 耗时）统一收口到 `tool_invocations` 表。

---

## 4. MCP 子系统实体（[NEW]）

### 4.1 `McpServerConnection`

**包路径**：`io.oryxos.tool.mcp.McpServerConnection`

```java
public record McpServerConnection(
    String name,                   // server 名（与 mcp_servers.yaml 一致）
    String transport,              // "http" / "stdio"
    String endpoint,               // HTTP URL 或 stdio command
    Map<String, Object> capabilities,  // initialize 响应里的 serverCapabilities
    List<String> toolNames,        // tools/list 返回的所有 tool 名
    ConnectionState state          // CONNECTED / DISCONNECTED / FAILED
) {
    public enum ConnectionState { CONNECTED, DISCONNECTED, FAILED }
}
```

**生命周期**：Spring bean 启动期由 `McpClientService.startup()` 创建；运行期不变（核心阶段不重连，[research.md R-10](./research.md)）。

### 4.2 `McpTool`（OryxTool 实现）

**包路径**：`io.oryxos.tool.mcp.McpTool`

```java
public class McpTool implements OryxTool {
    private final String name;
    private final String description;
    private final String inputSchema;       // MCP server 给出的 JSON Schema 字符串
    private final McpTransport transport;   // 持有 transport 实例，闭包式调用
    private final String serverName;        // 用于错误信息 / 审计

    public McpTool(String name, String description, String inputSchema,
                   McpTransport transport, String serverName) { ... }

    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public ToolResult execute(Map<String, Object> arguments) {
        // 把 arguments 包装成 MCP tools/call 请求；
        // 调 transport.sendRequest("tools/call", {name, arguments})；
        // 解析响应返回 ToolResult
    }
}
```

**关键设计**：

1. **不持有 Profile**——MCP Tool 不需要 Profile-level 配置（[research.md R-07](./research.md)）
2. **transport 共享**——同一 server 的所有 tool 共享一个 `McpTransport` 实例（HTTP 连接池 / stdio 进程单例）
3. **`source` 列自动填 `mcp`**——`resolveSource()` 根据类名前缀 `io.oryxos.tool.mcp.` 判定（[research.md R-06](./research.md)）

### 4.3 `McpTransport` 接口

**包路径**：`io.oryxos.tool.mcp.McpTransport`

```java
public interface McpTransport extends AutoCloseable {
    /** 发送 JSON-RPC 请求，返回响应内容。失败抛 {@link McpConnectionException}。 */
    McpResponse sendRequest(String method, Map<String, Object> params);

    /** 关闭连接。 */
    @Override
    void close();
}

public record McpResponse(int id, Map<String, Object> result, Map<String, Object> error) {
    public boolean isError() { return error != null; }
}

public class McpConnectionException extends RuntimeException {
    private final String serverName;
    public McpConnectionException(String serverName, String message, Throwable cause) { ... }
    public String serverName() { return serverName; }
}
```

**两种实现**：

| 类 | 用途 | 关键点 |
|----|------|--------|
| `HttpMcpTransport` | HTTP/SSE 模式 | 复用 `HttpClient`；`POST` JSON-RPC 请求 + `text/event-stream` 接收响应 |
| `StdioMcpTransport` | stdio 模式 | `ProcessBuilder` 启子进程；stdin 写请求 / stdout 读响应（按行） |

---

## 5. 配置 Properties（[NEW]）

### 5.1 `HttpToolProperties`

**包路径**：`io.oryxos.tool.http.HttpToolProperties`

```java
@ConfigurationProperties(prefix = "oryxos.tool.http")
public record HttpToolProperties(
    int timeoutSeconds,           // 默认 5
    int maxResponseBytes          // 默认 1_048_576（1 MB）
) { }
```

### 5.2 `ShellToolProperties`

**包路径**：`io.oryxos.tool.shell.ShellToolProperties`

```java
@ConfigurationProperties(prefix = "oryxos.tool.shell")
public record ShellToolProperties(
    int timeoutSeconds,           // 默认 30
    int maxOutputBytes,           // 默认 65_536（64 KB）
    List<String> dangerousCommands  // 见 [research.md R-03](./research.md)
) { }
```

### 5.3 `McpClientProperties`

**包路径**：`io.oryxos.tool.mcp.McpClientProperties`

```java
@ConfigurationProperties(prefix = "oryxos.tool.mcp")
public record McpClientProperties(
    int connectTimeoutSeconds,    // 默认 5
    int requestTimeoutSeconds,    // 默认 30
    boolean failFastOnStartup     // 默认 true（spec US-3 场景 3）
) { }
```

---

## 6. ToolRegistry 冲突检测（修改）

### 6.1 当前行为（004 阶段）

[ToolRegistry.java](../../oryxos-core/src/main/java/io/oryxos/core/tool/ToolRegistry.java) 第 47-59 行的 `of()` 方法在 key 冲突时**静默覆盖**（"后注册赢"）。

### 6.2 本 spec 修改（spec FR-015）

```java
public static ToolRegistry of(Map<String, ToolRegistration> registrations) {
    Map<String, ToolRegistration> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, ToolRegistration> e : registrations.entrySet()) {
        ToolRegistration reg = e.getValue();
        if (reg == null) continue;
        String key = reg.definition().name();
        if (normalized.containsKey(key)) {
            ToolRegistration existing = normalized.get(key);
            throw new IllegalStateException(String.format(
                "Tool name conflict: '%s' registered by both %s and %s",
                key,
                existing.tool().getClass().getName(),
                reg.tool().getClass().getName()));
        }
        normalized.put(key, reg);
    }
    return new ToolRegistry(normalized);
}
```

**测试**：`ToolRegistryTest.conflict_fails_at_construction()` — 两个 Tool 都用 `name="echo"` 时 `of()` 抛 `IllegalStateException`，测试断言异常消息包含两个类名。

---

## 7. `tool_invocations` 表 schema 演进

### 7.1 当前 schema（V2 之后）

```sql
CREATE TABLE tool_invocations (
  id                  TEXT    PRIMARY KEY,
  session_id          TEXT,
  profile_name        TEXT    NOT NULL,
  tool_name           TEXT    NOT NULL,
  arguments           TEXT,             -- JSON
  success             INTEGER NOT NULL,  -- 0/1
  error_message       TEXT,
  duration_ms         INTEGER NOT NULL,
  started_at          TEXT    NOT NULL,  -- ISO-8601
  session_iteration   INTEGER NOT NULL,
  channel             TEXT,             -- V2: notify 专用
  notify_status_code  INTEGER           -- V2: notify 专用
);
```

### 7.2 V3 新增列（本 spec）

```sql
-- V3__add_tool_source.sql
-- 新增 1 列到 tool_invocations：
--   source             TEXT      NOT NULL DEFAULT 'builtin'
--                                 — 区分 builtin / mcp / java_bean 三类 Tool
--                                 — 详见 spec FR-005 / research.md R-06

ALTER TABLE tool_invocations ADD COLUMN source TEXT NOT NULL DEFAULT 'builtin';

-- 索引：审计员按 source 维度过滤
CREATE INDEX IF NOT EXISTS idx_tool_source ON tool_invocations(tool_name, source, started_at);

-- 回滚：
-- DROP INDEX IF EXISTS idx_tool_source;
-- ALTER TABLE tool_invocations DROP COLUMN source;
```

### 7.3 JPA entity 改动

[ToolInvocationRecord.java](../../oryxos-storage/src/main/java/io/oryxos/storage/entity/ToolInvocationRecord.java) 新增字段：

```java
// 在现有字段后追加（data-model §7.2）
/** Tool 来源：builtin / mcp / java_bean（V3 DDL；详 research.md R-06）。 */
@Column(name = "source", nullable = false, columnDefinition = "TEXT")
private String source;
```

**构造器签名扩展**：

```java
public ToolInvocationRecord(UUID id, UUID sessionId, String profileName, String toolName,
                            Map<String, Object> arguments, boolean success, String errorMessage,
                            long durationMs, Instant startedAt, int sessionIteration,
                            String channel, Integer notifyStatusCode,
                            String source) {       // ← 新增
    // ...
    this.source = source;
    validate();
}
```

**`validate()` 新增检查**：

```java
if (source == null || (!source.equals("builtin") && !source.equals("mcp") && !source.equals("java_bean"))) {
    throw new IllegalArgumentException(
        "source must be one of builtin/mcp/java_bean, got: " + source);
}
```

**既有调用方兼容**：US-2 stub / 单测 fixture 显式传 `"builtin"`；新行由 `DefaultToolExecutor` 自动推导。

### 7.4 风险与缓解

[CLAUDE.md §13](../../CLAUDE.md) 明确 SQLite `ALTER TABLE` 能力有限，`hibernate.ddl-auto=update` 对新增列支持不可靠。

**缓解方案**：

1. **手动写 DDL 脚本** `oryxos-storage/src/main/resources/db/migration/V3__add_tool_source.sql`（已在本节给出）
2. **JPA entity 同步更新**：`ToolInvocationRecord.java` 加 `@Column source`
3. **`hibernate.ddl-auto=update` 仅作为开发环境的 fallback**；生产路径必须显式执行 V3 DDL
4. **回滚方案**：保留 V3 DDL 的 DOWN 版本；测试用临时 SQLite 文件验证
5. **NOT NULL DEFAULT**：V3 给新列加 `NOT NULL DEFAULT 'builtin'`，历史行自动填 `'builtin'`（notify 历史行不准确，但 V3 之后新行由 `DefaultToolExecutor` 显式填真实 source）

---

## 8. 实体关系图（简化）

```text
Profile (1) ──< tools[Tool name 列表] >── (LLM 视角的可见 Tool 集合)
                                              │
                                              │ (运行时查 ToolRegistry)
                                              ▼
ToolRegistry ──< registrations >── ToolRegistration (1 per name)
                                       │
                                       ├── definition: ToolDefinition (CLI 元数据)
                                       └── tool: OryxTool (执行实现)
                                                    │
                            ┌───────────────────────┼───────────────────────────┐
                            ▼                       ▼                           ▼
                  FileReadTool / ShellTool /  NotifyTool (已落地)        McpTool (NEW)
                  HttpTool (NEW) / MemoryTool                            ↑
                  (内置 builtin)                                       │
                                                                       McpTransport (闭包)
                                                                       ├── HttpMcpTransport
                                                                       └── StdioMcpTransport
                                                                       
tool_invocations 行 ─── tool_name 列 (TEXT)
                  ├── source 列 (TEXT, V3 新增)         ← builtin / mcp / java_bean
                  ├── channel 列 (TEXT, V2)             ← 仅 notify
                  └── notify_status_code 列 (INTEGER)  ← 仅 notify
                  
DefaultToolExecutor.invoke(name, args, profile)
  → ToolRegistry.find(name)
  → OryxTool.execute(args)
    ├── Sandbox.enforce(SandboxAction(type, target))    ← FR-004
    └── ToolResult 透传给 LLM
  → ToolAuditWriter.record(source, success, duration_ms, ...)  ← 写 tool_invocations 行
```

---

## 9. 兼容性总结

| 现有实体 | 影响 | 处理 |
|---------|------|------|
| `OryxTool` | 无 | — |
| `ToolRegistry` | 修改 `of()` 行为：冲突从"静默覆盖"改为"fail-fast" | 既有测试 fixture 如有两个同名 Tool 会触发新异常，需修复 |
| `ToolRegistration` / `ToolDefinition` | 无 | — |
| `ToolResult` | 无 | — |
| `SandboxAction` / `ActionType` / `SandboxViolationException` / `WhitelistSandbox` | 无（接口不变；扩展阶段扩展实现） | — |
| `ToolInvocationRecord` | 新增 `source` 列 + 构造器参数 | 既有调用方显式传 `"builtin"`；新增 1 个 `@Column` |
| `tool_invocations` 表 | 新增 1 列 `source` + 1 个新索引 | V3 DDL 手动执行；JPA entity 同步 |

---

## 10. 待 tasks.md 阶段落地的具体 DDL

```sql
-- V3__add_tool_source.sql（已确定）

ALTER TABLE tool_invocations ADD COLUMN source TEXT NOT NULL DEFAULT 'builtin';

CREATE INDEX IF NOT EXISTS idx_tool_source ON tool_invocations(tool_name, source, started_at);

-- 回滚
-- DROP INDEX IF EXISTS idx_tool_source;
-- ALTER TABLE tool_invocations DROP COLUMN source;
```

不引入 NOT NULL CHECK 约束（保留历史行 NULL 合法性的兜底）。
