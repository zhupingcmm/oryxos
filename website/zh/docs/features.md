---
title: 功能特性 —— 详细参考
description: OryxOS 5 大核心能力的详细参考。
---

# 功能特性

OryxOS 5 大核心能力的详细参考。

> 默认你已经读过 [系统架构](./architecture)。本页聚焦每个能力的运行参考。

---

## 1. LLM Provider 路由（`oryxos-provider`）

### 概念

Provider 是显式 `name → ChatModel` 映射，启动时从 `application.yml` 注册。不按容器类型扫描。

### 为什么显式

Spring AI 默认行为是把每个 `ChatModel` bean 注册到 `Map<Class<? extends ChatModel>, ChatModel>`，按实现类型当 key。当你有同类型的两个 provider（比如两个 OpenAI 兼容端点），第二个就撞第一个。OryxOS 用字符串名作 key 绕开这个坑。

### 配置

```yaml
oryxos:
  providers:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      model: deepseek-chat
      temperature: 0.7

    kimi:
      api-key: ${KIMI_API_KEY}
      base-url: https://api.moonshot.cn
      model: moonshot-v1-8k

    qwen:
      api-key: ${QWEN_API_KEY}
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      model: qwen-plus
```

### AGENT.md 引用

```yaml
provider:
  name: deepseek
  model: deepseek-chat   # 可选覆盖 provider 默认
```

`ProviderService` 暴露：

```java
ChatModel get(String name);                    // 未注册抛异常
void register(String name, ChatModel model);   // 只在启动时
Set<String> names();                           // 给 GET /api/v1/profiles
```

### 审计

每次 `provider.call(...)` 写一行 `llm_calls`：

```sql
SELECT created_at, profile_name, provider, model,
       prompt_tokens, completion_tokens, total_tokens, duration_ms
FROM llm_calls
ORDER BY created_at DESC LIMIT 10;
```

---

## 2. ReAct 循环（`oryxos-core`）

### 概念

Reason+Act 引擎**自实现**。**不用** Spring AI 的 `Agent` 抽象——它会自动执行 tool，导致重复调用。

### 循环形状

```java
for (int i = 0; i < profile.maxIterations(); i++) {
    Prompt prompt = promptBuilder.build(session, profile);
    LlmResponse resp = providerService.call(profile, prompt);
    session.append(resp);
    if (!resp.hasToolCall()) return session.lastMessage();
    ToolResult r = toolExecutor.execute(profile, resp.toolCall());
    session.append(r);
}
throw new MaxIterationsExceeded(profile.maxIterations());
```

### PromptBuilder —— 顺序固定的 4 部分

| # | 部分              | 来源                                       |
| - | ----------------- | ------------------------------------------ |
| 1 | System prompt     | `AGENT.md` 正文 + Bootstrap + 当前日期时间 |
| 2 | Memory 注入       | `MemoryService.findByKeyword(query)` top-k |
| 3 | 对话历史          | 最近 `max_history_turns` 条                  |
| 4 | Tool 列表         | profile `tools` 的 function-calling schema |

### ProfileContext（ThreadLocal）

Tool 不知道是哪个 Agent 调它。`ProfileContext` 是 `AgentService` 设置的 ThreadLocal，`finally` 里清：

```java
ProfileContext.set(currentProfile);
try {
    return toolExecutor.execute(profile, resp.toolCall());
} finally {
    ProfileContext.clear();
}
```

### `max_iterations`

默认 `10`。profile 可覆盖。上限防失控。耗尽迭代还没出最终答案时抛 `MaxIterationsExceededException`，部分 session 持久化。

---

## 3. 三层记忆（`oryxos-memory`）

### 概念

`MemoryService` 是统一门面。ReAct 只看 `MemoryService`——从来不直接看后端。

### 三层

```
MemoryService                ←─── ReAct 只看这个
  ├─ SessionManager          ←─── 短期，按 Session
  └─ LongTermMemoryStore     ←─── 长期，接口
       ├─ MarkdownMemoryStore
       ├─ SqliteMemoryStore
       └─ Mem0MemoryStore
```

### 接口契约

四条规则，不可改：

1. **不缓存** —— 每次 read 直接打底层。
2. **CORE scope 永不截断** —— 用户关键数据永远在。
3. **Agent 选 scope** —— `write(key, value, CORE | ARCHIVE)`。
4. **`recallByKeyword`** 只关键词（核心阶段不做 embedding）。

### 默认后端：MarkdownMemoryStore

```
.oryxos/memory/
└── MEMORY.md
```

文件由 Agent 分成两段：

```markdown
# MEMORY.md

## CORE
- user.prefers.format = table
- user.timezone = Asia/Shanghai

## ARCHIVE
### 2025-07-21
- tech-digest: <完整日报>
### 2025-07-20
- github-trending: <top 10 repos>
```

CORE 每次构造 prompt 都读。ARCHIVE 只在显式 `recall` 时读。

### 给 Agent 的 Tool

