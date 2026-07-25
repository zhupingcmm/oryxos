# OryxOS — Claude Agent Context

> 这份文件是给 AI coding agent 看的"项目记忆"。打开 OryxOS 时先读这一份，再按需查 `docs/` 里的详细文档。

---

## 1. 项目一句话

**OryxOS** = 企业级 **Agent OS** 运行时内核，**Java/Spring Boot** 实现，装在企业自己的 K8s/服务器上，在上面跑多个业务 Agent（运维/客服/HR/销售等），共享一套 Provider 路由、ReAct 引擎、Memory、Tool、Sandbox、Web Service 能力。**数据完全留在企业内、不锁云生态**。

跟 Node.js 的 OpenClaw、Python 的 Hermes Agent 是同类不同定位——OryxOS 直接锚定严监管企业场景（银行/政府/电信/能源/医疗）。

---

## 2. 文档地图（遇到问题先查这里）

| 文件 | 回答的问题 | 何时查 |
|------|----------|-------|
| [docs/IndustryResearch.md](docs/IndustryResearch.md) | **Why** — 为什么 Java 生态缺这个 | 想了解定位、市场、跟 OpenClaw/Hermes 区别时 |
| [docs/DemandAnalysis.md](docs/DemandAnalysis.md) | **What** — 做什么、不做什么 | 想了解功能范围、验收标准、风险时 |
| [docs/TechnicalSolution.md](docs/TechnicalSolution.md) | **How** — 怎么实现 | 想了解架构、模块、关键决策时 |
| [docs/AiProgrammingGuide.md](docs/AiProgrammingGuide.md) | **How to build** — 怎么用 AI 编码落地 | 想了解 Spec-Kit 流程、5 个 user story 拆解时 |

四个文档是同一套论证链条的不同切面，**不重写**。任何 agent 改动前必须先查相关章节。

---

## 3. 交付分两段（核心 vs 扩展）

- **核心阶段**（当前）：Agent OS **运行时内核**——五大核心能力 + 工程地基，4 周 12 小时
- **扩展阶段**（后续）：真正的差异化治理层——多租户、SSO、完整审计、Tool Policy、Web 仪表板、集群高可用

**核心阶段是地基，企业级治理是终局。** 不要在核心阶段去碰扩展阶段的东西。

---

## 4. 技术栈

```
JDK 21 + Spring Boot 3.x + Spring AI Alibaba（只取一半）
+ 自实现 ReAct loop + SQLite（Spring Data JPA）+ Picocli
+ MCP Java SDK + SnakeYAML + Logback/SLF4J
+ Micrometer/Prometheus（扩展阶段）
```

**打包**：Maven 多模块 → fat JAR → `java -jar` 启动（扩展阶段接 GraalVM Native Image）。

---

## 5. 9 个 Maven 模块

| 模块 | 职责 |
|------|------|
| `oryxos-core` | 核心抽象：`OryxTool` 接口、`Session`、`Profile`、`ContextLoader`、`AgentLoader`（扫 `.oryxos/agents/`、`deriveProfile`）、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`AgentScheduler`（定时触发） |
| `oryxos-provider` | 能力一：`ProviderService`、Function Calling 适配、**显式** provider name → `ChatModel` 映射 |
| `oryxos-memory` | 能力三：`MemoryService` 三层门面、`LongTermMemoryStore` 接口（3 后端：`MarkdownMemoryStore`/`SqliteMemoryStore`/`Mem0MemoryStore`）、`MemoryTools` |
| `oryxos-tool` | 能力四（**三合一**）：内置 Tool（`FileTools`/`ShellTools`/`HttpTools`/`NotifyTools`）、`McpClientService`、`McpToolAdapter`、`ToolRegistry`、`Sandbox` 接口 + `WhitelistSandbox`、`NotifyChannelAdapter` + `WebhookNotifyAdapter` |
| `oryxos-channel-cli` | `CliChannel`、`oryxos chat` 命令实现 |
| `oryxos-web` | 能力五：`WebServer`、6 个 `ApiController`、`GlobalExceptionHandler`、OpenAPI |
| `oryxos-storage` | SQLite 持久化层（`SessionRepository`/`ToolInvocationRepository`/`LlmCallRepository`/`ScheduledTaskStore`） |
| `oryxos-cli` | Picocli 主入口 + 12 个子命令 + `ConfigLoader` |
| `oryxos-boot` | Spring Boot 启动模块（主类、自动配置、依赖聚合） |

