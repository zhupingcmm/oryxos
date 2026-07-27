# 数据模型：Sandbox 白名单实现

**目的**：把 spec.md 中所有"关键实体"（[spec.md §关键实体](./spec.md)）的字段、约束、生命周期、关系固化为契约，给实现 + 测试提供明确数据边界。
**创建日期**：2026-07-27
**特性**：[spec.md](./spec.md)
**前置文档**：[research.md](./research.md) | [.specify/memory/constitution.md §I-VII](../../.specify/memory/constitution.md) | [specs/005-tool-system/contracts/sandbox.md §5](../005-tool-system/contracts/sandbox.md)

> **实体分两类**：① 不变量契约（`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException`）—— 已在 005 落地，007 **字节级不变**（NFR-004 / SC-007）；② 可变配置契约（`SandboxProperties` 4 类子配置）—— 007 扩展新增。本文件聚焦 007 新增/变更实体；既有契约只做引用摘要。

---

## 1. 不变量契约（007 字节级不变）

### 1.1 `Sandbox` 接口

| 项 | 值 |
|----|----|
| 归属 | `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/Sandbox.java` |
| 形态 | interface |
| 007 变更 | **无** —— 接口签名 `void enforce(SandboxAction action) throws SandboxViolationException` 保持 |
| 用途 | 4 类 ActionType 的校验门面；Tool 在 `execute()` 首行调用 |
| 实现 | `WhitelistSandbox`（核心阶段唯一实现） |

### 1.2 `SandboxAction` record

| 项 | 值 |
|----|----|
| 归属 | `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxAction.java` |
| 形态 | record `(ActionType type, String target)` |
| 007 变更 | **无** —— record 字段 + `Objects.requireNonNull` 校验 + 空白字符串拒绝 保持 |
| 用途 | 4 类 ActionType 的值对象（HTTP url / FILE path / SHELL command） |
| 约束 | `type != null`、`target != null`、`target` 经 `strip()` 后非空 |

### 1.3 `ActionType` 枚举

| 项 | 值 |
|----|----|
| 归属 | `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/ActionType.java` |
| 形态 | enum（4 值） |
| 007 变更 | **无** —— 4 值保持：`FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST` |
| 用途 | 标识 Sandbox 校验的目标动作类型 |

### 1.4 `SandboxViolationException`

| 项 | 值 |
|----|----|
| 归属 | `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxViolationException.java` |
| 形态 | RuntimeException |
| 007 变更 | **无** —— 异常类型 + 构造器签名 + 异常链 保持 |
| 用途 | 拦截失败抛出的异常；由 `DefaultToolExecutor` 兜底捕获并写 `tool_invocations(success=false, error_message="sandbox violation: <reason>")` |

---

## 2. 可变配置契约（007 扩展）

### 2.1 `SandboxProperties`（配置聚合根）

**归属**：`oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxProperties.java`
**形态**：`@ConfigurationProperties(prefix = "oryxos.tool.sandbox")` 顶层类（保留现有结构，不重构为 record）
**007 变更**：**扩展**为含 4 类子配置（HTTP / FILE / SHELL）—— 既有 HTTP 子配置保留并补强，新增 FILE / SHELL 两个子配置 + 兼容读源 `dangerous-commands`。

#### 顶层结构

```text
SandboxProperties
├── http: Http                # 既有（保留 + 补强 IPv6 字面识别）
│   └── allowedDomains: List<String> = []
├── file: File                # 新增
│   └── allowedPaths: List<String> = []
└── shell: Shell              # 新增
    ├── allowedCommands: List<String> = []
    └── dangerousCommands: List<String> = []   # 兼容读源（008 阶段统一收敛）
```

#### 字段约束

