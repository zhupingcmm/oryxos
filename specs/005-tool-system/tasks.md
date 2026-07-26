---
description: "Task list for Tool system implementation (005-tool-system)"
---

# Tasks: Tool 体系（Agent 的"双手"）

**Input**: Design documents from `/specs/005-tool-system/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅, quickstart.md ✅
**Tests**: 显式包含（宪法 §VII "Demo-First" + [research.md R-12](./research.md)）；JUnit 5 + Mockito + WireMock + 端到端冒烟脚本。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4, US5)
- Include exact file paths in descriptions

## Path Conventions

- **Maven multi-module**: 9 modules under `oryxos-{core,tool,memory,provider,storage,web,channel-cli,cli,boot}/`
- **Test paths**: `oryxos-<module>/src/test/java/io/oryxos/...`
- **DB migrations**: `oryxos-storage/src/main/resources/db/migration/V<n>__<name>.sql`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 项目初始化 + Tool 模块脚手架 + DB 演进前置条件

- [x] T001 [P] Verify 9 Maven modules exist (oryxos-core/tool/memory/provider/storage/web/channel-cli/cli/boot) per plan.md §4.2
- [x] T002 [P] Add `wiremock-standalone:3.5.4` as test scope dependency to `oryxos-tool/pom.xml`
- [x] T003 Create `db/migration/` resource directory at `oryxos-storage/src/main/resources/db/migration/` (per data-model §7.2)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 阻塞所有 User Story 的核心基础设施 —— DB schema 演进、ToolRegistry 冲突检测、source 字段抽取。这些不完成，任何 Tool 都不能正确注册 / 审计。

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [x] T004 Write V3 DDL migration `oryxos-storage/src/main/resources/db/migration/V3__add_tool_source.sql` per [data-model.md §7.2](./data-model.md) (ALTER TABLE tool_invocations ADD COLUMN source + idx_tool_source index + DOWN rollback)
- [x] T005 Update JPA entity `oryxos-storage/src/main/java/io/oryxos/storage/entity/ToolInvocationRecord.java`: add `@Column(name="source", nullable=false) private String source` field + extend constructor signature + add validate() check per [data-model.md §7.3](./data-model.md)
- [x] T006 Modify `oryxos-core/src/main/java/io/oryxos/core/tool/ToolRegistry.java` `of()` method: throw `IllegalStateException` on duplicate tool name per [research.md R-08](./research.md) (spec FR-015)
- [x] T007 [P] Add JUnit test `oryxos-core/src/test/java/io/oryxos/core/tool/ToolRegistryTest.java`: `conflict_fails_at_construction()` verifying `of()` throws IllegalStateException with both class names in message
- [x] T008 Extend `oryxos-core/src/main/java/io/oryxos/core/DefaultToolExecutor.java`: rename `extractNotifyAuditFields` → `extractExtraAuditFields(tool, result)`; add `resolveSource(tool)` returning "builtin"/"mcp"/"java_bean" per [research.md R-09](./research.md) + [tool-executor.md §3.3](./contracts/tool-executor.md)
- [x] T009 [P] Add JUnit test `oryxos-core/src/test/java/io/oryxos/core/DefaultToolExecutorTest.java`: verify `resolveSource` returns "builtin" for `io.oryxos.tool.*` class, "mcp" for `io.oryxos.tool.mcp.*` class, "java_bean" otherwise
- [x] T010 Register shared `HttpClient` bean in `oryxos-boot/src/main/java/io/oryxos/boot/config/HttpClientConfig.java`: `HttpClient.newBuilder().connectTimeout(5s).build()` (single instance shared by HTTP Tool + WebhookNotifyAdapter) per [research.md R-01](./research.md)
- [x] T011 Update `oryxos-boot/src/main/java/io/oryxos/boot/config/NotifyToolConfig.java` to `@Primary @Bean ToolRegistry` (already done in 004) — verify inclusion of all new Tool Beans in registry
- [x] T012 Verify `WhitelistSandbox` is wired with `SandboxProperties` (`oryxos-boot/src/main/java/io/oryxos/boot/config/SandboxConfig.java`): host suffix matching + IP rejection per [sandbox.md §3.1](./contracts/sandbox.md)

**Checkpoint**: Foundation ready — ToolRegistry enforces uniqueness, audit source field extracted, DB schema ready, HttpClient shared, Sandbox wired. User story implementation can now begin.

---

## Phase 3: User Story 1 — Agent 调一个内置 Tool + 审计（P1）🎯 MVP

**Goal**: 实现 8 个新增内置 Tool（file_read / file_write / file_list / shell / http_get / http_post / save_memory / recall_memory；notify 已在 004 落地），每个 Tool 走 `DefaultToolExecutor` 标准派发 + 审计路径；通过 `tool list` 命令验证 9 个 Tool 全部注册。

**Independent Test**: `oryxos tool list` 输出 9 行（每行 source=builtin）；调一次 `http_get` 后 SQLite `tool_invocations` 增 1 行 `tool_name='http_get', source='builtin', success=true/false, duration_ms>0`。

### Tests for User Story 1 ⚠️ (TDD: write first, verify fail)

- [x] T013 [P] [US1] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/file/FileReadToolTest.java`: file_read success / file-not-found / path-is-dir / oversize cases per [builtin-tools.md §1](./contracts/builtin-tools.md)
- [x] T014 [P] [US1] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/file/FileWriteToolToolTest.java`: write success / parent-dir-create / append mode / write-failure cases per [builtin-tools.md §2](./contracts/builtin-tools.md)
- [x] T015 [P] [US1] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/file/FileListToolTest.java`: list success / not-a-directory / glob pattern filter cases per [builtin-tools.md §3](./contracts/builtin-tools.md)
- [x] T016 [P] [US1] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/shell/ShellToolTest.java`: safe command echo / rm-blocked / sleep-timeout / command-not-found cases per [builtin-tools.md §4](./contracts/builtin-tools.md) + [research.md R-03](./research.md)
- [x] T017 [P] [US1] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/http/HttpGetToolTest.java`: success / sandbox-blocked / ip-rejected / timeout cases using WireMock (port 8089) per [builtin-tools.md §5](./contracts/builtin-tools.md) + [research.md R-11](./research.md)
- [x] T018 [P] [US1] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/http/HttpPostToolTest.java`: success / sandbox-blocked / body-too-large cases using WireMock per [builtin-tools.md §6](./contracts/builtin-tools.md)
- [x] T019 [P] [US1] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/memory/SaveMemoryToolTest.java`: save success / scope-validation / MemoryService-failure cases (mock MemoryService) per [builtin-tools.md §8](./contracts/builtin-tools.md) + [research.md R-05](./research.md)
- [x] T020 [P] [US1] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/memory/RecallMemoryToolTest.java`: recall success / no-hits / top-k-limit cases (mock MemoryService) per [builtin-tools.md §9](./contracts/builtin-tools.md)

### Implementation for User Story 1

- [x] T021 [P] [US1] Create record `oryxos-tool/src/main/java/io/oryxos/tool/file/FileToolResult.java` per [data-model.md §3.1](./data-model.md): `(String path, long sizeBytes, String content, List<String> entries)`
- [x] T022 [P] [US1] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/file/FileReadTool.java` implements `OryxTool`: name="file_read", uses `java.nio.file.Files.readString` + `Sandbox.enforce(FILE_READ)`, returns `ToolResult.ok(content, FileToolResult)` per [builtin-tools.md §1](./contracts/builtin-tools.md)
- [x] T023 [P] [US1] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/file/FileWriteTool.java` implements `OryxTool`: name="file_write", uses `Files.writeString` + creates parent dir + handles `append` flag per [builtin-tools.md §2](./contracts/builtin-tools.md)
- [x] T024 [P] [US1] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/file/FileListTool.java` implements `OryxTool`: name="file_list", uses `Files.list` + glob pattern filter via `PathMatcher` per [builtin-tools.md §3](./contracts/builtin-tools.md)
- [x] T025 [P] [US1] Create `@ConfigurationProperties` record `oryxos-tool/src/main/java/io/oryxos/tool/shell/ShellToolProperties.java` per [data-model.md §5.2](./data-model.md): `(int timeoutSeconds=30, int maxOutputBytes=65536, List<String> dangerousCommands)`
- [x] T026 [US1] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/shell/ShellTool.java` implements `OryxTool`: name="shell", checks `dangerous-commands` blacklist FIRST then `ProcessBuilder` with `waitFor(timeout)`, returns `ShellToolResult(command, exitCode, stdout, stderr, durationMs)` per [builtin-tools.md §4](./contracts/builtin-tools.md) + [research.md R-03](./research.md) (depends on T025)
- [x] T027 [P] [US1] Create record `oryxos-tool/src/main/java/io/oryxos/tool/shell/ShellToolResult.java` per [data-model.md §3.2](./data-model.md): `(String command, int exitCode, String stdout, String stderr, long durationMs)`
- [x] T028 [P] [US1] Create `@ConfigurationProperties` record `oryxos-tool/src/main/java/io/oryxos/tool/http/HttpToolProperties.java` per [data-model.md §5.1](./data-model.md): `(int timeoutSeconds=5, int maxResponseBytes=1048576)`
- [x] T029 [US1] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/http/HttpGetTool.java` implements `OryxTool`: name="http_get", `sandbox.enforce(HTTP_REQUEST, url)` THEN `HttpClient.send` with timeout + body-size limit, returns `HttpToolResult(statusCode, contentType, body, durationMs)` per [builtin-tools.md §5](./contracts/builtin-tools.md) + [research.md R-11](./research.md) (depends on T010, T028)
- [x] T030 [US1] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/http/HttpPostTool.java` implements `OryxTool`: name="http_post", same sandbox pattern, `POST(body)` with default `Content-Type: application/json` per [builtin-tools.md §6](./contracts/builtin-tools.md) (depends on T029)
- [x] T031 [P] [US1] Create record `oryxos-tool/src/main/java/io/oryxos/tool/http/HttpToolResult.java` per [data-model.md §3.3](./data-model.md): `(int statusCode, String contentType, String body, long durationMs)`
- [x] T032 [US1] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/memory/SaveMemoryTool.java` implements `OryxTool`: name="save_memory", wraps `MemoryService.save(content, MemoryScope.fromString(scope))`, returns `MemoryToolResult("save", scope, 1, null)` per [builtin-tools.md §8](./contracts/builtin-tools.md) + [research.md R-05](./research.md) (depends on MemoryService bean from US-3)
- [x] T033 [US1] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/memory/RecallMemoryTool.java` implements `OryxTool`: name="recall_memory", wraps `MemoryService.recallByKeyword(query, topK)`, returns `MemoryToolResult("recall", "core", hits.size(), snippets)` per [builtin-tools.md §9](./contracts/builtin-tools.md) (depends on T032)
- [x] T034 [P] [US1] Create record `oryxos-tool/src/main/java/io/oryxos/tool/memory/MemoryToolResult.java` per [data-model.md §3.4](./data-model.md): `(String operation, String scope, int entryCount, List<String> snippets)`
- [x] T035 [US1] Update `oryxos-cli/src/main/java/io/oryxos/cli/command/ToolListCommand.java`: include `source` column (builtin/mcp/java_bean) in output table per [quickstart.md §2](./quickstart.md)
- [x] T036 [US1] Update `oryxos-boot/src/main/java/io/oryxos/boot/config/ToolSystemConfig.java` (NEW): register all 8 new `@Component` Tool Beans (FileReadTool / FileWriteTool / FileListTool / ShellTool / HttpGetTool / HttpPostTool / SaveMemoryTool / RecallMemoryTool) into `ToolRegistry` via `@Bean` methods (NotifyToolConfig already handles notify)
- [x] T037 [US1] Add default `dangerous-commands` list to `application.yaml` (`oryxos-boot/src/main/resources/application.yaml`): `rm, mkfs, dd, shutdown, reboot, wget, curl, chmod, chown, mv, cp` per [research.md R-03](./research.md)
- [x] T038 [US1] Add default `tool.sandbox.http.allowed-hosts: [localhost, 127.0.0.1]` to `application.yaml` per [sandbox.md §5.2](./contracts/sandbox.md)
- [x] T039 [US1] Integration test `oryxos-tool/src/test/java/io/oryxos/tool/integration/BuiltinToolsIntegrationTest.java`: `@SpringBootTest` + WireMock; covers all 8 new Tools end-to-end (success + sandbox-blocked for each) per [research.md R-12](./research.md)

**Checkpoint**: At this point, 8 new built-in Tools + notify (9 total) are registered; `tool list` shows 9 rows; each Tool has unit + integration tests. User Story 1 is fully functional and independently testable.

---

## Phase 4: User Story 2 — Tool 调用必经 Sandbox 安全护栏（P1）

**Goal**: 验证所有 Tool 副作用在执行前过 `Sandbox.enforce()`；越界动作（HTTP 越域 / shell 黑名单 / 未来扩展的 FILE 越界）在执行**前**被拦掉，零副作用。

**Independent Test**: LLM 调 `http_get(url="https://evil.example.com/")` 触发 `SandboxViolationException`；WireMock 零请求计数；`tool_invocations` 写入 success=false 审计行，errorMessage 含 "sandbox"。

### Tests for User Story 2 ⚠️

- [x] T040 [P] [US2] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/WhitelistSandboxTest.java`: http-allowed-host-passes / http-unknown-host-throws / http-ip-literal-rejected / http-invalid-scheme-throws / file-read-no-op / shell-no-op cases per [sandbox.md §8](./contracts/sandbox.md)

