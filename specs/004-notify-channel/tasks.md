---
description: "Notify 出站推送（US-4 子能力）的实现任务列表"
---

# Tasks: Notify 出站推送（US-4 子能力）

**Input**: 来自 `/specs/004-notify-channel/` 的设计文档

**前置依赖**：

- [plan.md](plan.md)（必填，技术栈与结构）
- [spec.md](spec.md)（必填，User Story 与优先级）
- [research.md](research.md)（R-01..R-10 决策）
- [data-model.md](data-model.md)（实体与 schema 演进）
- [contracts/](contracts/)（notify-tool / webhook-payload / channel-config）

**测试**：本特性**显式要求** TDD 风格的测试任务（参见 spec FR-001..014 + SC-001..008）；每个 User Story 先写失败测试再实现。

**组织**：按 User Story 分组（4 个 User Story + Setup + Foundational + Polish = 7 phases）；每个故事可独立实现与测试。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件，无依赖）
- **[Story]**: 任务所属 User Story（US1, US2, US3, US4）
- 描述包含精确文件路径

## Path Conventions

- 模块路径：`oryxos-core/src/main/java/io/oryxos/core/`、`oryxos-tool/src/main/java/io/oryxos/tool/`、`oryxos-storage/src/main/java/io/oryxos/storage/`
- 测试路径：`<module>/src/test/java/io/oryxos/<package>/`
- DDL 路径：`oryxos-storage/src/main/resources/db/migration/`
- 脚本路径：`scripts/`

---

## Phase 1: Setup（项目初始化）

**目的**：项目骨架准备；确认 Notify 涉及模块当前编译通过。

