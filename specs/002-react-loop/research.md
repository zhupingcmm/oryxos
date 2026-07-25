# Research: ReAct Loop (US-2)

**Date**: 2026-07-25
**Branch**: `002-react-loop`
**Status**: Settled

> Phase 0 / Speckit-Plan。整理 US-2 实施过程中需要决定的非显然技术问题。每条决策给出 Decision / Rationale / Alternatives / Trade-offs。

---

## R-1. ReActLoop 与 ProviderService 之间的模块依赖方向

**Decision**: 将 `ProviderService`、`LlmRequest`、`LlmResponse`、`Provider` 四个公共契约从 `oryxos-provider` **下移到** `oryxos-core`。`oryxos-provider` 改为实现 `io.oryxos.core.ProviderService` 并保持 `DefaultProviderService` 等类在原模块。`DefaultAuditWriter` 等仍归 provider。`ReActLoop`（位于 `oryxos-core`）直接调 `io.oryxos.core.ProviderService.invoke(...)`，无跨模块上引。

**Rationale**:

1. 当前 Maven 依赖矩阵：
   - `oryxos-provider` → 依赖 `oryxos-core` + `oryxos-storage`（pom.xml `<dependencies>` 已确认）
   - `oryxos-core` → 无任何内部依赖
   - `ReActLoop` 在 `core` 内会调用 `ProviderService.invoke(...)`。
2. 若 `ProviderService` 留在 `oryxos-provider`，`core` 必须上引 `provider`，**违反** Maven 依赖方向 + 形成 `provider ↔ core` 循环（spring-auto-config 阶段尤其会报 *"Cycle detected"*，与 US-1 阶段在 `provider` 内遇到过的循环同源）。
3. `oryxos-core/package-info.java` 已经写明"pure interfaces and core types only"——把"对外 LLM 路由契约"下移到 core 与该目标天然契合。
4. 这样一个清晰的"接口在 core、实现在 provider"分层，是 ports-and-adapters（六边形架构）在 9 模块矩阵中的最小落地。

**Alternatives considered**:

| 备选 | 否决原因 |
|------|---------|
| (a) `ReActLoop` 移入 `oryxos-provider` | 违反 Constitution §V "Tool 相关代码归 `oryxos-tool` 单模块" 的精神——`ReActLoop` 跨入 provider 后，未来 Tool 相关循环也被吸入，模块边界模糊。 |
| (b) 通过 `Function<LlmRequest, LlmResponse>` 把 LLM 调用点暴露成纯函数 | 失去 `providerName` 路由键、失去 provider 配置注入位置；测试 mock 方便但生产集成变复杂。 |
| (c) `ReActLoop` 不感知 `ProviderService`，仅依赖 `LlmResponse` + `LlmRequest` 接口 | 仅在 `core` 内复制两个 record。语义上等同于下移，但拆得过于零碎，违反"接口整组下沉"原则。 |

**Trade-offs**:

- 需要改 US-1 阶段既有 import 路径（`io.oryxos.provider.ProviderService` → `io.oryxos.core.ProviderService`）。US-1 的 35 个测试与若干自动配置类同步调整。预估成本：1 个 PR、半天工作量。
- 后续 US-3（Memory）、US-5（Web）需要新接口（`LongTermMemoryStore`、`ToolRegistry` 等）也走"接口在 core、实现在 storage/tool/web"的下沉模式——R-1 成为后续 US 的范式。

---

## R-2. ToolExecutor 接口在 core 中的最小必要字段

**Decision**: 在 `oryxos-core` 内定义 `ToolExecutor` 接口如下：

```java
public interface ToolExecutor {
    /** @param toolName 工具名；调用方保证已被 Profile 授权（ProviderService 不感知） */
    ToolResult invoke(String toolName, Map<String, Object> arguments, Profile profile);
}
```

返回 `ToolResult`（record: `boolean success, Map<String,Object> payload, String errorMessage`）。**不**包含工具执行上下文（会话/Profile 信息由调用方在调用前通过 `ProfileContext` 注入或参数 `profile` 字段携带）。

**Rationale**:

