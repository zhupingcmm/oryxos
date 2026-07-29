# 数据模型：009-agent-web-service

**生成日期**：2026-07-28
**关联**：[spec.md](spec.md) / [research.md](research.md) / [plan.md](plan.md)

---

## 概述

008-agent-web-service 在 OryxOS 已有的 SQLite 5 张表（[CLAUDE.md §13](../../CLAUDE.md)）基础上
**不新增表、不修改 DDL**，只复用既有审计基础设施 + 扩展 `sessions.metadata.source` 取值范围
（已有 `"cli"` / `"scheduler"`，本 spec 新增 `"web"`）。REST 层的"数据模型"主要是**传输层 DTO**
（request / response / error envelope），与持久化模型严格解耦。

设计哲学：
- **DTO 是传输层对象**，不是持久化实体——DTO 字段命名按 REST 业务语义（如 `agentName`），持久化
  字段命名按数据库规范（如 `profile_name`）。Spring MVC 自动通过 record + Jackson 做映射。
- **复用既有 5 表**：sessions / tool_invocations / llm_calls / scheduled_tasks / task_executions
  已由 006/008 阶段落地，本 spec 不重新声明 DDL，只声明"REST 触发 → 哪些表被写入"。
- **审计 day-one**：每次 `POST /api/v1/agents/{name}/invoke` MUST 落地到 `sessions`（via
  `AgentService.process()`）+ `tool_invocations`（via `DefaultToolExecutor`）+ `llm_calls`（via
  Spring AI interceptor）三表，与 005/006/008 三契约字节级对齐。

---

## 实体 1：`InvokeRequest`（REST 请求 DTO）

**来源**：REST 请求体 JSON（`POST /api/v1/agents/{name}/invoke`）
**包路径**：`io.oryxos.web.dto.InvokeRequest`（record class）
**生命周期**：HTTP 请求进入 → Jackson 反序列化 → Controller 校验 → 调 `AgentService.process()` 后即丢弃

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | String | ✅ | 用户消息原文字符串；非空（trim 后 ≥1 字符）；≤ 16 KB（FR-009 同步调用 + 防 DOS） |
| `sessionId` | String | ❌（默认 null） | 可选指定已有 Session ID；null → `SessionFactory` 创建新 Session；非法 UUID → 400 invalid_path_param |
| `profileName` | String | ❌（默认 path 参数） | 可选覆盖 URL path 中的 `{name}`；为业务方提供 agent 路由别名能力（FR-001） |
| `metadata` | Map<String, Object> | ❌（默认 empty） | 自定义附加元数据；写入 `sessions.metadata.custom` 字段；大小 ≤ 4 KB |

**校验规则**：
- `message` 非空校验：`@NotBlank` + 长度校验 → 失败抛 `MethodArgumentNotValidException` → 400 invalid_request
- `sessionId` 格式校验：`@Pattern(regexp="^[0-9a-fA-F-]{36}$")` → 失败同上
- `metadata` 大小限制：Jackson 序列化后 ≤ 4 KB → 失败同上
- 业务校验（Agent 名存在 / Profile 解析成功）：由 `AgentService.process()` 抛 `AgentNotFoundException` → 404 agent_not_found

**字段命名规则**（对齐既有 005/006/008 契约）：
- REST 字段用驼峰（`message` / `sessionId` / `profileName`）—— Java record 的 component name 即 JSON key
- 不接受 snake_case（`session_id`）或 kebab-case（`session-id`）—— Spring Boot 默认 Jackson 配置锁定驼峰

---

## 实体 2：`InvokeResponse`（REST 响应 DTO）

**来源**：REST 响应体 JSON
**包路径**：`io.oryxos.web.dto.InvokeResponse`（record class）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | String | ✅ | 本次调用的 Session UUID；与 `sessions.session_id` 主键字节级一致 |
| `reply` | String | ✅ | Agent 最终回复文本；可空字符串（Agent 主动沉默场景） |
| `iterations` | Integer | ✅ | ReAct 循环实际迭代次数；0 = Agent 直接无工具调用回复；> 0 = 至少一次工具调用 |
| `durationMs` | Long | ✅ | 从 `AgentService.process()` 进入 → 返回的端到端耗时（毫秒） |
| `metadata` | Map<String, Object> | ❌（默认 empty） | Agent 输出的额外结构化结果（如工具调用摘要、Notify 推送状态等） |

