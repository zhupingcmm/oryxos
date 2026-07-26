# 数据模型：Notify 出站推送（US-4 子能力）

**目的**：定义 Notify spec 涉及的所有持久化实体与运行时数据结构，标注新增字段与 schema 演进风险。
**创建日期**：2026-07-25
**特性**：[spec.md](./spec.md) | [research.md](./research.md)
**前置**：[specs/002-react-loop/data-model.md §3](../../002-react-loop/data-model.md) | [CLAUDE.md §13](../../CLAUDE.md)

---

## 1. 实体总览

| 实体 | 类型 | 生命周期 | 模块归属 |
|------|------|---------|---------|
| `NotifyChannelConfig` | record（不可变） | Profile 加载期 | `oryxos-core` |
| `NotifyResult` | record（不可变） | 单次 HTTP 调用期（不持久化） | `oryxos-tool` |
| `NotifyTool`（class） | OryxTool 实现 | Spring bean | `oryxos-tool` |
| `ToolRegistration` | record（不可变） | Spring bean 注册期 | `oryxos-tool` |
| `SandboxAction` / `ActionType` / `SandboxViolationException` | record / enum / exception | 调用期 | `oryxos-tool` |
| `tool_invocations`（DB 表） | JPA entity（**已有，本 spec 加 2 列**） | 持久化 | `oryxos-storage` |

---

## 2. `NotifyChannelConfig`（Profile 内嵌 record）

**定义位置**：`io.oryxos.core.NotifyChannelConfig`

```java
public record NotifyChannelConfig(
    String name,        // Profile 内唯一；[a-z][a-z0-9-]{0,63}
    String type,        // 核心阶段仅合法值 "webhook"
    String url,         // 必填；HTTP/HTTPS；发送前过 Sandbox
    String secret       // 可空；核心阶段忽略，扩展阶段预留签名
) {
    public NotifyChannelConfig {
        // 校验逻辑见 §2.1
        ...
    }
}
```

### 2.1 字段约束

| 字段 | 必填 | 校验规则 | 校验失败行为 |
|------|------|---------|------------|
| `name` | ✅ | 匹配 `^[a-z][a-z0-9-]{0,63}$`；Profile 内唯一 | `IllegalArgumentException`；Profile 加载失败 |
| `type` | ✅ | 仅 `"webhook"`；其他值抛错 | 同上 |
| `url` | ✅ | 必须是合法 `http://` 或 `https://` URL；host 非空 | 同上 |
| `secret` | ❌ | 核心阶段不校验；可空 | n/a |

### 2.2 与 Profile 的集成

`Profile` record **新增字段**：

```java
public record Profile(
    String name,
    Provider provider,
    List<String> tools,
    List<String> mcpServers,
    List<String> bootstrap,
    List<String> skills,
    Settings settings,
    Map<String, Object> extra,
    List<NotifyChannelConfig> notifyChannels   // ← 新增；默认 List.of()
) { ... }
```

**演进策略**：

- 现有 `Profile` 构造调用方在 IDE 重构时会编译失败，提示新增字段；修复方式为显式传 `List.of()` 或具体值。
- `Profile.Settings` 不动。
- `extra` map 不动；NotifyChannel 不走 extra。

---

## 3. `NotifyResult`（运行时 record，不入库）

**定义位置**：`io.oryxos.tool.notify.NotifyResult`

```java
public record NotifyResult(
    String channelName,       // Profile 内通道名（脱敏后的 url 不在此处）
    boolean success,          // 单条 HTTP 调用结果
    Integer statusCode,       // HTTP 状态码；网络失败时为 null
    String errorMessage,      // 失败原因；成功时为 null
    long durationMs,          // 单条 send 耗时
    String redactedUrl        // 脱敏后的 URL，仅用于审计/日志
) { }
```

**为什么不是 JPA entity**：`NotifyResult` 是工具方法的返回值，**不进** `notify_invocations` 表（[research.md R-02](../../CLAUDE.md)）。它最终通过 `tool_invocations` 表的 `tool_name='notify'` 行体现审计；`status_code` 字段被拆解到 `tool_invocations.error_message`（失败时）或 audit 的新 `notify_status_code` 列。

---

## 4. `ToolRegistration`（扩展 ToolRegistry）

**定义位置**：`io.oryxos.tool.ToolRegistration`

```java
public record ToolRegistration(
    ToolDefinition definition,    // 元数据（给 CLI 的 tool list）
    OryxTool tool,                // 实现（给 DefaultToolExecutor 派发）
    String beanName               // Spring bean 名，便于调试
) { }
```