- [x] T001 验证 oryxos-tool/oryxos-core/oryxos-storage/oryxos-cli 当前编译通过（基线快照），命令：`mvn -pl oryxos-tool,oryxos-core,oryxos-storage,oryxos-cli -am compile`
- [x] T002 [P] 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/` 创建 sandbox 子包（package-info.java + 4 个空文件占位）
- [x] T003 [P] 在 `oryxos-tool/src/main/java/io/oryxos/tool/notify/` 创建 notify 子包（package-info.java + 4 个空文件占位）
- [x] T004 [P] 在 `oryxos-storage/src/main/resources/db/migration/` 创建迁移目录（如不存在）

---

## Phase 2: Foundational（阻塞前置）

**目的**：所有 User Story 共享的基础设施。**所有 4 个 User Story 都依赖本 Phase 完成**。

**⚠️ CRITICAL**：本 Phase 未完成前，任何 User Story 任务都不应启动。

- [x] T005 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/ActionType.java` 创建 `ActionType` enum（FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST）
- [x] T006 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxAction.java` 创建 `SandboxAction(ActionType type, String target)` record（带非空校验）
- [x] T007 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxViolationException.java` 创建 `SandboxViolationException extends RuntimeException`（携带 SandboxAction 字段）
- [x] T008 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/Sandbox.java` 创建 `Sandbox` 接口（`void enforce(SandboxAction) throws SandboxViolationException`）
- [x] T009 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/SandboxProperties.java` 创建 `@ConfigurationProperties("oryxos.tool.sandbox")` 配置类（含 `http.allowed-domains` List<String>）
- [x] T010 [P] 在 `oryxos-tool/src/main/java/io/oryxos/tool/sandbox/WhitelistSandbox.java` 实现 `WhitelistSandbox implements Sandbox`（host 后缀匹配 + IP 拒绝 + null/空校验）；依赖 T008/T009
- [x] T011 在 `oryxos-tool/src/test/java/io/oryxos/tool/sandbox/WhitelistSandboxTest.java` 编写白名单单元测试（精确匹配 / 子域匹配 / IP 拒绝 / null host 拒绝）；TDD 红色 → T010 实现后变绿
- [x] T012 在 `oryxos-core/src/main/java/io/oryxos/core/NotifyChannelConfig.java` 创建 `NotifyChannelConfig(String name, String type, String url, String secret)` record（带 name/type/url 校验；secret 可空）
- [x] T013 在 `oryxos-core/src/test/java/io/oryxos/core/NotifyChannelConfigTest.java` 编写 record 校验测试（合法用例 + 4 种非法用例）；TDD
- [x] T014 修改 `oryxos-core/src/main/java/io/oryxos/core/Profile.java`，新增 `List<NotifyChannelConfig> notifyChannels` 字段（默认 `List.of()`，放 record 末尾以兼容现有 8 参数构造调用）
- [x] T015 修改 `oryxos-core/src/test/java/io/oryxos/core/ProfileTest.java`（如存在）或新增测试覆盖 `notifyChannels` 字段（默认值 / 不可变 / null 视作空集合）
- [x] T016 修改 `oryxos-core/src/main/java/io/oryxos/core/OryxTool.java`，新增 `default String description() { return ""; }` 方法
- [x] T017 在 `oryxos-core/src/main/java/io/oryxos/core/tool/ToolRegistration.java` 创建 `ToolRegistration(ToolDefinition definition, OryxTool tool, String beanName)` record
- [x] T018 修改 `oryxos-core/src/main/java/io/oryxos/core/tool/ToolRegistry.java`：构造函数接受 `Map<String, ToolRegistration>`；新增 `Optional<OryxTool> find(String name)`；`all()` 保持返回 `ToolDefinition` 列表
- [x] T019 修改 `oryxos-core/src/main/java/io/oryxos/core/DefaultToolExecutor.java`：构造函数增加 `ToolRegistry` 依赖；将现有 UOE 抛出点替换为 `toolRegistry.find(toolName).map(t -> t.execute(arguments)).orElse(ToolResult.error(...))`
- [x] T020 修改 `oryxos-core/src/test/java/io/oryxos/core/DefaultToolExecutorTest.java`（如存在）注入 mock `ToolRegistry`，覆盖"工具未注册"路径返回 `ToolResult.error`
- [x] T021 在 `oryxos-core/src/test/java/io/oryxos/core/DefaultToolExecutorDispatchTest.java` 新增测试：whitelisted + registered 路径走派发；whitelisted + not registered 路径返回 error
- [x] T022 在 `oryxos-storage/src/main/resources/db/migration/V2__add_notify_columns.sql` 编写 DDL：`ALTER TABLE tool_invocations ADD COLUMN channel TEXT;` + `ADD COLUMN notify_status_code INTEGER;`
- [x] T023 修改 `oryxos-storage/src/main/java/io/oryxos/storage/entity/ToolInvocationRecord.java`：新增 `channel`（`@Column(name = "channel") String`）和 `notifyStatusCode`（`@Column(name = "notify_status_code") Integer`）字段；更新构造器与 getter
- [x] T024 修改 `oryxos-core/src/main/java/io/oryxos/core/ToolAuditWriter.java` 的 `ToolAuditData` record：新增 `channel`（String）和 `notifyStatusCode`（Integer）字段（默认 null）
- [x] T025 修改 `oryxos-core/src/main/java/io/oryxos/core/DefaultToolExecutor.java`：调用 `auditWriter.record(...)` 时把 `channel` 和 `notifyStatusCode` 字段透传（目前固定传 null，留给 Notify 路径填）
- [x] T026 修改 `oryxos-cli/src/main/java/io/oryxos/cli/config/ConfigLoader.java`：解析 frontmatter 中的 `notify_channels` 字段，构建 `List<NotifyChannelConfig>`；保留 `${ENV_VAR}` 替换逻辑；做 name 唯一性 + type/url 校验
- [x] T027 在 `oryxos-cli/src/test/java/io/oryxos/cli/config/ConfigLoaderNotifyChannelsTest.java` 新增测试：合法 YAML / 缺 url / 未知 type / 重复 name / 环境变量缺失 5 种用例
- [x] T028 [P] 创建 smoke 脚本骨架 `scripts/notify-smoke.sh`（仅占位 + help 文本；具体步骤在 Polish 阶段 T070 落地）

**Checkpoint**：Foundation ready — 4 个 User Story 可开始并行实现。

---

## Phase 3: User Story 1 — 单条消息送达默认通道（P1）🎯 MVP

**Goal**：实现最小可演示路径 —— Profile 配 1 条默认通道 webhook，LLM 调 `notify(content)` 把消息送达。

**Independent Test**：本地用 WireMock 起 mock webhook，配 `notify-demo` Profile，跑 `oryxos chat notify-demo "默认测试"`；WireMock 收到 1 次 POST；审计行 success=true。

