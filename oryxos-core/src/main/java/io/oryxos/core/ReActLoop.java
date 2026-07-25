package io.oryxos.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 循环主控 —— Reason → Act → Observe 直至 LLM 给出无 tool_call 的响应或达到 {@code MAX_ITERATIONS}。
 *
 * <h2>P1 阶段（最小可用）</h2>
 * 单次 LLM 调用；无 tool_call 时返回最终文本。
 *
 * <h2>P2 阶段（单 Reason-Act-Observe）</h2>
 * 遍历 LLM 返回的 {@code toolCalls}，每条派发给 {@link ToolExecutor}；每个 Tool 结果以
 * {@code Message.toolResult} 形式回喂 Session；继续循环。
 *
 * <h2>P3 阶段（多迭代 + 终止守卫 + 并发隔离）</h2>
 * {@code MAX_ITERATIONS} 守卫、空响应 fail-fast、ToolResult.session_iteration 跨表 join。
 *
 * <p>本实现**不**依赖 Spring AI 的 Agent 抽象（Constitution §III）；它**不**触发 Spring AI 的
 * 内部 tool 执行（Constitution §IV）—— Tool 调度完全由本类 + {@link ToolExecutor} 控制。
 */
@Component
public class ReActLoop {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    private final ProviderService provider;
    private final PromptBuilder promptBuilder;
    private final ToolExecutor toolExecutor;

    public ReActLoop(ProviderService provider, PromptBuilder promptBuilder, ToolExecutor toolExecutor) {
        this.provider = provider == null ? throwRequired("provider") : provider;
        this.promptBuilder = promptBuilder == null ? throwRequired("promptBuilder") : promptBuilder;
        this.toolExecutor = toolExecutor == null ? throwRequired("toolExecutor") : toolExecutor;
    }