### Implementation for User Story 2

- [x] T041 [US2] Extend `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java`: add InetAddress-based IP rejection (already partially done in 004) + scheme validation (http/https only) per [sandbox.md §3.1](./contracts/sandbox.md)
- [x] T042 [P] [US2] Verify `SandboxViolationException` is thrown with structured fields (type, target, reason) in `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxViolationException.java` per [sandbox.md §1](./contracts/sandbox.md)
- [x] T043 [US2] Verify `DefaultToolExecutor.invoke()` catches `SandboxViolationException` separately from generic `RuntimeException`, formats message as `"sandbox violation: <reason> (<type>: <target>)"` per [tool-executor.md §3.4](./contracts/tool-executor.md)
- [x] T044 [US2] Verify ShellTool dangerous-commands blacklist runs BEFORE `sandbox.enforce()` (so blacklist hit returns immediately without sandbox lookup) per [research.md R-03](./research.md) + [sandbox.md §3.3](./contracts/sandbox.md)
- [x] T045 [US2] Integration test `oryxos-tool/src/test/java/io/oryxos/tool/integration/SandboxEnforcementIntegrationTest.java`: 4 sandbox enforcement scenarios (HTTP unknown host / HTTP IP literal / shell rm-blocked / file-read no-op verified) per [quickstart.md §9](./quickstart.md)
- [x] T046 [US2] Add documentation comment in `WhitelistSandbox.java` explicitly stating "核心阶段 FILE_READ / FILE_WRITE / SHELL_COMMAND 是 no-op；扩展阶段补 allowed-paths / allowed-commands" per [sandbox.md §2](./contracts/sandbox.md)

