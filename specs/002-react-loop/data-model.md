# Data Model: ReAct Loop (US-2)

**Date**: 2026-07-25
**Branch**: `002-react-loop`
**Status**: Design

> Phase 1 / Speckit-Plan。本 US 新增/修改的核心数据类型（接口、record、实体）。spec 中已经存在的实体（`LlmCallRecord`）仅列引用，不重复定义。

---

## 1. 设计原则

1. **接口在 `oryxos-core`，实现在各自模块**（R-1、R-3）。
2. record 优先于 class：US-2 所有"值对象"用 Java 21 `record` 表达。
3. 不可变性优先：`Message`、`Profile`、`Snapshot`、`Prompt`、`ToolResult` 均为不可变 record 或 final 包装类；可变状态仅出现在 `ProfileContext`（thread-local holder）与 `Session.appendMessage` 的内部持久化代理。
4. 与 US-1 既有约定一致：`@Check` 约束兜底、JSON 列复用 `JsonType`（`io.hypersistence.utils.hibernate.type.json.JsonType`）。

---

## 2. 实体清单

US-2 引入或修改的实体分三类：

### A. `oryxos-core` 内的纯接口 / record（无持久化）

| 名称 | 类型 | 模块 | 用途 |
|------|------|------|------|
| `ProviderService` | 接口 | `oryxos-core` | 从 `oryxos-provider` 下沉（R-1）—— 公共 LLM 入口契约 |
| `LlmRequest` | record | `oryxos-core` | 从 `oryxos-provider` 下沉（R-1） |
| `LlmResponse` | record | `oryxos-core` | 从 `oryxos-provider` 下沉（R-1） |
| `Provider` | record | `oryxos-core` | 从 `oryxos-provider` 下沉（R-1） |
| `Session` | 接口 | `oryxos-core` | 会话抽象（R-3） |
| `Message` | record | `oryxos-core` | 三种角色消息的统一形态 |
| `Profile` | record | `oryxos-core` | loop 关心的 Profile 子集 |
| `ProfileContext` | final class | `oryxos-core` | thread-local holder |
| `Prompt` | record | `oryxos-core` | 四段式 prompt 内存形态（R-4） |
| `ToolExecutor` | 接口 | `oryxos-core` | 公共 Tool 派发契约（R-2） |
| `ToolResult` | record | `oryxos-core` | ToolExecutor 返回值 |
| `ToolCall` | record | `oryxos-core` | LLM 响应中的 tool_call 表示 |
| `LoopResult` | record | `oryxos-core` | ReActLoop 返回值 |
| `OryxTool` | 接口 | `oryxos-core` | 占位接口（US-4 填充实现） |

### B. `oryxos-storage` 内的 JPA 实体（持久化）

| 名称 | 实体表 | 模块 | 用途 |
|------|--------|------|------|
| `SessionEntity` | `sessions` | `oryxos-storage` | `Session` 的 JPA 实现；存 JSON messages 列 |
| `LlmCallRecord` | `llm_calls` | `oryxos-storage` | US-1 既有，引用 |
| `ToolInvocationRecord` | `tool_invocations` | `oryxos-storage` | **US-2 引入** —— 即使 US-4 拥有真实实现，本表 schema 必须在 US-2 同步建立，保证 `tool_invocations` day-one 表存在（Constitution §VI） |

> 注：`ToolInvocationRecord` 的写入由 `ToolExecutor` 真实实现（US-4）触发；US-2 stub 工具实现不写该表，但表结构就位。

---

## 3. 详细字段

### 3.1 `Message`（record，`oryxos-core`）

**职责**：循环与 Session 之间、`PromptBuilder` 与 LLM 之间的统一消息格式。

```java
public record Message(
    Role role,                      // USER | ASSISTANT | TOOL
    String content,                 // 文本内容（assistant text / tool error / user input）
    List<ToolCall> toolCalls,       // 仅 ASSISTANT role 使用：LLM 请求的工具调用列表
    String toolCallId,              // 仅 TOOL role：对应 assistant message 中 toolCall.id
    String toolName,                // 仅 TOOL role：tool 名
    ToolResult toolResult,          // 仅 TOOL role：执行结果
    Instant createdAt               // 本地时间；按 spec A-007
) {
    public enum Role { USER, ASSISTANT, TOOL }

    public static Message user(String text) { ... }
    public static Message assistantText(String text) { ... }
    public static Message assistantToolCalls(List<ToolCall> calls) { ... }
    public static Message toolResult(String id, String name, ToolResult result) { ... }
}
```

**Validation rules**:

