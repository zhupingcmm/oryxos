# Command Contract: `oryxos chat`

**Command group**: 必须启动 Spring（[FR-012](../003-cli-commands/spec.md)）
**Spec section**: [US-1](../003-cli-commands/spec.md)

## 用法

```
oryxos chat <profile-name> [--message <msg>] [--session-id <id>]
```

| 参数 | 必需 | 含义 |
|------|------|------|
| `<profile-name>` | ✅ | `.oryxos/agents/<name>/` 下的 Profile 名；匹配 `^[a-z][a-z0-9-]{0,63}$`（[FR-015](../003-cli-commands/spec.md)） |
| `--message <msg>` | 可选 | 单轮 user message；若省略，从 stdin 读一行 |
| `--session-id <id>` | 可选 | 续接已有 Session；省略时新建 |

## 行为契约

1. **启动 Spring Context** → 解析 `<profile-name>` → 构造 `Session` → 调 `AgentService.process(Session, message)`。
2. **stdout**：单行打印 `LoopResult.finalText()`，**无前缀无装饰**。
3. **stderr**：仅错误摘要。
4. **退出码**：

   | 场景 | 退出码 |
   |------|--------|
   | 成功打印 Agent 文本 | 0 |
   | `<profile-name>` 在 `.oryxos/agents/` 下不存在 | 64（EX_USAGE） |
   | Profile YAML 解析失败 | 78（EX_CONFIG） |
   | API key 缺失（`DEEPSEEK_API_KEY` 未注入） | 69（EX_UNAVAILABLE） |
   | Spring Context 启动失败 | 1 |
   | Provider 不可达 / LLM 4xx-5xx | 1 |

5. **审计**：复用 US-2 day-one 表 —— `sessions` / `llm_calls` / `tool_invocations` 由 `AgentService` / `ReActLoop` / `ToolExecutor` 自动落库，**不**绕过。

## 禁止行为

- ❌ 绕过 `AgentService.process()` 直接驱动 `ReActLoop`（[FR-002](../003-cli-commands/spec.md)）
- ❌ 直接持有 `ChatModel` Bean（[NFR-002](../003-cli-commands/spec.md)）
- ❌ 启用 Spring AI 自动 tool 执行（[Constitution §IV](../../.specify/memory/constitution.md)）
- ❌ 在 stdout 打印 stack trace（[FR-018](../003-cli-commands/spec.md)）
- ❌ 在 stdout 打印 API key（[FR-020](../003-cli-commands/spec.md)）

## 测试要点

- 端到端：`scripts/cli-smoke.sh chat` 在真实 DeepSeek + stub Tool 上跑通
- 单元：CommandLine `--help` 含 `<profile-name>` + `[--message]` + `[--session-id]`
- 集成：WireMock 模拟 DeepSeek → 验证 stdout / exit code / 审计行