Agent 在 function-calling schema 里看到两个记忆 tool：

- `memory_write(key, value, scope)` —— 持久化到 CORE 或 ARCHIVE。
- `memory_recall(keyword, scope?)` —— 关键词搜索，返回匹配条目。

### 记忆是 day-one

`Session` 从 US-1 开始就写 SQLite。`MEMORY.md` 每次 `memory_write` 都立即落盘。没有"in-flight 状态"。

---

## 4. 插件 Tool + 沙箱（`oryxos-tool`）

### 概念

Tool 是 Agent 的手。`OryxTool` 是接口，注册到 `ToolRegistry`。三档扩展，同一个接口。

### 三档扩展

| 档位       | 方式                                    | 用途                              |
| ---------- | --------------------------------------- | --------------------------------- |
| 零代码     | `AGENT.md` + MCP server                 | 大多数场景——Model 动态发现 tool |
| 轻代码     | 自实现 MCP server                       | 跨语言，不用 Java                  |
| 重代码     | `@OryxTool` 注解的 Java bean            | 性能敏感、深 Spring 集成           |

### 内置 tool（核心阶段交付 9 个）

| Tool           | 用途                                  | 沙箱动作            |
| -------------- | ------------------------------------- | ------------------- |
| `read_file`    | 读 `.oryxos/` 下的文件                 | `FILE_READ`         |
| `write_file`   | 写文件到 `.oryxos/` 下                 | `FILE_WRITE`        |
| `shell`        | 跑 shell 命令                          | `SHELL_COMMAND`     |
| `http_get`     | GET 一个 HTTP URL                       | `HTTP_REQUEST`      |
| `http_post`    | POST 到一个 HTTP URL                    | `HTTP_REQUEST`      |
| `memory_read`  | 读长期记忆                             | （内部）             |
| `memory_write` | 写长期记忆                             | （内部）             |
| `notify`       | 推到出站渠道                           | （用渠道的 URL）     |
| `list_agents`  | 发现其他 profile                       | （内部）             |

### 沙箱

`Sandbox.enforce(SandboxAction)` 在每次 tool 执行前调用。核心阶段：`WhitelistSandbox`。

```java
public class WhitelistSandbox implements Sandbox {
    @Override
    public void enforce(SandboxAction action) {
        if (!whitelist.matches(action)) {
            throw new SandboxViolationException(action);
        }
    }
}
```

违反抛 `SandboxViolationException`，翻译成 HTTP 403 / CLI exit code 2，记入 `tool_invocations.error_message`。

### MCP 集成

`.oryxos/mcp_servers.yaml`：

```yaml
servers:
  - name: github
    transport: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-github"]
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}

  - name: filesystem
    transport: http
    url: http://localhost:3001/mcp
```

从 MCP server 发现的 tool 加到 Agent 的可用 tool 列表。它们共享同一个 `OryxTool` 接口，ReAct 循环不知道也不在乎它们来自 MCP。

---

## 5. REST API（`oryxos-web`）

### 10 个生产端点

```
Session
  POST   /api/v1/sessions
  POST   /api/v1/sessions/{id}/messages
  GET    /api/v1/sessions/{id}
  DELETE /api/v1/sessions/{id}

Agent
  POST   /api/v1/agents/{name}/invoke

查询
  GET    /api/v1/profiles
  GET    /api/v1/memory
  GET    /api/v1/tools

系统
  GET    /api/v1/health
  GET    /api/v1/info
```

### Spring MVC + virtual threads

REST API 跑在 Spring MVC 6+，JDK 21 virtual threads 启用。`application.yml` 里 `spring.threads.virtual.enabled=true`。

### OpenAPI

springdoc-openapi 自动生成 spec，在 `/v3/api-docs`，Swagger UI 在 `/swagger-ui.html`。

### 没有认证、没有限流——故意

核心阶段端点全开放。多租户、SSO、RBAC、限流都是扩展阶段。**核心阶段 OryxOS 别暴露公网。**

---

## 横切：审计

`tool_invocations` 和 `llm_calls` 在返回结果的同一条调用路径里写入。没有异步 outbox。SQL 即真相。

```sql
-- Tool 调用：谁、什么、何时、多久、成功/失败
SELECT * FROM tool_invocations
WHERE created_at > datetime('now', '-1 day')
  AND success = 0;  -- 只看失败

-- LLM 调用：成本
SELECT profile_name, provider, model,
       SUM(total_tokens) AS tokens,
       COUNT(*) AS calls
FROM llm_calls
WHERE created_at > datetime('now', '-7 day')
GROUP BY profile_name, provider, model;
```

---

## 下一步

| 目标                                       | 看到什么                                          |
| ------------------------------------------ | ------------------------------------------------- |
| [使用场景](./scenarios)                    | 6 个企业级场景                                    |
| [系统架构](./architecture)                 | 分层详解                                          |
| [给工程师](./for-engineer)                 | 构建、部署、扩展                                  |