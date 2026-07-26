# Quickstart：Tool 体系端到端验证

**目的**：用 6 个可重复运行的场景，证明 OryxOS Tool 系统（9 个内置 Tool + MCP 接入 + 审计 + 沙箱）从 CLI / Web Service 入口调通。这是 spec US-4 的 day-one 验收路径。
**创建日期**：2026-07-26
**特性**：[spec.md §SC-001 / §SC-002 / §SC-003 / §SC-004 / §SC-005 / §SC-006](../spec.md) | [research.md R-12](./research.md)
**前置**：[plan.md](./plan.md) | [data-model.md](./data-model.md) | [contracts/](./contracts/)

---

## 0. 目标与前置

**目标**：

- 跑通 [spec.md §SC-001](../spec.md)（3 个 Demo Agent 端到端可演示）
- 跑通 [spec.md §SC-002](../spec.md)（审计行数 = Tool 调用次数）
- 跑通 [spec.md §SC-003](../spec.md)（沙箱拦截可观测）
- 跑通 [spec.md §SC-004](../spec.md)（9 个 Tool 全部注册）
- 跑通 [spec.md §SC-005](../spec.md)（白名单变更即生效）
- 跑通 [spec.md §SC-006](../spec.md)（MCP Tool 接入成功）

**前置**：

- JDK 21 已装（`java -version` → 21.x）
- Maven 已装（`mvn -version`）
- 项目根目录：`d:\code\java\oryxos`（Windows）或 `/code/java/oryxos`（Linux）
- `.oryxos/` 目录已 `init`（`mvn -pl oryxos-cli exec:java -Dexec.args="init"`）

---

## 1. 准备：WireMock + 本地工作区

### 1.1 启动 WireMock（HTTP Tool 测试桩）

WireMock 是 HTTP Tool 的测试桩，用于模拟"未在白名单的真实 HTTP server"。本地启动：

```bash
# 在另一个 terminal 启动 WireMock（监听 8089）
java -jar wiremock-standalone-3.5.4.jar --port 8089
```

**mock 规则**（保存到 `wiremock/mappings/tool-test.json`）：

```json
{
  "request": {
    "method": "GET",
    "urlPath": "/api/hello"
  },
  "response": {
    "status": 200,
    "headers": { "Content-Type": "application/json" },
    "jsonBody": { "message": "hello from wiremock" }
  }
}
```

### 1.2 准备沙箱白名单

编辑 `.oryxos/config/application.yaml`：

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-hosts:
          - localhost
          - 127.0.0.1
          - api.deepseek.com      # daily-weather Demo 需要
          - api.moonshot.cn       # kimi 测试用
```

### 1.3 准备临时工作目录

```bash
mkdir -p .oryxos/agents/daily-weather
mkdir -p .oryxos/agents/daily-tech
mkdir -p .oryxos/agents/daily-github
mkdir -p .oryxos/test-fixtures
echo "fixture content" > .oryxos/test-fixtures/sample.txt
```

---

## 2. 场景 1 — ToolListCommand（验证 9 个 Tool 全部注册）

**目的**：验证 `ToolRegistry` 在 Spring Boot 启动后含 9 个内置 Tool + N 个 MCP Tool。

```bash
# 启动 Spring Boot（确保所有 Tool Bean 已装配）
mvn -pl oryxos-boot spring-boot:run &
SPRING_PID=$!

# 等待 10 秒（启动期 MCP 握手 + Tool 装配）
sleep 10

# 列出所有 Tool
mvn -pl oryxos-cli exec:java -Dexec.args="tool list"