1. spec FR-009 ~ FR-012 要求 loop 调用 Tool 通过 `ToolExecutor.invoke(...)`，且 FR-011 要求对未授权 Tool 返回"tool not in profile"错误。
2. 接口签名需要 `Profile` 参数——`ToolExecutor` 实现需要知道"当前 Profile 授权了哪些 Tool"，便于白名单校验；不必让实现去 ThreadLocal 取。
3. 不带 sessionId：实现内部可从 `ProfileContext.current()` 读到（spec FR-017 强制设置），但接口签名保持纯净。
4. 返回 record（success/payload/errorMessage）匹配 US-4 预计写 `tool_invocations` 表的三列结构。

**Alternatives considered**:

| 备选 | 否决原因 |
|------|---------|
| (a) `ToolExecutor.invoke(String, Map<String,Object>)` 不带 Profile | 实现无法做白名单校验（白名单从哪取？）。 |
| (b) 暴露 `Session` 参数 | Tool 不应感知 Session 概念；Session 是循环内部状态，Tool 只与"当前 Agent"耦合。 |
| (c) 把 `ToolResult` 放到 provider 模块 | 循环需要 import `provider`，回到 R-1 的循环依赖问题。 |

**Trade-offs**: US-2 的 stub 实现 `DefaultToolExecutor` 必须检查 `profile.tools().contains(toolName)`；如果不在，**必须**返回 `success=false` 且 `errorMessage="tool not in profile"`——loop 把它作为 `tool` 消息回喂 LLM（FR-011）。US-4 的真实实现延后。

---

## R-3. Session 与 Message 的归属模块

**Decision**: **`Session` 是接口（位于 `oryxos-core`），实现 `SessionEntity` 是 JPA `@Entity`（位于 `oryxos-storage`）。`Message` 是 record（位于 `oryxos-core`）。**

接口（core）：

```java
public interface Session {
    UUID id();
    String profileName();
    List<Message> messages();         // 不可变视图
    void appendMessage(Message m);    // 触发持久化（实现内完成）
}
```

实现（storage）：

```java
@Entity @Table(name = "sessions")
public class SessionEntity implements Session { ... }
```

**Rationale**:

1. spec FR-016 要求"System MUST append each assistant and tool message to the Session conversation history in the order they occur, so that the Session is fully replayable." —— 抽象"会话历史"必须能在没有 DB 的情况下被 loop 引用（单元测试用）。
2. `Session.appendMessage` 在生产路径下要落库；测试路径可注入 `InMemorySession`。
3. 与 US-1 既有模式（`LlmCallRecord` 在 storage 实体内）保持一致。

**Alternatives considered**:

| 备选 | 否决原因 |
|------|---------|
| (a) Session 全部在 storage（无 core 接口） | loop 单测被迫开 Spring + JPA + H2，违反 NFR-003 "数十行可测"。 |
| (b) Session 全部在 core（不落库） | 失去审计与 replay 能力，违反 FR-016。 |

**Trade-offs**: `Session.appendMessage` 是阻塞+落库；SC-003 "concurrent process(...) calls" 保证每条会话写入相互隔离，靠 JPA `EntityManager` 默认 thread-bound 事务与 `@Transactional` 保证。

---

## R-4. PromptBuilder 四段式组装的内存模型

**Decision**: `PromptBuilder.build(Profile, Session) -> Prompt` 返回一个内部 record：

```java
public record Prompt(
    List<Map<String,Object>> systemBlocks,      // 段 1：AGENT.md + Bootstrap + 当前本地日期
    List<Map<String,Object>> memoryBlocks,      // 段 2：长期记忆 / 会话摘要（US-3 接入；US-2 默认空）
    List<Map<String,Object>> historyBlocks,     // 段 3：截断后的对话历史
    List<Map<String,Object>> toolSchemas        // 段 4：Provider 中立的 Function Calling schema
) {}
```

循环一次性把四个列表合并成 LlmRequest.messages（按段顺序拼接）+ toolSchemas 字段。**不**做实际字符串拼接（避免重复序列化；将序列化留给 ProviderService）。

**Rationale**:

