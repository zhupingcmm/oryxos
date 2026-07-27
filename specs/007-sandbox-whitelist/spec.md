# 功能规格说明书：Sandbox 白名单实现

**特性分支**：`007-sandbox-whitelist`
**创建日期**：2026-07-27
**状态**：草稿
**输入**：用户描述："第24节需求：Sandbox 白名单实现——把工具执行前那道安全校验的墙真正砌起来。核心阶段做应用层白名单校验，接口先行、能撑住未来容器/microVM。前面几节已经把 Sandbox 接口/动作值对象/动作类型/违规异常立好，并在文件/命令/HTTP 三类内置工具的执行首行接入了校验调用，当前挂的是放行一切只记告警的临时实现。这一节把临时实现换成真正的白名单实现，并把还只留了注释位的通知工具也接上校验。"

> **范围说明**：本 spec 是 OryxOS 核心能力第四项「Plugin Tool」的安全校验层收口 ([CLAUDE.md §9.4](../CLAUDE.md))。`Sandbox` 接口 + `SandboxAction` + `ActionType` + `SandboxViolationException` 已在 005-tool-system 阶段定型，本 spec 只做"应用层白名单真正落地"：①把现有 `WhitelistSandbox` 在 `FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` 三类上的 no-op 替成真正的白名单校验；②扩展 `SandboxProperties` 暴露 `file.allowed-paths` 与 `shell.allowed-commands` 配置；③把 `WebhookNotifyAdapter` 的 HTTP 校验固化为端到端契约；④保证接口不变（接口先行原则），让容器 / microVM 等升级路径在扩展阶段只换实现不动调用方。
>
> **已有事实**（[CLAUDE.md §9.4](../CLAUDE.md) + [CLAUDE.md §18](../CLAUDE.md) §V 边界 + [004-notify-channel spec](../004-notify-channel/spec.md)）：
> - `Sandbox` 接口（`io.oryxos.tool.sandbox.Sandbox`）+ `SandboxAction` record + `ActionType` enum（4 值：`FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST`）+ `SandboxViolationException`（RuntimeException 子类）已落地
> - 9 个内置 Tool + MCP 适配器在执行首行已接入 `sandbox.enforce(SandboxAction)` 调用
> - `WhitelistSandbox` 当前实现只对 `HTTP_REQUEST` 做 host 后缀匹配 + scheme 校验 + IP 字面拒绝；`FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` 三类是 no-op（注释明确"扩展阶段按 allowed-paths / allowed-commands 补校验"）
> - `SandboxProperties` 当前只暴露 `http.allowed-domains`
> - `ShellTool` 自带 `dangerousCommands` 黑名单（`ShellToolProperties`）—— 黑名单逻辑不属于 Sandbox 层，本 spec 把它归位为"Tool 自己的预校验"，Sandbox 层负责正向白名单（可执行命令白名单 / 文件路径白名单）
> - `WebhookNotifyAdapter.send()` 已在 HTTP 请求前调用 `sandbox.enforce(HTTP_REQUEST, url)` —— 本 spec 把这条路径的契约码化为验收场景
> - **不使用** `SecurityManager`（JDK 17 起废弃，JDK 21 不可用，[CLAUDE.md §18](../CLAUDE.md) 红线）

---

## 用户场景与测试 *（必填）*

### 用户故事 1 — 文件 IO 路径白名单（P1）🎯 MVP

企业用户跑 Agent 时，LLM 通过 `file_read` / `file_write` / `file_list` Tool 接触工作区。业务方担心 Agent 误读 `/etc/passwd` 或写 `/root/.ssh/authorized_keys`。白名单 MUST 限定 Agent 只能落在业务方预先声明的工作区（例如 `.oryxos/workspace/` 或 `/home/agent/workspace/`），路径越界必须被拦截，且路径含 `..` 等 traversal 模式时直接拒绝。

