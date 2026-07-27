# Quickstart：Sandbox 白名单实现

**目的**：通过 4 个端到端验收场景，证明 007 阶段后 4 类 ActionType（HTTP_REQUEST / FILE_READ / FILE_WRITE / SHELL_COMMAND）的真实白名单拦截 + 审计还原完整链路。
**创建日期**：2026-07-27
**特性**：[spec.md](./spec.md)
**前置文档**：[research.md](./research.md) | [data-model.md](./data-model.md) | [contracts/sandbox-whitelist.md](./contracts/sandbox-whitelist.md) | [specs/005-tool-system/contracts/sandbox.md](../005-tool-system/contracts/sandbox.md)

> **使用对象**：开发者 / 评审 / 测试 —— 跑通这 4 个场景 + 通过断言 = 007 阶段 spec 验收完成。

---

## 前置条件

| 依赖 | 版本 / 路径 | 检查命令 |
|------|------------|---------|
| JDK | 21+（强制，详见 [CLAUDE.md §4 / §18 坑 4](../../CLAUDE.md)） | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| 工作分支 | `007-sandbox-whitelist` | `git branch --show-current` |
| 既有测试 | 005 阶段已落地 [SandboxEnforcementIntegrationTest.java](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java) | `mvn -pl oryxos-tool -am test -Dtest=SandboxEnforcementIntegrationTest` |
| spec 契约 | [spec.md §关键实体](./spec.md) + [data-model.md §2](./data-model.md) + [contracts/sandbox-whitelist.md §12-§16](./contracts/sandbox-whitelist.md) | 文件已生成 |

---

## 验收场景一览

| # | ActionType | 场景 | 期望拦截？ | 断言路径 |
|---|-----------|------|-----------|---------|
| S1 | `FILE_READ` | 路径在白名单内 + 越界 (`..`) | 部分通过 | `tool_invocations` 审计 |
| S2 | `SHELL_COMMAND` | 首 token 在白名单内 + 越名单 | 部分通过 | `tool_invocations` 审计 |
| S3 | `HTTP_REQUEST` | 越域 + IPv6 字面 `[::1]` | 全部拦截 | `tool_invocations` 审计 + WireMock 零请求 |
| S4 | `HTTP_REQUEST`（Notify） | Notify 出站越域 | 全部拦截 | `tool_invocations.channel` + 审计 |

---

## 场景 S1 — FILE_READ 路径白名单 + traversal 拦截

### 前置

`application.yaml` 配：

```yaml
oryxos:
  tool:
    sandbox:
      file:
        allowed-paths:
          - /tmp/oryxos-workspace   # 业务方工作区根
```

### 步骤

```bash
# 1. 准备测试工作区
mkdir -p /tmp/oryxos-workspace/sub
echo "inside" > /tmp/oryxos-workspace/notes.md
echo "sub" > /tmp/oryxos-workspace/sub/inner.md

# 2. 启动 OryxOS（任意方式：CLI / Web / Scheduler 触发都行）
# 推荐用 shell 形式手测（用现有 file_read Tool）

# 3. 调 file_read，路径在白名单内
```

### 3a. 通过路径

```java
ToolResult r = fileReadTool.execute(Map.of("path", "notes.md"));
// 或绝对路径：ToolResult r = fileReadTool.execute(Map.of("path", "/tmp/oryxos-workspace/notes.md"));
```

**期望**：

- `r.success() == true`
- `r.payload().get("content") == "inside"`
- `tool_invocations` 写入一行：`success=true, tool_name=file_read, duration_ms=...`

### 3b. 越界路径（不在白名单）

```java
ToolResult r = fileReadTool.execute(Map.of("path", "/etc/passwd"));
```

**期望**：

- `r.success() == false`
- `r.errorMessage()` 以 `"sandbox violation: path '/etc/passwd' not in allowed-paths"` 开头
- `tool_invocations` 写入一行：`success=false, error_message startsWith "sandbox violation: "`

### 3c. `..` traversal

```java
ToolResult r = fileReadTool.execute(Map.of("path", "../etc/passwd"));
```

**期望**：

- `r.success() == false`
- `r.errorMessage()` 含 `"path traversal detected"` + `../etc/passwd -> ../etc/passwd`

### 3d. 前缀绕过（spec 边界情况第 3 条）

```bash
# 业务方配 allowed-paths=/tmp/oryxos-workspace
# 攻击方建 /tmp/oryxos-workspace-evil/secret.md
mkdir -p /tmp/oryxos-workspace-evil
echo "secret" > /tmp/oryxos-workspace-evil/secret.md
```

```java
ToolResult r = fileReadTool.execute(Map.of("path", "/tmp/oryxos-workspace-evil/secret.md"));
```

**期望**：

- `r.success() == false`
- `r.errorMessage()` 含 `"not in allowed-paths"`（因 `startsWith("/tmp/oryxos-workspace/")` 为 false）

### 清理

```bash
rm -rf /tmp/oryxos-workspace /tmp/oryxos-workspace-evil
```

---

## 场景 S2 — SHELL_COMMAND 首 token 白名单

### 前置

