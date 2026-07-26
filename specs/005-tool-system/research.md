# 研究文档：Tool 体系（Agent 的"双手"）

**目的**：把 spec.md 中所有 NEEDS CLARIFICATION 与技术决策汇总，按"决策 / 理由 / 备选"格式固化，给 plan.md 与 tasks.md 提供可追溯依据。
**创建日期**：2026-07-26
**特性**：[spec.md](./spec.md)
**前置文档**：[.specify/memory/constitution.md](../../.specify/memory/constitution.md) | [CLAUDE.md §5 / §9.4 / §9.5](../../CLAUDE.md) | [specs/004-notify-channel/research.md](../004-notify-channel/research.md)

> **与 004 spec 的关系**：本 spec 在 [004-notify-channel/research.md](../004-notify-channel/research.md) 的 R-01..R-10 之上扩展。R-01（R-11 in 004）+ R-08（R-08 in 004）+ R-09（R-09 in 004）+ 沙箱 / Notify / ToolRegistry 等已有决策**直接继承**，不在本文件重复。本文件聚焦 R-01..R-12 的"新增"决策点。

---

## R-01：HTTP Tool 客户端复用 JDK `HttpClient`

**决策**：`HttpGetTool` / `HttpPostTool` 直接复用 [004-notify-channel/research.md R-01](../004-notify-channel/research.md) 的决策 —— JDK 21 `java.net.http.HttpClient`，同步 API，配合 JDK 21 虚拟线程实现非阻塞。

**理由**：

1. **决策一致性**——HTTP Tool 与 Notify 走完全相同的 HTTP 出站路径（虚拟线程 + `HttpClient.send`），复用同一份 `HttpClient` Bean；避免引入多套 HTTP 客户端栈。
2. **沙箱复用**——`HttpTool.execute()` 复用 `WhitelistSandbox.enforce(SandboxAction(HTTP_REQUEST, url))`，校验失败抛 `SandboxViolationException`，由 `DefaultToolExecutor` 既有审计路径消费（spec FR-004）。
3. **零新增依赖**——`HttpClient` 自 JDK 11 起稳定；不引入 OkHttp / Apache HC / Spring RestClient。
4. **超时一致**——HTTP Tool 默认 timeout 5 秒（与 Notify 一致）；通过 `HttpToolProperties.timeout` 可配（spec NFR-001）。

**HttpToolProperties 配置**：

```yaml
oryxos:
  tool:
    http:
      timeout-seconds: 5
      max-response-bytes: 1048576     # 1 MB；防止恶意巨型响应撑爆内存
```

**返回值结构**：

```java
record HttpToolResult(int statusCode, String contentType, String body, long durationMs) {}
```

**`HttpPostTool` 的 body 入参**：JSON 字符串（`body: String`），由 Tool 层负责设置 `Content-Type: application/json`；不引入 multipart / form-data（核心阶段无此需求）。

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| Spring `RestClient` | 与 Spring 生态统一 | 同样阻塞 + 引入 `spring-web` 依赖 + 与 Notify 决策不一致 |
| Spring `WebClient` (reactive) | 内建非阻塞 | 引入 `spring-webflux` 依赖；Reactor 学习成本 |
| OkHttp / Apache HC | 工业标准 | 新增第三方依赖（违反宪法 §I "依赖最小化"） |

---

## R-02：File Tool 用 JDK 21 `java.nio.file.Files`

**决策**：`FileReadTool` / `FileWriteTool` / `FileListTool` 全部用 JDK 内置 `java.nio.file.Files` / `Path` API；不引入 Apache Commons IO / Guava 等文件系统库。

**理由**：

1. **JDK 21 `java.nio` 已足够**——`Files.readString` / `Files.writeString` / `Files.list` / `Files.walk` 在 JDK 21 是 stable API；核心阶段文件 I/O 需求（读 / 写 / 列目录）全部覆盖。
2. **零依赖**——不引入第三方库，违反宪法 §I "依赖最小化"。
3. **路径安全**——`Path.of(String)` + `Path.toRealPath()` 处理 symlink / 相对路径；`Files.exists(..., LinkOption.NOFOLLOW_LINKS)` 与既有 `InitCommand` 的 symlink refusal 模式一致（[InitCommand.java](../../oryxos-cli/src/main/java/io/oryxos/cli/command/InitCommand.java) 第 47 行）。

