---
title: 给工程师 —— OryxOS 构建与集成指南
description: 如何从源码构建 OryxOS，集成到你的技术栈，参与贡献。
---

# 给工程师

你在用 Java 构建企业级多 Agent 系统。本指南面向你。

> 默认你已经读过 [OryxOS 是什么](./what)。本页聚焦构建、集成、贡献。

## 从源码构建 OryxOS

### 前置条件

- **JDK 21+**（构建锁死 JDK 21，旧版本不支持）
- **Maven 3.9+**
- **Git**

### 克隆与构建

```bash
git clone https://github.com/oryxos/oryxos.git
cd oryxos

# 构建可执行 fat JAR
mvn -pl oryxos-boot -am clean package -DskipTests

# 输出: oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar (~66 MB)
```

fat JAR 自包含。`java -jar` 直接跑：

```bash
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar gateway
```

## 9 个 Maven 模块

```
oryxos/
├── oryxos-core/         # 核心抽象：OryxTool, Session, Profile,
│                        # ContextLoader, AgentLoader, ReActLoop, PromptBuilder,
│                        # ToolExecutor, AgentService, AgentScheduler
├── oryxos-provider/     # 能力一：ProviderService + ChatModel 映射
├── oryxos-memory/       # 能力三：MemoryService 门面 +
│                        # MarkdownMemoryStore / SqliteMemoryStore / Mem0MemoryStore
├── oryxos-tool/         # 能力四（三合一）：内置 9 Tool，
│                        # MCP 客户端, ToolRegistry, Sandbox, NotifyChannelAdapter
├── oryxos-channel-cli/  # CLI Channel adapter
├── oryxos-web/          # 能力五：6 个 ApiController，10 个端点
├── oryxos-storage/      # SQLite 持久化层（JPA repositories）
├── oryxos-cli/          # Picocli 主入口 + 12 个子命令 + ConfigLoader
└── oryxos-boot/         # Spring Boot 启动模块
```

**依赖规则：**

- `core` 是叶子。不依赖任何 OryxOS 模块。
- `provider` / `memory` / `tool` / `channel-cli` / `storage` 依赖 `core`。
- `web` 依赖 `core` + `storage`。
- `cli` 依赖 `core` + `channel-cli` + `web`。
- `boot` 聚合 `cli` + `provider` + `memory` + `tool` + Spring Boot starter。

**`tool` 不要拆模块。** 七条不可改宪法原则之一。

## 配置 Provider

Provider 在 `application.yml` 里是显式 `name → ChatModel` 映射。不要按容器类型扫描——多个 Provider 共享同一个 `ChatModel` 类型时类型扫描会撞车。

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
```

在 `AGENT.md` frontmatter 里按名引用：

```markdown
---
name: daily-weather
provider:
  name: deepseek
  model: deepseek-chat
---
```

`ProviderService.register("deepseek", deepseekChatModelBean)` 在启动时自动调用。`ProviderService` 暴露 `ChatModel get(String name)`，ReAct 循环按 profile 的 `provider.name` 查找。

## 定义 Agent

Agent 在 `AGENT.md` 里定义，不用 Java：

```markdown
---
name: daily-weather
description: 推送每日天气和穿搭建议到群里
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - http_get
  - notify
notify_channels:
  - type: webhook
    config:
      url: ${WEATHER_NOTIFY_URL}
schedules:
  - id: morning-weather
    cron: "0 0 8 * * *"
    zone: Asia/Shanghai
    message: "查一下今天上海的天气，生成穿搭建议并推送"
settings:
  max_iterations: 10
---

# Daily Weather Agent

你是一个每日天气助手。每天早上：

1. 通过 `http_get` 查上海今天天气。
2. 生成简洁的穿搭建议。
3. 通过 `notify` 推送到群。
```

`AgentLoader` 启动时扫 `.oryxos/agents/`，从每份 `AGENT.md` 的 frontmatter 派生 `Profile` 并注册。

## 三种触发方式

```bash
# 1. CLI —— 人推
oryxos chat --profile daily-weather

# 2. REST —— 人推
curl -X POST http://localhost:8080/api/v1/agents/daily-weather/invoke \
  -H "Content-Type: application/json" \
  -d '{"message":"今天上海天气怎么样？"}'

# 3. Cron —— 钟推
# (在 AGENT.md frontmatter 的 schedules: 里配置)
```

三条路径都进 `AgentService.process(Session, String)`。ReAct 循环跟触发源无关。

## 通过 REST 集成

核心阶段暴露 10 个生产端点（无认证、无限流——扩展阶段才有）：

```bash
# Session
POST   /api/v1/sessions
POST   /api/v1/sessions/{id}/messages
GET    /api/v1/sessions/{id}
DELETE /api/v1/sessions/{id}