**为什么是这个优先级**：`file_read` / `file_write` 是 Agent 接触本地文件系统的两个口子；如不限制，Agent 可读敏感文件（`/etc/shadow`、`~/.aws/credentials`）或破坏系统。MVP 的核心是"业务方声明工作区 → Agent 只能落该工作区"。本能力 + 现有 `dangerousCommands` 黑名单 = 完整应用层白名单。P1 一旦跑通，三个验收 Demo 中"每日 GitHub 日报"（[CLAUDE.md §11](../CLAUDE.md)）的"脚本信任边界"才有应用层兜底（容器隔离放扩展阶段）。

**独立测试**：Profile 不配 `memo.backend`；`application.yaml` 配 `oryxos.tool.sandbox.file.allowed-paths=[".oryxos/workspace"]`；调 `FileReadTool.execute(path="/etc/passwd")` → 抛 `SandboxViolationException` → `tool_invocations` 写一行 `success=false`；调 `FileReadTool.execute(path=".oryxos/workspace/notes.md")` → 通过。路径含 `..`（如 `.oryxos/workspace/../../../etc/passwd`）→ 拒绝。

**验收场景**：

1. **假设** `SandboxProperties.file.allowed-paths=[".oryxos/workspace"]`，**当** 调 `FileReadTool.execute(path="/etc/passwd")`，**那么** 抛 `SandboxViolationException`，errorMessage 含 `"path '/etc/passwd' not in allowed-paths"`，**并且** `DefaultToolExecutor` 写一行 `tool_invocations(success=false, error_message=...)`。
2. **假设** 同上，**当** 调 `FileReadTool.execute(path=".oryxos/workspace/notes.md")`，**那么** 通过校验，正常返回文件内容。
3. **假设** 同上，**当** 调 `FileReadTool.execute(path=".oryxos/workspace/../../../etc/passwd")`（含 `..`），**那么** 抛 `SandboxViolationException`，errorMessage 含 `"path traversal detected"`（即使最终解析路径在白名单内也拒绝）。
4. **假设** `file.allowed-paths` 未配置（空列表），**当** 任意 `FileReadTool` / `FileWriteTool` / `FileListTool` 调用，**那么** 抛 `SandboxViolationException`（fail-closed 默认）。

---

### 用户故事 2 — Shell 命令白名单（P1）

企业用户跑 Agent 时，LLM 通过 `shell` Tool 执行命令。`ShellTool` 自带的 `dangerousCommands` 黑名单只能拦截首 token 命中 `rm` / `shutdown` / `reboot` / `dd` / `mkfs` 这类已知名词，无法覆盖"`curl | bash` 这类借壳执行"。业务方需要正向白名单：LLM 只能调业务方声明的安全命令（如 `git` / `python3` / `pytest` / `gh`）。

**为什么是这个优先级**：黑名单 = 已知威胁库（永远追不完）；白名单 = 默认拒绝 + 业务方显式声明。这是从"被攻击面最大化"切到"被攻击面最小化"的根本。MVP 的核心是 `shell.allowed-commands` + 任何首 token 不在白名单内一律拒绝。**注意**：本 spec 的 Sandbox 白名单与 `ShellToolProperties.dangerousCommands` 黑名单是两个独立护栏——黑名单在 ShellTool 内做"已知威胁兜底"，白名单在 Sandbox 层做"默认拒绝"。两者并存，黑名单先于白名单检查。

**独立测试**：`application.yaml` 配 `oryxos.tool.sandbox.shell.allowed-commands=["git", "python3", "pytest"]`；调 `ShellTool.execute(command="git status")` → 通过；调 `ShellTool.execute(command="rm -rf /")` → 拒（黑名单先拦）；调 `ShellTool.execute(command="curl https://example.com")` → 拒（白名单未声明 `curl`）；`shell.allowed-commands` 未配置 → 全部 Shell 命令拒（fail-closed）。

**验收场景**：