**FileSandbox 校验**（FR-004 实现）：

```java
// FileReadTool.execute(path)
sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path.toString()));
// WhitelistSandbox 当前对 FILE_READ 是 no-op（核心阶段仅 HTTP_REQUEST 走白名单）；
// 扩展阶段在 WhitelistSandbox 内部按 allowed-paths 校验。
```

**白名单落地路径**（核心阶段不实现，扩展阶段在 WhitelistSandbox 内部追加 FILE_READ 校验）：

```yaml
oryxos:
  tool:
    sandbox:
      file:
        allowed-paths:
          - .oryxos/agents
          - .oryxos/memory
```

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| Apache Commons IO | API 简洁 | 新增第三方依赖；JDK 21 NIO 已足够 |
| Guava `Files` | API 简洁 | 新增 Guava 整个生态；违反宪法 §I |
| JDK 7 `java.io.File` | 老牌 API | 已被 NIO.2 取代；不支持符号链接安全处理 |

---

## R-03：Shell Tool 的命令白名单策略

**决策**：`ShellTool.execute(command)` 在执行 `ProcessBuilder` 前必须通过 `Sandbox.enforce(SandboxAction(SHELL_COMMAND, command))` 校验。**核心阶段**：

1. `WhitelistSandbox` 对 `SHELL_COMMAND` 仍是 no-op（与 `FILE_READ` / `FILE_WRITE` 一致；核心阶段只校验 `HTTP_REQUEST`）
2. **应用层降级**：Tool 内部维护一份"危险命令黑名单"——`["rm", "mkfs", "dd", "shutdown", "reboot", "wget", "curl", ...]`——命中即拒绝
3. 完整 SHELL_COMMAND 白名单校验放扩展阶段（与 `FILE_READ` / `FILE_WRITE` 一起）

**理由**：

1. **宪法 §V "企业级 Tool 治理"** 要求所有副作用走白名单；核心阶段做不到完整白名单时**至少做黑名单兜底**，避免 Agent 误调 `rm -rf /` 这种灾难命令
2. **沙箱抽象不变**——`Sandbox.enforce(SHELL_COMMAND, ...)` 接口已存在（[ActionType.java](../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/ActionType.java) 第 14 行）；白名单扩展阶段接入，Tool 调用方代码不变
3. **黑名单 vs 白名单**——白名单更安全但限制更多（仅 `["echo", "ls", "cat"]` 等极少数命令）；核心阶段 Demo 需要 `python` / `node` / `git` 等命令，黑名单更务实
4. **配置**：`tool.sandbox.shell.dangerous-commands: [...]`（默认包含上述列表；可配置）

**配置**：

```yaml
oryxos:
  tool:
    sandbox:
      shell:
        dangerous-commands:
          - rm
          - mkfs
          - dd
          - shutdown
          - reboot
          - wget
          - curl
          - chmod       # 仅写入 777 类破坏性
          - chown       # 仅改文件属主
          - mv
          - cp
```

**实际拦截逻辑**：

```java
// ShellTool.execute(command)
String[] tokens = command.split("\\s+");
for (String dangerous : sandboxProperties.getShell().getDangerousCommands()) {
    if (tokens[0].equals(dangerous)) {
        return ToolResult.error("shell command blocked: " + dangerous + " is in dangerous-commands");
    }
}
sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, command));  // 扩展阶段 no-op
// 真正执行
ProcessBuilder pb = new ProcessBuilder(tokens).directory(workDir);
Process p = pb.start();
```

**超时**：`Process.waitFor(timeout, TimeUnit.SECONDS)`；30 秒超时（`HttpToolProperties` 之外加 `ShellToolProperties.timeout-seconds: 30`，spec NFR-001 上限 30 秒给到 shell）。

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 完整白名单（仅 echo / ls / cat） | 最安全 | 核心阶段 Demo 需要 `python` / `git` 等命令；白名单会卡死 Demo |
| 不做任何拦截 | 最简 | 违反宪法 §V；LLM 误调 `rm -rf /` 是灾难 |
| 用 seccomp / cgroups（容器级） | 系统级安全 | 扩展阶段；核心阶段仅应用层 |
| 全交给 WhitelistSandbox（让 WhitelistSandbox 扩展支持 SHELL_COMMAND） | 沙箱抽象一致 | 核心阶段实施成本高；黑名单 + Tool 层拦截已能挡住已知危险命令 |

