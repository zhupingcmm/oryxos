# 契约：Notify 通道 Profile 配置

**目的**：定义 Profile YAML 中 `notify_channels` 字段的完整 schema、校验规则、加载路径。这是 `oryxos-cli` 的 `ConfigLoader` 解析 Notify 配置的依据。
**创建日期**：2026-07-25
**特性**：[spec.md §FR-005](../spec.md) | [data-model.md §2](./data-model.md)

---

## 1. YAML 形态

### 1.1 顶层位置

`notify_channels` 是 Profile YAML 的**顶级字段**，与 `provider` / `tools` / `settings` 同级。

```yaml
# .oryxos/agents/<name>/AGENT.md frontmatter（YAML 块）

name: weather-bot
description: 每日天气推送

provider:
  name: deepseek
  model: deepseek-chat

tools:
  - notify

# ↓↓↓ 新增字段 ↓↓↓
notify_channels:
  - name: default
    type: webhook
    url: ${WEATHER_WEBHOOK_URL}

settings:
  max_iterations: 10
  max_history_turns: 20
```

### 1.2 字段约束

| 字段 | 必填 | 类型 | 校验规则 | 错误 |
|------|------|------|---------|------|
| `name` | ✅ | string | 匹配 `^[a-z][a-z0-9-]{0,63}$`；Profile 内唯一 | 加载失败：`Profile 'X' has duplicate notify_channels name: Y` |
| `type` | ✅ | string | 仅 `"webhook"` | 加载失败：`unsupported notify_channel type: <value>` |
| `url` | ✅ | string | 合法 `http://` 或 `https://` URL；host 非空 | 加载失败：`invalid notify_channel url: <value>` |
| `secret` | ❌ | string | 核心阶段忽略 | n/a |

### 1.3 多通道示例

```yaml
notify_channels:
  - name: default
    type: webhook
    url: ${DEFAULT_WEBHOOK_URL}

  - name: feishu-tech
    type: webhook
    url: ${FEISHU_WEBHOOK_URL}

  - name: dingtalk-security
    type: webhook
    url: ${DINGTALK_WEBHOOK_URL}
```

---

## 2. Profile 内唯一性

### 2.1 重复 name 检测

Profile 加载时（`ConfigLoader.loadProfile()`）必须检查 `notify_channels[*].name` 互不重复：

```java
Set<String> seen = new HashSet<>();
for (NotifyChannelConfig ch : notifyChannels) {
    if (!seen.add(ch.name())) {
        throw new IllegalArgumentException(
            "duplicate notify_channels name: " + ch.name());
    }
}
```

### 2.2 与 `default` 的关系

`name: "default"` **不**是保留字——任何通道都可以叫 `default`，只是约定俗成。如果 Profile 配了 `name=foo` 而不配 `default`，LLM 不传 `channel` 时：

- 通道数 == 1 → 落到该唯一通道
- 通道数 > 1 → 广播到全部（[notify-tool.md §3](./notify-tool.md)）

---

## 3. `${ENV_VAR}` 解析

### 3.1 与 US-1 / 003-cli-commands 的对齐

`ConfigLoader` 在 US-1 已支持 `${ENV_VAR}` 占位符解析（[specs/003-cli-commands/contracts/](../../003-cli-commands/contracts/)）；`notify_channels[*].url` 复用同一机制。

### 3.2 缺失环境变量

若 `${WEATHER_WEBHOOK_URL}` 在加载 Profile 时未设置：

- **`${VAR}` 占位符未解析**：抛 `MissingEnvVarException("WEATHER_WEBHOOK_URL")`；Profile 加载失败
- **可空字段**：`secret` 可缺失；但 `url` **不允许**缺失占位符

### 3.3 默认值（核心阶段不推荐）

YAML 层面允许写：

```yaml
url: ${WEBHOOK_URL:-https://default.example.com/hook}
```

但**核心阶段不强制实现**该语法；推荐所有 `url` 都是必填、必须显式给环境变量。

---

## 4. 与 Sandbox 配置的关系

### 4.1 必须协同配置

`notify_channels[*].url` 的 host **必须**出现在 `tool.sandbox.http.allowed-domains` 中，否则所有 `notify` 调用都会 sandbox 拦截。

**这是设计上的强约束**——核心阶段不绕过。

### 4.2 配置示例（完整）

```yaml
# .oryxos/application.yaml（工作区级）
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:
          - qyapi.weixin.qq.com       # 企业微信
          - oapi.dingtalk.com         # 钉钉
          - open.feishu.cn            # 飞书（如启用）
          - localhost                 # 本地测试（WireMock 等）
```

