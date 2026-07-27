# 研究文档：Sandbox 白名单实现

**目的**：把 spec.md 中所有 NEEDS CLARIFICATION 与技术决策汇总，按"决策 / 理由 / 备选"格式固化，给 plan.md 与 tasks.md 提供可追溯依据。
**创建日期**：2026-07-27
**特性**：[spec.md](./spec.md)
**前置文档**：[.specify/memory/constitution.md](../../.specify/memory/constitution.md) | [CLAUDE.md §9.4 / §18](../../CLAUDE.md) | [specs/005-tool-system/contracts/sandbox.md](../005-tool-system/contracts/sandbox.md) | [specs/005-tool-system/research.md R-03 / R-11](../005-tool-system/research.md) | [specs/004-notify-channel/spec.md FR-007](../004-notify-channel/spec.md)

> **已有决策继承**：[004-notify-channel spec FR-007](../004-notify-channel/spec.md) 已落地 `WebhookNotifyAdapter.send()` 在 HTTP POST 前调 `sandbox.enforce(HTTP_REQUEST, url)`；[005-tool-system/contracts/sandbox.md](../005-tool-system/contracts/sandbox.md) §5.1 已定义 `SandboxProperties` 应含 3 类子配置（`HttpConfig` / `ShellConfig` / `FileConfig`）但当前实现只落地了 `http.allowed-domains`（[SandboxProperties.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxProperties.java)）。本文件聚焦 007 阶段的"新增"决策点，继承 005 的 HTTP 实现 + Notify 路径契约。

---

## R-01：路径规范化与 `..` traversal 检测

**决策**：`WhitelistSandbox.enforce(FILE_READ | FILE_WRITE)` 用 JDK 内置 `Path.normalize()` 拒 `..` + 路径前缀严格匹配。

**算法**：

```text
1. raw = action.target()
2. normalized = Path.of(raw).normalize()
3. 若 normalized != Path.of(raw)  → 抛 "path traversal detected: <raw> -> <normalized>"
   （任何 .. / . 段被规范化移除即视为 traversal）
4. 若 normalized 是绝对路径（以 '/' 或盘符开头）→ 抛 "absolute path not allowed: <normalized>"
   （核心阶段只允许相对路径；业务方把工作区根配在 allowed-paths 内）
5. 取 resolved = workspaceRoot.resolve(normalized)（workspaceRoot 取自 file.allowed-paths 第一项）
6. 严格前缀匹配：resolved == allowed OR resolved.startsWith(allowed + "/")
7. 不在白名单 → 抛 "path '<resolved>' not in allowed-paths"
```

**理由**：

1. **JDK 内置优先**——`Path.normalize()` 是 JDK 21 稳定 API，零新增依赖。
2. **白名单前缀严格匹配**——`resolved.startsWith(allowed + "/")` 防 `/home/agent/workspace` 被 `/home/agent/workspace-evil` 绕过（spec FR-003 边界情况）。
3. **绝对路径直接拒绝**——核心阶段假定业务方只配"工作区根"一条 `allowed-paths`；绝对路径绕过工作区的攻击面太大，扩展阶段如要支持绝对路径再做。
4. **`..` traversal 在规范化前检测**——`Path.of(raw).normalize() != Path.of(raw)` 即"含 `.` / `..` 段"，与 `equals` 比较既检测 traversal 又同时允许 normalize 后的等值路径。**注意**：业务方传 `notes.md`（无 `.` / `..`）`normalize()` 等于原值，允许通过。

**备选 1**：用 `Path.toRealPath()` 检测 traversal。**否决**：`toRealPath()` 需要文件存在 + 触发文件系统调用（symlink resolution），单测无文件系统 fixture 时跑不通。

**备选 2**：正则 `.split("[/\\\\]")` 检测 `..` 段。**否决**：需自己处理 Windows `\\` 分隔符 + 跨平台差异；JDK `Path` 已封装。

**备选 3**：glob 模式白名单（如 `*.md` / `src/**`）。**否决**：glob 解释器需引入 `antlr` 或自实现；放扩展阶段（spec out-of-scope 第 8 条）。

