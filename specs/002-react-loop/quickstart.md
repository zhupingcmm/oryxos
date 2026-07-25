# Quickstart: ReAct Loop (US-2)

**Date**: 2026-07-25
**Branch**: `002-react-loop`
**Purpose**: 端到端验证 US-2 ReAct 循环的四个维度的可运行脚本：(1) 纯 Reason 路径；(2) 单 Tool 调用；(3) 多 Tool 串联；(4) Audit + 并发隔离。所有脚本可在 30 分钟内跑完，覆盖 spec SC-005 / SC-006。

> 这是 **validation/run guide**，不是 implementation details。完整代码实现见后续 `/speckit-tasks` 输出的 `tasks.md`。

---

## 0. 前置条件

| 项 | 要求 |
|----|------|
| JDK | 21（`java --version` → openjdk 21.x） |
| Maven | 3.9.x |
| SQLite | 4.x（来自 `spring-boot-starter-data-jpa` 内嵌） |
| 真实 LLM | `DEEPSEEK_API_KEY` 环境变量（也可临时改为 qwen/minimax，只需替换 `.oryxos/providers/<name>.yaml` 的 `name`） |
| 网络出口 | 能访问 `api.deepseek.com` + `api.openweathermap.org`（天气 Demo 用） |

构建一次性准备工作：

```bash
cd d:/code/java/oryxos
mvn -pl oryxos-core,oryxos-storage,oryxos-provider,oryxos-boot -am clean compile
```

预期：US-2 新增的所有接口 / record 编译通过；US-1 既有测试全部绿（35 个测试）。

---

## 1. Demo：纯 Reason 路径（US1 / P1）

**目标**：验证 `ReActLoop` 在用户消息不触发 Tool 时，一次 LLM 调用返回最终答复，且 Session 历史只包含两条消息（user + assistant）。

### 1.1 准备 Agent Profile

`.oryxos/agents/small-talk/AGENT.md`（如果不存在则创建）：

```markdown
---
name: small-talk
description: 一个简单问候 Agent，不使用任何 Tool。
provider:
  name: deepseek
  model: deepseek-chat
  temperature: 0.5
tools: []
settings:
  max_iterations: 10
  max_history_turns: 20
---

你是一个简洁、礼貌的 Agent。只回答用户问题，不主动展开话题。
```

### 1.2 启动 CLI

```bash
mvn -pl oryxos-boot spring-boot:run -Dspring-boot.run.profiles=e2e
```

新开终端：

```bash
mvn -pl oryxos-cli exec:java -Dexec.args="chat small-talk"
```

### 1.3 跑对话

```
> 你好
你好！很高兴见到你。有什么我能帮你的吗？
> ^D
```

### 1.4 验证

```sql
SELECT iteration, tool_calls, duration_ms, success FROM llm_calls
WHERE session_id = (
  SELECT id FROM sessions WHERE profile_name = 'small-talk' ORDER BY created_at DESC LIMIT 1
)
ORDER BY started_at;
```

预期：恰好 **1 行**，`success=1`（true），`duration_ms > 0` 且 < 3000 ms。

```sql
SELECT COUNT(*) FROM tool_invocations
WHERE session_id = (
  SELECT id FROM sessions WHERE profile_name = 'small-talk' ORDER BY created_at DESC LIMIT 1
);
```

预期：**0 行**（无 Tool 调用）。

```sql
SELECT json_extract(messages, '$') FROM sessions
WHERE profile_name = 'small-talk' ORDER BY created_at DESC LIMIT 1;
```

预期：消息列表长度为 **2**，第一条 `role=user, content="你好"`，第二条 `role=assistant, content="你好！很高兴见到你..."`（注意 spec FR-016："assistant(tool_call)"与"assistant(text)"是同一角色，子字段区分）。

---

## 2. Demo：单 Tool 调用（US2 / P2 = SC-005）

**目标**：验证循环跨过 Tool 执行（HTTP 天气查询），二次 Reason 后给出文本回复；2 次 LLM 调用 + 1 次 Tool 调用。

### 2.1 准备 Agent Profile

`.oryxos/agents/weather-bot/AGENT.md`：

```markdown
---
name: weather-bot
description: 简短天气播报员。
provider:
  name: deepseek
  model: deepseek-chat
  temperature: 0.5
tools:
  - http_get
settings:
  max_iterations: 10
  max_history_turns: 20
---

你是一个天气播报员。必须用一次 `http_get` 工具调用获取北京当前天气，然后**只**返回"今日北京天气 {温度}°C 晴/阴/雨"格式的一句话，不展开、不寒暄。
```

### 2.2 配置 Tool 白名单

