# 契约扩展：Sandbox 白名单实现（007 增量）

**目的**：把 007 阶段对 [specs/005-tool-system/contracts/sandbox.md](../005-tool-system/contracts/sandbox.md) 的扩展固化为契约文档，供实现 + 测试 + 后续分析使用。
**创建日期**：2026-07-27
**特性**：[spec.md](../spec.md)
**基础契约**：[specs/005-tool-system/contracts/sandbox.md](../005-tool-system/contracts/sandbox.md)（007 阶段继承既有 §1-§11 全部 + 本文件补 §12-§14 三节）

> **重要约束**：005 契约中 [Sandbox / SandboxAction / ActionType / SandboxViolationException / SandboxProperties 5 个公共 API 字节级不变](../005-tool-system/contracts/sandbox.md)（NFR-004 / SC-007）。本契约**只扩展**既有 SandboxProperties 的子配置 + WhitelistSandbox 行为；不改公共 API。

---

## §12. SandboxProperties 扩展契约（007 新增）

### §12.1 4 类子配置总览

| 子配置 | 归属 | 007 状态 | 字段 |
|--------|------|---------|------|
| `Http` | 既有（005 §5.1 落地） | 保留 + 补强 IPv6 | `allowedDomains: List<String>` |
| `File` | 新增 | **新增** | `allowedPaths: List<String>` |
| `Shell` | 新增 | **新增** | `allowedCommands: List<String>` + `dangerousCommands: List<String>`（兼容读源） |

### §12.2 完整类结构契约

```java
@ConfigurationProperties(prefix = "oryxos.tool.sandbox")
public class SandboxProperties {
    private Http http = new Http();      // 既有（保留）
    private File file = new File();      // 新增
    private Shell shell = new Shell();   // 新增

    // --- 既有 Http（005 落地，保留）---
    public static class Http {
        private List<String> allowedDomains = List.of();
        public List<String> getAllowedDomains() { return allowedDomains; }
        public void setAllowedDomains(List<String> allowedDomains) {
            this.allowedDomains = (allowedDomains == null) ? List.of() : List.copyOf(allowedDomains);
        }
    }

    // --- 新增 File（007 落地）---
    public static class File {
        private List<String> allowedPaths = List.of();
        public List<String> getAllowedPaths() { return allowedPaths; }
        public void setAllowedPaths(List<String> allowedPaths) {
            this.allowedPaths = (allowedPaths == null) ? List.of() : List.copyOf(allowedPaths);
        }
    }

    // --- 新增 Shell（007 落地）---
    public static class Shell {
        private List<String> allowedCommands = List.of();
        private List<String> dangerousCommands = List.of();   // 兼容读源

        public List<String> getAllowedCommands() { return allowedCommands; }
        public void setAllowedCommands(List<String> allowedCommands) {
            this.allowedCommands = (allowedCommands == null) ? List.of() : List.copyOf(allowedCommands);
        }

        public List<String> getDangerousCommands() { return dangerousCommands; }
        public void setDangerousCommands(List<String> dangerousCommands) {
            this.dangerousCommands = (dangerousCommands == null) ? List.of() : List.copyOf(dangerousCommands);
        }
    }

    // --- getters/setters（既有 + 新增）---
    public Http getHttp() { return http; }
    public void setHttp(Http http) { this.http = http; }

    public File getFile() { return file; }
    public void setFile(File file) { this.file = file; }

    public Shell getShell() { return shell; }
    public void setShell(Shell shell) { this.shell = shell; }
}
```

### §12.3 YAML 绑定契约

| Java 字段 | YAML key | 默认 | 类型 |
|---------|---------|------|------|
| `http.allowedDomains` | `oryxos.tool.sandbox.http.allowed-domains` | `[]` | `List<String>` |
| `file.allowedPaths` | `oryxos.tool.sandbox.file.allowed-paths` | `[]` | `List<String>` |
| `shell.allowedCommands` | `oryxos.tool.sandbox.shell.allowed-commands` | `[]` | `List<String>` |
| `shell.dangerousCommands` | `oryxos.tool.sandbox.shell.dangerous-commands` | `[]` | `List<String>` |