**Checkpoint**: Sandbox enforcement is verified for HTTP (full) and Shell (blacklist); FILE is no-op by design. SC-003 / SC-005 satisfied.

---

## Phase 5: User Story 3 — 零代码 / 轻代码接入新 Tool（P2）

**Goal**: 通过 `mcp_servers.yaml` 配置 + `McpClientService` 启动期握手 + `McpToolAdapter` 把 MCP server 的 tool 转成 `OryxTool`，注册到 `ToolRegistry`。LLM 在 ReAct 循环里调到该 Tool，`tool_invocations` 写入 `source='mcp'`。

**Independent Test**: `mcp_servers.yaml` 登记 mock MCP server（HTTP 模式）；启动 Spring Boot → 握手成功；Profile 配 `mcp_servers: [mock]` → LLM 调 mock 提供的 `list_pull_requests` Tool → ToolResult 返回 mock 内容；审计行 `source='mcp'`。

### Tests for User Story 3 ⚠️

- [x] T047 [P] [US3] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/mcp/HttpMcpTransportTest.java`: sendRequest-success / sendRequest-connection-fail / sendRequest-timeout / close-clears-resources cases using WireMock as mock MCP server per [mcp-adapter.md §10](./contracts/mcp-adapter.md)
- [x] T048 [P] [US3] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/mcp/StdioMcpTransportTest.java`: spawn-process-success / send-line-and-read-line / close-kills-process cases (mock command = `cat` echo back JSON-RPC) per [mcp-adapter.md §10](./contracts/mcp-adapter.md)
- [x] T049 [P] [US3] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/mcp/McpClientServiceTest.java`: startup-handshake-succeeds / startup-server-unreachable-fails-fast / startup-protocol-mismatch-fails / list-tools-returns-descriptors per [mcp-adapter.md §5.2](./contracts/mcp-adapter.md) + [research.md R-10](./research.md)
- [x] T050 [P] [US3] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/mcp/McpToolAdapterTest.java`: adapt-descriptor-to-tool preserves name+description+inputSchema; source-resolution returns "mcp" for `io.oryxos.tool.mcp.*` per [mcp-adapter.md §6](./contracts/mcp-adapter.md) + [research.md R-06](./research.md)
- [x] T051 [P] [US3] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/mcp/McpToolTest.java`: execute-dispatches-tools-call / execute-handles-error-response / execute-handles-connection-lost per [mcp-adapter.md §7](./contracts/mcp-adapter.md) + spec US-3 场景 4

### Implementation for User Story 3

- [x] T052 [P] [US3] Create record `oryxos-tool/src/main/java/io/oryxos/tool/mcp/McpServerConnection.java` per [data-model.md §4.1](./data-model.md): `(String name, String transport, String endpoint, Map capabilities, List<String> toolNames, ConnectionState state)`
- [x] T053 [P] [US3] Create exception `oryxos-tool/src/main/java/io/oryxos/tool/mcp/McpConnectionException.java` per [data-model.md §4.3](./data-model.md): RuntimeException with serverName field
- [x] T054 [P] [US3] Create record `oryxos-tool/src/main/java/io/oryxos/tool/mcp/McpResponse.java` per [data-model.md §4.3](./data-model.md): `(int id, Map result, Map error)` + `isError()` / `errorMessage()` helpers
- [x] T055 [P] [US3] Create interface `oryxos-tool/src/main/java/io/oryxos/tool/mcp/McpTransport.java` per [data-model.md §4.3](./data-model.md): `sendRequest(method, params) → McpResponse`, `close()`
- [x] T056 [US3] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/mcp/HttpMcpTransport.java` implements `McpTransport`: JSON-RPC over HTTP POST + SSE response parsing using shared `HttpClient` from T010 per [mcp-adapter.md §4](./contracts/mcp-adapter.md) (depends on T055)
- [x] T057 [US3] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/mcp/StdioMcpTransport.java` implements `McpTransport`: spawn child process via `ProcessBuilder`, stdin writes JSON-RPC lines, stdout reads lines per [mcp-adapter.md §4](./contracts/mcp-adapter.md) (depends on T055)
- [x] T058 [P] [US3] Create `@ConfigurationProperties` record `oryxos-tool/src/main/java/io/oryxos/tool/mcp/McpClientProperties.java` per [data-model.md §5.3](./data-model.md): `(int connectTimeoutSeconds=5, int requestTimeoutSeconds=30, boolean failFastOnStartup=true)`
- [x] T059 [US3] Create record `oryxos-tool/src/main/java/io/oryxos/tool/mcp/McpToolDescriptor.java`: `(String name, String description, String inputSchema)`
- [x] T060 [US3] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/mcp/McpToolAdapter.java` per [data-model.md §4.2](./data-model.md) + [mcp-adapter.md §6](./contracts/mcp-adapter.md): `adapt(serverName, descriptors, transport) → List<OryxTool>` (depends on T059)
- [x] T061 [US3] Create `oryxos-tool/src/main/java/io/oryxos/tool/mcp/McpTool.java` implements `OryxTool` per [data-model.md §4.2](./data-model.md) + [mcp-adapter.md §7](./contracts/mcp-adapter.md): execute calls `transport.sendRequest("tools/call", {name, arguments})`, returns ToolResult.ok or error (depends on T053, T054, T060)
- [x] T062 [US3] Create YAML config loader `oryxos-tool/src/main/java/io/oryxos/tool/mcp/McpServerConfig.java`: record `(String name, String transport, String command, List<String> args, String url, String authToken, Map<String,String> env)` for parsing `mcp_servers.yaml`
- [x] T063 [US3] Create `@Component` `oryxos-tool/src/main/java/io/oryxos/tool/mcp/McpClientService.java` with `@PostConstruct startup()` per [mcp-adapter.md §5](./contracts/mcp-adapter.md): reads `mcp_servers.yaml`, creates transport, calls `initialize` + `tools/list`, registers McpTools into ToolRegistry; throws IllegalStateException on any failure (fail-fast) (depends on T056-T062)
- [x] T064 [US3] Update `oryxos-boot/src/main/java/io/oryxos/boot/config/McpClientConfig.java` (NEW): `@EnableConfigurationProperties(McpClientProperties.class)` + register McpClientService Bean (depends on T063)
- [x] T065 [US3] Integration test `oryxos-tool/src/test/java/io/oryxos/tool/integration/McpIntegrationTest.java`: spawn local mock MCP server (Python script simulating JSON-RPC over HTTP), verify McpClientService handshakes + registers Tools + Tool execution returns mock result + connection-lost returns ToolResult.error per [quickstart.md §7](./quickstart.md) + spec US-3 场景 4