---

## R-02：命令首 token 提取 + 大小写不敏感

**决策**：`WhitelistSandbox.enforce(SHELL_COMMAND, command)` 取 `command.split("\\s+", 2)[0]` lower-case 后精确匹配 `shell.allowed-commands` 任一项。

**算法**：

```text
1. trimmed = command.trim()
2. 若 trimmed.isEmpty() → 抛 "empty command"
3. tokens = trimmed.split("\\s+", 2)   // 只切首段，后续段不解析
4. first = tokens[0].toLowerCase(Locale.ROOT)
5. 遍历 shell.allowed-commands：每个 norm = cmd.toLowerCase(Locale.ROOT)；first == norm → 通过
6. 未命中 → 抛 "command '<first>' not in allowed-commands"
```

**理由**：

1. **首 token 即可控**——`ShellTool` 用 `ProcessBuilder(command.split("\\s+"))` 把命令按空白拆分（不解析引号 / 重定向 / 管道），业务方传进来的整串命令先经过 `split("\\s+")` 拆分后传给 OS。Sandbox 也按相同规则取首 token，**与 Tool 实际执行路径一致**。
2. **大小写不敏感**——Linux `git` / `GIT` / `Git` 等价；业务方配 `git` 一次即生效。Windows 不区分大小写天然兼容。
3. **不解析引号 / 元字符**——与 R-01 同源：核心阶段不引入 shell 解析器；如需支持 `command "arg with spaces"` 整串判定，放扩展阶段。
4. **与现有 `dangerousCommands` 黑名单分层**——`ShellToolProperties.dangerousCommands` 黑名单（已知威胁兜底，先于 Sandbox）；`SandboxProperties.shell.allowed-commands` 白名单（默认拒绝，后于黑名单）。两者并存，黑名单先于白名单（spec FR-004）。

**备选 1**：用 `bash -n` 或 `dash -n` 做语法解析后取首 token。**否决**：依赖外部进程 + 不可移植（Windows 无 `bash`）。

**备选 2**：解析引号 / 元字符后取首 token。**否决**：自实现 shell parser 是巨大的工程；放扩展阶段。

---

## R-03：`SandboxProperties` 扩展为 4 类子配置

**决策**：保留现有类形态（不重构为 record），新增 `File` + `Shell` 内部类 + 完整 4 类配置；保留 `ShellToolProperties.dangerousCommands` 作为兼容读源。

**新结构**：

```java
@ConfigurationProperties(prefix = "oryxos.tool.sandbox")
public class SandboxProperties {
    private Http http = new Http();
    private File file = new File();
    private Shell shell = new Shell();

    public static class Http {
        private List<String> allowedDomains = List.of();
        // getter / setter
    }
    public static class File {
        private List<String> allowedPaths = List.of();
        // getter / setter
    }
    public static class Shell {
        private List<String> allowedCommands = List.of();
        private List<String> dangerousCommands = List.of();   // 兼容读源
        // getter / setter
    }
}
```

**理由**：

1. **类形态延续现有模式**——现有 `SandboxProperties` 是类（含 `http` 内部类）；改成 record 会破坏 Spring Boot `@ConfigurationProperties` 绑定 + 破坏现有 `WhitelistSandbox` 构造器签名（`SandboxProperties properties`）。
2. **`Shell.dangerousCommands` 作为兼容读源**——`ShellToolProperties.dangerousCommands` 仍是 `ShellTool` 内的黑名单兜底直接读源；如 `SandboxProperties.shell.dangerousCommands` 非空则同时读两边（如有冲突，以 `ShellToolProperties` 为准——保留旧优先级，008 阶段统一收敛）。
3. **顺序 = HTTP → FILE → SHELL**——4 类配置各司其职；HTTP 复用现有 `allowed-domains`（与 R-04 已知 `allowed-hosts` 命名差异——保留现有命名）。

