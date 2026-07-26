# 契约：Notify 工具（LLM 视角）

**目的**：定义 LLM 通过 Function Calling 调用 `notify` 工具时的完整契约——参数 schema、返回结构、错误语义。这是 `ToolSchemaProvider` 翻译成 Function Calling JSON 的依据。
**创建日期**：2026-07-25
**特性**：[spec.md §FR-003](../spec.md) | [research.md R-04](./research.md)

---

## 1. 工具元数据

```yaml
name: notify
description: |
  向已配置的群机器人 webhook 推送一条文本消息。
  - 不指定 channel 时：发到名为 "default" 的通道；若 Profile 没配 default 通道则报错。
  - 不指定 channel 且 Profile 配了多条通道（N>1）：广播到所有通道。
  - 指定 channel="<name>" 时：仅发到名为 <name> 的通道；<name> 不存在则报错。
  发送失败（HTTP 非 2xx / 超时 / Sandbox 拦截）会作为 tool 错误返回给你（success=false），
  不会中断 ReAct 循环；你可以决定重试或放弃。
origin: builtin
```

**origin 字段**：固定 `"builtin"`（核心阶段唯一实现就是内置 `WebhookNotifyAdapter`）。

---

## 2. 输入参数（JSON Schema）

### 2.1 顶层 schema

```json
{
  "type": "object",
  "properties": {
    "content": {
      "type": "string",
      "minLength": 1,
      "maxLength": 4096,
      "description": "要推送的文本内容。UTF-8。空字符串或超过 4096 字节会被拒绝。"
    },
    "channel": {
      "type": "string",
      "pattern": "^[a-z][a-z0-9-]{0,63}$",
      "description": "目标通道名；可选。缺省时按 Profile 配置的默认通道或广播语义路由。"
    }
  },
  "required": ["content"],
  "additionalProperties": false
}
```

### 2.2 字段说明

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `content` | ✅ | string (1..4096 字节 UTF-8) | 推送文本 |
| `channel` | ❌ | string | 通道名；缺省时按 §3 路由规则 |

### 2.3 `additionalProperties: false`

LLM 不应传额外字段（`type`、`silent`、`at` 等企业微信/飞书扩展字段）。核心阶段 Notify 只接受 `content` + `channel`；扩展字段若需要，可走扩展阶段。

---

## 3. 路由规则

按 [spec.md FR-006 / FR-007](../spec.md) 固化（**MVP 阶段 = US-1 单通道**；广播路径见 §3.1）：

| 场景 | 行为 |
|------|------|
| LLM 不传 `channel`；Profile 有名为 `default` 的通道 | 发到 `default` |
| LLM 不传 `channel`；Profile 无 `default` 但仅 1 条通道 | 发到该唯一通道（不论 name；**MVP 单通道语义**） |
| LLM 不传 `channel`；Profile 无 `default` 且 N>1 条 | `ToolResult.success=false, errorMessage="channel 不能省略: profile 配了 N 条通道（无 default）"`（**MVP 显式降级**，广播留给 US-4） |
| LLM 不传 `channel`；Profile 配了 0 条通道 | `ToolResult.success=false, errorMessage="profile 未配置 notify_channels"` |
| LLM 传 `channel="<name>"`；`<name>` 在 Profile 中 | 仅发到该通道 |
| LLM 传 `channel="<name>"`；`<name>` 不在 Profile 中 | `ToolResult.success=false, errorMessage="未知通道: <name>"`，零 HTTP 请求 |

### 3.1 广播语义（US-4 阶段固化，本 MVP 不实现）

- Profile 层显式声明 `broadcast: true` 时：LLM 不传 `channel` → 系统并行发到全部 N 条通道
- Profile 未声明 `broadcast: true` 时：走 §3 单通道路由表
- 部分失败 / 全失败聚合返回结构（`ToolResult.success` 字段语义）见 [spec.md §4.4-4.6](../spec.md)

---

## 4. 返回结构（ToolResult）

### 4.1 单通道成功

```json
{
  "success": true,
  "payload": {
    "channel": "default",
    "status_code": 200,
    "duration_ms": 234
  },
  "error_message": null
}
```

### 4.2 单通道失败（HTTP 5xx）

```json
{
  "success": false,
  "payload": {
    "channel": "feishu-tech",
    "status_code": 500,
    "duration_ms": 5023,
    "error_class": "http_error"
  },
  "error_message": "HTTP 500: Internal Server Error"
}
```

### 4.3 单通道失败（Sandbox 拦截）

```json
{
  "success": false,
  "payload": {
    "channel": "evil-channel",
    "error_class": "sandbox_violation"
  },
  "error_message": "sandbox violation: host 'evil.example.com' not in allowed-domains"
}
```

### 4.4 广播全部成功

```json
{
  "success": true,
  "payload": {
    "broadcast": true,
    "results": [
      { "channel": "default",          "status_code": 200, "duration_ms": 234, "success": true },
      { "channel": "feishu-tech",      "status_code": 200, "duration_ms": 312, "success": true }
    ]
  },
  "error_message": null
}
```

