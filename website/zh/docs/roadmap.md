---
title: 路线图
description: 核心阶段 → 扩展阶段 → 社区阶段。
---

# 路线图

OryxOS 交付分阶段。**核心阶段是运行时内核——地基，不是完整企业产品。** 差异化治理层是终局，建在地基之上。

---

## 阶段 1 —— 核心阶段（进行中）

**目标**：可运行的 Agent OS 运行时内核，5 大核心能力齐备，3 个端到端 Demo 每日跑。

**时间**：4 周 × 3 小时。

| 周  | 聚焦                       | 可演示结果                                       |
| --- | -------------------------- | ------------------------------------------------ |
| 1   | LLM Provider + ReAct 循环  | `oryxos chat` 多轮 + Agent 调 HTTP tool          |
| 2   | Memory + Tool 系统         | Agent 记偏好；调本地文件 + 外部 MCP               |
| 3   | Web Service                | 外部系统通过 10 个 REST 端点调 OryxOS             |
| 4   | 多 Agent + 工程化          | 多 Agent 共存；Session 重启可恢复；定时任务        |

### 5 个 user story

| US   | 能力         | Demo                                 |
| ---- | ------------ | ------------------------------------ |
| US-1 | LLM Provider | （跟 US-2 一起）                     |
| US-2 | ReAct 循环   | Demo 1（每日天气）                   |
| US-3 | Memory       | Demo 2（每日科技日报）               |
| US-4 | Tool + 沙箱  | Demo 3（每日 GitHub 日报）           |
| US-5 | REST API     | Demo 4/5（同步 + 多端点联动）        |

每个 user story 完成后跑 `/speckit.analyze`——防漂移不能省。

### 3 个 Demo

- **Demo 1 —— 每日天气**：HTTP + Notify + Scheduler。
- **Demo 2 —— 每日科技日报**：Memory + MCP + read_file。
- **Demo 3 —— 每日 GitHub 日报**：Shell + 脚本沙箱 + Memory。

三个都是**钟推**，也支持**人推**（CLI 或 REST）——证明三条触发路径共用同一条 `AgentService` 链路。

---

## 阶段 2 —— 扩展阶段

生产级能力建在核心内核之上。**核心阶段是地基，不是产品。** 这一阶段构差异化治理层。

### 认证与多租户

- SAML / OIDC SSO
- 三级租户模型（Org → Workspace → Project）
- RBAC 细到 Agent / Tool / Skill 粒度
- 每租户限流和配额

### 完整审计与追溯

- 审计查询 REST API
- Trace ID 在 LLM 和 tool 调用间传递
- SIEM 导出（Splunk / ELK / OpenTelemetry）
- 可配保留策略 + 冷存储归档

### Web 仪表板

- Profile 管理 UI（CRUD `AGENT.md`）
- Session 浏览器（搜索 / 回放 / 导出）
- 审计查询 UI（按日期 / profile / tool / success 过滤）
- 实时监控（calls/minute、tokens/minute、错误率）

### Tool Policy

- Profile 级 allow / deny 规则
- 时段限制
- 每 tool 参数校验（正则 / schema）
- 配额强制

### 沙箱升级

- 容器隔离：namespace + cgroups + seccomp
- MicroVM 隔离：Firecracker / Kata Containers / gVisor
- **`Sandbox` 接口不变** —— bean 替换

### 向量记忆

- 可插拔向量后端：LanceDB Java / pgvector / JVector（待定）
- 语义搜索替换关键词版 `recallByKeyword`
- 仍尊重 CORE / ARCHIVE scope

### 自适应路由

- Fallback（provider A 超时切 provider B）
- Hedge racing（并发调，取最快响应）
- 每个 provider 单独 circuit breaker
- 成本感知路由（简单问题用便宜 provider）

### 集群 HA

- Nacos / ETCD 多节点部署
- `AgentScheduler` leader 选举
- Session 复制（或 sticky 路由）
- 滚动升级零停机

---

## 阶段 3 —— 社区阶段

开放式、社区驱动。下面都是非承诺的——按贡献者兴趣生长。

### IM 渠道

- 企业微信、飞书、钉钉、Slack
- 双向（入站命令 + 出站通知）

### Skills 市场

- 兼容 [agentskills.io](https://agentskills.io)
- 社区贡献的 `AGENT.md` + `skills/` 索引

### 多语言 SDK

- Python SDK（社区主导）
- TypeScript SDK（社区主导）
- Go SDK（社区主导）

### 可视化 Profile 编辑器

- `AGENT.md` WYSIWYG 编辑器
- 渲染后 system prompt 实时预览
- Tool 面板拖拽选择

### Kubernetes Operator

- 声明式 `OryxOSCluster` CRD
- 生产部署 Helm chart
- 按队列深度自动扩缩

### 移动管理控制台

- iOS / Android 应用移动监控
- 严重错误推送通知

### 多区域部署

- 跨区域 Session 复制
- Agent 调用的地理路由

---

## 怎么贡献

从任何阶段挑一个，开 issue、提设计、推 PR：

- 🐛 Bug 报告：开 issue 附复现步骤
- 💡 Feature 请求：先在 issue 里讨论再开 PR
- 🔧 Pull Request：fork、对照当前 user story 实现、加测试、跑 `mvn verify`
- 📖 文档：错别字、说明、例子（在 `docs/` 下）
- 🧩 插件：新 MCP server、新 Provider adapter、新 Tool

---

## 下一步

| 目标                                       | 看到什么                                          |
| ------------------------------------------ | ------------------------------------------------- |
| [快速开始](./quick-start)                  | 本地跑通三个 Demo                                  |
| [系统架构](./architecture)                 | 分层详解                                          |
| [七条原则](./constitution)                 | 七条不可改宪法原则                                |
| [GitHub Discussions](https://github.com/oryxos/oryxos/discussions) | 讨论路线图 |