### §12.4 fail-closed 默认（核心阶段硬约束）

| 字段 | 空配置行为 |
|------|----------|
| `http.allowedDomains = []` | HTTP_REQUEST **全部拒绝**（errorMessage 含 `"not in allowed-domains"`） |
| `file.allowedPaths = []` | FILE_READ / FILE_WRITE **全部拒绝**（errorMessage 含 `"not in allowed-paths"`） |
| `shell.allowedCommands = []` | SHELL_COMMAND **全部拒绝**（errorMessage 含 `"not in allowed-commands"`） |
| `shell.dangerousCommands = []` | 行为降级为仅读 `ShellToolProperties.dangerousCommands`（既有黑名单兜底，不受影响） |

---

## §13. WhitelistSandbox 行为契约（007 扩展）

### §13.1 enforce() switch 完整契约

```java
@Override
public void enforce(SandboxAction action) {
    Objects.requireNonNull(action, "action");
    switch (action.type()) {
        case HTTP_REQUEST   -> enforceHttp(action.target());
        case FILE_READ,
             FILE_WRITE    -> enforceFile(action.target());    // 007 新增
        case SHELL_COMMAND  -> enforceShell(action.target());   // 007 新增
    }
}

private void enforceFile(String target) {
    // research.md R-01 算法
    Path raw = Path.of(target);
    Path normalized = raw.normalize();
    if (!normalized.equals(raw)) {
        throw new SandboxViolationException(
            "sandbox violation: path traversal detected: " + raw + " -> " + normalized);
    }
    if (normalized.isAbsolute()) {
        throw new SandboxViolationException(
            "sandbox violation: absolute path not allowed: " + normalized);
    }
    if (properties.getFile().getAllowedPaths().isEmpty()) {
        throw new SandboxViolationException(
            "sandbox violation: path '" + normalized + "' not in allowed-paths");
    }
    Path resolved = Path.of(properties.getFile().getAllowedPaths().get(0)).resolve(normalized).normalize();
    boolean allowed = properties.getFile().getAllowedPaths().stream().anyMatch(p ->
        resolved.equals(Path.of(p)) || resolved.startsWith(Path.of(p) + "/"));
    if (!allowed) {
        throw new SandboxViolationException(
            "sandbox violation: path '" + resolved + "' not in allowed-paths");
    }
}

private void enforceShell(String command) {
    // research.md R-02 算法
    String trimmed = command.trim();
    if (trimmed.isEmpty()) {
        throw new SandboxViolationException("sandbox violation: empty command");
    }
    String first = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    boolean allowed = properties.getShell().getAllowedCommands().stream()
        .map(c -> c.toLowerCase(Locale.ROOT))
        .anyMatch(c -> first.equals(c));
    if (!allowed) {
        throw new SandboxViolationException(
            "sandbox violation: command '" + first + "' not in allowed-commands");
    }
}
```

**注**：上述代码仅展示行为契约的伪代码骨架，**不**等同于最终实现 —— 实际实现按 `OryxOS` 编码风格（[CLAUDE.md §17](../../CLAUDE.md)）调整（中文注释、异常链、null-check 时机等）。

### §13.2 enforceHttp() 行为契约（既有 + 007 补强）

#### §13.2.1 既有行为（保留不变）

| 行为 | 既有实现 | 007 契约 |
|------|---------|----------|
| scheme 校验 | 仅 http / https 通过；其他抛 `unsupported scheme` | 不变 |
| URL 解析失败 | 抛 `cannot parse URL` | 不变 |
| host 后缀匹配 | `host` 是 `allowed-domain` 之一 OR host 后缀为 `<allowed-domain>` 通过 | 不变 |
| IPv4 字面拒绝 | 命中 IPv4 pattern 直接拒绝 | 不变 |

#### §13.2.2 007 补强 — IPv6 字面识别

**既有实现**（[WhitelistSandbox.java:127-133](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java)）：仅检测 `:digit:.` 简单模式，对纯 IPv6 字面（如 `[::1]`）识别不全。

