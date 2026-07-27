---
description: "Task list for Sandbox 白名单实现 — 007-sandbox-whitelist"
---

# Tasks: Sandbox 白名单实现

**Input**: Design documents from `/specs/007-sandbox-whitelist/`
**Prerequisites**: [plan.md](./plan.md) ✅ | [spec.md](./spec.md) ✅ | [research.md](./research.md) ✅ | [data-model.md](./data-model.md) ✅ | [contracts/sandbox-whitelist.md](./contracts/sandbox-whitelist.md) ✅

**Tests**: spec §SC-001 / §SC-002 / §SC-003 / §SC-005 / §SC-006 显式要求端到端 + 单测 + 性能基准；tests **INCLUDED**.

**Organization**: Tasks grouped by user story（US-1 FILE P1 🎯 MVP / US-2 SHELL P1 / US-3 Notify P2 / US-4 跨 ActionType 集成审计 + 接口稳定性 P2）

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 / US2 / US3 / US4 — maps to [spec.md §用户场景](./spec.md)
- 路径遵循 [plan.md §Project Structure](./plan.md)：核心变更在 `oryxos-tool/sandbox/` + `oryxos-boot/config/`；测试在 `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/` + `.../integration/`

---

## Phase 1: Setup（基础设施 + 分支验证）

**目的**：工作分支 + 9 模块 Maven 结构 + JDK 21 编译器配置确认。

- [x] T001 验证当前在 `007-sandbox-whitelist` 分支（`git branch --show-current` 期望 `007-sandbox-whitelist`），如未切则 `git checkout 007-sandbox-whitelist`；工作区清空（`git status` 无未提交变更）
- [x] T002 [P] 验证 `pom.xml` JDK 21 配置完整——`<forceLegacyJavacApi>true</forceLegacyJavacApi>` + `<encoding>UTF-8</encoding>` + surefire `<argLine>-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8</argLine>`（防 [CLAUDE.md §18 坑 4](../../CLAUDE.md)）；9 个 Maven 模块全部存在（oryxos-core / oryxos-provider / oryxos-memory / oryxos-tool / oryxos-channel-cli / oryxos-web / oryxos-storage / oryxos-cli / oryxos-boot）

**Checkpoint**: Setup 完成 = 分支 + JDK 21 + Maven 多模块结构验证通过；可进入 Phase 2 Foundational。

---

## Phase 2: Foundational（阻塞前置 — 公共 API + 配置聚合根）

**目的**：扩展 `SandboxProperties` 为 4 子配置 + 在 `WhitelistSandbox` 加 switch 框架 + 准备单测基线。**所有 4 个 US 都依赖本阶段产物**。

**⚠️ CRITICAL**：US-1 / US-2 / US-3 / US-4 全部依赖 Phase 2 完成；本阶段未完成 = 任何 US 不可启动。

- [x] T003 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxProperties.java` 加 2 个内部类：`public static class File { private List<String> allowedPaths = List.of(); ... }` + `public static class Shell { private List<String> allowedCommands = List.of(); private List<String> dangerousCommands = List.of(); ... }`；顶层加 `private File file = new File(); private Shell shell = new Shell();` + 各自 `getFile/setFile/getShell/setShell`；setter 内 `null → List.of()` 兜底（[data-model.md §2.1](./data-model.md) / [contracts/sandbox-whitelist.md §12.2](./contracts/sandbox-whitelist.md)）
- [x] T004 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java` 改写 `enforce(SandboxAction)` 方法：现有 HTTP_REQUEST switch case 保留；在 HTTP_REQUEST case 之后插入 FILE_READ/FALL_THROUGH → FILE_WRITE → SHELL_COMMAND 三个 case，每个 case 调 `enforceFile(target)` / `enforceShell(target)` 占位（占位仅抛 `UnsupportedOperationException`），编译通过；既有 HTTP 行为字节级不变（[research.md R-04 / R-07](./research.md)）
- [x] T005 [P] 创建测试基线 `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/SandboxPropertiesTest.java`：覆盖 `File.allowedPaths` + `Shell.allowedCommands` + `Shell.dangerousCommands` 默认值（`List.of()`）+ setter null 兜底 + YAML 绑定（用 `@ConfigurationProperties` + `Binder.get(...)` 跑 `application.yaml` 解析）