**与持久化字段映射**（仅供审计，不在 REST 暴露 DB 列名）：

| DTO 字段 | 持久化来源 | 说明 |
|---------|-----------|------|
| `sessionId` | `sessions.session_id` | UUID v7（时间排序） |
| `reply` | `sessions.history[]` 最后一条 AssistantMessage.content | ReAct 循环最后一次 LLM 响应 |
| `iterations` | `sessions.history[]` 中 `role=assistant` + tool_calls.length 计数 | 派生字段，不存独立列 |
| `durationMs` | 运行时计算：`process()` 入口 System.nanoTime() 出口差 | 不落库（审计由 `task_executions.duration_ms` 承载，但本 spec 不强求） |

**为什么 reply 不从 `llm_calls.response_content` 反查**：
- 性能：`llm_calls` 表是审计表，每次 ReAct 迭代 1 行；N 轮迭代 → N 行；反查需要 MAX(id) + 文本解码
- 语义：`llm_calls` 是 LLM 协议层记录（含 token 计数 / model version），`reply` 是业务语义层
- 解耦：将来 LLM 协议升级（如改 streaming）不影响 REST reply 字段

---

## 实体 3：`SessionDto`（REST 查询响应 DTO）

**来源**：`GET /api/v1/sessions/{id}` 响应体
**包路径**：`io.oryxos.web.dto.SessionDto`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `sessionId` | String | ✅ | UUID v7 |
| `profileName` | String | ✅ | Agent 名 |
| `createdAt` | String (ISO-8601 UTC) | ✅ | `sessions.created_at` |
| `updatedAt` | String (ISO-8601 UTC) | ✅ | `sessions.updated_at` |
| `messageCount` | Integer | ✅ | `sessions.history[]` 长度 |
| `metadata` | Map<String, Object> | ✅ | 完整 `sessions.metadata` JSON；含 `source` / `task_id`（可选） |
| `history` | List<MessageDto> | ❌（默认 lazy） | 完整对话历史；可选包含（spec FR-012 标 SHOULD，业务方可省去 history 走详情接口） |

**字段对应**（bytes-level 对齐持久化）：
- `metadata.source` 枚举：必含 `"cli"` / `"web"` / `"scheduler"` 三选一（spec.md §实体 + 008 data-model.md §实体 4）
- `metadata.task_id` 仅在 `source="scheduler"` 时非空
- `history[i].role` 枚举：`"user"` / `"assistant"` / `"tool"` —— 与 008-agent-scheduler 字段枚举对齐

---

## 实体 4：`MessageDto`（REST 会话消息 DTO）

**来源**：`POST /api/v1/sessions/{id}/messages` 请求体 + `GET /api/v1/sessions/{id}` 响应 history 元素
**包路径**：`io.oryxos.web.dto.MessageDto`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `role` | String | ✅ | 枚举 `"user"` / `"assistant"` / `"tool"`；非法值 → 400 invalid_request |
| `content` | String | ✅ | 消息正文；`role=tool` 时为 Tool 执行结果 JSON 字符串 |
| `toolName` | String | ❌（仅 tool） | `role=tool` 时必填；其他 role → null |
| `timestamp` | String (ISO-8601 UTC) | ❌（仅响应） | 写入时间；请求中可省 → 由 `MessageRecorder` 自动写 |

**为什么 role 三选一**：
- 与 `sessions.history[]` 既有 JSON schema 对齐（006-memory-layer data-model.md）
- 与 Spring AI `MessageType` 枚举对齐（USER / ASSISTANT / TOOL）
- 业务方客户端可以用 `role` 做简单的消息分类渲染

---

## 实体 5：`ProfileDto`（REST Profile 查询响应 DTO）

**来源**：`GET /api/v1/profiles` 响应体 list 元素
**包路径**：`io.oryxos.web.dto.ProfileDto`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | String | ✅ | Profile 名（与 URL path 一致） |
| `description` | String | ❌（profile YAML 可省） | `Profile.description` |
| `agentName` | String | ✅ | `Profile.identity.agent_name` |
| `providerName` | String | ✅ | `Profile.provider.name` |
| `model` | String | ✅ | `Profile.provider.model` |
| `toolCount` | Integer | ✅ | `Profile.tools[]` 长度 |
| `scheduleCount` | Integer | ✅ | `Profile.schedules[]` 长度 |
| `notifyChannelCount` | Integer | ✅ | `Profile.notify_channels[]` 长度 |
| `bootstrapFiles` | List<String> | ✅ | `Profile.bootstrap[]` 文件名列表 |

