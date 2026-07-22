---
title: 七条原则 —— 宪法
description: 七条不可改的 OryxOS 开发宪法原则。
---

# 七条原则

七条不可改的 OryxOS 开发宪法原则。AI 编码 agent 必须无条件遵守。**不能**由个人贡献者单方面修改——改动需要 maintainer 评审。

> 来源：项目仓库 [`docs/AiProgrammingGuide.md` §3.2](https://github.com/oryxos/oryxos/blob/main/docs/AiProgrammingGuide.md)。

---

## 原则 1 —— 单 Java/Spring Boot 运行时

OryxOS 是 **JDK 21 + Spring Boot 3.x** 单体应用，Maven 多模块（9 个）。一个二进制部署，一个进程监控。

- ❌ 不搞微服务爆炸——运行时就是一个 JVM。
- ❌ 核心阶段不做多语言持久化——只用 SQLite。
- ❌ 不搞替代部署形式（Docker Swarm、Nomad）——核心阶段就是 K8s + fat JAR。

扩展阶段可加多节点 HA（Nacos / ETCD），但核心是单二进制。

---

## 原则 2 —— 5 大核心能力优先

5 大核心能力（Provider / ReAct / Memory / Tool / REST）**必须**完整、可演示，然后再动扩展阶段的活。

扩展阶段（多租户 / SSO / 完整审计 / Tool Policy / Web 仪表板）建在核心内核**之上**。核心阶段不要去碰扩展阶段的东西。

---

## 原则 3 —— 自实现 ReAct 循环

Reason+Act 引擎在 `oryxos-core/ReActLoop` 自实现。**不**委托给 Spring AI 的 `AgentExecutor` / `FunctionCallingAgent`。

原因：Spring AI 的 Agent 抽象会自动执行 tool。跟我们的 `ToolExecutor` 叠加，每个 tool 被调两次。自实现是唯一解法。

---

## 原则 4 —— Spring AI 只用一半

Spring AI **只**用于：

- ✅ Provider 抽象（多 LLM 厂商）
- ✅ 协议转换（Anthropic / OpenAI / DashScope 线协议）
- ✅ `@Tool` schema 生成（function-calling payload）

**不**用于：

- ❌ Agent 抽象
- ❌ 自动 tool 执行
- ❌ 内置 ReAct 循环

在配置里禁用 Spring AI 的自动 tool 执行。没有绕过 `ToolExecutor` 的路径。

---

## 原则 5 —— Tool 三档接入

Tool 通过三档扩展，都用同一个 `OryxTool` 接口：

- **零代码** —— `AGENT.md` + MCP server（推荐大多数场景）
- **轻代码** —— 自实现 MCP server（跨语言，不用 Java）
- **重代码** —— `@OryxTool` 注解的 Java bean（性能敏感）

**Tool 相关代码全部在 `oryxos-tool`** —— 不要拆成多个模块（`builtin` / `skill` / `mcp` 等）。一个模块一件事。

---

## 原则 6 —— SQLite + MEMORY.md 文件存储

核心阶段使用：

- **SQLite** 存结构化数据（sessions / tool_invocations / llm_calls / scheduled_tasks / task_executions）
- **`MEMORY.md` 文件** 存长期记忆（默认 MarkdownMemoryStore）

`tool_invocations` 和 `llm_calls` 从 **day-one** 就写进 SQLite——审计地基。没有"以后再加日志"的路径。审计表由 `ToolExecutor` 和 `ProviderService` 直接写入。

向量记忆**不在核心阶段**。可插拔向量后端（pgvector / LanceDB Java / JVector）是扩展阶段。

> ⚠️ **SQLite 注意事项**：`ALTER TABLE` 有限。`hibernate.ddl-auto=update` 处理不了复杂迁移。后续 schema 演进要 Flyway / Liquibase。

---

## 原则 7 —— 每个 user story 后有可跑 Demo

每个 user story 结束都要有一个**可跑的 Demo** 端到端覆盖新能力。**能跑 > 完美**。

每个 user story 后跑 `/speckit.analyze` 验证没漂移。强制——防漂移比速度更重要。

---

## 三个常见坑

AI agent 最常犯的错。看到这些症状就停下来。

### 坑 1 —— 启用 Spring AI 自动 tool 执行

开了自动执行开关，每个 tool 被调两次——一次 Spring AI 调，一次我们的 `ToolExecutor` 调。第一次没有审计行，第二次没有 schema。整个系统就坏了。

**症状**：tool 在响应 payload 里出现两次。

**修复**：自动执行保持关闭。Tool 调度完全归 `ReActLoop` + `ToolExecutor` 管。

### 坑 2 —— Provider 按容器类型扫描

用 `Map<Class<? extends ChatModel>, ChatModel>` 按实现类型当 key，同类型的两个 provider（比如两个 OpenAI 兼容端点）撞车。第二个覆盖第一个。

**修复**：维护显式 `Map<String name, ChatModel>`，`ProviderService.get(String name)`。profile 的 `provider.name` 是查找 key。

### 坑 3 —— 只写日志不写库

如果 `tool_invocations` 和 `llm_calls` 只写日志文件（不写 SQLite 表），合规团队查不了。日志扒不是 SQL。

**修复**：在返回结果的同一条调用路径里写 SQLite 表。没有异步 outbox，没有"以后补"。

---

## 宪法里没有的

这些是有意不在核心阶段宪法的。都进扩展阶段：

- 认证 / SSO / RBAC
- 多租户
- 通过 API 创建 / 更新 Profile
- 通过 API 创建 / 更新 Agent
- SSE 流式响应
- 向量记忆
- 自适应路由
- 集群 HA
- Web 仪表板
- Tool Policy

接口保持稳定。扩展阶段是 bean 替换。

---

## 修改宪法

宪法**不能**单方面改。修改需要：

1. 在 GitHub 提 issue，说明改动 + 理由。
2. maintainer 评审讨论。
3. 合 `docs/AiProgrammingGuide.md` §3.2 的 PR。

AI 编码 agent **不能**自己改宪法。

---

## 下一步

| 目标                                       | 看到什么                                          |
| ------------------------------------------ | ------------------------------------------------- |
| [系统架构](./architecture)                 | 分层详解                                          |
| [功能特性](./features)                     | 5 大核心能力的详细参考                            |
| [路线图](./roadmap)                        | 核心 / 扩展 / 社区三阶段                          |