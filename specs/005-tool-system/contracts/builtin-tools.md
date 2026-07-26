# 契约：9 个内置 Tool 的 Schema + 行为

**目的**：定义 OryxOS 核心阶段**全部 9 个内置 Tool** 的 Function Calling schema、参数语义、返回值结构、错误情形。这是 `PromptBuilder` 生成 Tool 列表给 LLM 的依据，也是 ToolListCommand 输出的来源。
**创建日期**：2026-07-26
**特性**：[spec.md §FR-003 / §FR-005 / §FR-007](../spec.md) | [research.md R-01 / R-02 / R-03 / R-05 / R-09](./../research.md)
**前置**：[OryxTool.java](../../../oryxos-core/src/main/java/io/oryxos/core/OryxTool.java) | [oryx-tool.md](./oryx-tool.md) | [tool-executor.md](./tool-executor.md) | [sandbox.md](./sandbox.md)

---

## 0. 总览

| # | Tool 名 | 类别 | 配置 | 沙箱 | 状态 |
|---|---------|------|------|------|------|
| 1 | `file_read` | 内置 | — | `FILE_READ`（no-op） | [NEW] |
| 2 | `file_write` | 内置 | — | `FILE_WRITE`（no-op） | [NEW] |
| 3 | `file_list` | 内置 | — | `FILE_READ`（no-op） | [NEW] |
| 4 | `shell` | 内置 | `ShellToolProperties` | `SHELL_COMMAND`（no-op）+ 黑名单 | [NEW] |
| 5 | `http_get` | 内置 | `HttpToolProperties` | `HTTP_REQUEST`（白名单） | [NEW] |
| 6 | `http_post` | 内置 | `HttpToolProperties` | `HTTP_REQUEST`（白名单） | [NEW] |
| 7 | `notify` | 内置 | `NotifyProperties` | `HTTP_REQUEST`（白名单） | [已落地 004] |
| 8 | `save_memory` | 内置 | — | — | [NEW] |
| 9 | `recall_memory` | 内置 | — | — | [NEW] |

**状态**：[NEW] = 本 spec 新增；[已落地 004] = 在 004-notify-channel spec 落地，本 spec 引用。

---

## 1. `file_read` — 读取本地文本文件

**类**：`io.oryxos.tool.file.FileReadTool`（[NEW]）

**Schema**：

```json
{
  "name": "file_read",
  "description": "读取本地文本文件内容",
  "parameters": {
    "type": "object",
    "properties": {
      "path": { "type": "string", "description": "文件绝对路径或相对于 .oryxos/agents/<name>/ 的相对路径" }
    },
    "required": ["path"]
  }
}
```

**行为**：

1. 解析 `path`（相对路径相对 `.oryxos/agents/<profile.name>/` 工作目录）
2. `sandbox.enforce(FILE_READ, resolvedPath)`（核心阶段 no-op）
3. `Files.readString(resolvedPath, StandardCharsets.UTF_8)`
4. 返回 `FileToolResult(path, sizeBytes, content, null)` 作为 `payload`

**错误情形**：

| 触发 | ToolResult.errorMessage |
|------|------------------------|
| 文件不存在 | `"file not found: <path>"` |
| 路径是目录 | `"path is a directory: <path>"` |
| 权限拒绝 | `"permission denied: <path>"` |
| 文件超大（> 10 MB） | `"file too large: <size> bytes (max 10 MB)"` |

**审计**：`source=builtin`, `success=true/false`

---

## 2. `file_write` — 写入本地文本文件

**类**：`io.oryxos.tool.file.FileWriteTool`（[NEW]）

**Schema**：

```json
{
  "name": "file_write",
  "description": "写入本地文本文件（覆盖）",
  "parameters": {
    "type": "object",
    "properties": {
      "path": { "type": "string", "description": "文件绝对路径或相对于 .oryxos/agents/<name>/ 的相对路径" },
      "content": { "type": "string", "description": "要写入的文本内容" },
      "append": { "type": "boolean", "description": "是否追加（默认 false 覆盖）", "default": false }
    },
    "required": ["path", "content"]
  }
}
```

**行为**：