# 期望输出：
# ┌──────────────────┬────────────────────────────────────┬────────┬────────────┐
# │ Tool Name        │ Description                        │ Source │ Registered │
# ├──────────────────┼────────────────────────────────────┼────────┼────────────┤
# │ file_read        │ 读取本地文本文件内容                │ builtin│ ✓          │
# │ file_write       │ 写入本地文本文件                    │ builtin│ ✓          │
# │ file_list        │ 列出目录下条目                      │ builtin│ ✓          │
# │ shell            │ 在受限白名单内执行 shell 命令       │ builtin│ ✓          │
# │ http_get         │ 发起 HTTP GET 请求（受沙箱校验）     │ builtin│ ✓          │
# │ http_post        │ 发起 HTTP POST 请求（受沙箱校验）    │ builtin│ ✓          │
# │ notify           │ 向已配置的群机器人 webhook 推送消息  │ builtin│ ✓          │
# │ save_memory      │ 写入长期记忆                        │ builtin│ ✓          │
# │ recall_memory    │ 按关键词检索长期记忆                │ builtin│ ✓          │
# └──────────────────┴────────────────────────────────────┴────────┴────────────┘

# 关闭 Spring Boot
kill $SPRING_PID
```

**验收**：[spec.md §SC-004](../spec.md) — 9 行输出，每行 `Source=builtin`，`Registered=✓`。

---

## 3. 场景 2 — File Tool（读 / 写 / 列）

**目的**：验证 3 个 File Tool 的核心路径 + 错误路径。

```bash
# 启动 Spring Boot
mvn -pl oryxos-boot spring-boot:run &
SPRING_PID=$!
sleep 10

# 2.1 file_read 成功路径
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-github --tool file_read --args path=.oryxos/test-fixtures/sample.txt'

# 期望输出：content="fixture content\n", payload={path: ..., size_bytes: 16}

# 2.2 file_read 失败路径
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-github --tool file_read --args path=/etc/passwd'

# 期望输出：success=false, errorMessage="file not found: /etc/passwd"（Windows 上可能是 "file not found: C:\etc\passwd"）

# 2.3 file_write + file_read 联动
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-github --tool file_write --args path=.oryxos/test-fixtures/output.txt,content="written by agent"'
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-github --tool file_read --args path=.oryxos/test-fixtures/output.txt'

# 期望：第二次读到的 content="written by agent"

# 2.4 file_list 过滤
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-github --tool file_list --args path=.oryxos/test-fixtures,pattern="*.txt"'

# 期望：entries=["output.txt", "sample.txt"]

# 关闭 Spring Boot
kill $SPRING_PID
```

**验收**：

- 2.1 / 2.3 / 2.4 返回 `success=true` + 正确 payload
- 2.2 返回 `success=false` + errorMessage

---

## 4. 场景 3 — Shell Tool（含黑名单拦截）

**目的**：验证 Shell Tool 的正常执行 + 危险命令拦截 + 超时。

```bash
mvn -pl oryxos-boot spring-boot:run &
SPRING_PID=$!
sleep 10

# 3.1 安全命令
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-github --tool shell --args command="echo hello"'

# 期望：success=true, payload={exit_code: 0, stdout: "hello\n", stderr: "", duration_ms: <n>}

# 3.2 危险命令（黑名单拦截）
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-github --tool shell --args command="rm -rf /"'

# 期望：success=false, errorMessage="shell command blocked: rm is in dangerous-commands"

# 3.3 超时
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-github --tool shell --args command="sleep 60",timeout_seconds=2'

# 期望：success=false, errorMessage="shell command timeout after 2 seconds: sleep 60"

kill $SPRING_PID
```

**验收**：

- 3.1 安全命令正常返回
- 3.2 黑名单拦截（[spec.md §SC-003](../spec.md) + [research.md R-03](./research.md)）
- 3.3 超时控制生效

---

## 5. 场景 4 — HTTP Tool（含沙箱拦截）

**目的**：验证 HTTP Tool 与 `WhitelistSandbox` 联动 —— 白名单内通过 / 白名单外拦截。

```bash
# 确保 WireMock 仍在运行（场景 1.1）
mvn -pl oryxos-boot spring-boot:run &
SPRING_PID=$!
sleep 10

# 4.1 白名单内（WireMock）
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-weather --tool http_get --args url=http://localhost:8089/api/hello'