**要点**：
- Tool 相关**全部归 `oryxos-tool` 一个模块**（不拆 builtin/skill/mcp）
- `AGENT.md` 加载归 `oryxos-core` 的 `ContextLoader`，**不是** Tool
- 任何 agent 想在核心阶段新增第 10 个模块 → 停下来讨论，这是结构性调整

---

## 6. 五大核心能力

| # | 能力 | 关键模块 | 核心 stage 做什么 |
|---|------|---------|----------------|
| 一 | 对接 LLM | `ProviderService` | Provider 抽象；显式 name → ChatModel 映射；至少跑通 DeepSeek + Kimi |
| 二 | ReAct 循环 | `ReActLoop` + `PromptBuilder` + `ToolExecutor` + `AgentService` | 自实现主循环；Profile 上下文；`MAX_ITERATIONS` 默认 10 |
| 三 | Memory 三层 | `MemoryService` 门面 + `LongTermMemory` + `MemoryTools` | 会话 + 长期两层；`MEMORY.md` 文件；`save_memory`/`recall_memory`；情景记忆放扩展 |
| 四 | Plugin Tool | `ToolRegistry` + 内置 9 Tool + MCP + Sandbox + Notify | 内置 9 Tool；三档接入（零代码/轻代码/重代码）；`Sandbox.enforce` 白名单 |
| 五 | Web Service | 6 个 `ApiController` | 核心 10 个 REST 端点；Spring MVC + virtual thread |

---

## 7. 七条非协商原则（constitution — 不可改）

来自 [docs/AiProgrammingGuide.md §3.2](docs/AiProgrammingGuide.md)，是 AI agent 必须遵守的硬约束：

1. **JDK 21 + Spring Boot 3.x** 单体应用；Maven 多模块（9 个）；单二进制部署
2. **五大核心能力优先**；企业级治理（多租户/SSO/审计/Tool Policy）放扩展阶段
3. **自实现 ReAct loop**；不依赖 Spring AI 的 Agent 抽象
4. **Spring AI 只用一半**——只用 Provider 抽象 + 协议转换 + `@Tool` schema 生成；**禁用自动 tool 执行**（否则 tool 被调两次）
5. **Plugin Tool 三档接入**；主推 SKILL.md + MCP 零代码方式
6. **SQLite + `MEMORY.md` 文件存储**；向量检索放扩展阶段；**`tool_invocations` 和 `llm_calls` 核心阶段就写入落库**（审计 day one）
7. **每个 user story 完成后有可演示 Demo**；跑通优先于完美

> `constitution.md` 写一次定下来，整个主体开发期间不改。AI agent **不允许自己修改 constitution**。

---

## 8. 三个最容易踩的坑（AI 必看）

| # | 坑 | 正确做法 |
|---|----|---------|
| 1 | **启用 Spring AI 的自动 tool 执行** | 必须禁用；只用协议转换和 schema 生成；tool 调度完全由 `ReActLoop` + `ToolExecutor` 控制。症状：tool 被调两次 |
| 2 | **Provider 用"扫描容器里所有 `ChatModel`"区分** | 必须维护 provider name 到 `ChatModel` 的**显式映射**；多 Provider 并存时 Bean 类型相同会有歧义 |
| 3 | **`tool_invocations` / `llm_calls` 只放日志** | 核心阶段就**写库**；可审计这个差异化能力的 day-one 地基要从 SQL 拉，不能从日志反解析 |

其他次常见坑：
- 把 Tool 又拆成多模块 → 应该合并为 `oryxos-tool`
- 把 `AGENT.md`/`AgentLoader` 当成 Tool → 归 `core` 的 `ContextLoader`
- 写了非 JDK 21 特性 → 强制 JDK 21
- Memory 简化成跟 Session 合并 → `MemoryService` 是三层统一门面

---

## 9. 关键设计决策

### 9.1 ReAct 循环自实现
- 核心循环约**数十行 Java**
- 每次迭代：组装 Prompt → 调 LLM → 解析响应 → [有 tool 调用] 执行 → 追加到 Session → 继续
- `MAX_ITERATIONS` 默认 10，Profile 可覆盖
- 消息累积：每次 LLM 响应和 Tool 结果都进 Session 对话历史（可查可审计）

### 9.2 `PromptBuilder` 组装顺序（四部分）
```
1. system prompt = AGENT.md 正文（这个 Agent 的指令）+ Bootstrap（AGENTS.md/SOUL.md/USER.md）
   + 当前日期时间（LLM 自己不知道今天几号，定时场景的"今天"全靠这一行）
2. Memory 注入（会话历史 + 长期记忆）
3. 对话历史（按 maxHistoryTurns 截断）
4. 当前 Profile 可用的 Tool 列表（Function Calling 格式）
```

