# Implementation Plan: ReAct Loop (US-2)

**Branch**: `002-react-loop` | **Date**: 2026-07-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/speckit-specify` 阶段产物 [spec.md](spec.md)；本 US 对应 OryxOS 5-US 交付序列中的 US-2（[CLAUDE.md §10](../CLAUDE.md)）。

## Summary

US-2 落 `ReActLoop` —— OryxOS 的核心控制流循环。给定用户消息与 Session，循环在每个 iteration 步骤内：(1) 通过 `PromptBuilder` 组装四段式 prompt；(2) 通过 `ProviderService.invoke`（位于 `oryxos-core`，由 `oryxos-provider` 实现）调一次 LLM；(3) 检查响应是否含 `tool_calls`，若有则通过 `ToolExecutor.invoke`（接口在 `core`、实现在 US-4）派发每个 Tool 并把结果作为 `tool` 消息回加进 Session；(4) 重复直到响应无 `tool_call` 或 `MAX_ITERATIONS`（默认 10，可被 Profile 覆盖）。三个触发源（CLI / Web / Scheduler）共用同一 `AgentService.process(session, userMessage)` 入口；循环不感知来源。`Session.appendMessage` 落 SQLite；`LlmCall` 与 `ToolInvocation` 审计行分别在 ProviderService 与 ToolExecutor 内部完成（loop 永不直接 INSERT 审计表）。`ProfileContext` thread-local 由 `AgentService` 设置、`finally` 块清空，供 `OryxTool` 在执行时无参化解析当前 Agent。

## Technical Context

**Language/Version**: Java 21（records、sealed types、pattern matching、`Thread.ofVirtual()`、sequenced collections 全部使用；不允许预览特性）—— [constitution §I](../CLAUDE.md) 与 [constitution §I](../.specify/memory/constitution.md)

**Primary Dependencies**:

- 内部：依赖 `oryxos-provider` 接口子集（`ProviderService`、`LlmRequest`、`LlmResponse`、`Provider`）—— 经 R-1（见 [research.md](research.md) 决策 R-1）后下沉到 `oryxos-core`
- 内部：`oryxos-storage` 提供 `SessionEntity` / `SessionRepository`（待 US-2 实现）
- Spring：Spring Boot 3.3.x、Spring Data JPA、Spring Web（仅用于在测试中拉起 Application Context）
- 日志：Logback + SLF4J（结构化 key=value，单行每次事件）
- 测试：JUnit 5、AssertJ、Mockito、WireMock、Testcontainers（不引入，仅保持可选）

**Storage**: SQLite（由 `oryxos-storage` 管理的 `SessionEntity` 表；messages 以 JSON 字段持久化，遵循 US-1 既有 `LlmCallRecord` 的 `JsonType` 模式）

**Testing**: JUnit 5 单元测试 + Spring Boot `@SpringBootTest` 集成测试（带 WireMock 替代 LLM Provider）+ 自定义 `LlmInvoker` spy 用于验证 invocation 次数

**Target Platform**: Linux server + Windows dev（与项目其他模块保持一致）；JDK 21 单一版本

**Project Type**: Java / Maven / Spring Boot 多模块项目（本 US 在 9 模块矩阵中落在 `oryxos-core` 与 `oryxos-storage`）

**Performance Goals**: NFR-001 单次 5-tool 循环 ≤ 30 s（开发者机器 + 真实 LLM + 本地 HTTP mock）；单次 Reason 迭代 ≤ 3 s 端到端（含 Provider 网络往返）

**Constraints**:

- ≤ ~200 行 Java 实现 `ReActLoop` 的核心 for-loop（Constitution §III："数十行"）
- 无 Spring AI Agent 抽象依赖、无 MCP SDK 调用、无任何第三方 Agent 框架（Constitution §III/§IV）
- `ProfileContext` thread-local `finally` 清零（FR-017）
- 审计写入由 `ProviderService` 与 `ToolExecutor` 各自完成（Constitution §VI：day-one 审计地基）

**Scale/Scope**: 单进程内单实例；单条 `process(...)` 调用占一个线程；并发隔离靠 thread-local（SC-003 要求 N 个并发调用零串扰）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

逐条 [constitution.md](../.specify/memory/constitution.md) 校验：

- **§I Single-Stack Monolith (JDK 21 + Spring Boot 3.x, 9 modules)**：US-2 只动 `oryxos-core`（新建）与 `oryxos-storage`（新增 `SessionEntity`/`Repository`）—— **不引入第 10 个模块**。✅ PASS
- **§II Core-Stage Scope Discipline**：spec 中已列"Out of Scope"——禁止 Tool 实现、MCP、Web 端点、Scheduler cron API、流式、并发 tool_call 派发、Provider fallback；本 plan 不含上述任何实现。✅ PASS
- **§III Self-Implemented ReAct Loop**：手写 `for` 循环约 30~60 行的核心方法；MAX_ITERATIONS 默认 10 + Profile 覆盖；不依赖 Spring AI Agent 抽象。✅ PASS
- **§IV Spring AI Used at Half-Strength**：循环仅调 `ProviderService.invoke`，禁止 Spring AI auto-tool-execution；Tool 派发经由 `ToolExecutor`（自实现），不交回 Spring AI。✅ PASS
- **§V Three-Tier Plugin Tooling**：US-2 不实现 Tool（本 plan 仅定义 `ToolExecutor` 接口 + US-4 stub）；Tool 入口下沉到 `oryxos-tool` 模块留待 US-4。✅ PASS
- **§VI SQLite + MEMORY.md with Day-One Audit Persistence**：Day-one 审计表 `llm_calls`（由 ProviderService 写，US-1 已就绪）受 loop 调用路径保护——SC-004 要求 100% 覆盖率；本 plan 不绕过 `LlmCallRecord` 入库路径。`MEMORY.md` 由 US-3 拥有；本 loop 仅消费其只读 API（spec A-003）。✅ PASS
- **§VII Demo-First Delivery**：SC-005（每日天气）、SC-006（每日科技日报）作为本 US 完成的硬门禁；本 plan 输出 `quickstart.md` 含端到端可跑脚本。✅ PASS

**Additional Constraints** 检查：

- ✅ NO `SecurityManager`
- ✅ API key 全部 `${ENV_VAR}`，已在 `application.yml`（参见 US-1 落地）
- ✅ `MAX_ITERATIONS` Profile 覆盖语义走 YAML，**不**硬编码业务阈值
- ✅ `ToolExecutor` 拒绝未授权 Tool 调用（FR-011）以白名单方式实现，**不**用容器类型扫描
- ✅ Session ≠ 长记忆；两者解耦
- ✅ JDK 21 only

**结论**：所有 7 条原则 PASS / 全量 non-MUST 约束 PASS。无需 Complexity Tracking 调整。

### Post-Design Re-evaluation (after Phase 1)

复检时间：Phase 1 设计产物（[research.md](research.md)、[data-model.md](data-model.md)、[contracts/](contracts/)、[quickstart.md](quickstart.md)）落地后。

| 原则 | Pre-Phase | Post-Phase | 关键变化 / 维持 |
| --- | --- | --- | --- |
| §I 单栈 9 模块 | PASS | **PASS** | 维持：US-2 不引入第 10 模块；`ProviderService` 等接口下沉至 core（[research.md R-1](research.md)）符合模块边界。 |
| §II Core-Stage Scope Discipline | PASS | **PASS** | 维持：spec + plan 显式列出 Out-of-Scope（Tool 实现、MCP、Web 端点、Scheduler cron API、流式、并发 tool_call、Provider fallback）。 |
| §III 自实现 ReAct Loop | PASS | **PASS** | 维持：核心 for-loop 在 core 内；约 30~60 行骨架见 [plan §Implementation Strategy Phase 4](plan.md)。`ProviderService`、`ToolExecutor` 是接口，由本模块 / 其他模块实现；不依赖 Spring AI Agent 抽象。 |
| §IV Spring AI 半禁用 | PASS | **PASS** | 加强：[contracts/ProviderService.md](contracts/ProviderService.md) §2 C-PS-5 强制"同步非流式"；C-PS-6 强制按 providerName 显式路由。`ReActLoop` 仅持接口引用。 |
| §V 三档 Plugin Tool | PASS | **PASS** | 维持：US-2 仅定义 `ToolExecutor` 接口 + `ToolResult`；具体 Tool 实现（MCP / `@Tool` / SKILL.md）下沉到 US-4，本 US 不实现。 |
| §VI Day-One 审计 | PASS | **PASS** | 加强：[data-model.md §3.9](data-model.md) 显式列出 `ToolInvocationRecord` 表 schema 在 US-2 落地（即使实现归 US-4），满足 day-one 表存在；[contracts/ToolExecutor.md](contracts/ToolExecutor.md) C-TE-2 + C-TE-9 强制每次 invoke 必写审计行（无论成功失败）。`LlmCallRecord` 既有（US-1）。 |
| §VII Demo-First | PASS | **PASS** | 维持：[quickstart.md](quickstart.md) 含 4 个端到端小节映射到 SC-005、SC-006、SC-001、SC-003、SC-004；NFR-001 ≤ 30 s 计时命令列入 §6。 |

**Additional Constraints 复检**：依然全部满足（参见上文）。R-1 引入的接口下移**不**违反"不引入第 10 模块"——是把已有契约下移到 core，模块数仍 = 9。

**Final verdict**：所有 7 条原则 + 全部 hard constraints 在 Phase 1 后维持 PASS。**Complexity Tracking** 仍空——无需任何违背辩护。

## Project Structure

### Documentation (this feature)

```text
specs/002-react-loop/
├── plan.md                # 本文件（/speckit-plan 输出）
├── research.md            # Phase 0：研究与决策记录
├── data-model.md          # Phase 1：Session / Message 数据模型
├── contracts/             # Phase 1：公开接口契约
│   ├── ProviderService.md        # LLM 入口契约
│   ├── ToolExecutor.md           # Tool 派发契约
│   ├── AgentService.md           # 统一入口契约
│   ├── ProfileContext.md         # ThreadLocal 契约
│   └── LoopResult.md             # 循环返回值契约
├── quickstart.md          # Phase 1：端到端可跑 demo
├── checklists/
│   └── requirements.md    # /speckit-specify 产物
└── spec.md                # /speckit-specify 产物
```

### Source Code (repository root)

US-2 涉及的实际新建/修改的文件落点：

```text
oryxos-core/
├── pom.xml                                  # 不变（已无依赖）
└── src/main/java/io/oryxos/core/
    ├── Session.java                         # [NEW] 纯接口（在 core 中）
    ├── Message.java                         # [NEW] record(user|assistant|tool)
    ├── Profile.java                         # [NEW] record(子集：loop 关心的字段)
    ├── OryxTool.java                        # [NEW] 工具接口（只到 name/execute 子集）
    ├── PromptBuilder.java                   # [NEW] 四段式 prompt 组装
    ├── ContextLoader.java                   # [NEW] AGENT.md + Bootstrap + Memory 注入
    ├── ProviderService.java                 # [MOVE from oryxos-provider] 接口下沉
    ├── LlmRequest.java                      # [MOVE from oryxos-provider]
    ├── LlmResponse.java                     # [MOVE from oryxos-provider]
    ├── Provider.java                        # [MOVE from oryxos-provider]
    ├── ToolExecutor.java                    # [NEW] 接口（实现在 US-4）
    ├── ToolResult.java                      # [NEW] record(success, payload, error)
    ├── AgentService.java                    # [NEW] 统一入口 process(session, msg)
    ├── ReActLoop.java                       # [NEW] 核心循环
    ├── ProfileContext.java                  # [NEW] ThreadLocal holder
    └── ReActLoopProperties.java             # [NEW] @ConfigurationProperties 默认值
