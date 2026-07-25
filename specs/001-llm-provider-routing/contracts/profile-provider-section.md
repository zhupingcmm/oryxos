# 契约：Profile YAML 的 provider / tools 段

**目的**：定义 Profile YAML 中 Provider 路由层消费的两段配置（`provider`、`tools`）
**关联**：[spec.md](./spec.md) FR-004、FR-009、US-4、US-5

---

## Profile 中被 Provider 层消费的字段

```yaml
# .oryxos/agents/<name>/AGENT.md frontmatter
name: daily-weather
description: 每日天气 Agent
identity:
  agent_name: 天气播报员
  prompt: |
    你是天气播报员，每天 8 点给企业微信群推送上海天气。
provider:
  name: deepseek                          # 路由键 → 必须与 application.yml 中某条 name 一致
  model: deepseek-chat                    # 模型名（可与 application.yml 中该 name 的 model 不同）
  temperature: 0.5                        # 可选；不填用 application.yml 默认
  max_tokens: 2000                        # 可选
tools:                                    # 该 Agent 可用的工具名列表
  - http_get                              # 内置工具（oryxos-tool 模块注册）
  - notify                                # 内置工具
# ... 其他与本特性无关的 Profile 段（identity.tools / mcp_servers / schedules 等）略
```

---

## 字段约束

| 字段 | 必填 | 约束 |
|------|------|------|
| `provider.name` | ✅ | 必须与 `application.yml` 中某条已配置 Provider 的 `name` 字段**完全相等** |
| `provider.model` | ❌ | 不填则用 `application.yml` 中该 name 对应的默认 `model`；填了则覆盖 |
| `provider.temperature` | ❌ | `0.0 ~ 2.0` 区间浮点数；超出范围启动期 fail-fast |
| `provider.max_tokens` | ❌ | 正整数；0 或负数启动期 fail-fast |
| `tools` | ❌ | 字符串列表；每个元素必须是 `oryxos-tool` 注册过的工具名；未知工具名由 ReAct 层报错，不由 Provider 层处理 |

---

## Profile 加载失败的传播路径

```
ContextLoader 加载 AGENT.md
    ↓
校验 provider.name 是否在 ProviderRegistry 中存在
    ├─ 不存在 → Agent 启动失败，错误信息："profile 'daily-weather' declares unknown provider 'gpt-99'. Available: deepseek, qwen, minimax"
    └─ 存在 → 把 provider / tools 段翻译成 LlmRequest 所需字段
              ↓
              ProviderService.invoke(name, request)
```

**关键边界**：Profile 里写错 `provider.name` 是**启动期**错误，不是**运行期**错误。这样运维人员在 `oryxos serve` 启动那一刻就能看到所有错误，而不是某个 Agent 被定时器拉起来时才报。

---

## 与 application.yml 中 model 字段的优先级

| 情况 | 使用的 model |
|------|------------|
| Profile 写了 `provider.model` | 用 Profile 写的（**US-4 热切换**生效） |
| Profile 没写 `provider.model` | 用 `application.yml` 中该 name 对应的 `model` |
| Profile 写了，但该 name 在 application.yml 中没配置 | Profile 加载失败（不是 Provider 路由时报错，更早） |

---

## 反例（应被拒绝）

```yaml
# ❌ provider.name 不在实例目录中
provider:
  name: gpt-99           # 启动失败

# ❌ provider.name 拼写错（与配置的不一致）
provider:
  name: deepseekk        # 启动失败（typo）

# ❌ provider.temperature 越界
provider:
  name: deepseek
  temperature: 5.0       # 启动失败

# ❌ provider.max_tokens 为 0
provider:
  name: deepseek
  max_tokens: 0          # 启动失败

# ❌ 缺失 provider.name
tools: [http_get]        # 启动失败
```

---

## 工具 schema 翻译的传递性

Provider 层只**消费** `tools` 字段的"工具名列表"，并**自行**在 `ToolRegistry`（`oryxos-tool` 模块）中查每个名字对应的 schema。Profile 里**不**直接写 schema JSON，避免：

- Profile 文件膨胀
- 工具签名改了 Profile 不知道
- 工具无法跨 Profile 共享

Profile → Provider 的 `toolSchemas` 字段是 Provider 层在运行时动态查 `ToolRegistry` 得到的，不在 Profile 文件里写死。