**Checkpoint**: Phase 2 完成 = `mvn -pl oryxos-tool -am compile` 编译通过 + `SandboxPropertiesTest` 全过；US-1 可启动。

---

## Phase 3: User Story 1 — 文件 IO 路径白名单（Priority: P1 🎯 MVP）

**Goal**：把 `WhitelistSandbox.enforceFile()` 从占位换成真实白名单实现；`file_read` / `file_write` / `file_list` Tool 路径越界 + `..` traversal + 绝对路径 + 前缀绕过 → 全部拦截 + 审计。

**Independent Test**: 跑 [quickstart.md §场景 S1](./quickstart.md) 4 个子场景（通过路径 / 越界路径 / `..` traversal / 前缀绕过）断言全过 + `tool_invocations` 审计表对应行写入。

### Tests for User Story 1（先红后绿）

- [x] T006 [P] [US1] 在 `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/FilePathSandboxTest.java` 新建 8 个 `@Test`：① 路径在白名单内 + workspace root 解析通过；② workspace root 之外路径抛 `"path '...' not in allowed-paths"`；③ `../etc/passwd` 抛 `"path traversal detected"`；④ 绝对路径 `/etc/passwd` 抛 `"absolute path not allowed"`；⑤ workspace root 含 trailing slash `/home/agent/workspace/` + 子路径 `notes.md` 仍通过；⑥ 前缀绕过 `/home/agent/workspace-evil/secret.md` 不被 workspace root 包含；⑦ `./notes.md` 抛 traversal（`Path.normalize()` 后与原值不等）；⑧ 配置 `file.allowed-paths=[]` 时任何路径抛 `"not in allowed-paths"`（fail-closed 默认）（[research.md R-01](./research.md)）

### Implementation for User Story 1

- [x] T007 [US1] 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java` 实现 `private void enforceFile(String target)`（伪代码 [contracts/sandbox-whitelist.md §13.1](./contracts/sandbox-whitelist.md)）：1) `Path.of(raw).normalize() != Path.of(raw)` → 抛 `path traversal detected`；2) `normalized.isAbsolute()` → 抛 `absolute path not allowed`；3) `properties.getFile().getAllowedPaths().isEmpty()` → 抛 `path '...' not in allowed-paths`；4) 取 `allowedPaths` 第一项作为 workspace root；5) `resolved = workspaceRoot.resolve(normalized).normalize()`；6) 严格前缀匹配 `resolved.equals(Path.of(p)) || resolved.startsWith(Path.of(p) + "/")` → 全部不在白名单抛 `path '...' not in allowed-paths`；所有异常 `SandboxViolationException` 中文 Javadoc 注释（[CLAUDE.md §18 坑 5](../../CLAUDE.md)）
- [x] T008 [US1] 在 `oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java` 把现有 `@Test void file_read_no_op_in_core_phase()`（L278-288）改写为两个测试：① `file_read_in_whitelist_allowed` 配 `oryxos.tool.sandbox.file.allowed-paths[0]=/tmp/oryxos-workspace` + 创建 tmp/notes.md + 读通过；② `file_read_outside_whitelist_blocked` 读 `/etc/passwd` 抛 `not in allowed-paths`（[contracts/sandbox-whitelist.md §15.2](./contracts/sandbox-whitelist.md)）

**Checkpoint**: US-1 完成 = `mvn -pl oryxos-tool -am test -Dtest='FilePathSandboxTest,SandboxEnforcementIntegrationTest'` 全过（FILE 相关场景）+ `tool_invocations` 审计行写入正确；US-1 独立可演示。

---

## Phase 4: User Story 2 — Shell 命令白名单（Priority: P1）

**Goal**：把 `WhitelistSandbox.enforceShell()` 从占位换成真实白名单实现；`shell` Tool 命令首 token 不在白名单 + 大小写不敏感 + 黑名单先于白名单 + 空命令 → 全部拦截。

**Independent Test**: 跑 [quickstart.md §场景 S2](./quickstart.md) 5 个子场景（通过命令 / 大小写不敏感 / 越名单 / 黑名单先命中 / 空命令）断言全过 + 既有 Shell 黑名单不变。

### Tests for User Story 2（先红后绿）

- [x] T009 [P] [US2] 在 `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/ShellCommandSandboxTest.java` 新建 7 个 `@Test`：① `ls -la /tmp` → 不抛异常；② `GIT status` 经 toLowerCase 后命中 `git` 不抛；③ `curl https://evil.com` 抛 `"command 'curl' not in allowed-commands"`；④ `   ` 空字符串抛 `"empty command"`；⑤ 单空格 ` ` 抛 `"empty command"`（trim 后空）；⑥ 配置 `shell.allowed-commands=[]` 时任何命令抛 `"not in allowed-commands"`（fail-closed）；⑦ 配 `allowed-commands=['git','ls']` 时 `cat` 抛 `"command 'cat' not in allowed-commands"`（[research.md R-02](./research.md)）