1. spec FR-004 把段 1 / 2 / 3 / 4 顺序固定，循环不在乎段内字段细节，只在乎"这四段是有序的块"。
2. 把序列化留给 ProviderService 是合理的：ProviderService 已实现 OpenAI/Anthropic 的协议转换（US-1 `ToolSchemaTranslator`）。
3. `LocalDateTime.now(ZoneId.systemDefault()).toString()` 注入段 1 末尾一行——遵循 spec FR-005 / CLAUDE.md §9.2 步骤 1。

**Alternatives considered**:

| 备选 | 否决原因 |
|------|---------|
| (a) `PromptBuilder` 直接返回字符串 `String prompt` | 失去 schema 序列化自由度；与 spec FR-006 "toolSchemas 翻译为 Provider 中立的 JSON Schema" 不衔接。 |
| (b) PromptBuilder 调用 Spring AI 的 `Prompt` 类 | 违反 spec FR-007（不依赖 Spring AI Agent 抽象）。 |

**Trade-offs**: US-2 的 `PromptBuilder` 实现需要可注入 `MemoryInjector` 接口（US-3 实现真正的"记忆注入"时覆盖），US-2 默认实现返回空 `memoryBlocks`。解耦点清楚，US-3 工作量小。

---

## R-5. 多 tool_call 在一次 assistant 响应内的处理顺序

**Decision**: **顺序**处理。循环 `for (ToolCall tc : r.toolCalls())` 一个个 invoke，按出现顺序把结果 append 进 Session，再进入下一次 Reason 迭代。

**Rationale**:

1. spec A-010 明文："Multi-tool-call in a single assistant message is processed **sequentially** in core stage"。
2. SC-001 "N+1 LLM calls" 的可观测断言基于"每次 assistant 响应触发至多一次循环迭代"，顺序实现让它直接可数。
3. 顺序处理保证 `tool_invocations` 行的 `invocation_id` 与 Session 历史中 `tool` 消息严格同序（US3 验收场景 1）。

**Alternatives considered**:

| 备选 | 否决原因 |
|------|---------|
| (a) 并发派发同一 assistant 消息内的多 tool_call | 引入 CompletableFuture 复杂度；与 spec A-010 明示违背；US-3/4 阶段尚未稳定。 |
| (b) 只调用第一个 tool_call | 损失功能完整性；某些模型会一次性给两个 tool_call 并希望都执行。 |

**Trade-offs**: spec.md §Out of Scope 已列 "Multiple parallel tool-call dispatch — call is sequential within one assistant message in core stage"。R-5 与 spec 一致。

---

## R-6. Tool not in profile 的失败传播语义

**Decision**: `ToolExecutor`（含 US-4 真实实现）必须识别"tool 不在 profile 白名单"，返回 `ToolResult(success=false, errorMessage="tool not in profile: {tc.name()}")`。`ReActLoop` 把它包装成 `Message(role=tool, toolName=tc.name, content=errorMessage, success=false)` 追加进 Session，继续下一次 Reason 迭代。**不**抛异常、**不**结束循环。

**Rationale**:

1. spec FR-011 明确规定："the refusal MUST be recorded as a `tool_invocations` row with error='tool not in profile' and the loop continues"。
2. 由 ToolExecutor 内做白名单校验（不在循环内做）保证"现实 tool 行为与 `tool_invocations` 审计行"对齐：校验通过 → 真实执行 → 成功/失败行；校验不通过 → 拒绝 → 失败行。
3. 不抛异常的原因：让 LLM 自己根据 tool 错误消息决定"换一个 tool"、"重试同样的 tool（实现可能 race 下已上架）"或"放弃这一回合"。

**Alternatives considered**:

| 备选 | 否决原因 |
|------|---------|
| (a) 循环先验白名单，再调用 ToolExecutor | 双重校验；且 ToolExecutor 无法信任上游。 |
| (b) 抛 `ToolNotInProfileException`，由循环捕获 | 与 FR-011 "loop continues（不崩溃）" 一致，但增加异常路径使 R-2 接口签名不必要地复杂化。返回失败结果语义更扁平。 |

**Trade-offs**: ToolExecutor 接口需要能正确区分"参数错误 / 沙箱违例 / sandbox OK 但工具抛异常"等场景。US-2 的 stub 与 US-4 的真实实现都要遵循同一错误形态。

---

## R-7. ProfileContext ThreadLocal 在 Spring 工作线程复用场景下的清理