### Tests for User Story 1（先红后绿）

- [x] T029 [P] [US1] 在 `oryxos-tool/src/test/java/io/oryxos/tool/notify/UrlRedactorTest.java` 编写 URL 脱敏测试（5 种敏感 query 名：key/access_token/secret/api_key/token；大小写不敏感；非敏感参数保留）；TDD 红色
- [x] T030 [P] [US1] 在 `oryxos-tool/src/test/java/io/oryxos/tool/notify/WebhookNotifyAdapterTest.java` 编写适配器单元测试（mock `HttpClient`，覆盖：成功 200 / 4xx / 5xx / 超时 / Sandbox 拦截 5 种场景）；TDD 红色
- [x] T031 [P] [US1] 在 `oryxos-tool/src/test/java/io/oryxos/tool/notify/NotifyToolSingleChannelTest.java` 编写 `NotifyTool` 单通道测试（mock `WebhookNotifyAdapter`，覆盖：default 通道成功 / 无 notify_channels 报错 / content 为空报错 / content 超长报错）；TDD 红色

### Implementation for User Story 1

- [x] T032 [P] [US1] 在 `oryxos-tool/src/main/java/io/oryxos/tool/notify/NotifyResult.java` 创建 `NotifyResult(String channelName, boolean success, Integer statusCode, String errorMessage, long durationMs, String redactedUrl)` record
- [x] T033 [US1] 在 `oryxos-tool/src/main/java/io/oryxos/tool/notify/UrlRedactor.java` 实现 URL 脱敏（依赖 T029 单测）；TDD 绿色
- [x] T034 [US1] 在 `oryxos-tool/src/main/java/io/oryxos/tool/notify/WebhookNotifyAdapter.java` 实现 HTTP POST 适配器（JDK `HttpClient` + 5 秒超时 + Sandbox 校验前置 + 状态码判定 + 响应体前 256 字节截取 + URL 脱敏）；依赖 T033；使 T030 单测变绿
- [x] T035 [US1] 在 `oryxos-tool/src/test/java/io/oryxos/tool/notify/WebhookNotifyAdapterIntegrationTest.java` 编写 Spring `@SpringBootTest` + WireMock 集成测试，覆盖 200/4xx/5xx/超时/Sandbox 拦截 5 种端到端路径
- [x] T036 [US1] 在 `oryxos-tool/src/main/java/io/oryxos/tool/notify/NotifyTool.java` 实现 `NotifyTool implements OryxTool`（name="notify" + description + 单通道路由逻辑 + 错误包成 `ToolResult.error`）；依赖 T034；使 T031 单测变绿
- [x] T037 [US1] 在 `oryxos-boot/src/main/java/io/oryxos/boot/config/NotifyToolConfig.java` 创建 Spring 配置（`@Bean ToolRegistration` 注册 `notify` 到 `ToolRegistry`）；依赖 T036
- [x] T038 [US1] 修改 `oryxos-core/src/main/java/io/oryxos/core/DefaultToolExecutor.java`：当派发到 `NotifyTool` 时，把 `NotifyResult.channelName` 写入审计行 `channel` 字段、`NotifyResult.statusCode` 写入 `notifyStatusCode` 字段；依赖 T036
- [x] T039 [US1] 修改 `oryxos-storage/src/main/java/io/oryxos/storage/entity/ToolInvocationRecord.java`：构造函数接收 channel + notifyStatusCode；audit 写入路径同步；依赖 T023

**Checkpoint**：User Story 1 应完全可独立测试。`oryxos chat notify-demo "默认测试"` 端到端跑通。

---

## Phase 4: User Story 2 — 多通道按名路由（P2）

**Goal**：Profile 配 N 条通道，LLM 通过 `notify(content, channel="<name>")` 显式指定通道；只有指定通道收到请求，未知 channel 名报错。

**Independent Test**：3 条通道 Profile，LLM 调 `notify("...", channel="feishu-tech")`；只有 feishu 对应 mock 收到请求；`tool_invocations.channel` 列记录 `feishu-tech`。

### Tests for User Story 2（先红后绿）

