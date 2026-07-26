# 契约：OryxTool 接口（Tool 抽象层）

**目的**：定义 Tool 体系的最底层抽象契约 —— `OryxTool` 接口的字段语义、调用约束、失败语义。这是所有内置 Tool / MCP Tool / Java Bean Tool 共同遵守的契约。
**创建日期**：2026-07-26
**特性**：[spec.md §FR-001](../spec.md) | [research.md R-07](./../research.md)
**前置**：[OryxTool.java](../../../oryxos-core/src/main/java/io/oryxos/core/OryxTool.java) | [CLAUDE.md §5 §V 边界澄清](../../../CLAUDE.md)

---

## 1. 接口签名

```java
package io.oryxos.core;

import java.util.Map;

public interface OryxTool {

    /**
     * Tool 全局唯一名（与 Profile {@code tools[]} 中的字符串一致）。
     *
     * <p>命名约束：
     * <ul>
     *   <li>仅允许 {@code [a-z0-9_]+}（小写字母 + 数字 + 下划线）</li>
     *   <li>非空，长度 ≤ 64</li>
     *   <li>进程内唯一（[research.md R-08](../research.md)）</li>
     * </ul>
     */
    String name();

    /**
     * Tool 一句话描述 —— 用于 LLM Function Calling schema 与 CLI 展示。
     *
     * <p>默认实现返回空串；具体 Tool 实现 MUST override（spec FR-001）。
     */
    default String description() {
        return "";
    }

    /**
     * 执行 Tool。
     *
     * <p>调用约定：
     * <ul>
     *   <li>{@code arguments} 已经过 Function Calling schema 校验（Spring AI 阶段拒掉类型错误 / 缺必填字段）</li>
     *   <li>Tool 实现 STILL 需要 defensive check（schema 校验不能替代）</li>
     *   <li>成功返回 {@link ToolResult#ok}；失败返回 {@link ToolResult#error}；不抛 RuntimeException（spec FR-012）</li>
     * </ul>
     *
     * @param arguments LLM 解析后的 JSON 参数 map
     * @return 执行结果
     */
    ToolResult execute(Map<String, Object> arguments);
}
```

---

## 2. `name()` 契约

### 2.1 命名规则

| 类别 | Tool 名 | 命名理由 |
|------|---------|---------|
| 内置 Tool（`io.oryxos.tool.*`） | `file_read` / `file_write` / `file_list` / `shell` / `http_get` / `http_post` / `notify` / `save_memory` / `recall_memory` | snake_case，描述行为 |
| MCP Tool | MCP server 定义的原名（如 `list_pull_requests`） | 不强制重命名；保留 MCP server 的命名空间 |
| Java Bean Tool | 业务自定（如 `github_pr_digest`） | snake_case 优先 |

### 2.2 唯一性约束

**进程内全局唯一**（spec FR-015，[research.md R-08](../research.md)）。冲突检测由 `ToolRegistry.of()` 在装配期执行：

```java
// ToolRegistry.java 第 47 行（修改后）
public static ToolRegistry of(Map<String, ToolRegistration> registrations) {
    Map<String, ToolRegistration> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, ToolRegistration> e : registrations.entrySet()) {
        ToolRegistration reg = e.getValue();
        if (reg == null) continue;
        String key = reg.definition().name();
        if (normalized.containsKey(key)) {
            ToolRegistration existing = normalized.get(key);
            throw new IllegalStateException(String.format(
                "Tool name conflict: '%s' registered by both %s and %s",
                key,
                existing.tool().getClass().getName(),
                reg.tool().getClass().getName()));
        }
        normalized.put(key, reg);
    }
    return new ToolRegistry(normalized);
}
```

**冲突时**：抛 `IllegalStateException`，Spring Boot 启动失败。

---

## 3. `description()` 契约

### 3.1 用途

`description` 在两处被消费：

1. **Function Calling schema 生成** —— `ToolSchemaProvider` 把 description 嵌入到每个 Tool 的 JSON schema 的 `description` 字段，供 LLM 阅读
2. **CLI 展示** —— `oryxos tool list` 命令把 description 打印给运营者

### 3.2 写作建议

- **第一句话**：Tool 的能力概述（动词开头），如 "读取本地文本文件内容"
- **第二句话（可选）**：约束或边界，如 "沙箱白名单内的 HTTP GET 请求；URL 必须通过 `tool.sandbox.http.allowed-domains` 校验"
- **长度**：≤ 200 字符
- **语言**：中文（与 CLAUDE.md §21 一致；标识符本身保留英文）

### 3.3 默认空串的兼容性

`OryxTool.description()` 是 JDK 21 **默认方法**（[OryxTool.java](../../../oryxos-core/src/main/java/io/oryxos/core/OryxTool.java) 第 31 行）；既有 fake / 测试桩不修改即可继续编译。

但**所有**内置 Tool / MCP Tool / Java Bean Tool 都 MUST override（spec FR-001）—— `description` 为空会让 LLM 不知道 Tool 干什么。