1. **假设** `shell.allowed-commands=["git", "python3"]`，**当** 调 `ShellTool.execute(command="git status")`，**那么** 通过校验。
2. **假设** 同上，**当** 调 `ShellTool.execute(command="rm -rf /")`，**那么** 先被 `dangerousCommands` 黑名单拦截，返回 `ToolResult.error("shell command blocked: rm is in dangerous-commands")`（不走到 Sandbox）。
3. **假设** 同上，**当** 调 `ShellTool.execute(command="curl https://example.com")`，**那么** 走到 `Sandbox.enforce(SHELL_COMMAND, "curl ...")` → 首 token `curl` 不在白名单 → 抛 `SandboxViolationException`，errorMessage 含 `"command 'curl' not in allowed-commands"`。
4. **假设** `shell.allowed-commands` 未配置，**当** 任意 Shell 调用（含 `git status`），**那么** Sandbox 拒（fail-closed）；`dangerousCommands` 黑名单仅对已声明黑名单项生效（不与白名单 fail-closed 互锁）。

---

### 用户故事 3 — Notify 工具经 WebhookNotifyAdapter 走 Sandbox（P2）✅ 已落地验证

`notify` Tool 通过 `WebhookNotifyAdapter.send(channel, content)` 发 HTTP POST。WebhookNotifyAdapter 在 HTTP 请求前 MUST 调用 `Sandbox.enforce(HTTP_REQUEST, url)`（spec FR-007），拒绝域外 host。本 spec 把这条已有路径的契约码化、加入端到端测试覆盖，确保 Notify 工具的 sandbox 拦截不会被未来重构意外摘除。

**为什么是这个优先级**：`notify` 是出站消息的主要通道（企业微信 / 飞书 / 钉钉 webhook）；如 sandbox 拦截被摘除，Agent 可往任意域 POST 内容（含敏感数据）。本 US 不引入新功能，只"把已有路径固化为契约 + 测试"。MVP 不依赖本 US（P1 文件 / shell 已覆盖基本出站风险）；P2 是补完"四大 ActionType 全部走 Sandbox"契约完整性。

**独立测试**：Profile 配 `notify_channels=[{name: "wechat", type: "webhook", url: "https://qyapi.weixin.qq.com/..."}]`；调 `NotifyTool.execute(content="hi", channel="wechat")` → 通过；Profile 改 `url=https://evil.example.com/hook` → 调 `NotifyTool.execute(...)` → WebhookNotifyAdapter sandbox 拦截 → 返回 `NotifyResult(success=false, errorMessage="sandbox violation: ...")` → `NotifyTool` 把错误归类为 `error_class="sandbox_violation"`。

**验收场景**：

1. **假设** `http.allowed-domains=["qyapi.weixin.qq.com"]`，**当** `NotifyTool` 经 `WebhookNotifyAdapter` 发往 `https://qyapi.weixin.qq.com/...`，**那么** sandbox 通过 → HTTP POST 实际发送 → 2xx → `NotifyResult.success=true`。
2. **假设** 同上但 channel 配置的 url 为 `https://evil.example.com/hook`，**当** `NotifyTool` 触发，**那么** WebhookNotifyAdapter 抛 `SandboxViolationException` → 捕获返回 `NotifyResult(success=false, errorMessage="sandbox violation: host 'evil.example.com' not in allowed-domains")` → `NotifyTool` `error_class="sandbox_violation"`。
3. **假设** `NotifyTool.broadcast()` 多通道场景，**当** 其中一条通道 url 越域，**那么** 该条 `success=false(error_class=sandbox_violation)`，其他正常通道 `success=true`；聚合 ToolResult 走 `partial: ...` 分支（spec FR-007 聚合语义）。

---

### 用户故事 4 — 跨 ActionType 集成审计 + 接口稳定性（P2）

业务方 / 审计员需要从 `tool_invocations` 表完整还原"哪个 Agent / 哪次调用 / 哪个 ActionType / 被 Sandbox 拦截的原因"。所有四类 ActionType 的拦截失败 MUST 走 `DefaultToolExecutor` 既有审计路径，写一行 `success=false, error_message="sandbox violation: ..."`。