    /**
     * 跑一次 ReAct 循环。
     *
     * @param profile     当前 Profile
     * @param session     当前 Session（消息就地追加）
     * @param userMessage 当前用户输入
     * @return 循环结果（{@link LoopResult#finalText} 即用户最终可见回复）
     */
    public LoopResult run(Profile profile, Session session, String userMessage) {
        if (profile == null) throw new IllegalArgumentException("profile must not be null");
        if (session == null) throw new IllegalArgumentException("session must not be null");
        if (userMessage == null) throw new IllegalArgumentException("userMessage must not be null");

        // C-LR-7 / Edge case 5：MAX_ITERATIONS == 0 → 静态"loop not configured"答复
        int maxIterations = profile.settings().maxIterations();
        if (maxIterations == 0) {
            log.info("react.completed session_id={} iterations=0 duration_ms=0 reason=max_iterations_zero",
                session.id());
            return new LoopResult("loop not configured", 0, true, profile.name(), session.id());
        }

        long startedAtNanos = System.nanoTime();
        AtomicInteger iterCounter = new AtomicInteger(0);

        // 1) 追加用户消息
        session.appendMessage(Message.user(userMessage));

        // 2) 进入循环
        int iterations = 0;
        LlmResponse lastResponse = null;
        boolean lastWasToolCall = false;
        String lastToolCallText = ""; // P3 fallback 当 terminatedAtMax=true 时取这个

        while (iterations < maxIterations) {
            int currentIter = iterCounter.incrementAndGet();

            // 2.1) 组装 prompt
            Prompt prompt = promptBuilder.build(profile, session);
            LlmRequest request = new LlmRequest(
                session.id(),
                profile.name(),
                profile.provider().model(),
                prompt.flatten(),
                prompt.toolSchemas(),
                temperatureFromOptions(profile),
                maxTokensFromOptions(profile)
            );

            // 2.2) 调 LLM（可能抛异常，向上传播；spec Edge case 1）
            LlmResponse response = provider.invoke(profile.provider().name(), request);
            lastResponse = response;
            iterations = currentIter;

            List<LlmResponse.ToolCall> toolCalls = response.toolCalls() == null ? List.of() : response.toolCalls();
            log.info("react.iteration session_id={} iteration={}/{} tool_calls={}",
                session.id(), currentIter, maxIterations, toolCalls.size());

            if (toolCalls.isEmpty()) {
                // Edge case 4：text + toolCalls 都空 → 合成 "model returned empty response"
                String text = response.textContent() == null ? "" : response.textContent();
                if (text.isEmpty()) {
                    text = "model returned empty response";
                }
                session.appendMessage(Message.assistantText(text));
                long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
                log.info("react.completed session_id={} iterations={} duration_ms={} final_tool_call=false",
                    session.id(), iterations, durationMs);
                return new LoopResult(text, iterations, false, profile.name(), session.id());
            }

            // 路径 (b)：有 tool_call
            lastWasToolCall = true;
            lastToolCallText = response.textContent() == null ? "" : response.textContent();
            // 追加 assistant(tool_calls) 消息
            List<ToolCall> converted = convertToolCalls(toolCalls);
            session.appendMessage(Message.assistantToolCalls(converted));

            // 派发每个 tool_call —— ToolExecutor 必须把所有失败转成 ToolResult（不抛 unchecked 异常）
            for (int i = 0; i < toolCalls.size(); i++) {
                LlmResponse.ToolCall tc = toolCalls.get(i);
                ToolResult tr = toolExecutor.invoke(tc.name(), argumentsMap(tc), profile);
                session.appendMessage(Message.toolResult(tc.callId(), tc.name(), tr));
            }

            // 继续下一轮迭代 —— 不增加 iteration 计数的逻辑已经在外层 while 完成
            // 在 P2/P3 阶段，每次"调 LLM"算一次迭代（FR-013）
        }

        // 达到 MAX_ITERATIONS 且最后一次响应是 tool_call → terminatedAtMax=true（C-LR-3 / Edge case 1）
        long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        if (lastWasToolCall) {
            String text = lastToolCallText.isEmpty()
                ? "loop terminated at max_iterations"
                : lastToolCallText;
            log.warn("react.completed session_id={} iterations={} duration_ms={} final_tool_call=true reason=max_iterations",
                session.id(), iterations, durationMs);
            return new LoopResult(text, iterations, true, profile.name(), session.id());
        }

        // 理论上不会到达 —— 一定以"无 tool_call"或"达到 max + last is tool_call"结束
        // 防御性 fallback：取 lastResponse.textContent（spec FR-013 (a) 路径的近似）
        String fallback = lastResponse != null && lastResponse.textContent() != null
            && !lastResponse.textContent().isEmpty()
            ? lastResponse.textContent()
            : "loop terminated unexpectedly";
        log.warn("react.unreachable session_id={} iterations={} duration_ms={} text=\"{}\"",
            session.id(), iterations, durationMs, fallback);
        return new LoopResult(fallback, iterations, true, profile.name(), session.id());
    }

    /** 内部 use：把 {@code provider.options().temperature} 转为 {@link Double}（可为 null）。 */
    private static Double temperatureFromOptions(Profile profile) {
        Object t = profile.provider().options().get("temperature");
        if (t instanceof Number n) return n.doubleValue();
        return null;
    }

    /** 内部 use：把 {@code provider.options().maxTokens} 转为 {@link Integer}（可为 null）。 */
    private static Integer maxTokensFromOptions(Profile profile) {
        Object mt = profile.provider().options().get("maxTokens");
        if (mt instanceof Number n) return n.intValue();
        return null;
    }

    /**
     * 把 {@link LlmResponse.ToolCall} 翻译为顶层 {@link ToolCall}。P2 阶段不解析 JSON arguments
     * 字符串 —— 用单 key {@code raw} 携带原始 JSON，让 Tool 自行解析（US-4 真实 Tool 会自行
     * 反序列化）；FakeToolExecutor 忽略 args 形态差异。
     */
    private static List<ToolCall> convertToolCalls(List<LlmResponse.ToolCall> tcs) {
        if (tcs == null || tcs.isEmpty()) return List.of();
        List<ToolCall> out = new java.util.ArrayList<>(tcs.size());
        for (LlmResponse.ToolCall tc : tcs) {
            out.add(new ToolCall(tc.callId(), tc.name(), argumentsMap(tc)));
        }
        return out;
    }

    private static Map<String, Object> argumentsMap(LlmResponse.ToolCall tc) {
        Map<String, Object> rawArgs = new HashMap<>();
        rawArgs.put("raw", tc.arguments() == null ? "" : tc.arguments());
        return rawArgs;
    }

    private static <T> T throwRequired(String name) {
        throw new IllegalArgumentException(name + " must not be null");
    }
}