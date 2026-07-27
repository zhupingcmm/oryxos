# 契约：Sandbox 接口（Tool 的安全护栏）

**目的**：定义 Tool 副作用的"前置校验"契约 —— `Sandbox.enforce()` 的语义、四种 ActionType 的处理策略、白名单匹配规则。这是 Agent OS 体系最核心的安全抽象。
**创建日期**：2026-07-26
**最后修订**：2026-07-27（007-sandbox-whitelist 落地后修正）
**特性**：[spec.md §FR-002 / §FR-004 / §SC-005](../spec.md) | [research.md R-03 / R-11](./../research.md)
**前置**：[Sandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/Sandbox.java) | [WhitelistSandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java) | [CLAUDE.md §9.4](../../../CLAUDE.md)
**后续**：007-sandbox-whitelist 把 §3.2 / §3.3 从 no-op 升级为真实白名单实现

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

public record SandboxAction(ActionType type, String target) {
    public SandboxAction {                                  // compact ctor
        if (type == null) throw new NullPointerException("type must not be null");
        if (target == null) throw new NullPointerException("target must not be null");
        if (target.isBlank()) throw new IllegalArgumentException("target must not be blank");
    }
}

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
    public SandboxViolationException(SandboxAction action, String reason) { ... }
}
```

> **字节级不变契约**（spec NFR-004 / SC-007 / 宪法 §VII）：
> 5 个核心契约 face MUST 字节级不变 —— 任何对外部可见的签名变化必须经过显式契约升级流程。
> SandboxApiCompatibilityTest 反射断言（14 断言）永久守护。
> 详见 [SandboxApiCompatibilityTest.java](../../../oryxos-tool/src/test/java/io/oryxos/tool/sandbox/SandboxApiCompatibilityTest.java)。

---

## 2. 4 种 ActionType 的核心阶段策略

| ActionType | 核心阶段策略 | 扩展阶段路径 |
|-----------|------------|------------|
| `HTTP_REQUEST` | **完整实现**：`WhitelistSandbox` host 后缀匹配 + IP 拒绝（含 IPv6 / zone-id / mapped-IPv4） | 按运营者审计日志迭代规则 |
| `FILE_READ` | **完整实现**：`WhitelistSandbox` 按 `allowed-paths` 前缀匹配（normalize + 穿越防护 + 绝对路径拒绝） | 容器化（namespace + cgroups + seccomp） |
| `FILE_WRITE` | **完整实现**（与 FILE_READ 同源策略） | 同 FILE_READ |
| `SHELL_COMMAND` | **完整实现**：`WhitelistSandbox` 按 `allowed-commands` 首 token 大小写不敏感匹配；ShellTool 同步走 `dangerous-commands` 黑名单兜底（line 72 前置 line 78 sandbox） | 容器化 |

**双层防御顺序（007 后硬约束，spec FR-013）**：

```text
ShellTool.execute(command):
  1. 校验 dangerous-commands 黑名单（sandbox.shell.dangerous-commands）    ← line 72
     → 命中：return ToolResult.error("shell command blocked: <cmd> (dangerous-commands)")
  2. 校验 sandbox.enforce(SHELL_COMMAND, command)                          ← line 78
     → 不在 allowed-commands：抛 SandboxViolationException → 兜底转 ToolResult.error("sandbox violation: ...")
```

**fail-closed 默认（spec FR-011 / 宪法 §VII）**：任何 ActionType 的白名单为空列表 → 任何 target 拒绝。

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
4. host 是 IP 字面量（InetAddress.getAllByName(host) 等同于 host，或 host 含 ':' / '[' / ']' / '%' 字面 IPv6 特征）
   → 抛 SandboxViolationException("ip literal rejected: <host>")
5. host 后缀不在 allowed-domains 中（whitelist: ["localhost", ".example.com", "api.deepseek.com"]）
   → 抛 SandboxViolationException("host not in allowed-domains: <host>")
   注：匹配是 host.toLowerCase(Locale.ROOT) 后做 .endsWith(<lowercased-allowed-domain>)，
   因此 "QYAPI.WEIXIN.QQ.COM" 命中 "qyapi.weixin.qq.com"
6. 全部通过 → 静默返回
```