**Checkpoint**: At this point, MCP subsystem is fully wired; mock MCP server tools appear in `tool list` with source=mcp; running them produces audit rows with source='mcp'; handshake failures abort startup. SC-006 partially satisfied (zero-code path).

---

## Phase 6: User Story 4 — 重代码接入：Java `@Tool` 自定义 Tool（P2）

**Goal**: 用户写一个最小 `EchoTool implements OryxTool`（<100 行），标 `@Component`，加到 classpath 即被 ToolRegistry 自动发现并注册；Profile 配 `tools: [echo]` 即可被 LLM 调到。

**Independent Test**: 启动 Spring Boot → `tool list` 显示 9 行 + `echo` 行（source=java_bean）= 共 10 行；Profile 配 `tools: [echo]` → LLM 调 echo → ToolResult.success=true, content=回显字符串；审计行 `source='java_bean'`。

### Tests for User Story 4 ⚠️

- [x] T066 [P] [US4] Contract test `oryxos-tool/src/test/java/io/oryxos/tool/javabean/EchoToolTest.java`: echo-success / null-args / exception-caught scenarios (custom Java Bean Tool under `io.oryxos.tool.javabean` package) per [research.md R-06](./research.md) + spec US-4 场景 2-3

### Implementation for User Story 4

- [x] T067 [P] [US4] Create `@Component` `oryxos-tool/src/test/java/io/oryxos/tool/javabean/EchoTool.java` implements `OryxTool`: name="echo", description="回显输入字符串（重代码接入示例）", execute returns ToolResult.ok(args.get("text").toString()) — total line count ≤ 100 (SC-007) per spec FR-008 第 3 档 + SC-007
- [x] T068 [US4] Verify ToolRegistry auto-discovers all `@Component implements OryxTool` via existing Spring bean scan in `NotifyToolConfig.java` (no additional registration code needed for java_bean Tools) per [research.md R-06](./research.md) (depends on T011)
- [x] T069 [US4] Verify DefaultToolExecutor's `resolveSource()` returns "java_bean" for tools outside `io.oryxos.tool.*` namespace; this test reuses T009's logic with a fixture class in `io.oryxos.tool.javabean.*` per [research.md R-09](./research.md)
- [x] T070 [US4] Verify RuntimeException in custom Tool is caught by `DefaultToolExecutor` and converted to `ToolResult.error("tool execution failed: <message>")` with audit success=false per [tool-executor.md §3.4](./contracts/tool-executor.md) + spec US-4 场景 3
- [x] T071 [US4] Integration test `oryxos-tool/src/test/java/io/oryxos/tool/integration/JavaBeanToolIntegrationTest.java`: `@SpringBootTest` with EchoTool registered; verify `tool list` shows echo with source=java_bean; invoke echo → ToolResult; verify audit row source='java_bean' per [quickstart.md §11](./quickstart.md) troubleshooting + spec US-4

