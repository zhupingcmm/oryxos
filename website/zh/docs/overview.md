---
title: 总览 —— 设计理念
description: 为什么有 OryxOS，它解决什么，怎么定位。
---

# 总览

## OryxOS 是什么

OryxOS 是基于 **Java + Spring Boot** 的企业级 **Agent OS** 运行时内核。装在企业自己的 K8s 集群或服务器上，承载多个业务 Agent（运维 / 客服 / HR / 销售 / 知识管理），共享一套核心能力：LLM Provider 路由、自实现 ReAct 推理循环、三层记忆、插件 Tool（含沙箱）、REST API。

定位 **严监管企业**（银行 / 政府 / 电信 / 能源 / 医疗）—— 它们：

- 现有 IT 主干是 Java（Nacos / Sentinel / SkyWalking / Arthas / Prometheus+Grafana）。
- 数据必须本地化。
- 每条 LLM 和 tool 调用必须可审计。
- Agent 层不能上公有云 SaaS。

## 两个核心问题

### 问题 1 —— Agent 跑不进现有技术栈

Agent OS 领域有两个成熟的玩家：**OpenClaw**（Node.js，消费者向）和 **Hermes Agent**（Python，团队向）。两者都验证了模型可行，但都不瞄准 Java 群体——而严监管企业的 IT 主体恰恰是 Java。

OryxOS 填补 Java 这个生态位。把成熟的 Agent OS 设计带进 Java/Spring 生态，那里周边基础设施已经齐备。

### 问题 2 —— Agent 既不能审计也不能私有

公有云 SaaS 把对话发到厂商域。即便有日志，你的 SIEM / 合规团队 / 数据驻留审计也碰不到这些日志。"我们有日志" 不够。

OryxOS 把 `tool_invocations` 和 `llm_calls` 写进**你自己的 SQLite 数据库**，从 day-one 开始。SQL 可查，可复制，能跟你的其他审计表 join。

## OryxOS 如何解决

### Java 原生运行时

- JDK 21 + Spring Boot 3.x。单 fat JAR。
- 接入现有 Nacos / Sentinel / SkyWalking / Prometheus 基础设施。
- Virtual threads 支撑高并发。

### 自实现 ReAct 循环

- Reason+Act 引擎在 `ReActLoop` 里，几十行 Java。
- **不用** Spring AI 的 Agent 抽象（自动执行 tool 会导致重复调用）。
- Spring AI 只用：Provider 抽象、协议转换、`@Tool` schema 生成。就这些。

### day-one 审计

```sql
-- 谁调了什么 tool 何时调？
SELECT created_at, profile_name, tool_name, success, duration_ms
FROM tool_invocations
WHERE created_at > datetime('now', '-1 day');

-- LLM 看到了什么，token 花了多少？
SELECT created_at, profile_name, provider, model, prompt_tokens, completion_tokens, total_tokens
FROM llm_calls
WHERE created_at > datetime('now', '-1 day');
```

这些表由 `ToolExecutor` 和 `ProviderService` 写入——没有"忘记打日志"的可能。

### 应用层沙箱

`Sandbox.enforce(SandboxAction)` 在每次 tool 执行前调用。核心阶段实现是 `WhitelistSandbox`（路径 / URL pattern 匹配）。违反抛 `SandboxViolationException`，由全局异常处理器和审计日志捕获。

扩展阶段升级到容器（namespace + cgroups + seccomp）和 microVM（Firecracker / Kata / gVisor）。**接口不变**——升级是 bean 替换。

### 零代码定义 Agent

```
.oryxos/agents/daily-weather/
├── AGENT.md            # frontmatter = profile，正文 = 系统提示词
├── skills/             # 可选子指令（按需读）
└── scripts/            # 可选脚本（通过 shell tool 跑）
```

业务用户写文件定义 Agent。Model 通过内置 tool 按需取 `skills/` 和 `scripts/`。单 Agent 内部的渐进式披露。

## 对比

