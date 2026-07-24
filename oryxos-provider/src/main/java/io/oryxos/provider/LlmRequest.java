package io.oryxos.provider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link ProviderService#invoke(String, LlmRequest)} 的入参。
 *
 * <p>本对象由调用方（ReAct 循环层）从 Profile 翻译而来；Provider 层不读 Profile / YAML
 * （spec FR-006 + research.md R-06）。
 *
 * @param sessionId   会话标识；可空（CLI 直调无 session）
 * @param profileName 调用方 Profile 名；用于审计行 {@code profile_name}
 * @param model       模型名覆盖（来自 Profile {@code provider.model}）；为空则用 {@code application.yml} 默认
 * @param messages    对话历史 + 当前用户消息，按 OpenAI 风格 {@code [{role, content}]}
 * @param toolSchemas 要声明的工具 schema 列表（已翻译为 Provider 中立的 JSON Schema 形式）
 * @param temperature 采样温度；{@code null} 则使用 Provider 默认
 * @param maxTokens   最大输出 token；{@code null} 则不限制
 */
public record LlmRequest(
    UUID sessionId,
    String profileName,
    String model,
    List<Map<String, Object>> messages,
    List<Map<String, Object>> toolSchemas,
    Double temperature,
    Integer maxTokens
) {

    /**
     * 解析最终使用的 model：{@link #model} 非空时用它（Profile 热切换路径，US-4），
     * 否则回落到 {@code application.yml} 中该 Provider 配置的默认 model。
     */
    public String modelNameOrDefault(String registryDefault) {
        return (model == null || model.isBlank()) ? registryDefault : model;
    }
}