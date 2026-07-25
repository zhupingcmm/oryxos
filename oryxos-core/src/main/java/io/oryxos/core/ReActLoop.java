package io.oryxos.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ReAct 循环主控 —— Reason → Act → Observe 直至 LLM 给出无 tool_call 的响应或达到 {@code MAX_ITERATIONS}。
 *
 * <h2>P1 阶段能力</h2>
 * <ul>
 *   <li>追加用户消息到 Session</li>
 *   <li>组装 Prompt（四段式）</li>
 *   <li>调 LLM 一次</li>
 *   <li>无 tool_call → 返回最终文本</li>
 * </ul>
 *
 * <h2>P2 阶段扩（P2 起作用）</h2>
 * <ul>
 *   <li>遍历 LLM 返回的 {@code toolCalls}，每条派发给 {@link ToolExecutor}</li>
 *   <li>每个 Tool 结果以 {@code Message.toolResult} 形式回喂 Session</li>
 * </ul>
 *
 * <h2>P3 阶段扩</h2>
 * <ul>
 *   <li>{@code MAX_ITERATIONS} 守卫（避免病态 LLM 死循环）</li>
 *   <li>空响应 fail-fast 边界</li>
 * </ul>
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
     * <p>P1 实现：单次 LLM 调用；无 tool_call 时返回最终文本。
     * P2 起支持多轮 + Tool 派发；此处保留外层 while 框架与日志钩子。
     *
     * @param profile 当前 Profile（必非空；AgentService 已保证名称合法）
     * @param session 当前 Session（必非空；消息就地追加）
     * @param userMessage 当前用户输入
     * @return 循环结果（{@link LoopResult#finalText} 即用户最终可见回复）
     */
    public LoopResult run(Profile profile, Session session, String userMessage) {
        if (profile == null) throw new IllegalArgumentException("profile must not be null");
        if (session == null) throw new IllegalArgumentException("session must not be null");
        if (userMessage == null) {
            throw new IllegalArgumentException("userMessage must not be null");
        }

        // Edge case 5: MAX_ITERATIONS == 0 → 静态"loop not configured"答复
        int maxIterations = profile.settings().maxIterations();
        if (maxIterations == 0) {
            String text = "loop not configured";
            LoopResult result = new LoopResult(text, 0, false, profile.name(), session.id());
            log.info("react.completed session_id={} iterations=0 duration_ms=0 final_tool_call=false terminatedAtMax=false reason=max_iterations_zero",
                session.id());
            return result;
        }

        long startedAtNanos = System.nanoTime();
        AtomicInteger iterCounter = new AtomicInteger(0);

        // 1) 追加用户消息
        session.appendMessage(Message.user(userMessage));

        // 2) 进入循环
        int iterations = 0;
        LlmResponse lastResponse = null;
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

            log.info("react.iteration session_id={} iteration={}/{} tool_calls={}",
                session.id(), currentIter, maxIterations,
                response.toolCalls() == null ? 0 : response.toolCalls().size());

            // 2.3) 检查 tool_calls
            List<LlmResponse.ToolCall> toolCalls = response.toolCalls() == null ? List.of() : response.toolCalls();
            if (toolCalls.isEmpty()) {
                // 路径 (a)：无 tool_call → 最终文本
                String text = response.textContent() == null ? "" : response.textContent();
                session.appendMessage(Message.assistantText(text));
                long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
                log.info("react.completed session_id={} iterations={} duration_ms={} final_tool_call=false",
                    session.id(), iterations, durationMs);
                return new LoopResult(text, iterations, false, profile.name(), session.id());
            }

            // 路径 (b)：有 tool_call —— P2 完整支持；P1 阶段保证不会触发
            // 防御性 fallback：把 tool_calls 翻译为顶层 ToolCall 并以 assistant message 入 Session，
            // 返回 LLM textContent（可能为空）—— 避免无限循环
            List<ToolCall> converted = convertToolCalls(toolCalls);
            session.appendMessage(Message.assistantToolCalls(converted));
            String fallback = response.textContent() == null ? "" : response.textContent();
            long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            log.warn("react.completed session_id={} iterations={} duration_ms={} final_tool_call=true reason=p1_fallback tool_calls={}",
                session.id(), iterations, durationMs, converted.size());
            return new LoopResult(fallback, iterations, false, profile.name(), session.id());
        }

        // 理论上不会到达：while 循环在 maxIterations 之前一定以 tool_calls 空终止
        // 若到达此处，是迭代计数异常 —— 返回空 fallback
        long durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
        log.warn("react.unreachable session_id={} iterations={} duration_ms={}",
            session.id(), iterations, durationMs);
        String text = lastResponse != null && lastResponse.textContent() != null
            ? lastResponse.textContent() : "";
        return new LoopResult(text, iterations, true, profile.name(), session.id());
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

    private static <T> T throwRequired(String name) {
        throw new IllegalArgumentException(name + " must not be null");
    }

    /**
     * 内部 use：把 {@link LlmResponse.ToolCall}（Provider 形态，arguments 是 JSON 字符串）
     * 翻译为顶层 {@link ToolCall}（Session 形态，arguments 是 Map）。
     * P2 起将由 {@link ToolExecutor} 派发逻辑使用；P1 仅在 fallback 路径调用。
     */
    private static List<ToolCall> convertToolCalls(List<LlmResponse.ToolCall> tcs) {
        if (tcs == null || tcs.isEmpty()) return List.of();
        List<ToolCall> out = new ArrayList<>(tcs.size());
        for (LlmResponse.ToolCall tc : tcs) {
            // P1 不解析 JSON arguments 字符串 —— 完整解析留 US-4（Tool dispatch 阶段）。
            // 这里用单 key "raw" 携带原始 JSON，保持 Map 形态约束不破坏。
            Map<String, Object> rawArgs = new HashMap<>();
            rawArgs.put("raw", tc.arguments() == null ? "" : tc.arguments());
            out.add(new ToolCall(tc.callId(), tc.name(), rawArgs));
        }
        return out;
    }
}