`application.yaml` 配：

```yaml
oryxos:
  tool:
    sandbox:
      shell:
        allowed-commands:
          - ls
          - cat
          - git
    shell:
      dangerous-commands:   # 兼容读源（既有 ShellTool 黑名单）
        - rm
        - shutdown
```

### 步骤

### 2a. 通过命令

```java
ToolResult r = shellTool.execute(Map.of("command", "ls -la /tmp"));
```

**期望**：

- `r.success() == true`
- `r.payload().get("output")` 含 `"/tmp"` 目录列表

### 2b. 大小写不敏感（research.md R-02）

```java
ToolResult r = shellTool.execute(Map.of("command", "GIT status"));
```

**期望**：

- `r.success() == true`（`GIT` 经 `.toLowerCase()` 后匹配 `git`）

### 2c. 越名单命令

```java
ToolResult r = shellTool.execute(Map.of("command", "curl https://evil.example.com"));
```

**期望**：

- `r.success() == false`
- `r.errorMessage()` 以 `"sandbox violation: command 'curl' not in allowed-commands"` 开头

### 2d. 黑名单先于白名单（research.md R-06）

```java
ToolResult r = shellTool.execute(Map.of("command", "rm -rf /tmp"));
```

**期望**：

- `r.success() == false`
- `r.errorMessage()` 以 `"shell command blocked:"` 开头（**而非** `"sandbox violation: command 'rm' not in allowed-commands"`）—— `ShellTool` 内 dangerousCommands 黑名单先命中

### 2e. 空命令（spec 边界情况）

```java
ToolResult r = shellTool.execute(Map.of("command", "   "));
```

**期望**：

- `r.success() == false`
- `r.errorMessage()` 含 `"sandbox violation: empty command"`

---

## 场景 S3 — HTTP_REQUEST 越域 + IPv6 字面拦截（既有 + 007 补强）

### 前置

`application.yaml` 配：

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:
          - api.example.com
```

### 步骤

### 3a. 越域

```java
ToolResult r = httpGetTool.execute(Map.of("url", "https://evil.example.com/hook"));
```

**期望**：

- `r.success() == false`
- `r.errorMessage()` 含 `"not in allowed-domains"`
- WireMock 零请求

### 3b. IPv6 字面 `[::1]`（007 新增）

```java
ToolResult r = httpGetTool.execute(Map.of("url", "http://[::1]:8080/api"));
```

**期望**：

- `r.success() == false`
- `r.errorMessage()` 含 `"IP-literal"`

### 3c. 通过域名

```java
ToolResult r = httpGetTool.execute(Map.of("url", "https://api.example.com/users"));
```

**期望**：

- `r.success() == true`
- HTTP 200 + JSON 响应（需业务方 Mock 后端）

### 3d. fail-closed 默认（业务方未配）

```yaml
# 删掉 allowed-domains 配置（或配空数组）
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains: []
```

```java
ToolResult r = httpGetTool.execute(Map.of("url", "https://api.example.com/users"));
```

**期望**：

- `r.success() == false`
- `r.errorMessage()` 含 `"not in allowed-domains"`（即使 host 在原 allowed-domains 列表内——空配置 = 全部拒绝）

---

## 场景 S4 — Notify 出站经 WebhookNotifyAdapter 走 Sandbox（既有契约固化）

### 前置

Profile YAML 配 notify 通道：

```yaml
# .oryxos/profiles/dev-team/profile.yaml
notify_channels:
  - type: webhook
    config:
      url: https://webhook.example.com/dev-team-hook
```

`application.yaml`：

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:
          - api.example.com   # 注意：不包含 webhook.example.com
```

### 步骤

### 4a. Notify 出站越域

```java
NotifyChannelAdapter adapter = new WebhookNotifyAdapter(sandbox, httpClient, objectMapper);
adapter.send(channelConfig, "test message", ProfileContext.of("dev-team"));
```

**期望**：

- 抛 `SandboxViolationException` 或 `NotifyException`（封装自 SandboxViolationException）
- `errorMessage` 含 `"host 'webhook.example.com' not in allowed-domains"`
- 实际 HTTP POST **未发出**（WireMock 零请求）

### 4b. Notify 出站 + 域名在白名单内

```yaml
# 改成 allowed-domains 含 webhook.example.com
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:
          - api.example.com
          - webhook.example.com
```

**期望**：

- HTTP POST 正常发出
- Webhook 接收方收到 `{"content":"test message"}`

### 4c. Notify 出站 IPv6 字面拦截（007 补强）

```yaml
# 业务方误配 URL 为 IP 字面值
notify_channels:
  - type: webhook
    config:
      url: http://[::1]:9999/hook
```

**期望**：

- 抛 `SandboxViolationException` 或 `NotifyException`
- `errorMessage` 含 `"IP-literal"`

---

## 验收流程