# Agent
POST   /api/v1/agents/{name}/invoke

# 查询
GET    /api/v1/profiles
GET    /api/v1/memory
GET    /api/v1/tools

# 系统
GET    /api/v1/health
GET    /api/v1/info
```

OpenAPI 在 `/v3/api-docs`（springdoc-openapi）。

## 扩展 OryxOS

### 加一个内置 Tool

实现 `OryxTool`，加 `@Component` 注解，自动注册到 `ToolRegistry`：

```java
@Component
public class MyTool implements OryxTool {
    @Override public String name() { return "my_tool"; }
    @Override public ToolDefinition definition() { /* ... */ }

    @Override
    public ToolResult execute(ToolCall call, ProfileContext ctx) {
        sandbox.enforce(ActionType.HTTP_REQUEST, call.arg("url"));
        // ... 你的逻辑 ...
        return ToolResult.ok(result);
    }
}
```

Tool 自动发现，列在 `GET /api/v1/tools` 里。

### 加一个 Provider

实现 `ProviderInitializer`：

```java
@Component
public class ZhipuProviderInitializer implements ProviderInitializer {
    @Override public String name() { return "zhipu"; }
    @Override public ChatModel create(ProviderConfig cfg) {
        return ZhipuChatModel.builder()
            .apiKey(cfg.apiKey())
            .model(cfg.model())
            .build();
    }
}
```

把 bean 加进 `oryxos-provider`，在 `application.yml` 的 `oryxos.providers.zhipu` 里声明配置，AGENT.md 里引用即可。

### 加一个 Notify 渠道

实现 `NotifyChannelAdapter`：

```java
@Component
public class FeishuNotifyAdapter implements NotifyChannelAdapter {
    @Override public String type() { return "feishu"; }
    @Override public void send(String content, NotifyConfig cfg) {
        // POST 到飞书 webhook，按飞书消息 schema 封装
    }
}
```

在 `AGENT.md` 里 `notify_channels[].type = "feishu"` 引用。

## 常见模式

### 渐进式披露（一个目录 = 一个 Agent）

```
.oryxos/agents/daily-tech-digest/
├── AGENT.md            # system prompt + profile
├── skills/
│   ├── digest-format.md   # 格式化规则 —— 按需读
│   └── source-list.md     # 信源列表 —— 按需读
└── REFERENCE.md        # 词汇表 / 风格指南 —— 按需读
```

Model 真正要写日报时才 fetch `skills/digest-format.md`。系统 prompt 小，成本低。

### 定时触发 + 手动覆盖

```yaml
schedules:
  - id: morning-weather
    cron: "0 0 8 * * *"
    zone: Asia/Shanghai
    message: "查天气，生成建议"
```

Agent 08:00 自动跑。你也可以手动触发：

```bash
oryxos chat --profile daily-weather
curl -X POST http://localhost:8080/api/v1/agents/daily-weather/invoke \
  -d '{"message":"查天气"}'
```

两条路径跑的是同一条 `AgentService` 链路。

### 沙箱强制

每次 tool 调用都过 `Sandbox.enforce(...)`。核心阶段是 `WhitelistSandbox`：

```java
sandbox.enforce(ActionType.FILE_READ, "/path/to/file");
sandbox.enforce(ActionType.SHELL_COMMAND, "python script.py");
sandbox.enforce(ActionType.HTTP_REQUEST, "https://api.example.com/data");
```

违反抛 `SandboxViolationException`，全局异常处理器翻译成 HTTP 403 或 CLI exit code 2，审计日志记录违规。

## 核心阶段没有的东西

这些是核心阶段有意留白的，都在扩展阶段路线图里——别提前动：

- ❌ 认证 / SSO / RBAC
- ❌ 多租户
- ❌ 通过 API 创建 / 更新 Profile（只读；Profile 是文件）
- ❌ 通过 API 创建 / 更新 Agent（Agent 是文件）
- ❌ SSE 流式响应
- ❌ 向量记忆（pgvector / LanceDB Java / JVector）
- ❌ 自适应路由（fallback / hedge racing / circuit breaker）
- ❌ 集群 HA（Nacos / ETCD）

## 贡献

看 [路线图](./roadmap) 找在做的方向。Issue 提 bug 和 feature 请求。PR 欢迎——fork、对照当前 user story 实现、加测试、跑 `mvn verify`。