**Checkpoint**: User Story 4 demonstrates the heavy-code path is functional. SC-007 satisfied (EchoTool < 100 lines).

---

## Phase 7: User Story 5 — NotifyTools 作为出站 Tool（P2）

**Goal**: 验证 NotifyTool 是 Tool 体系的一等公民 —— 复用 `ToolRegistry` + `tool_invocations` 审计；Profile 配 `notify_channels` 时 `notify` 出现在可用 Tool 列表，未配时不出现；Notify 调用仍走 Sandbox 白名单（继承 US-2）。

**Independent Test**: Profile 配 `notify_channels: [...]` → `tool list` 输出含 `notify`（source=builtin, channel=default）；Profile 不配 → 输出不含 `notify`；调一次 notify → 审计行 success=true/false, channel=feishu, notify_status_code=200 (spec US-5 场景 1+3)。

### Tests for User Story 5 ⚠️

- [x] T072 [P] [US5] Verify NotifyTool is already covered by 004-notify-channel integration tests (no new tests required) — defer to T073 for cross-cutting audit verification

### Implementation for User Story 5

- [x] T073 [US5] Verify PromptBuilder's Tool list filter (`oryxos-core/src/main/java/io/oryxos/core/PromptBuilder.java`): filters out `notify` Tool when profile has empty `notify_channels` list per spec US-5 场景 2
- [x] T074 [US5] Verify `tool_invocations` Notify rows include `channel` (per 004 V2 DDL) AND `source='builtin'` (per V3 DDL T004) AND `notify_status_code=200/4xx/5xx` per [data-model.md §7](./data-model.md) + [tool-executor.md §3.5](./contracts/tool-executor.md) + spec US-5 场景 3
- [x] T075 [US5] Cross-cutting integration test `oryxos-tool/src/test/java/io/oryxos/tool/integration/NotifyToolInRegistryIntegrationTest.java`: `@SpringBootTest` with profile that has `notify_channels` configured → Tool list contains `notify`; profile without → Tool list does NOT contain `notify`; invoke notify → audit row consistent per [quickstart.md §10](./quickstart.md)

