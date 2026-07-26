# 契约：Sandbox 接口（Tool 的安全护栏）

**目的**：定义 Tool 副作用的"前置校验"契约 —— `Sandbox.enforce()` 的语义、四种 ActionType 的处理策略、白名单匹配规则。这是 Agent OS 体系最核心的安全抽象。
**创建日期**：2026-07-26
**特性**：[spec.md §FR-002 / §FR-004 / §SC-005](../spec.md) | [research.md R-03 / R-11](./../research.md)
**前置**：[Sandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/Sandbox.java) | [WhitelistSandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java) | [CLAUDE.md §9.4](../../../CLAUDE.md)

---

## 1. 接口签名

```java
package io.oryxos.tool.sandbox;

public interface Sandbox {
    /**
     * 前置校验：Tool 副作用必须通过该校验。
     *
     * @param action  待校验动作（type + target）
     * @throws SandboxViolationException  校验失败
     */
    void enforce(SandboxAction action);
}

public record SandboxAction(ActionType type, String target) { }

public enum ActionType {
    FILE_READ,
    FILE_WRITE,
    SHELL_COMMAND,
    HTTP_REQUEST
}

public class SandboxViolationException extends RuntimeException {
    private final ActionType type;
    private final String target;
    private final String reason;     // "host not in whitelist" / "ip rejected" / "path not allowed"
    public SandboxViolationException(ActionType type, String target, String reason) { ... }
}
```

---

## 2. 4 种 ActionType 的核心阶段策略

| ActionType | 核心阶段策略 | 扩展阶段路径 |
|-----------|------------|------------|
| `FILE_READ` | **no-op**（核心阶段不限制文件读） | `WhitelistSandbox` 按 `allowed-paths` 前缀匹配 |
| `FILE_WRITE` | **no-op**（核心阶段不限制文件写） | `WhitelistSandbox` 按 `allowed-paths` 前缀匹配 |
| `SHELL_COMMAND` | **no-op**（核心阶段不限制命令）+ Tool 层黑名单兜底（[research.md R-03](../research.md)） | `WhitelistSandbox` 按 `allowed-commands` 精确匹配 |
| `HTTP_REQUEST` | **完整实现**（`WhitelistSandbox` host 后缀匹配 + IP 拒绝，004 阶段已落地） | 按运营者审计日志迭代规则 |

**理由**：

- 核心阶段 Demo 需要 `python` / `git` / `cat` / `echo` 等命令 —— 完整 `SHELL_COMMAND` 白名单会卡死 Demo。
- HTTP 白名单是 004 notify 阶段已落地的能力 —— HTTP Tool 复用即可（[research.md R-11](../research.md)）。
- `FILE_*` 在核心阶段不引入路径白名单是为了让 Demo `daily-github` 能读 `.oryxos/agents/<name>/scripts/` 下的脚本。

---

## 3. `WhitelistSandbox` 核心阶段行为

### 3.1 `enforce(HTTP_REQUEST, url)`

[WhitelistSandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java) 已落地的校验顺序：

```text
1. URL 解析失败（URI.create 抛 IllegalArgumentException）
   → 抛 SandboxViolationException("invalid url: <url>")
2. scheme 是 http / https 之外（如 file://、gopher://）
   → 抛 SandboxViolationException("unsupported scheme: <scheme>")
3. host 解析失败 / host 为 null
   → 抛 SandboxViolationException("cannot resolve host: <host>")
4. host 是 IP 字面量（InetAddress.getAllByName(host) 等同于 host）
   → 抛 SandboxViolationException("ip literal rejected: <host>")
5. host 后缀不在 whitelist 中（whitelist: ["localhost", ".example.com", "api.deepseek.com"]）
   → 抛 SandboxViolationException("host not in whitelist: <host>")
6. 全部通过 → 静默返回
```

**Whitelist 来源**：`SandboxProperties.http.allowed-hosts`（YAML 数组；精确域名或 `.suffix` 后缀匹配）。

**示例配置**（`.oryxos/config/application.yaml`）：

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-hosts:
          - localhost          # WireMock 测试
          - 127.0.0.1          # WireMock 测试
          - .oryxos.dev        # 通配后缀
          - api.deepseek.com
          - api.moonshot.cn