### 9.3 `AgentService` 是三种触发源统一入口
- CLI（人推）+ Web Service（人推）+ `AgentScheduler`（钟推）都进 `process(Session, String)`
- `ReActLoop` 不感知消息从哪个入口来
- 用 `ProfileContext`（ThreadLocal）解决"OryxTool.execute 不带 Profile 但要知道当前 Agent"的问题

### 9.4 Sandbox = 接口先行
```java
Sandbox.enforce(SandboxAction action)
SandboxAction  = { type: ActionType, target: String }
ActionType     = FILE_READ | FILE_WRITE | SHELL_COMMAND | HTTP_REQUEST
```
- 核心阶段唯一实现：`WhitelistSandbox`（应用层 Path/Pattern 白名单）
- 校验失败抛 `SandboxViolationException`，走 `ToolExecutor` 既有审计路径
- **不使用** `SecurityManager`（JDK 17 起废弃，JDK 21 不可用）
- 升级路径：白名单 → 容器（namespace+cgroups+seccomp）→ microVM（Firecracker/Kata/gVisor），接口不变

### 9.5 Notify 出站推送（对称补"消息怎么出去"）
- 入站 Channel Adapter = "消息怎么进来"
- 出站 `NotifyChannelAdapter` = "消息怎么出去"
- 核心阶段唯一实现：`WebhookNotifyAdapter`（通用 webhook，覆盖企业微信/飞书/钉钉群机器人）
- `@Tool notify(content: String, channel: String = 默认渠道)`
- 发送前同样过 `Sandbox.enforce(HTTP_REQUEST, url)` 域名白名单

### 9.6 Memory 三层门面 + 三档可插拔后端
```
MemoryService（统一门面，对 ReAct 暴露）
  ├─ SessionManager（会话记忆，SQLite）
  └─ LongTermMemoryStore（接口，可插拔后端）
       ├─ MarkdownMemoryStore（默认，.oryxos/memory/MEMORY.md，分核心/归档两个分区）
       ├─ SqliteMemoryStore（memory_entries 表，结构化查询）
       └─ Mem0MemoryStore（自托管 Mem0，语义检索）
```
接口四条契约：① 不缓存；② 核心区永不被截断；③ 写核心还是写归档由 Agent 经 `scope` 显式指定；④ `recallByKeyword` 是关键词检索不做复杂化。

### 9.7 "一个目录 = 一个 Agent"
- `.oryxos/agents/<name>/AGENT.md`（frontmatter = profile，正文 = 任务指令）
- 可选 `skills/*.md`（子指令）、`scripts/`（脚本）、`REFERENCE.md`
- 加载走渐进式披露：`AGENT.md` 正文进 system prompt；子指令/脚本不预载，由模型经 `read_file`/`shell` 按需取
- 核心阶段手动丢目录；扩展阶段升级到 `POST /api/v1/agents` 纯 API

---

## 10. 推进顺序：5 个 User Story

按依赖关系排，不是按重要性：

```
US-1（对接 LLM）
  └─ US-2（ReAct 循环）
       ├─ US-3（Memory）∥
       └─ US-4（Plugin Tool）
            └─ US-5（Web Service）
```

| US | 核心能力 | 验收 Demo |
|----|---------|----------|
| US-1 | 能力一 | （跟 US-2 一起跑 Demo 一） |
| US-2 | 能力二 | Demo 一：每日天气 |
| US-3 | 能力三 | Demo 二：每日科技日报（跨对话记偏好） |
| US-4 | 能力四 | Demo 三：每日 GitHub 日报（零代码 PR digest） |
| US-5 | 能力五 | Demo 四/五：Web Service 同步调用 + 多端点联动 |

每个 US 完成后**必须跑 `/speckit.analyze`**（防漂移，不能省）。

---

## 11. 三个验收 Demo（每日自动运行）

| Demo | Agent 目录形态 | 跑通能力 |
|------|---------------|---------|
| **每日天气** | 光杆 `AGENT.md` | Provider + ReAct + 内置 HTTP Tool + NotifyTools + Sandbox + AgentScheduler |
| **每日科技日报** | `AGENT.md` + `skills/` 子指令 | Memory + MCP + read_file 按需取 + NotifyTools + AgentScheduler |
| **每日 GitHub 日报** | `AGENT.md` + `scripts/` 脚本 | Shell Tool + Sandbox 脚本信任边界 + Memory + NotifyTools + AgentScheduler |