**Checkpoint**: User Story 5 confirms NotifyTool is a first-class Tool citizen with proper Profile-level visibility filtering and consistent audit schema.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: 端到端冒烟脚本 + 全场景验证 + 最终分析

- [x] T076 Create `scripts/tool-smoke.sh` (or `.bat` for Windows) per [research.md R-12](./research.md): orchestrates 6 scenarios from [quickstart.md](./quickstart.md) (ToolList / File / Shell / HTTP / Memory / MCP) using WireMock + local tmp dirs + mock MCP server; produces pass/fail summary
- [x] T077 [P] Verify `DefaultToolExecutor` audit writes 1 row per invocation across all 5 stories (SC-002) — integration test `oryxos-tool/src/test/java/io/oryxos/tool/integration/AuditConsistencyIntegrationTest.java`: count `tool_invocations` rows before/after a known N Tool invocations, assert diff=N
- [x] T078 [P] Verify NO duplicate Tool execution (FR-007 / SC-009) — integration test `oryxos-tool/src/test/java/io/oryxos/tool/integration/NoDuplicateToolExecutionTest.java`: invoke a Tool 1 time, assert EXACTLY 1 audit row for that (tool_name, session_id, args)
- [x] T079 [P] Verify Tool errorMessage contains 0% stack traces (NFR-004 / SC-009) — JUnit test that parses ToolResult.errorMessage and asserts no `at io.oryxos.*` or `Exception:` patterns
- [x] T080 [P] Update `docs/` (or `CLAUDE.md` §9 if needed) with final Tool体系 reference (5.5 内置 + MCP 接入 + Sandbox 规则) — cross-reference quickstart.md
- [x] T081 Run `/speckit.analyze` against spec.md + plan.md + tasks.md to verify no drift (constitution compliance, FR coverage, SC coverage) per [CLAUDE.md §10](../CLAUDE.md) "每个 US 完成后必须跑 /speckit.analyze"
- [x] T082 [P] Add `Source` column to `ToolListCommand` output (already in T035) and verify MCP + java_bean Tools are correctly labeled in `tool list` end-to-end via smoke.sh
- [x] T083 Run `mvn verify` on all 9 modules and confirm 0 failures (SC-008 baseline from 004 + 005) per [CLAUDE.md §17](../CLAUDE.md) "git 协作约定"
- [x] T084 [P] Commit per-US using `feat(005): <summary>` convention with file lists per US boundary (depends on [CLAUDE.md §17](../CLAUDE.md) git convention + `.specify/extensions.yml` `after_implement` hook)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion (T001-T003) - BLOCKS all user stories
- **User Stories (Phase 3-7)**: All depend on Foundational phase completion
  - **US-1 → US-2 dependency**: US-1 Tools must exist before US-2 sandbox tests can wire them (T026, T029-T030 must precede T044-T045)
  - **US-3 → US-1 dependency**: US-3 MemoryService reference (T032-T033 calls `memoryService.save/recallByKeyword`); MemoryService already exists in 003 spec (referenced, not implemented)
  - **US-1 / US-3 → US-4 / US-5 dependency**: US-4 EchoTool + US-5 NotifyTool integration tests need `tool list` working (T035)
- **Polish (Phase 8)**: Depends on all desired user stories being complete

### User Story Dependencies

```text
┌─────────────┐
│  Phase 1    │  T001-T003  Setup
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 2    │  T004-T012  Foundational (BLOCKS all US)
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 3    │  T013-T039  US-1 内置 Tool + 审计 (P1) 🎯 MVP
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 4    │  T040-T046  US-2 Sandbox (P1)
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 5    │  T047-T065  US-3 MCP (P2)
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 6    │  T066-T071  US-4 Java Bean (P2)
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 7    │  T072-T075  US-5 Notify (P2)
└──────┬──────┘
       ▼
┌─────────────┐
│  Phase 8    │  T076-T084  Polish & smoke & analyze
└─────────────┘
```

- **User Story 1 (P1)**: Can start after Foundational — No dependencies on other stories
- **User Story 2 (P1)**: Can start after US-1 Tools exist (T022-T034) for sandbox enforcement tests
- **User Story 3 (P2)**: Can start after US-1 ToolRegistry+audit ready (T008 done) for source='mcp' verification
- **User Story 4 (P2)**: Can start after US-1 ToolRegistry auto-discovery verified (T036)
- **User Story 5 (P2)**: Can start after US-1 `tool list` working (T035); NotifyTool is already in 004

### Within Each User Story

- Tests (T013-T020, T040, T047-T051, T066, T072) MUST be written FIRST and FAIL before implementation
- ConfigurationProperties records BEFORE Tool classes that use them (T025 before T026, T028 before T029)
- Result records BEFORE Tool classes that return them (T021 before T022, T027 before T026, T031 before T029, T034 before T032)
- Transport interface BEFORE concrete impls (T055 before T056/T057)
- Tool classes BEFORE Spring bean wiring / integration tests
- Story complete before moving to next priority

### Parallel Opportunities