**对 [ToolRegistry.java](oryxos-tool/src/main/java/io/oryxos/tool/ToolRegistry.java) 的改动**：

```java
// 当前
public static ToolRegistry of(Map<String, ToolDefinition> definitions)

// 改为
public static ToolRegistry of(Map<String, ToolRegistration> registrations)
public Optional<OryxTool> find(String name)    // 新增
public Collection<ToolDefinition> all()          // 已有；返回 definition 列表
```

`OryxTool.description()` 默认方法（[research.md R-05](./research.md)）：

```java
public interface OryxTool {
    String name();
    default String description() { return ""; }   // 新增
    ToolResult execute(Map<String, Object> arguments);
}
```

---

## 5. Sandbox 子系统

**包路径**：`io.oryxos.tool.sandbox`

| 类型 | 名称 | 说明 |
|------|------|------|
| `enum` | `ActionType` | `FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST` |
| `record` | `SandboxAction(ActionType type, String target)` | `target` 是 URL（HTTP_REQUEST）/ 路径（FILE_*） / 命令（SHELL_*） |
| `interface` | `Sandbox` | `void enforce(SandboxAction action) throws SandboxViolationException` |
| `class` | `SandboxViolationException` | `extends RuntimeException`；携带 `SandboxAction` |
| `class` | `WhitelistSandbox` | 本 spec 的唯一实现；从配置读 allowed-domains；HTTP_REQUEST 仅校验 host 后缀匹配 |

**WhitelistSandbox 的 host 匹配规则**：

- 配置 `api.weixin.qq.com` → 匹配 `api.weixin.qq.com` 与其所有子域（`a.api.weixin.qq.com`）
- 配置 `*.example.com` → 不支持（仅后缀匹配，简化实现）
- IP 地址（v4 / v6）默认不通过；除非未来扩展加入 IP 白名单配置项（本 spec 不覆盖）

**配置**：

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:
          - api.weixin.qq.com
          - open.feishu.cn
          - oapi.dingtalk.com
```

→ 绑定到 `oryxos-tool` 模块的 `SandboxProperties`（`@ConfigurationProperties`）。

---

## 6. `NotifyTool`（OryxTool 实现）

**包路径**：`io.oryxos.tool.notify.NotifyTool`

```java
@Component
public class NotifyTool implements OryxTool {
    private final WebhookNotifyAdapter adapter;
    private final ProfileContext context;       // 间接获取 Profile

    public NotifyTool(WebhookNotifyAdapter adapter) { ... }

    @Override public String name() { return "notify"; }

    @Override public String description() {
        return "向已配置的群机器人 webhook 推送一条文本消息。" +
               "channel 缺省时发到 default 通道；不指定 channel 且配置了多条时广播。";
    }

    @Override public ToolResult execute(Map<String, Object> arguments) { ... }
}
```

**execute 流程**：

```
1. 从 arguments 拿 content / channel
2. 校验：content 非空；content 长度 ≤ 4096（spec FR 未限制但 webhook 平台限制）
3. 从 ProfileContext.current() 拿到 Profile
4. 拿 Profile.notifyChannels()
5. 路由：
   - channel=null && N>1 → 广播（[research.md R-08](./research.md)）
   - channel=null && N==1 → 等同于指定该通道
   - channel=null && N==0 → ToolResult.error("profile 未配置 notify_channels")
   - channel="<name>" → 查通道；找不到 → error("未知通道: <name>")
6. 对每条通道：调 WebhookNotifyAdapter.send(channel)
7. 聚合 NotifyResult → ToolResult
```

---

## 7. `WebhookNotifyAdapter`

**包路径**：`io.oryxos.tool.notify.WebhookNotifyAdapter`

```java
@Component
public class WebhookNotifyAdapter {
    private final HttpClient httpClient;             // JDK java.net.http.HttpClient
    private final ExecutorService virtualExecutor;   // newVirtualThreadPerTaskExecutor
    private final Duration timeout;                  // 默认 5 秒
    private final UrlRedactor urlRedactor;           // 脱敏