**备选 1**：重构为 record。**否决**：破坏现有 `@ConfigurationProperties` 绑定 + WhitelistSandbox 构造器 + 测试 fixtures；008 阶段才考虑。

**备选 2**：直接读 `ShellToolProperties` 而非 SandboxProperties。**否决**：违反"Tool 与 Sandbox 配置解耦"原则（Tool 不该影响 Sandbox 校验）。

---

## R-04：`WhitelistSandbox` 既有 HTTP 实现 + 行为对齐契约

**决策**：007 不重写 HTTP 校验路径；只新增 FILE / SHELL 两个 switch case。HTTP 行为与现有 [WhitelistSandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java) 第 47-91 行完全一致。

**对齐点**：

| 行为 | 005 落地 | 007 验收对齐 |
|------|---------|------------|
| scheme 校验（http/https） | ✅ WhitelistSandbox.java:61-65 | SC-001 端到端覆盖 |
| host 后缀匹配 | ✅ WhitelistSandbox.java:79-90 | SC-001 |
| IP 字面拒绝 | ✅ WhitelistSandbox.java:73-76 + 127-133 | SC-001 |
| URL 解析失败拒绝 | ✅ WhitelistSandbox.java:69-72 | SC-001 |
| IPv6 字面识别 | ⚠️ isIpLiteral() 第 127-133 行只检测 `:digit:.` 简单模式 | spec 边界情况第 5 条要求 `[::1]` 也拒——007 阶段补强 |
| Notify 链路调用 | ✅ WebhookNotifyAdapter.java:86-95 | SC-005 端到端覆盖 |

**理由**：

1. **HTTP 实现已在 005 落地且通过测试**（[WhitelistSandboxTest.java](../../../oryxos-tool/src/test/java/io/oryxos/tool/sandbox/WhitelistSandboxTest.java)）——007 不重写既有代码，只扩 switch case。
2. **IPv6 字面识别补强**——既有 `isIpLiteral()` 对 `::1` / `fe80::1` 等纯 IPv6 字面识别不全（只覆盖 `digit:.` 模式），007 阶段把判定改成"含 `:` 且全部由 hex digit + `:` + `.` 组成"以覆盖 `[::1]` / `fe80::1%eth0`。
3. **契约名 `allowed-domains` vs 005 文档 `allowed-hosts`**——保留现有 `allowed-domains`（与既有代码 + 测试 + 用户配置一致），005 文档 §3.1 第 81 行写的是 `allowed-hosts` 是文档错误，007 阶段通过文档对齐修复。

---

## R-05：fail-closed 默认 + 错误信息格式

**决策**：

1. **空白名单 = 全部拒绝**——`http.allowed-domains=[]` / `file.allowed-paths=[]` / `shell.allowed-commands=[]` MUST 等价"全部拒绝"，与宪法 §VII "Demo-First" 安全默认对齐。
2. **errorMessage 格式统一**——形如 `"sandbox violation: <reason>"`，含：
   - HTTP: `"sandbox violation: host '<host>' not in allowed-domains"` / `"sandbox violation: IP-literal hosts are not allowed: <host>"` / `"sandbox violation: unsupported scheme: <scheme>"`
   - FILE: `"sandbox violation: path traversal detected: <raw> -> <normalized>"` / `"sandbox violation: absolute path not allowed: <path>"` / `"sandbox violation: path '<path>' not in allowed-paths"`
   - SHELL: `"sandbox violation: command '<first>' not in allowed-commands"`
3. **errorMessage 不含 stack trace**（宪法 §VI / [CLAUDE.md §18](../CLAUDE.md) NFR-004）——stack trace 100% 进 `.oryxos/logs/oryxos-cli-error.log`（既有 DefaultToolExecutor 路径）。

**理由**：

1. **fail-closed = 业务方未声明前不许跑通**——与 `MemoryService` "未配后端抛错" 同模式；业务方必须显式声明 `allowed-*` 才允许 Tool 调用，符合"严格"安全基调。
2. **errorMessage 格式统一便于审计**——审计员能从 `tool_invocations.error_message` 列用 `LIKE 'sandbox violation: %'` 直接过滤；分类（HTTP / FILE / SHELL）由 reason 前缀分类。
3. **无 stack trace**——LLM 看到的是简短 reason（"host not in allowed-domains"），可调整下一次调用；运维看 `.oryxos/logs/oryxos-cli-error.log` 才有 stack。