- `role == USER` 时：`content != null`，`toolCalls == null`、`toolCallId == null`、`toolName == null`
- `role == ASSISTANT` 且"无 tool_call"：`content != null`、`toolCalls == empty list`
- `role == ASSISTANT` 且"有 tool_call"：`content` 可能为空、`toolCalls.size() >= 1`
- `role == TOOL`：`content == null`、`toolResult != null`、`toolName != null`、`toolCallId != null`

**Not a JPA 实体**——`Message` 是 record，不直接落库。`SessionEntity` 把 messages 序列化为 JSON 列。

---

### 3.2 `Session`（接口） + `SessionEntity`（`@Entity`）

#### 3.2.1 接口（`oryxos-core/Session.java`）

```java
public interface Session {
    UUID id();
    String profileName();
    List<Message> messages();         // 不可变视图
    void appendMessage(Message m);    // 触发持久化（实现细节）
    Instant createdAt();
    Instant updatedAt();
}
```

#### 3.2.2 实体（`oryxos-storage/SessionEntity.java`）

```java
@Entity
@Table(name = "sessions", indexes = {
    @Index(name = "idx_profile",   columnList = "profile_name"),
    @Index(name = "idx_updated",   columnList = "updated_at")
})
@Check(constraints = "length(id) > 0")
public class SessionEntity implements Session {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "TEXT")
    private UUID id;

    @Column(name = "profile_name", nullable = false, columnDefinition = "TEXT")
    private String profileName;

    /** JSON list of Message；按 spec FR-016 保留完整会话历史以便回放 */
    @Type(JsonType.class)
    @Column(name = "messages", nullable = false, columnDefinition = "TEXT")
    private List<Message> messages;

    @Column(name = "created_at", nullable = false, columnDefinition = "TEXT")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "TEXT")
    private Instant updatedAt;

    // ====== methods ======
    @Override public UUID id()                  { return id; }
    @Override public String profileName()       { return profileName; }
    @Override public List<Message> messages()   { return List.copyOf(messages); }

    @Override
    @Transactional
    public void appendMessage(Message m) {
        messages.add(m);
        this.updatedAt = Instant.now();
        // 持久化由 Spring Data JPA 脏检查自动 flush；显式 save() 也可
    }

    // ====== factories ======
    public static SessionEntity create(UUID id, String profileName) { ... }
}
```

**Validation rules**:

- `id` 非空且为 UUID
- `profile_name` 非空、`length > 0`
- `messages` 不可为空列表（创建时至少含 `[Message.user("")]` 哨兵——本期 US-2 暂不要求，存空 list 也允许）

**State transitions**：

```
[Created] --appendMessage--> [Active] --appendMessage--> [Active] --...--> [Closed]
                                                                              ^
                                                                              | 触发：用户主动 DELETE /api/v1/sessions/{id}（US-5 拥有）
                                                                              |
                                                                       [US-5 端点定义]
```

US-2 不关闭 session（spec §Functional Requirements 未要求）；多轮用户消息并发追加同一 session 由 `appendMessage` 的 `@Transactional` 保证单一写入。

---

### 3.3 `Profile`（record，`oryxos-core`）

```java
public record Profile(
    String name,                                   // Profile YAML 的 name
    Provider provider,                             // 路由键 + 模型 + 温度 + maxTokens
    List<String> tools,                            // 启用的 Tool 名列表
    List<String> mcpServers,                       // 启用的 MCP server 名列表
    List<String> bootstrap,                        // Bootstrap 文件列表（AGENTS.md/SOUL.md/USER.md）
    List<String> skills,                           // 引用 SKILL.md 列表
    Settings settings,                             // max_iterations, max_history_turns
    Map<String, Object> extra                      // 未知字段透传，留 US-3/4/5 增补
) {
    public record Settings(
        int maxIterations,                         // 默认 10；spec FR-014
        int maxHistoryTurns                        // 默认 20
    ) {}
}
```

**Validation rules**：

- `name` 匹配 `^[a-z][a-z0-9-]{0,63}$`（与 Provider 名同 pattern，Constitution §I 已要求）
- `provider.name` 不为空
- `provider.model` 不为空
- `settings.maxIterations >= 0`（`0` 是合法值，spec Edge case 4）
- `settings.maxHistoryTurns >= 1`

---

### 3.4 `ToolCall`（record，`oryxos-core`）

```java
public record ToolCall(
    String id,                                     // LLM 给出的 tool_call.id；与 Message.toolCallId 对应
    String name,                                   // 工具名
    Map<String, Object> arguments                  // JSON 解析后的参数 map
) {}
```

---

### 3.5 `ToolResult`（record，`oryxos-core`）