```bash
# 1. 切到工作分支
git checkout 007-sandbox-whitelist

# 2. 跑既有 SandboxEnforcementIntegrationTest（005 阶段基线，007 完成后应仍然通过）
mvn -pl oryxos-tool -am test -Dtest=SandboxEnforcementIntegrationTest

# 3. 跑新增的 4 类 ActionType 单元测试 + 集成测试
mvn -pl oryxos-tool -am test -Dtest='WhitelistSandboxTest,FilePathSandboxTest,ShellCommandSandboxTest,SandboxEnforcementIntegrationTest'

# 4. 跑 mvn verify 全模块
mvn verify

# 5. 跑性能断言（spec SC-006 P95 ≤ 5ms）
mvn -pl oryxos-tool -am test -Dtest=SandboxPerformanceBenchmarkIT

# 6. 跑契约断言（spec SC-007 接口字节级不变）
mvn -pl oryxos-tool -am test -Dtest=SandboxApiCompatibilityIT
```

### 通过标准

| 检查项 | 通过条件 | spec 编号 |
|--------|---------|----------|
| `SandboxEnforcementIntegrationTest` 全过 | 4 / 4（修改 + 新增后） | SC-001 |
| `WhitelistSandboxTest` 全过 | FILE + SHELL 新增用例 ≥ 8 个全过 | SC-002 |
| `mvn verify` 全模块绿 | 所有模块 0 失败 0 错误 | SC-004 |
| `tool_invocations` 审计行 | 100% 拦截事件含 `error_message startsWith "sandbox violation: "` | SC-003 |
| 性能 benchmark P95 ≤ 5ms | `enforce()` wall-time 单测 P95 ≤ 5ms | SC-006 |
| 接口字节级不变 | `javap -p Sandbox.class` 签名与 005 阶段一致 | SC-007 |
| Notify 链路走 Sandbox | `WebhookNotifyAdapter.send()` 触发 sandbox.enforce（HTTPS 抓包或 WireMock 验证） | FR-007 / SC-005 |
| fail-closed 默认 | 空白名单配置 → 全部 Tool 调用返回 sandbox violation | spec FR-011 |

---

## 端到端调试技巧

### 1. 验证审计表写入

```sql
sqlite3 .oryxos/oryxos.db \
  "SELECT tool_name, success, substr(error_message, 1, 80), duration_ms
   FROM tool_invocations
   WHERE error_message LIKE 'sandbox violation: %'
   ORDER BY timestamp DESC
   LIMIT 20;"
```

### 2. 验证 errorMessage 不含 stack trace

```bash
sqlite3 .oryxos/oryxos.db \
  "SELECT error_message FROM tool_invocations
   WHERE error_message LIKE 'sandbox violation: %'
     AND (error_message LIKE '%at %' OR error_message LIKE '%Exception in thread%');"
# 期望：零行（assertion 通过）

# 同时检查 stack trace 是否进了 cli-error.log
grep -c "SandboxViolationException" .oryxos/logs/oryxos-cli-error.log
# 期望：> 0（stack 100% 进日志，不进 error_message）
```

### 3. 验证性能 P95

```bash
mvn -pl oryxos-tool -am test -Dtest=SandboxPerformanceBenchmarkIT
# 报告：median ~1ms, P95 ~3ms, P99 ~5ms（目标 ≤ 5ms）
```

### 4. 验证接口字节级不变

```bash
git diff 005-tool-system 007-sandbox-whitelist -- oryxos-tool/src/main/java/io/oryxos/tool/sandbox/Sandbox.java oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxAction.java oryxos-tool/src/main/java/io/oryxos/tool/sandbox/ActionType.java oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxViolationException.java
# 期望：零变更（除可能的 Javadoc 注释）

# 验证 SandboxProperties 公共方法签名不变
git diff 005-tool-system 007-sandbox-whitelist -- oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxProperties.java
# 期望：仅新增内部类 + getter/setter；既有 getHttp/setHttp + getAllowedDomains/setAllowedDomains 字节级不变
```

---

## 升级路径验证（spec §9.4）

007 完成后，未来扩展到容器隔离（namespace + cgroups + seccomp）或 microVM（Firecracker / Kata / gVisor）时，**接口不变**：

```text
白名单（核心阶段 007）   →  容器隔离（扩展阶段）
Sandbox.enforce(SandboxAction)              [接口不变]
WhitelistSandbox                            → ContainerSandbox
Application-layer path/pattern check        → Kernel-layer syscall filter
```

**验证方式**：跑 spec SC-007 接口字节级不变断言（见上述"接口字节级不变"）；007 完成后任何扩展阶段实现的 `ContainerSandbox` / `MicroVmSandbox` 都 MUST 实现相同接口（`Sandbox.enforce(SandboxAction)`），业务方零代码迁移。

---

## 引用

- [spec.md FR-001..011](./spec.md)
- [spec.md SC-001..009](./spec.md)
- [research.md R-01..R-10](./research.md)
- [data-model.md §2.1 / §2.2](./data-model.md)
- [contracts/sandbox-whitelist.md §12-§16](./contracts/sandbox-whitelist.md)
- [specs/005-tool-system/contracts/sandbox.md](../005-tool-system/contracts/sandbox.md)
- [SandboxEnforcementIntegrationTest.java](../../../oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java)
- [CLAUDE.md §9.4 / §18](../../CLAUDE.md)