# 期望：success=true, payload={status_code: 200, body: '{"message":"hello from wiremock"}', duration_ms: <n>}

# 4.2 白名单外（沙箱拦截）
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-weather --tool http_get --args url=http://api.example.com/test'

# 期望：success=false, errorMessage="sandbox violation: host not in whitelist (HTTP_REQUEST: http://api.example.com/test)"

# 4.3 IP 字面量（始终拒绝）
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-weather --tool http_get --args url=http://1.2.3.4/test'

# 期望：success=false, errorMessage="sandbox violation: ip literal rejected: 1.2.3.4"

# 4.4 白名单变更后立即生效（spec SC-005）
# 追加 api.example.com 到 application.yaml 的白名单 → 重启 Spring → 4.2 改为 success=true

# 4.5 http_post 简单测试
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-weather --tool http_post --args url=http://localhost:8089/api/echo,body="{\"foo\":\"bar\"}"'

# 期望：WireMock 收到 POST body `{"foo":"bar"}`

kill $SPRING_PID
```

**验收**：

- 4.1 / 4.5 通过白名单，正常返回
- 4.2 / 4.3 沙箱拦截（[spec.md §SC-005](../spec.md)）
- 4.4 修改白名单后**重启**生效（spec SC-005 明确"无需改代码"）

---

## 6. 场景 5 — Memory Tool（跨调用保存 + 检索）

**目的**：验证 Memory Tool 与 `MemoryService` 联动（[research.md R-05](./research.md)）。

```bash
mvn -pl oryxos-boot spring-boot:run &
SPRING_PID=$!
sleep 10

# 5.1 save_memory
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-tech --tool save_memory --args content="用户偏好 markdown 格式摘要",scope="core"'

# 期望：success=true, payload={operation: "save", scope: "core", entry_count: 1}

# 5.2 recall_memory 命中
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-tech --tool recall_memory --args query="markdown",top_k=3'

# 期望：success=true, payload={operation: "recall", snippets: ["用户偏好 markdown 格式摘要", ...]}

# 5.3 save_memory 写 archive 区
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-tech --tool save_memory --args content="2024-01-01 历史归档",scope="archive"'

# 期望：success=true, payload={scope: "archive"}

# 验证存储：cat .oryxos/memory/MEMORY.md 应该看到上述两条记忆

kill $SPRING_PID
```

**验收**：

- 5.1 / 5.3 写入 `MEMORY.md`（核心区 + 归档区）
- 5.2 关键词检索命中

---

## 7. 场景 6 — MCP Tool（含启动期握手 + 运行期调用）

**目的**：验证 MCP server 启动期 handshake + 运行期 `tools/call`。

### 7.1 准备：mock MCP server

启动一个 mock MCP server（HTTP 模式监听 8081）。可以用任意 MCP 兼容 server，这里用 `mcp-server-everything`（社区示例）：

```bash
# 用 uvx 启动 mock MCP server（如未装 uvx 则用 docker run）
uvx mcp-server-everything --port 8081 --transport sse &
MCP_PID=$!
sleep 5
```

或在 `mcp_servers.yaml` 配 stdio 模式 + 一个 mock JSON-RPC 应答脚本（参考 [research.md R-04](./research.md)）。

### 7.2 配置 mcp_servers.yaml

```yaml
# .oryxos/mcp_servers.yaml
servers:
  - name: mock-mcp
    transport: http
    url: http://localhost:8081/sse
```

### 7.3 启动 OryxOS + 验证 MCP Tool

```bash
mvn -pl oryxos-boot spring-boot:run &
SPRING_PID=$!
sleep 10

# 7.3.1 列出所有 Tool（应含 MCP Tool，source=mcp）
mvn -pl oryxos-cli exec:java -Dexec.args="tool list" | grep "mcp"

# 期望：1+ 行 source=mcp