```yaml
# .oryxos/agents/<name>/AGENT.md frontmatter（Agent 级）
notify_channels:
  - name: default
    type: webhook
    url: ${WEATHER_WEBHOOK_URL}      # e.g. https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxxx
```

---

## 5. 默认 channel 的语义

### 5.1 `name: "default"` 与路由规则

LLM 不指定 `channel` 时：

1. Profile 含 name=`default` 的通道 → 发到该通道
2. Profile 不含 name=`default`，但通道数 == 1 → 发到该唯一通道
3. Profile 不含 name=`default`，通道数 == 0 → error("profile 未配置 notify_channels")
4. Profile 不含 name=`default`，通道数 > 1 → 广播到全部（spec user story 4 P3 行为）

详见 [notify-tool.md §3](./notify-tool.md) 路由规则表。

### 5.2 命名建议（不是强制）

- 单通道 Profile → 推荐 `name: default`
- 多通道 Profile → 推荐至少有一条 `name: default`，作为 LLM "不指定 channel" 的回落

---

## 6. 缺失 `notify_channels` 时的行为

Profile YAML 完全不写 `notify_channels` 字段：

- 解析为 `List<NotifyChannelConfig>.of()`（空列表）
- `Profile.tools: [notify]` 仍合法；Tool 注册表里 `notify` 仍存在
- 但 LLM 调 `notify()` 时返回 `ToolResult.success=false, errorMessage="profile 未配置 notify_channels"`

这是 spec FR-006 的核心行为；让错误尽早暴露给 LLM。

**进阶（spec FR-006 优化）**：若 Profile.tools 不含 `"notify"`，NotifyTool 完全不在 Function Calling schema 里出现——LLM **看不到**这个工具，更友好。详见 [notify-tool.md §1](./notify-tool.md)。

---

## 7. 加载路径（实现契约）

```
ConfigLoader.loadProfile(path)
  ├── SnakeYAML 解析 frontmatter → Map<String, Object>
  ├── ${ENV_VAR} 替换
  ├── 校验 name/provider/tools/settings 字段（已有）
  ├── 新增：解析 notify_channels
  │     ├── 遍历列表 → 每条建 NotifyChannelConfig record
  │     ├── 校验 name/type/url
  │     ├── 校验 Profile 内 name 唯一性
  │     └── 失败 → 抛 IllegalArgumentException / MissingEnvVarException
  └── 返回 Profile(含 notifyChannels)
```

**新增 Java 文件**（tasks.md 阶段落实）：

- `io.oryxos.core.NotifyChannelConfig`（record）
- `io.oryxos.core.Profile.notifyChannels` 字段（[data-model.md §2](./data-model.md)）

---

## 8. 测试用 Profile 模板

### 8.1 `minimal` 模板

[oryxos-cli](oryxos-cli/) 已提供 `profile create --template minimal`；本 spec 不新增模板；现有 minimal 不带 notify_channels。

### 8.2 `notify-demo` 模板（本 spec 不实现，仅示例）

```yaml
name: notify-demo
description: Notify 模块演示

provider:
  name: deepseek
  model: deepseek-chat

tools:
  - notify

notify_channels:
  - name: default
    type: webhook
    url: ${NOTIFY_DEMO_WEBHOOK_URL}      # WireMock URL in test

settings:
  max_iterations: 10
  max_history_turns: 20
```

> **核心阶段策略**：不新增 CLI 模板；演示通过 `quickstart.md` 中的手动 YAML 复制完成。扩展阶段如果 Notify 成为标配，可加 `--template notify`。

---

## 9. 兼容性与迁移

### 9.1 向后兼容

- 现有 Profile（不带 `notify_channels`）→ 解析为空列表；不影响现有功能
- 现有 `ConfigLoader` 测试 → 不破坏；只新增 notify_channels 处理分支

### 9.2 schema 演进

- 未来扩展字段（如 `headers`、`template`、`retry`）→ 在 `NotifyChannelConfig` record 新增字段；YAML 加可选字段即可
- 旧 Profile YAML 不带新字段 → 反序列化时 record 默认值（如 null）→ 应用层处理

---

## 10. 不在本契约范围

- ❌ `${VAR:-default}` 默认值语法（核心阶段不强制实现）
- ❌ `headers` / `template` / `retry` 等扩展字段（核心阶段不支持）
- ❌ 通道配置的热更新（reload without restart）——核心阶段不支持；改 Profile 后需重启
- ❌ 通道配置的远程拉取（从 vault / 配置中心）——扩展阶段