---

## 4. `execute()` 契约

### 4.1 输入（`arguments`）

```java
Map<String, Object> arguments
```

- **key** 一定是 String（Function Calling JSON 解析后是 string-keyed map）
- **value** 类型由 Tool schema 决定：可能是 String / Number / Boolean / List / Map
- **不存在性**：LLM 传 `{}` 或缺字段；Tool 自行决定是否报错
- **类型错误**：schema 校验阶段已拒掉；Tool 不应再校验

### 4.2 输出（`ToolResult`）

```java
public record ToolResult(
    boolean success,
    String content,            // LLM 可读的成功消息；失败时为 null
    String errorMessage,       // 失败原因（LLM 可读）；成功时为 null
    Map<String, Object> payload  // 可选的结构化数据；ToolResult 透传给 LLM
) {
    public static ToolResult ok(String content) { ... }
    public static ToolResult ok(String content, Map<String, Object> payload) { ... }
    public static ToolResult error(String errorMessage) { ... }
}
```

**成功路径**：`success=true, content=<消息>, errorMessage=null, payload=<结构化数据>?`

**失败路径**：`success=false, content=null, errorMessage=<原因>, payload=null`

### 4.3 不变量（Invariants）

- **I-OT-1**：`success=true` 时 `errorMessage` MUST 为 null
- **I-OT-2**：`success=false` 时 `errorMessage` MUST 非空（验证失败拒绝调用）
- **I-OT-3**：执行 MUST NOT 抛 RuntimeException（spec FR-012）；异常由 `DefaultToolExecutor` 捕获并包装为 `ToolResult.error`
- **I-OT-4**：`errorMessage` MUST NOT 含 stack trace（spec NFR-004）；stack trace 走 `.oryxos/logs/`
- **I-OT-5**：执行 MUST 不阻塞 ReAct 主循环（spec NFR-002）；JDK 21 虚拟线程负责隔离

### 4.4 `payload` 用途

`payload` 是**可选**的二级返回值，给 LLM 提供结构化数据（如 HTTP 响应包含 `status_code` / `body` / `duration_ms`）。典型用例：

| Tool | payload 字段 |
|------|--------------|
| `notify`（见 004 spec） | `channel` / `status_code` / `duration_ms` / `broadcast` / `results[]` |
| `http_get` / `http_post` | `status_code` / `content_type` / `body` / `duration_ms` |
| `shell` | `exit_code` / `stdout` / `stderr` / `duration_ms` |
| `file_read` | `path` / `size_bytes` / `content` |
| `file_list` | `path` / `entries[]` |
| `save_memory` / `recall_memory` | `operation` / `scope` / `entry_count` / `snippets[]` |

**LLM 看到 `payload`**：Spring AI 的 `@ToolReturn` 把 `payload` 序列化进 Function Calling response；LLM 可以 JSON 路径访问具体字段。

---

## 5. ThreadLocal 依赖

### 5.1 `ProfileContext` 使用边界

只有 `NotifyTool`（[004-notify-channel/spec.md §US-2](../004-notify-channel/spec.md)）需要 `ProfileContext.current()` —— 因为 `notify` 需要从 Profile 拿 `notifyChannels` 配置（[CLAUDE.md §9.3](../../../CLAUDE.md)）。

其他 8 个内置 Tool + MCP Tool + Java Bean Tool **不**依赖 `ProfileContext`：

- 配置（`HttpToolProperties` / `ShellToolProperties` 等）在 Tool 的 `@Component` 构造期注入
- Profile-level 过滤由 `Profile.tools[]` 前置完成（spec FR-011）

### 5.2 测试隔离

Tool 的单测不需要 mock `ProfileContext` —— 只需 mock 自己的依赖（`HttpClient` / `MemoryService` / `McpTransport` 等）。

---

## 6. 不在本契约范围

- ❌ Function Calling schema 的生成细节（`ToolSchemaProvider` 内部契约；扩展阶段细化）
- ❌ Tool 注册表的扫描机制（Spring bean 自动扫描；不在 OryxTool 接口约束内）
- ❌ Tool 沙箱校验的执行时机（`Sandbox.enforce` 由 `DefaultToolExecutor` / Tool 自身调用；不在 `execute()` 契约内）

---

## 7. 总结：实现 OryxTool 的最少工作量

实现一个自定义 Java Bean Tool 的最少步骤（spec US-4 用户故事 4）：

```java
@Component
public class MyTool implements OryxTool {
    @Override public String name() { return "my_tool"; }
    @Override public String description() { return "我的自定义工具"; }
    @Override public ToolResult execute(Map<String, Object> arguments) {
        // 业务逻辑
        return ToolResult.ok("done");
    }
}
```

加上 import + javadoc，总行数 ≤ 100 行（spec SC-007）。Profile 加 `tools: [my_tool]` 即可被 LLM 调到。