- [x] T040 [P] [US2] 在 `oryxos-tool/src/test/java/io/oryxos/tool/notify/NotifyToolMultiChannelTest.java` 编写测试：3 通道 Profile / 显式 channel 路由 / 未知 channel 报错（不发起 HTTP）/ 唯一通道但 channel 显式匹配；TDD 红色

### Implementation for User Story 2

- [x] T041 [US2] 扩展 `NotifyTool.execute`：从 `arguments` 取 `channel` 字段；按 channel 名字查 Profile.notifyChannels；找不到 → `ToolResult.error("未知通道: <name>")`；找到 → 调 `WebhookNotifyAdapter.send` 仅对该通道；依赖 T040 单测变绿
- [x] T042 [US2] 修改 `NotifyTool` 的 description 字符串（`oryxos-tool/.../notify/NotifyTool.java`）：补充"channel 缺省 vs 显式"路由语义说明；LLM 决策依据

**Checkpoint**：US1 + US2 同时可独立工作（不破坏 P1 路径）。

---

## Phase 5: User Story 3 — 出站域名走 Sandbox 白名单（P2）

**Goal**：每次 notify 调用的出站 URL 必须先过 `Sandbox.enforce(HTTP_REQUEST, url)`；未在白名单被 100% 拦截，发生在 HTTP 请求**之前**。

**Independent Test**：Profile 配 URL `https://evil.example.com/hook`（不在白名单）；WireMock 接收计数 = 0；`ToolResult.errorMessage` 含 "sandbox violation"；审计行 `success=false`。

### Tests for User Story 3（先红后绿）

- [x] T043 [P] [US3] 在 `oryxos-tool/src/test/java/io/oryxos/tool/notify/NotifyToolSandboxTest.java` 编写测试：白名单内的 URL 正常发出 / 白名单外的 URL 被拦截（HTTP 计数 = 0）/ IP 形式 URL 被拦截；TDD 红色

### Implementation for User Story 3

- [x] T044 [US3] 已在 Phase 2 的 T010 中实现 `WhitelistSandbox`；本任务**验证** `WebhookNotifyAdapter.send` 路径对 `Sandbox.enforce` 的调用顺序——保证 HTTP 请求**之前**执行；如有偏差修复；依赖 T043 单测变绿
- [x] T045 [US3] 修改 `oryxos-boot/src/main/resources/application.yml`：示例白名单追加 `localhost` 与 `qyapi.weixin.qq.com`（便于本地 quickstart）

**Checkpoint**：US1 + US2 + US3 同时可独立工作；安全约束生效。

---

## Phase 6: User Story 4 — 多通道并发发送与部分失败（P3）

**Goal**：LLM 不传 channel 且 N>1 条通道时，并行发出；单条失败不影响其他；聚合 ToolResult 的 success/errorMessage 反映"全部/部分/全失败"。

**Independent Test**：3 通道 Profile（2 条健康 + 1 条返回 500）；LLM 调 `notify(content)`；3 条 mock 并发收到（wall-time ≪ 串行 3 倍）；`ToolResult.success=true`，`errorMessage="partial: ..."`；3 行审计写库。

### Tests for User Story 4（先红后绿）

- [x] T046 [P] [US4] 在 `oryxos-tool/src/test/java/io/oryxos/tool/notify/NotifyToolBroadcastTest.java` 编写测试：3 通道全成功 / 部分失败（1 条 500）/ 全失败（连接超时）；TDD 红色

### Implementation for User Story 4

- [x] T047 [US4] 扩展 `NotifyTool.execute`：channel=null 且 Profile.notifyChannels.size() > 1 时走"广播"分支；用 `Executors.newVirtualThreadPerTaskExecutor()` 并发提交 N 条 send 任务；`CompletableFuture.allOf(...).join()` 聚合；依赖 T046 单测变绿
- [x] T048 [US4] 实现聚合逻辑（方法 `aggregate(List<NotifyResult> results)`）：全成功 → `ToolResult.ok(broadcast=true, results=[...])`；部分成功 → `ToolResult.ok(..., errorMessage="partial: <channel>=<status>; ...")`；全失败 → `ToolResult.error("all failed: ...")`；依赖 T047
- [x] T049 [US4] 修改审计写入（`DefaultToolExecutor` 的 Notify 路径）：广播时 `channel` 字段用 `;` 分隔多条通道名；`notifyStatusCode` 按"最差"规则取——优先取非 2xx 状态码（多条非 2xx 取数字最大）；若全为网络错误（null）则记 null；若有 2xx 又有非 2xx 则取非 2xx（避免 Java `Math.max(Integer, null)` 把 null 当最小值的坑）；`success` 按聚合结果写
- [x] T050 [US4] 在 `oryxos-tool/src/test/java/io/oryxos/tool/notify/NotifyToolBroadcastConcurrencyTest.java` 编写并发性能测试：1 / 2 / 5 / 10 通道广播 wall-time P95 分别满足 spec NFR-002 闭式表（≤ 3 / 4 / 5 / 6 秒）；TDD