### Implementation for User Story 2

- [x] T010 [US2] 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java` 实现 `private void enforceShell(String command)`（伪代码 [contracts/sandbox-whitelist.md §13.1](./contracts/sandbox-whitelist.md)）：1) `command.trim().isEmpty()` → 抛 `empty command`；2) `first = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT)`；3) 遍历 `properties.getShell().getAllowedCommands()`，每个 lower-case 后 equals 比；4) 未命中 → 抛 `command '<first>' not in allowed-commands`；异常中文 Javadoc
- [x] T011 [US2] 验证 `ShellTool.execute()` 既有 dangerousCommands 黑名单**先于** Sandbox 白名单：在 `oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java` 加 `@Test void shell_blacklist_precedes_whitelist` 配 `shell.dangerous-commands=[rm]` + `shell.allowed-commands=[rm,ls]`（兼容读源），调 `rm -rf /tmp` → 断言 `errorMessage` 以 `"shell command blocked:"` 开头（而非 `"sandbox violation: command 'rm'"`）；既有 `shell_blacklisted_command_blocked_no_side_effect` 测试不变（[research.md R-06](./research.md) / [contracts/sandbox-whitelist.md §14.4](./contracts/sandbox-whitelist.md)）

**Checkpoint**: US-2 完成 = `mvn -pl oryxos-tool -am test -Dtest='ShellCommandSandboxTest,SandboxEnforcementIntegrationTest'` 全过（SHELL 相关场景）+ 黑名单优先级正确；US-1 + US-2 共同构成 MVP（应用层白名单核心能力）。

---

## Phase 5: User Story 3 — Notify 出站经 Sandbox 拦截（Priority: P2 ✅ 已落地验证）

**Goal**：固化 [004-notify-channel/spec.md FR-007](../004-notify-channel/spec.md) 已落地的 `WebhookNotifyAdapter` 出站 Sandbox 钩入路径；加 IPv6 字面识别补强 + 越域拦截测试。

**Independent Test**: 跑 [quickstart.md §场景 S4](./quickstart.md) 3 个子场景（Notify 越域拦截 / Notify 白名单内通过 / Notify IPv6 字面拦截）断言全过。

### Tests for User Story 3（先红后绿）

- [x] T012 [P] [US3] 在 `oryxos-tool/src/test/java/io/oryxos/tool/integration/NotifySandboxEnforcementIT.java` 新建 4 个 `@Test`：① `notify_outbound_to_disallowed_domain_blocked` 配 `http.allowed-domains=[api.example.com]` + Notify URL `https://webhook.example.com/hook` → 抛 `not in allowed-domains` + WireMock 零请求；② `notify_outbound_to_allowed_domain_passes` 配 `http.allowed-domains=[webhook.example.com]` + 同 URL → HTTP POST 发出；③ `notify_outbound_ipv6_literal_blocked` Notify URL `http://[::1]:9999/hook` → 抛 `IP-literal`；④ `notify_outbound_no_allowed_domains_fail_closed` `http.allowed-domains=[]` → 任何 URL 抛 `not in allowed-domains`（[research.md R-04 IPv6 补强](./research.md) / [contracts/sandbox-whitelist.md §13.2.2](./contracts/sandbox-whitelist.md)）