1. 解析 `path`
2. `sandbox.enforce(FILE_WRITE, resolvedPath)`（核心阶段 no-op）
3. 创建父目录（如不存在）：`Files.createDirectories(parentDir)`
4. 根据 `append` 调用 `Files.writeString` 或 `Files.writeString(... CREATE, APPEND)` + `TRUNCATE_EXISTING`
5. 返回 `ToolResult.ok("wrote <sizeBytes> bytes to <path>")`，payload 含 `path` + `size_bytes`

**错误情形**：

| 触发 | ToolResult.errorMessage |
|------|------------------------|
| 父目录创建失败 | `"cannot create parent dir: <path>"` |
| 写入失败 | `"write failed: <message>"` |

---

## 3. `file_list` — 列出目录条目

**类**：`io.oryxos.tool.file.FileListTool`（[NEW]）

**Schema**：

```json
{
  "name": "file_list",
  "description": "列出目录下条目（不递归）",
  "parameters": {
    "type": "object",
    "properties": {
      "path": { "type": "string", "description": "目录绝对路径或相对路径" },
      "pattern": { "type": "string", "description": "可选的文件名 glob 模式（如 *.md）", "default": null }
    },
    "required": ["path"]
  }
}
```

**行为**：

1. `sandbox.enforce(FILE_READ, resolvedPath)`
2. `Files.list(resolvedPath)` → 收集到 `List<String> entries`
3. 如 `pattern` 非空，按 glob 过滤
4. 返回 `FileToolResult(path, null, null, entries)`

**错误情形**：

| 触发 | ToolResult.errorMessage |
|------|------------------------|
| 路径不是目录 | `"not a directory: <path>"` |
| 目录不存在 | `"directory not found: <path>"` |

---

## 4. `shell` — 在受限沙箱内执行 shell 命令

**类**：`io.oryxos.tool.shell.ShellTool`（[NEW]）

**Schema**：

```json
{
  "name": "shell",
  "description": "在受限白名单内执行 shell 命令",
  "parameters": {
    "type": "object",
    "properties": {
      "command": { "type": "string", "description": "要执行的命令字符串" },
      "timeout_seconds": { "type": "integer", "description": "超时秒数（默认 30）", "default": 30 }
    },
    "required": ["command"]
  }
}
```

**行为**：

1. **黑名单校验**（[research.md R-03](./../research.md)）：`tokens[0]` 在 `dangerous-commands` 中 → `ToolResult.error`
2. `sandbox.enforce(SHELL_COMMAND, command)`（核心阶段 no-op）
3. `ProcessBuilder(command.split("\\s+")).directory(workDir)`
4. 启动进程 + 异步读 stdout/stderr（避免进程因满 buffer 阻塞）
5. `process.waitFor(timeout, TimeUnit.SECONDS)`；超时则 `process.destroyForcibly()` + 返回 error
6. 返回 `ShellToolResult(command, exitCode, stdout, stderr, durationMs)`

**工作目录**：`profile.tools[].workDir`（默认 `.oryxos/agents/<profile.name>/`）

**错误情形**：

| 触发 | ToolResult.errorMessage |
|------|------------------------|
| 黑名单命中 | `"shell command blocked: <dangerous> is in dangerous-commands"` |
| 超时 | `"shell command timeout after <n> seconds: <command>"` |
| 退出码非零 | `success=false, errorMessage="shell exit code <n>: <command>"`，但 payload 含 stdout/stderr 供 LLM 读 |
| 进程启动失败（命令不存在） | `"shell command not found: <command>"` |

**STDOUT/STDERR 截断**：默认 64 KB（`ShellToolProperties.max-output-bytes`），超出截断。

---

## 5. `http_get` — 发起 HTTP GET 请求

**类**：`io.oryxos.tool.http.HttpGetTool`（[NEW]）

**Schema**：

```json
{
  "name": "http_get",
  "description": "发起 HTTP GET 请求（受沙箱校验）",
  "parameters": {
    "type": "object",
    "properties": {
      "url": { "type": "string", "description": "目标 URL（必须 http/https，host 在白名单内）" },
      "headers": { "type": "object", "description": "可选的请求头键值对", "default": {} }
    },
    "required": ["url"]
  }
}
```

**行为**：