**备选 1**：fail-open 默认（空白名单 = 全部通过）。**否决**：违反宪法 §VII Demo-First 安全默认；与"业务方未声明禁止" 的合规基调冲突。

**备选 2**：错误信息含完整异常 stack。**否决**：违反宪法 §VI / NFR-004；LLM 上下文被 stack trace 污染，损失 recall 准确率。

---

## R-06：`ShellToolProperties.dangerousCommands` 兼容读源策略

**决策**：007 阶段保留 `ShellToolProperties.dangerousCommands` 字段（已是 `record` 不可变），`SandboxProperties.shell.dangerousCommands` 作为新增字段**只在 Sandbox 层兜底使用**；`ShellTool` 内的 `properties.dangerousCommands()` 黑名单**不动**——保留"已知威胁先于白名单"的双层防御语义。

**理由**：

1. **双层防御不破坏**——`ShellTool` 内 `dangerousCommands` 黑名单（已知威胁兜底）+ `Sandbox.shell.allowedCommands` 白名单（默认拒绝）= 双保险；二者并存无冲突。
2. **不破坏既有集成测试**——`SandboxEnforcementIntegrationTest.java` 已测试 `rm -rf /` 触发 ShellTool 黑名单返回 `ToolResult.error("shell command blocked: rm is in dangerous-commands")`（不走 Sandbox）；007 阶段该测试不变。
3. **008 阶段统一收敛**——届时可以把 `ShellToolProperties.dangerousCommands` 删除，全部读 `SandboxProperties.shell.dangerousCommands`；007 阶段不动是减少本次 spec 改动面 + 避免引入回归。

**备选 1**：007 阶段删除 `ShellToolProperties.dangerousCommands`，全部走 `SandboxProperties`。**否决**：破坏既有测试 + 增加本次回归风险；008 阶段可做。

**备选 2**：`ShellTool` 完全去掉黑名单，仅靠 Sandbox 白名单。**否决**：失去"已知威胁先于白名单"的 defense-in-depth；`rm -rf /` 即便白名单配 `rm` 也会被 OS 误删——黑名单兜底不可缺。

---

## R-07：`DefaultToolExecutor` 审计路径不动

**决策**：007 阶段**不动** `DefaultToolExecutor` ——既有 `try { tool.execute() } catch (SandboxViolationException ex) { return ToolResult.error(...) }` 路径（[contracts/tool-executor.md §3](../005-tool-system/contracts/tool-executor.md)）已正确处理 `SandboxViolationException`，审计表 `tool_invocations(success=false, error_message="sandbox violation: ...")` 写入正确。

**理由**：

1. **既有路径已通过 005 验收**——`SandboxEnforcementIntegrationTest` 已有 HTTP 越域 → audit 行 `success=false` 的断言；007 阶段不动执行器即可让 FILE / SHELL 拦截自动复用既有审计路径。
2. **无新增依赖**——既不引入 audit 写入 helper，也不改 executor 接口；007 阶段纯 Sandbox 层扩展。

**验证**：007 阶段任务 T-XX 跑 `SandboxEnforcementIntegrationTest` 扩展场景（FILE 越界 + SHELL 越名单），断言 `auditTable.rows.last().success() == false` + `errorMessage.startsWith("sandbox violation: ")`。

---

## R-08：`Shell` 内部类的 `dangerousCommands` 字段 vs `ShellToolProperties.dangerousCommands` 同名是否冲突

**决策**：`SandboxProperties.Shell.dangerousCommands` 是 `SandboxProperties` 内的字段（不暴露给 `ShellTool`）；`ShellToolProperties.dangerousCommands` 是独立 Properties 类（`ShellTool` 直接读取）。两者命名一致但归属不同，**不会冲突**。

