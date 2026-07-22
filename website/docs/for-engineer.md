---
title: For Engineers — OryxOS Build & Integration Guide
description: How to build OryxOS from source, integrate it into your stack, and contribute.
---

# For Engineers

You're building an enterprise multi-agent system in Java. This is your integration guide.

> This page assumes you've read [What is OryxOS](./what). It focuses on building, integrating, and contributing.

## Build OryxOS from source

### Prerequisites

- **JDK 21+** (the build is locked to JDK 21; older JDKs are not supported)
- **Maven 3.9+**
- **Git**

### Clone and build

```bash
git clone https://github.com/oryxos/oryxos.git
cd oryxos

# Build the executable fat JAR
mvn -pl oryxos-boot -am clean package -DskipTests

# Output: oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar (~66 MB)
```

The fat JAR is self-contained. Run it with `java -jar`:

```bash
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar gateway
```

## The 9 Maven modules

```
oryxos/
├── oryxos-core/         # Core abstractions: OryxTool, Session, Profile,
│                        # ContextLoader, AgentLoader, ReActLoop, PromptBuilder,
│                        # ToolExecutor, AgentService, AgentScheduler
├── oryxos-provider/     # Capability 1: ProviderService + ChatModel mapping
├── oryxos-memory/       # Capability 3: MemoryService facade +
│                        # MarkdownMemoryStore / SqliteMemoryStore / Mem0MemoryStore
├── oryxos-tool/         # Capability 4 (all-in-one): built-in 9 tools,
│                        # MCP client, ToolRegistry, Sandbox, NotifyChannelAdapter
├── oryxos-channel-cli/  # CLI Channel adapter
├── oryxos-web/          # Capability 5: 6 ApiControllers, 10 endpoints
├── oryxos-storage/      # SQLite persistence layer (JPA repositories)
├── oryxos-cli/          # Picocli entry + 12 sub-commands + ConfigLoader
└── oryxos-boot/         # Spring Boot bootstrap module
```

**Dependency rules:**

- `core` is the leaf. No OryxOS dependencies.
- `provider`, `memory`, `tool`, `channel-cli`, `storage` depend on `core`.
- `web` depends on `core` + `storage`.
- `cli` depends on `core` + `channel-cli` + `web`.
- `boot` aggregates `cli` + `provider` + `memory` + `tool` + Spring Boot starter.

**Do not split `tool` into multiple modules.** This is one of the seven non-negotiable constitution principles.

## Configure a Provider

Providers are explicit name → `ChatModel` mappings in `application.yml`. Do not scan the container by type — multiple providers share the same `ChatModel` interface, and type scanning creates ambiguity.

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

Reference a provider by name in `AGENT.md` frontmatter:

```markdown
---
name: daily-weather
provider:
  name: deepseek
  model: deepseek-chat
---
```

`ProviderService.register("deepseek", deepseekChatModelBean)` is called at startup from the auto-configuration. The `ProviderService` exposes `ChatModel get(String name)` and the ReAct loop calls it with the profile's `provider.name`.

## Define an Agent

Agents are defined in `AGENT.md` — no Java required:

```markdown
---
name: daily-weather
description: Push daily weather and outfit advice to the team
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

You are a daily weather assistant. Each morning:

1. Fetch today's weather for Shanghai via `http_get`.
2. Generate concise outfit advice.
3. Push the advice to the team channel via `notify`.
```

`AgentLoader` scans `.oryxos/agents/` at startup, derives a `Profile` from each `AGENT.md`, and registers it.

## Trigger an Agent three ways

```bash
# 1. CLI — human push
oryxos chat --profile daily-weather

# 2. REST — human push
curl -X POST http://localhost:8080/api/v1/agents/daily-weather/invoke \
  -H "Content-Type: application/json" \
  -d '{"message":"今天上海天气怎么样？"}'

# 3. Cron — clock push
# (configured in AGENT.md frontmatter under schedules:)
```

All three paths converge on `AgentService.process(Session, String)`. The ReAct loop is identical regardless of trigger source.

## Integrate via REST

