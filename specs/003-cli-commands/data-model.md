# Data Model: CLI 命令行入口（data-model.md）

> Phase 1 输出 — 本 US 不引入新表；CLI 复用 US-1 / US-2 的实体与表。本文件**只**定义 CLI 层的本地数据结构（与磁盘 / 日志 / Bean 注册相关的瞬时态），避免与已有 [data-model.md](../002-react-loop/data-model.md) 重复。

## 1. 实体清单（本 US 新增）

| 实体 | 作用域 | 持久化 | 备注 |
|------|--------|--------|------|
| `WorkspaceLayout` | 内存瞬时态 | ❌ | 描述 `.oryxos/` 工作区在磁盘上的预期布局，用于 `init` / `status` |
| `CommandInvocation` | 内存 + 落 `.oryxos/logs/` | ❌（仅日志） | CLI 顶层捕获的"一条命令跑一次"的诊断信息 |
| `SpringContextHandle` | 内存瞬时态 | ❌ | CLI 启动 Spring 后持有一个 `ConfigurableApplicationContext`，命令结束后关闭 |

## 2. `WorkspaceLayout`

描述 `.oryxos/` 工作区在磁盘上的预期布局，由 `init` 创建、由 `status` 校验。

```java
package io.oryxos.cli.workspace;

import java.nio.file.Path;
import java.util.List;

/**
 * WorkspaceLayout —— .oryxos/ 工作区在磁盘上的预期布局。
 * init 命令按此布局创建；status 命令按此布局校验"已初始化"。
 *
 * 不持久化；纯内存描述。
 */
public record WorkspaceLayout(
    Path root,                    // .oryxos/ 绝对路径（realpath）
    List<Path> requiredDirs,      // agents/, memory/, sessions/, logs/
    List<Path> requiredFiles,     // mcp_servers.yaml, AGENTS.md, SOUL.md, USER.md, oryxos.db
    long createdAtEpochMs,        // .oryxos/ 的最早 mtime（init 时记录）
    int profileCount,             // agents/ 下一级子目录数
    int providerCountConfigured,  // 从 application.yaml + Spring context 读
    int providerCountMissingKey   // API key 未 resolved 的 provider 数
) {
    /**
     * 探测现有 .oryxos/ 工作区，返回 layout 摘要。
     * 若 .oryxos/ 不存在，抛 NotInitializedException（CLI 包成 exit 1）。
     */
    public static WorkspaceLayout probe(Path root);

    /** 在 root 下创建完整工作区（幂等：目录已存在则跳过；文件已存在则保留）。 */
    public void initialize();

    /** status 命令的输出，按此结构渲染表格。 */
    public String renderHumanReadable();
    public String renderJson();
}
```

### 2.1 字段约束

| 字段 | 验证规则 |
|------|----------|
| `root` | 必须是绝对路径；通过 `Path.toRealPath(LinkOption.NOFOLLOW_LINKS)` 解析 |
| `requiredDirs` | 长度 = 4（`agents/` + `memory/` + `sessions/` + `logs/`） |
| `requiredFiles` | 长度 = 5（`mcp_servers.yaml` + `AGENTS.md` + `SOUL.md` + `USER.md` + `oryxos.db`） |
| `profileCount` | `>= 0`；由 `init` 设为 0，由 `status` 动态扫描 |
| `providerCountConfigured` | Spring 启动后从 `ProviderService.allProviders()` 读 |
| `providerCountMissingKey` | 同上，但 `apiKeyResolved == false` 的 provider 数 |

### 2.2 状态转换

```
            ┌─────────────┐
            │  NotExist   │  ← oryxos init 启动前
            └──────┬──────┘
                   │ oryxos init
                   ▼
            ┌─────────────┐
            │  Initialized│  ← 完整 .oryxos/ 已创建
            └──────┬──────┘
                   │ 用户手动 rm -rf
                   ▼
            ┌─────────────┐
            │  NotExist   │
            └─────────────┘
```

`init` 在 `Initialized` 状态再跑 → 报 `Already initialized` + exit 1（[FR-003](../003-cli-commands/spec.md)）。

## 3. `CommandInvocation`

CLI 顶层每次跑命令时记录一条诊断信息，**只**落 `.oryxos/logs/oryxos-cli.log`，**不**进 SQLite 表。

```java
package io.oryxos.cli.diag;

import java.time.Instant;
import java.util.Map;

/**
 * CommandInvocation —— CLI 层"一次命令执行"的诊断条目。
 * 字段名故意避开 tool_invocations / llm_calls（避免与 day-one 审计表混淆）。
 *
 * 落日志路径：.oryxos/logs/oryxos-cli.log
 */
public record CommandInvocation(
    Instant startedAt,
    Instant finishedAt,
    String commandPath,           // 例: "oryxos chat weather-bot", "oryxos profile list"
    int exitCode,
    long durationMs,
    boolean springBootStarted,    // 本次命令是否启动了 Spring
    Map<String, String> envSnapshot  // 仅记录 ENV_VAR *是否* 设置（不记录值）
) {
    /** Logback MDC key：cli.invocation */
    public static final String MDC_KEY = "cli.invocation";
}
```

### 3.1 字段约束