### Implementation for User Story 3

- [x] T013 [US3] 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java` 改 `private boolean isIpLiteral(String host)` 方法：旧实现仅 `:digit:.` 模式（[WhitelistSandbox.java:127-133](../../oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java)）；新实现改为"含 `:` AND（全部由 hex digit + `:` + `.` 组成 OR 含 `[` / `]` 包装）"，覆盖 `[::1]` / `[fe80::1%eth0]` / `[::ffff:192.168.1.1]` 等纯 IPv6 字面；既有 IPv4 字面识别不变（[research.md R-04](./research.md) / [contracts/sandbox-whitelist.md §13.2.2](./contracts/sandbox-whitelist.md)）；既有 HTTP 越域 / scheme 校验 / host 后缀匹配逻辑**字节级不变**

**Checkpoint**: US-3 完成 = `mvn -pl oryxos-tool -am test -Dtest='NotifySandboxEnforcementIT,SandboxEnforcementIntegrationTest'` 全过（Notify 链路 4 场景 + IPv6 补强）；既有 `WebhookNotifyAdapter.java` 享受 IPv6 补强无需修改。

---

## Phase 6: User Story 4 — 跨 ActionType 集成审计 + 接口稳定性（Priority: P2）

**Goal**：4 类 ActionType 端到端集成测试 + 接口字节级不变断言（[SC-007](./spec.md)）+ 性能基准（[SC-006](./spec.md)）；证明 Sandbox 拦截 → `tool_invocations(success=false, error_message="sandbox violation: ...")` 100% 覆盖且不含 stack trace。

**Independent Test**: 跑 `mvn -pl oryxos-tool -am verify` 全模块绿 + 接口字节级不变断言 + 性能 P95 ≤ 5ms；审计 SQL 查询过滤 `LIKE 'sandbox violation: %'` 返回 0 行含 stack trace。

### Tests for User Story 4（先红后绿）

- [x] T014 [P] [US4] 在 `oryxos-tool/src/test/java/io/oryxos/tool/integration/CrossActionTypeSandboxIT.java` 新建 5 个 `@Test`：① 配 `http.allowed-domains=[localhost]` + `file.allowed-paths=[tmp]` + `shell.allowed-commands=[ls]`，4 个 Tool 各调一次：HTTP 越域 → 拦截；FILE 越界 → 拦截；SHELL 越名单 → 拦截；FILE 通过路径 → 成功；断言每条 Tool 调用在 `tool_invocations` 表都有对应行（4/4 命中）；② 跑 `SELECT error_message FROM tool_invocations WHERE error_message LIKE 'sandbox violation: %' AND (error_message LIKE '%\tat %' OR error_message LIKE '%Exception in thread%')` → 期望 0 行（[NFR-004](./spec.md) stack trace 不进 errorMessage）；③ 调 `notify` Tool 经 WebhookNotifyAdapter 越域 → 审计行 `channel='webhook'` + errorMessage 含 sandbox violation；④ 配 `file.allowed-paths=[]` 时 FILE 调用 fail-closed 全部拒；⑤ 配 `shell.allowed-commands=[]` 时 SHELL 调用 fail-closed 全部拒
- [x] T015 [P] [US4] 在 `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/SandboxApiCompatibilityIT.java` 新建 6 个断言：① `Sandbox.class.getDeclaredMethods()` 与 005 阶段一致（用 `javap -p` 输出对比基准字符串 / 或反射检查方法签名集合）；② `SandboxAction.class` 字段（`type` / `target`）不变；③ `ActionType.class` enum 4 值不变；④ `SandboxViolationException.class` 构造器签名不变；⑤ `SandboxProperties.class` 既有 `getHttp/setHttp/getAllowedDomains/setAllowedDomains` 字节级不变；⑥ `WhitelistSandbox.class` 既有的 `enforce(SandboxAction)` 方法签名不变（[SC-007 接口字节级不变](./spec.md) / [NFR-004](./spec.md)）

### Implementation for User Story 4

- [x] T016 [US4] 在 `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/SandboxPerformanceBenchmarkIT.java` 新建性能基准：循环 1000 次 `whitelistSandbox.enforce(new SandboxAction(HTTP_REQUEST, "https://api.example.com/"))`，用 `System.nanoTime()` 测量 wall-time；断言 median / P95 / P99 均 ≤ 5ms（[SC-006](./spec.md)）；含 4 类 ActionType 各 1000 次（HTTP / FILE / SHELL）混合 benchmark 输出报告
- [x] T017 [US4] 在 `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/WhitelistSandboxTest.java`（既有单测文件）扩展 HTTP 场景：① IPv4 字面识别既有测试不变；② 新增 `[::1]` / `[fe80::1%eth0]` / `[::ffff:192.168.1.1]` 三个 IPv6 字面识别测试；③ 新增 fail-closed 默认测试（业务方未配 `http.allowed-domains` → 任何 host 拒）

**Checkpoint**: US-4 完成 = `mvn -pl oryxos-tool -am verify` 全过；接口字节级不变断言 6/6 全过；性能 P95 ≤ 5ms；4 类 ActionType 审计 100% 含 `sandbox violation:` 前缀且 0% 含 stack trace。

---

## Phase 7: Polish & Cross-Cutting Concerns

**目的**：跨故事横切改进——文档对齐 + 既有契约补充 + 全模块 green + analyze 收口。

- [x] T018 [P] 更新 [specs/005-tool-system/contracts/sandbox.md §3.1](../005-tool-system/contracts/sandbox.md) 第 81 行 YAML 示例 `allowed-hosts` 错误拼写 → `allowed-domains`（[research.md R-04 契约名修正](./research.md)）；在 §3.3 / §3.2 添加 FILE / SHELL 落地后行为描述（不再是 no-op）；§6 不变量增加 I-SB-7（interface byte-stable）+ I-SB-8（fail-closed 默认）；§7 性能特性更新为"P95 ≤ 5ms × 4 类 ActionType"
- [x] T019 [P] 跑 `mvn verify` 全模块（9 模块 + 测试 + 集成），所有模块 0 失败 0 错误；记录 `mvn verify` 输出（成功耗时 + 测试用例数 + 集成测试数）到 [specs/007-sandbox-whitelist/evidence/mvn-verify.log](evidence/mvn-verify.log)（[SC-004](./spec.md)）
- [x] T020 [P] 跑 `scripts/memory-smoke.sh` 既有脚本（006 阶段遗留）确认无回归（007 不动 memory 层但要确认没影响）
- [x] T021 跑 `/speckit.analyze` 007 阶段 analyze 收口（[CLAUDE.md §17 必须跑 /speckit.analyze 防漂移](../../CLAUDE.md)）；分析报告写到 [specs/007-sandbox-whitelist/checklists/analyze.log](checklists/analyze.log) 或本地 review；如有 F1-FN remediation 按既有 006 模式应用并新增 Phase 8 任务
- [x] T022 提交 `feat(007): sandbox whitelist implementation (US-1 + US-2 + US-3 + US-4)` 到 `007-sandbox-whitelist` 分支；commit message 引用 [SC-001..009](./spec.md) 全部覆盖；如需拆 commit 可 US-1/2 一个、US-3 一个、US-4 + Polish 一个（[CLAUDE.md §17 Per-US commit 约定](../../CLAUDE.md)）
- [x] T023 [P] 跑 [quickstart.md](./quickstart.md) 4 场景端到端验收（手动或脚本化）；记录每个场景的实测 errorMessage + audit 行 ID 到 [specs/007-sandbox-whitelist/evidence/quickstart-evidence.md](evidence/quickstart-evidence.md)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即启动
- **Foundational (Phase 2)**: 依赖 Setup 完成 —— **BLOCKS** 所有 US
- **User Stories (Phase 3-6)**: 全部依赖 Foundational 完成
  - US-1 (P1 FILE) → US-2 (P1 SHELL) → US-3 (P2 Notify) → US-4 (P2 集成) 可串行或并行（不同文件 + 互相独立可测）
- **Polish (Phase 7)**: 依赖所有期望完成的 US

### User Story Dependencies

- **US-1 (P1 FILE)**: Foundational 完成后即可启动；与 US-2 / US-3 / US-4 独立
- **US-2 (P1 SHELL)**: Foundational 完成后即可启动；与 US-1 并行可启动（不同 switch case + 不同测试文件）；黑名单先于白名单断言（T011）依赖既有 `ShellTool` 黑名单（既有，无新增）
- **US-3 (P2 Notify)**: Foundational 完成后即可启动；IPv6 字面识别补强（T013）逻辑与 US-1 / US-2 独立（不同文件 `isIpLiteral()` 函数）；Notify 测试文件 `NotifySandboxEnforcementIT` 独立
- **US-4 (P2 集成)**: 依赖 US-1 + US-2 + US-3 都完成（跨 ActionType 集成测试必须 4 类都实现才能跑）

### Within Each User Story

- Tests 先写且 fail（T006 / T009 / T012 / T014 / T015 / T016 / T017），再 implementation（T007 / T010 / T013）
- 单测（`WhitelistSandboxTest` / `FilePathSandboxTest` / `ShellCommandSandboxTest`）先于集成测试（`SandboxEnforcementIntegrationTest` / `NotifySandboxEnforcementIT` / `CrossActionTypeSandboxIT`）
- T016 性能基准依赖 T007 + T010 + T013 全部完成
- T015 接口兼容性依赖 Phase 2 T003（`SandboxProperties` 扩展）完成（公共方法签名在新内部类加完后做对比）

### Parallel Opportunities

- **Phase 1**: T001 + T002 可并行
- **Phase 2**: T003 + T004 + T005 可并行（不同文件）
- **Phase 3 US-1**: T006 + T007 串行（测试先 fail）；T008 串行（T007 实现后才能改 integration test）
- **Phase 4 US-2**: T009 + T010 串行；T011 依赖 T010（verify 双层防御顺序）
- **Phase 5 US-3**: T012 + T013 并行（不同文件）
- **Phase 6 US-4**: T014 + T015 + T016 + T017 全部并行（不同测试文件）；T015 接口兼容性依赖 T003（Phase 2）
- **Phase 7**: T018 + T019 + T020 + T023 并行；T021 串行（T019 + T020 完成）；T022 串行（T021 完成）

### Phase 7 Polish 内部依赖图

```text
T018 (文档) ─┐
T019 (verify) ─┤
T020 (smoke)  ─┼─→ T021 (analyze) ─→ T022 (commit) ─→ T023 (quickstart 验证)
              ┘