└── src/test/java/io/oryxos/core/
    ├── ReActLoopPureReasonTest.java         # [NEW] P1 单元
    ├── ReActLoopToolChainTest.java          # [NEW] P2/P3 单元
    ├── ReActLoopTerminationTest.java        # [NEW] max_iterations + edge cases
    ├── ReActLoopConcurrencyTest.java        # [NEW] SC-003 多线程隔离
    ├── ProfileContextTest.java              # [NEW] ThreadLocal 清零
    ├── PromptBuilderTest.java               # [NEW] 四段式 prompt 顺序
    └── AgentServiceE2EIT.java               # [NEW] Spring Boot 集成 + WireMock LLM mock

oryxos-provider/
├── pom.xml                                  # (+) 移除内部对 core 中已存在的类型的导入
└── src/main/java/io/oryxos/provider/
    ├── DefaultProviderService.java          # [MOD] 实现 io.oryxos.core.ProviderService
    ├── DefaultAuditWriter.java              # 不变
    ├── ProviderRegistry.java                # 不变（仍持有 io.oryxos.core.Provider）
    └── ...                                  # 不变
└── src/test/java/io/oryxos/provider/
    ├── DefaultProviderServiceTest.java      # [MOD] 改 import 路径
    └── ...                                  # 不变

oryxos-storage/
└── src/main/java/io/oryxos/storage/
    ├── entity/SessionEntity.java            # [NEW] @Entity sessions 表 + JSON messages
    ├── repository/SessionRepository.java    # [NEW] extends JpaRepository
    └── ...                                  # 不变
