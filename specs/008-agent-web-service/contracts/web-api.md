# 接口契约：009-agent-web-service

**生成日期**：2026-07-28
**关联**：[spec.md](../spec.md) / [data-model.md](../data-model.md) / [research.md](../research.md)
**对应 [CLAUDE.md §15](../../CLAUDE.md) "REST API" 10 端点**

---

## 概述

本文档定义 008-agent-web-service 的 10 个 REST 端点的字节级契约，包括：
1. HTTP 方法 + 路径
2. 请求体 schema（Content-Type / 必填字段 / 校验规则）
3. 响应体 schema（成功 / 错误）
4. HTTP 状态码映射
5. 审计写入契约（哪些 DB 表被写入）

集成测试（`@WebMvcTest` 模式） MUST 按本契约做端到端断言；任何字段名 / 枚举值 / 类型漂移
都是契约违反，必须修复或通过正式 spec 修订。

---

## 全局约束

| 项 | 值 | 说明 |
|----|----|------|
| Base URL | `/api/v1` | 全局前缀 |
| Content-Type | `application/json; charset=UTF-8` | 所有请求 / 响应 |
| 鉴权 | None | per research.md R-005（CLAUDE.md §15 + 宪法 §II 排除项） |
| CORS | `*`（默认） | 业务方前置 gateway 控制；core 阶段不做 CORS 白名单 |
| 字符集 | UTF-8 | per CLAUDE.md §18 坑 #4 |
| 限流 | 无 | per CLAUDE.md §15 "核心阶段不做限流" |
| 时间格式 | ISO-8601 UTC | `2026-07-28T00:00:00Z` |
| 大小写 | JSON key 严格驼峰 | 与 data-model.md §字段命名规范一致 |
| 错误响应 | 统一 `ErrorResponse` envelope | per data-model.md §实体 10 |

---

## 端点清单

| # | 方法 | 路径 | 用途 | Controller | spec 验收场景 |
|---|------|------|------|------------|-------------|
| 1 | POST | `/api/v1/agents/{name}/invoke` | 业务系统调用 Agent | `AgentsController` | US-1 场景 1, 2, 3, 4 |
| 2 | POST | `/api/v1/sessions` | 创建 Session | `SessionsController` | US-2 场景 1 |
| 3 | POST | `/api/v1/sessions/{id}/messages` | 追加消息 | `SessionsController` | US-2 场景 2 |
| 4 | GET | `/api/v1/sessions/{id}` | 查询 Session | `SessionsController` | US-2 场景 3 |
| 5 | DELETE | `/api/v1/sessions/{id}` | 删除 Session | `SessionsController` | US-2 边界 |
| 6 | GET | `/api/v1/profiles` | Profile 列表 | `ProfilesController` | US-3 场景 1 |
| 7 | GET | `/api/v1/memory` | Memory 元数据 | `MemoryController` | US-3 场景 2 |
| 8 | GET | `/api/v1/tools` | Tool 列表 | `ToolsController` | US-3 场景 3 |
| 9 | GET | `/api/v1/health` | 健康检查 | `SystemController` | US-1 边界 |
| 10 | GET | `/api/v1/info` | 系统信息 | `SystemController` | US-1 边界 |

辅助端点（springdoc 自动暴露，非业务方主用）：
- `GET /v3/api-docs` → OpenAPI 3.1 YAML
- `GET /swagger-ui.html` → Swagger UI HTML
- `GET /actuator/health` → Spring Boot Actuator 默认 health

---

## 端点 1：`POST /api/v1/agents/{name}/invoke`

### 用途
业务系统调用指定 Agent；URL `{name}` 是 Profile 名（path variable）。

### 请求
- **Content-Type**：`application/json`
- **Body schema**（`InvokeRequest`，per data-model.md §实体 1）：
  ```json
  {
    "message": "查一下今天上海天气",
    "sessionId": "0190a3b4-7c8d-7890-abcd-ef1234567890",
    "profileName": "weather-v2",
    "metadata": {"source_system": "crm", "ticket_id": "T-12345"}
  }
  ```
