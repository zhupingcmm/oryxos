# 契约：Webhook HTTP 出站格式

**目的**：定义 `WebhookNotifyAdapter` 实际发出的 HTTP 请求形态（method / headers / body），覆盖核心阶段唯一支持的 `webhook` 类型通道。
**创建日期**：2026-07-25
**特性**：[spec.md §FR-002](../spec.md) | [research.md R-01](./research.md)

---

## 1. HTTP 请求结构

```http
POST <NotifyChannelConfig.url> HTTP/1.1
Host: <url host>
Content-Type: application/json; charset=UTF-8
User-Agent: OryxOS/<version>
Content-Length: <body 字节数>
Connection: close

{"content": "<原始 UTF-8 文本>"}
```

### 1.1 Method

**`POST`**——所有出站通知均为 POST；其他 method 视为配置错误（FR-002）。

### 1.2 Headers

| Header | 值 | 说明 |
|--------|----|------|
| `Content-Type` | `application/json; charset=UTF-8` | 固定 |
| `User-Agent` | `OryxOS/<version>`（如 `OryxOS/1.0.0`） | 便于 webhook 平台识别来源 |
| `Content-Length` | 自动 | JDK HttpClient 自动设置 |
| `Connection` | `close` | 短连接，避免 webhook 平台 keep-alive 兼容问题 |
| 其他自定义 header | **不发送** | 核心阶段 Notify 不带签名 header（HMAC 等放扩展阶段） |

### 1.3 Body

```json
{
  "content": "<NotifyTool 收到的 content 参数原值>"
}
```

- **JSON 序列化**：JDK 内置 Jackson（Spring Boot starter 默认），UTF-8，无 BOM。
- **`content` 字段类型**：String。
- **不允许额外字段**：核心阶段 Notify 不发 `msgtype` / `type` / `markdown` 等扩展字段；三家平台差异适配放扩展阶段。

### 1.4 URL 形态

- Profile 配置的 url 完整原样使用（含 query 参数）。
- 不会改写 url 的 host、path、query。
- Sandbox 校验只读 host 部分。

---

## 2. URL 形态覆盖（核心阶段 3 家 webhook）

| 平台 | URL 形态示例 |
|------|------------|
| 企业微信群机器人 | `https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=<token>` |
| 飞书自定义机器人 | `https://open.feishu.cn/open-apis/bot/v2/hook/<token>` |
| 钉钉群机器人 | `https://oapi.dingtalk.com/robot/send?access_token=<token>` |

**核心阶段适配说明**：上述三家 webhook 的标准消息体格式略有差异（企业微信用 `text.content`、飞书 v2 hook 用 `msg_type=text, content.text`、钉钉用 `text.content`）。核心阶段 Notify **不**做这些差异适配——只发通用 `{"content": "..."}` payload。

**含义**：

- 企业微信 / 钉钉的通用 webhook 实际**接受** `{"content": "..."}` 形态（兼容模式）；通知能送达。
- 飞书的 v2 hook **要求** `msg_type` 字段；通用 `{"content": "..."}` 会被飞书拒掉（400）。
- 这是核心阶段**显式取舍**：扩展阶段（plan / tasks）增加 `type` 字段后，飞书 webhook 才能跑通。

**当前核心阶段 MVP 可演示目标**：企业微信群机器人 / 钉钉群机器人（通用 payload 兼容）。飞书在扩展阶段或加 platform-specific 适配后支持。

---

## 3. Sandbox 校验

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:
          - qyapi.weixin.qq.com    # 企业微信
          - oapi.dingtalk.com      # 钉钉
          # - open.feishu.cn       # 飞书（核心阶段如启用需先解决 payload 兼容）
```

`WhitelistSandbox.enforce(SandboxAction(HTTP_REQUEST, url))` 校验流程：

```
1. 解析 url → 拿 host 部分（小写，去端口号）
2. 对 allowed-domains 列表逐项匹配：
   - 配置项 = host → 精确匹配
   - 配置项 = 子域 → 后缀匹配（host.endsWith("." + entry)）
3. 任意一项匹配 → 通过
4. 全部不匹配 → 抛 SandboxViolationException
```

**异常传递路径**：

```
WebhookNotifyAdapter.send(channel)
  → sandbox.enforce(SandboxAction(HTTP_REQUEST, url))
       ↑ throws SandboxViolationException
  → catch → 返回 NotifyResult(success=false, errorClass="sandbox_violation")