### 4.5 广播部分失败

```json
{
  "success": true,
  "payload": {
    "broadcast": true,
    "results": [
      { "channel": "default",     "status_code": 200, "duration_ms": 234, "success": true },
      { "channel": "feishu-tech", "status_code": 500, "duration_ms": 5023, "success": false, "error": "HTTP 500" }
    ]
  },
  "error_message": "partial: feishu-tech=500"
}
```

> **设计取舍说明**：广播部分失败时 `success=true`（聚合语义），但 `error_message` 字段携带失败明细供 LLM / 审计员读取。LLM 可据此决定是否再调一次或向用户报告。

### 4.6 广播全部失败

```json
{
  "success": false,
  "payload": {
    "broadcast": true,
    "results": [
      { "channel": "default",     "success": false, "error_class": "timeout" },
      { "channel": "feishu-tech", "success": false, "status_code": 500 }
    ]
  },
  "error_message": "all failed: default=timeout; feishu-tech=500"
}
```

---

## 5. 错误分类（`error_class`）

Notify 把失败原因分成 7 类，便于 LLM 与审计员区分：

| `error_class` | 含义 | 触发条件 |
|---------------|------|---------|
| `empty_content` | 空内容 | `content` 为 null / 空字符串 |
| `content_too_long` | 内容超长 | `content` UTF-8 字节数 > 4096 |
| `unknown_channel` | 通道未配置 | LLM 指定了 Profile 里没有的 `channel` |
| `no_channels` | 未配置通道 | Profile 没配 `notify_channels` 且 LLM 没指定 `channel` |
| `sandbox_violation` | 白名单拦截 | URL host 不在 `tool.sandbox.http.allowed-domains` |
| `timeout` | 超时 | HTTP send 超 5 秒未响应 |
| `http_error` | HTTP 失败 | 状态码 < 200 或 >= 300 |
| `network_error` | 网络层失败 | DNS / 连接拒绝 / TLS 握手失败 |

`error_class` 仅在 `success=false` 时有意义；成功时为 null。

---

## 6. LLM 可见的错误文案

LLM 看到的 `error_message` 是**人类可读的中文**：

- `empty_content` → `"content 不能为空"`
- `content_too_long` → `"content 超长 (X bytes, limit=4096)"`
- `unknown_channel` → `"未知通道: <name>"`
- `no_channels` → `"profile 未配置 notify_channels"`
- `sandbox_violation` → `"sandbox violation: host '<host>' not in allowed-domains"`
- `timeout` → `"HTTP request timeout after 5s"`
- `http_error` → `"HTTP <status_code>: <reason_phrase>"`
- `network_error` → `"network error: <异常类型简述>（如 ConnectionRefused / UnknownHost）"`

**语言**：默认中文（与 CLAUDE.md §21 一致）。LLM 可以基于这些错误自主调整响应。

---

## 7. 审计契约（tool_invocations 落库字段）

每次 `notify` 调用必产生一行 `tool_invocations` 审计：

| 字段 | 单通道值 | 广播值 |
|------|---------|--------|
| `tool_name` | `'notify'` | `'notify'` |
| `success` | 该通道 success | N 条全成功=true；至少一条失败=true（聚合） |
| `error_message` | 该通道错误；成功为 null | 全成功=null；部分失败=`partial: ...`；全失败=`all failed: ...` |
| `duration_ms` | 该通道耗时 | 总耗时（最长那条） |
| `arguments` | `{"content": "...", "channel": "..."}` | `{"content": "...", "channel": null}` 或省略 channel |
| `channel` | 通道名 | 多通道用 `;` 分隔（如 `"default;feishu-tech"`） |
| `notify_status_code` | 单条状态码；网络失败 null | 最差那条的状态码；全网络失败 null |
| 其他 | 同既有字段 | 同既有字段 |

**广播模式的 channel 字段**：用 `;` 分隔通道名（避免拆 JSON 列的复杂度）；扩展阶段如需结构化可改 JSON 列。

---

## 8. 不变量（Invariants）

- **I-NT-1**：单次 `notify` 调用产**恰好一行** `tool_invocations` 审计行（广播也只一行）。
- **I-NT-2**：审计行在 ToolResult 返回给 LLM **之前**写入（C-TE-9 一致）。
- **I-NT-3**：审计行的 `error_message` 与 ToolResult 的 `error_message` **内容一致**（成功时均为 null）。
- **I-NT-4**：`tool_invocations.channel` 字段在 `tool_name != 'notify'` 时**必须**为 null；JPA 层不强制（依赖 NotifyTool 显式设），但建议加 DB CHECK 约束（扩展阶段）。

---

## 9. 不在本契约范围（Out of Contract）

- ❌ Notify 工具的 Spring bean 装配路径（在 plan.md / tasks.md 阶段）
- ❌ HTTP 客户端选型（已在 research.md R-01 固化）
- ❌ Audit 落地的具体 JPA 调用（在 tasks.md 阶段）
- ❌ webhook payload 三家平台差异适配（核心阶段用通用 `{"content": "..."}`，扩展阶段再分）