`application.yml`（或 Profile YAML `tools.http_get.config`）声明 Tool 域名白名单（按 spec FR-011 + CLAUDE.md §"Sandbox"）：

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:
          - api.openweathermap.org
```

### 2.3 启动并跑对话

```bash
mvn -pl oryxos-cli exec:java -Dexec.args="chat weather-bot"
```

```
> 今天的天气怎么样？
[模型先发 tool_call：http_get(url=https://api.openweathermap.org/...?q=Beijing)]
[ToolExecutor 拦截域名白名单，检查通过；调用一次]
[ToolExecutor 返回 22°C 晴]
[模型最终回复：今日北京天气 22°C 晴]
> ^D
```

### 2.4 验证

```sql
-- 两次 LLM 调用：第一次返 tool_call，第二次返 text
SELECT COUNT(*) FROM llm_calls
WHERE session_id = (
  SELECT id FROM sessions WHERE profile_name = 'weather-bot' ORDER BY created_at DESC LIMIT 1
);
```

预期：**2 行**。

```sql
-- 一次 Tool 调用：成功
SELECT tool_name, success, duration_ms FROM tool_invocations
WHERE session_id = (
  SELECT id FROM sessions WHERE profile_name = 'weather-bot' ORDER BY created_at DESC LIMIT 1
);
```

预期：**1 行**，`tool_name='http_get', success=1, duration_ms>0`。

```sql
-- 消息列表长度 = 2 + 2 = 4：user + assistant(tool_call) + tool(result) + assistant(text)
SELECT json_array_length(json_extract(s.messages, '$')) FROM sessions s
WHERE profile_name = 'weather-bot' ORDER BY created_at DESC LIMIT 1;
```

预期：**4 条**消息，按 `user → assistant(tool_call) → tool(result) → assistant(text)` 顺序。

---

## 3. Demo：多 Tool 串联（US3 / P3 + SC-006 准备）

**目标**：验证循环跨两个不同 Tool 一次调用 + 一次 Reason 后出答复；3 次 LLM 调用 + 2 次 Tool 调用；`in_memory_session_iteration` 与 `ToolInvocationRecord.session_iteration` 一致。

### 3.1 准备 Profile

`.oryxos/agents/daily-digest/AGENT.md`：

```markdown
---
name: daily-digest
description: 每日科技日报 Agent。
provider:
  name: deepseek
  model: deepseek-chat
  temperature: 0.3
tools:
  - http_get
  - read_file
  - save_memory
  - recall_memory
settings:
  max_iterations: 10
  max_history_turns: 30
---

你的任务流程：
1. 先用 `read_file` 读取 `.oryxos/memory/MEMORY.md`，找出最近 3 天用户关注的主题。
2. 用 `http_get` 取每个主题的最新 1 条 RSS / GitHub Trending 摘要。
3. 用 `recall_memory` 检查是否有用户偏好的"避免话题"。
4. 用 `save_memory` 把今天的观察写进 MEMORY.md（不与"避免话题"重合的部分）。
5. 最后输出一句话总结（≤60 字）。
```

### 3.2 准备 Memory 种子

`.oryxos/memory/MEMORY.md`（最小）：

```markdown
# 长期记忆

## 用户偏好
- 避免政治
- 偏好 AI / 编程 / 开源

## 最近主题
- 2026-07-24：claude-code、deepseek-v3
- 2026-07-23：github-actions、kubernetes
- 2026-07-22：sqlite、jpa
```

### 3.3 启动并跑

> 注：本 Demo 的 Memory/Save 工具实现在 US-3 阶段完成；US-2 阶段跑此 demo 预期 4 个工具里有 2 个返回 `tool not in profile`（`save_memory`/`recall_memory` 还不在任何 Profile 的白名单中）。

步骤同 §2.3。

### 3.4 验证

```sql
SELECT
  llm.id            AS llm_id,
  llm.success       AS llm_success,
  inv.id            AS inv_id,
  inv.tool_name     AS tool_name,
  inv.success       AS tool_success,
  inv.session_iteration AS iter
FROM llm_calls llm
LEFT JOIN tool_invocations inv
  ON inv.session_id = llm.session_id
 AND inv.session_iteration = llm.session_iteration
WHERE llm.session_id = (
  SELECT id FROM sessions WHERE profile_name = 'daily-digest' ORDER BY created_at DESC LIMIT 1
)
ORDER BY llm.started_at;
```

预期：3 行 `llm_calls`（iter=0..2），其中 iter=0 + iter=1 各有 1 行 tool_invocations（`read_file` + `http_get`），iter=2 无 tool_invocations。`MAX_ITERATIONS=10` 远未触达。

完整验证清单见 spec SC-001/SC-003/SC-004/SC-006。

---

## 4. Audit + 并发验证（SC-003 + I-04）

**目标**：20 个并发 `process(...)` 调用共用同一个 Spring Application Context，每个 Session 的消息列表完全独立；`llm_calls` / `tool_invocations` 行通过 `session_id` 列正确归属。

### 4.1 触发

`scripts/load-test.sh`（注入 20 个并发 grep）：

```bash
#!/bin/bash
N=20
for i in $(seq 1 $N); do
  (mvn -pl oryxos-cli exec:java -Dexec.args="chat small-talk <<< \"hi from session $i\"") &
done
wait
```

### 4.2 验证

```sql
SELECT COUNT(DISTINCT session_id) FROM llm_calls WHERE profile_name = 'small-talk';
```

预期：**N = 20**。

```sql
WITH s AS (
  SELECT id, json_array_length(json_extract(messages, '$')) AS msg_count FROM sessions
  WHERE profile_name = 'small-talk' ORDER BY created_at DESC LIMIT 20
)
SELECT MIN(msg_count), MAX(msg_count), COUNT(*) FROM s;
```

预期：每个 Session 各 2 条消息（user + assistant）；MIN=MAX=2；COUNT=20。

```sql
SELECT s.id, llm.session_iteration
FROM sessions s
JOIN llm_calls llm ON llm.session_id = s.id
WHERE s.profile_name = 'small-talk'
ORDER BY s.created_at DESC, llm.started_at
LIMIT 40;
```

预期：每条 `llm_calls` 的 `session_iteration` 是其 Session 内的迭代编号，与 [ProfileContext.md §4.3](contracts/ProfileContext.md) 一致；无跨 Session 串扰。

---

## 5. 故障 / 边界回归

| 场景 | 预期行为 | 来源 |
|------|----------|------|
| LLM 调用 4xx/5xx/超时 | `AgentService.process` 抛 `LlmInvocationException`，循环立即终止；`LlmCallRecord.success=false` 已写 | spec Edge case 1 |
| Tool 调用抛 unchecked | 异常被 `ToolExecutor` 捕获、记 `success=false` 行、回喂 LLM；循环继续 | spec Edge case 2 |
| Tool 名不在 Profile 白名单 | 返回 `ToolResult.error("tool not in profile: X")`，写 `success=false` 行，循环继续 | spec FR-011 |
| LLM 返空 `tool_calls` + 空 `text` | 当作模型截断；返回 `LoopResult(finalText="model returned empty response", iter=N, terminatedAtMax=false)` | spec Edge case 4 |
| `MAX_ITERATIONS=0` | 跳过整个循环；返回 `LoopResult("loop not configured", 0, true, ...)` | spec Edge case 5 |
| Session 引用的 Profile 未注册 | `AgentService` 抛 `IllegalArgumentException("Unknown profile: ...")` | spec FR-002 |
| CLI SIGINT (Ctrl+D) | 已写入的审计行不丢；Session 是 partial state 可回放 | spec NFR-002 / Edge case 10 |
| Spring 工作线程被复用且未清 ProfileContext | `ProfileContextTest#doubleSetThrows` 防止 set 重入 | spec I-06 |

每条用对应单元/集成测试覆盖。

---

## 6. 验收清单映射

| 验收标准 | 来源 | 本 quickstart 对应小节 |
|----------|------|------------------------|
| SC-001：恰好 N+1 次 LLM 调用 + N 次 Tool 调用 | §3.4 | §2 / §3 |
| SC-002：MAX_ITERATIONS 截断 | 不在本 quickstart；见 `ReActLoopTerminationTest` 单元 | （单元） |
| SC-003：20 个并发调用零串扰 | §4.1 / §4.2 | §4 |
| SC-004：100% Tool 调用落审计行（含 `tool not in profile`） | §1.4 / §2.4 | §1 / §2 |
| SC-005：每日天气 Demo 三触发源一致 | §2（CLI 触发；Web/Scheduler 由 US-5 提供） | §2 |
| SC-006：每日科技日报 Demo 端到端 | §3 | §3 |
| SC-007：循环可观测性（log 行 ≥ 1 总结 + N 迭代） | 通过 `~/.oryxos/logs/oryxos.log` 检查 `react.iteration` / `react.completed` | （log 路径） |
| NFR-001：5 次 Tool ≤ 30 s | 实际跑 §2 计时 | §2 |

---

## 7. Done 信号

US-2 通过 `/speckit-analyze`、所有 quickstart 小节可跑通、零核心原则漂移 → `/speckit-tasks` 开始实施 → 实施完成 → 跑 `/speckit-analyze` 复检 → git commit + push → 开 PR。