**Whitelist 来源**：`SandboxProperties.http.allowed-domains`（YAML 数组；精确域名或 `.suffix` 后缀匹配）。

**示例配置**（`.oryxos/config/application.yaml`）：

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:
          - localhost          # WireMock 测试
          - 127.0.0.1          # WireMock 测试
          - .oryxos.dev        # 通配后缀
          - api.deepseek.com
          - api.moonshot.cn
```

**IPv6 字面量拒绝规则（007 阶段补强，SC-007）**：

| host 字面形态 | 规则 |
| ------------ | ------ |
| `192.168.1.100` | InetAddress.equals 命中 → IP 字面拒绝 |
| `[fe80::1]` | 含 `[` / `]` / `:` → IP 字面拒绝（带 zone-id 同理） |
| `[fe80::1%eth0]` | 含 `%`（zone identifier）→ IP 字面拒绝 |
| `[::1]:8080` | IPv6 loopback → IP 字面拒绝 |
| `[::ffff:192.168.1.1]` | IPv4-mapped IPv6 → IP 字面拒绝 |
| `localhost` | DNS 解析后为 127.0.0.1，但 InetAddress.getAllByName("localhost") ≠ "localhost" 字面 → 走域名匹配 |

### 3.2 `enforce(FILE_READ, path)` / `enforce(FILE_WRITE, path)`（007 升级为真实白名单）

**校验顺序**：

```text
1. target 空白/空字符串 → SandboxAction ctor 抛 IllegalArgumentException
2. path 是绝对路径（Path.getRoot() != null）
   → 抛 SandboxViolationException("absolute path not allowed: <path>")
   （核心阶段原则：Agent 只读工作区相对路径，绝对路径放开会扩大攻击面）
3. Path.normalize() 解析后含 ".." 段
   → 抛 SandboxViolationException("path traversal detected: <path>")
4. normalized path 不以任何 allowed-paths 前缀开头
   → 抛 SandboxViolationException("path not in allowed-paths: <path>")
5. 全部通过 → 静默返回
```

**跨平台注意**：Windows 上 `Path.of("/etc/passwd")` 实际为 `\etc\passwd`（drive-relative，仍有 root）；测试需用相对路径（如 `../etc/passwd`）才能精准触发"穿越防护"分支。

**示例配置**：

```yaml
oryxos:
  tool:
    sandbox:
      file:
        allowed-paths:
          - .                   # 当前工作区
          - ./workspace
          - /opt/agent-data     # 仅 Unix 路径
```

**fail-closed**：allowed-paths 空 → 任何路径拒绝。

### 3.3 `enforce(SHELL_COMMAND, command)`（007 升级为真实白名单）

**校验顺序**：

```text
1. target 空白/空字符串 → SandboxAction ctor 抛 IllegalArgumentException
2. 取首 token（trim + split("\\s+", 2)[0]，case-insensitive）
   不在 allowed-commands 列表中
   → 抛 SandboxViolationException("command not in allowed-commands: <token>")
3. 通过 → 静默返回
```

**示例配置**：

```yaml
oryxos:
  tool:
    sandbox:
      shell:
        allowed-commands:
          - git
          - ls
          - cat
          - echo
          - python    # daily-tech-news Demo 需要
        dangerous-commands:    # ShellTool 黑名单兜底（双层防御上层）
          - rm
          - mkfs
          - dd
          - shutdown
          - reboot