```java
public record ToolResult(
    boolean success,
    Map<String, Object> payload,                   // 成功时为工具的真实返回（结构化）；失败时为 null 或诊断信息
    String errorMessage                            // 仅 success=false 时非空
) {
    public static ToolResult ok(Map<String,Object> p) {
        return new ToolResult(true, Map.copyOf(p), null);
    }
    public static ToolResult error(String message) {
        return new ToolResult(false, null, Objects.requireNonNull(message));
    }
}
```

---

### 3.6 `Prompt`（record，`oryxos-core`，R-4）

```java
public record Prompt(
    List<Map<String, Object>> systemBlocks,        // 段 1
    List<Map<String, Object>> memoryBlocks,        // 段 2（US-3 提供；US-2 默认 empty）
    List<Map<String, Object>> historyBlocks,       // 段 3（按 settings.maxHistoryTurns 截断）
    List<Map<String, Object>> toolSchemas          // 段 4
) {}
```

`Map<String,Object>` 与 spec FR-006 "Provider 中立的 JSON Schema" 一致；`LlmRequest.messages: List<Map<String,Object>>` 直接接受这种形态（参见下条）。

---

### 3.7 `LlmRequest` / `LlmResponse` / `Provider`（下沉自 `oryxos-provider`，R-1）

`LlmRequest`、`LlmResponse`、`Provider` 原属 `oryxos-provider`（US-1 已就绪）；详见 [research.md §R-1](research.md)，本 US 仅记录下移目标位置：

- `io.oryxos.provider.LlmRequest` → `io.oryxos.core.LlmRequest`
- `io.oryxos.provider.LlmResponse` → `io.oryxos.core.LlmResponse`
- `io.oryxos.provider.Provider` → `io.oryxos.core.Provider`

字段不变（原 spec FR-005 ~ FR-014 引用的字段集）。

---

### 3.8 `LlmCallRecord`（既有，US-1）

不动。引用：[`oryxos-storage/.../entity/LlmCallRecord.java`](../../oryxos-storage/src/main/java/io/oryxos/storage/entity/LlmCallRecord.java)。

---

### 3.9 `ToolInvocationRecord`（新，US-2 day-one）

**Day-one 表创建**——即使 US-4 才填真实实现，**`tool_invocations` 表的 schema 必须在 US-2 落地**以满足 Constitution §VI "day-one 审计地基"。

```java
@Entity
@Table(name = "tool_invocations", indexes = {
    @Index(name = "idx_session",  columnList = "session_id"),
    @Index(name = "idx_profile",  columnList = "profile_name"),
    @Index(name = "idx_tool_ts",  columnList = "tool_name, started_at"),
    @Index(name = "idx_success",  columnList = "success, started_at")
})
@Check(constraints = "success = 0 OR error_message IS NULL")
@Check(constraints = "duration_ms >= 0")
public class ToolInvocationRecord {

    @Id @Column(name = "id", nullable = false, columnDefinition = "TEXT")
    private UUID id;

    @Column(name = "session_id", columnDefinition = "TEXT")
    private UUID sessionId;                                // 可空：CLI 直调

    @Column(name = "profile_name", nullable = false, columnDefinition = "TEXT")
    private String profileName;

    @Column(name = "tool_name", nullable = false, columnDefinition = "TEXT")
    private String toolName;                                // 也记录被拒绝的 Tool 名（spec FR-011）

    @Type(JsonType.class)
    @Column(name = "arguments", columnDefinition = "TEXT")
    private Map<String, Object> arguments;                  // 实际传给 Tool 的参数

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;                           // success=false 时非空

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "started_at", nullable = false, columnDefinition = "TEXT")
    private Instant startedAt;                              // 本地时间（spec A-007）

    @Column(name = "session_iteration", nullable = false)
    private int sessionIteration;                          // 来自 ProfileContext.currentIteration()；用于与 llm_calls 行交叉定位
}
```

**Validation rules**：

- `tool_name != null`、`profile_name != null`
- `success=false` 时 `error_message != null && length > 0`
- `duration_ms >= 0`、`started_at != null`
- `session_id` 可空（CLI 直调场景）
- `session_iteration >= 0`

**Repository**：`ToolInvocationRepository extends JpaRepository<ToolInvocationRecord, UUID> { ... }`，由 US-2 创建空版本，方法集由 US-4 实现填充。

---

### 3.10 `ProfileContext`（final class，`oryxos-core`）