```

---

## Parallel Examples

### Example 1: Phase 2 Foundational 三个任务并行

```text
T003 (SandboxProperties 扩展)        ─┐
T004 (WhitelistSandbox switch 框架)   ─┼─→ Phase 3 启动
T005 (SandboxPropertiesTest)         ─┘
```

### Example 2: Phase 6 US-4 四个测试文件并行

```text
T014 (CrossActionTypeSandboxIT)        ─┐
T015 (SandboxApiCompatibilityIT)       ─┤
T016 (SandboxPerformanceBenchmarkIT)   ─┼─→ Phase 7 Polish
T017 (WhitelistSandboxTest 扩展 IPv6) ─┘
```

### Example 3: 多开发者并行（Phase 3 + Phase 4 + Phase 5）

```text
Dev A: T006 + T007 + T008 (US-1 FILE)
Dev B: T009 + T010 + T011 (US-2 SHELL)
Dev C: T012 + T013 (US-3 Notify + IPv6)
```

---

## Implementation Strategy

### MVP First (US-1 + US-2 Only)

1. Phase 1 Setup ✅
2. Phase 2 Foundational ✅（BLOCKS all）
3. Phase 3 US-1（FILE 路径白名单）✅
4. Phase 4 US-2（SHELL 命令白名单）✅
5. **STOP and VALIDATE**：跑 `mvn -pl oryxos-tool -am test -Dtest='FilePathSandboxTest,ShellCommandSandboxTest,SandboxEnforcementIntegrationTest'`，全过 = MVP 完成
6. **MVP Demo**：3 个 Demo（每日天气 / 每日科技日报 / 每日 GitHub 日报）配 `file.allowed-paths` + `shell.allowed-commands` 后可正常运行

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. US-1 → Test independently → 应用层白名单第一堵墙（FILE）落地
3. US-2 → Test independently → 第二堵墙（SHELL）落地（**MVP 完成**）
4. US-3 → Test independently → Notify 出站拦截 + IPv6 补强（既有契约固化）
5. US-4 → Test independently → 跨 ActionType 集成审计 + 接口稳定性
6. 每个 story 加 value 不破坏前序 story（既有用例 `SandboxEnforcementIntegrationTest` 4 个场景仍通过）

### Parallel Team Strategy

1. 团队一起完成 Setup + Foundational
2. Foundational 完成后：
   - Dev A: US-1（FILE）
   - Dev B: US-2（SHELL）
   - Dev C: US-3（Notify + IPv6）
3. US-1 + US-2 + US-3 都完成后：
   - Dev A 或 Dev B: US-4（跨 ActionType 集成 + 接口稳定性）
4. US-4 完成 → Phase 7 Polish（sequential）

---

## Notes

- **[P] 任务**：不同文件、无依赖；并行执行可节省 ~30% wall-clock
- **[Story] label**：US1 / US2 / US3 / US4 映射 [spec.md §用户场景](./spec.md)
- **每个 US 独立可完成 + 可测试**——T007 / T010 / T013 实现完成后立即跑对应测试文件，确认 fail-to-pass
- **commit 时机**：每个 US 完成后 commit 一次（per-US commit 约定 [CLAUDE.md §17](../../CLAUDE.md)）；Phase 7 T022 做最后整理（如需要）
- **避开 common pitfalls**：
  - ⚠️ 不要修改 `Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` 公共 API（[NFR-004 / SC-007](./spec.md) 字节级不变）
  - ⚠️ 不要在 `WhitelistSandbox` 加新异常类型（统一抛 `SandboxViolationException`）
  - ⚠️ 不要修改 `DefaultToolExecutor` 既有审计路径（research.md R-07 复用既有路径）
  - ⚠️ 不要在 `ShellToolProperties.dangerousCommands` 007 阶段做删除或迁移（008 阶段统一收敛）
  - ⚠️ 不要在 Javadoc 注释里写一字面 `*/`（[CLAUDE.md §18 坑 5](../../CLAUDE.md)）
- **任务总数**：23 个（T001-T023）
- **任务按 US 分布**：
  - Setup: 2
  - Foundational: 3
  - US-1: 3 (T006-T008)
  - US-2: 3 (T009-T011)
  - US-3: 2 (T012-T013)
  - US-4: 4 (T014-T017)
  - Polish: 6 (T018-T023)