1. `sandbox.enforce(HTTP_REQUEST, url)` —— `WhitelistSandbox` host 后缀匹配
2. 校验失败抛 `SandboxViolationException` → `DefaultToolExecutor` 转 `ToolResult.error`
3. `httpClient.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(...).GET().headers(...).build(), BodyHandlers.ofString(limit))`
4. 返回 `HttpToolResult(statusCode, contentType, body, durationMs)`

**超时**：`HttpToolProperties.timeout-seconds`（默认 5）

**Body 截断**：`HttpToolProperties.max-response-bytes`（默认 1 MB），超出抛 `IOException` → `ToolResult.error("response too large")`

**错误情形**：

| 触发 | ToolResult.errorMessage |
|------|------------------------|
| 沙箱拒绝 | `"sandbox violation: host not in whitelist (HTTP_REQUEST: <url>)"` |
| 连接超时 | `"http_get timeout after <n> seconds: <url>"` |
| HTTP 4xx/5xx | `success=false`，但 payload 仍含 statusCode / body（让 LLM 看错误响应） |
| Body 超大 | `"response too large: > <max> bytes"` |

---

## 6. `http_post` — 发起 HTTP POST 请求

**类**：`io.oryxos.tool.http.HttpPostTool`（[NEW]）

**Schema**：

```json
{
  "name": "http_post",
  "description": "发起 HTTP POST 请求（受沙箱校验，body 为 JSON 字符串）",
  "parameters": {
    "type": "object",
    "properties": {
      "url": { "type": "string", "description": "目标 URL" },
      "body": { "type": "string", "description": "JSON 字符串 body" },
      "headers": { "type": "object", "description": "可选请求头（如 Authorization）", "default": {} }
    },
    "required": ["url", "body"]
  }
}
```

**行为**：

1. `sandbox.enforce(HTTP_REQUEST, url)`
2. 默认 `Content-Type: application/json`（除非调用方在 `headers` 里覆盖）
3. `httpClient.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(...).POST(BodyPublishers.ofString(body)).headers(...).build(), ...)`
4. 返回 `HttpToolResult`（同 http_get）

**错误情形**：同 `http_get`。

---

## 7. `notify` — 向群机器人推送消息

**类**：`io.oryxos.tool.notify.NotifyTool`（[已落地 004]）

**Schema**：

```json
{
  "name": "notify",
  "description": "向已配置的群机器人 webhook 推送消息",
  "parameters": {
    "type": "object",
    "properties": {
      "content": { "type": "string", "description": "推送的消息内容（支持 markdown）" },
      "channel": { "type": "string", "description": "目标渠道名（默认 profile.notifyChannels 默认值）", "default": null }
    },
    "required": ["content"]
  }
}
```

**契约详情**：[004-notify-channel/spec.md §5](../004-notify-channel/spec.md) + [004-notify-channel/contracts/notify.md](../004-notify-channel/contracts/notify.md)。

**审计特殊字段**：`channel=feishu, notify_status_code=200`（由 `extractExtraAuditFields` 提取）。

---

## 8. `save_memory` — 写入长期记忆

**类**：`io.oryxos.tool.memory.SaveMemoryTool`（[NEW]）

**Schema**：

```json
{
  "name": "save_memory",
  "description": "写入长期记忆（支持核心区 / 归档区）",
  "parameters": {
    "type": "object",
    "properties": {
      "content": { "type": "string", "description": "要记忆的内容" },
      "scope": { "type": "string", "enum": ["core", "archive"], "description": "写入分区：core（永不被截断）/ archive（可归档）", "default": "core" }
    },
    "required": ["content"]
  }
}
```

**行为**（[research.md R-05](./../research.md)）：

1. `memoryService.save(content, MemoryScope.fromString(scope))`
2. 返回 `MemoryToolResult("save", scope, 1, null)`

**审计**：`source=builtin, success=true/false`，`errorMessage=null` 或异常 message。

**与 [003 Memory spec](../003-cli-commands/spec.md) 的边界**：

- Tool 层只做"参数解析 + ToolResult 适配"
- 存储逻辑（文件 / SQLite / Mem0）由 `MemoryService` 三层门面管
- 一条记忆一行 `memory_entries` 表记录 + 一行 `tool_invocations` 审计

---

## 9. `recall_memory` — 按关键词检索长期记忆