- **必填字段**：`message`
- **可选字段**：`sessionId` / `profileName` / `metadata`

### 响应（200 OK）
- **Body schema**（`InvokeResponse`，per data-model.md §实体 2）：
  ```json
  {
    "sessionId": "0190a3b4-7c8d-7890-abcd-ef1234567890",
    "reply": "今天上海多云 28°C，偏南风 3 级...",
    "iterations": 3,
    "durationMs": 4250,
    "metadata": {"notify_sent": true, "channel": "feishu-ops"}
  }
  ```

### 错误响应
| HTTP | error code | 触发条件 |
|------|-----------|---------|
| 400 | `invalid_request` | Bean Validation 失败（message 为空 / 长度超限） |
| 400 | `invalid_json` | JSON 反序列化失败 |
| 400 | `invalid_path_param` | URL `{name}` 包含非法字符 |
| 404 | `agent_not_found` | `{name}` 在已加载 Profile 列表中不存在 |
| 500 | `internal_error` | AgentService.process() 抛未捕获异常 |
| 504 | `agent_timeout` | ReAct 循环超过 30s 阈值 |

### 审计写入
| 表 | 写入时机 | 字段 |
|----|---------|------|
| `sessions` | process() 完成后 | `session_id`, `profile_name="weather-v2"`, `metadata.source="web"` |
| `tool_invocations` | 每次 Tool 调用 | `tool_name`, `session_id`, `success`, `duration_ms`, `source` |
| `llm_calls` | 每次 LLM 调用 | `provider`, `model`, `tokens_in/out`, `duration_ms` |
| `task_executions` | process() 完成后（无论成败） | `task_id=null`, `session_id`, `trigger_source="web"`, `success`, `duration_ms`, `error_message?` |

### 集成测试断言（per spec SC-001 / SC-002 / SC-003 / SC-004）
- ✅ 200 + InvokeResponse 字段完整（反射断言）
- ✅ 调用 `AgentService.process()` 的 `Method` 对象与 CLI / Scheduler 入口一致（反射 `Method.getDeclaringClass() == AgentService.class`）
- ✅ `sessions.metadata.source == "web"` 字节级（DB 直查）
- ✅ `task_executions.trigger_source == "web"` 字节级（DB 直查）
- ✅ 10 并发调用 → 10 个独立 session_id（无串话）

---

## 端点 2：`POST /api/v1/sessions`

### 用途
预创建 Session（不触发 Agent）；用于业务方在 invoke 前先分配 session_id。

### 请求
- **Content-Type**：`application/json`
- **Body schema**：
  ```json
  {
    "profileName": "weather-agent",
    "metadata": {"customer_id": "C-12345"}
  }
  ```
- **必填字段**：`profileName`
- **可选字段**：`metadata`

### 响应（201 Created）
- **Headers**：`Location: /api/v1/sessions/{id}`
- **Body schema**（`SessionDto`，per data-model.md §实体 3）：
  ```json
  {
    "sessionId": "0190a3b4-7c8d-7890-abcd-ef1234567890",
    "profileName": "weather-agent",
    "createdAt": "2026-07-28T00:00:00Z",
    "updatedAt": "2026-07-28T00:00:00Z",
    "messageCount": 0,
    "metadata": {"source": "web", "customer_id": "C-12345"},
    "history": []
  }
  ```

### 错误响应
| HTTP | error code | 触发条件 |
|------|-----------|---------|
| 400 | `invalid_request` | `profileName` 缺失或为空 |
| 400 | `invalid_json` | JSON 反序列化失败 |
| 404 | `agent_not_found` | `profileName` 在已加载 Profile 列表中不存在 |
| 500 | `internal_error` | Session 创建失败 |

### 审计写入
| 表 | 写入时机 | 字段 |
|----|---------|------|
| `sessions` | 立即 | `session_id`, `profile_name`, `metadata.source="web"`, `created_at`, `updated_at`, `history=[]` |