```

**ShellTool 双层防御**（[ShellTool.java line 72 vs line 78](../../../oryxos-tool/src/main/java/io/oryxos/tool/shell/ShellTool.java)）：

```java
// ShellTool.execute(command) 内部（精简示意）
String firstToken = command.trim().split("\\s+", 2)[0];
// line 72:  黑名单兜底（即使 sandbox 配错，rm 等永不通过）
if (sandboxProperties.getShell().getDangerousCommands().stream()
        .anyMatch(d -> d.equalsIgnoreCase(firstToken))) {
    return ToolResult.error("shell command blocked: " + firstToken + " (dangerous-commands)");
}
// line 78:  沙箱白名单
sandbox.enforce(new SandboxAction(ActionType.SHELL_COMMAND, command));
// 后续：ProcessBuilder 启动子进程
```

**fail-closed**：allowed-commands 空 → 任何命令拒绝。

> **与 005 阶段差异**：005 阶段 SHELL_COMMAND 在 Sandbox 内走 no-op，黑名单完全靠 Tool 层兜底；
> 007 阶段升级为 Sandbox 真实白名单后，双层防御变成可独立审计的两道闸（audit 行 success=false 双源）。

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

**WebhookNotifyAdapter 升级（007 阶段）**：004 阶段留 `// TODO: 走 sandbox` 注释位；007 阶段在 `send()` 内部首行加入 `sandbox.enforce(HTTP_REQUEST, url)`，覆盖 9 个内置 Tool 出站（WebhookNotifyAdapter / HttpGetTool / HttpPostTool / NotifyTool）。

### 4.2 异常处理

`SandboxViolationException` 抛到 `DefaultToolExecutor`：

```java
try {
    result = tool.execute(arguments);
} catch (SandboxViolationException ex) {
    return ToolResult.error("sandbox violation: " + ex.getReason()
        + " (" + ex.getType() + ": " + ex.getTarget() + ")");
} catch (RuntimeException ex) {
    return ToolResult.error("tool execution failed: " + ex.getMessage());
}
```

**审计**：`SandboxViolationException` 也写 1 行 `tool_invocations`（success=false，errorMessage 含 `reason` + `target`，**不含** stack trace —— SC-006 端到端断言）。

### 4.3 校验失败透传语义

LLM 下一轮看到：

```text
success=false,
errorMessage="sandbox violation: command not in allowed-commands (SHELL_COMMAND: rm -rf /)"
```

LLM 会自行调整（换命令 / 改方案）。

---

## 5. 配置契约

### 5.1 `SandboxProperties`（007 阶段扩展）

```java
@ConfigurationProperties(prefix = "oryxos.tool.sandbox")
public class SandboxProperties {
    private Http http = new Http();
    private File file = new File();
    private Shell shell = new Shell();
    // getters / setters（setter 兜底 null → Collections.emptyList()）

    public static class Http {  // 字段 allowedDomains
        private List<String> allowedDomains = new ArrayList<>();
        public List<String> getAllowedDomains() { return allowedDomains; }
        public void setAllowedDomains(List<String> v) {
            this.allowedDomains = (v == null) ? new ArrayList<>() : v;
        }
    }
    public static class File {  // 字段 allowedPaths
        // 同上结构
    }
    public static class Shell {  // 字段 allowedCommands + dangerousCommands
        // 同上结构
    }
}
```

> **007 阶段变更**：§3.1 的 `allowed-hosts` 重命名为 `allowed-domains`（拼写修正，反映"域名/后缀"语义）；
> `Shell` 子类新增 `allowedCommands`；`File` 子类新增 `allowedPaths`。

### 5.2 默认值

```yaml
# application.yaml 内置默认（fail-closed 优先：所有白名单空 → 所有请求拒绝）
# 运营者必须显式覆盖才能放行；demo 场景覆盖详见 docs/quickstart.md
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains: []    # 005/004 阶段曾默认 localhost/127.0.0.1；007 改为 fail-closed
      file:
        allowed-paths: []
      shell:
        allowed-commands: []
        dangerous-commands:    # Tool 层黑名单兜底，007 阶段保留
          - rm
          - mkfs
          - dd
          - shutdown
          - reboot
          - wget
          - curl
          - chmod
          - chown
          - mv
          - cp
```