**类**：`io.oryxos.tool.memory.RecallMemoryTool`（[NEW]）

**Schema**：

```json
{
  "name": "recall_memory",
  "description": "按关键词检索长期记忆",
  "parameters": {
    "type": "object",
    "properties": {
      "query": { "type": "string", "description": "检索关键词" },
      "top_k": { "type": "integer", "description": "返回前 N 条（默认 5）", "default": 5 }
    },
    "required": ["query"]
  }
}
```

**行为**：

1. `List<MemoryEntry> hits = memoryService.recallByKeyword(query, topK)`
2. 格式化 hits 为 `List<String> snippets`（每条截断到 200 字符）
3. 返回 `MemoryToolResult("recall", "core", hits.size(), snippets)`

**与 Memory 四条契约的对齐**（[CLAUDE.md §9.6](../../../CLAUDE.md)）：

- ① 不缓存 ✅（`recallByKeyword` 直接走 store）
- ② 核心区永不被截断 ✅（只读不写）
- ③ 写核心还是写归档由 Agent 经 `scope` 显式指定 ✅（`save_memory` 的 scope 参数）
- ④ `recallByKeyword` 是关键词检索不做复杂化 ✅（不引入向量检索）

---

## 10. 共同约定

### 10.1 Tool 命名

| 类型 | 命名规则 |
|------|---------|
| 内置 Tool | snake_case（`file_read` / `http_get` / `save_memory`） |
| MCP Tool | 保留 MCP server 原名（如 `list_pull_requests`） |
| Java Bean Tool | 业务自定（snake_case 优先） |

### 10.2 `description` 第一句话要求

- **动词开头**（如"读取"、"发起"、"写入"）
- **≤ 200 字符**
- **中文**（与 [CLAUDE.md §21](../../../CLAUDE.md) 一致）

### 10.3 `payload` 透传给 LLM

每个 Tool 的 `payload` 字段（[OryxTool 契约 §4.4](./oryx-tool.md)）作为结构化数据返回给 LLM。LLM 可以在下一轮引用这些字段。

### 10.4 审计列映射

| Tool | `source` | `channel` | `notify_status_code` |
|------|----------|-----------|---------------------|
| 8 个非 notify 内置 Tool | `builtin` | null | null |
| `notify` | `builtin` | `<channel名>` | HTTP status code |
| MCP Tool | `mcp` | null | null |
| Java Bean Tool | `java_bean` | null | null |

---

## 11. 测试矩阵

| 测试 | 期望 |
|------|------|
| `file_read_returns_content` | 读 `.oryxos/agents/<n>/AGENT.md`，content 含 frontmatter |
| `file_read_file_not_found` | ToolResult.error("file not found: ...") |
| `file_write_creates_file` | 写文件后 `Files.exists(path)` 为 true |
| `file_list_glob_filter` | pattern=`*.md` 只列 .md 文件 |
| `shell_echo_returns_stdout` | `echo "hi"` 返回 payload 含 stdout="hi\n" |
| `shell_rm_blocked` | `rm -rf /` 返回 ToolResult.error("shell command blocked: rm") |
| `shell_timeout` | `sleep 60` + timeout=2 返回 ToolResult.error("shell command timeout") |
| `http_get_wiremock_200` | WireMock `/hello` 返回 200 + body |
| `http_get_blocked_by_sandbox` | `http://api.example.com`（不在白名单）返回 sandbox violation |
| `http_post_sends_body` | WireMock 接收 POST body 一致 |
| `notify_sends_to_feishu` | WireMock `/webhook` 接收 content 一致（004 阶段测试） |
| `save_memory_appends` | 调一次 `save_memory`，MarkdownMemoryStore 多 1 行 |
| `recall_memory_keyword_match` | 调 `recall_memory("foo")`，命中前 5 条含 "foo" |
| `mcp_tool_dispatches` | MCP Tool 经 `DefaultToolExecutor` 调通（[mcp-adapter.md](./mcp-adapter.md)） |
| `builtin_tool_audit_source` | 8 个内置 Tool 调用后 `tool_invocations.source='builtin'` |
| `duplicate_tool_name_fails_at_construction` | 自定义 Tool 重名时 Spring 启动失败（spec FR-015）|