| 字段 | 验证规则 |
|------|----------|
| `startedAt` | 本地时间；早于 `finishedAt` |
| `finishedAt` | 本地时间；等于 `startedAt + durationMs`（允许 ±1 ms 误差） |
| `commandPath` | 必须以 `oryxos ` 开头 |
| `exitCode` | 0/1/2/64/69/78 之一（[FR-009](../003-cli-commands/spec.md) BSD sysexits） |
| `durationMs` | `>= 0` |
| `springBootStarted` | `true`（chat / serve / gateway / provider list / tool list / session list），`false`（init / status / profile list/show/create/delete） |
| `envSnapshot` | key 是 ENV_VAR 名（`DEEPSEEK_API_KEY` 等），value 是 `"set"` 或 `"unset"`。**绝不记录真实值**（[FR-020](../003-cli-commands/spec.md)） |

### 3.2 不写入 SQLite

`CommandInvocation` 不进 SQLite 表：CLI 的可观测性以**日志**为主，SQLite 的 day-one 审计只承接 `tool_invocations` / `llm_calls`（[Constitution §VI](../../.specify/memory/constitution.md)）。避免在 SQLite 里再开一张 `cli_invocations` 表导致 schema 演进风险（[CLAUDE.md §13](../../CLAUDE.md) 工程风险提示）。

## 4. `SpringContextHandle`

CLI 启动 Spring 后持有的句柄，负责 graceful shutdown。

```java
package io.oryxos.cli.spring;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * SpringContextHandle —— CLI 层持有的 Spring 容器句柄。
 *
 * lifecycle: open() → commands run with beans → close() (in finally).
 * 失败时 throw，让 CLI 顶层捕获并包成 exit code 1。
 */
public final class SpringContextHandle implements AutoCloseable {
    private final ConfigurableApplicationContext ctx;

    public static SpringContextHandle open(String[] args) {
        // 调用 io.oryxos.boot.OryxosApplication.main(args)
        // 监听 ContextRefreshedEvent 拿到 ctx
    }

    public <T> T getBean(Class<T> type) { return ctx.getBean(type); }
    public ConfigurableApplicationContext raw() { return ctx; }

    @Override
    public void close() {
        if (ctx.isActive()) ctx.close();
    }
}
```

### 4.1 字段约束

| 字段 | 验证规则 |
|------|----------|
| `ctx` | 非 null；`ConfigurableApplicationContext` 实现（Spring Boot 默认给的是 `AnnotationConfigApplicationContext`） |

### 4.2 生命周期

```
open(args)
   │
   ▼
[ Active ]  ──── commands run ────▶  close()
   │
   └─ ctx refresh fails ──▶ 抛 ContextRefreshException ──▶ CLI 顶层捕获 ──▶ exit 1
```

`close()` 必须在 finally 块执行（[FR-012](../003-cli-commands/spec.md) "优雅关闭 Context"）。

## 5. 复用既有实体（不属本 US）

| 既有实体 | 来源 | CLI 用法 |
|---------|------|---------|
| `Profile`（YAML record） | [CLAUDE.md §16](../../CLAUDE.md) + [data-model.md §3.3](../002-react-loop/data-model.md) | `profile show` / `chat` / `init` 加载 |
| `Session` JPA entity | US-1 / US-2 | `session list` 查 `SessionRepository` |
| `Provider` 配置 record | US-1 | `provider list` 列 |
| `Tool` Bean | US-4（不在本 US） | `tool list` 列（前置：`tool list` 落到本 US，US-4 接入真实 Tool Bean） |

## 6. SQLite 表复用

本 US **不**新增 SQLite 表。CLI 落审计行只走 US-1 / US-2 已有的：

- `sessions` —— `chat` 命令每次执行新增一行（`AgentService.process()` 已写）
- `llm_calls` —— 每次 LLM 调用新增一行（US-1 已写）
- `tool_invocations` —— 每次 Tool 调用新增一行（US-2 day-one 已写）

不引入 `cli_invocations` 表 —— 避免 `ALTER TABLE` 演进风险（[CLAUDE.md §13](../../CLAUDE.md)）。

## 7. 不变量

| ID | 不变量 | 触发条件 |
|----|--------|----------|
| INV-CLI-1 | `.oryxos/` 二次 `init` 必须 fail-fast，不覆盖任何文件 | 用户跑 `oryxos init` 在已初始化目录 |
| INV-CLI-2 | `chat` 必须驱动 `AgentService.process()`，**不**直接持有 `ChatModel` Bean | FR-002 / NFR-002 |
| INV-CLI-3 | API key **永不**进 stdout / 日志 / `status` 默认输出 | FR-020 |
| INV-CLI-4 | 任何 Tool / LLM 调用都进 day-one 审计表，**不**绕过 `ToolExecutor` / `ProviderService` | Constitution §VI + CLAUDE.md §13 |
| INV-CLI-5 | 错误消息走 stderr；stdout **仅**承载成功的命令输出 | FR-010 |
| INV-CLI-6 | CLI 不引入第 10 个模块；不引入 `picocli-spring-boot-starter` | Constitution §I + 决策 3 |
| INV-CLI-7 | Spring 启动命令 ≤ 5 s 首输出；零 Spring 命令 ≤ 200 ms | SC-003 / SC-004 / FR-013 |