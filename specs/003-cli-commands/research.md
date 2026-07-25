# Research: CLI 命令行入口（research.md）

> Phase 0 输出 — 解析所有 `NEEDS CLARIFICATION`、固化技术选型。所有决策锚定 [CLAUDE.md](../../CLAUDE.md) §5 / §14 / §16 + [Constitution](../../.specify/memory/constitution.md) §I / §III / §IV / §V。

## 决策 1：CLI 模块边界（`oryxos-cli` vs `oryxos-channel-cli`）

**Decision**：两个模块各司其职，**不重叠**。

- **`oryxos-cli`**（Picocli 主入口，**本 US 的交付物**）：承载 12 个 CLI 命令（`init / status / chat / serve / gateway / profile / provider / tool / session`）—— 即 [CLAUDE.md §14](../../CLAUDE.md) 表里的全部 12 个。`ConfigLoader` 也归这里。
- **`oryxos-channel-cli`**（[CLAUDE.md §5](../../CLAUDE.md) 既有的 Channel Adapter）：承载入站消息协议（HTTP POST / WS / SSE 等对称部分），由 US-3 Memory / US-4 Plugin Tool 阶段自然接入。**不属于本 US 的 CLI 命令清单**。

**Rationale**：CLAUDE.md §5 把这两个模块列得很清楚。`oryxos-cli` 是"消息怎么**由人启动**"，`oryxos-channel-cli` 是"消息怎么**进来**"，二者职责正交。

**Alternatives considered**：

- ❌ 合并为一个模块：会引入 Channel Adapter 的 HTTP/WS 依赖，违反 US-3 之前不引入 Channel 协议的纪律。
- ❌ 新建 `cli-v2` 模块：违反 Constitution §I "exactly nine modules"。

## 决策 2：Spring Context 启动策略（"零 Spring" vs "必须 Spring"）

**Decision**：按 [CLAUDE.md §14](../../CLAUDE.md) 后半段的明确分组，**两类命令走两条构造路径**，但共用同一个主类。

| 命令组 | 路径 | 是否 Spring |
|--------|------|-------------|
| `init` / `status` / `profile list/show/create/delete` | 直接 `new CommandLine(...)` + 文件 IO / SnakeYAML | ❌ 零 Spring |
| `chat` / `serve` / `gateway` / `provider list` / `tool list` / `session list` | `OryxosApplication.main(...)` → `SpringApplication.run(...)` | ✅ Spring |
| `serve` / `gateway` | 当前为 stub（"not yet implemented in 003"），US-5 接管 | stub 实现 |

具体做法：

1. `io.oryxos.cli.Main` 是 Picocli 根入口，**不做** `SpringApplication.run`；它解析 `-S / --spring / --no-spring` 标志决定是否启动 Spring。
2. `-S`（默认按子命令自动判断）下，Main 调用 `io.oryxos.boot.OryxosApplication.main(args)` 触发 `SpringApplication.run`，得到 `ConfigurableApplicationContext`，再从 context 里取 `AgentService` / `ProviderService` / `SessionRepository` 等 bean。
3. `init` / `status` / `profile list/show/create/delete` **不**调用 `-S`；只走 `Files.*` + SnakeYAML（[CLAUDE.md §5](../../CLAUDE.md) `oryxos-cli` 的 `ConfigLoader`）。

**Rationale**：避免每次 `profile list` 都花 5 秒启动 Spring；同时满足 [SC-003 / SC-004](../003-cli-commands/spec.md) "≤ 200 ms 首输出"。

**Alternatives considered**：

- ❌ 所有命令都走 Spring Context：牺牲 `profile list` 的 200 ms 指标。
- ❌ 全部不启动 Spring：要拿到 `ProviderService` / `SessionRepository` 必须 Spring DI，绕不开。

## 决策 3：Picocli 与 Spring Boot 的集成方式

**Decision**：用 Picocli 原生 API（**不**用 `picocli-spring-boot-starter`），由 `Main` 类自己持有 `CommandLine` 实例并按需注入 Spring beans。

```java
// oryxos-cli/src/main/java/io/oryxos/cli/Main.java
@Command(name = "oryxos", mixinStandardHelpOptions = true, version = "oryxos 1.0.0",
         description = "OryxOS command-line entry", subcommands = {
    InitCommand.class, StatusCommand.class, ChatCommand.class,
    ServeCommand.class, GatewayCommand.class,
    ProfileCommand.class, ProviderCommand.class,
    ToolCommand.class, SessionCommand.class
})
public class Main implements Callable<Integer> {
    @Spec CommandSpec spec;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(System.out);
        return 0;
    }
}
```

子命令分两类：

- **零 Spring 子命令**：`InitCommand`、`StatusCommand`、`ProfileListCommand` 等 —— 直接 `extends CommandBase`，无 `@Component`，不进入 Spring 扫描。
- **需要 Spring 的子命令**：`ChatCommand`、`ProviderListCommand` 等 —— 在 Spring 启动后由 Spring 实例化 bean，注入到 Main 后再注册到 `CommandLine`（见 `BootCommandLineRegistrar`）。

