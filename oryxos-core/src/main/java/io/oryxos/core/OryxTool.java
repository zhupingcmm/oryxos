package io.oryxos.core;

import java.util.Map;

/**
 * OryxOS 统一的 Tool 抽象 —— 所有可被 LLM 调用的能力都实现本接口。
 *
 * <p>US-2 阶段仅占位（最小签名），真实实现归 US-4：
 * <ul>
 *   <li>内置 Tool（{@code FileTools} / {@code ShellTools} / {@code HttpTools} / {@code NotifyTools}）</li>
 *   <li>{@code @Tool} Bean（Spring AI schema 生成 + 自管 dispatch）</li>
 *   <li>MCP Tool（{@code McpToolAdapter}）</li>
 *   <li>SKILL.md 类的脚本封装</li>
 * </ul>
 *
 * <p>US-4 实现时应在保持本接口的同时，新增"schema 声明"等元数据方法；
 * 当前不做 commit —— 本接口形状以"循环可用"为最小目标。
 */
public interface OryxTool {

    /** Tool 名（全局唯一，与 Profile {@code tools[]} 中的字符串一致）。 */
    String name();

    /**
     * 执行 Tool。
     *
     * @param arguments LLM 解析后的 JSON 参数 map（按 Tool schema 校验后传入）
     * @return 执行结果 —— 成功返 {@code ToolResult.ok(...)}，失败返 {@code ToolResult.error(...)}
     */
    ToolResult execute(Map<String, Object> arguments);
}
