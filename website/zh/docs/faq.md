---
title: 常见问题
description: 关于 OryxOS 的常见问题。
---

# 常见问题

## 总览

### OryxOS 是什么？

OryxOS 是基于 Java + Spring Boot 的企业级 Agent Operating System 运行时内核。装在企业自己的基础设施上，跑多个业务 Agent，共享一套核心能力：LLM Provider 路由、ReAct 推理、三层记忆、插件 Tool、REST API。详见 [OryxOS 是什么](./what)。

### OryxOS 给谁用？

严监管企业（银行 / 政府 / 电信 / 能源 / 医疗）——它们的 IT 主干是 Java、数据必须本地化、每条 LLM 和 tool 调用必须可审计。

如果是消费者或小团队，OpenClaw 或 Hermes Agent 可能更合适。

### OryxOS 在什么阶段？

**核心阶段** —— 正在搭运行时内核（5 大能力 + 3 个 Demo）。4 周 × 3 小时集中开发。扩展阶段（多租户 / SSO / 完整审计 / Tool Policy / Web 仪表板）在后面。

### OryxOS 用的什么协议？

MIT。详见 [LICENSE](https://github.com/oryxos/oryxos/blob/main/LICENSE)。

---

## 架构

### 为什么不选 Python 而选 Java？

Java 是严监管企业的 IT 主干——Nacos / Sentinel / SkyWalking / Arthas / Prometheus+Grafana。Agent 层必须接进这个栈。Python 或 Node 的 Agent 框架接不进来。

### 既然不用 Spring AI 的 Agent 抽象，为什么还用它？

Spring AI 用一半：

- ✅ Provider 抽象（多 LLM 厂商）
- ✅ 协议转换（Anthropic / OpenAI / DashScope 线协议）
- ✅ `@Tool` schema 生成（function-calling payload）

**不用**：

- ❌ Agent 抽象
- ❌ 自动 tool 执行
- ❌ 内置 ReAct 循环

### 为什么自实现 ReAct 循环？

Spring AI 的 `AgentExecutor` / `FunctionCallingAgent` 会自动执行 tool 调用。跟我们的 `ToolExecutor` 叠加，每个 tool 被调两次。自实现是唯一解法。

### 为什么 SQLite 而不是 Postgres？

核心阶段够用：

- 单二进制部署（无 DB server）
- 5 张表，都不大
- SQL 可查审计

Postgres 是扩展阶段多租户部署的选择。

### 为什么 `tool_invocations` 写库而不是只写日志？

day-one 合规。合规问"上周二发生了什么"——SQL 一条答完。日志没法按 `profile_name = 'daily-weather' AND tool_name = 'http_get' AND success = 0` 查。

---

## 运维

### 怎么加新 Provider？

1. 在 `application.yml` 的 `oryxos.providers.<name>` 加配置。
2. 实现 `ProviderInitializer` 或用内置 adapter。
3. 在 `AGENT.md` 里引用 `provider.name`。

详见 [给工程师](./for-engineer#加一个-provider)。

### 怎么加新 Tool？

实现 `OryxTool`，加 `@Component`。自动发现。详见 [功能特性](./features#4-插件-tool--沙箱-oryxos-tool)。

### 怎么加新 Notify 渠道？

实现 `NotifyChannelAdapter`，加 `@Component`。通过 `notify_channels[].type` 引用。详见 [给工程师](./for-engineer#加一个-notify-渠道)。

### Profile 能运行时改吗？

**不能**——核心阶段 Profile 是文件，启动时读一次。改了要重启 gateway。运行时 Profile CRUD 是扩展阶段。

### Agent 能通过 REST 创建吗？

**不能**——同理。在 `.oryxos/agents/<name>/` 丢 `AGENT.md`，重启。

### 审计表保留多久？

核心阶段没策略，表无限增长。扩展阶段会加保留 job（比如 90 天后归档到冷存储）。现在先算好磁盘。

---

## 安全

### OryxOS 能暴露公网吗？

**不能**。核心阶段无认证、无授权、无限流。只跑在内网。多租户 / SSO / RBAC 是扩展阶段。

### API key 会泄露吗？

永不硬编码到 `AGENT.md` 或 `application.yml`。用 `${ENV_VAR}` 占位，加载时从环境变量解析。

### 脚本能逃出沙箱吗？

能。脚本在子进程里跑，有自己的网络 / 文件系统权限。装一个带 `scripts/` 的 Agent = **信任 Agent 作者**。核心阶段不隔离脚本——容器 / microVM 隔离是扩展阶段。

---

## 对比

### OryxOS vs OpenClaw？

OpenClaw 是 Node.js，消费者向。OryxOS 是 Java，严监管企业向。OpenClaw 单租户、无审计。OryxOS day-one 审计表。

### OryxOS vs Hermes Agent？

Hermes Agent 是 Python，团队向。同大类但语言生态和目标市场不同。

### OryxOS vs Dify / Coze？

Dify / Coze 是可视化工作流编排器，公有云 SaaS。OryxOS 是运行时内核，本地私有。Dify/Coze 面向不写代码的业务用户；OryxOS 面向想要完全控制的 Java 团队。

### OryxOS vs LangChain？

LangChain 是 Python 框架。OryxOS 是 Java 运行时，基于 Spring AI Alibaba（受 LangChain 启发但 Java 原生）。

---

## 路线图

### 多租户什么时候有？

核心阶段完成后进扩展阶段。详见 [路线图](./roadmap)。

### 会有 Python SDK 吗？

社区阶段。Java SDK（Spring Boot Starter）是核心和扩展阶段的唯一官方 SDK。

### 会有向量记忆吗？

扩展阶段。后端待定：LanceDB Java、pgvector、JVector。

---

## 下一步

| 目标                                       | 看到什么                                          |
| ------------------------------------------ | ------------------------------------------------- |
| [快速开始](./quick-start)                  | 本地跑通三个 Demo                                  |
| [系统架构](./architecture)                 | 分层详解                                          |
| [路线图](./roadmap)                        | 核心 / 扩展 / 社区三阶段                          |