```java
public final class ProfileContext {
    private static final ThreadLocal<Snapshot> CTX = new ThreadLocal<>();

    public record Snapshot(
        String profileName,
        UUID sessionId,
        AtomicInteger currentIteration     // 循环迭代计数；AgentService.process 设初值 0
    ) {}

    private ProfileContext() {}

    public static void set(Snapshot s) { CTX.set(Objects.requireNonNull(s)); }
    public static Optional<Snapshot> current() { return Optional.ofNullable(CTX.get()); }
    public static void clear() { CTX.remove(); }
}
```

---

### 3.11 `LoopResult`（record，`oryxos-core`）

```java
public record LoopResult(
    String finalText,                // 用户的最终可见回复
    int iterations,                  // 实际迭代次数（含最后一次无 tool_call 的迭代）
    boolean terminatedAtMax,         // true 表示因 MAX_ITERATIONS 而结束（spec FR-013 (b) 路径）
    String profileName,
    UUID sessionId
) {}
```

---

## 4. 关系

```
Profile (core) ──1:N───? Profile.tool_calls  ──通过──> ToolExecutor
Session (core)  ──1:N─── Message (core)
SessionEntity (storage) ── implements ── Session
ProfileContext.Snapshot ──以 ThreadLocal 形式 ──为 ──每一个 ── AgentService.process(...) 持有一次
ReActLoop.run(Profile, Session, String) ──returns──> LoopResult
AgentService.process(Session, String) ──delegates──> ReActLoop
                    └─ sets / clears ──> ProfileContext
                    ├─ lookups Profile from registry ──> Profile
                    └─ raises ── IllegalArgumentException on unknown Profile
ToolExecutor.invoke(String, Map, Profile) ──writes──> ToolInvocationRecord（US-4 真实实现）
ProviderService.invoke(String, LlmRequest) ──writes──> LlmCallRecord（US-1 已实现）
```

```
+----------------------------+
|      AgentService          |       oryxos-core
+----------------------------+
            |
            v
+----------------------------+        +--------------------------+
|       ReActLoop            |<------>|  PromptBuilder           |
+----------------------------+        +--------------------------+
     |       |       |
     |       |       +-- invokes --> ToolExecutor (interface, in core)  ── 真实实现在 US-4
     |       +--------- invokes --> ProviderService (interface, in core) ── 真实实现在 oryxos-provider
     |       |
     |       +--- appends Message --> Session (interface, in core) ── 真实实现在 oryxos-storage
     |       |
     |       +--- sets / clears --> ProfileContext (ThreadLocal)
     |
     +--> returns LoopResult
```

---

## 5. 不变量（Invariants）

为后续 `/speckit-analyze` 与 US-2 实现期单测断言集中列出：

| 编号 | 不变量 | 维护者 | 验证方式 |
|------|--------|--------|----------|
| I-01 | 一次 `process()` 调用至多产生 `MAX_ITERATIONS + 1` 次 `LlmCallRecord` | `ReActLoop` + `AgentService` | SC-002 单元 |
| I-02 | 每次 ToolExecutor 真实调用恰对应一行 `ToolInvocationRecord`，无论成功失败 | `ToolExecutor` 实现 | SC-004 单元 |
| I-03 | 一条 assistant(tool_call) 消息可触发多个 tool 调用；每个工具名在一轮内至多调一次 | `ReActLoop` | SC-001 / 用户故事 3 验收 |
| I-04 | Session 的 messages 列表按时间序追加；一次 process() 调用前后总长度增量 = `2 * iterations + 1 + 2 * tool_calls` （user + N assistant + N tool + final assistant + tool messages） | `Session.appendMessage` | P3 验收 1 |
| I-05 | 同一 `process()` 调用内，`ProfileContext.Snapshot.sessionIteration` 与 `LlmCallRecord.started_at` 顺序对应 | `AgentService` + `ReActLoop` | 单测 + 集成 |
| I-06 | `ProfileContext.clear()` 必在 `finally` 块中被调用，即使循环抛异常 | `AgentService` | `ProfileContextTest` |
| I-07 | 拒绝调用（`tool not in profile`）同样写入 `ToolInvocationRecord`，`success=false`、`error_message="tool not in profile: {name}"` | `ToolExecutor` | FR-011 单元 |
| I-08 | 循环永不调用 Spring AI Agent 抽象或 OpenAI 的 function-calling auto-execute | `ReActLoop` | Constitution §III/§IV 检查 |

---

## 6. 不在 US-2 内的 schema

下列类型在后续 US 才填充实现，但**接口在 US-2 阶段下沉**到 core（不写实现）：

- `OryxTool`（接口，仅占位）—— US-4 引入重代码实现
- `ToolRegistry`（接口）—— US-4
- `MemoryInjector`（接口）—— US-3
- `ScheduledTask` / `TaskExecution`（实体）—— US-5
- `Event` / 通知相关 schema —— US-4