三个 Demo 都是"钟推"（到点自动跑），但都要能同时支持"人推"手动补跑一次（`oryxos chat` 或 `POST /agents/{name}/invoke`），验证同一个 Agent 不管从哪个入口触发走的是同一条 `AgentService` 链路。

**脚本的信任边界（重要）**：带 `scripts/` 的 Agent 跑 Python 子进程自己发网络请求，**绕过 `http_get` 的域名白名单**。装一个带脚本的 Agent = 信任这个 Agent 的作者。容器/网络隔离放扩展阶段。

---

## 12. 工作区结构

```
.oryxos/
├── agents/              # 每个子目录 = 一个 Agent（AGENT.md + 可选 skills/ scripts/ REFERENCE.md）
├── memory/
│   └── MEMORY.md        # 长期记忆（默认 MarkdownMemoryStore）
├── mcp_servers.yaml     # MCP 配置
├── sessions/            # Session 数据
├── logs/                # 结构化日志
├── AGENTS.md            # Bootstrap：项目级 agent 行为说明
├── SOUL.md              # Bootstrap：默认 agent 人格
├── USER.md              # Bootstrap：用户偏好
└── oryxos.db            # SQLite
```

`AGENT.md` 不在 `.oryxos/` 根目录，而在 `agents/<name>/AGENT.md`。

---

## 13. 数据模型（SQLite 5 张表）

| 表 | 用途 | day-one 写入？ |
|----|------|--------------|
| `sessions` | Session 元数据 + JSON 对话历史 | ✅ |
| `tool_invocations` | 每次 Tool 调用记录（`success`/`error_message`/`duration_ms`） | ✅（审计地基） |
| `llm_calls` | 每次 LLM 调用记录（provider/model/tokens/duration） | ✅（成本透明地基） |
| `scheduled_tasks` | 定时任务登记（`task_id`/`profile_name`/`cron`/`enabled`/`last_run_at`） | ✅ |
| `task_executions` | 每次执行历史（`task_id`/`session_id`/`success`/`duration_ms`） | ✅ |

**工程风险提示**：SQLite 的 `ALTER TABLE` 能力有限，`hibernate.ddl-auto=update` 对表结构演进支持弱。表结构后续演进不要依赖自动迁移，需要手动维护建表脚本或引入 Flyway/Liquibase。

---

## 14. 命令行（12 个命令，Picocli）

```
启动状态    init / status / chat / serve / gateway
Profile    profile list / create / show / delete
查询       provider list / tool list / session list
```

`init` 和 `profile list` 不需要 Spring 上下文，直接走文件操作；`chat`/`serve`/`gateway` 启动 Spring。

---

## 15. REST API（核心 10 个端点）

```
会话管理   POST   /api/v1/sessions
         POST   /api/v1/sessions/{id}/messages
         GET    /api/v1/sessions/{id}
         DELETE /api/v1/sessions/{id}
Agent     POST   /api/v1/agents/{name}/invoke
查询     GET    /api/v1/profiles
         GET    /api/v1/memory
         GET    /api/v1/tools
系统     GET    /api/v1/health
         GET    /api/v1/info
```

**核心阶段不做**：认证、流式 SSE、WebSocket、RBAC、限流、Profile 的 create/update（只读）、Agent 目录上传、Scheduler 增删查改。

---

## 16. Profile YAML 字段

```yaml
name: string
description: string

identity:
  agent_name: string
  prompt: string                  # 人格/系统提示词（或引用 SOUL.md）

provider:
  name: string                    # deepseek/qwen/kimi 等（必须跟 application.yaml 里配的 name 一致）
  model: string
  temperature: float              # 可选

tools: [string]                   # 可用 Tool 名称列表
skills: [string]                  # 引用的 SKILL.md 文件列表
mcp_servers: [string]             # 引用的 MCP Server 列表

channels:
  - name: string
    config: {}

notify_channels:                  # NotifyTools 出站目标（Demo 需要）
  - type: string
    config: {}                    # 渠道特定配置，如 url

bootstrap: [string]               # Bootstrap 文件列表（AGENTS.md/SOUL.md/USER.md）

schedules:                        # AgentScheduler 触发规则（Demo 需要）
  - id: string
    cron: string                  # cron 表达式
    zone: string                  # 时区
    message: string               # 到点发给 Agent 的消息

settings:
  max_iterations: 10              # 默认 10
  max_history_turns: 20           # 默认 20
```

**敏感字段**用 `${ENV_VAR}` 占位，加载时从环境变量解析；不硬编码 API key。