```

### 3.2 `enforce(FILE_READ, path)` / `enforce(FILE_WRITE, path)`

**核心阶段**：no-op。

**扩展阶段预期实现**（不在本 spec 范围）：

```java
// WhitelistSandbox.java 扩展阶段（伪代码）
@Override
public void enforce(SandboxAction action) {
    switch (action.type()) {
        case HTTP_REQUEST -> checkHttp(action.target());
        case FILE_READ, FILE_WRITE -> checkPath(action.target());  // 扩展阶段
        case SHELL_COMMAND -> checkCommand(action.target());      // 扩展阶段
    }
}
```

### 3.3 `enforce(SHELL_COMMAND, command)`

**核心阶段**：no-op（白名单不实现）。

**Tool 层兜底**（[ShellTool.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/shell/ShellTool.java) [NEW]，[research.md R-03](../research.md)）：

```java
// ShellTool.execute(command) 内部校验
String[] tokens = command.trim().split("\\s+");
for (String dangerous : sandboxProperties.getShell().getDangerousCommands()) {
    if (tokens[0].equals(dangerous)) {
        return ToolResult.error("shell command blocked: " + dangerous);
    }
}
```

**默认黑名单**（`SandboxProperties.shell.dangerous-commands`）：

```yaml
- rm
- mkfs
- dd
- shutdown
- reboot
- wget
- curl        # 用 http_get Tool 替代
- chmod       # 仅写入 777 类破坏性
- chown
- mv
- cp
```

---

## 4. 调用方契约

### 4.1 调用时机

**前置**：Tool 必须在执行副作用**之前**调 `sandbox.enforce(action)`，不允许先执行再校验。

**顺序**：

```java
// Tool 实现
public ToolResult execute(Map<String, Object> arguments) {
    String url = (String) arguments.get("url");
    sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url));  // ← 副作用前
    HttpResponse<String> resp = httpClient.send(...);                  // 副作用
    return ToolResult.ok(...);
}
```

### 4.2 异常处理

`SandboxViolationException` 抛到 `DefaultToolExecutor`：

```java
try {
    result = tool.execute(arguments);
} catch (SandboxViolationException ex) {
    return ToolResult.error("sandbox violation: " + ex.getReason() + " (" + ex.getType() + ": " + ex.getTarget() + ")");
} catch (RuntimeException ex) {
    return ToolResult.error("tool execution failed: " + ex.getMessage());
}
```

**审计**：`SandboxViolationException` 也写 1 行 `tool_invocations`（success=false，errorMessage 含 `reason` + `target`）。

### 4.3 校验失败透传语义

LLM 下一轮看到：

```text
success=false,
errorMessage="sandbox violation: host not in whitelist (HTTP_REQUEST: http://api.example.com)"
```

LLM 会自行调整（换 URL / 改方案）。

---

## 5. 配置契约

### 5.1 `SandboxProperties`（已落地）

```java
@ConfigurationProperties(prefix = "oryxos.tool.sandbox")
public record SandboxProperties(
    HttpConfig http,
    ShellConfig shell,
    FileConfig file
) {
    public record HttpConfig(List<String> allowedHosts) { }
    public record ShellConfig(List<String> dangerousCommands) { }
    public record FileConfig(List<String> allowedPaths) { }
}
```

### 5.2 默认值

```java
@Bean
public SandboxProperties sandboxProperties() {
    return new SandboxProperties(
        new HttpConfig(List.of("localhost", "127.0.0.1")),    // 仅本地默认
        new ShellConfig(List.of("rm", "mkfs", "dd", "shutdown", "reboot",
                                "wget", "curl", "chmod", "chown", "mv", "cp")),
        new FileConfig(List.of())                              // 核心阶段未启用
    );
}
```

### 5.3 运营者覆盖

运营者在 `.oryxos/config/application.yaml` 显式覆盖：

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-hosts:
          - localhost
          - api.deepseek.com
      shell:
        dangerous-commands:
          - rm
          - mkfs
          - dd
```

---