### 5.3 运营者覆盖

运营者在 `.oryxos/config/application.yaml` 显式覆盖：

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:
          - localhost
          - api.deepseek.com
      shell:
        allowed-commands:
          - git
          - ls
          - python
        dangerous-commands:
          - rm
          - mkfs
```

---

## 6. 不变量（Invariants）

- **I-SB-1**：`sandbox.enforce()` 在 Tool 副作用**之前**调用，不允许先执行再校验。
- **I-SB-2**：校验失败抛 `SandboxViolationException`（继承 `RuntimeException`），由 `DefaultToolExecutor` 捕获并转 `ToolResult.error`。
- **I-SB-3**（007 重写）：4 ActionType 全部由 `WhitelistSandbox` 真实实现；不再有 no-op 路径。
- **I-SB-4**：`HTTP_REQUEST` 白名单匹配规则：精确域名 OR `.suffix` 后缀通配（如 `.oryxos.dev` 匹配 `api.oryxos.dev`）；大小写不敏感；不支持端口号通配（`:*`）。
- **I-SB-5**：host 是 IP 字面量**直接拒绝**，不允许白名单放行（防止 SSRF 绕过）；含 IPv4 / IPv6 / zone-id / IPv4-mapped-IPv6 全部拒绝。
- **I-SB-6**：URL 解析失败 / scheme 不支持（file://、gopher:// 等）一律拒绝。
- **I-SB-7**（007 新增）：**fail-closed 默认** —— 任何 ActionType 的白名单为空列表 → 任何 target 拒绝；不允许"白名单未配 = 默认放行"的隐式语义。
- **I-SB-8**（007 新增）：`SHELL_COMMAND` 校验取首 token（trim + split `\\s+`），与 `allowed-commands` 做大小写不敏感匹配；不允许"完整字符串包含"或"子串包含"。
- **I-SB-9**（007 新增）：`FILE_*` 校验先 `Path.normalize()`，含 `..` 段即拒绝穿越；绝对路径直接拒绝（核心阶段 Agent 只走工作区相对路径）。
- **I-SB-10**（007 新增）：`ShellTool` 双层防御顺序固定 —— `dangerous-commands` 黑名单（line 72）先于 `sandbox.enforce`（line 78）；两层各自独立审计。
- **I-SB-11**（007 新增）：5 个核心契约 face（`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `SandboxProperties`）字节级不变（spec NFR-004 / SC-007 / 宪法 §VII），由 `SandboxApiCompatibilityTest` 反射断言守护。

---

## 7. 性能特性

**007 阶段实测**（[SandboxPerformanceBenchmarkIT.java](../../../oryxos-tool/src/test/java/io/oryxos/tool/sandbox/SandboxPerformanceBenchmarkIT.java)，4 ActionType × 1000 warm + 1000 measured）：

| ActionType | P50 | P95 | P99 | 备注 |
| ---------- | --- | --- | --- | ---- |
| `HTTP_REQUEST` | ~5.1 μs | ~6.4 μs | ~14.3 μs | 含 InetAddress.getAllByName 调用 |
| `FILE_READ` | ~3.2 μs | ~3.5 μs | ~8.6 μs | 含 Path.normalize |
| `FILE_WRITE` | ~3.0 μs | ~3.2 μs | ~8.5 μs | 同 FILE_READ |
| `SHELL_COMMAND` | ~3.4 μs | ~5.4 μs | ~12.7 μs | 字符串 trim + split |

