---
title: 系统架构 —— 分层详解
description: OryxOS 运行时分层：入口、引擎、能力、地基、外部。
---

# 系统架构

OryxOS 是基于 JDK 21 的 Spring Boot 3.x 单体应用，打包成单个可执行 fat JAR。运行时分为四个内部层 + 一个外部层。

![OryxOS 架构](/flow.svg)

---

## 第 1 层 —— 入口层

三种触发源，都是人或钟推，都进同一条 `AgentService.process(Session, String)`：

| 来源                 | 人/钟推     | 端点                                         | 用途                |
| -------------------- | ----------- | -------------------------------------------- | ------------------- |
| **CLI Channel**      | 人推        | `oryxos chat --profile <name>`               | 交互式 REPL、调试   |
| **REST Web Service** | 人推        | `POST /api/v1/agents/{name}/invoke`          | 系统集成、仪表板     |
| **AgentScheduler**   | 钟推        | `AGENT.md` schedules 里的 cron                | 定时报表、周期任务   |

入口层没有任何业务逻辑——只封装 `Session` + `String message`，调 `AgentService`。

## 第 2 层 —— 引擎层

ReAct 推理引擎。**四个组件都在 `oryxos-core`：**

### `AgentService` —— 单一入口

```java
public class AgentService {
    public AgentResponse process(Session session, String userMessage) {
        return reactLoop.run(session, userMessage, profile);
    }
}
```

`ReActLoop` 不在乎消息从 CLI、REST 还是 Scheduler 来——都进这里。

### `ReActLoop` —— 循环本体

```java
for (int i = 0; i < profile.maxIterations(); i++) {
    Prompt prompt = promptBuilder.build(session, profile);
    LlmResponse resp = providerService.call(profile, prompt);
    session.append(resp);
    if (!resp.hasToolCall()) break;
    ToolResult r = toolExecutor.execute(profile, resp.toolCall());
    session.append(r);
}
return session.lastMessage();
```

几十行 Java。`max_iterations` 上限（默认 10，profile 可覆盖）防死循环。

### `PromptBuilder` —— 顺序固定的四部分

1. **System prompt** —— `AGENT.md` 正文 + Bootstrap（`AGENTS.md` / `SOUL.md` / `USER.md`）+ 当前日期时间。
2. **Memory 注入** —— `MemoryService.findByKeyword(...)` 的 top-k。
3. **对话历史** —— 最近 `max_history_turns` 条，截断。
4. **Tool 列表** —— profile 可用 tool 的 function-calling schema。

### `ToolExecutor` —— 网关式执行

```java
public ToolResult execute(Profile profile, ToolCall call) {
    sandbox.enforce(toSandboxAction(call));          // ← 1. 策略检查
    Tool tool = toolRegistry.get(call.name());       // ← 2. 解析
    ToolResult r = tool.execute(call, profileCtx);   // ← 3. 执行
    audit.persist(profile, call, r);                 // ← 4. 落库（day-one）
    return r;
}
```

第 4 步的落库**不是可选**——没有绕过路径。

## 第 3 层 —— 能力层

引擎每次迭代都要调的三个能力：

### `ProviderService`

- 显式 `name → ChatModel` 映射（不按容器类型扫描）。
- 启动时从 `application.yml` 的 `oryxos.providers.<name>` 注册。
- `ProviderService.get(name)` 返回 `ChatModel`。
- 每次调用包一层：调模型 + 写 `llm_calls` 行。

### `MemoryService`

三层门面：

```
MemoryService
  ├─ SessionManager       (内存 + 每 Session 的 SQLite)
  └─ LongTermMemoryStore  (接口 —— 3 个后端)
       ├─ MarkdownMemoryStore   (默认 —— .oryxos/memory/MEMORY.md)
       ├─ SqliteMemoryStore     (结构化 —— memory_entries 表)
       └─ Mem0MemoryStore       (语义 —— 自托管 Mem0)
```

接口四条契约：

1. **不缓存** —— 每次 read 直接打底层存储。
2. **CORE scope 永不截断** —— 用户关键数据永远在。
3. **Agent 选 scope** —— `write(key, value, CORE | ARCHIVE)`。
4. **`recallByKeyword`** 只做关键词（不做语义）。

### `ToolRegistry` + `Sandbox` + `NotifyChannelAdapter`

