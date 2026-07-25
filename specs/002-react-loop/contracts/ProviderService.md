# Contract: `ProviderService`

**Package**: `io.oryxos.core`
**Module**: `oryxos-core` (下沉自 US-1，原 `oryxos-provider`)
**Stability**: Stable — 任何变更需走 constitution amendment
**Consumers**: `ReActLoop`（US-2）、future `AgentScheduler`（US-5）
**Implementors**: `DefaultProviderService` in `oryxos-provider`（US-1 已就绪）

---

## 1. 接口签名

```java
package io.oryxos.core;

public interface ProviderService {
    /**
     * 通过 provider 名称（精确匹配 application.yml 的 routes）发起一次 LLM 调用。
     * 实现 MUST 写入一行 {@code LlmCallRecord}（成功或失败）。
     *
     * @param providerName 路由键；MUST 与某条已配置的 name 完全相等
     * @param request      LLM 调用入参
     * @return LLM 响应
     * @throws UnknownProviderException providerName 未配置
     * @throws LlmInvocationException   LLM 调用失败（4xx/5xx/network/timeout）；已写审计行（success=false）
     */
    LlmResponse invoke(String providerName, LlmRequest request);
}
```

相关类型（同样下沉自 `oryxos-provider`）：

```java
// oryxos-core
public record LlmRequest(
    UUID sessionId,
    String profileName,
    String model,                    // null 表示使用 Provider YAML 默认
    List<Map<String,Object>> messages,
    List<Map<String,Object>> toolSchemas,
    Double temperature,
    Integer maxTokens
) {
    public String modelNameOrDefault(String registryDefault);
}

public record LlmResponse(
    String text,                     // 文本部分；可能为 null/"" 当 LLM 只返 tool_call
    List<ToolCall> toolCalls,        // 空列表代表 "no tool call"
    Integer promptTokens,
    Integer completionTokens
) {}

public record ToolCall(
    String id,
    String name,
    Map<String,Object> arguments
) {}

public record Provider(
    String name,                     // 路由键
    String model,                    // YAML 默认 model
    String endpoint,                 // base URL
    String credentialRef,            // ${ENV_VAR} 占位
    Map<String,Object> options       // temperature / maxTokens 等
) {}
```

---

## 2. 契约条款

| ID | 条款 | 强制性 | 验证方式 |
|----|------|--------|----------|
| C-PS-1 | 每个 `invoke(...)` 调用产出一行 `LlmCallRecord` | MUST | 单测统计调用前后行数 |
| C-PS-2 | `providerName` 未配置抛 `UnknownProviderException`（不写审计行） | MUST | 单测 |
| C-PS-3 | LLM 调用失败（4xx/5xx/网络）抛 `LlmInvocationException`（**已写**审计行 `success=false`） | MUST | 集成测试 |
| C-PS-4 | LLM 响应中若仅含 `tool_calls` 无 `text`，`LlmResponse.text` 为 `null` 或 `""` | SHOULD NOT FAIL | 单测 |
| C-PS-5 | 调用同步、非流式；实现不暴露 streaming API 给 core | MUST | 接口签名 |
| C-PS-6 | 多 Provider 并存时，按 `providerName` 显式路由，不依赖容器类型扫描 | MUST | Constitution §I / §IV "Additional Constraints" |
| C-PS-7 | 凭证占位符 `${ENV_VAR}` 由 `CredentialResolver` 解析；缺失即 fail-fast | MUST | US-1 已验证 |

---

## 3. 与 spec 的对应

| spec 条目 | 对应契约 |
|----------|---------|
| spec FR-007：ReActLoop 通过 `ProviderService.invoke(providerName, LlmRequest)` | C-PS-1 + C-PS-5 |
| spec FR-008：循环不直接写 `llm_calls`，由 ProviderService 写 | C-PS-1 |
| spec A-001：US-1 已实现并稳定 | 实现已存在 `DefaultProviderService` |
| Constitution §IV：禁用 Spring AI 自动工具执行 | 协议层只用 ProviderService 接口；不暴露 OpenAI 原生 ChatModel |

---

## 4. 调用者（`ReActLoop`）用法约定

```java
// pseudocode
LlmResponse r = providerService.invoke(
    profile.provider().name(),                          // 路由键
    new LlmRequest(
        session.id(),                                   // sessionId（可选，由 ProfileContext 兜底）
        profile.name(),                                 // profileName
        profile.provider().model(),                     // 覆盖默认 model
        prompt.flatten(),                               // 四段合并后的 messages
        prompt.toolSchemas(),                           // 段 4
        profile.provider().temperature(),
        profile.provider().maxTokens()
    )
);
```

- 调用方**禁止**传入 `null` providerName。
- 调用方**禁止**绕开 ProviderService 直接调用 Spring AI 的 ChatClient（Constitution §IV）。
- 异常**直接向上抛**给 `AgentService.process(...)` 调用方——ProviderService 自己负责审计；循环只关心返回的 `LlmResponse`。

---

## 5. 与下沉的兼容策略

US-1 既有代码路径（位于 `oryxos-provider`）会同步做以下变更：

1. 删除 `oryxos-provider/src/main/java/io/oryxos/provider/{ProviderService,LlmRequest,LlmResponse,Provider}.java`。
2. 删除 `oryxos-provider` 内既有对这些类型的 import；改为 `import io.oryxos.core.{ProviderService,...};`。
3. `DefaultProviderService`（实现类）的 implements 类型从 `io.oryxos.provider.ProviderService` 改为 `io.oryxos.core.ProviderService`。
4. `ProviderRegistry`、`DefaultAuditWriter`、`ToolSchemaTranslator`、`CredentialResolver` 同步。
5. US-1 已有 35 个测试改 import 路径。
6. `oryxos-provider/pom.xml` 中确认仍依赖 `oryxos-core`（不变）。

完成上述后即可在 `oryxos-core` 的 `ReActLoop` 中 import `io.oryxos.core.ProviderService`。

---

## 6. 测试义务

| 测试类 | 断言 |
|--------|------|
| `DefaultProviderServiceTest#routesToConfiguredProvider` | 传入 `providerName` 与 YAML 一致时，返回 LlmResponse |
| `DefaultProviderServiceTest#unknownProviderThrows` | 不一致时 `UnknownProviderException` |
| `DefaultProviderServiceTest#auditAlwaysWritten` | 调用前后 `LlmCallRecordRepository.count()` 增长 1 |
| `DefaultProviderServiceTest#failureWritesFailureAudit` | mock Provider 抛异常；`LlmCallRecord.success=false` 且 `error_message != null` |