同时，业务方 / 架构师需要保证：未来升级到容器（namespace + cgroups + seccomp）或 microVM（Firecracker / Kata / gVisor）时，**只换 `Sandbox` 实现即可**，`Tool` 调用方零改动。本 spec 在端到端测试 + 接口不变性上明确这条契约。

**验收场景**：

1. **假设** 任意 ActionType（FILE/SHELL/HTTP）+ 越界请求，**当** Tool 调用执行前，**那么** `tool_invocations` 写入一行 `success=false, error_message="sandbox violation: <具体原因>", duration_ms=<P95 ≤ 5ms>`。
2. **假设** 升级路径（容器实现类 `ContainerSandbox implements Sandbox`），**当** 通过 Spring `@Primary` 替换 `WhitelistSandbox`，**那么** 9 个内置 Tool + MCP 适配器 + WebhookNotifyAdapter 调用方零改动。

---

## 边界情况

- **路径含 `..` traversal**：必须拒绝，即使最终解析后落在白名单内（如 `.oryxos/workspace/../workspace/notes.md` 解析后等价 `.oryxos/workspace/notes.md`）。`WhitelistSandbox` MUST 在路径规范化（`Path.normalize()`）前就拒绝，避免"通过 traversal 进入"和"通过 traversal 走出"。
- **绝对路径 vs 相对路径**：传入 `target` 是绝对路径（`/etc/passwd`）直接拒绝；是相对路径（`./notes.md` / `../etc/passwd`）先规范化为绝对路径再匹配白名单前缀。
- **路径前缀匹配**：白名单 `["/home/agent/workspace"]`，路径 `/home/agent/workspace-evil/notes.md` MUST 不被允许（必须 `equals` 或 `startsWith("白名单 + '/'")` 严格匹配，避免"白名单前缀被绕过"）。
- **命令含 shell 元字符（`;` / `&&` / `|`）**：取首 token（`split("\\s+", 2)[0]`）做白名单匹配；元字符拼接不绕过白名单。
- **URL 含 IPv6 字面 `[::1]`**：WhitelistSandbox 的 `isIpLiteral()` MUST 识别 IPv6 字面拒绝。
- **空白名单（fail-closed）**：`http.allowed-domains=[]` / `file.allowed-paths=[]` / `shell.allowed-commands=[]` MUST 等价"全部拒绝"。这是宪法 §VII"Demo-First"的安全默认：业务方未声明前不许跑通。
- **重复配置（数组含 `null` / 空串）**：`SandboxProperties` MUST 跳过 `null` / 空串，与现有 `http.allowed-domains` 处理一致。
- **`SandboxAction.target` 含控制字符 / 极长字符串**：record compact constructor 已要求 `target.isBlank()` 抛 `IllegalArgumentException`；非空但超长（>4 KB） MUST 抛 `SandboxViolationException` 拒绝（防病态输入拖垮 IO）。
- **跨进程并发**：白名单校验无状态（`WhitelistSandbox` 不持有任何 mutable state），多线程安全。

---

## 需求 *（必填）*

### 功能需求

