package io.oryxos.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.chat.messages.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-5 工具 schema 翻译单元测试。
 *
 * <p>对应 spec FR-009 / FR-010：
 * <ol>
 *   <li><strong>translate</strong>：把 Provider 中立的 {@code List<Map<String,Object>>}
 *       工具 schema 翻译成 Spring AI 的 {@link OpenAiApi.FunctionTool} 列表，
 *       字段对齐 OpenAI 原生 {@code {type:"function", function:{name, description, parameters}}}</li>
 *   <li><strong>denormalize</strong>：把 Spring AI 的 {@link ChatResponse} 解析为
 *       {@link LlmResponse.ToolCall} 中立列表（不执行，只翻译）</li>
 * </ol>
 *
 * <p><strong>本 translator 静态、无 IO、无状态</strong>，所以直接 {@code new ToolSchemaTranslator()}，
 * 不依赖 Spring 容器。
 */
@DisplayName("ToolSchemaTranslator (US-5)")
class ToolSchemaTranslatorTest {

    private final ToolSchemaTranslator translator = new ToolSchemaTranslator();

    // ────────────────────────────────────────────────────────────────────
    // translate: 中立 → OpenAiApi.FunctionTool
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("translate: 中立 schema → OpenAI FunctionTool")
    class TranslateTests {

        @Test
        @DisplayName("单条 schema：name + description + parameters 完整映射")
        void translateSingle() {
            Map<String, Object> neutral = new LinkedHashMap<>();
            neutral.put("name", "read_file");
            neutral.put("description", "Read the file at the given path.");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "object");
            params.put("properties", Map.of(
                "path", Map.of("type", "string", "description", "absolute file path")));
            params.put("required", List.of("path"));
            neutral.put("parameters", params);

            List<OpenAiApi.FunctionTool> result = translator.translate(List.of(neutral));

            assertThat(result).hasSize(1);
            OpenAiApi.FunctionTool ft = result.get(0);
            assertThat(ft.getType()).isEqualTo(OpenAiApi.FunctionTool.Type.FUNCTION);
            assertThat(ft.getFunction().getName()).isEqualTo("read_file");
            assertThat(ft.getFunction().getDescription()).isEqualTo("Read the file at the given path.");
            assertThat(ft.getFunction().getParameters()).isEqualTo(params);
        }