**为什么不暴露完整 Profile YAML**：
- 安全：Profile YAML 含 `system prompt`（可能含敏感 IP）+ 工具调用模式（可推断业务流程）
- 体积：完整 YAML 可能几十 KB；列表场景下 N 条 profile 会爆带宽
- 解耦：业务方需要时走 `GET /api/v1/profiles/{name}` 单条详情接口（**核心阶段不做**，[CLAUDE.md §15](../../CLAUDE.md) "核心阶段不做 Profile 的 create/update"）

---

## 实体 6：`ToolDto`（REST Tool 查询响应 DTO）

**来源**：`GET /api/v1/tools` 响应体 list 元素
**包路径**：`io.oryxos.web.dto.ToolDto`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | String | ✅ | Tool 名（如 `file_read` / `shell` / `notify` / `integration__echo` MCP） |
| `description` | String | ✅ | Tool 描述（一行说明） |
| `source` | String | ✅ | 枚举 `"builtin"` / `"mcp"` / `"java_bean"`（与 `tool_invocations.source` 字段枚举对齐，per 005-tool-system 契约） |
| `inputSchema` | Object | ❌（按需） | Tool 参数 JSON Schema（OpenAPI 3.1 兼容）；由 `ToolSchemaProvider` 反射生成 |

**与 `tool_invocations` 字段对齐**：
- `source` 枚举完全一致：`"builtin"` / `"mcp"` / `"java_bean"`（byte-level 一致，per [005-tool-system/contracts/tool-executor.md](../005-tool-system/contracts/tool-executor.md) §Tool 来源审计）
- 业务方可按 source 做 client-side 过滤（如只显示 builtin tool）

---

## 实体 7：`MemoryDto`（REST Memory 查询响应 DTO）

**来源**：`GET /api/v1/memory` 响应体
**包路径**：`io.oryxos.web.dto.MemoryDto`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `backend` | String | ✅ | 枚举 `"markdown"` / `"sqlite"` / `"mem0"`；当前 LongTermMemoryStore 实现（per 006 memory-layer 契约） |
| `coreEntries` | Integer | ✅ | 核心区分区大小 |
| `archiveEntries` | Integer | ✅ | 归档区分区大小 |
| `filePath` | String | ❌（markdown 后端） | MEMORY.md 文件路径；其他后端 → null |

**为什么只暴露元数据不暴露内容**：
- 内容敏感：长期记忆可能含用户偏好 / 业务信息（006 memory-layer §9.6 列举）
- 体积：完整 memory dump 可能 MB 级
- 安全：业务方客户端读取需要审计（spec §不在范围内 "Memory REST 详情"）

---

## 实体 8：`HealthDto`（REST 系统健康响应 DTO）

**来源**：`GET /api/v1/health` 响应体
**包路径**：`io.oryxos.web.dto.HealthDto`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `status` | String | ✅ | 枚举 `"UP"` / `"DOWN"` |
| `uptimeMs` | Long | ✅ | 从 Spring Boot 启动到现在的毫秒数 |
| `version` | String | ✅ | `oryxos.version`（来自 pom.xml / MANIFEST） |
| `components` | Map<String, Object> | ❌ | Spring Boot Actuator 默认 health indicators（db / diskSpace / ping 等） |

**为什么用 Spring Boot Actuator 而不是自定义 health**：
- `spring-boot-starter-actuator` 已包含 005/006/008 测试依赖（per pom.xml 验证）
- `HealthEndpoint` 自动包含 `db` / `diskSpace` / `ping` 等标准 indicators
- 可扩展：扩展阶段加自定义 `LlmHealthIndicator` / `SchedulerHealthIndicator` 走 `@Component` 自动注册
- 性能：actuator 默认走内存缓存，P95 < 50ms 可达（per SC-006）