- **FR-001**：`Sandbox` 接口 MUST 保持不变（[CLAUDE.md §9.4](../CLAUDE.md) 接口先行原则）；`void enforce(SandboxAction action)` 是唯一公开方法；升级路径（容器 / microVM）只换实现不动调用方。
- **FR-002**：`WhitelistSandbox.enforce(HTTP_REQUEST)` MUST 真正校验：①scheme 必须是 `http` / `https`；②host 必须是后缀匹配 `http.allowed-domains` 任一项；③host 为 IPv4 / IPv6 字面 → 拒绝；④URL 解析失败 → 拒绝（spec 005 FR-007 + §3.1）。
- **FR-003**：`WhitelistSandbox.enforce(FILE_READ | FILE_WRITE)` MUST 真正校验：①target 路径含 `..`（任何 segment 命中）→ 拒绝 `path traversal detected`；②target 解析为绝对路径后必须等于任一 `file.allowed-paths` 项，或以 `<path> + '/'` 严格前缀开头（避免前缀绕过）；③`file.allowed-paths` 为空 → fail-closed 拒绝所有文件操作；④`target` 为 null / 空（已有 record compact constructor 校验）→ 抛 `IllegalArgumentException`。
- **FR-004**：`WhitelistSandbox.enforce(SHELL_COMMAND)` MUST 真正校验：①取首 token（`split("\\s+", 2)[0]`）lower-case 后必须在 `shell.allowed-commands` 任一项中（大小写不敏感）；②不在 → 拒绝 `command '<token>' not in allowed-commands`；③`shell.allowed-commands` 为空 → fail-closed 拒绝所有 Shell 调用。注意：本条**不取代** `ShellToolProperties.dangerousCommands` 黑名单；黑名单在 ShellTool 内做"已知威胁兜底"先于 Sandbox 校验（[CLAUDE.md §9.4](../CLAUDE.md) §9.7 表格）。
- **FR-005**：`SandboxProperties` MUST 扩展为 4 类配置：`http.allowed-domains`（已有）+ `file.allowed-paths`（新增）+ `shell.allowed-commands`（新增）+ `shell.dangerous-commands`（新增，把 `ShellToolProperties.dangerousCommands` 移过来做统一配置入口；保留 `ShellToolProperties.dangerousCommands` 作为兼容读源直到 008 阶段删除）。
- **FR-006**：所有 9 个内置 Tool + MCP 适配器 + `WebhookNotifyAdapter` 在执行首行 MUST 调 `sandbox.enforce(SandboxAction)`；调用方零容忍"绕过 Sandbox 直出"（[CLAUDE.md §9.4](../CLAUDE.md)）。
- **FR-007**：`NotifyTool` → `WebhookNotifyAdapter` 链路上 `sandbox.enforce(HTTP_REQUEST, channel.url())` MUST 在 HTTP POST 前调用（[004-notify-channel spec FR-007](../004-notify-channel/spec.md) 已落地，本 spec 验收 + 测试化）。
- **FR-008**：拦截失败 MUST 抛 `SandboxViolationException`（RuntimeException 子类）；`DefaultToolExecutor` 既有审计路径 MUST 捕获并写一行 `tool_invocations(success=false, error_message="sandbox violation: <reason>", duration_ms=...)`；errorMessage MUST 不含 stack trace（[CLAUDE.md §18](../CLAUDE.md) NFR-004）。
- **FR-009**：`SandboxViolationException.action` MUST 暴露原始 `SandboxAction`；审计行可选择性记录 `action.type`（FILE/SHELL/HTTP）便于审计分类查询。
- **FR-010**：核心阶段 MUST NOT 使用 `SecurityManager`（[CLAUDE.md §18](../CLAUDE.md) 红线 — JDK 17 起废弃，JDK 21 不可用）；核心阶段只用应用层白名单。升级路径：白名单 → 容器（namespace + cgroups + seccomp）→ microVM（Firecracker / Kata / gVisor），接口不变，扩展阶段替换 `WhitelistSandbox` 实现即可。
- **FR-011**：`SandboxProperties` 数组配置项 MUST 跳过 `null` 与空字符串（与现有 `http.allowed-domains` 处理一致）；`WhitelistSandbox` 构造器 MUST 防御 null 入参 → `List.of()`。

### 非功能需求

