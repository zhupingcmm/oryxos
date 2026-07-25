package io.oryxos.core;

import java.util.Map;

/**
 * Tool 派发契约 —— ReActLoop 通过本接口调用 Tool。
 *
 * <p>详见 [contracts/ToolExecutor.md](../../../../../specs/002-react-loop/contracts/ToolExecutor.md) §1。
 *
 * <h2>实现责任</h2>
 * <ol>
 *   <li>校验 {@code toolName} 是否在 {@code profile.tools()} 白名单；不在则返回
 *       {@code ToolResult.error("tool not in profile: " + toolName)}，
 *       且 MUST 写一行 {@code ToolInvocationRecord}（{@code success=false}）。</li>
 *   <li>每次调用 MUST 写一行 {@code ToolInvocationRecord}（C-TE-2），
 *       无论成功失败。</li>
 *   <li>{@code ToolInvocationRecord.session_iteration} = {@code ProfileContext.current().currentIteration().get()}。</li>
 *   <li>工具自身抛 unchecked 异常时，捕获、返回 {@code ToolResult.error(ex.getMessage())} 并写 {@code success=false} 行。</li>
 * </ol>
 */
public interface ToolExecutor {

    /**
     * 派发一次 Tool 调用。
     *
     * @param toolName  工具名（调用方保证已通过 LLM 检验、需走白名单二次确认）
     * @param arguments 解析后的 JSON 参数
     * @param profile   当前 Profile（用于白名单校验 + 审计行的 profile_name）
     * @return 工具执行结果
     */
    ToolResult invoke(String toolName, Map<String, Object> arguments, Profile profile);
}