**Checkpoint**：4 个 User Story 全部独立可工作；notify 端到端覆盖三个 Demo（每日天气 / 每日科技日报 / 每日 GitHub 日报）的最后一步推送。

---

## Phase 7: Polish & Cross-Cutting Concerns

**目的**：跨 User Story 的端到端验证、文档、analyze。

- [x] T051 [P] 在 `specs/004-notify-channel/quickstart/wiremock/mappings/` 创建 WireMock stub JSON 三份：`notify-default.json`（200）、`notify-feishu.json`（200）、`notify-dingtalk-fail.json`（500）
- [x] T052 [P] 完善 `scripts/notify-smoke.sh`：10 步端到端脚本（[quickstart.md](quickstart.md) 步骤 0-10 的 shell 化）；set -euo pipefail；BSD sysexits 风格退出码
- [ ] T053 跑 `scripts/notify-smoke.sh`；记录输出到 `specs/004-notify-channel/evidence/notify-smoke-output.log`；如失败则在 `evidence/notify-smoke-failure.md` 记复盘
- [ ] T054 跑 `mvn verify` 全模块；记录到 `evidence/mvn-verify-output.log`；如有失败在 `evidence/mvn-verify-failure.md` 记复盘
- [ ] T055 [P] 更新 `README.md` 的"Spec-Kit Deliverables"节追加 US-4 子能力 Notify 已落地（[README §待定位](../../README.md)）
- [ ] T056 [P] 更新 `docs/AiProgrammingGuide.md`（如存在 Notify 章节缺失则补，否则跳过）
- [ ] T057 跑 `/speckit-analyze` 对 specs/004-notify-channel/ 做交叉一致性分析；记录 verdict 到 `specs/004-notify-channel/evidence/analyze.log`
- [ ] T058 [P] Per-US commit：每个 User Story 完成后打一个独立 commit（4 个 commit），commit message 遵循 `feat(tool): ...` / `fix(tool): ...` 格式
- [ ] T059 跑 `/speckit-converge`：扫描仓库当前实现与 spec/plan/tasks 的 gap；如有遗漏则追加新 task 到本 tasks.md 并实施
- [x] T060 [P] 显式验证 FR-011"核心阶段 MUST NOT 重试"：在 `oryxos-tool/src/test/java/io/oryxos/tool/notify/NoRetrySemanticsTest.java` 新增负向测试——mock `HttpClient` 让其第一次返回 500；调 `NotifyTool.execute` 一次；断言 `HttpClient.send` 被调**恰好 1 次**（不是 2/3/N 次）；若实现里以后误加重试逻辑本测试会失败
- [ ] T061 [P] 显式验证 FR-014"Notify 全部代码 MUST 落在 oryxos-tool 模块内"：在 `scripts/check-notify-module-boundary.sh` 新增检查脚本——`grep -rn "io.oryxos.tool.notify" oryxos-core oryxos-storage oryxos-cli oryxos-provider oryxos-memory oryxos-web oryxos-channel-cli` 必须为 0 行（oryxos-boot 例外，因 Spring DI 装配需要引用 `@Bean`）；脚本纳入 `scripts/notify-smoke.sh` 步骤 0 的前置检查

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖；可立即开始
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞所有 User Story**
- **User Stories (Phase 3-6)**: 全部依赖 Foundational
  - User Stories 之间可并行（不同文件）
  - 但 P1 推荐先做（提供 MVP）
- **Polish (Phase 7)**: 依赖所有期望的 User Story 完成

### User Story Dependencies

