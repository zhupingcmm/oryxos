# 契约：McpClientService + McpToolAdapter（MCP 接入层）

**目的**：定义 MCP（Model Context Protocol）客户端的接入契约 —— 从 `mcp_servers.yaml` 读 server 列表、握手 JSON-RPC、把 server 暴露的 tools 转成 `OryxTool` 实例。这是核心阶段"零代码 + 轻代码"接入路径的核心实现。
**创建日期**：2026-07-26
**特性**：[spec.md §FR-008 / §FR-009 / §US-3](../spec.md) | [research.md R-04 / R-10](./../research.md)
**前置**：[CLAUDE.md §V 三档接入](../../../CLAUDE.md) | [tool-executor.md](./tool-executor.md) | [oryx-tool.md](./oryx-tool.md)

---

## 1. 设计目标

MCP 是 [Model Context Protocol](https://modelcontextprotocol.io/) —— Anthropic 推出的"LLM 工具调用标准化协议"。MCP server 通过 JSON-RPC 暴露 tools / resources / prompts；客户端（OryxOS）只需要：

1. **握手** `initialize` → 拿到 server capabilities
2. **列工具** `tools/list` → 拿到该 server 暴露的 tool 列表 + JSON Schema
3. **调用工具** `tools/call` → 传 arguments，收到结果

`McpClientService` + `McpToolAdapter` 把这三步转成 `OryxTool` 注册到 `ToolRegistry`，LLM 通过 Function Calling 调用时**不感知** MCP 协议。

---

## 2. 包结构（[NEW]）

```text
oryxos-tool/mcp/
├── McpClientService.java           # 启动期握手 + 维护 connection map
├── McpClientProperties.java        # @ConfigurationProperties
├── McpServerConnection.java        # record（server 名 + transport + 连接状态）
├── McpTransport.java               # interface（sendRequest, close）
├── HttpMcpTransport.java           # HTTP/SSE 实现
├── StdioMcpTransport.java          # stdio 子进程实现
├── McpToolAdapter.java             # 把 McpToolDescriptor 转成 OryxTool
└── McpTool.java                    # 单个 MCP tool 的 OryxTool 实现
```

---

## 3. `mcp_servers.yaml` 配置契约

```yaml
# .oryxos/mcp_servers.yaml
servers:
  - name: github-mcp                  # 全局唯一，与 ToolRegistry.name() 一致
    transport: stdio                  # 或 http
    command: uvx mcp-server-github    # stdio 模式（command + args）
    args:
      - --repo
      - oryxos/oryxos
    env:                              # 可选，仅注入到子进程的环境变量
      GITHUB_TOKEN: ${GITHUB_TOKEN}

  - name: weather-mcp
    transport: http                   # HTTP/SSE 模式
    url: http://localhost:8081/sse    # JSON-RPC over HTTP/SSE endpoint
    auth-token: ${MCP_WEATHER_TOKEN}  # 可选，注入 Authorization: Bearer
```

**YAML 解析**：`McpClientService.startup()` 启动期由 [ConfigLoader](../../specs/003-cli-commands/spec.md) 解析为 `List<McpServerConfig>`。

---

## 4. `McpTransport` 接口契约

```java
package io.oryxos.tool.mcp;

public interface McpTransport extends AutoCloseable {
    /**
     * 发送 JSON-RPC 2.0 请求。
     *
     * @param method  方法名（如 "initialize", "tools/list", "tools/call"）
     * @param params  参数（null = 无 params；或 Map<String, Object>）
     * @return 响应（result 或 error）
     * @throws McpConnectionException  连接失败 / 超时 / 协议错误
     */
    McpResponse sendRequest(String method, Map<String, Object> params);

    @Override
    void close();
}

public record McpResponse(int id, Map<String, Object> result, Map<String, Object> error) {
    public boolean isError() { return error != null; }
    public String errorMessage() {
        return error == null ? null : (String) error.getOrDefault("message", "unknown mcp error");
    }
}

public class McpConnectionException extends RuntimeException {
    private final String serverName;
    public McpConnectionException(String serverName, String message, Throwable cause) { ... }
}
```

**两种实现**：

| 实现 | Transport | 关键点 |
|------|-----------|--------|
| `HttpMcpTransport` | HTTP POST + SSE | 复用 JDK `HttpClient`；`POST` 发送 JSON-RPC 请求，`Accept: text/event-stream` 接收响应 |
| `StdioMcpTransport` | 子进程 stdio | `ProcessBuilder` 启子进程；stdin 写 JSON-RPC 行（每行一个请求 + Content-Length 头），stdout 按行读 |

**HTTP/SSE 协议简化**：

```text
# 请求
POST /sse HTTP/1.1
Content-Type: application/json
Accept: text/event-stream

{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}

# 响应（多行）
event: message
data: {"jsonrpc":"2.0","id":1,"result":{"tools":[...]}}

```

---

## 5. `McpClientService` 启动期契约

### 5.1 入口

```java
@Component
public class McpClientService {
    private final Map<String, McpServerConnection> connections = new ConcurrentHashMap<>();
    private final McpToolAdapter toolAdapter;

    @PostConstruct  // Spring Boot 启动期执行
    public void startup() {
        // 1. 读 mcp_servers.yaml
        // 2. 对每个 server 建 transport
        // 3. 调 initialize
        // 4. 调 tools/list
        // 5. 用 McpToolAdapter 转 McpTool
        // 6. 把 McpTool 注册到 ToolRegistry
    }
}
```

### 5.2 `startup()` 详细流程

```text
┌─────────────────────────────────────────────────────────┐
│ Step 1: 读 mcp_servers.yaml → List<McpServerConfig>      │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│ Step 2: 对每个 server 建 transport                       │
│   ├─ transport=stdio → StdioMcpTransport(command, args)│
│   └─ transport=http → HttpMcpTransport(url, token?)     │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│ Step 3: sendRequest("initialize",                       │
│                       {protocolVersion, capabilities})   │
│   └─ 失败 → 抛 McpConnectionException                    │
│              → Spring Boot 启动失败（spec US-3 场景 3）  │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│ Step 4: sendRequest("tools/list", null)                  │
│   └─ 返回 List<McpToolDescriptor>                       │
│      {name, description, inputSchema}                   │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│ Step 5: 用 McpToolAdapter 把每个 descriptor              │
│         转成 McpTool（implements OryxTool）              │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│ Step 6: 把 McpTool 注册到 ToolRegistry                   │
│   （经 ToolRegistry.find() / ToolSystemConfig 装配）     │
└─────────────────────────────────────────────────────────┘
```

### 5.3 启动期 fail-fast

**任一 server 连接失败 → Spring Boot 启动失败**（[research.md R-10](./../research.md) + spec US-3 场景 3）：

```java
// McpClientService.startup()
for (McpServerConfig cfg : configs) {
    try {
        McpTransport transport = createTransport(cfg);
        Map<String, Object> initResult = transport.sendRequest("initialize", Map.of(
            "protocolVersion", "2024-11-05",
            "capabilities", Map.of()
        )).result();
        // ...
    } catch (McpConnectionException | RuntimeException ex) {
        throw new IllegalStateException(
            "MCP server startup failed: " + cfg.name() + " (" + ex.getMessage() + ")", ex);
    }
}
```

**理由**：

- 配置错（URL 不可达 / command 不存在）→ 运营者立即看到，不进入生产
- 避免懒连接的首调用延迟
- 与宪法 §VII "Demo-First / fail-fast" 一致

### 5.4 运行期不重连

**核心阶段**：server 运行期挂掉 → `McpConnectionException` → `ToolResult.success=false`（spec US-3 场景 4）。

**不实现**：心跳 / 指数退避 / 死信队列 —— 这些是扩展阶段的 "Tool-as-a-Service" 抽象（spec 假设 6 推迟项）。

---

## 6. `McpToolAdapter` 契约

```java
@Component
public class McpToolAdapter {
    /**
     * 把 MCP server 的 tools/list 响应转成 OryxTool 实例列表。
     */
    public List<OryxTool> adapt(
        String serverName,
        List<McpToolDescriptor> descriptors,
        McpTransport transport
    ) {
        return descriptors.stream()
            .map(d -> new McpTool(serverName, d.name(), d.description(), d.inputSchema(), transport))
            .map(t -> (OryxTool) t)
            .toList();
    }
}

public record McpToolDescriptor(
    String name,           // server 定义的 tool 名
    String description,    // server 提供的 description
    String inputSchema     // JSON Schema 字符串（Function Calling schema 兼容）
) { }
```

**关键设计**：

- **McpToolAdapter 无状态**（只是 builder 角色）
- **Transport 共享**：同一 server 的所有 tool 共享一个 `McpTransport` 实例（HTTP 连接池复用 / stdio 进程单例）
- **Tool 名 = server 定义原名**（如 `list_pull_requests`），**不**强制前缀命名空间
- **类路径前缀** `io.oryxos.tool.mcp.` 触发 `source=mcp`（[research.md R-06](./../research.md)）

---

## 7. `McpTool` 契约

```java
package io.oryxos.tool.mcp;

public class McpTool implements OryxTool {
    private final String serverName;
    private final String name;
    private final String description;
    private final String inputSchema;
    private final McpTransport transport;

    public McpTool(String serverName, String name, String description,
                   String inputSchema, McpTransport transport) { ... }

    @Override public String name() { return name; }
    @Override public String description() { return description; }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            McpResponse resp = transport.sendRequest("tools/call", Map.of(
                "name", name,
                "arguments", arguments
            ));
            if (resp.isError()) {
                return ToolResult.error("mcp tool error: " + resp.errorMessage());
            }
            Map<String, Object> result = resp.result();
            // result 形如 {content: [{type: "text", text: "..."}], isError: false}
            String text = extractText(result);
            return ToolResult.ok(text);
        } catch (McpConnectionException ex) {
            return ToolResult.error("mcp connection lost: " + serverName + " (" + ex.getMessage() + ")");
        } catch (RuntimeException ex) {
            return ToolResult.error("mcp tool failed: " + ex.getMessage());
        }
    }
}
```

**`payload` 不暴露内部**：McpTool 的 `payload` 不包含 `transport` / `inputSchema`；这些是内部状态，LLM 不需要。

**审计**：`source='mcp'`（由 `resolveSource(tool)` 自动推导，[research.md R-09](./../research.md)）。

---

## 8. 失败语义

| 失败场景 | ToolResult.errorMessage |
|---------|------------------------|
| 启动期 server 不可达 | Spring Boot 启动失败（不是 Tool 失败） |
| 运行期 server 挂掉 | `"mcp connection lost: <serverName> (<cause>)"` |
| 运行期 tool 不存在 | `"mcp tool error: tool '<name>' not found"` |
| 运行期 tool 抛协议错误 | `"mcp tool failed: <message>"` |
| 运行期 JSON-RPC error | `"mcp tool error: <server error message>"` |

**审计**：上述所有失败均写 1 行 `tool_invocations`（success=false，`source='mcp'`）。

---

## 9. 不变量（Invariants）

- **I-MCP-1**：MCP server 启动期 handshake 必须成功（[research.md R-10](./../research.md)）；任一失败 → Spring Boot 启动失败
- **I-MCP-2**：McpTool 永远不抛 RuntimeException；异常一律转 `ToolResult.error`
- **I-MCP-3**：McpTool 的 `name()` 必须与 MCP server 在 `tools/list` 中声明的 `name` 完全一致（不改名）
- **I-MCP-4**：同一 server 的所有 tool 共享一个 `McpTransport` 实例；连接关闭时所有 tool 失效
- **I-MCP-5**：McpTool 不依赖 `ProfileContext`（[research.md R-07](./../research.md)）；Profile 过滤由 `DefaultToolExecutor.invoke()` 的 `profile.tools[]` 前置完成

---

## 10. 测试矩阵

| 测试 | 期望 |
|------|------|
| `startup_reads_yaml` | 1 个 server 配置 → 1 个 connection + N 个 tool |
| `startup_handshake_succeeds` | mock transport 返回 initialize result → connection 状态 CONNECTED |
| `startup_server_unreachable_fails_fast` | mock transport 抛 IOException → Spring 启动失败 |
| `adapt_descriptor_to_tool` | 1 个 descriptor → 1 个 McpTool，name/description 透传 |
| `mcp_tool_dispatches_call` | execute(args) → transport.sendRequest("tools/call", ...) 调用 1 次 |
| `mcp_tool_handles_error_response` | transport 返回 error → ToolResult.error |
| `mcp_tool_handles_connection_lost` | transport 抛 McpConnectionException → ToolResult.error("mcp connection lost") |
| `http_transport_sends_post` | HttpMcpTransport 正确序列化为 JSON-RPC 2.0 POST body |
| `stdio_transport_spawns_process` | StdioMcpTransport 启动子进程 + 写 stdin 行 |
| `mcp_tool_audit_source_mcp` | McpTool 调用后 `tool_invocations.source='mcp'` |

---

## 11. 不在本契约范围

- ❌ MCP server 端的实现（OryxOS 是 MCP **客户端**，不做 server）
- ❌ `resources/list` / `prompts/list`（仅用到 `initialize` / `tools/list` / `tools/call` 三方法）
- ❌ OAuth / token refresh（MCP auth 用环境变量注入，简化）
- ❌ Stdio 模式的环境变量隔离（spec 假设 6 推迟项；tasks.md 阶段需明确：std 子进程继承 OryxOS 进程环境，但 `mcp_servers.yaml` 的 `env:` 字段会覆盖同名变量）

---

## 12. 引用

- [spec.md §FR-009](../spec.md)（MCP 接入）
- [spec.md §US-3 场景 1](../spec.md)（成功握手）
- [spec.md §US-3 场景 3](../spec.md)（fail-fast）
- [spec.md §US-3 场景 4](../spec.md)（运行期挂掉）
- [research.md R-04](../research.md)（MCP 自实现决策）
- [research.md R-10](../research.md)（启动期握手决策）
- [CLAUDE.md §V 三档接入](../../../CLAUDE.md)
- [MCP 官方规范](https://modelcontextprotocol.io/)（protocol version 2024-11-05 引用）