- **NFR-001**：`Sandbox.enforce()` 单次 wall-time P95 ≤ 5ms（健康依赖场景 = 无 IO 阻塞，纯字符串 / 路径操作）；包含路径规范化（`Path.normalize()`）+ 前缀匹配 + 元字符扫描。
- **NFR-002**：拦截信息 MUST 对 LLM 友好（[CLAUDE.md §18](../CLAUDE.md) NFR-004）：errorMessage 形如 `"sandbox violation: <reason>"`，**不**含 stack trace；stack trace 100% 进 `.oryxos/logs/oryxos-cli-error.log`。
- **NFR-003**：`WhitelistSandbox` MUST 无状态、可多线程并发安全；不持有任何 mutable 字段，构造期一次性拷贝配置为 `List.copyOf(...)`。
- **NFR-004**：接口稳定性 = `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `SandboxProperties` 这 5 个契约面 MUST 在 007 阶段**字节级不变**；扩展阶段实现可替换（如 `ContainerSandbox implements Sandbox`），调用方零改动。

### 关键实体

- **`Sandbox`**（接口）：公开方法 `void enforce(SandboxAction action)`；校验失败抛 `SandboxViolationException`。
- **`SandboxAction`**（record）：`{ActionType type, String target}`；compact constructor 校验 `type != null && target != null && !target.isBlank()`。
- **`ActionType`**（enum）：`FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` / `HTTP_REQUEST`；扩展阶段可加 `DB_QUERY` / `SMTP_SEND` 等。
- **`SandboxViolationException`**（RuntimeException 子类）：携带 `SandboxAction action` + `message`；走 `DefaultToolExecutor` 既有审计路径。
- **`SandboxProperties`**（`@ConfigurationProperties("oryxos.tool.sandbox")`）：4 类配置 = `http.allowed-domains`（已有）+ `file.allowed-paths`（新增）+ `shell.allowed-commands`（新增）+ `shell.dangerous-commands`（新增，兼容读源）。
- **`WhitelistSandbox`**（`@Component implements Sandbox`）：核心阶段唯一实现；按 ActionType 分发到 4 类校验逻辑。

---

## 成功标准 *（必填）*

### 可测量结果

- **SC-001**：`WhitelistSandbox` 对 4 类 ActionType（FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST）端到端集成测试全部通过：HTTP 越域拒 / FILE 越界拒 + `..` 拒 / SHELL 越名单拒 / 空白名单 fail-closed 拒；正向路径（白名单内）通过。
- **SC-002**：`SandboxProperties` 4 类配置生效：`http.allowed-domains` + `file.allowed-paths` + `shell.allowed-commands` + `shell.dangerous-commands` 各自绑定到 `application.yaml` 对应键；`SandboxConfig` 暴露为 Spring Bean。
- **SC-003**：拦截失败 → `tool_invocations` 100% 写一行 `success=false, error_message="sandbox violation: <reason>", duration_ms=<P95 ≤ 5ms>`，errorMessage 不含 stack trace（= [CLAUDE.md §18](../CLAUDE.md) NFR-004 可测断言）。
- **SC-004**：`mvn verify` 全绿（继承 005 + 006 + 本 spec 新增 Sandbox 集成测试）；`SandboxEnforcementIntegrationTest` 4 类场景全过。
- **SC-005**：跨 4 类 ActionType 集成测试 = `SandboxEnforcementIntegrationTest`（HTTP 越域 / FILE 越界 + `..` / SHELL 越名单 / Notify 域外）覆盖所有"拦截 → 审计"链路；每条测试断言 `tool_invocations` 1 行 + errorMessage 不含 stack trace。
- **SC-006**：`Sandbox.enforce()` 单次 wall-time P95 ≤ 5ms（含路径规范化 + 前缀匹配）；`SandboxPerformanceIT` 验证。
- **SC-007**：接口稳定性 = `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `SandboxProperties` 5 个契约面的 public API 在 007 完成后字节级不变（用 `api-guardian` / `japicmp` 类工具或简单 grep 验证）。

### 业务结果

- **SC-008**：业务方在配置 `oryxos.tool.sandbox.*` 后，Agent 跑通 Demo "每日 GitHub 日报"（CLAUDE.md §11）时，文件读 / 写 / 命令执行全部落在白名单内；任何越界（即使 LLM 试图通过 `..` traversal）被 Sandbox 拦截，审计可见。
- **SC-009**：升级路径明确（白名单 → 容器 → microVM）落地于 spec / README / 架构图；扩展阶段实现 `ContainerSandbox implements Sandbox` 时，9 个内置 Tool + MCP + WebhookNotifyAdapter 零改动。

---

## 假设