- `ToolRegistry` —— name → `OryxTool` 映射。启动时 `@Component` 自动发现。
- `Sandbox` —— 接口，当前是 `WhitelistSandbox`。升级路径：容器、microVM。
- `NotifyChannelAdapter` —— 出站推送目标。核心阶段：`WebhookNotifyAdapter`（企业微信 / 飞书 / 钉钉）。

## 第 4 层 —— 地基层

用户可编辑的工作区 + 审计级持久化 + 密钥安全配置：

```
.oryxos/
├── agents/                  # Agent 目录（AGENT.md + skills/ + scripts/）
├── memory/
│   └── MEMORY.md            # 长期记忆（默认 Markdown）
├── sessions/                # 运行时 session 数据（SQLite 里也有镜像）
├── logs/                    # 结构化 JSON 日志
├── mcp_servers.yaml         # MCP 配置
├── AGENTS.md                # Bootstrap：项目级 agent 行为
├── SOUL.md                  # Bootstrap：默认 agent 人格
├── USER.md                  # Bootstrap：用户偏好
└── oryxos.db                # SQLite（5 张表）
```

### SQLite —— 5 张表，审计级

| 表                  | 用途                                                  | day-one 写入？ |
| ------------------- | ----------------------------------------------------- | -------------- |
| `sessions`          | Session 元数据 + JSON 对话历史                         | ✅             |
| `tool_invocations`  | 每条 tool 调用（success / error / duration）           | ✅（审计）     |
| `llm_calls`         | 每条 LLM 调用（provider / model / tokens / duration）  | ✅（成本/审计） |
| `scheduled_tasks`   | 注册的 cron 任务                                       | ✅             |
| `task_executions`   | 每次执行历史                                            | ✅             |

> ⚠️ SQLite 的 `ALTER TABLE` 有限；`hibernate.ddl-auto=update` 处理不了复杂迁移。后续 schema 演进要 Flyway / Liquibase。

### `ConfigLoader`

- 加载 `application.yml`。
- 加载时解析 `${ENV_VAR}` 占位。
- 加载 `.oryxos/mcp_servers.yaml`。
- 加载每个 `.oryxos/agents/*/AGENT.md`，派生 `Profile`。

## 外部层

这些**在 OryxOS 进程外**——OryxOS 默认不绑任何一个：

- **LLM Provider API** —— DeepSeek、Kimi、Qwen、智谱、豆包、Anthropic、OpenAI、Ollama。
- **MCP server** —— 外部 tool 提供方（stdio 或 HTTP 传输）。
- **Notify 目标** —— webhook URL（企业微信 / 飞书 / 钉钉 / Slack / 通用 webhook）。

所有外部调用都过能力层——也就是被沙箱检查、被审计。

## 横切关注点

### `ProfileContext`（ThreadLocal）

`OryxTool.execute(ToolCall, ProfileContext)` 不知道是哪个 Agent 调的。`ProfileContext` 是 `AgentService` 在调 `ToolExecutor` 前设置的 `ThreadLocal`，`finally` 里清。需要 Agent 上下文的 Tool 从那里取。

### 审计钩子

`tool_invocations` 和 `llm_calls` 都在返回结果的同一条调用路径里写入。没有异步 outbox，没有 fire-and-forget 日志。SQL 即真相。

### Virtual threads

JDK 21 virtual threads 驱动 REST API（Spring MVC 6+）和 `AgentScheduler` 任务池。核心阶段工作负载（三个 Demo）在 256MB 堆上跑得很舒服。

## 核心阶段架构里没有的东西

这些是有意不在核心阶段的。都建在核心引擎**之上**而不是替换它：

- ❌ 多租户路由层
- ❌ SSO / RBAC 过滤器链
- ❌ 审计查询 API + SIEM 导出
- ❌ Tool Policy（profile 级 allow/deny）
- ❌ Web 仪表板 UI
- ❌ 集群 HA（Nacos / ETCD）
- ❌ 向量记忆后端

接口保持稳定。扩展阶段是 bean 替换。

## 下一步

| 目标                                       | 看到什么                                          |
| ------------------------------------------ | ------------------------------------------------- |
| [功能特性](./features)                     | 5 大核心能力的详细参考                            |
| [使用场景](./scenarios)                    | 6 个企业级场景                                    |
| [给工程师](./for-engineer)                 | 构建、部署、扩展                                  |
| [路线图](./roadmap)                        | 核心阶段 vs 扩展阶段                              |