# 7.3.2 通过 Web Service 调 MCP Tool（演示给 LLM）
curl -X POST http://localhost:8080/api/v1/agents/daily-tech/invoke \
  -H "Content-Type: application/json" \
  -d '{"message": "用 mock-mcp 的 echo tool 测试一下", "tools": ["echo"]}'

# 期望：响应包含 ToolResult 内容（来自 mock-mcp）

# 7.3.3 MCP server 挂掉 → ToolResult.error
kill $MCP_PID
sleep 2
curl -X POST http://localhost:8080/api/v1/agents/daily-tech/invoke \
  -H "Content-Type: application/json" \
  -d '{"message": "再调一次 echo", "tools": ["echo"]}'

# 期望：success=false, errorMessage="mcp connection lost: mock-mcp"

kill $SPRING_PID
```

**验收**：

- 7.3.1 启动期 handshake 成功 → MCP Tool 注册
- 7.3.2 MCP Tool 经 Web Service 调用成功
- 7.3.3 MCP server 挂掉 → ToolResult.error（[spec.md §US-3 场景 4](../spec.md)）

---

## 8. 验证审计（Tool 调用次数 = tool_invocations 行数）

```bash
# 启动期写一行 SQL 脚本
sqlite3 .oryxos/oryxos.db <<EOF
.headers on
.mode column
SELECT
  tool_name,
  source,
  success,
  COUNT(*) AS count
FROM tool_invocations
GROUP BY tool_name, source, success
ORDER BY count DESC;
EOF
```

**期望**：每个 Tool 在前面的场景中至少被调一次；每个 Tool 名对应至少 1 行审计。`source` 列对应 builtin / mcp。

**对应 spec FR-005 / FR-007**：[spec.md §FR-005](../spec.md)（每次 Tool 调用必产 1 行审计）；同一 Tool 在同一 Session 内**不**应重复计数 ≥ 2 行（spec FR-007 "tool 被调两次"的反例）。

---

## 9. 验证沙箱拦截可观测（SC-005）

```bash
# 启动 Spring Boot
mvn -pl oryxos-boot spring-boot:run &
SPRING_PID=$!
sleep 10

# 调一个被沙箱拒掉的 Tool
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-weather --tool http_get --args url=http://api.blocked.com/test'

# 期望输出含 sandbox violation 字样

# 验证审计行
sqlite3 .oryxos/oryxos.db "SELECT tool_name, success, error_message FROM tool_invocations WHERE tool_name='http_get' AND success=0;"

# 期望：error_message 含 "sandbox violation"

kill $SPRING_PID
```

**对应**：[spec.md §SC-005](../spec.md) + [research.md R-11](./research.md)。

---

## 10. 端到端 Demo（spec SC-001）

最后跑通 3 个 Demo Agent（[CLAUDE.md §11](../../../CLAUDE.md)）：

### Demo 一：每日天气（`daily-weather`）

`.oryxos/agents/daily-weather/AGENT.md`：

```yaml
---
name: daily-weather
description: 每日天气简报
provider:
  name: deepseek
  model: deepseek-chat
tools: [http_get, notify]
schedules:
  - id: morning
    cron: "0 8 * * *"
    zone: "Asia/Shanghai"
    message: "查询今天天气并通知"
---
```

触发：

```bash
mvn -pl oryxos-cli exec:java -Dexec.args='chat --agent daily-weather'
```

### Demo 二：每日科技日报（`daily-tech`）

```yaml
---
name: daily-tech
description: 每日科技日报（跨对话记忆用户偏好）
provider:
  name: deepseek
  model: deepseek-chat
tools: [http_get, recall_memory, save_memory, notify]
schedules:
  - id: daily
    cron: "0 9 * * *"
    zone: "Asia/Shanghai"
    message: "汇总科技日报"
---
```

触发：同上命令，`--agent daily-tech`。

### Demo 三：每日 GitHub 日报（`daily-github`）

```yaml
---
name: daily-github
description: 每日 GitHub 仓库变更日报
provider:
  name: deepseek
  model: deepseek-chat