**Rationale**：避免 `picocli-spring-boot-starter` 引入的反射黑盒（Spring 启动顺序与 Picocli 子命令扫描有耦合）；保持 CLI 启动到第一行输出 ≤ 200 ms（[SC-003](../003-cli-commands/spec.md)）。

**Alternatives considered**：

- ❌ `picocli-spring-boot-starter`：自动注册所有 `@Component` 子命令 → 与"按需启动 Spring"策略冲突。
- ❌ `spring-boot-cli`：已 deprecated。

## 决策 4：Profile 加载与 `${ENV_VAR}` 替换

**Decision**：`ConfigLoader`（`oryxos-cli` 模块）负责 YAML 解析 + 占位符替换，**不**复用 Spring 的 `${...}` 占位符机制（Spring 启动后才能用）。

```java
// 伪代码（实际写在 ConfigLoader.java）
String yaml = Files.readString(agentMdPath);
String resolved = yaml.replaceAll("\\$\\{([A-Z_][A-Z0-9_]*)\\}",
    m -> Optional.ofNullable(System.getenv(m.group(1)))
                .orElseThrow(() -> new ConfigLoader.MissingEnvVarException(
                    "env var " + m.group(1) + " required by " + agentMdPath)));
```

匹配规则 `\$ \{ [A-Z_][A-Z0-9_]* \}` 与 [CLAUDE.md §16](../../CLAUDE.md) 的 `${ENV_VAR}` 约定一致。**未设置**时抛 `MissingEnvVarException`，CLI 包成 stderr + exit code 69（EX_UNAVAILABLE）。

**Rationale**：

- 与 Spring 的 `${ENV_VAR:default}` 语法不冲突（Spring 那一套在 `application.yaml` 走，本 CLI 的 YAML 走自己的 `ConfigLoader`）。
- fail-fast 优于 silent fallback（[SC-007](../003-cli-commands/spec.md)）。

**Alternatives considered**：

- ❌ 复用 Spring `PlaceholderResolver`：要求 Spring 已启动 → 与"零 Spring"路径冲突。
- ❌ `dotenv` / Vault：属于扩展阶段。

## 决策 5：BSD sysexits 退出码