**实际端点设计**：
- `GET /api/v1/health` → `HealthDto` 简化版（仅 status / uptime / version）+ 自定义 components
- `GET /actuator/health` → Spring Boot Actuator 默认（detail=when_authorized 默认隐藏）—— 两个端点并存，REST 走简化版，运维走 Actuator

---

## 实体 9：`InfoDto`（REST 系统信息响应 DTO）

**来源**：`GET /api/v1/info` 响应体
**包路径**：`io.oryxos.web.dto.InfoDto`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | String | ✅ | OryxOS 应用名（`spring.application.name`） |
| `version` | String | ✅ | pom.xml `<version>` |
| `javaVersion` | String | ✅ | `System.getProperty("java.version")` |
| `osName` | String | ✅ | `System.getProperty("os.name")` |
| `agents` | Integer | ✅ | 已加载 Profile 数量 |
| `tools` | Integer | ✅ | 已注册 Tool 数量 |
| `uptimeMs` | Long | ✅ | 启动到现在的毫秒数 |

**为什么同时存在 health 与 info**：
- `health`：运维监控用，频率高（每 10s）；仅 status / uptime
- `info`：诊断 / 上线检查用，频率低（启动后查一次）；详细元数据
- 二者不重叠：health 强调"是否能用"，info 强调"是什么版本 + 配置如何"

---

## 实体 10：`ErrorResponse`（REST 错误响应 DTO）

**来源**：`GlobalExceptionHandler` 统一返回
**包路径**：`io.oryxos.web.dto.ErrorResponse`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `error` | String | ✅ | 程序化错误码（snake_case）；与 HTTP status code 解耦 |
| `detail` | String | ✅ | 人类可读错误描述；不含 stack trace（per 007-sandbox-whitelist 契约） |
| `field` | String | ❌（仅表单校验失败） | 失败字段名（如 `message` / `sessionId`）；其他错误 → null |

**HTTP status → error code 映射表**（per research.md R-007）：

| HTTP | error code | 触发异常 | 业务语义 |
|------|-----------|---------|---------|
| 400 | `invalid_request` | `MethodArgumentNotValidException` | Bean Validation 失败（如 `@NotBlank`） |
| 400 | `invalid_json` | `HttpMessageNotReadableException` | JSON 反序列化失败 |
| 400 | `invalid_path_param` | `MethodArgumentTypeMismatchException` | 路径参数类型错（如 UUID 格式） |
| 404 | `agent_not_found` | `AgentNotFoundException` | URL `{name}` 不存在 |
| 404 | `session_not_found` | `SessionNotFoundException` | URL `{id}` 不存在 |
| 500 | `internal_error` | 兜底 `Exception` | 未捕获异常 |
| 503 | `service_unavailable` | Spring 启动失败 | Bean wiring 失败 |
| 504 | `agent_timeout` | `AgentTimeoutException` | ReAct 循环超时（> 30s 默认阈值） |

**为什么不暴露 stack trace**：
- 安全：stack trace 暴露包路径 + 类名 + 行号 → 攻击面扩大
- 与 007-sandbox-whitelist 契约对齐：`tool_invocations.error_message` 不含 stack trace（per [007-sandbox-whitelist/contracts/sandbox.md](../007-sandbox-whitelist/contracts/sandbox.md) §Sanitize）
- 业务方客户端不需要看 stack trace（运维查日志走 Actuator + Logback）

---

## 实体关系图（DTO ↔ 持久化）

```text
┌────────────────────────────────────┐
│  REST Layer DTOs (oryxos-web)      │
│  InvokeRequest / InvokeResponse    │
│  SessionDto / MessageDto           │
│  ProfileDto / ToolDto / MemoryDto  │
│  HealthDto / InfoDto / ErrorResponse │
└─────────────┬──────────────────────┘
              │ Spring MVC + Jackson 映射
              ▼
┌────────────────────────────────────┐
│  AgentService.process()            │
│  SessionFactory / MessageRecorder  │
└─────────────┬──────────────────────┘
              │ 既有 5 表写入入口
              ▼
┌─────────────────────────────────────────────────────────┐
│  SQLite 5 表（CLAUDE.md §13）                            │
│  sessions         ← metadata.source="web" 新增          │
│  tool_invocations ← REST 触发的 Tool 调用                │
│  llm_calls        ← REST 触发的 LLM 调用                 │
│  scheduled_tasks  ← 不变（scheduler 触发专属）           │
│  task_executions  ← REST 不写（仅 scheduler 触发写）    │
└─────────────────────────────────────────────────────────┘
```