**007 补强**：把 `isIpLiteral()` 改为：

```text
含 `:` 字符 AND（全部由 hex digit + `:` + `.` 组成 OR 含 `[` / `]` 包装）
→ 视为 IP 字面值（含 IPv4-mapped IPv6 / 纯 IPv6 / IPv6 zone-id）
```

#### §13.2.3 IPv6 字面识别测试用例（007 新增）

```java
// 应拒（IP 字面）
"http://[::1]:8080/api"            → blocked (IPv6 loopback)
"http://[fe80::1%eth0]:8080/api"   → blocked (IPv6 link-local with zone-id)
"http://[::ffff:192.168.1.1]/api"  → blocked (IPv4-mapped IPv6)

// 应通过（域名白名单）
"http://api.example.com/api"       → allowed
```

### §13.3 错误信息格式契约（007 新增）

| ActionType | 拦截场景 | errorMessage（精确字符串） |
|-----------|---------|------------------------|
| `HTTP_REQUEST` | 白名单外 host | `"sandbox violation: host '<host>' not in allowed-domains"` |
| `HTTP_REQUEST` | IP 字面值 | `"sandbox violation: IP-literal hosts are not allowed: <host>"` |
| `HTTP_REQUEST` | scheme 不支持 | `"sandbox violation: unsupported scheme: <scheme>"` |
| `HTTP_REQUEST` | URL 解析失败 | `"sandbox violation: cannot parse URL: <raw>"` |
| `FILE_READ` / `FILE_WRITE` | `..` / `.` 段被规范化 | `"sandbox violation: path traversal detected: <raw> -> <normalized>"` |
| `FILE_READ` / `FILE_WRITE` | 绝对路径 | `"sandbox violation: absolute path not allowed: <path>"` |
| `FILE_READ` / `FILE_WRITE` | 不在白名单 | `"sandbox violation: path '<path>' not in allowed-paths"` |
| `SHELL_COMMAND` | 首 token 不匹配 | `"sandbox violation: command '<first>' not in allowed-commands"` |
| `SHELL_COMMAND` | 空命令 | `"sandbox violation: empty command"` |

**不变量**：errorMessage MUST NOT 包含任何 stack trace 字符（`\tat ` / `\n\tat ` / `Exception in thread` 等）；stack trace 100% 进 `.oryxos/logs/oryxos-cli-error.log`。

---

## §14. 集成契约（007 复用既有路径）

### §14.1 Tool.execute() 钩入契约

**既有契约**（[specs/005-tool-system/contracts/tool-executor.md §3](../005-tool-system/contracts/tool-executor.md)）：所有 `OryxTool.execute()` MUST 在首行调 `sandbox.enforce(SandboxAction)`。

**007 行为**：既有契约不变；`SandboxViolationException` 既有兜底逻辑（`DefaultToolExecutor` 捕获 → 返回 `ToolResult.error(...)`）自动覆盖 FILE / SHELL 新场景。

### §14.2 审计契约

**既有契约**（[specs/005-tool-system/contracts/tool-executor.md §4](../005-tool-system/contracts/tool-executor.md)）：每次 Tool 调用写 `tool_invocations` 一行。

**007 行为**：FILE / SHELL 拦截事件通过既有 `JpaToolAuditWriter` 自动写 `tool_invocations(success=false, error_message="sandbox violation: <reason>")`，无需新增审计 helper。

### §14.3 Notify 链路契约

**既有契约**（[specs/004-notify-channel/spec.md FR-007](../004-notify-channel/spec.md)）：`WebhookNotifyAdapter.send()` 在 HTTP POST 前调 `sandbox.enforce(HTTP_REQUEST, url)`。

**007 行为**：既有契约不变；004 已落地的 `WebhookNotifyAdapter.java:86-95` 不需修改即可享受 007 阶段 IPv6 识别补强。

### §14.4 Shell 黑名单 + 白名单分层契约

**双层防御顺序**（spec FR-004 + research.md R-06）：

