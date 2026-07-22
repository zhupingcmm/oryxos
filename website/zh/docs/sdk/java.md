---
title: Java SDK —— OryxOsCli
description: OryxOS 的 Java CLI 入口和编程式 API。
---

# Java SDK

官方 Java SDK 是 `oryxos-cli` 模块。提供 Picocli 命令行入口和编程式 API，把 OryxOS 嵌入你自己的 Spring Boot 应用。

> Java SDK 是核心阶段和扩展阶段的唯一官方 SDK。Python / TypeScript / Go SDK 是社区阶段。

---

## CLI 入口

`oryxos-cli` 是 Picocli 主类：

```java
package io.oryxos.cli;

@Command(
    name = "oryxos",
    mixinStandardHelpOptions = true,
    version = "OryxOS 1.0.0-SNAPSHOT",
    description = "OryxOS — 企业级 Agent OS 运行时内核 CLI"
)
public class OryxOsCli implements Runnable {
    @Spec CommandSpec spec;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new OryxOsCli()).execute(args);
        System.exit(exitCode);
    }
}
```

构建并运行：

```bash
mvn -pl oryxos-cli -am clean package -DskipTests
java -jar oryxos-cli/target/oryxos-cli-1.0.0-SNAPSHOT.jar --version
# OryxOS 1.0.0-SNAPSHOT
```

## 12 个子命令

| 分组       | 命令               | 用途                                       |
| ---------- | ------------------ | ------------------------------------------ |
| Lifecycle  | `init`             | 初始化 `.oryxos/` 工作区                    |
| Lifecycle  | `status`           | 运行时状态（Profiles / sessions / jobs）    |
| Lifecycle  | `chat`             | 跟一个 Profile 交互式 REPL                 |
| Lifecycle  | `serve`            | 只跑 REST API                              |
| Lifecycle  | `gateway`          | CLI + REST + Scheduler（一切）              |
| Profile    | `profile list`     | 列出已加载 Profiles                          |
| Profile    | `profile create`   | 脚手架一个新 `AGENT.md` 目录                |
| Profile    | `profile show`     | 打印 Profile 解析后的 YAML                  |
| Profile    | `profile delete`   | 删除一个 Profile 目录                       |
| Discovery  | `provider list`    | 列出已注册 Providers                         |
| Discovery  | `tool list`        | 列出已注册 Tools                             |
| Discovery  | `session list`     | 列出持久化 Sessions                          |

`init` / `profile list` / `profile create` 不需要 Spring 上下文——JVM 启动前就跑完。其他都拉起 Spring。

## 编程式 API

要把 OryxOS 嵌入自己的应用，直接依赖 `oryxos-core`：

```xml
<dependency>
    <groupId>io.oryxos</groupId>
    <artifactId>oryxos-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

然后注入你需要的服务：

```java
@Service
public class MyService {
    @Autowired private AgentService agentService;
    @Autowired private ProviderService providerService;
    @Autowired private MemoryService memoryService;
    @Autowired private ToolRegistry toolRegistry;

    public AgentResponse ask(String profileName, String message) {
        Profile profile = profileRegistry.get(profileName);
        Session session = sessionManager.create(profile);
        return agentService.process(session, message);
    }
}
```

服务接口在核心和扩展阶段稳定——你的代码继续能编译。

## 版本管理

OryxOS 遵循 [语义化版本](https://semver.org/)：

- **Major** —— 不兼容 API 变更（极少，仅在扩展→社区阶段边界）
- **Minor** —— 新特性，向后兼容（大多数发布）
- **Patch** —— bug 修复，无 API 变化

`oryxos-*` artifact 共享一个版本号（`1.0.0`、`1.1.0` 等）。

## 兼容性矩阵

| OryxOS 版本       | JDK | Spring Boot | Spring AI |
| ----------------- | --- | ----------- | --------- |
| 1.0.x（核心）     | 21  | 3.3.x       | 1.0.x     |
| 1.1.x（规划）     | 21  | 3.4.x       | 1.1.x     |

---

## 下一步

| 目标                                                         | 看到什么                                          |
| ------------------------------------------------------------ | ------------------------------------------------- |
| [Spring Boot Starter](./spring-boot-starter)                  | 拖入即用的 Spring Boot 自动装配                   |
| [CLI](./cli)                                                 | 12 个子命令详解                                    |
| [Spring AI 集成](../integrations/spring-ai)                   | OryxOS 怎么用 Spring AI                           |
| [给工程师](../for-engineer)                                  | 构建、部署、扩展                                  |