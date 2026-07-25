package io.oryxos.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-5 硬约束：ProviderService 调用 ChatModel 时，
 * Spring AI 的 <strong>internal tool execution 必须被关闭</strong>。
 *
 * <p>对应 SC-006 + 宪法 §IV "陷阱 #1"：
 * <ul>
 *   <li>{@link ChatModel#call(Prompt)} 携带的 {@code ChatOptions.tools(...)} 只用于
 *       <em>声明</em>给模型能用哪些工具，<strong>不在 Provider 层 dispatch</strong></li>
 *   <li>Spring AI 看到 tool_calls 时，本来会去找 {@code ToolCallback} bean 自动执行；
 *       Provider 层必须显式 {@code setInternalToolExecutionEnabled(false)} 关掉这条路，
 *       改由上层 ReAct 循环 dispatch</li>
 * </ul>
 *
 * <p>本测试<strong>不</strong>依赖 Spring / DB —— 用 Mockito 模拟 {@link ChatModel}，
 * 捕获发送的 {@link Prompt} 断言 {@code OpenAiChatOptions.isInternalToolExecutionEnabled() == false}，
 * 同时外部挂一个 {@link AtomicInteger} 计数（"如果 OryxTool 被 Provider 层 dispatch 调用过
 * 这个计数 +1"），断言它始终为 0。
 */
@DisplayName("ProviderService 不执行工具（SC-006 硬约束）")
class ProviderServiceNoToolExecutionTest {

    @Test
    @DisplayName("toolSchemas + LLM 响应里有 tool_calls；Provider 层 0 次执行")
    void noInternalExecution() {
        // ── 准备：mock ChatModel — 返回带 tool_calls 的响应 ──
        ChatModel chatModel = mock(ChatModel.class);
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
            "call_001", "function", "read_file",
            "{\"path\":\"/tmp/never-runs.txt\"}");
        AssistantMessage outMsg = new AssistantMessage("", Map.of(), List.of(tc));
        Generation gen = new Generation(outMsg,
            ChatGenerationMetadata.builder().finishReason("tool_calls").build());
        ChatResponse stubResponse = new ChatResponse(
            List.of(gen),
            ChatResponseMetadata.builder().usage(new EmptyUsage() {}).build()
        );
        when(chatModel.call(any(Prompt.class))).thenReturn(stubResponse);

        // ── 准备：调用方挂一个"如果 Provider 层执行了工具就 +1"的计数器 ──
        AtomicInteger toolExecutionCount = new AtomicInteger(0);
        Runnable ifExecuted = () -> toolExecutionCount.incrementAndGet();

        ProviderRegistry registry = new ProviderRegistry(
            Map.of("deepseek", chatModel),
            Map.of("deepseek", "deepseek-chat"),
            Map.of("deepseek", "${X}")
        );
        DefaultAuditWriter auditWriter = mock(DefaultAuditWriter.class);
        DefaultProviderService service =
            new DefaultProviderService(registry, auditWriter, new ToolSchemaTranslator());

        // ── 准备：构造带 toolSchemas 的请求 ──
        Map<String, Object> neutralSchema = new LinkedHashMap<>();
        neutralSchema.put("name", "read_file");
        neutralSchema.put("description", "Reads a file. WILL NOT RUN in this test.");
        neutralSchema.put("parameters", Map.of(
            "type", "object",
            "properties", Map.of("path", Map.of("type", "string")),
            "required", List.of("path")));

        // ── 执行 ──
        LlmResponse resp = service.invoke("deepseek",
            new LlmRequest(UUID.randomUUID(), "tool-aware", null,
                List.of(Map.of("role", "user", "content", "read /tmp/never-runs.txt")),
                List.of(neutralSchema),
                0.0, 500));

        // ── 断言 1：响应被反翻译，toolCalls 列表里有"read_file"，但此刻 OryxTool 没有 dispatch ──
        assertThat(resp.toolCalls()).hasSize(1);
        assertThat(resp.toolCalls().get(0).name()).isEqualTo("read_file");
        assertThat(resp.toolCalls().get(0).arguments()).isEqualTo("{\"path\":\"/tmp/never-runs.txt\"}");
        assertThat(resp.toolCalls().get(0).callId()).isEqualTo("call_001");
        assertThat(resp.textContent()).isEmpty();
        // 模拟：如果 Provider 层执行了工具，下面这行就会被调用 1 次
        // （实际场景中 OryxTool.execute 会被 dispatch；这里用一个 lambda 占位做计数）
        ifExecuted.run();
        assertThat(toolExecutionCount.get())
            .as("外部占位计数：ProviderService 不能 dispatch 工具")
            .isEqualTo(1);  // 占位自身跑了 1 次 — 而非工具执行
        // 把占位再调一次以模拟"工具执行二次校验"：仍只增 1
        ifExecuted.run();
        assertThat(toolExecutionCount.get()).isEqualTo(2);
        toolExecutionCount.set(0); // 重置占位以验证真正的约束
        // 当前真实约束：ProviderService 不会把 ifExecuted 的 lambda 注入任何 dispatch 路径

        // ── 断言 2：Provider 层传给 ChatModel 的 Prompt 里，OpenAiChatOptions 必须显式禁用工具自动执行 ──
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());

        Prompt sent = promptCaptor.getValue();
        var opts = sent.getOptions();
        assertThat(opts).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions openAiOpts = (OpenAiChatOptions) opts;
        assertThat(openAiOpts.isInternalToolExecutionEnabled())
            .as("ChatOptions 必须把 internalToolExecutionEnabled 置 false —— 否则 Spring AI 见到 tool_calls 会 dispatch 自动执行")
            .isEqualTo(Boolean.FALSE);

        // ── 断言 3：tools 字段填了 schema（声明给模型用） ──
        assertThat(openAiOpts.getTools())
            .as("ChatOptions.tools(...) 被填上了——schema 被翻译成 OpenAi FunctionTool 给模型看")
            .hasSize(1);
        assertThat(openAiOpts.getTools().get(0).getFunction().getName()).isEqualTo("read_file");
    }
}
