package io.oryxos.provider;

import io.oryxos.core.LlmRequest;
import io.oryxos.core.LlmResponse;
import io.oryxos.core.ProviderService;
import io.oryxos.provider.exception.LlmInvocationException;
import io.oryxos.provider.exception.UnknownProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * US-1: DefaultProviderService 路由 + 异常路径。
 *
 * <p>覆盖：
 * <ul>
 *   <li>按 name 路由到对应 ChatModel</li>
 *   <li>未知 name 抛 {@link UnknownProviderException}</li>
 *   <li>ChatModel 抛异常 → 包成 {@link LlmInvocationException}</li>
 * </ul>
 */
@DisplayName("DefaultProviderService")
class DefaultProviderServiceTest {

    private ProviderRegistry registry;
    private DefaultAuditWriter auditWriter;
    private DefaultProviderService service;

    @BeforeEach
    void setUp() {
        registry = mock(ProviderRegistry.class);
        auditWriter = mock(DefaultAuditWriter.class);
        service = new DefaultProviderService(registry, auditWriter, new ToolSchemaTranslator());
    }

    @Nested
    @DisplayName("路由（routing）")
    class Routing {

        @Test
        @DisplayName("按 name 路由到对应 ChatModel，并返回 LlmResponse")
        void routesByName() {
            ChatModel deepseek = mock(ChatModel.class);
            when(registry.get("deepseek")).thenReturn(deepseek);
            when(registry.defaultModelFor("deepseek")).thenReturn("deepseek-chat");
            when(deepseek.call(any(Prompt.class))).thenReturn(simpleChatResponse("hi"));

            LlmRequest req = sampleRequest();
            LlmResponse resp = service.invoke("deepseek", req);

            assertThat(resp.textContent()).isEqualTo("hi");
            verify(registry, times(1)).get("deepseek");
            verify(deepseek, times(1)).call(any(Prompt.class));
        }

        @Test
        @DisplayName("Profile 声明 model 时覆盖 application.yml 默认（US-4 路径）")
        void profileModelOverridesDefault() {
            ChatModel deepseek = mock(ChatModel.class);
            when(registry.get("deepseek")).thenReturn(deepseek);
            when(registry.defaultModelFor("deepseek")).thenReturn("deepseek-chat");
            when(deepseek.call(any(Prompt.class))).thenReturn(simpleChatResponse("ok"));

            LlmRequest req = new LlmRequest(UUID.randomUUID(), "route-demo", "deepseek-coder",
                List.of(Map.of("role", "user", "content", "ping")),
                List.of(), 0.5, 1000);

            service.invoke("deepseek", req);

            ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> captor =
                ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
            verify(deepseek).call(captor.capture());
            assertThat(captor.getValue().getOptions().getModel()).isEqualTo("deepseek-coder");
        }
    }

    @Nested
    @DisplayName("异常路径")
    class Errors {

        @Test
        @DisplayName("未知 Provider name 抛 UnknownProviderException 并写一行 audit")
        void unknownProviderThrows() {
            when(registry.get("gpt-99")).thenThrow(new UnknownProviderException("gpt-99"));

            LlmRequest req = sampleRequest();
            assertThatThrownBy(() -> service.invoke("gpt-99", req))
                .isInstanceOf(UnknownProviderException.class)
                .hasMessageContaining("gpt-99");

            verify(auditWriter, times(1)).write(any());
            verify(registry, never()).get("qwen");
        }

        @Test
        @DisplayName("ChatModel 抛异常 → 包成 LlmInvocationException + audit 写入")
        void chatModelFailureWrapped() {
            ChatModel deepseek = mock(ChatModel.class);
            when(registry.get("deepseek")).thenReturn(deepseek);
            when(registry.defaultModelFor("deepseek")).thenReturn("deepseek-chat");
            when(deepseek.call(any(Prompt.class))).thenThrow(new RuntimeException("401 unauthorized"));

            LlmRequest req = sampleRequest();
            assertThatThrownBy(() -> service.invoke("deepseek", req))
                .isInstanceOf(LlmInvocationException.class)
                .hasMessageContaining("401");

            verify(auditWriter, times(1)).write(any());
        }
    }

    // --- helpers ---

    private LlmRequest sampleRequest() {
        return new LlmRequest(UUID.randomUUID(), "route-demo", null,
            List.of(Map.of("role", "user", "content", "ping")),
            List.of(), 0.5, 1000);
    }

    private ChatResponse simpleChatResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}