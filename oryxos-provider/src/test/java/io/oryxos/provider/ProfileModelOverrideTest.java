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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-4 通过 Profile {@code provider.model} 字段热切换模型的单元测试。
 *
 * <p>不依赖 Spring / 数据库——直接 {@code new DefaultProviderService(registry, auditWriter)}，
 * 用 Mockito 模拟 {@link ChatModel} 调用，捕获 {@link Prompt} 断言 {@code ChatOptions.model}。
 *
 * <p>对应 SC-003：Profile 改 model，下次 invoke 立即用新 model，原 Provider 配置不动。
 */
@DisplayName("Profile 热切换 model")
class ProfileModelOverrideTest {

    @Test
    @DisplayName("LlmRequest.model 非空时覆盖 Provider 默认 model（ChatOptions.model == request.model）")
    void requestModelWinsOverDefault() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(stubResponse("Hello from coder"));

        ProviderRegistry registry = new ProviderRegistry(
            Map.of("deepseek", chatModel),
            Map.of("deepseek", "deepseek-chat"),
            Map.of("deepseek", "${DEEPSEEK_API_KEY}")
        );
        DefaultAuditWriter auditWriter = mock(DefaultAuditWriter.class);
        DefaultProviderService service = new DefaultProviderService(registry, auditWriter, new ToolSchemaTranslator());

        LlmRequest reqWithOverride = new LlmRequest(
            UUID.randomUUID(), "sw-profile", "deepseek-coder",
            List.of(Map.of("role", "user", "content", "explain code")),
            List.of(), 0.3, 800
        );

        LlmResponse resp = service.invoke("deepseek", reqWithOverride);
        assertThat(resp.textContent()).isEqualTo("Hello from coder");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());

        Prompt sent = promptCaptor.getValue();
        assertThat(sent.getOptions()).isNotNull();
        assertThat(sent.getOptions().getModel()).isEqualTo("deepseek-coder");
        // LlmRequest.temperature=0.3 也应被透传到 ChatOptions
        assertThat(sent.getOptions().getTemperature()).isEqualTo(0.3);
        assertThat(sent.getOptions().getMaxTokens()).isEqualTo(800);
    }

    @Test
    @DisplayName("LlmRequest.model 空时回落到 Provider 默认 model（application.yml）")
    void nullRequestModelFallsBackToYmlDefault() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(stubResponse("Hello from chat"));

        ProviderRegistry registry = new ProviderRegistry(
            Map.of("deepseek", chatModel),
            Map.of("deepseek", "deepseek-chat"),
            Map.of("deepseek", "${DEEPSEEK_API_KEY}")
        );
        DefaultProviderService service = new DefaultProviderService(registry, mock(DefaultAuditWriter.class), new ToolSchemaTranslator());

        LlmRequest reqNoOverride = new LlmRequest(
            UUID.randomUUID(), "chat-profile", null,
            List.of(Map.of("role", "user", "content", "chat")),
            List.of(), null, null
        );

        service.invoke("deepseek", reqNoOverride);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("deepseek-chat");
    }

    @Test
    @DisplayName("LlmRequest.model 是空白字符串时同样回落到 Provider 默认")
    void blankRequestModelFallsBackToYmlDefault() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(stubResponse("x"));

        ProviderRegistry registry = new ProviderRegistry(
            Map.of("deepseek", chatModel),
            Map.of("deepseek", "deepseek-chat"),
            Map.of("deepseek", "${X}")
        );
        DefaultProviderService service = new DefaultProviderService(registry, mock(DefaultAuditWriter.class), new ToolSchemaTranslator());

        service.invoke("deepseek",
            new LlmRequest(UUID.randomUUID(), "p", "   ",
                List.of(Map.of("role", "user", "content", "x")),
                List.of(), null, null));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("deepseek-chat");
    }

    // --- helpers ---

    private static ChatResponse stubResponse(String text) {
        var meta = new ChatResponseMetadata() {
            @Override public EmptyUsage getUsage() { return new EmptyUsage() {}; }
            // Suppress: parent has many abstract/deprecated members; we only need usage.
        };
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("stop").build())),
            meta
        );
    }
}