**Decision**: `ProfileContext` 是 `io.oryxos.core` 内的 final class，持有 `static final ThreadLocal<Snapshot>` 与一对静态方法 `set(Snapshot)`、`clear()`。`AgentService.process(...)` 是设置与清理的**唯一**合法入口：

```java
public LoopResult process(Session session, String userMessage) {
    var snapshot = new Snapshot(session.profileName(), session.id(), 0);
    ProfileContext.set(snapshot);
    try {
        return reactLoop.run(...);
    } finally {
        ProfileContext.clear();
    }
}
```

**禁止**外部调用方手动设置或清除。

**Rationale**:

1. spec FR-017 强制：thread-local 设置一次、`finally` 清除。
2. Spring 自带的 `InheritableThreadLocal` 不需要（spec A-009）；core 不暴露异步 API（R-5/§ Risk 中分析）。
3. SC-003 要求"N 个并发 process() 调用零串扰"——ThreadLocal + 静态工厂（`Snapshot.from(...)`）让 Snapshot 实例不可变，避免任何"跨线程共享引用"隐患。

**Alternatives considered**:

| 备选 | 否决原因 |
|------|---------|
| (a) 用 MDC (Mapped Diagnostic Context) | MDC 本质还是 ThreadLocal，等价但缺少类型安全与不变 snapshot。 |
| (b) 把 profile/session 作为参数贯穿调用栈 | 改所有 Tool 接口签名（破坏 §V 三档接入）；且 OryxTool 来自用户的"零代码 SKILL.md"无法添加新参数。 |

**Trade-offs**: 任何 I/O 库不得包装 `AgentService.process(...)` 进入虚拟线程池而忘记恢复上下文；US-5 (Web Service) 若用 @Async 异步，必须显式在 `@Async` 边界捕获并复制 Snapshot。这是 R-7 与 future US 的耦合点。

---

## R-8. 测试隔离：避免 ProviderService.invoke 真实打到网络

**Decision**: 单元测试用 Mockito mock `ProviderService`（纯 Java mock），不引入 spring context。集成测试 `AgentServiceE2EIT` 拉真实 Spring + ApplicationContext，但通过 WireMock 替代 deepseek/qwen/minimax 的 HTTP endpoint（每个 Provider 装一个 `@Bean WireMockServer`、port 由 Profile.endpoint 动态读出，参考 US-1 `E2ETestApp`）。

**Rationale**:

1. spec NFR-001（30 s 内完成）由真实 LLM RTT 决定；CI 内不能依赖网络 → 必须 mock。
2. US-1 已用 `WireMockServer` 拦 provider endpoint 模拟 LLM HTTP 行为；US-2 集成测试沿用同一模式避免重复造轮子。
3. 单元测试用 Mockito 校验调用次数、参数（spec SC-001 "exactly N+1 LLM calls"、"each tool at most once per loop"）。

**Alternatives considered**:

| 备选 | 否决原因 |
|------|---------|
| (a) Spring AI Test 自带 `ChatModel` mock | 违反 R-1 已经把 ProviderService 当接口的事实；用 mockito 直接 mock 接口更直接。 |
| (b) 仅端到端 | 单测缺失导致循环分支（max_iterations 截断、空响应、未授权 Tool 拒绝）难以构造。 |

**Trade-offs**: WireMock 端口绑定到 `application-e2e.yml`（参考 US-1），不污染主 `application.yml`。

---

## Open Items Deferred to Plan/Implementation

| 项 | 来源 | 何时定 |
|----|------|--------|
| `MemoryInjector` 接口签名细节 | US-3 已声明的范围 | 在 US-3 spec 阶段定；US-2 默认 noop |
| `ToolRegistry` 与 `OryxTool` 的 metadata schema | US-4 拥有 | US-4 spec 阶段定；US-2 通过 stub 提供 |
| `Session.appendMessage` 的事务边界 | US-5 端点也调用 session.append | US-2 给出 `@Transactional` 标记，待 US-5 集成时验证 |
| AgentScheduler → process(...) 的 cron 注入 | US-5 拥有 | US-5 阶段定 |

R-1 ~ R-8 八项是 US-2 实施的不可绕过路径。R-9+ 在 US-3 / US-4 / US-5 各自 spec 中讨论。