- **US-1 (P1)**: 仅依赖 Foundational（Phase 2）完成；无其他 Story 依赖
- **US-2 (P2)**: 仅依赖 Foundational；与 US-1 共享 `NotifyTool` 但不破坏 US-1 路径
- **US-3 (P2)**: 仅依赖 Foundational；与 US-1/US-2 共享 `WebhookNotifyAdapter`，但 sandbox 调用顺序独立
- **US-4 (P3)**: 仅依赖 Foundational；扩展 `NotifyTool.execute` 的广播分支

### Within Each User Story

- Tests（MUST FAIL）→ Models/Records → Services → Spring Wiring → 审计字段透传
- `NotifyResult` (T032) 早于 `WebhookNotifyAdapter` (T034) 早于 `NotifyTool` (T036)

### Parallel Opportunities

- Phase 1 所有 [P] 任务可并行
- Phase 2 的 T005..T008（T010 之后）/ T012/T013/T016/T017 可并行
- Phase 3 的 T029/T030/T031（tests）+ T032（NotifyResult）可并行
- 4 个 User Story 的实现阶段在 Foundational 完成后可并行（不同文件）
- Phase 7 的 T051/T052/T055/T056 可并行

---

## Parallel Example: User Story 1

```bash
# 一起启动 US-1 所有测试（先红）
Task: "T029 - UrlRedactorTest"
Task: "T030 - WebhookNotifyAdapterTest"
Task: "T031 - NotifyToolSingleChannelTest"

# 一起启动 NotifyResult record + 现有单测的并行更新
Task: "T032 - NotifyResult record"
```

---

## Implementation Strategy

### MVP First（仅 User Story 1）

1. 完成 Phase 1: Setup（T001-T004）
2. 完成 Phase 2: Foundational（T005-T028）—— **CRITICAL**
3. 完成 Phase 3: User Story 1（T029-T039）
4. **STOP and VALIDATE**：跑 `scripts/notify-smoke.sh` 步骤 0-3；WireMock 收到 1 次 POST；审计行 success=true
5. Deploy/demo MVP

### Incremental Delivery

1. Setup + Foundational → Foundation ready
2. → User Story 1 → 单通道默认可推送（**MVP!**）→ demo：每日天气
3. → User Story 2 → 多通道按名路由 → demo：每日科技日报发飞书群
4. → User Story 3 → Sandbox 白名单拦截 → 安全性验证
5. → User Story 4 → 广播 + 部分失败 → demo：每日 GitHub 日报多群推送
6. Polish（quickstart 全跑通 + analyze + commit）

### Parallel Team Strategy

多开发者场景下：

1. 团队一起完成 Setup + Foundational
2. Foundational 完成：
   - 开发者 A: User Story 1（主路径 / MVP）
   - 开发者 B: User Story 2 + User Story 3（多通道 + 安全，可并行）
   - 开发者 C: User Story 4（并发与聚合）
3. Stories 独立完成、独立集成

---

## Notes

### A3 Cleanup Notes (2026-07-26)

本节由 `/speckit-analyze` 之后的 A3 cleanup commit 一次性更新：

- **A1 修复**：T017/T018 的文件路径从错误的 `oryxos-tool/.../tool/` 改回正确的 `oryxos-core/.../core/tool/`（ToolRegistry/ToolRegistration 按 CLAUDE.md §5 §V 边界澄清归 core；tasks.md 当时误写）。
- **批量勾选**：T001-T052、T060 共 53 个已落地的任务标记 `[x]`；T053/T054/T055/T056/T057/T059/T061 共 7 个仍待落地；T058 标 N/A（未勾选）。
- **T058 N/A 原因**：代码已在 `a55d052` 等 commit 中按 User Story 1-4 分块提交（feature/004-notify-channel 分支上），单 US 单 commit 不可在不重写历史的前提下回溯。
- **T061 状态**：独立的 `scripts/check-notify-module-boundary.sh` 尚未创建；功能已部分通过 `scripts/notify-smoke.sh --module-boundary` 集成（T052 完成时包含）。
- **A2 修复（前置 commit `9970b06`）**：FR-008 广播触发条件从 3 重收敛为单条件 (`channel 缺省 + N>=2 + extra.broadcast=true`)，与 contracts/notify-tool.md §3.1 + `NotifyTool.isBroadcast()` + `NotifyToolBroadcastTest` 对齐。