---

## R-04：MCP Java SDK 集成方案

**决策**：MCP Java SDK 暂不直接依赖；本 spec 在 `oryxos-tool/mcp/` 包内实现**自有 MCP 客户端**（HTTP/SSE / stdio 双协议），遵循 [Model Context Protocol 规范](https://modelcontextprotocol.io/)。MCP server 协议握手（initialize / tools/list / tools/call）由 `McpClientService` 用 JDK `HttpClient` + `ProcessBuilder`（stdio 模式）实现。

**理由**：

1. **MCP Java SDK 处于活跃开发期**——[oryxos-tool/pom.xml](../../oryxos-tool/pom.xml) 已有注释："MCP SDK is added in US-4 once the correct artifact/version is verified (MCP Java SDK is currently in active development and version naming has shifted)"。锁定具体版本有破坏性升级风险。
2. **协议简单**——MCP 是 JSON-RPC 2.0 over HTTP/SSE 或 stdio；核心阶段只用到 `initialize` / `tools/list` / `tools/call` 三种方法；自实现成本可控（~200 行 Java）。
3. **避免依赖膨胀**——MCP Java SDK 引入 ~ 10+ 传递依赖（Reactor、Jackson、SLF4J 变体等）；自实现仅依赖 JDK + Jackson（已在 pom）。
4. **未来升级路径**——若 MCP 协议稳定，可平滑替换为官方 SDK；`McpClientService` 是接口边界，实现可替换。

**包路径**：

```text
oryxos-tool/mcp/
├── McpClientService.java              # 顶层接口
├── McpClientProperties.java           # @ConfigurationProperties
├── McpServerConnection.java           # record（server 名 + transport 类型 + 连接状态）
├── McpTransport.java                  # interface（sendRequest, close）
├── HttpMcpTransport.java              # HTTP/SSE 实现
├── StdioMcpTransport.java             # stdio 子进程实现
├── McpToolAdapter.java                # 把 MCP tools/list 转成 OryxTool 列表
└── McpTool.java                       # 单个 MCP tool 的 OryxTool 实现（闭包持有 transport）
```

**`mcp_servers.yaml` 配置**：

```yaml
servers:
  - name: github-mcp
    transport: stdio                 # or http
    command: uvx mcp-server-github   # stdio 模式
    args: []
  - name: weather-mcp
    transport: http
    url: http://localhost:8081/sse   # HTTP/SSE 模式
    auth-token: ${MCP_WEATHER_TOKEN}
```

**`McpClientService` 启动期行为**：

1. 读 `mcp_servers.yaml`，对每个 server 起一个连接
2. 调 `initialize` 方法拿到 server capabilities
3. 调 `tools/list` 拿到该 server 暴露的 tool 列表
4. 对每个 tool 用 `McpToolAdapter` 生成一个 `McpTool implements OryxTool`
5. 注册到 `ToolRegistry`（spec FR-009）
6. **启动期 fail-fast**（spec US-3 场景 3）：任一 server 连接失败 → Spring Boot 启动失败

**McpTool.execute 路径**：

```text
LLM 调 list_pull_requests(...)
  → DefaultToolExecutor.invoke("list_pull_requests", args, profile)
    → ToolRegistry.find("list_pull_requests")
      → McpTool.execute(args)
        → McpTransport.sendRequest("tools/call", {name: "list_pull_requests", arguments: args})
          → HTTP POST or stdin → MCP server
        → 解析响应 → ToolResult.success + content
```

**失败语义**（spec US-3 场景 4）：MCP server 运行期挂掉 → `McpTransport.sendRequest` 抛 `McpConnectionException` → `McpTool.execute` 捕获 → `ToolResult.success=false, errorMessage="mcp connection lost: <name>"`。

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 直接依赖官方 MCP Java SDK | 协议维护由官方 | SDK 版本不稳；引入 ~10 个传递依赖；规格锁定困难 |
| 只支持 stdio（不起 HTTP MCP） | 实现简单 | 业界 MCP server 多数走 HTTP/SSE；不支持 HTTP 会让 90% 现成 MCP server 不可用 |
| 只支持 HTTP/SSE（不起 stdio） | 实现简单 | 一些 MCP server 走 stdio（避免端口冲突）；失去 stdio 等于放弃本地开发场景 |
| 完全外包给外部进程（OryxOS 只调 shell 启 MCP CLI） | 0 自实现 | shell 路径绕过沙箱（违反宪法 §V）；审计难以追踪 |

---

## R-05：Memory Tool 封装策略

**决策**：`SaveMemoryTool` / `RecallMemoryTool` 是 `MemoryService` 的 thin wrapper，仅做"参数解析 + ToolResult 适配"；不实现 Memory 存储逻辑本身（避免与 [US-3 Memory](../003-cli-commands/spec.md) 重复）。

**理由**：

1. **职责清晰**——Memory 三层门面 + 后端实现已在 [specs/003-cli-commands](../003-cli-commands/spec.md) 落地；Tool 层只负责把 MemoryService API 包成 OryxTool。
2. **schema 一致**——`save_memory(content: String, scope: String)` / `recall_memory(query: String, top_k: int)` 是 Function Calling 友好的参数签名；scope 是 `core` / `archive` 二选一（参见 [CLAUDE.md §9.6](../CLAUDE.md) 四条契约第 3 条）。
3. **审计一致**——Memory 工具调用走与 File / Shell / HTTP 完全相同的 `DefaultToolExecutor` 路径，审计行写入 `tool_invocations` 表；`source` 列填 `builtin`。

**包路径**：

```text
oryxos-tool/memory/
├── SaveMemoryTool.java
└── RecallMemoryTool.java
```

**SaveMemoryTool.execute(args)**：

```java
String content = (String) args.get("content");
String scope = (String) args.getOrDefault("scope", "core");
memoryService.save(content, MemoryScope.fromString(scope));   // 已有 API
return ToolResult.ok("memory saved: <truncated-content>");
```

**RecallMemoryTool.execute(args)**：

```java
String query = (String) args.get("query");
int topK = (int) args.getOrDefault("top_k", 5);
List<MemoryEntry> hits = memoryService.recallByKeyword(query, topK);   // 已有 API
return ToolResult.ok(formatHits(hits));
```

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 在 Tool 层重写 Memory 存储 | 一站式 | 与 MemoryService 重复；违反单一职责 |
| 直接暴露 MemoryService API 给 LLM | 0 wrapper | Function Calling schema 与 Java API 不对齐；丢失 scope / top_k 等语义 |
| 不提供 Memory Tool（LLM 走 web 调 HTTP 路径） | 0 代码 | 失去三层 Memory 的 day-one 价值；违反宪法 §V "零代码优先" |

---

## R-06：`source` 列语义与 Java enum

**决策**：`tool_invocations.source` 列定义为 `TEXT`，取值为以下三个枚举值之一：

| 值 | 含义 | 触发条件 |
|----|------|---------|
| `builtin` | 内置 Tool（FileTools / ShellTools / HttpTools / NotifyTool / MemoryTools） | Tool 类标 `@Component` 且落在 `io.oryxos.tool.{file,shell,http,notify,memory}` 包 |
| `mcp` | MCP 工具 | Tool 由 `McpToolAdapter` 生成；类路径含 `McpTool` 前缀 |
| `java_bean` | 业务自定义 Java Tool | Tool 是 `@Component implements OryxTool`，且不在 `io.oryxos.tool.*` 内置包 |

**判定逻辑**（在 `DefaultToolExecutor.invoke` 或 `NotifyToolConfig` 装配路径中实现）：

```java
private static String resolveSource(OryxTool tool) {
    String className = tool.getClass().getName();
    if (className.startsWith("io.oryxos.tool.mcp.")) return "mcp";
    if (className.startsWith("io.oryxos.tool."))     return "builtin";
    return "java_bean";
}
```

**写入时机**：`DefaultToolExecutor.invoke()` 派发 Tool 之前从 `OryxTool` 实例推导 `source`，写入 audit 行的 `source` 列（spec FR-005）。

**Schema 演进**（spec FR-005 落地）：

```sql
-- V3__add_tool_source.sql
ALTER TABLE tool_invocations ADD COLUMN source TEXT NOT NULL DEFAULT 'builtin';

-- 历史行：DDL 加默认值后，新列对历史行默认填 'builtin'（notify 类的历史行不准确，
-- 但 US-4 落地后 notify 类新建的行会被显式填 'builtin'，与实际情况一致）。

-- 索引：审计员按 source 维度过滤（如只看 MCP 工具调用）
CREATE INDEX IF NOT EXISTS idx_tool_source ON tool_invocations(tool_name, source, started_at);
```

**JPA entity 改动**：

```java
// ToolInvocationRecord.java
@Column(name = "source", nullable = false, columnDefinition = "TEXT")
private String source = "builtin";  // 默认值保持兼容

public ToolInvocationRecord(..., String source) { ... }
```

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 用 Java enum 存（DB 存 enum name） | 类型安全 | JPA enum 映射需要 `@Enumerated(EnumType.STRING)`；DB 列仍为 TEXT；与本决策等价，仅多一层映射 |
| 用整数码（0=builtin, 1=mcp, 2=java_bean） | 紧凑存储 | 调试 / SQL 查询体验差；不直观 |
| 不区分 source，全填 'builtin' | 0 改动 | 失去审计员"按 Tool 来源过滤"的能力；违反 spec FR-005 |

---

## R-07：Tool 调用对 ProfileContext 的依赖

**决策**：仅 `NotifyTool` 需要 `ProfileContext.current()` 拿 Profile（用于 `profile.notifyChannels()` 查询）；其他 8 个内置 Tool + MCP Tool + Java Bean Tool **不**依赖 `ProfileContext`——它们的 Profile 信息已通过 `DefaultToolExecutor.invoke(name, args, profile)` 的 `profile` 参数传入。

**理由**：

1. **接口对齐**——`OryxTool.execute(Map<String, Object> arguments)` 的签名是**无 Profile 的**；Notify 的特殊需求通过 `ProfileContext` 间接获取，是为了不破坏统一签名（参见 [CLAUDE.md §9.3](../CLAUDE.md) 关于 ProfileContext 的设计依据）。
2. **职责清晰**——其他 Tool（File / Shell / HTTP / Memory / MCP / Java Bean）的配置在 Tool 自身的 `@Component` 构造期注入（`HttpToolProperties` / `McpClientProperties` 等），不需要 Profile-level 配置。
3. **测试隔离**——其他 Tool 的单测不需要模拟 `ProfileContext`，更易测。

**例外情况**：将来如果某个 Tool 需要 Profile-level 配置（如 "某 Profile 才能用 `notify`，其他 Profile 用不了"），通过 `Profile.tools[]` 字段**前置过滤**——LLM 看不到也调不到（spec FR-011），不需要 Tool 内部再用 ProfileContext 二级判断。

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 全部 Tool 都走 ProfileContext | 一致性 | 多数 Tool 不需要；引入不必要的 ThreadLocal 依赖 |
| `OryxTool.execute(args, profile)` 加 profile 参数 | 显式 | 破坏既有签名；DefaultToolExecutor 也要改 |
| 通过 `ToolContext` record 包装（args + profile） | 类型安全 | 与既有 `Map<String, Object>` 签名不兼容；改动面大 |

---

## R-08：Tool schema 冲突检测与启动期 fail-fast

**决策**：`ToolRegistry.of(Map<String, ToolRegistration>)` 在装配时**主动检测**两个 Tool 用同一 `name`，冲突时抛 `IllegalStateException`，Spring Boot 启动失败（spec FR-015）。

**理由**：

1. **fail-fast > 静默选一个**——同名 Tool 让 LLM 不知道调的是哪个；运行时才发现"调到了错误的 Tool"是灾难。
2. **Spring Boot 启动期是天然检查点**——`@Primary @Bean ToolRegistry`（已有 `NotifyToolConfig`）的初始化失败会阻断启动；不会进入生产。
3. **现状**——[ToolRegistry.java](../../oryxos-core/src/main/java/io/oryxos/core/tool/ToolRegistry.java) 第 47-59 行的 `of()` 方法已经按 `definition().name()` 二次归一化（`normalized.put(reg.definition().name(), reg)`），后续 put 会**覆盖**前一个。这导致静默"后注册赢"——与 spec FR-015 冲突。

**需要修改 `ToolRegistry.of()`**：

```java
// 当前（004 阶段）
public static ToolRegistry of(Map<String, ToolRegistration> registrations) {
    Map<String, ToolRegistration> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, ToolRegistration> e : registrations.entrySet()) {
        ToolRegistration reg = e.getValue();
        if (reg == null) continue;
        normalized.put(reg.definition().name(), reg);  // 静默覆盖
    }
    return new ToolRegistry(normalized);
}

// 本 spec 改为
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

**测试**：`ToolRegistryTest.conflict_fails_at_construction()` 两个 Tool 都用 `name="echo"` 时 `of()` 抛 `IllegalStateException`。

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 静默"后注册赢" | 0 代码改动 | 与 spec FR-015 直接冲突；LLM 调到错误 Tool 是灾难 |
| 启动期只警告（不阻断） | 不影响启动 | 与 fail-fast 哲学冲突；运行时才发现问题更难调试 |
| 按 prefix 隔离（builtin: / mcp: / bean:） | 命名空间清晰 | 复杂度高；扩展阶段才考虑 |

---

## R-09：DefaultToolExecutor 派发路径的 source 字段抽取

**决策**：扩展 [DefaultToolExecutor.java](../../oryxos-core/src/main/java/io/oryxos/core/DefaultToolExecutor.java) 的 `extractNotifyAuditFields` 为通用 `extractExtraAuditFields(tool, result)`，对所有 Tool 都返回 `source`；notify 工具额外返回 `channel` + `notify_status_code`。

**理由**：

1. **复用既有抽取模式**——现有 `extractNotifyAuditFields`（第 135 行起）已经展示了"按 Tool 类型抽取额外字段"的模式；本 spec 把这个模式扩展为"通用 + notify 专属"。
2. **避免在 ToolResult payload 里写 source**——source 是审计元数据，不是 ToolResult 业务数据；放在 payload 里会污染 LLM 视角的返回结构。
3. **`ToolAuditWriter.ToolAuditData`** 已预留 `source` 字段占位（继承既有模式，加新参数即可）。

**改动点**（伪代码）：

```java
// DefaultToolExecutor.java
private ExtraAuditFields extractExtraAuditFields(String toolName, OryxTool tool, ToolResult result) {
    String source = resolveSource(tool);  // builtin / mcp / java_bean
    if ("notify".equals(toolName)) {
        // 既有的 Notify 字段抽取逻辑保留
        return new ExtraAuditFields(source, channel, notifyStatusCode);
    }
    return new ExtraAuditFields(source, null, null);
}

private static String resolveSource(OryxTool tool) {
    String cn = tool.getClass().getName();
    if (cn.startsWith("io.oryxos.tool.mcp.")) return "mcp";
    if (cn.startsWith("io.oryxos.tool."))     return "builtin";
    return "java_bean";
}
```

**`ToolAuditWriter.ToolAuditData`** 构造签名扩展：增加 `String source` 参数；既有调用方（US-2 stub / 单测 fixture）显式传 `null` 或 `"builtin"` 保持兼容。

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 在 ToolResult payload 里写 `source` | 不动 DefaultToolExecutor | 污染 LLM 视角；LLM 不关心 Tool 来源 |
| 让每个 Tool 自报 source（OryxTool.source() 默认方法） | 显式 | 增加接口方法；与既有 default 方法 `description()` 的扩展模式类似但 source 是审计元数据不该上 Tool 接口 |
| 用 Bean name 后缀（`*McpTool` / `*BuiltinTool`） | 命名约定 | 隐式约束；不如类路径前缀明确 |

---

## R-10：MCP 启动期握手 vs 懒连接

**决策**：MCP server 连接在 **Spring Boot 启动期**握手（spec US-3 场景 1 + 场景 3）；运行期不重连。

**理由**：

1. **启动期 fail-fast**——`mcp_servers.yaml` 配错 / server 不可达 / 协议版本不兼容，**启动期立刻报错**，运营者能立即看到；不让问题进入生产环境（spec US-3 场景 3）。
2. **避免运行期首次调用的延迟**——LLM 调 MCP Tool 时不需要先握手 200ms；连接已在启动期建好。
3. **运行期重连的复杂度**——心跳 / 指数退避 / 死信队列是 Tool-as-a-Service 抽象（spec 假设 6 推迟项）；核心阶段不在此范围。
4. **运行期挂掉**——已在 spec US-3 场景 4 覆盖：`McpTool.execute` 捕获 `McpConnectionException`，返回 `ToolResult.success=false`。

**`McpClientService.startup()` 流程**（`@PostConstruct` 或 Spring `ApplicationReadyEvent`）：

```text
1. 读 oryxos/mcp_servers.yaml
2. 对每个 server：
   a. 建 transport（HTTP 或 stdio）
   b. 发送 initialize 请求
   c. 发送 tools/list 请求
   d. 用 McpToolAdapter 把每个 tool 转成 McpTool
3. 把所有 McpTool 注册到 ToolRegistry
4. 任一步失败 → 抛 RuntimeException → Spring Boot 启动失败
```

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 懒连接（首次调用时握手） | 启动快 | 首次 Tool 调用有 200ms+ 延迟；配置错到运行时才暴露 |
| 运行期自动重连 | 高可用 | 扩展阶段；核心阶段不引入 |
| 仅警告（启动期握手失败不阻断） | 启动不失败 | 与 fail-fast 冲突；运营者不易察觉 |

---

## R-11：HTTP Tool 与 Sandbox 的 host 校验

**决策**：`HttpGetTool` / `HttpPostTool` 在执行前调 `sandbox.enforce(SandboxAction(HTTP_REQUEST, url))`，复用 `WhitelistSandbox` 的 host 后缀匹配逻辑。

**理由**：

1. **沙箱复用**——`WhitelistSandbox` 已落地（[WhitelistSandbox.java](../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java)），host 后缀匹配 + IP 拒绝都已实现；HTTP Tool 直接复用。
2. **与 Notify 一致**——`WebhookNotifyAdapter.send()` 走同一份 `WhitelistSandbox.enforce`；HTTP Tool 与 Notify 在沙箱语义上完全一致。
3. **零额外校验**——HTTP Tool 不在沙箱层加额外的 body / method / header 校验；body / method 由 Tool schema 阶段管控。

**`HttpToolProperties`**（共享给 Get / Post）：

```yaml
oryxos:
  tool:
    http:
      timeout-seconds: 5
      max-response-bytes: 1048576  # 1 MB
```

**HttpGetTool.execute(url) 伪代码**：

```java
sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));   // 沙箱
HttpRequest req = HttpRequest.newBuilder()
    .uri(URI.create(url))
    .timeout(Duration.ofSeconds(httpProps.getTimeoutSeconds()))
    .GET()
    .build();