| 字段 | 类型 | 默认值 | 验证规则 |
|------|------|--------|----------|
| `http.allowedDomains` | `List<String>` | `List.of()` | 元素去前后空白；空白 / null 在 setter 阶段被 `List.of()` 兜底 |
| `file.allowedPaths` | `List<String>` | `List.of()` | 同上 |
| `shell.allowedCommands` | `List<String>` | `List.of()` | 元素 lower-case 后比较；保留原大小写仅用于显示 |
| `shell.dangerousCommands` | `List<String>` | `List.of()` | 兼容读源 —— 仅 `WhitelistSandbox` 在 `shell.allowedCommands` 非空时读取 |

#### YAML 配置示例

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:           # 既有（保留）
          - localhost
          - api.example.com
      file:                        # 新增
        allowed-paths:
          - /home/agent/workspace  # 工作区根；子目录允许
      shell:                       # 新增
        allowed-commands:          # 白名单
          - git
          - ls
          - cat
        dangerous-commands:        # 兼容读源（默认空；ShellToolProperties.dangerous-commands 优先）
          - rm
          - shutdown
```

#### fail-closed 默认

| 字段 | 业务方配置 | WhitelistSandbox 行为 |
|------|----------|---------------------|
| `http.allowedDomains` | 未配（空） | HTTP_REQUEST 全部拒绝；errorMessage = `"sandbox violation: host '<host>' not in allowed-domains"` |
| `file.allowedPaths` | 未配（空） | FILE_READ / FILE_WRITE 全部拒绝；errorMessage = `"sandbox violation: path '<path>' not in allowed-paths"` |
| `shell.allowedCommands` | 未配（空） | SHELL_COMMAND 全部拒绝（**注意**：ShellTool 内的 dangerousCommands 黑名单独立兜底，不受此影响） |

#### 与既有 Properties 的关系

| Properties 类 | Prefix | 字段 | 读方 | 007 变更 |
|-------------|--------|------|------|---------|
| `SandboxProperties.Http` | `oryxos.tool.sandbox.http` | `allowedDomains` | `WhitelistSandbox` | 不变 |
| `SandboxProperties.File`（新） | `oryxos.tool.sandbox.file` | `allowedPaths` | `WhitelistSandbox` | 新增 |
| `SandboxProperties.Shell`（新） | `oryxos.tool.sandbox.shell` | `allowedCommands` | `WhitelistSandbox` | 新增 |
| `SandboxProperties.Shell`（新） | `oryxos.tool.sandbox.shell` | `dangerousCommands` | `WhitelistSandbox`（兼容读源） | 新增 |
| `ShellToolProperties` | `oryxos.tool.shell` | `dangerousCommands` | `ShellTool`（黑名单兜底） | 不变 |

#### 关键不变约束

1. **HTTP 配置向后兼容**——既有 `oryxos.tool.sandbox.http.allowed-domains[0]=localhost` 配置在 007 完成后行为不变；既有 [SandboxEnforcementIntegrationTest.java](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java) 不修改即可通过。
2. **`dangerousCommands` 双源**——`ShellToolProperties.dangerousCommands`（黑名单）与 `SandboxProperties.shell.dangerousCommands`（兼容读源）并存；以 `ShellToolProperties` 为主（在 `ShellTool.execute()` 内优先检查），`SandboxProperties` 仅用于未来扩展阶段统一收敛（007 阶段不动此优先级）。
3. **结构稳定**——007 阶段不引入新子配置；008 / 009 阶段如要新增 `toolPolicy` / `audit` 等子配置应通过 Open-Closed 原则扩展 `SandboxProperties`，不修改既有 setter。

---

### 2.2 `WhitelistSandbox`（实现，007 扩展）

**归属**：`oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java`
**形态**：`implements Sandbox`
**007 变更**：**扩展** `enforce(SandboxAction)` switch case，新增 FILE_READ / FILE_WRITE / SHELL_COMMAND 校验；HTTP_REQUEST 既有逻辑不变 + 补强 IPv6 字面识别。

#### 校验逻辑

| ActionType | 既有（005） | 007 扩展 |
|-----------|------------|----------|
| `HTTP_REQUEST` | ✅ scheme / host 后缀 / IP 字面拒绝（[WhitelistSandbox.java:47-91](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java)） | + IPv6 字面识别补强（`[::1]` / `fe80::1%eth0`） |
| `FILE_READ` | ⚠️ no-op（[WhitelistSandbox.java:49-53](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java)） | ✅ 路径规范化 + 严格前缀匹配（research.md R-01） |
| `FILE_WRITE` | ⚠️ no-op | ✅ 同 FILE_READ |
| `SHELL_COMMAND` | ⚠️ no-op | ✅ 首 token 提取 + 大小写不敏感匹配（research.md R-02） |

#### 行为不变量

1. **`enforce()` 抛 `SandboxViolationException` MUST NOT 包含 stack trace**（NFR-004 / 宪法 §VI）。
2. **`enforce()` MUST NOT 触发任何文件系统调用**（如 `Files.exists` / `Path.toRealPath`）—— 即便业务方配 `allowed-paths = ['/home/agent/workspace']`，校验不应触发该目录的 IO（research.md R-01 备选 1 否决理由）。
3. **配置变化后必须重启生效**——核心阶段不引入 `RefreshScope` / 配置热更新；008 阶段如要热更新应通过 Spring Cloud Config / Nacos 集成。
4. **`WhitelistSandbox` 无状态 / 线程安全**（NFR-003）—— 配置仅在构造期注入，运行时只读；多线程调用 `enforce()` 不需加锁。

#### errorMessage 格式契约

| ActionType | 拦截场景 | errorMessage 前缀 + 内容 |
|-----------|---------|------------------------|
| `HTTP_REQUEST` | 白名单外 host | `sandbox violation: host '<host>' not in allowed-domains` |
| `HTTP_REQUEST` | IP 字面值 | `sandbox violation: IP-literal hosts are not allowed: <host>` |
| `HTTP_REQUEST` | 不支持的 scheme | `sandbox violation: unsupported scheme: <scheme>` |
| `HTTP_REQUEST` | URL 解析失败 | `sandbox violation: cannot parse URL: <raw>` |
| `FILE_READ` / `FILE_WRITE` | `..` traversal | `sandbox violation: path traversal detected: <raw> -> <normalized>` |
| `FILE_READ` / `FILE_WRITE` | 绝对路径 | `sandbox violation: absolute path not allowed: <path>` |
| `FILE_READ` / `FILE_WRITE` | 不在白名单 | `sandbox violation: path '<path>' not in allowed-paths` |
| `SHELL_COMMAND` | 首 token 不匹配 | `sandbox violation: command '<first>' not in allowed-commands` |
| `SHELL_COMMAND` | 空命令 | `sandbox violation: empty command` |

---

## 3. 审计数据契约（007 复用既有路径）

### 3.1 `tool_invocations` 表

**归属**：`oryxos-storage/src/main/resources/db/migration/`（[CLAUDE.md §13](../../CLAUDE.md)）
**007 变更**：**无表结构变更**——既有 `tool_invocations(success, error_message, duration_ms, ...)` 列足够承载 Sandbox 拦截事件。

#### Sandbox 拦截写入路径

1. Tool 在 `execute()` 首行调 `sandbox.enforce(action)`
2. `WhitelistSandbox` 检测到越界 → 抛 `SandboxViolationException("sandbox violation: <reason>")`
3. `DefaultToolExecutor` 兜底 `catch (SandboxViolationException ex) { return ToolResult.error("sandbox violation: " + ex.getMessage()) }`（既有路径，不变）
4. `JpaToolAuditWriter` 写 `tool_invocations(success=false, error_message="sandbox violation: <reason>", duration_ms=<ms>)`（既有路径，不变）

#### 审计查询模式

```sql
-- 业务方：统计 7 天内 Sandbox 拦截事件
SELECT date(timestamp), tool_name, error_message, COUNT(*) AS blocks
FROM tool_invocations
WHERE success = 0
  AND error_message LIKE 'sandbox violation: %'
  AND timestamp > datetime('now', '-7 days')