    public NotifyResult send(NotifyChannelConfig channel, String content) { ... }
}
```

**发送细节**：

- HTTP method：`POST`
- Headers：`Content-Type: application/json; charset=UTF-8`
- Body：`{"content": "<text>"}`（通用形态，覆盖企业微信 / 飞书 / 钉钉基本 webhook）
- TLS：使用 JDK 默认 truststore（不引入自定义证书库）

---

## 8. `tool_invocations` 表 schema 演进

**当前 schema**（[ToolInvocationRecord.java](oryxos-storage/src/main/java/io/oryxos/storage/entity/ToolInvocationRecord.java)）：

```sql
CREATE TABLE tool_invocations (
  id                TEXT    PRIMARY KEY,
  session_id        TEXT,
  profile_name      TEXT    NOT NULL,
  tool_name         TEXT    NOT NULL,
  arguments         TEXT,             -- JSON
  success           INTEGER NOT NULL,  -- 0/1
  error_message     TEXT,
  duration_ms       INTEGER NOT NULL,
  started_at        TEXT    NOT NULL,  -- ISO-8601
  session_iteration INTEGER NOT NULL
);
```

**本 spec 新增 2 列**：

```sql
ALTER TABLE tool_invocations ADD COLUMN channel            TEXT;     -- 新增；仅 notify 工具非空
ALTER TABLE tool_invocations ADD COLUMN notify_status_code INTEGER;  -- 新增；仅 notify 工具非空；网络失败时 NULL
```

**风险与缓解**：[CLAUDE.md §13](../../CLAUDE.md) 明确 SQLite `ALTER TABLE` 能力有限，`hibernate.ddl-auto=update` 对新增列支持不可靠。

**缓解方案**：

1. **手动写 DDL 脚本** `oryxos-storage/src/main/resources/db/migration/V2__add_notify_columns.sql`（纯 SQL 形式，约束 NOT NULL 的列加默认值）。
2. **JPA entity 同步更新** `ToolInvocationRecord.java`：新增 `channel`（`@Column(name = "channel") String channel`）+ `notify_status_code`（`@Column(name = "notify_status_code") Integer notifyStatusCode`）。
3. **`hibernate.ddl-auto=update` 仅作为开发环境的 fallback**；生产路径必须显式执行 DDL。
4. **回滚方案**：保留 DDL 脚本的 DOWN 版本；测试用临时 SQLite 文件验证。

**新列语义**：

- `channel TEXT`：仅 `tool_name='notify'` 行非空；存 `NotifyChannelConfig.name`（如 `"default"` / `"feishu-tech"`）。**不存** webhook URL（避免 token 落库）。
- `notify_status_code INTEGER`：仅 `tool_name='notify'` 行非空；HTTP 状态码（如 `200` / `500`）；网络层失败（DNS / 连接超时 / TLS 握手）时为 `null`。

**索引建议**：暂不新增索引；`tool_name` 已有联合索引 `idx_tool_ts (tool_name, started_at)`，按 `tool_name='notify'` 过滤已有索引支持。如未来 Notify 调用量剧增，再考虑 `idx_notify_status (notify_status_code, started_at)`。

---

## 9. 实体关系图（简化）

```text
Profile (1) ──< notifyChannels (0..*) >── NotifyChannelConfig
                                              │
                                              │ (name 引用)
                                              ▼
tool_invocations 行 ─── channel 列 (TEXT, 可空)
                  └── notify_status_code 列 (INTEGER, 可空)

NotifyTool.execute(args) ──> WebhookNotifyAdapter.send(channel)
                            ├── Sandbox.enforce(HTTP_REQUEST, url)
                            └── HttpClient.send(...)  ──> NotifyResult
                                                          │
                                                          ▼
                                            DefaultToolExecutor 写 tool_invocations 行
                                            (channel + notify_status_code)
```

---

## 10. 兼容性总结

| 现有实体 | 影响 | 处理 |
|---------|------|------|
| `Profile` record | 新增 `notifyChannels` 字段 | 编译期提示；调用方补 `List.of()` 或具体值 |
| `Profile.Settings` | 无 | — |
| `ToolInvocationRecord` | 新增 2 列 | entity 加 2 个 `@Column`；DDL 脚本手动执行 |
| `ToolRegistry` | 改 `of()` 签名 | 调用方改传 `Map<String, ToolRegistration>`；`all()` 不变 |
| `OryxTool` | 新增默认方法 `description()` | 默认实现返回 `""`；现有 fake 不动 |
| `DefaultToolExecutor` | UOE 替换为 registry 派发 | 测试 fixture 注入 mock ToolRegistry |

---

## 11. 待 tasks.md 阶段落地的具体 DDL

```sql
-- V2__add_notify_columns.sql
ALTER TABLE tool_invocations ADD COLUMN channel TEXT;
ALTER TABLE tool_invocations ADD COLUMN notify_status_code INTEGER;

-- 回滚
-- ALTER TABLE tool_invocations DROP COLUMN notify_status_code;
-- ALTER TABLE tool_invocations DROP COLUMN channel;
```

不引入 NOT NULL 约束以保持历史行 NULL 合法。