HttpResponse<String> resp = httpClient.send(req, BodyHandlers.ofString(limit));
return ToolResult.ok(new HttpToolResult(resp.statusCode(), resp.headers().firstValue("content-type").orElse(""), resp.body(), durationMs));
```

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| HTTP Tool 不走沙箱 | 0 依赖 | 违反宪法 §V；LLM 可调任意 URL 是灾难 |
| HTTP Tool 自己的白名单（独立于 Notify） | 配置独立 | 配置分裂；运营者要为两个 Tool 配两遍 |
| 内置"始终允许 localhost / WireMock"白名单 | 调试友好 | 安全风险；运营者可能误开 |

---

## R-12：测试与可演示策略

**决策**：

1. **单元测试**：JUnit 5 + Mockito + AssertJ 覆盖每个内置 Tool（File / Shell / HTTP / Notify / Memory）与 McpToolAdapter 的核心路径。
2. **集成测试**：Spring Boot `@SpringBootTest` + WireMock（HTTP Tool mock）+ 本地 tmp 目录（File Tool）+ 本地真 shell（Shell Tool）+ mock `MemoryService`（Memory Tool）+ mock `McpTransport`（MCP Tool）。每个内置 Tool 至少 3 个集成测试场景（success / fail / sandbox 拦截）。
3. **端到端冒烟**：[scripts/tool-smoke.sh](../../scripts/tool-smoke.sh)（在 tasks.md 阶段创建）跑"5 个内置 Tool 各跑一遍 + 1 个 MCP mock tool 跑一遍"的 6 场景冒烟。
4. **三个 Demo 关联**：daily-weather / daily-tech / daily-github 三条 Agent 在 `AGENT.md` 里显式使用 Tool 指令（每日天气 → `http_get`；每日科技日报 → MCP `list_pull_requests`；每日 GitHub 日报 → `shell "git log ..."`），与本 spec 联调。
5. **宪法 §IV 验证手段**：`tool-smoke.sh` 的核心断言 = "每个 Tool 在同一 Session 调一次只产生 1 行审计行"；重复计数 ≥ 2 即失败（对应 spec FR-007）。

**测试数据**：

- File Tool：`./.oryxos/test-fixtures/` 下的临时文件
- Shell Tool：`echo "hello"`（无害命令）/ `git --version`（无害版本查询）
- HTTP Tool：WireMock 8089 端口 + `localhost` 加入白名单
- MCP Tool：mock MCP server（HTTP 模式监听 8081，stdio 模式用 `cat` 模拟 JSON-RPC 应答）
- Memory Tool：mock MemoryService 桩

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 仅单元测试，无端到端冒烟 | 编译快 | 违反宪法 §VII "Demo-First"；spec SC-001 是硬约束 |
| 集成测试用真 HTTP（不 mock） | 真实 | 集成测试不稳定（外网抖动）；WireMock 是业界标准 |
| 不测 Sandbox 拦截路径 | 0 测试成本 | 沙箱拦截是宪法 §V 核心；必须测 |
| 用 Testcontainers 起真 MCP server | 真实 MCP 协议 | 集成测试启动慢；mock McpTransport 已覆盖 90% 路径 |

---

## 决策索引

| ID | 主题 | 决策 |
|----|------|------|
| R-01 | HTTP Tool 客户端 | JDK `java.net.http.HttpClient` |
| R-02 | File Tool API | JDK `java.nio.file.Files` |
| R-03 | Shell Tool 拦截 | 应用层黑名单 + `Sandbox.enforce` 抽象（完整白名单放扩展阶段） |
| R-04 | MCP SDK 集成 | 自实现 MCP 客户端（MCP Java SDK 暂不依赖） |
| R-05 | Memory Tool | 薄封装 MemoryService |
| R-06 | source 列 | TEXT + 三枚举值（builtin / mcp / java_bean） |
| R-07 | ProfileContext 依赖 | 仅 NotifyTool 用；其他 Tool 走 `invoke(name, args, profile)` 参数 |
| R-08 | Tool schema 冲突 | 启动期 fail-fast（修改 ToolRegistry.of） |
| R-09 | source 字段抽取 | 扩展 `extractExtraAuditFields` 为通用 + notify 专属 |
| R-10 | MCP 连接时机 | 启动期握手，运行期不重连 |
| R-11 | HTTP Tool 沙箱 | 复用 `WhitelistSandbox.enforce(HTTP_REQUEST, url)` |
| R-12 | 测试策略 | JUnit 5 + Mockito + WireMock + scripts/tool-smoke.sh 端到端 |

---

## 待 tasks.md 阶段固化项

- 每个内置 Tool 的具体 `@Bean` 装配路径（`@Primary @Bean ToolRegistry` 的合并逻辑）
- MCP server stdio 模式下子进程的环境变量隔离（不能污染 OryxOS 自身环境）
- V3 DDL 的 DOWN 脚本（`DROP COLUMN source`）保留
- `tool-smoke.sh` 的 6 个场景详细步骤（含 WireMock 启动 / 临时工作区 / 预期输出）
