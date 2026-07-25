package io.oryxos.provider;

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
     * @throws io.oryxos.provider.exception.UnknownProviderException
     *         {@code providerName} 未在实例目录中配置
     * @throws io.oryxos.provider.exception.LlmInvocationException
     *         LLM 调用失败（凭证错、网络错、Provider 错等），但审计行已写入，{@code success=false}
     */
    LlmResponse invoke(String providerName, LlmRequest request);
}