- 单次 `enforce()` 调用开销：**P95 ≤ 6.4 μs**（PRD 预算 5ms；远超）
- `WhitelistSandbox` 是 Spring 单例；`allowedDomains` / `allowedPaths` / `allowedCommands` 是不可变 List，启动期加载
- InetAddress 解析受 DNS 抖动影响；高频场景下可加本地缓存（**不**在本 spec 范围）
- 性能门禁：CI runner 容许 P95 ≤ 30ms（性能趋势监测，不作为硬阻断）

---

## 8. 测试矩阵

| 测试 | 期望 | 来源 |
| ---- | ---- | ---- |
| `http_allowed_domain_passes` | `enforce(HTTP_REQUEST, "http://api.deepseek.com")` 不抛 | SandboxEnforcementIntegrationTest |
| `http_unknown_host_throws` | 抛 `SandboxViolationException("host not in allowed-domains: example.com")` | SandboxEnforcementIntegrationTest |
| `http_ip_literal_v4_rejected` | 抛 `SandboxViolationException("ip literal rejected: 192.168.1.100")` | WhitelistSandboxTest |
| `http_ip_literal_v6_brackets_rejected` | `enforce(HTTP_REQUEST, "http://[fe80::1]/hook")` 抛（"IP-literal"） | WhitelistSandboxTest（007 阶段补强） |
| `http_ip_literal_v6_loopback_rejected` | `[::1]:8080` 抛 IP-literal | WhitelistSandboxTest |
| `http_ip_literal_v6_zone_id_rejected` | `[fe80::1%eth0]` 抛 IP-literal | WhitelistSandboxTest |
| `http_ip_literal_v6_mapped_ipv4_rejected` | `[::ffff:192.168.1.1]` 抛 IP-literal | WhitelistSandboxTest |
| `http_localhost_in_whitelist` | WireMock 场景：白名单含 `localhost`/`127.0.0.1`，测试通过 | SandboxEnforcementIntegrationTest |
| `http_invalid_scheme_throws` | `file:///etc/passwd` 抛 `SandboxViolationException("unsupported scheme: file")` | WhitelistSandboxTest |
| `http_gopher_scheme_throws` | `gopher://example.com/` 抛 | WhitelistSandboxTest |
| `http_ftp_scheme_throws` | `ftp://example.com/` 抛 | WhitelistSandboxTest |
| `http_suffix_match_case_insensitive` | `QYAPI.WEIXIN.QQ.COM` 命中 `qyapi.weixin.qq.com` 白名单 | WhitelistSandboxTest |
| `http_fail_closed_empty_whitelist_blocks_all` | `WhitelistSandbox(List.of())` 任何 URL 拒绝 | WhitelistSandboxTest |
| `file_read_absolute_path_rejected` | `/etc/passwd` 抛 `SandboxViolationException("absolute path not allowed")` | FilePathSandboxTest + CrossActionTypeSandboxIT |
| `file_read_traversal_rejected` | `../etc/passwd` 抛 `SandboxViolationException("path traversal detected")` | FilePathSandboxTest |
| `file_read_not_in_whitelist_rejected` | 任意未匹配 allowed-paths 路径抛 `not in allowed-paths` | FilePathSandboxTest |
| `file_read_in_whitelist_passes` | `allowed-paths=["./workspace"]` 下 `./workspace/notes.md` 不抛 | FilePathSandboxTest |
| `file_fail_closed_empty_whitelist_blocks_all` | `WhitelistSandbox(_, List.of(), _)` 任何 FILE 路径拒绝 | CrossActionTypeSandboxIT |
| `shell_dangerous_blocked_at_tool` | `ShellTool.execute("rm -rf /")` 返回 `ToolResult.error("shell command blocked: rm (dangerous-commands)")` | SandboxEnforcementIntegrationTest（007 阶段 T011） |
| `shell_safe_passes_sandbox` | `enforce(SHELL_COMMAND, "git status")` 不抛（首 token `git` ∈ allowed-commands） | ShellCommandSandboxTest |
| `shell_first_token_case_insensitive` | `enforce(SHELL_COMMAND, "GIT status")` 不抛（`GIT`.toLowerCase 命中 `git`） | ShellCommandSandboxTest |
| `shell_extra_args_allowed` | `enforce(SHELL_COMMAND, "git -C /repo log")` 不抛（仅看首 token） | ShellCommandSandboxTest |
| `shell_command_not_in_whitelist` | `enforce(SHELL_COMMAND, "python -m foo")` 未配 python 抛 `not in allowed-commands` | ShellCommandSandboxTest |
| `shell_fail_closed_empty_whitelist_blocks_all` | `WhitelistSandbox(_, _, List.of())` 任何命令拒绝 | CrossActionTypeSandboxIT |
| `shell_blacklist_precedes_whitelist` | `rm` 同时配入 dangerous-commands 和 allowed-commands → Tool 层先拒，errorMessage 不含 "not in allowed-commands" | SandboxEnforcementIntegrationTest（T011） |
| `audit_error_message_no_stack_trace` | SC-006：sandbox violation 进入 audit 时不带 `\n\tat io.oryxos...` 调用链 | CrossActionTypeSandboxIT |
| `api_byte_stable` | 5 个核心契约 face 反射断言 14 项全过 | SandboxApiCompatibilityTest |
| `performance_p95_under_budget` | 4 ActionType P95 ≤ 30ms CI 预算（实测 ≤ 6.4 μs） | SandboxPerformanceBenchmarkIT |
| `cross_action_type_end_to_end` | HTTP/FILE/SHELL/NOTIFY 各正反例一次走 Tool → sandbox → 副作用隔离 | CrossActionTypeSandboxIT |
| `notify_sandbox_hook` | WebhookNotifyAdapter.send() 在 sandbox.enforce 通过后才发 HTTP | NotifySandboxEnforcementIT |
| `notify_disallowed_url_blocked` | 发送目标域名不在 allowed-domains → 返回 failure，WireMock 收不到 POST | NotifySandboxEnforcementIT |
| `notify_ipv6_url_blocked` | `http://[::1]:8080/hook` → IP-literal 拒绝 | NotifySandboxEnforcementIT |
| `notify_fail_closed_default` | 空白白名单下任何 webhook 发送失败 | NotifySandboxEnforcementIT |