GROUP BY date(timestamp), tool_name, error_message
ORDER BY date(timestamp) DESC, blocks DESC;
```

**注意**：`error_message` MUST 不含 stack trace（NFR-004）；stack trace 100% 进 `.oryxos/logs/oryxos-cli-error.log` 由 Logback 异步写出。

---

## 4. 关系图

```text
SandboxProperties (or yxos.tool.sandbox)
├── http: Http
│   └── allowedDomains: List<String>
├── file: File                 [NEW 007]
│   └── allowedPaths: List<String>
└── shell: Shell               [NEW 007]
    ├── allowedCommands: List<String>
    └── dangerousCommands: List<String>   [NEW 007, 兼容读源]

WhitelistSandbox (implements Sandbox)
  ├── @Autowired SandboxProperties
  ├── enforce(HTTP_REQUEST)   → 既有 + IPv6 补强
  ├── enforce(FILE_READ)      [NEW 007] → 路径规范化 + 严格前缀
  ├── enforce(FILE_WRITE)     [NEW 007] → 路径规范化 + 严格前缀
  └── enforce(SHELL_COMMAND)  [NEW 007] → 首 token 大小写不敏感

ShellToolProperties (or yxos.tool.shell) [既有，不变]
  └── dangerousCommands: List<String>   → ShellTool 黑名单直接读源