## 6. 不变量（Invariants）

- **I-SB-1**：`sandbox.enforce()` 在 Tool 副作用**之前**调用，不允许先执行再校验。
- **I-SB-2**：校验失败抛 `SandboxViolationException`（继承 `RuntimeException`），由 `DefaultToolExecutor` 捕获并转 `ToolResult.error`。
- **I-SB-3**：核心阶段 `FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` 走 no-op；运营者必须理解"核心阶段 Shell Tool 黑名单是 Tool 层兜底，不是 `Sandbox` 实现"。
- **I-SB-4**：`HTTP_REQUEST` 白名单匹配规则：精确域名 OR `.suffix` 后缀通配（如 `.oryxos.dev` 匹配 `api.oryxos.dev`）；不支持端口号通配（`:*`）。
- **I-SB-5**：host 是 IP 字面量**直接拒绝**，不允许白名单放行（防止 SSRF 绕过）。
- **I-SB-6**：URL 解析失败 / scheme 不支持（file://、gopher:// 等）一律拒绝。

---

## 7. 性能特性

- 单次 `enforce()` 调用开销：< 1ms（仅 InetAddress 解析 + List contains）
- `WhitelistSandbox` 是 Spring 单例；`allowedHosts` 是不可变 List，启动期加载
- InetAddress 解析受 DNS 抖动影响；高频场景下可加本地缓存（**不**在本 spec 范围）

---

## 8. 测试矩阵

| 测试 | 期望 |
|------|------|
| `http_allowed_host_passes` | `enforce(HTTP_REQUEST, "http://api.deepseek.com")` 不抛 |
| `http_unknown_host_throws` | 抛 `SandboxViolationException("host not in whitelist: example.com")` |
| `http_ip_literal_rejected` | 抛 `SandboxViolationException("ip literal rejected: 1.2.3.4")`（即使在白名单内） |
| `http_localhost_in_whitelist` | WireMock 场景：白名单含 `localhost`/`127.0.0.1`，测试通过 |
| `http_invalid_scheme_throws` | `file:///etc/passwd` 抛 `SandboxViolationException("unsupported scheme: file")` |
| `file_read_no_op` | `enforce(FILE_READ, "/etc/passwd")` 不抛（核心阶段 no-op） |
| `shell_dangerous_blocked_at_tool` | `ShellTool.execute("rm -rf /")` 返回 `ToolResult.error("shell command blocked: rm")` |
| `shell_safe_passes_sandbox` | `enforce(SHELL_COMMAND, "echo hi")` 不抛（核心阶段 no-op） |

---

## 9. 升级路径（不在本 spec 范围）

```text
核心阶段（应用层 WhitelistSandbox）
  ├─ HTTP_REQUEST ✓ 已落地
  ├─ FILE_READ / FILE_WRITE ✗ 扩展阶段补 allowed-paths
  └─ SHELL_COMMAND ✗ 扩展阶段补 allowed-commands

扩展阶段
  └─ 容器级隔离（namespace + cgroups + seccomp）—— 接口不变

远期
  └─ microVM 级隔离（Firecracker / Kata / gVisor）—— 接口不变
```

---

## 10. 不在本契约范围

- ❌ `Tool` 实现内部的"业务校验"（如 `path` 必须以 `.oryxos/` 开头）—— 是 Tool 的职责，不是 Sandbox
- ❌ Audit 写入（`SandboxViolationException` 由 `DefaultToolExecutor` 写审计，不是 Sandbox 自己写）
- ❌ 容器/虚拟化级别的强制隔离（扩展阶段）

---

## 11. 引用

- [spec.md §FR-002](../spec.md)（Tool 必须经沙箱校验）
- [spec.md §FR-004](../spec.md)（HTTP Tool 与 Sandbox 联动）
- [spec.md §SC-005](../spec.md)（白名单缺一个 host → Tool 返回 sandbox 错误）
- [research.md R-03](../research.md)（Shell 黑名单策略）
- [research.md R-11](../research.md)（HTTP Tool 沙箱复用）
- [CLAUDE.md §9.4](../../../CLAUDE.md)（Sandbox 抽象设计）
