package io.oryxos.provider;

import io.oryxos.core.LlmResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具 schema 翻译器（中立 ↔ OpenAI FunctionTool）+ 响应反翻译。
 *
 * <p>职责（spec FR-009 / FR-010）：
 * <ol>
 *   <li>{@link #translate(List)}：把 Provider 中立的 {@code List<Map<String,Object>>}
 *       schema 翻译成 Spring AI 的 {@link OpenAiApi.FunctionTool}，
 *       字段对齐 OpenAI 原生 {@code {type:"function", function:{name, description, parameters}}}</li>
 *   <li>{@link #denormalize(ChatResponse)}：把 Spring AI 响应里的
 *       {@link AssistantMessage.ToolCall} 列表解析为 {@link LlmResponse.ToolCall}
 *       中立列表</li>
 * </ol>
 *
 * <p>关键约束（宪法 §IV "陷阱 #1"）：
 * <ul>
 *   <li><strong>不</strong>接受、不产出任何 {@code ToolCallback} / {@code FunctionCallback}
 *       —— 编译期阻止 Spring AI 自动执行工具</li>
 *   <li>无 IO、无状态、可单测 — {@code new ToolSchemaTranslator()} 即可用</li>
 *   <li>{@code translate} 输入 schema 缺 {@code name} → fail-fast；缺 {@code parameters}
 *       → 默认空 schema object（OpenAI 协议允许）</li>
 * </ul>
 */
@Component
public class ToolSchemaTranslator {

    /**
     * 把中立 schema 列表翻译为 OpenAI FunctionTool 列表。
     *
     * @param neutral 每条 {@code Map} 必含 {@code name}，可选含 {@code description}
     *                与 {@code parameters}（JSON Schema 形式）
     * @return 顺序保持、互不共享的 FunctionTool 列表；空输入返回空列表
     * @throws IllegalArgumentException 任一条 schema 缺 {@code name} / 形态异常
     */
    public List<OpenAiApi.FunctionTool> translate(List<Map<String, Object>> neutral) {
        if (neutral == null || neutral.isEmpty()) {
            return List.of();
        }
        List<OpenAiApi.FunctionTool> out = new ArrayList<>(neutral.size());
        for (Map<String, Object> entry : neutral) {
            out.add(toFunctionTool(entry));
        }
        return out;
    }

    /**
     * 把 {@link ChatResponse} 反翻译为中立 {@link LlmResponse.ToolCall} 列表。
     *
     * <p>只翻译 <strong>不</strong>执行 — 即使响应里含有 {@code tool_calls}，
     * 本方法也只是把它们挑出来给调用方 dispatch。Spring AI 的内部执行链路
     * 在 {@code DefaultProviderService} 通过 {@code setInternalToolExecutionEnabled(false)}
     * 关闭（宪法 §IV）。
     *
     * @param response 来自 {@code ChatModel.call} 的响应；可空
     * @return 反翻译后的中立 tool call 列表；无 tool_calls / null 输入 → 空列表
     */
    public List<LlmResponse.ToolCall> denormalize(ChatResponse response) {
        if (response == null) return List.of();
        List<Generation> generations = response.getResults();
        if (generations == null || generations.isEmpty()) return List.of();

        List<LlmResponse.ToolCall> out = new ArrayList<>();
        for (Generation gen : generations) {
            if (gen == null || gen.getOutput() == null) continue;
            AssistantMessage msg = gen.getOutput();
            List<AssistantMessage.ToolCall> raw = msg.getToolCalls();
            if (raw == null || raw.isEmpty()) continue;
            for (AssistantMessage.ToolCall tc : raw) {
                out.add(new LlmResponse.ToolCall(tc.name(), tc.arguments(), tc.id()));
            }
        }
        return out;
    }

    // --- internals ---

    private static OpenAiApi.FunctionTool toFunctionTool(Map<String, Object> entry) {
        Object nameRaw = entry.get("name");
        if (nameRaw == null || !(nameRaw instanceof String name) || name.isBlank()) {
            throw new IllegalArgumentException(
                "Tool schema entry is missing 'name' field. Refusing to translate. " +
                "Schema keys: " + entry.keySet());
        }
        String description = entry.get("description") instanceof String d ? d : "";
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = entry.get("parameters") instanceof Map
            ? (Map<String, Object>) entry.get("parameters")
            : Map.of("type", "object");

        OpenAiApi.FunctionTool.Function fn =
            new OpenAiApi.FunctionTool.Function(description, name, parameters, null);
        return new OpenAiApi.FunctionTool(OpenAiApi.FunctionTool.Type.FUNCTION, fn);
    }
}