The core stage exposes 10 production endpoints (no auth, no rate limit — those are extension stage):

```bash
# Sessions
POST   /api/v1/sessions
POST   /api/v1/sessions/{id}/messages
GET    /api/v1/sessions/{id}
DELETE /api/v1/sessions/{id}

# Agent
POST   /api/v1/agents/{name}/invoke

# Discovery
GET    /api/v1/profiles
GET    /api/v1/memory
GET    /api/v1/tools

# System
GET    /api/v1/health
GET    /api/v1/info
```

OpenAPI is exposed at `/v3/api-docs` via springdoc-openapi.

## Extending OryxOS

### Add a new built-in Tool

Implement `OryxTool`, annotate with `@Component`, register in `ToolRegistry`:

```java
@Component
public class MyTool implements OryxTool {
    @Override public String name() { return "my_tool"; }
    @Override public ToolDefinition definition() { /* ... */ }

    @Override
    public ToolResult execute(ToolCall call, ProfileContext ctx) {
        sandbox.enforce(ActionType.HTTP_REQUEST, call.arg("url"));
        // ... your logic ...
        return ToolResult.ok(result);
    }
}
```

The tool is auto-discovered and listed in `GET /api/v1/tools`.

### Add a Provider

Implement `ProviderInitializer`:

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

Add the bean to `oryxos-provider`, declare config under `oryxos.providers.zhipu`, and reference in `AGENT.md`.

### Add a Notify Channel

Implement `NotifyChannelAdapter`:

```java
@Component
public class FeishuNotifyAdapter implements NotifyChannelAdapter {
    @Override public String type() { return "feishu"; }
    @Override public void send(String content, NotifyConfig cfg) {
        // POST to webhook URL with Feishu message schema
    }
}
```

Register via `notify_channels[].type = "feishu"` in `AGENT.md`.

## Common patterns

### Progressive disclosure (one agent = one directory)

```
.oryxos/agents/daily-tech-digest/
├── AGENT.md            # system prompt + profile
├── skills/
│   ├── digest-format.md   # formatting rules — read on demand
│   └── source-list.md     # news source list — read on demand
└── REFERENCE.md        # glossary / style guide — read on demand
```

The model fetches `skills/digest-format.md` only when it's actually writing the digest. This keeps the system prompt small and the cost low.

### Scheduled agent + manual override

```yaml
schedules:
  - id: morning-weather
    cron: "0 0 8 * * *"
    zone: Asia/Shanghai
    message: "查天气，生成建议"
```

The agent fires automatically at 08:00. You can also fire it manually:

```bash
oryxos chat --profile daily-weather
curl -X POST http://localhost:8080/api/v1/agents/daily-weather/invoke \
  -d '{"message":"查天气"}'
```

The same `AgentService` chain runs in both cases.

### Sandbox enforcement

Every tool call goes through `Sandbox.enforce(...)`. The core stage implementation is `WhitelistSandbox`:

```java
sandbox.enforce(ActionType.FILE_READ, "/path/to/file");
sandbox.enforce(ActionType.SHELL_COMMAND, "python script.py");
sandbox.enforce(ActionType.HTTP_REQUEST, "https://api.example.com/data");
```

Violations throw `SandboxViolationException`, which the global exception handler translates to HTTP 403 or CLI exit code 2, and the audit log captures the violation.

## What's not in the core stage

These are intentional gaps for the core stage. They are **planned for the extension stage** — do not start implementing them yet:

- ❌ Authentication / SSO / RBAC
- ❌ Multi-tenancy
- ❌ Profile create / update via API (read-only at runtime; Profiles are file-based)
- ❌ Agent create / update via API (Agents are file-based)
- ❌ Streaming SSE responses
- ❌ Vector memory (LanceDB Java, pgvector, JVector)
- ❌ Adaptive routing (fallback, hedge racing, circuit breaker)
- ❌ Cluster HA (Nacos / ETCD)

## Contributing

See the [Roadmap](./roadmap) for active work. Open issues for bug reports, feature requests, and design proposals. PRs are welcome — fork, implement against an active user story, add tests, run `mvn verify`.