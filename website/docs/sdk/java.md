---
title: Java SDK — OryxOsCli
description: The Java CLI entrypoint and programmatic API for OryxOS.
---

# Java SDK

The first-party Java SDK is the `oryxos-cli` module. It exposes the Picocli-based command-line entrypoint and a programmatic API for embedding OryxOS in your own Spring Boot application.

> The Java SDK is the only first-party SDK in the core and extension stages. Python / TypeScript / Go SDKs are community-stage.

---

## CLI entrypoint

`oryxos-cli` is a Picocli main class:

```java
package io.oryxos.cli;

@Command(
    name = "oryxos",
    mixinStandardHelpOptions = true,
    version = "OryxOS 1.0.0-SNAPSHOT",
    description = "OryxOS — Enterprise Agent OS runtime kernel CLI"
)
public class OryxOsCli implements Runnable {
    @Spec CommandSpec spec;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new OryxOsCli()).execute(args);
        System.exit(exitCode);
    }
}
```

Build and run:

```bash
mvn -pl oryxos-cli -am clean package -DskipTests
java -jar oryxos-cli/target/oryxos-cli-1.0.0-SNAPSHOT.jar --version
# OryxOS 1.0.0-SNAPSHOT
```

## 12 sub-commands

| Group       | Command           | Purpose                                       |
| ----------- | ----------------- | --------------------------------------------- |
| Lifecycle   | `init`            | Initialize `.oryxos/` workspace               |
| Lifecycle   | `status`          | Show runtime status (Profiles, sessions, jobs) |
| Lifecycle   | `chat`            | Interactive REPL with one Profile             |
| Lifecycle   | `serve`           | Run REST API only                             |
| Lifecycle   | `gateway`         | CLI + REST + Scheduler (everything)           |
| Profile     | `profile list`    | List loaded Profiles                          |
| Profile     | `profile create`  | Scaffold a new `AGENT.md` directory           |
| Profile     | `profile show`    | Print a Profile's resolved YAML               |
| Profile     | `profile delete`  | Remove a Profile directory                    |
| Discovery   | `provider list`   | List registered Providers                     |
| Discovery   | `tool list`       | List registered Tools                         |
| Discovery   | `session list`    | List persisted Sessions                       |

`init`, `profile list`, and `profile create` don't require Spring context — they run before the JVM starts up. Everything else spins up Spring.

## Programmatic API

For embedding OryxOS in your own application, depend on `oryxos-core` directly:

```xml
<dependency>
    <groupId>io.oryxos</groupId>
    <artifactId>oryxos-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Then inject the services you need:

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

The service interfaces are stable across core and extension stages — your code will keep compiling.

## Versioning

OryxOS follows [Semantic Versioning](https://semver.org/):

- **Major** — incompatible API changes (rare, only at extension→community stage boundaries)
- **Minor** — new features, backward-compatible (most releases)
- **Patch** — bug fixes, no API change

`oryxos-*` artifacts share a single version (`1.0.0`, `1.1.0`, etc.).

## Compatibility matrix

| OryxOS version | JDK | Spring Boot | Spring AI  |
| -------------- | --- | ----------- | ---------- |
| 1.0.x (core)   | 21  | 3.3.x       | 1.0.x      |
| 1.1.x (planned) | 21 | 3.4.x       | 1.1.x      |

---

## Where to go next

| Destination                                                  | What you'll find                                       |
| ------------------------------------------------------------ | ------------------------------------------------------ |
| [Spring Boot Starter](./spring-boot-starter)                  | Drop-in Spring Boot auto-configuration                 |
| [CLI](./cli)                                                 | The 12 sub-commands in detail                          |
| [Spring AI integration](../integrations/spring-ai)          | How OryxOS uses Spring AI                              |
| [For Engineers](../for-engineer)                             | Build, deploy, extend                                  |