```text
ShellTool.execute() 首行（既有，不变）:
  1. properties.dangerousCommands() 黑名单先检查
     → 命中：return ToolResult.error("shell command blocked: <cmd> is in dangerous-commands")
  2. 通过 → sandbox.enforce(SHELL_COMMAND, command)
     → WhitelistSandbox 检查 shell.allowedCommands
     → 未命中：抛 SandboxViolationException("sandbox violation: command '<first>' not in allowed-commands")
  3. 通过 → 既有 ProcessBuilder(command.split("\\s+")) 执行
```

**优先级**：黑名单 > 白名单 > 默认执行。`ShellToolProperties.dangerousCommands` 与 `SandboxProperties.shell.dangerousCommands` 同时配置时，以 `ShellToolProperties` 为准（既有优先级；007 阶段不动此约定）。

---

## §15. 配置变更影响契约

### §15.1 业务方兼容性

| 既有配置 | 007 后行为 | 是否 breaking change |
|---------|----------|---------------------|
| `oryxos.tool.sandbox.http.allowed-domains[0]=localhost`（既有 005 测试） | 不变 | 否 |
| 未配 `http.allowed-domains` | HTTP_REQUEST 全部拒绝（既有 fail-open → 007 fail-closed） | **是（安全强化）** —— spec FR-011 |
| 未配 `file.allowed-paths` | FILE_READ / FILE_WRITE 全部拒绝 | **是（安全强化）** —— spec FR-011 |
| 未配 `shell.allowed-commands` | SHELL_COMMAND 全部拒绝 | **是（安全强化）** —— spec FR-011 |
| `ShellToolProperties.dangerousCommands` 已配 | 不变（既有黑名单兜底） | 否 |

### §15.2 测试兼容性

| 既有测试 | 007 行为 | 是否需修改 |
|---------|---------|----------|
| [SandboxEnforcementIntegrationTest.http_unknown_host_blocked_no_side_effect](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java#L242) | 通过 | 否 |
| [SandboxEnforcementIntegrationTest.http_ip_literal_blocked_no_side_effect](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java#L252) | 通过 | 否 |
| [SandboxEnforcementIntegrationTest.shell_blacklisted_command_blocked_no_side_effect](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java#L262) | 通过（黑名单兜底先于白名单） | 否 |
| [SandboxEnforcementIntegrationTest.file_read_no_op_in_core_phase](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java#L278) | **必修改** —— 007 阶段 FILE_READ 必须真的 enforce，因此该测试要么改成断言"路径在白名单内通过 / 路径在白名单外拒绝"，要么删除 | **是（breaking）** |

**修法**：把第 4 个测试改成断言"FILE_READ 越界被拦"，并新增一个测试"FILE_READ 路径在白名单内通过"。后者需先在 `MinimalApp` 增加 `@Bean` 注入 `sandbox(SandboxProperties props)` 接受新文件配置；具体修法由 007 tasks.md T0XX 任务决定。

---

## §16. 引用

- [spec.md FR-001..011](../spec.md)
- [spec.md NFR-001..004](../spec.md)
- [spec.md SC-001..009](../spec.md)
- [data-model.md §2.1 / §2.2](../data-model.md)
- [research.md R-01..R-10](../research.md)
- [specs/005-tool-system/contracts/sandbox.md](../005-tool-system/contracts/sandbox.md)（基础契约，007 字节级继承）
- [specs/005-tool-system/contracts/tool-executor.md §3 / §4](../005-tool-system/contracts/tool-executor.md)
- [specs/004-notify-channel/spec.md FR-007](../004-notify-channel/spec.md)
- [CLAUDE.md §9.4 / §18](../../CLAUDE.md)
- [.specify/memory/constitution.md §VI / §VII](../../.specify/memory/constitution.md)
- [Sandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/Sandbox.java)
- [SandboxAction.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxAction.java)
- [ActionType.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/ActionType.java)
- [SandboxViolationException.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxViolationException.java)
- [SandboxProperties.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxProperties.java)
- [WhitelistSandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java)
- [SandboxEnforcementIntegrationTest.java](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java)