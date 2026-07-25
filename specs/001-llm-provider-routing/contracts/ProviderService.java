// 契约：ProviderService 接口
// 目的：定义 LLM Provider 路由层对外暴露的唯一编程入口
// 关联：spec.md FR-005、FR-007、FR-010
// 注意：本文件是接口契约，不是实现；实现位于 oryxos-provider 模块
// 路径：oryxos-provider/src/main/java/io/oryxos/provider/ProviderService.java
//     与 oryxos-provider/src/main/java/io/oryxos/provider/LlmRequest.java
//     与 oryxos-provider/src/main/java/io/oryxos/provider/LlmResponse.java

package io.oryxos.provider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LLM Provider 路由层对外入口。
 *
 * <p>契约要点（来自 spec.md FR-005 ~ FR-014）：
 * <ul>
 *   <li>按 {@code providerName} 精确路由到已配置的 Provider，不做模糊匹配、不做 fallback</li>
 *   <li>每次调用产出一行 {@code llm_calls} 审计记录，调用方不可绕过</li>
 *   <li>工具 schema 翻译为 Provider 原生格式随请求发出，但本层不执行工具</li>
 *   <li>失败时原样抛出，调用方收到一次错误，不重试</li>
 *   <li>同步调用，不流式</li>
 * </ul>
 */
public interface ProviderService {

    /**
     * 按名称发起一次 LLM 调用。
     *
     * @param providerName 路由键，必须与 {@code application.yml} 中配置的某条 {@code name} 完全相等
     * @param request      调用参数（消息、工具 schema、采样参数等）
     * @return LLM 响应（文本 + 工具调用 + token 用量）
     * @throws UnknownProviderException       {@code providerName} 未在实例目录中配置
     * @throws LlmInvocationException         LLM 调用失败（凭证错、网络错、Provider 错等），
     *                                        但审计行已写入，{@code success=false}
     */
    LlmResponse invoke(String providerName, LlmRequest request);
}

/**
 * LLM 调用入参。
 *
 * <p>本对象由调用方（ReAct 循环层）从 Profile 翻译而来；Provider 层不读 Profile / YAML。
 */
final class LlmRequest {

    /** 调用方会话标识（可空：CLI 直调无 session） */
    private final UUID sessionId;

    /** 调用方 Profile 名（用于审计行） */
    private final String profileName;

    /** 对话历史 + 当前用户消息，按 OpenAI 风格 {role, content} 列表 */
    private final List<Map<String, Object>> messages;

    /** 工具 schema 列表（已翻译为 Provider 中立的 JSON Schema 形式） */
    private final List<Map<String, Object>> toolSchemas;

    /** 采样温度；null 则使用 Provider 默认 */
    private final Double temperature;

    /** 最大输出 token；null 则不限制 */
    private final Integer maxTokens;

    // ... 构造器 / getter 省略（实现时按需补全）
}

/**
 * LLM 调用出参。
 *
 * <p>工具调用以 Provider 中立格式返回：每个 toolCall 包含 name 与 arguments（JSON object）。
 * Provider 层不持有任何 ToolCallback / FunctionCallback，调用方拿到这个列表后自己 dispatch。
 */
final class LlmResponse {

    /** LLM 输出的纯文本（无 tool call 时的主输出） */
    private final String textContent;

    /** LLM 建议调用的工具列表（可能为空） */
    private final List<ToolCall> toolCalls;

    /** Token 用量 */
    private final TokenUsage usage;

    /** 终止原因：{@code stop} / {@code tool_calls} / {@code length} / {@code content_filter} */
    private final String finishReason;

    public static final class ToolCall {
        private final String name;        // 工具名
        private final String arguments;   // 参数，JSON 字符串
        private final String callId;      // Provider 给的调用 ID
        // ... 构造器 / getter 省略
    }

    public static final class TokenUsage {
        private final Integer promptTokens;
        private final Integer completionTokens;
        // ... 构造器 / getter 省略
    }
}

/**
 * Provider 名称未在实例目录中配置。
 *
 * <p>启动期校验可发现此错误时直接 fail-fast；运行期出现通常意味着配置漂移。
 */
final class UnknownProviderException extends RuntimeException {
    public UnknownProviderException(String name) {
        super("Unknown provider: '" + name + "'. " +
              "Check oryxos.providers.* in application.yml.");
    }
}

/**
 * LLM 调用失败。
 *
 * <p>此异常被抛出时，审计行已写入 {@code llm_calls}（{@code success=false}）。
 * 调用方不应再尝试重试或回退——直接处理错误并呈现给上层。
 */
final class LlmInvocationException extends RuntimeException {
    private final String providerName;   // 用于日志/响应中标识 Provider
    private final Long durationMs;       // 已用时间，便于上层做超时判定

    public LlmInvocationException(String providerName, String message,
                                  Long durationMs, Throwable cause) {
        super(message, cause);
        this.providerName = providerName;
        this.durationMs = durationMs;
    }
}