**冲突检查**：

- `ShellToolProperties`（prefix=`oryxos.tool.shell`）：`dangerousCommands` 由 `ShellTool` 读
- `SandboxProperties.Shell`（prefix=`oryxos.tool.sandbox.shell`）：`dangerousCommands` 由 `WhitelistSandbox` 读
- YAML 配置：`oryxos.tool.shell.dangerous-commands` 与 `oryxos.tool.sandbox.shell.dangerous-commands` 是不同 key，无冲突

**理由**：两套 Properties 类 + 不同 prefix + 不同读方，配置层完全独立。命名一致是巧合（两者本就描述同一概念）。

---

## R-09：路径前缀严格匹配 vs glob 模式

**决策**：核心阶段仅"严格前缀匹配"（`resolved == allowed || resolved.startsWith(allowed + "/")`），glob 模式放扩展阶段。

**理由**：

1. **glob 解释器 = 新依赖**——`antlr` / `jflex` / 自实现 glob；任何路径都增加 spec 复杂度。
2. **核心阶段需求 = 工作区根**——三个 Demo 仅需"工作区根"一条白名单即可（`/home/agent/workspace` 配一次，所有子目录允许），严格前缀匹配足够。
3. **扩展阶段可加 glob**——如业务方需要 `*.md` / `src/**` 模式，008 阶段扩展 `allowed-paths` 语法（glob vs literal）。

---

## R-10：`..` traversal 在 `Path.normalize()` 前检测 vs 之后

**决策**：在 `normalize()` 后用 `Path.of(raw).normalize() != Path.of(raw)` 比较检测——任何 `.` / `..` 段被规范化即视为 traversal。

**理由**：

1. **JDK 21 `Path.normalize()` 文档契约**：移除冗余 `.` / `..` / 双斜杠段。如果传 `notes.md`（无 `.` / `..`）`normalize()` 等于原值，`!=` 比较为 false，允许通过；如果传 `../etc/passwd` `normalize()` 等于 `../etc/passwd`（仍在父目录），`!=` 比较为 false，**但工作区根解析后会落到白名单外**，被前缀匹配拒（仍然安全）。如果传 `./workspace/notes.md` `normalize()` 等于 `workspace/notes.md`，`!=` 比较为 true（traversal 标记），拒（业务方应去掉 `./` 前缀，行为可接受）。
2. **业务方传 raw path 含有 `.` 段是"非典型用法"**——通常业务方传 `notes.md` / `src/foo.md` 等规整路径，`./` 前缀是误用；拒之合规。

**备选 1**：仅在 `normalize()` 后路径与原 raw 字符串不等时拒。**否决**：未规范化时直接 equals 字符串易绕过（`./notes.md` vs `notes.md` 字符串不等但语义等价）。

**备选 2**：检测原始字符串是否含 `..` 字面段。**否决**：仅字符串检测无法处理规范化路径（`./../etc`）；需要先规范化。

---

## 引用

- [spec.md FR-001..011](./spec.md)
- [spec.md NFR-001..004](./spec.md)
- [spec.md SC-001..009](./spec.md)
- [CLAUDE.md §9.4 / §18](../../CLAUDE.md)
- [.specify/memory/constitution.md §I-VII](../../.specify/memory/constitution.md)
- [specs/005-tool-system/contracts/sandbox.md §5.1](../005-tool-system/contracts/sandbox.md)
- [specs/005-tool-system/research.md R-03 / R-11](../005-tool-system/research.md)
- [specs/004-notify-channel/spec.md FR-007](../004-notify-channel/spec.md)
- [Sandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/Sandbox.java)
- [SandboxAction.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxAction.java)
- [SandboxProperties.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxProperties.java)
- [WhitelistSandbox.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java)
- [SandboxViolationException.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxViolationException.java)
- [WebhookNotifyAdapter.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/notify/WebhookNotifyAdapter.java)
- [ShellTool.java](../../../oryxos-tool/src/main/java/io/oryxos/tool/shell/ShellTool.java)