### 集成测试断言
- ✅ 201 + Location header 字节级正确
- ✅ `sessions.metadata.source == "web"` 字节级
- ✅ 返回的 `sessionId` 与 DB 主键一致
- ✅ `messageCount == 0` 反射断言

---

## 端点 3：`POST /api/v1/sessions/{id}/messages`

### 用途
向已有 Session 追加用户消息（不触发 Agent）；用于业务方分步注入上下文。

### 请求
- **Content-Type**：`application/json`
- **URL 参数**：`{id}` UUID v7 格式
- **Body schema**（`MessageDto`，per data-model.md §实体 4）：
  ```json
  {
    "role": "user",
    "content": "今天有什么新闻？",
    "timestamp": "2026-07-28T01:00:00Z"
  }
  ```
- **必填字段**：`role`, `content`
- **可选字段**：`timestamp`

### 响应（201 Created）
- **Body schema**（追加的 `MessageDto` + 新 `updatedAt`）：
  ```json
  {
    "sessionId": "0190a3b4-7c8d-7890-abcd-ef1234567890",
    "messageCount": 1,
    "updatedAt": "2026-07-28T01:00:00Z",
    "message": {
      "role": "user",
      "content": "今天有什么新闻？",
      "timestamp": "2026-07-28T01:00:00Z"
    }
  }
  ```

### 错误响应
| HTTP | error code | 触发条件 |
|------|-----------|---------|
| 400 | `invalid_request` | `role` 非法值（非 user/assistant/tool） |
| 400 | `invalid_json` | JSON 反序列化失败 |
| 400 | `invalid_path_param` | `{id}` 不是 UUID 格式 |
| 404 | `session_not_found` | `{id}` 在 sessions 表中不存在 |
| 500 | `internal_error` | 写入失败 |

### 审计写入
| 表 | 写入时机 | 字段 |
|----|---------|------|
| `sessions` | 立即 | `history` append 一条；`updated_at` 更新 |

### 集成测试断言
- ✅ 201 + 消息追加成功
- ✅ `sessions.history.length` 增加 1
- ✅ `sessions.updated_at` 时间戳更新

---

## 端点 4：`GET /api/v1/sessions/{id}`

### 用途
查询 Session 完整信息（含历史）。

### 请求
- **URL 参数**：`{id}` UUID v7 格式
- **Query 参数**：
  - `includeHistory`（可选，默认 true）：是否包含 history（false 时 history 为空数组）

### 响应（200 OK）
- **Body schema**（`SessionDto`）：
  ```json
  {
    "sessionId": "0190a3b4-7c8d-7890-abcd-ef1234567890",
    "profileName": "weather-agent",
    "createdAt": "2026-07-28T00:00:00Z",
    "updatedAt": "2026-07-28T01:30:00Z",
    "messageCount": 5,
    "metadata": {"source": "web", "customer_id": "C-12345"},
    "history": [
      {"role": "user", "content": "查天气", "timestamp": "2026-07-28T00:00:00Z"},
      {"role": "assistant", "content": "...", "timestamp": "2026-07-28T00:00:05Z"},
      ...
    ]
  }
  ```

### 错误响应
| HTTP | error code | 触发条件 |
|------|-----------|---------|
| 400 | `invalid_path_param` | `{id}` 不是 UUID 格式 |
| 404 | `session_not_found` | `{id}` 在 sessions 表中不存在 |

### 审计写入
无（纯读）。

### 集成测试断言
- ✅ 200 + SessionDto 字段完整
- ✅ `metadata.source` 字节级正确（CLI 触发是 "cli"，web 触发是 "web"）
- ✅ `includeHistory=false` → history 为空数组（不查 DB / 不返回）

---

## 端点 5：`DELETE /api/v1/sessions/{id}`

### 用途
软删除 Session（与 006-memory-layer 删除契约对齐）。

### 请求
- **URL 参数**：`{id}` UUID v7 格式

### 响应（204 No Content）
- 空 body