**关键约束**：REST 触发**不**写 `task_executions` 表。原因：
- `task_executions.trigger_source` 枚举 `"cli"` / `"web"` / `"scheduler"` 三选一（per 008 data-model.md §实体 3）
- 但 `task_executions` 语义是"定时任务执行历史"，手动补跑（CLI / Web）**应该**写但 008 spec 没要求
- 本 spec 决议：**手动补跑**也写 `task_executions`，`trigger_source="web"` / `"cli"`—— 与 008 契约一致；不新增 schema 仅复用

**修订后的写入契约**（web-service 触发）：

| REST 端点 | 触发表 | trigger_source | 备注 |
|----------|--------|----------------|------|
| `POST /api/v1/agents/{name}/invoke` | `sessions` | `metadata.source="web"` | AgentService 既有入口 |
| 同上 | `tool_invocations` | N/A | DefaultToolExecutor 既有入口 |
| 同上 | `llm_calls` | N/A | Spring AI interceptor 既有入口 |
| 同上 | `task_executions` | `trigger_source="web"` | **本 spec 新增**：手动补跑也走 TaskExecutionRecorder.record(session, "web") |
| `POST /api/v1/sessions/{id}/messages` | `sessions` | `metadata.source` 不变 | 仅追加 history，不新建 Session |
| `GET /api/v1/sessions/{id}` | 无（仅读） | - | 直接 SQL 查询 |
| `DELETE /api/v1/sessions/{id}` | `sessions` | - | 软删除（与 006 删除契约对齐） |
| 其它查询类端点 | 无 | - | 仅读 |

---

## 字段命名规范（REST DTO）

**统一规则**（per Spring Boot 3.x Jackson 默认 + OryxOS 既有约定）：

| 类型 | 规则 | 例子 |
|------|------|------|
| 字段名 | 驼峰（camelCase） | `sessionId` / `profileName` / `notifyChannelCount` |
| 枚举值 | snake_case（小写 + 下划线） | `"agent_not_found"` / `"markdown"` / `"web"` |
| 时间 | ISO-8601 UTC 字符串 | `2026-07-28T00:00:00Z` |
| 大小写敏感 | 是（JSON key 严格匹配） | `MessageId` ≠ `messageId` |

**与既有契约一致性**：
- `tool_invocations.source` / `metadata.source` / `task_executions.trigger_source` 三个字段
  枚举值三选一完全一致：`"cli"` / `"web"` / `"scheduler"`——byte-level 锁定
- 与 008 data-model.md §实体 3 `task_executions.trigger_source` 字段定义对齐

---

## 不在本 spec 范围

- **不新增 SQLite 表**：5 表已由 006/008 阶段锁定
- **不修改 DDL**：与 008 spec "不在范围内"一致
- **不暴露完整 Profile YAML**：[CLAUDE.md §15](../../CLAUDE.md) "核心阶段不做 Profile 的 create/update" → 详情查询也排除
- **不暴露 Memory 内容**：`GET /api/v1/memory` 只返回元数据（backend / 大小）；内容读取走 core/agent 自行 invoke
- **不写 AgentService.process() 之外的持久化逻辑**：所有 DTO → 实体映射由 Spring MVC + Jackson 自动处理

---

## 引用

- [spec.md](spec.md) §关键实体 — 10 个 DTO 实体的业务定义
- [research.md](research.md) — R-007 错误响应契约 + R-008 session 来源标记
- [CLAUDE.md §13](../../CLAUDE.md) — SQLite 5 张表 day-one DDL
- [CLAUDE.md §15](../../CLAUDE.md) — REST 10 端点清单
- [008-agent-scheduler/data-model.md](../008-agent-scheduler/data-model.md) — `sessions.metadata` 扩展契约
- [006-memory-layer/data-model.md](../006-memory-layer/data-model.md) — `sessions` 表 schema
- [005-tool-system/contracts/tool-executor.md](../005-tool-system/contracts/tool-executor.md) — `tool_invocations.source` 枚举契约
- [007-sandbox-whitelist/contracts/sandbox.md](../007-sandbox-whitelist/contracts/sandbox.md) — error_message 不含 stack trace 契约