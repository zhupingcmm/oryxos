---
title: Spring Boot Starter
description: 拖入即用的 OryxOS Spring Boot 自动装配。
---

# Spring Boot Starter

OryxOS Spring Boot Starter 加到任何 Spring Boot 3.x 应用里，自动装配 Agent OS 运行时。5 大能力全齐，不用写启动代码。

---

## 加 starter

```xml
<dependency>
    <groupId>io.oryxos</groupId>
    <artifactId>oryxos-boot</artifactId>
    <version>1.0.0</version>
</dependency>
```

就这样。Spring Boot 自动装配会：

1. 启动 CLI Channel（`oryxos chat` 直接可用）。
2. :8080 启动 REST API（用 `server.port` 覆盖）。
3. 启动 `AgentScheduler`（`AGENT.md` 里的 cron）。
4. 加载 `application.yml` 的 `oryxos:` 前缀。
5. 扫 `.oryxos/agents/` 找 `AGENT.md`。

## 配置 Provider

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

  profiles-dir: ./agents          # 默认: .oryxos/agents
  mcp-config: ./mcp_servers.yaml  # 默认: .oryxos/mcp_servers.yaml
  sqlite-path: ./oryxos.db        # 默认: .oryxos/oryxos.db

server:
  port: 8080
```

## 定制

### 关 REST API

```yaml
oryxos:
  web:
    enabled: false
```

### 关 Scheduler

```yaml
oryxos:
  scheduler:
    enabled: false
```

### 改 profiles 目录

```yaml
oryxos:
  profiles-dir: /etc/oryxos/agents
```

启动时扫这个目录。带 `AGENT.md` 的子目录成为 Profiles。

### 配置沙箱

```yaml
oryxos:
  sandbox:
    type: whitelist        # 核心阶段只支持 'whitelist'
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

违反抛 `SandboxViolationException`，全局异常处理器捕获。

## 运行

```bash
mvn spring-boot:run
# 或者
java -jar target/your-app-1.0.0.jar
```

应该看到：

```
[oryxos] started gateway
[oryxos] CLI channel:    ready
[oryxos] REST API:       http://localhost:8080
[oryxos] AgentScheduler: 3 schedules registered
[oryxos] Profiles:       daily-weather, daily-tech-digest, daily-github
```

## 自动装配清单

| 组件                       | 类                                         |
| -------------------------- | ------------------------------------------ |
| AgentService               | `io.oryxos.core.service.AgentService`      |
| ReActLoop                  | `io.oryxos.core.loop.ReActLoop`            |
| PromptBuilder              | `io.oryxos.core.prompt.PromptBuilder`      |
| ToolExecutor               | `io.oryxos.tool.exec.ToolExecutor`         |
| ProviderService            | `io.oryxos.provider.ProviderService`       |
| MemoryService              | `io.oryxos.memory.MemoryService`           |
| ToolRegistry               | `io.oryxos.tool.registry.ToolRegistry`     |
| Sandbox（WhitelistSandbox）| `io.oryxos.tool.sandbox.WhitelistSandbox`  |
| AgentScheduler             | `io.oryxos.core.scheduler.AgentScheduler`  |
| REST Controllers           | `io.oryxos.web.*`                          |

任何一个都可以用自己的 `@Bean` 同类型覆盖。Spring Boot 的 `@ConditionalOnMissingBean` 会让位给你。

## 健康检查与系统信息

starter 如果 classpath 上有 Spring Boot Actuator 就自动启用：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

标准 Actuator 端点在 `/actuator/*`，加 OryxOS 特有的：

- `GET /actuator/oryxos/profiles`
- `GET /actuator/oryxos/tools`
- `GET /actuator/oryxos/sessions/count`

---

## 下一步

| 目标                                                         | 看到什么                                          |
| ------------------------------------------------------------ | ------------------------------------------------- |
| [Java SDK](./java)                                           | 自定义 Spring Boot 应用的编程式 API               |
| [CLI](./cli)                                                 | 12 个子命令详解                                    |
| [给工程师](../for-engineer)                                  | 构建、部署、扩展                                  |