```

**关键不变量**：Sandbox 校验在 `HttpClient.send()` **之前**发生；被拦截时 WireMock 计数 = 0（spec SC-003 验证方式）。

---

## 4. 超时

- **默认值**：5 秒（[research.md R-08](./research.md) / spec FR-012）
- **可配置**：未来扩展；核心阶段硬编码 5 秒
- **JDK 设置**：`HttpRequest.newBuilder().timeout(Duration.ofSeconds(5))`
- **超时行为**：`HttpClient.send()` 抛 `HttpTimeoutException`；`WebhookNotifyAdapter` catch 后返回 `NotifyResult(success=false, errorClass="timeout")`

---

## 5. 响应处理

### 5.1 状态码判定

| 状态码范围 | 视为 | ToolResult.success |
|-----------|------|-------------------|
| `200..299` | 成功 | `true` |
| `300..399` | 失败（不自动 follow redirect） | `false`（`errorClass=http_error`） |
| `400..599` | 失败 | `false`（`errorClass=http_error`） |
| 网络层失败（DNS / 连接 / TLS） | 失败 | `false`（`errorClass=network_error`） |
| 超时 | 失败 | `false`（`errorClass=timeout`） |

### 5.2 响应体

- **核心阶段不解析**——核心阶段只看状态码。
- 失败时，响应体**前 256 字节**截断写入 `tool_invocations.error_message`（便于排查）；超过部分丢弃。
- 成功时，响应体**不读取**（JDK `BodyHandlers.discarding()`）——避免下载大响应。

---

## 6. 重试

**核心阶段：不重试**（spec FR-011 / research.md R-09）。

- 失败立即返回 `ToolResult.success=false` 给 LLM。
- 让 LLM 决定是否再调一次（在 ReAct 循环的下一轮 iteration）。
- 这与宪法 §VII「跑通优先于完美」一致——避免无限循环 + 雪崩。

**扩展阶段路线**：指数退避 + dead letter queue（放 spec "不在范围内" 节）。

---

## 7. TLS / 证书

- **JDK 默认 truststore**——使用 `$JAVA_HOME/lib/security/cacerts`。
- **不引入自定义证书库**——核心阶段不处理企业内部 CA。
- **HTTPS 失败**→ 视为 `network_error`（`errorClass=network_error`，message 含 `SSLHandshakeException`）。

企业内部 CA 支持放扩展阶段（CA bundle 路径可配置）。

---

## 8. 并发（广播模式）

- **执行器**：`Executors.newVirtualThreadPerTaskExecutor()`（JDK 21，每任务一虚拟线程）
- **触发**：仅在广播模式（`channel=null` 且 N>1）启用
- **同步点**：所有虚拟线程 join 后聚合 `NotifyResult` 列表
- **隔离**：每条通道独立 try/catch；一条失败不影响其他
- **wall-time**：N 条广播 wall-time ≈ 最慢那条（虚拟线程并发执行）

具体见 [research.md R-08](./research.md)。

---

## 9. 测试用 mock 期望

集成测试用 WireMock 验证以下 5 种场景：

| 场景 | WireMock 配置 | 期望 ToolResult |
|------|--------------|----------------|
| 健康 200 | `post(url).willReturn(200)` | `success=true, status_code=200` |
| 4xx | `post(url).willReturn(400, "bad request")` | `success=false, status_code=400, error_class=http_error` |
| 5xx | `post(url).willReturn(500, "internal")` | `success=false, status_code=500, error_class=http_error` |
| 超时 | `post(url).willReturn(ok().withFixedDelay(10000))` | `success=false, error_class=timeout`（5 秒超时先触发） |
| Sandbox 拦截 | WireMock 不应被调用 | `success=false, error_class=sandbox_violation`；WireMock 计数 = 0 |

---

## 10. 不在本契约范围

- ❌ 重试策略（[spec "不在范围内" 节](../spec.md)）
- ❌ HMAC 签名 header（核心阶段不发送）
- ❌ 三家平台专属 payload 适配（飞书 v2 hook 要求 `msg_type`；核心阶段不解决）
- ❌ 自定义 CA 证书（核心阶段用 JDK 默认 truststore）
- ❌ HTTP/2（核心阶段 HTTP/1.1，够用）
- ❌ Proxy（核心阶段直连）