---

## 17. 协作约定

### Spec-Kit 流程（主体开发）
- 不重写已有文档，把需求+技术方案直接喂给 Spec-Kit
- `constitution.md` 写一次定下来，**AI agent 不允许自己改**
- 每个 US 完成后跑 `/speckit.analyze`（防漂移，不能省）
- 喂文档时用最新版（9 模块不是 11 模块；Spring AI 只用一半；审计 day one）

### 手动提示词（增量阶段）
- Spec-Kit 适合大颗粒度 greenfield，不适合小 feature
- 社区贡献者直接用 Claude Code 在已有代码上改，改完跑测试没问题就提 PR

### 跨 task 上下文丢失
- AI agent 实施每个 task 时可能不知道前面做了什么 → 定期让它重读 `spec.md` + `plan.md` + 最近代码

### Git
- 每个 user story 完成时打 commit，方便回退到稳定状态
- 改 main 前先建分支（CLAUDE.md 里说明）

---

## 18. 不要做的事

- ❌ 不要启用 Spring AI 的自动 tool 执行（会调两次）
- ❌ 不要把 Tool 又拆成多模块（合并为 `oryxos-tool`）
- ❌ 不要把 `AGENT.md`/`AgentLoader` 当成 Tool（归 core 的 `ContextLoader`）
- ❌ 不要用容器类型扫描区分 Provider（用显式 name → ChatModel 映射）
- ❌ 不要把 `tool_invocations`/`llm_calls` 只放日志（核心阶段就写库）
- ❌ 不要把 Memory 简化成跟 Session 合并（`MemoryService` 是三层统一门面）
- ❌ 不要在核心阶段碰扩展阶段的东西（多租户/SSO/审计查询/Tool Policy/Web 仪表板/集群）
- ❌ 不要写非 JDK 21 特性
- ❌ 不要自己修改 `constitution.md`
- ❌ 不要跳过 `/speckit.analyze`
- ❌ 不要使用 `SecurityManager`（JDK 21 不可用）
- ❌ 不要在 Profile YAML 里硬编码 API key（用 `${ENV_VAR}` 占位）
- ❌ 不要假设 SQLite 的 `hibernate.ddl-auto=update` 能搞定所有表结构演进

### 补充坑（US-2 阶段发现，US-3+ 避免重复掉坑）

| 坑 | 正确做法 |
|---|---------|
| 4. **JDK 21 `javac` 在 Windows 上默认读 UTF-8 源文件为 GBK** —— `<encoding>UTF-8</encoding>` 在新 javac API 下被忽略，`native.encoding=GBK` 直接生效，结果：`非法字符: '#'` / `需要 class、interface、enum 或 record` 的报错其实跟 GBK 没关系，是解析问题。修法见 [pom.xml](pom.xml) 同时设置：① `<forceLegacyJavacApi>true</forceLegacyJavacApi>`（让 <encoding> 重新生效）；② surefire `<argLine>-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8</argLine>`（让测试 JVM 也走 UTF-8） |
| 5. **Javadoc 里写 `*/` 会被 javac 当成注释结束符** —— 一字面 `*/` 在 Javadoc 注释里立刻终止该注释块；之后的所有字符都被 javac 当 Java 代码解析。常见于 `.oryxos/agents/*/AGENT.md` 这种 file-pattern 描述。规避：用 `{@code * /AGENT.md}`（中间加空格）或 `&#42;/` |

---

## 19. 当前阶段的状态

- **核心阶段**：4 周 12 小时，5 个 US 按依赖顺序推进
- **代码状态**：尚未生成（仓库根目录当前只有 `docs/` 和 `LICENSE`）
- **下一步建议**（用户确认后执行）：
  1. 按 [docs/AiProgrammingGuide.md §3](docs/AiProgrammingGuide.md) 准备阶段生成 `.specify/memory/constitution.md` + `spec.md` + `plan.md`
  2. 按 9 个模块生成 Maven 多模块骨架
  3. 从 US-1 开始实现

---

## 20. 一句话提醒

**OryxOS 不是一个完整的企业级 Agent OS 产品——它是这个产品的运行时内核地基。** 企业级治理层（多租户/SSO/完整审计/Tool Policy）是终局，**核心阶段不做**。文档/对话里如果有人说"我们要做完整 Agent OS"，先确认他说的是核心阶段还是扩展阶段。

Anchor on the unchanging need: **严监管企业要一个自己能完全掌控的 Agent 底座**——这不是"Agent OS 这个概念"，是不会变的需求。