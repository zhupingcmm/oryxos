package io.oryxos.provider;

import io.oryxos.core.LlmRequest;
import io.oryxos.core.LlmResponse;
import io.oryxos.core.ProviderService;
import io.oryxos.provider.exception.LlmInvocationException;
import io.oryxos.provider.exception.UnknownProviderException;
import io.oryxos.storage.entity.LlmCallRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link ProviderService} 默认实现。
 *
 * <p>职责链（spec FR-005 ~ FR-014）：
 * <pre>
 *   1. 按 providerName 查 ProviderRegistry.get(name) → ChatModel
 *   2. 把 LlmRequest 翻译为 OpenAiChatOptions + Prompt
 *      （含 toolSchemas → OpenAi FunctionTool，由 ToolSchemaTranslator.translate 完成）
 *   3. ChatModel.call(Prompt) → ChatResponse
 *   4. 反翻译 ChatResponse → LlmResponse（Provider 中立格式，
 *      tool_calls → LlmResponse.toolCalls 由 ToolSchemaTranslator.denormalize 完成）
 *   5. 捕获 ChatModelException → 包成 LlmInvocationException
 *   6. 调用返回前：构造 LlmCallRecord + 委托 DefaultAuditWriter.write
 * </pre>
 *
 * <p>关键约束（宪法 §IV + research.md R-02）：
 * <ul>
 *   <li>使用 {@code ChatModel.call(Prompt)} 底层 API，<strong>不</strong>引入 {@code ChatClient}</li>
 *   <li><strong>不</strong>注入 {@code ToolCallback} / {@code FunctionCallback}（物理上消除自动工具执行）</li>
 *   <li>显式 {@code setInternalToolExecutionEnabled(false)}：即便模型返回 tool_calls，
 *       Spring AI 也不会去 dispatch，调用方拿 {@link LlmResponse#toolCalls()} 自 dispatch
 *       （US-5 SC-006）</li>
 *   <li>失败时原样抛出，调用方收到一次错误，<strong>不</strong>重试 / 不 fallback</li>
 * </ul>
 */
@Service
public class DefaultProviderService implements ProviderService {

    private static final Logger log = LoggerFactory.getLogger(DefaultProviderService.class);

    private final ProviderRegistry registry;
    private final DefaultAuditWriter auditWriter;
    private final ToolSchemaTranslator toolSchemaTranslator;

    public DefaultProviderService(ProviderRegistry registry,
                                  DefaultAuditWriter auditWriter,
                                  ToolSchemaTranslator toolSchemaTranslator) {
        this.registry = registry;
        this.auditWriter = auditWriter;
        this.toolSchemaTranslator = toolSchemaTranslator;
    }

    @Override
    public LlmResponse invoke(String providerName, LlmRequest request) {
        Instant start = Instant.now();

        // 1. 路由：按 name 拿 ChatModel；缺 name 抛 UnknownProviderException
        ChatModel chatModel;
        try {
            chatModel = registry.get(providerName);
        } catch (UnknownProviderException ex) {
            long elapsed = Duration.between(start, Instant.now()).toMillis();
            writeFailureAudit(request, providerName, elapsed, ex.getMessage());
            throw ex;
        }

        String model = request.modelNameOrDefault(registry.defaultModelFor(providerName));

        // 2. US-5：构造 OpenAiChatOptions；填 tools(...)，**并显式关掉 internalToolExecution**
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(model)
            .temperature(request.temperature())
            .maxTokens(request.maxTokens())
            .internalToolExecutionEnabled(false)        // ← 关掉自动工具执行（SC-006）
            .build();

        if (request.toolSchemas() != null && !request.toolSchemas().isEmpty()) {
            List<org.springframework.ai.openai.api.OpenAiApi.FunctionTool> tools =
                toolSchemaTranslator.translate(request.toolSchemas());
            options.setTools(tools);
            log.debug("LlmRequest carries {} tool schema(s); translated to OpenAI FunctionTool",
                tools.size());
        }

        // 3. 构造 Prompt：messages 来自 LlmRequest
        Prompt prompt = new Prompt(toMessages(request.messages()), options);

        // 4. 调 ChatModel；捕获异常 → 包成 LlmInvocationException
        ChatResponse chatResponse;
        try {
            chatResponse = chatModel.call(prompt);
        } catch (Exception ex) {
            long elapsed = Duration.between(start, Instant.now()).toMillis();
            log.warn("LLM call failed for provider={} model={} after {}ms: {}",
                providerName, model, elapsed, ex.toString());
            LlmInvocationException wrapped = new LlmInvocationException(
                providerName, ex.getMessage(), elapsed, ex);
            writeFailureAudit(request, providerName, model, elapsed, ex.getMessage());
            throw wrapped;
        }

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();

        // 5. 翻译 ChatResponse → LlmResponse（Provider 中立），含 tool_calls 反翻译
        LlmResponse response = translate(chatResponse);

        // 6. 写审计（成功路径）
        writeSuccessAudit(request, providerName, model, response, elapsedMs);

        log.info("LLM call OK provider={} model={} elapsedMs={} promptTokens={} completionTokens={} toolCalls={}",
            providerName, model, elapsedMs,
            response.usage() == null ? null : response.usage().promptTokens(),
            response.usage() == null ? null : response.usage().completionTokens(),
            response.toolCalls() == null ? 0 : response.toolCalls().size());

        return response;
    }

    // --- 翻译辅助 ---

    private List<Message> toMessages(List<Map<String, Object>> rawMessages) {
        List<Message> result = new ArrayList<>(rawMessages.size());
        for (Map<String, Object> raw : rawMessages) {
            String role = String.valueOf(raw.get("role"));
            String content = String.valueOf(raw.getOrDefault("content", ""));
            result.add(switch (role) {
                case "system"    -> new org.springframework.ai.chat.messages.SystemMessage(content);
                case "assistant" -> new org.springframework.ai.chat.messages.AssistantMessage(content);
                default          -> new org.springframework.ai.chat.messages.UserMessage(content);
            });
        }
        return result;
    }

    private LlmResponse translate(ChatResponse chatResponse) {
        // US-5：tool_calls 走 Translator.denormalize；Provider 中立形态返回
        List<LlmResponse.ToolCall> toolCalls = toolSchemaTranslator.denormalize(chatResponse);

        // textContent：取 AssistantMessage.getText()；
        // 当响应是 tool_calls 时 Spring AI 把 text 设为空，因此不会"覆盖" tool calls
        String textContent = "";
        if (chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
            textContent = String.valueOf(chatResponse.getResult().getOutput().getText());
        }

        LlmResponse.TokenUsage usage = null;
        if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            var u = chatResponse.getMetadata().getUsage();
            usage = new LlmResponse.TokenUsage(u.getPromptTokens(), u.getCompletionTokens());
        }

        String finishReason = chatResponse.getResult() != null
            && chatResponse.getResult().getMetadata() != null
            && chatResponse.getResult().getMetadata().getFinishReason() != null
                ? chatResponse.getResult().getMetadata().getFinishReason()
                : "stop";

        return new LlmResponse(textContent, toolCalls, usage, finishReason);
    }

    // --- 审计写入 ---

    private void writeSuccessAudit(LlmRequest request, String providerName, String model,
                                   LlmResponse response, long elapsedMs) {
        LlmCallRecord record = new LlmCallRecord(
            UUID.randomUUID(),
            request.sessionId(),
            nullToEmpty(request.profileName()),
            providerName,
            model,
            true,
            null,
            response.usage() != null ? response.usage().promptTokens() : null,
            response.usage() != null ? response.usage().completionTokens() : null,
            elapsedMs,
            Instant.now(),
            Map.of("textLength", response.textContent() == null ? 0 : response.textContent().length())
        );
        auditWriter.write(record);
    }

    private void writeFailureAudit(LlmRequest request, String providerName, long elapsedMs, String errorMsg) {
        writeFailureAudit(request, providerName, registry.defaultModelFor(providerName), elapsedMs, errorMsg);
    }

    private void writeFailureAudit(LlmRequest request, String providerName, String model,
                                   long elapsedMs, String errorMsg) {
        LlmCallRecord record = new LlmCallRecord(
            UUID.randomUUID(),
            request.sessionId(),
            nullToEmpty(request.profileName()),
            providerName,
            model == null ? "<unknown>" : model,
            false,
            errorMsg,
            null,
            null,
            elapsedMs,
            Instant.now(),
            null
        );
        auditWriter.write(record);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}