### 错误响应
| HTTP | error code | 触发条件 |
|------|-----------|---------|
| 400 | `invalid_path_param` | `{id}` 不是 UUID 格式 |
| 404 | `session_not_found` | `{id}` 在 sessions 表中不存在 |
| 500 | `internal_error` | DB 写入失败 |

### 审计写入
| 表 | 写入时机 | 字段 |
|----|---------|------|
| `sessions` | 立即 | `deleted_at = now`（软删除标记）；**不**真删 |

### 集成测试断言
- ✅ 204 + 空 body
- ✅ 后续 `GET /api/v1/sessions/{id}` 返回 404 session_not_found
- ✅ DB 中 `deleted_at IS NOT NULL`（验证软删除）

---

## 端点 6：`GET /api/v1/profiles`

### 用途
列出已加载的 Profile（只读）。

### 请求
- 无 body / 无 query 参数

### 响应（200 OK）
- **Body schema**（`List<ProfileDto>`，per data-model.md §实体 5）：
  ```json
  [
    {
      "name": "weather-agent",
      "description": "每日天气查询",
      "agentName": "WeatherBot",
      "providerName": "deepseek",
      "model": "deepseek-chat",
      "toolCount": 3,
      "scheduleCount": 1,
      "notifyChannelCount": 1,
      "bootstrapFiles": ["AGENTS.md", "USER.md"]
    },
    ...
  ]
  ```

### 错误响应
| HTTP | error code | 触发条件 |
|------|-----------|---------|
| 500 | `internal_error` | ContextLoader 未初始化 |

### 审计写入
无（纯读）。

### 集成测试断言
- ✅ 200 + 列表非空（包含至少 daily-weather-agent）
- ✅ `toolCount` / `scheduleCount` 等计数与 Profile YAML 一致

---

## 端点 7：`GET /api/v1/memory`

### 用途
查询 Memory 元数据（backend 类型 + 大小）。

### 请求
- 无 body / 无 query 参数

### 响应（200 OK）
- **Body schema**（`MemoryDto`，per data-model.md §实体 7）：
  ```json
  {
    "backend": "markdown",
    "coreEntries": 42,
    "archiveEntries": 8,
    "filePath": "/path/to/.oryxos/memory/MEMORY.md"
  }
  ```

### 错误响应
| HTTP | error code | 触发条件 |
|------|-----------|---------|
| 500 | `internal_error` | MemoryService 未初始化 |

### 审计写入
无（纯读）。

### 集成测试断言
- ✅ 200 + backend 字段枚举（markdown / sqlite / mem0）
- ✅ coreEntries 与 archiveEntries 整数
- ✅ markdown backend 时 filePath 非空

---

## 端点 8：`GET /api/v1/tools`

### 用途
列出已注册的 Tool（含 builtin / mcp / java_bean 三类）。

### 请求
- **Query 参数**：
  - `source`（可选）：过滤 `source` 字段（`builtin` / `mcp` / `java_bean`）

### 响应（200 OK）
- **Body schema**（`List<ToolDto>`，per data-model.md §实体 6）：
  ```json
  [
    {
      "name": "file_read",
      "description": "读取文件内容",
      "source": "builtin",
      "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}}
    },
    {
      "name": "integration__echo",
      "description": "Echo MCP tool",
      "source": "mcp"
    }
  ]
  ```

### 错误响应
| HTTP | error code | 触发条件 |
|------|-----------|---------|
| 400 | `invalid_request` | `source` 参数非法值 |
| 500 | `internal_error` | ToolRegistry 未初始化 |

### 审计写入
无（纯读）。

### 集成测试断言
- ✅ 200 + 至少 9 个 builtin tool
- ✅ `source` 枚举与 `tool_invocations.source` 字节级一致
- ✅ `source=mcp` 过滤生效

---

## 端点 9：`GET /api/v1/health`

### 用途
轻量级健康检查（运维监控用，频率高）。

### 请求
- 无 body / 无 query 参数