- Phase 1: All [P] tasks (T001, T002, T003) run in parallel
- Phase 2: Most tasks parallel except T008 (extends existing class)
- Phase 3 (US-1): Many [P] tasks — 8 Tool classes + 8 test classes can be developed in parallel by separate "developers" (T013-T020 tests, T021-T034 implementation grouped by category)
- Phase 4 (US-2): Tests parallel (T040)
- Phase 5 (US-3): Heavily parallel — 5 test classes + 11 implementation files (T047-T065)
- Phase 6 (US-4): EchoTool + 1 test parallel (T066-T067)
- Phase 7 (US-5): Verification tasks parallel (T073-T075)
- Phase 8: Polish [P] tasks run in parallel (T077-T080, T082)

---

## Parallel Example: User Story 1 (US-1 MVP)

```bash
# Phase 3 parallel group A: All 8 unit tests written first (TDD)
Task: "T013 [P] [US1] FileReadToolTest in oryxos-tool/src/test/java/io/oryxos/tool/file/FileReadToolTest.java"
Task: "T014 [P] [US1] FileWriteToolTest in oryxos-tool/src/test/java/io/oryxos/tool/file/FileWriteToolTest.java"
Task: "T015 [P] [US1] FileListToolTest in oryxos-tool/src/test/java/io/oryxos/tool/file/FileListToolTest.java"
Task: "T016 [P] [US1] ShellToolTest in oryxos-tool/src/test/java/io/oryxos/tool/shell/ShellToolTest.java"
Task: "T017 [P] [US1] HttpGetToolTest in oryxos-tool/src/test/java/io/oryxos/tool/http/HttpGetToolTest.java"
Task: "T018 [P] [US1] HttpPostToolTest in oryxos-tool/src/test/java/io/oryxos/tool/http/HttpPostToolTest.java"
Task: "T019 [P] [US1] SaveMemoryToolTest in oryxos-tool/src/test/java/io/oryxos/tool/memory/SaveMemoryToolTest.java"
Task: "T020 [P] [US1] RecallMemoryToolTest in oryxos-tool/src/test/java/io/oryxos/tool/memory/RecallMemoryToolTest.java"

# Phase 3 parallel group B: All result records + Properties (T021, T025, T028, T027, T031, T034)
Task: "T021 [P] [US1] FileToolResult record in oryxos-tool/src/main/java/io/oryxos/tool/file/FileToolResult.java"
Task: "T025 [P] [US1] ShellToolProperties record in oryxos-tool/src/main/java/io/oryxos/tool/shell/ShellToolProperties.java"
Task: "T027 [P] [US1] ShellToolResult record in oryxos-tool/src/main/java/io/oryxos/tool/shell/ShellToolResult.java"
Task: "T028 [P] [US1] HttpToolProperties record in oryxos-tool/src/main/java/io/oryxos/tool/http/HttpToolProperties.java"
Task: "T031 [P] [US1] HttpToolResult record in oryxos-tool/src/main/java/io/oryxos/tool/http/HttpToolResult.java"
Task: "T034 [P] [US1] MemoryToolResult record in oryxos-tool/src/main/java/io/oryxos/tool/memory/MemoryToolResult.java"

# Phase 3 sequential: Tool classes (depend on their Properties/Result records)
Task: "T022 [US1] FileReadTool class in oryxos-tool/src/main/java/io/oryxos/tool/file/FileReadTool.java"
Task: "T026 [US1] ShellTool class in oryxos-tool/src/main/java/io/oryxos/tool/shell/ShellTool.java (depends on T025)"
Task: "T029 [US1] HttpGetTool class in oryxos-tool/src/main/java/io/oryxos/tool/http/HttpGetTool.java (depends on T010, T028)"
Task: "T030 [US1] HttpPostTool class in oryxos-tool/src/main/java/io/oryxos/tool/http/HttpPostTool.java (depends on T029)"
Task: "T032 [US1] SaveMemoryTool class in oryxos-tool/src/main/java/io/oryxos/tool/memory/SaveMemoryTool.java"
Task: "T033 [US1] RecallMemoryTool class in oryxos-tool/src/main/java/io/oryxos/tool/memory/RecallMemoryTool.java"

# Phase 3 final: Wiring + integration
Task: "T036 [US1] ToolSystemConfig registers all 8 Tools into ToolRegistry"
Task: "T037 [US1] application.yaml default dangerous-commands"
Task: "T038 [US1] application.yaml default allowed-hosts"
Task: "T039 [US1] BuiltinToolsIntegrationTest end-to-end"
```

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2 = P1 only)

P1 是 [CLAUDE.md §10](../CLAUDE.md) 与宪法 §V 强调的核心安全能力。MVP 路径：

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004-T012)
3. Complete Phase 3: User Story 1 (T013-T039) — 内置 Tool + 审计
4. Complete Phase 4: User Story 2 (T040-T046) — Sandbox 护栏
5. **STOP and VALIDATE**: 
   - `mvn verify` 全绿
   - `tool list` 输出 9 行
   - 调一次 `http_get` → 1 行 audit + ToolResult
   - 调一次 `shell(rm -rf /)` → sandbox violation + 0 副作用