┌────────────────────────────────────────────────────────────────┐
│ Tool 调用链（既有 + 007 钩入）                                  │
├────────────────────────────────────────────────────────────────┤
│ Tool.execute() 首行：                                           │
│   sandbox.enforce(new SandboxAction(type, target))             │
│   ↓ 通过 → 既有执行逻辑                                        │
│   ↓ 抛 SandboxViolationException → DefaultToolExecutor 兜底     │
│      → ToolResult.error("sandbox violation: <reason>")         │
│      → JpaToolAuditWriter → tool_invocations(success=false)     │
└────────────────────────────────────────────────────────────────┘
```

---

## 5. 配置变更影响范围

### 5.1 业务方配置面

| 配置入口 | YAML key | 默认行为 |
|---------|---------|---------|
| HTTP 域名白名单 | `oryxos.tool.sandbox.http.allowed-domains[0..N]` | 空 = 全部 HTTP 拒绝 |
| FILE 路径白名单 | `oryxos.tool.sandbox.file.allowed-paths[0..N]` | 空 = 全部 FILE 拒绝 |
| SHELL 命令白名单 | `oryxos.tool.sandbox.shell.allowed-commands[0..N]` | 空 = 全部 SHELL 拒绝 |
| SHELL 黑名单（兼容读源） | `oryxos.tool.sandbox.shell.dangerous-commands[0..N]` | 空 = 仅读 `ShellToolProperties.dangerousCommands` |

### 5.2 Spring 装配面

| Bean | 装配入口 | 007 变更 |
|------|---------|---------|
| `SandboxProperties` | `oryxos-boot/src/main/java/io/oryxos/boot/config/SandboxConfig.java` `@EnableConfigurationProperties(SandboxProperties.class)` | 不变（自动绑定新子配置） |
| `WhitelistSandbox` | 同上 `@Bean Sandbox` | 既有 @Bean 创建方式不变 |
| `ShellTool` | `oryxos-boot/src/main/java/io/oryxos/boot/config/ToolSystemConfig.java`（既有） | 不变 |

---

## 6. 引用

- [spec.md FR-001..011](./spec.md)
- [spec.md NFR-001..004](./spec.md)
- [spec.md SC-001..009](./spec.md)
- [research.md R-01..R-10](./research.md)
- [contracts/sandbox-whitelist.md](./contracts/sandbox-whitelist.md)（007 扩展契约）
- [quickstart.md](./quickstart.md)（4 类 ActionType 端到端验收场景）
- [CLAUDE.md §9.4 / §18](../../CLAUDE.md)
- [.specify/memory/constitution.md §I-VII](../../.specify/memory/constitution.md)
- [specs/005-tool-system/contracts/sandbox.md §5](../005-tool-system/contracts/sandbox.md)