### 响应（200 OK）
- **Body schema**（`HealthDto`，per data-model.md §实体 8）：
  ```json
  {
    "status": "UP",
    "uptimeMs": 125000,
    "version": "0.1.0-SNAPSHOT",
    "components": {
      "db": {"status": "UP"},
      "diskSpace": {"status": "UP", "details": {"free": "10GB"}}
    }
  }
  ```

### 响应（503 Service Unavailable）
- **Body schema**：
  ```json
  {
    "status": "DOWN",
    "uptimeMs": 5000,
    "version": "0.1.0-SNAPSHOT",
    "components": {
      "db": {"status": "DOWN", "details": {"error": "Connection refused"}}
    }
  }
  ```

### 错误响应
无（健康检查端点本身永远返回 200 或 503）。

### 审计写入
无。

### 集成测试断言
- ✅ 启动后 `GET /api/v1/health` → 200 + status="UP"
- ✅ P95 ≤ 50ms（100 次迭代）
- ✅ DB 不可达 → 503 + status="DOWN"

---

## 端点 10：`GET /api/v1/info`

### 用途
系统版本 + 启动元数据（诊断用）。

### 请求
- 无 body / 无 query 参数

### 响应（200 OK）
- **Body schema**（`InfoDto`，per data-model.md §实体 9）：
  ```json
  {
    "name": "oryxos",
    "version": "0.1.0-SNAPSHOT",
    "javaVersion": "21.0.5",
    "osName": "Windows 11",
    "agents": 3,
    "tools": 11,
    "uptimeMs": 60000
  }
  ```

### 错误响应
| HTTP | error code | 触发条件 |
|------|-----------|---------|
| 500 | `internal_error` | Spring 上下文未完全启动 |

### 审计写入
无。

### 集成测试断言
- ✅ 200 + 字段类型正确
- ✅ `agents` 数等于 `GET /api/v1/profiles` 列表长度
- ✅ `tools` 数等于 `GET /api/v1/tools` 列表长度

---

## 全局错误响应契约

任何 4xx / 5xx 响应 MUST 遵循 `ErrorResponse` shape（per data-model.md §实体 10）：

```json
{
  "error": "agent_not_found",
  "detail": "Agent 'foo-agent' not found in loaded profiles",
  "field": null
}
```

字段缺失规则：
- `error` 永远存在
- `detail` 永远存在（人类可读；不含 stack trace）
- `field` 仅表单校验失败时存在，其他情况为 null（Jackson `@JsonInclude(NON_NULL)` 配置）

---

## OpenAPI 端点（springdoc 自动暴露）

| 端点 | 类型 | 说明 |
|------|------|------|
| `GET /v3/api-docs` | YAML / JSON | OpenAPI 3.1 spec |
| `GET /swagger-ui.html` | HTML | Swagger UI |
| `GET /v3/api-docs.yaml` | YAML | 与 `/v3/api-docs` 同步 |

### 业务方契约固化方式
集成测试 MUST 断言：
- `GET /v3/api-docs.yaml` 包含所有 10 个端点路径
- 每个端点的 requestBody schema 与本契约字段名一致
- 每个端点的 responses schema 与本契约字段名一致
- 任何字段名漂移（如 `session_id` → `sessionId`）→ 测试失败 → 必须修复

---

## 引用

- [spec.md](../spec.md) §关键实体 + 验收场景 — 业务需求来源
- [data-model.md](../data-model.md) — 10 个 DTO 实体 schema
- [research.md](../research.md) — R-006 端点对齐 + R-007 错误响应 + R-008 session 来源标记
- [CLAUDE.md §15](../../CLAUDE.md) — REST API 10 端点定义来源
- [CLAUDE.md §13](../../CLAUDE.md) — SQLite 5 张表 day-one DDL
- [005-tool-system/contracts/tool-executor.md](../005-tool-system/contracts/tool-executor.md) — `tool_invocations.source` 枚举
- [007-sandbox-whitelist/contracts/sandbox.md](../007-sandbox-whitelist/contracts/sandbox.md) — error_message 不含 stack trace
- [008-agent-scheduler/data-model.md §实体 4](../008-agent-scheduler/data-model.md) — `metadata.source` 三选一契约