```

**Structure Decision**: 选择 Constitution §I 强制的"9 模块 Maven 多模块"形态；US-2 不引入新模块，将 `ProviderService` 等接口从 `oryxos-provider` 下沉到 `oryxos-core`（决策依据见 [research.md §R-1](research.md)），实现"接口在 core，实现在 provider"，避免 `ReActLoop` ↔ `ProviderService` 的循环依赖。

## Complexity Tracking

> Fill ONLY if Constitution Check has violations that must be justified

无违规。当前 `Complex` 列空。

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| (none) | (none) | (none) |

## Implementation Strategy

### Phase 1: 接口下沉（R-1）

把 `ProviderService`、`LlmRequest`、`LlmResponse`、`Provider` 从 `oryxos-provider` **搬到** `oryxos-core`（同 package `io.oryxos.core`）。`oryxos-provider` 改为实现 `core` 中的接口 + import 新路径。配套：所有现有 US-1 测试更新 import。本步骤是后续循环可调 `ProviderService` 的 **前提条件**——非"优化"而是"必要去耦"。

### Phase 2: Core 数据结构

新增 `Session`/`Message`/`Profile`/`OryxTool`/`ToolExecutor`/`ToolResult`/`ProfileContext` —— 全部纯接口 / record / ThreadLocal holder。`Session` 接口方法：`appendMessage`、`messages`、`profileName`、`id`；`Message` record：`role`（user/assistant/tool）、`content`、`toolCallId`、`toolCalls`、`toolName`、`toolResult`。`Profile` record：仅取 loop 必需字段（`name`, `provider.name`, `provider.model`, `provider.temperature`, `provider.maxTokens`, `tools[]`, `mcpServers[]`, `bootstrap[]`, `skills[]`, `settings.maxIterations`）。

### Phase 3: PromptBuilder

四段式（spec FR-004）：(1) system = AGENT.md 内容 + Bootstrap 文件拼接 + 当前本地日期时间；(2) Memory 注入：`MarkdownMemoryStore` 的当前会话+长期条目（US-3 提供，本 US 用空 stub）；(3) 对话历史：按 `max_history_turns` 截断的最后 N 条；(4) `toolSchemas`：从 `ToolRegistry.list(profile) -> List<Map>`（US-4 提供，本 US 用 stub 列表）。`PromptBuilder.build(profile, session) -> Prompt` 返回 record（`systemMessages: List<Message>`, `memoryMessages: List<Message>`, `historyMessages: List<Message>`, `toolSchemas: List<Map<String,Object>>`）。

### Phase 4: ReActLoop — 核心

约 30~60 行主方法骨架：

```java
// pseudocode — 实际产出 100% Java + 完整异常路径
public LoopResult run(Profile profile, Session session, String userMessage) {
    ProfileContext.set(profile, session);
    try {
        session.appendMessage(userMessage(userMessage));
        int iter = 0;
        while (iter < profile.maxIterations()) {
            iter++;
            Prompt prompt = promptBuilder.build(profile, session);
            LlmResponse r = providerService.invoke(
                profile.provider().name(),
                new LlmRequest(session.id(), profile.name(), profile.provider().model(),
                               prompt.flatten(), prompt.toolSchemas(),
                               profile.provider().temperature(), profile.provider().maxTokens()));
            session.appendMessage(assistantFrom(r));
            if (r.toolCalls().isEmpty()) {
                log.completed(iter, final_tool_call=false); return new LoopResult(r.text(), iter, false);
            }
            for (ToolCall tc : r.toolCalls()) {
                ToolResult tr = toolExecutor.invoke(tc.name(), tc.arguments(), profile);
                session.appendMessage(toolMessage(tc, tr));
                // 如果 profile 没声明该 Tool, ToolExecutor 已返回工具级"tool not in profile"错误
                // 状态: ToolInvocation row(success=false) 在 ToolExecutor 内已写
            }
            log.iteration(iter, tool_calls=r.toolCalls().size());
        }
        log.completed(iter, final_tool_call=true);
        return new LoopResult(lastAssistantText(), iter, true); // max_iterations reached
    } finally {
        ProfileContext.clear();
    }
}
```

详细异常路径、Tool 拒绝、LLM 失败、empty response、Profile 未找到等在 [research.md §R-3..R-6](research.md) 给出。

### Phase 5: AgentService

```java
public class AgentService {
    public LoopResult process(Session session, String userMessage) {
        Profile p = profileRegistry.load(session.profileName())
            .orElseThrow(() -> new IllegalArgumentException("Unknown profile: " + session.profileName()));
        return reactLoop.run(p, session, userMessage);
    }
}
```

`profileRegistry.load` 由 `ContextLoader` 实现（已在 `package-info` 中明确划归 core），通过 `Profile.name -> Profile record` 的 Map 查询。

### Phase 6: 测试

- **单元**（无 Spring，毫秒级）：`ReActLoopPureReasonTest`、`ReActLoopToolChainTest`、`ReActLoopTerminationTest`、`ProfileContextTest`、`PromptBuilderTest` —— 用 Mockito 模拟 `ProviderService`、`ToolExecutor`。
- **集成**（Spring Boot 上下文）：`ReActLoopConcurrencyTest`、`AgentServiceE2EIT` —— 用真实 ApplicationContext 拉起，用 WireMock 模拟 LLM（按 spec 测试路径替换 provider endpoint），断言数据库 `LlmCallRecord`/`ToolInvocation` 行数。
- **端到端**（实 LLM + WireMock 出口）：`quickstart.md` 的"每日天气"脚本：CLI 触发 → `AgentService.process` → `ReActLoop` → `ProviderService` → 真实 DeepSeek → Tool 调本地 WireMock → 文本回复。**这是 SC-005 的验真路径**。

## Risk & Mitigation

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| `PromptBuilder` 在 US-3 Memory 语义检索未就绪时就要工作 | 中 | `PromptBuilder` 接 `MemoryInjector` 接口，US-2 提供 noop 实现，US-3 覆盖 |
| `ToolExecutor` 在 US-4 未就绪时就要被调 | 中 | `ToolExecutor` 在 core 中是接口；`oryxos-tool` 提供 noop/stub 实现 + 严格的"tool not in profile" 错误路径 |
| `Session` 持久化与 `LlmCallRecord` 跨模块写入存在事务边界 | 中 | 所有审计行在 `ProviderService.invoke` / `ToolExecutor.invoke` 内部各自提交；loop 不持有事务 |
| `ProfileContext` ThreadLocal 在异步/虚拟线程下可能不生效 | 低 | core 仅支持同步入口（per CLAUDE.md §9.1 "Self-ReAct loop"）；不暴露异步 API；SC-003 用平台线程验证 |
| Profile YAML 字段子集裁剪 | 中 | `Profile` record 只取必需字段；其他字段保留为 `Map<String, Object> extra()` 透传，留 US-3/4/5 增补 |

## Done When

- [ ] `ReActLoop` 落地于 `oryxos-core`，调用 `ProviderService` 和 `ToolExecutor` 接口
- [ ] `ProviderService` 等接口下沉至 `oryxos-core` 完成；US-1 测试改 import 通过
- [ ] `Session`/`Message`/`Profile`/`OryxTool`/`ProfileContext` 类型就绪
- [ ] `PromptBuilder` 四段式组装实现 + 单元测试
- [ ] `AgentService.process(session, msg)` 统一入口就绪
- [ ] 单元 + 集成测试全绿；至少覆盖 US1/P1 + US2/P2 + US3/P3 + 边界情况 + SC-003 并发
- [ ] `quickstart.md` 中"每日天气"脚本可在本地真实 DeepSeek 下跑通（**SC-005**）
- [ ] Constitution Check 仍然 PASS（复检 [constitution §I .. §VII](../.specify/memory/constitution.md)）
- [ ] `/speckit-analyze` 通过
