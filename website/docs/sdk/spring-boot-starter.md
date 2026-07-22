---
title: Spring Boot Starter
description: Drop-in Spring Boot auto-configuration for OryxOS.
---

# Spring Boot Starter

The OryxOS Spring Boot Starter auto-configures the Agent OS runtime when added to any Spring Boot 3.x application. You get the full 5-capability engine without writing bootstrap code.

---

## Add the starter

```xml
<dependency>
    <groupId>io.oryxos</groupId>
    <artifactId>oryxos-boot</artifactId>
    <version>1.0.0</version>
</dependency>
```

That's it. Spring Boot's auto-configuration will:

1. Start the CLI Channel (`oryxos chat` works out of the box).
2. Start the REST API on `:8080` (override with `server.port`).
3. Start `AgentScheduler` (cron jobs defined in `AGENT.md`).
4. Load `application.yml` under the `oryxos:` prefix.
5. Scan `.oryxos/agents/` for `AGENT.md` files.

## Configure providers

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

  profiles-dir: ./agents          # default: .oryxos/agents
  mcp-config: ./mcp_servers.yaml  # default: .oryxos/mcp_servers.yaml
  sqlite-path: ./oryxos.db        # default: .oryxos/oryxos.db

server:
  port: 8080
```

## Customize

### Disable REST API

```yaml
oryxos:
  web:
    enabled: false
```

### Disable scheduler

```yaml
oryxos:
  scheduler:
    enabled: false
```

### Change profiles directory

```yaml
oryxos:
  profiles-dir: /etc/oryxos/agents
```

The directory is scanned at startup. Subdirectories with `AGENT.md` become Profiles.

### Configure sandbox

```yaml
oryxos:
  sandbox:
    type: whitelist        # core stage: only 'whitelist'
    file-read-whitelist:
      - ".oryxos/**"
      - "/tmp/oryxos/**"
    shell-command-whitelist:
      - "python *.py"
      - "git *"
    http-request-whitelist:
      - "*.example.com"
      - "api.deepseek.com"
```

Violations throw `SandboxViolationException`, captured by the global exception handler.

## Run

```bash
mvn spring-boot:run
# or
java -jar target/your-app-1.0.0.jar
```

You should see:

```
[oryxos] started gateway
[oryxos] CLI channel:    ready
[oryxos] REST API:       http://localhost:8080
[oryxos] AgentScheduler: 3 schedules registered
[oryxos] Profiles:       daily-weather, daily-tech-digest, daily-github
```

## What's auto-configured

| Component                  | Class                              |
| -------------------------- | ---------------------------------- |
| AgentService               | `io.oryxos.core.service.AgentService` |
| ReActLoop                  | `io.oryxos.core.loop.ReActLoop`    |
| PromptBuilder              | `io.oryxos.core.prompt.PromptBuilder` |
| ToolExecutor               | `io.oryxos.tool.exec.ToolExecutor` |
| ProviderService            | `io.oryxos.provider.ProviderService` |
| MemoryService              | `io.oryxos.memory.MemoryService`   |
| ToolRegistry               | `io.oryxos.tool.registry.ToolRegistry` |
| Sandbox (WhitelistSandbox) | `io.oryxos.tool.sandbox.WhitelistSandbox` |
| AgentScheduler             | `io.oryxos.core.scheduler.AgentScheduler` |
| REST Controllers           | `io.oryxos.web.*`                  |

You can override any of these by declaring your own `@Bean` of the same type. Spring Boot's `@ConditionalOnMissingBean` will defer to yours.

## Health & info endpoints

The starter auto-configures Spring Boot Actuator if it's on the classpath:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

Standard Actuator endpoints are available at `/actuator/*`, plus OryxOS-specific ones:

- `GET /actuator/oryxos/profiles`
- `GET /actuator/oryxos/tools`
- `GET /actuator/oryxos/sessions/count`

---

## Where to go next

| Destination                                                  | What you'll find                                       |
| ------------------------------------------------------------ | ------------------------------------------------------ |
| [Java SDK](./java)                                            | Programmatic API for custom Spring Boot apps           |
| [CLI](./cli)                                                 | The 12 sub-commands in detail                          |
| [For Engineers](../for-engineer)                             | Build, deploy, extend                                  |