**Decision**：CLI 顶层捕获异常 → 映射到 [BSD sysexits](https://man.openbsd.org/sysexits) 码。Picocli 子命令的 `@Command` 注解指定 `exitCodeOnException` 即可。

| 场景 | 退出码 | sysexits |
|------|--------|----------|
| 成功 | 0 | EX_OK |
| 通用失败（异常未分类） | 1 | EX_GENERIC |
| 用法错误（参数缺失 / Profile 名非法） | 64 | EX_USAGE |
| 配置缺失（API key / Provider 未配） | 69 | EX_UNAVAILABLE |
| Profile 配置错误（YAML 解析失败） | 78 | EX_CONFIG |
| 第二次 `init` | 1 | EX_GENERIC |

**Rationale**：[SC-007](../003-cli-commands/spec.md) 显式要求；企业运维 / shell 脚本需要稳定退出码语义。

**Alternatives considered**：

- ❌ 全部用 0/1 二值：违反 [SC-007](../003-cli-commands/spec.md)。
- ❌ 自定义码（>200）：不通用。

## 决策 6：STDIN 交互 vs 一次性 `--message`

**Decision**：两种输入形式并存，**默认一次性**。

- `oryxos chat <profile> "你好"`：把 `"你好"` 作为单轮 user message 注入（[FR-002](../003-cli-commands/spec.md)）。
- `oryxos chat <profile>` （无参数）：从 stdin 读一行，多行用 heredoc（`<<EOF`）。

实现：`ChatCommand.call()` 用 Picocli 的 `@Parameters` + `arity = "0..1"` 区分。

**Rationale**：兼容企业 CI smoke（一次性命令）+ 运维手敲（stdin）。多轮 REPL 是扩展阶段能力。

**Alternatives considered**：

- ❌ 强制多轮 REPL：CI smoke 不友好。
- ❌ 强制一次性：无法交互式调试。

## 决策 7：错误信息走 stderr 而非 stdout（FR-010）

**Decision**：所有 `@Command.call()` 方法**仅**写 `System.out` 写最终结果；异常栈用 SLF4J `oryxos-cli-error` logger 写 `.oryxos/logs/`，并用 `CommandLine.ParameterException` / `ExecutionException` 包装后只写 `System.err` 一行摘要。

```java
try {
    return doWork();
} catch (ConfigLoader.MissingEnvVarException e) {
    System.err.println("ERROR: " + e.getMessage());
    log.error("chat failed", e);
    return 69;  // EX_UNAVAILABLE
}
```

**Rationale**：[FR-010](../003-cli-commands/spec.md) + `oryxos chat foo "x" | grep bar` 这类管道用法需要干净 stdout。

**Alternatives considered**：

- ❌ 全走 stdout：管道用法坏掉。
- ❌ 全走 stderr：成功时没输出 → CI 检测不到。

## 决策 8：测试策略（[SC-008](../003-cli-commands/spec.md)）

**Decision**：三层测试，**不**引入新测试框架。

| 层 | 工具 | 覆盖 |
|----|------|------|
| 单元 | JUnit 5（既有）+ AssertJ | `ConfigLoader` 解析、`${ENV_VAR}` 替换、Profile 名正则、命令 --help |
| 集成 | `@SpringBootTest` + WireMock（既有） | `chat` / `provider list` / `tool list` / `session list` 真起 Spring |
| 端到端 | `scripts/cli-smoke.sh`（新增） | `init` / `status` / `profile list` / `chat` 在真实 DeepSeek + stub Tool 上跑 |

**Rationale**：[CLAUDE.md §11](../../CLAUDE.md) Demo-First；既有测试体系不动。

**Alternatives considered**：

- ❌ 引入 Cucumber / BDD：与 §21 "叙述用中文、标识符用英文" + 既有用 JUnit 5 的现状冲突。
- ❌ Testcontainers：CLI 不需要容器；扩展阶段才引入。

## 决策 9：Maven 模块依赖图（本 US 增量）

**Decision**：只动 `oryxos-cli` 与 `oryxos-channel-cli` 两个模块的依赖，**不**改其他 7 个模块。

```xml
<!-- oryxos-cli/pom.xml -->
<dependencies>
  <dependency><groupId>info.picocli</groupId><artifactId>picocli</artifactId></dependency>
  <dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot</artifactId></dependency>

  <!-- 跨模块 -->
  <dependency><groupId>io.oryxos</groupId><artifactId>oryxos-core</artifactId></dependency>
  <dependency><groupId>io.oryxos</groupId><artifactId>oryxos-provider</artifactId></dependency>
  <dependency><groupId>io.oryxos</groupId><artifactId>oryxos-storage</artifactId></dependency>
  <dependency><groupId>io.oryxos</groupId><artifactId>oryxos-boot</artifactId></dependency>

  <!-- 既有：oryxos-channel-cli（CliChannel 在 US-3+ 才用到，本 US 仅占位依赖） -->
  <dependency><groupId>io.oryxos</groupId><artifactId>oryxos-channel-cli</artifactId></dependency>
</dependencies>
```

`oryxos-cli` 不依赖 `oryxos-tool` / `oryxos-memory` / `oryxos-web`：本 US 不引入 Tool / Memory / Web 命令面（[CLAUDE.md §5](../../CLAUDE.md) "不要拆 Tool 多模块" 的对偶原则）。

**Rationale**：[Constitution §I](../../.specify/memory/constitution.md) "exactly nine modules" + [CLAUDE.md §5](../../CLAUDE.md) 9 模块布局；依赖收敛避免循环。

**Alternatives considered**：

- ❌ 让 `oryxos-cli` 直接依赖 `spring-boot-starter`：导致 Picocli 子命令被 Spring 接管，与决策 3 冲突。
- ❌ 抽公共 `cli-api` 模块：违反 §I。

## 决策 10：日志策略

**Decision**：

- `oryxos-cli` 自身的日志（启动、命令解析、Profile 加载）走 Logback `oryxos-cli.log`（路径 `.oryxos/logs/`），**不**进入 Spring Application Context 的 stdout。
- Spring 启动后 `chat` / `serve` / `gateway` 等命令的日志继承既有 `oryxos-app.log`（US-2 day-one 落库 + 日志通路）。

**Rationale**：避免 CLI 启动 Spring 失败时，CLI 自己的诊断信息被 Spring 的 banner 淹没。

**Alternatives considered**：

- ❌ 全部走 Spring 的 stdout：诊断不友好。
- ❌ 全部独立 logger：配置重复。

## 0. 决策汇总表

| ID | 决策 | 锚定 |
|----|------|------|
| 1 | `oryxos-cli` = 12 命令；`oryxos-channel-cli` = Channel Adapter（US-3+ 接入） | CLAUDE.md §5 / §14 |
| 2 | 零 Spring / 必须 Spring 双路径，共用 `Main` | CLAUDE.md §14 |
| 3 | Picocli 原生 API，不引 `picocli-spring-boot-starter` | Constitution §I |
| 4 | `ConfigLoader` 自管 `${ENV_VAR}`，不依赖 Spring | Constitution §IV + FR-014 |
| 5 | BSD sysexits 退出码（0/1/2/64/69/78） | FR-009 / SC-007 |
| 6 | `--message` 与 stdin 两种输入 | FR-002 |
| 7 | stderr 仅写摘要，stack trace 走 `.oryxos/logs/` | FR-010 / FR-018 |
| 8 | 三层测试（JUnit 5 + WireMock + cli-smoke.sh） | SC-008 |
| 9 | 只动 `oryxos-cli` 与 `oryxos-channel-cli` 依赖 | Constitution §I |
| 10 | 双 logger：`oryxos-cli.log` + `oryxos-app.log` | FR-017 |