1. **`Sandbox` 接口先行**（[CLAUDE.md §9.4](../CLAUDE.md)）：核心阶段唯一实现 = `WhitelistSandbox`（应用层白名单）；扩展阶段实现容器 / microVM；接口不变。
2. **核心阶段不做容器隔离**（[CLAUDE.md §9.4](../CLAUDE.md) 升级路径）：应用层白名单是核心阶段唯一手段；扩展阶段才引入 Linux namespace + cgroups + seccomp / microVM。
3. **`SecurityManager` 不可用**（[CLAUDE.md §18](../CLAUDE.md) 红线 — JDK 17 起废弃，JDK 21 不可用）；核心阶段纯应用层校验，不依赖 JVM 安全管理器。
4. **`dangerousCommands` 黑名单保留为兼容读源**：`ShellToolProperties.dangerousCommands` 在 007 阶段保留（已有调用方），新配置走 `SandboxProperties.shell.dangerous-commands`（如配置在 SandboxProperties 则生效在 `WhitelistSandbox` 内），008 阶段再统一收敛。
5. **fail-closed 默认**（[CLAUDE.md §18](../CLAUDE.md) §9.6 宪法 §VII"Demo-First"安全默认）：业务方未配置 `allowed-*` 列表 = 全部拒绝；这与 `MemoryService` "未配后端抛错" 同模式。
6. **路径前缀严格匹配**：`/home/agent/workspace-evil` 不被 `/home/agent/workspace` 允许（避免白名单前缀绕过）。
7. **命令首 token 不区分大小写**：`git` / `GIT` / `Git` 等价匹配 `allowed-commands` 任一项（统一 lowercase 后比较）。
8. **Notify 工具的 Sandbox 钩已落地**（[004-notify-channel spec FR-007](../004-notify-channel/spec.md)）：`WebhookNotifyAdapter.send()` 已在 HTTP POST 前调 `sandbox.enforce(HTTP_REQUEST, channel.url())`；本 spec 不重复实现，只"固化契约 + 测试化"。
9. **本 spec 不引入新 Maven 模块**：Sandbox 抽象 + 实现都在既有 `oryxos-tool` 模块（宪法 §I 既定）；不改 9 模块边界。
10. **本 spec 实现状态盘点**：①Sandbox 接口 + 4 类 ActionType + SandboxViolationException + SandboxProperties (HTTP) —— 已在 005-tool-system 落地；②WhitelistSandbox 的 HTTP 实现 —— 已在 005-tool-system 落地；③WhitelistSandbox 的 FILE / SHELL 实现 —— **待落地**（007 核心工作）；④SandboxProperties 扩展 (file + shell) —— **待落地**；⑤Notify 链路契约固化 + 测试 —— **待落地**（路径已实现，测试化 + 文档化是本 spec 工作）；⑥升级路径文档 —— **待落地**。

---

## 不在范围内（Out of Scope）

- ❌ 容器隔离（Linux namespace + cgroups + seccomp）—— 扩展阶段；本阶段只接应用层白名单
- ❌ microVM 隔离（Firecracker / Kata Containers / gVisor）—— 扩展阶段
- ❌ `SecurityManager` 复活 —— JDK 21 不可用，红线
- ❌ Tool Policy 引擎（按 Profile / Agent 区分不同白名单）—— 扩展阶段，宪法 §II 多租户
- ❌ 多租户隔离的 `Sandbox` 多实例 —— 扩展阶段
- ❌ 白名单的运行时热更新（不动 Spring 上下文改配置）—— 扩展阶段
- ❌ Sandbox 拦截的可观测性 metrics（拦截计数 / 拦截率 / 拦截 P95）—— 扩展阶段
- ❌ 路径 glob 模式白名单（仅前缀匹配）—— 核心阶段仅 equals / 前缀；glob 放扩展阶段
- ❌ 命令白名单的参数级约束（只允许 `git status` 不允许 `git push`）—— 扩展阶段；核心阶段只控首 token
- ❌ 文件 IO 的 read-only / write-only 分离（FILE_READ 和 FILE_WRITE 各自独立白名单）—— 扩展阶段；核心阶段共享 `file.allowed-paths` 一个列表