        @Test
        @DisplayName("多条 schema：顺序保持 + 互不干扰")
        void translateMultiple() {
            Map<String, Object> a = Map.of(
                "name", "tool_a",
                "description", "Alpha",
                "parameters", Map.of("type", "object"));
            Map<String, Object> b = Map.of(
                "name", "tool_b",
                "description", "Beta",
                "parameters", Map.of("type", "object", "properties", Map.of("x", Map.of("type", "integer"))));

            List<OpenAiApi.FunctionTool> result = translator.translate(List.of(a, b));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getFunction().getName()).isEqualTo("tool_a");
            assertThat(result.get(1).getFunction().getName()).isEqualTo("tool_b");
            assertThat(result.get(0).getFunction().getParameters().get("type")).isEqualTo("object");
            assertThat(result.get(1).getFunction().getParameters().get("properties"))
                .isEqualTo(Map.of("x", Map.of("type", "integer")));
        }

        @Test
        @DisplayName("空列表原样返回（不抛）")
        void translateEmpty() {
            assertThat(translator.translate(List.of())).isEmpty();
        }

        @Test
        @DisplayName("缺 name 时拒翻译——schema 不全 = 拿不到 tool_calls；fail-fast")
        void translateMissingName() {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("description", "no name");
            bad.put("parameters", Map.of("type", "object"));

            assertThatThrownBy(() -> translator.translate(List.of(bad)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // denormalize: ChatResponse → 中立 ToolCall 列表
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("denormalize: ChatResponse → 中立 ToolCall 列表")
    class DenormalizeTests {

        @Test
        @DisplayName("响应带 2 个 tool_calls → 列表里有 2 个 ToolCall（含 name/arguments/callId）")
        void denormalizeTwoToolCalls() {
            AssistantMessage.ToolCall tc1 = new AssistantMessage.ToolCall(
                "call_1", "function", "read_file",
                "{\"path\":\"/tmp/a.txt\"}");
            AssistantMessage.ToolCall tc2 = new AssistantMessage.ToolCall(
                "call_2", "function", "notify",
                "{\"channel\":\"#ops\",\"content\":\"done\"}");

            AssistantMessage msg = new AssistantMessage(
                "", Map.of(), List.of(tc1, tc2));
            ChatResponse response = new ChatResponse(List.of(new Generation(msg)));

            List<LlmResponse.ToolCall> out = translator.denormalize(response);

            assertThat(out).hasSize(2);
            assertThat(out.get(0).name()).isEqualTo("read_file");
            assertThat(out.get(0).arguments()).isEqualTo("{\"path\":\"/tmp/a.txt\"}");
            assertThat(out.get(0).callId()).isEqualTo("call_1");
            assertThat(out.get(1).name()).isEqualTo("notify");
            assertThat(out.get(1).arguments()).isEqualTo("{\"channel\":\"#ops\",\"content\":\"done\"}");
            assertThat(out.get(1).callId()).isEqualTo("call_2");
        }

        @Test
        @DisplayName("响应无 tool_calls（普通文本回复）→ 返回空列表")
        void denormalizeNoToolCalls() {
            AssistantMessage msg = new AssistantMessage(
                "Hello, world", Map.of(), List.of());
            ChatResponse response = new ChatResponse(List.of(new Generation(msg)));

            assertThat(translator.denormalize(response)).isEmpty();
        }

        @Test
        @DisplayName("ChatResponse 为 null → 空列表（不抛）")
        void denormalizeNullSafe() {
            assertThat(translator.denormalize(null)).isEmpty();
        }

        @Test
        @DisplayName("空 Generation 列表 → 空列表")
        void denormalizeEmptyGenerations() {
            ChatResponse response = new ChatResponse(List.of());
            assertThat(translator.denormalize(response)).isEmpty();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // translator 自身的硬约束
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("translator 硬约束（US-5 边界）")
    class InvariantTests {

        @Test
        @DisplayName("无状态：同一 translator 多次 translate 互不影响")
        void statelessTranslate() {
            Map<String, Object> schema = Map.of(
                "name", "x", "description", "d", "parameters", Map.of("type", "object"));

            List<OpenAiApi.FunctionTool> r1 = translator.translate(List.of(schema));
            List<OpenAiApi.FunctionTool> r2 = translator.translate(List.of(schema));

            assertThat(r1).isNotSameAs(r2);          // 返回独立 List
            assertThat(r1.get(0)).isNotSameAs(r2.get(0)); // 独立 FunctionTool 实例
            assertThat(r1.get(0).getFunction().getName()).isEqualTo("x");
            assertThat(r2.get(0).getFunction().getName()).isEqualTo("x");
        }

        @Test
        @DisplayName("持有任何 FunctionCallback 都会被翻译；不持有 ToolCallback — 物理上消除自动执行")
        void translatesToInertList_notCallbacks() {
            // Translator 输出类型为 OpenAiApi.FunctionTool，不接受/产出 ToolCallback
            Map<String, Object> schema = Map.of(
                "name", "x", "description", "d", "parameters", Map.of("type", "object"));

            List<OpenAiApi.FunctionTool> out = translator.translate(List.of(schema));

            // 输出是 List<OpenAiApi.FunctionTool>，编译期保证不含 ToolCallback / FunctionCallback
            assertThat(out).isInstanceOf(List.class);
            assertThat(out.get(0)).isInstanceOf(OpenAiApi.FunctionTool.class);
        }

        // 静态 helper reference to keep the import used
        @SuppressWarnings("unused")
        private List<Message> noop() { return Stream.<Message>of(new AssistantMessage("")).toList(); }
    }
}
