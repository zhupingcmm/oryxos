package io.oryxos.core;

/**
 * LLM Provider 路由层对外入口。
 *
 * <p>本接口属于 <code>oryxos-core</code>（按 <a href="../../../../../specs/002-react-loop/research.md">research.md</a>
 * 决策 R-1 从 <code>oryxos-provider</code> 下沉），是 ReAct 循环层的唯一 LLM 调用入口。
 *
 * <h2>契约条款（来自 contracts/ProviderService.md §2）</h2>
 * <ul>
 *   <li>{@code C-PS-1} 每次 invoke 产出一行 {@code LlmCallRecord}</li>
 *   <li>{@code C-PS-2} providerName 未配置抛
 *       {@link io.oryxos.provider.exception.UnknownProviderException}（不写审计行）</li>
 *   <li>{@code C-PS-3} LLM 调用失败抛
 *       {@link io.oryxos.provider.exception.LlmInvocationException}（已写审计行 success=false）</li>
 *   <li>{@code C-PS-4} LLM 响应仅含 tool_calls 无 text 时 {@link LlmResponse#textContent()} 为
 *       {@code null} 或 {@code ""}</li>
 *   <li>{@code C-PS-5} 同步、非流式；不暴露 streaming API 给 core</li>
 *   <li>{@code C-PS-6} 多 Provider 并存时按 {@code providerName} 显式路由，
 *       <strong>不</strong>依赖容器类型扫描</li>
 *   <li>{@code C-PS-7} 凭证占位符 {@code ${ENV_VAR}} 由 {@code CredentialResolver} 解析；
 *       缺失即 fail-fast</li>
 * </ul>
 *
 * <h2>实现位置</h2>
 * <p>实现在 {@code oryxos-provider} 模块的 {@code DefaultProviderService}，
 * 本模块不引入 Spring AI 或其他 LLM Provider 框架的传递依赖。
 *
 * @implNote 实现必须：
 * <ul>
 *   <li>写入 {@code llm_calls} 审计行（每调一行，含 success / errorMessage / duration）</li>
 *   <li>使用 <code>ProviderRegistry.get(name)</code> 按显式 name 路由，<strong>不</strong>扫容器</li>
 *   <li>同步阻塞调用 ChatModel.call(Prompt)，不引入 ChatClient</li>
 *   <li>设置 {@code internalToolExecutionEnabled(false)}，物理上关闭 Spring AI 自动工具执行</li>
 * </ul>
 */
public interface ProviderService {

    /**
     * 按名称发起一次 LLM 调用。
     *
     * @param providerName 路由键，必须与 {@code application.yml} 中配置的某条 {@code name} 完全相等
     *                      （spec FR-006 / C-PS-6）
     * @param request      LLM 调用入参（messages / toolSchemas / 采样参数）
     * @return LLM 响应（文本 + 工具调用 + token 用量）
     * @throws io.oryxos.provider.exception.UnknownProviderException
     *         {@code providerName} 未在实例目录中配置（C-PS-2，不写审计行）
     * @throws io.oryxos.provider.exception.LlmInvocationException
     *         LLM 调用失败（凭证错、网络错、Provider 错等），
     *         但审计行已写入 {@code success=false}（C-PS-3）
     */
    LlmResponse invoke(String providerName, LlmRequest request);
}