6. Deploy/demo if ready (3 Demos at least partially functional: daily-weather via http_get + notify)

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. Add US-1 → Test independently → Deploy/Demo (MVP P1!)
3. Add US-2 → Test independently → Deploy/Demo (MVP P1 complete)
4. Add US-3 → Test independently → Deploy/Demo (zero-code path available)
5. Add US-4 → Test independently → Deploy/Demo (heavy-code path available)
6. Add US-5 → Test independently → Deploy/Demo (Notify as first-class Tool citizen)
7. Polish + smoke + analyze → Production-ready Tool system

### Parallel Team Strategy

With multiple developers:

1. Team completes Phase 1 + Phase 2 together (T001-T012)
2. Once Foundational done:
   - Developer A: User Story 1 (T013-T039) — built-in Tools + audit
   - Developer B: User Story 2 (T040-T046) — sandbox enforcement (blocked until A's Tools exist)
   - Developer C: User Story 3 (T047-T065) — MCP subsystem (parallel to A)
3. After US-1 done:
   - Developer A continues to US-4 (T066-T071) — java bean Tool
   - Developer D: US-5 (T072-T075) — Notify in registry
4. Stories complete and integrate independently via ToolRegistry

---

## Task Count Summary

| Phase | Story | Tasks | Parallelizable |
|-------|-------|-------|----------------|
| Phase 1 | Setup | T001-T003 (3) | T001, T002, T003 |
| Phase 2 | Foundational | T004-T012 (9) | T007, T009 |
| Phase 3 | US-1 内置 Tool + 审计 (P1) | T013-T039 (27) | T013-T020 (8 tests), T021-T034 (impl grouped) |
| Phase 4 | US-2 Sandbox (P1) | T040-T046 (7) | T040, T042 |
| Phase 5 | US-3 MCP (P2) | T047-T065 (19) | T047-T051 (5 tests), T052-T063 (impl) |
| Phase 6 | US-4 Java Bean (P2) | T066-T071 (6) | T066, T067 |
| Phase 7 | US-5 Notify (P2) | T072-T075 (4) | T073-T075 |
| Phase 8 | Polish | T076-T084 (9) | T077-T080, T082 |
| **Total** | | **84 tasks** | **~50% parallelizable** |

---

## Coverage Matrix

| FR / SC | Tasks |
|---------|-------|
| FR-001 (OryxTool interface) | T011 (NotifyToolConfig), T036 (ToolSystemConfig) |
| FR-002 (ToolRegistry) | T006, T011 |
| FR-003 (9 built-in Tools) | T022-T034, T036 (Notify in 004) |
| FR-004 (Sandbox pre-check) | T026 (ShellTool), T029-T030 (HttpTools), T041-T044 |
| FR-005 (audit + source field) | T004-T005 (DB), T008-T009 (extractor) |
| FR-006 (Spring AI schema) | (verified via Spring AI starter) |
| FR-007 (no auto-execution) | T078 (NoDuplicateToolExecutionTest) |
| FR-008 (3-tier access) | T036 (built-in), T047-T065 (MCP), T067 (Java Bean) |
| FR-009 (McpClientService) | T047-T065 |
| FR-010 (NotifyChannelAdapter) | (already in 004) |
| FR-011 (Profile.tools[] filter) | T073 |
| FR-012 (no RuntimeException) | T043, T070 |
| FR-013 (all in oryxos-tool) | All file/shell/http/memory/mcp under `oryxos-tool/src/main/java/io/oryxos/tool/` |
| FR-014 (MAX_ITERATIONS serial) | (inherited from 002 ReActLoop; no new task) |
| FR-015 (Tool name conflict fail-fast) | T006, T007 |
| SC-001 (3 Demos) | T039 + T046 + T075 + T076 |
| SC-002 (audit row count) | T077 |
| SC-003 (sandbox enforcement) | T040-T046 |
| SC-004 (≤30s wall-time) | T026 (shell timeout), T029 (HTTP timeout), T078 |
| SC-005 (Tool failure → LLM sees error) | T043, T070 |
| SC-006 (zero-code in ≤30min) | T065 (MCP integration), T076 (smoke) |
| SC-007 (heavy-code ≤100 lines) | T067 (EchoTool line count check) |
| SC-008 (mvn verify green) | T083 |
| SC-009 (no stack trace in errorMessage) | T079 |
| SC-010 (no kernel mod needed) | T067 (EchoTool example demonstrates this) |
| SC-011 (audit completeness) | T074 + T077 |

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story is independently completable and testable
- Tests MUST fail before implementing (TDD per [research.md R-12](./research.md))
- Commit after each task or logical group; use `feat(005): <summary>` convention (per `.specify/extensions.yml` `after_implement` hook)
- Stop at any checkpoint to validate story independently (especially P1 MVP at end of Phase 4)
- Avoid: vague tasks, same-file conflicts, cross-story dependencies that break independence
- Reminder: Update the source column (T008 / T036) for ALL Tools so `tool list` correctly shows source
- Reminder: 004 V2 DDL + 005 V3 DDL must run in sequence on existing databases (or fresh init)

---

## Suggested MVP Scope

**MVP = User Story 1 + User Story 2 (both P1)** = T001-T046 = **46 tasks**.

This delivers:
- 9 built-in Tools registered + listed
- `DefaultToolExecutor` with source resolution + audit
- Sandbox enforcement (HTTP whitelist + Shell blacklist)
- `mvn verify` green + audit consistency verified

Defer to post-MVP: US-3 (MCP), US-4 (Java Bean), US-5 (Notify as Tool citizen). These are P2 and extend the system without changing its core safety guarantees.