|      | **OryxOS**    | OpenClaw      | Hermes Agent  | Dify / Coze  |
| ---- | ------------- | ------------- | ------------- | ------------ |
| 语言 | **Java**      | Node.js       | Python        | Python / TS  |
| 目标 | **严监管企业** | 消费者 / 小团队 | 团队 / 小组织  | 业务用户     |
| 部署 | **单二进制本地** | 本地          | 本地          | 公有云 SaaS  |
| 审计 | **day-one 内置（库表落盘）** | ❌      | 部分          | ✅（SaaS）   |
| 生态 | **Java/Spring/Cloud-native** | JS/TS | Python 数据栈 | 跨平台       |
| Java AI 框架 | 基于 Spring AI Alibaba | N/A | LangChain | LangChain     |
| MCP   | 客户端（核心）+ 服务端（扩展） | ✅ | ✅ | ✅            |
| 形态 | **运行时内核 + 配置** | 运行时 | 运行时 | 可视化工作流 |

**关键定位**：*框架给你代码，编排器给你流程，OryxOS 给你承载 Agent 的运行时——可审计、私有、Java 原生。*

## 设计原则

1. **运行时内核不是产品。** 核心阶段交付运行时内核——地基，不是完整企业产品。差异化治理层（多租户 / SSO / 完整审计 / Tool Policy / Web 仪表板）是终局，建在上面。
2. **审计 day-one，不补账。** `tool_invocations` 和 `llm_calls` 从 US-1 开始就写，不是最后补。
3. **一个目录 = 一个 Agent。** `AGENT.md` + 可选 `skills/` + 可选 `scripts/` + 可选 `REFERENCE.md`。渐进式披露。
4. **一个引擎，三种触发源。** CLI / REST / Scheduler 都进 `AgentService.process(Session, String)`。
5. **只用 JDK 21 特性。** 不用反射 hack 兼容老 JDK。
6. **Spring AI 只用一半。** Provider + 协议转换 + `@Tool` schema。不用 Agent 抽象，不用自动 tool 执行。
7. **Tool 相关代码在一个模块。** `oryxos-tool` 不拆。

## 核心阶段包含

| 能力             | 模块               | Demo                                          |
| ---------------- | ------------------ | --------------------------------------------- |
| LLM Provider     | `oryxos-provider`  | （三个 Demo 共享）                             |
| ReAct 循环       | `oryxos-core`      | Demo 1（每日天气）                              |
| Memory           | `oryxos-memory`    | Demo 2（每日科技日报）                          |
| Tool + 沙箱      | `oryxos-tool`      | Demo 1（HTTP）、Demo 2（MCP）、Demo 3（Shell） |
| REST API         | `oryxos-web`       | 所有 Demo（通过 REST 手动触发）                |
| Scheduler        | `oryxos-core`      | 所有 Demo（cron 触发）                          |

## 核心阶段不包含

这些是有意留白的，都在扩展阶段路线图里——别提前动：

- ❌ 认证 / SSO / RBAC
- ❌ 多租户
- ❌ 通过 API 创建 / 更新 Profile
- ❌ 通过 API 创建 / 更新 Agent
- ❌ SSE 流式响应
- ❌ 向量记忆（pgvector / LanceDB Java / JVector）
- ❌ 自适应路由（fallback / hedge racing / circuit breaker）
- ❌ 集群 HA（Nacos / ETCD）
- ❌ Web 仪表板
- ❌ Tool Policy（profile 级 allow/deny）

## 下一步

| 目标                                       | 看到什么                                          |
| ------------------------------------------ | ------------------------------------------------- |
| [系统架构](./architecture)                 | 分层详解                                          |
| [功能特性](./features)                     | 5 大核心能力的详细参考                            |
| [使用场景](./scenarios)                    | 6 个企业级场景                                    |
| [路线图](./roadmap)                        | 核心 → 扩展 → 社区三阶段                          |
| [七条原则](./constitution)                 | 七条不可改的宪法原则                              |