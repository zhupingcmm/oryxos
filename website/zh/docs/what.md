---
title: OryxOS 是什么
description: OryxOS —— 基于 Java + Spring Boot 的企业级 Agent OS。
---

# OryxOS 是什么

**OryxOS** 是基于 **Java + Spring Boot** 的企业级 **Agent Operating System**。装在企业自己的 K8s 集群或服务器上，在上面跑多个业务 Agent（运维 / 客服 / HR / 销售 / 知识管理等），共享一套核心能力：LLM Provider 路由、自实现 ReAct 推理循环、三层记忆、插件 Tool（含沙箱）和 REST API。

**数据完全不离开企业内网，无云锁定，MIT 开源。**

## 两个核心问题

任何企业级多 Agent 系统，无论语言或框架，都会遇到同样的两个问题。

**问题 1：Agent 跑不进现有技术栈。**

银行 / 政府 / 电信的 IT 主干是 Java。Python 和 Node.js 的 Agent 框架再先进，也接不进 Nacos / Sentinel / SkyWalking / Prometheus+Grafana 这套基础设施。Agent 层缺位。

**问题 2：Agent 跑起来之后既不能审计，也不能私有。**

公有云 SaaS 把对话发到厂商域。每次 tool 调用、每次 LLM 调用都必须可追溯。"我们后台有日志"不够——需要 SQL 可查的审计表。

**OryxOS 一次解决两个问题。**

## OryxOS 如何解决

### Java 原生运行时

JDK 21 + Spring Boot 3.x 单体应用，打成单个 fat JAR 直接跑。直接接入现有 Java 基础设施。GraalVM Native Image 是扩展阶段的路线图。

### day-one 审计

两个审计表：`tool_invocations` 和 `llm_calls`，从第一个 user story 开始就写。每条 tool 调用记录 success / error / duration；每条 LLM 调用记录 provider / model / tokens / duration。合规团队一条 SQL 就能回放任意历史调用。

### 零代码定义 Agent

业务用户写文件就能定义 Agent，不用写 Java：

```
.oryxos/agents/<name>/
├── AGENT.md            # frontmatter = profile，正文 = 系统提示词
├── skills/             # 子指令，按需读取
└── scripts/            # 脚本，通过 shell tool 运行
```

Model 通过内置 `read_file` / `shell` 工具按需取 `skills/` 和 `scripts/`，不预加载。这就是单 Agent 内部的渐进式披露。

### 应用层沙箱

Tool 不会绕过策略。每次 file / shell / HTTP 调用都过 `Sandbox.enforce(...)` 白名单。失败抛 `SandboxViolationException`，进审计日志。扩展阶段升级到容器 / microVM 隔离，接口不变。

## 核心概念

### Profile

`Profile` 是 Agent 的声明式描述——provider、model、可用 tool、schedule、notify 渠道。Profile 落在 `.oryxos/agents/<name>/AGENT.md`（frontmatter = profile，正文 = system prompt）。

### Session

`Session` 是跟一个 Agent 的一次持续对话。Session 持久化到 SQLite，重启可恢复。包含完整消息历史和 Profile 设定的 `MAX_ITERATIONS`。

### Memory

`MemoryService` 是三层门面：

- **Session 记忆**——短期，跟着 Session 走。
- **长期记忆**——`MEMORY.md` 文件，默认 Markdown。
- **可插拔后端**——`MarkdownMemoryStore`（默认）、`SqliteMemoryStore`、`Mem0MemoryStore`（语义）。

### Tool

三档接入，同一个 `OryxTool` 接口：

- **零代码**——`AGENT.md` + MCP server（Model 动态发现 tool）。
- **轻代码**——自实现 MCP server，不写 Java。
- **重代码**——`@OryxTool` 注解的 Java bean。

### Sandbox

`Sandbox.enforce(SandboxAction)`——每次 `ToolExecutor.execute(...)` 前必查。核心阶段唯一实现是 `WhitelistSandbox`（路径 / pattern 匹配）。升级路径：容器 → microVM，接口不变。

## 对比

| 维度         | **OryxOS**                            | OpenClaw            | Hermes Agent        | Dify / Coze        |
| ------------ | ------------------------------------- | ------------------- | ------------------- | ------------------ |
| 语言         | **Java**                              | Node.js             | Python              | Python / TS        |
| 目标用户     | **严监管企业**                        | 消费者 / 小团队     | 团队 / 小组织       | 业务用户           |
| 部署         | **单二进制，本地私有**                | 本地                | 本地                | 公有云 SaaS        |
| 审计         | **day-one 内置（库表落盘）**          | ❌                  | 部分                | ✅（SaaS）        |
| 生态契合     | **Java/Spring/Cloud-native**          | JS/TS               | Python 数据栈       | 跨平台             |
| Java AI 框架 | 基于 Spring AI Alibaba                | N/A                 | LangChain           | LangChain          |
| MCP 支持     | 客户端（核心）+ 服务端（扩展）        | ✅                  | ✅                  | ✅                |
| 产品形态     | **运行时内核 + 配置**                 | 运行时              | 运行时              | 可视化工作流       |

## 设计原则

**运行时内核是地基，不是产品。** 差异化治理层（多租户 / SSO / 完整审计 / Tool Policy / Web 仪表板）是终局，建在地基之上。别把两个阶段混为一谈。

**审计 day-one，不补账。** `tool_invocations` 和 `llm_calls` 从 US-1 开始就写，不是最后再补。合规问"上周二发生了什么"，你答 SQL，不答日志。

**一个目录 = 一个 Agent。** `AGENT.md` + 可选 `skills/` + 可选 `scripts/` + 可选 `REFERENCE.md`。Model 按需读文件。渐进式披露。

**一个引擎，三种触发源。** CLI（人推）+ REST（人推）+ `AgentScheduler`（钟推）都进 `AgentService.process(Session, String)`。ReAct 循环不在乎谁启动它。

## OryxOS 不是什么

- ❌ 不是 SaaS。你自己部署。
- ❌ 不是框架。是运行时。你用 YAML 定义 Agent，不用 Java。
- ❌ 不是多租户产品（暂时）。核心阶段单租户，多租户在扩展阶段。
- ❌ 不是常在线服务之间 HTTP/gRPC 的替代。
- ❌ 不是数据管道或事件日志。

## 下一步

| 目标                                     | 看到什么                                                |
| ---------------------------------------- | ------------------------------------------------------- |
| [给工程师](./for-engineer)               | 从源码构建，理解 9 个 Maven 模块                          |
| [给 Agent](./for-agent)                  | 不写 Java 也能定义 Agent（`AGENT.md` 手册）              |
| [快速开始](./quick-start)                | 10 分钟内本地跑通三个 Demo                               |
| [系统架构](./architecture)                | 运行时分层详解                                          |
| [功能特性](./features)                    | 5 大核心能力的详细参考                                   |
| [使用场景](./scenarios)                  | 6 个企业级场景                                          |
| [路线图](./roadmap)                       | 核心阶段 / 扩展阶段 / 社区阶段                            |