---

## 9. 升级路径（不在本 spec 范围）

```text
核心阶段（007 后状态，应用层 WhitelistSandbox 全部 4 ActionType 真实实现）
  ├─ HTTP_REQUEST ✓ 已落地（含 IPv6 全部形态）
  ├─ FILE_READ ✓ 已落地（normalize + 穿越防护 + 绝对路径拒绝）
  ├─ FILE_WRITE ✓ 已落地（与 FILE_READ 同源）
  └─ SHELL_COMMAND ✓ 已落地（首 token 大小写不敏感 + ShellTool 黑名单兜底）

扩展阶段
  └─ 容器级隔离（namespace + cgroups + seccomp）—— 接口不变，WhitelistSandbox 退化为兜底

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
- [spec.md §FR-011](../spec.md)（fail-closed 默认）
- [spec.md §FR-013](../spec.md)（sandbox 拦截规则）
- [spec.md §SC-005](../spec.md)（白名单缺一个 host → Tool 返回 sandbox 错误）
- [spec.md §SC-007](../spec.md)（Sandbox 5 个核心契约 face 字节级不变）
- [research.md R-03](../research.md)（Shell 黑名单策略 + 双层防御）
- [research.md R-11](../research.md)（HTTP Tool 沙箱复用）
- [CLAUDE.md §9.4](../../../CLAUDE.md)（Sandbox 抽象设计）
- [007-sandbox-whitelist/spec.md](../../007-sandbox-whitelist/spec.md)（007 实施 spec）
- [007-sandbox-whitelist/contracts/sandbox-whitelist.md](../../007-sandbox-whitelist/contracts/sandbox-whitelist.md)（007 阶段新增契约面）