tools: [shell, file_read, file_list, notify]
schedules:
  - id: daily
    cron: "0 10 * * *"
    zone: "Asia/Shanghai"
    message: "汇总仓库昨日提交"
---
```

**对应**：[CLAUDE.md §11](../../../CLAUDE.md) 3 个 Demo + [spec.md §SC-001](../spec.md)。

---

## 11. 总结验证清单

| 场景 | 对应 spec / research | 验收 |
|------|---------------------|------|
| 1. ToolListCommand | [spec.md §SC-004](../spec.md) | 9 行 builtin Tool 输出 |
| 2. File Tool | [contracts/builtin-tools.md §1-3](./contracts/builtin-tools.md) | 成功 + 失败路径覆盖 |
| 3. Shell Tool | [research.md R-03](./research.md) | 黑名单 + 超时拦截 |
| 4. HTTP Tool | [spec.md §SC-005](../spec.md) | 白名单内通过 + 外拦截 + IP 拒绝 |
| 5. Memory Tool | [research.md R-05](./research.md) | 跨调用保存 + 检索 |
| 6. MCP Tool | [spec.md §US-3](../spec.md) | 启动期握手 + 运行期调用 + 挂掉失败 |
| 审计行数 | [spec.md §SC-002](../spec.md) | 调 1 次 = 1 行 |
| 沙箱可观测 | [spec.md §SC-005](../spec.md) | 错误信息含 "sandbox violation" |
| 3 Demo | [spec.md §SC-001](../spec.md) | 端到端跑通 |

---

## 12. 故障排查（Troubleshooting）

| 现象 | 可能原因 | 处理 |
|------|---------|------|
| `tool list` 输出 < 9 行 | Tool Bean 未注册 | 检查 `@Component` 注解 + `@ComponentScan` 路径 |
| MCP Tool 没出现在 list | `mcp_servers.yaml` 配错 / server 不可达 | 检查日志：`mcp server startup failed: <name>` |
| HTTP Tool 总是 sandbox violation | `localhost` 不在白名单 | 检查 `application.yaml` `oryxos.tool.sandbox.http.allowed-hosts` |
| Shell Tool 拒绝所有命令 | 黑名单误配 | 检查 `dangerous-commands` 列表 |
| Audit 行数 = 0 | `ToolAuditWriter` 未注入 | 检查 `ToolAuditWriter` Bean 是否在 Spring 上下文 |
| `tool_invocations` 没 `source` 列 | V3 DDL 未执行 | 手动跑 `V3__add_tool_source.sql` |

---

## 13. 不在本 quickstart 范围

- ❌ 性能压测（spec NFR-001 P95 ≤ 30s 留给 tasks.md 阶段压测）
- ❌ 沙箱单元测试（`sandbox.md` §8 测试矩阵由 tasks.md 阶段实现）
- ❌ Profile 装配 / Tool 白名单 YAML 解析（继承 [003-cli-commands spec](../003-cli-commands/spec.md)）
- ❌ Web Service 端到端（US-5 阶段；本 quickstart 仅用 `chat` 命令）

---

## 14. 引用

- [spec.md](./spec.md)（功能规格）
- [research.md](./research.md)（决策 R-01..R-12）
- [data-model.md](./data-model.md)（V3 DDL 等 schema 演进）
- [plan.md](./plan.md)（技术上下文）
- [contracts/oryx-tool.md](./contracts/oryx-tool.md)（接口契约）
- [contracts/tool-executor.md](./contracts/tool-executor.md)（派发 + 审计契约）
- [contracts/sandbox.md](./contracts/sandbox.md)（沙箱契约）
- [contracts/builtin-tools.md](./contracts/builtin-tools.md)（9 Tool schema）
- [contracts/mcp-adapter.md](./contracts/mcp-adapter.md)（MCP 契约）
- [CLAUDE.md §11](../../../CLAUDE.md)（三个 Demo）
