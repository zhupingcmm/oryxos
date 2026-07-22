/**
 * Capability 1 — LLM Provider abstraction.
 *
 * <p>{@code ProviderService} wraps Spring AI / Spring AI Alibaba's {@code ChatClient},
 * mapping provider names (deepseek, qwen, kimi, etc.) to {@code ChatModel} instances
 * via an <strong>explicit</strong> lookup table — never via container type scanning.
 *
 * <p>Spring AI is used here only for:
 * <ul>
 *   <li>Provider abstraction and protocol conversion (OpenAI tools, Anthropic tools, etc.)</li>
 *   <li>{@code @Tool} annotation JSON Schema generation</li>
 * </ul>
 *
 * <p><strong>Auto tool execution must remain disabled</strong> — the ReAct loop is
 * controlled entirely by {@code ToolExecutor} in {@code oryxos-core}. See constitution
 * principle 4.
 */
package io.oryxos.provider;