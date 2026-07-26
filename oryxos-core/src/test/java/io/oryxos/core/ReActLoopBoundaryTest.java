package io.oryxos.core;

import io.oryxos.core.testing.FakeProviderService;
import io.oryxos.core.testing.FakeToolExecutor;
import io.oryxos.core.testing.InMemorySession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-2 P3 阶段：ReActLoop 边界守卫单测。
 *
 * <p>覆盖 [contracts/ReActLoop.md §3](../../../../../specs/002-react-loop/contracts/ReActLoop.md) 的边界条款：
 * <ul>
 *   <li>C-LR-7：{@code MAX_ITERATIONS == 0} → finalText = {@code "loop not configured"}，iterations=0</li>
 *   <li>Edge case 4（text + toolCalls 都空）→ finalText = {@code "model returned empty response"}</li>
 *   <li>C-LR-3（达到 max + 最后一次是 tool_call）→ terminatedAtMax=true，使用最后 assistant 的 content 作 finalText</li>
 * </ul>
 */
@DisplayName("ReActLoop 边界守卫（C-LR-3/7 + Edge case 4）")
class ReActLoopBoundaryTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000222");
    private static final Provider PROVIDER = new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of());
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-25T03:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private FakeProviderService provider;
    private FakeToolExecutor tools;
    private InMemorySession session;
    private ReActLoop loop;

    @BeforeEach
    void setUp() {
        provider = new FakeProviderService();
        tools = new FakeToolExecutor(Map.of(
            "http_get", ToolResult.ok(Map.of("v", 1))
        ));
        session = new InMemorySession(SESSION_ID, "weather-bot");
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, pb, tools);
    }

    private Profile profileWith(int maxIterations) {
        return new Profile(
            "weather-bot", PROVIDER, List.of("http_get"),
            List.of(), List.of(), List.of(),
            new Profile.Settings(maxIterations, 20),
            Map.of(),
            List.of()
        );
    }

    // === C-LR-7 ===

    /**
     * C-LR-7：MAX_ITERATIONS == 0 → "loop not configured"，零 LLM 调用、不污染 Session。
     * 设计选择：静态配置情况下**不**追加 user message（没有可对话的循环，加进 Session 是噪声）。
     */
    @Test
    @DisplayName("C-LR-7: MAX_ITERATIONS=0 → \"loop not configured\"，iterations=0，零 LLM 副作用")
    void maxIterationsZero_GivesStaticAnswer() {
        Profile profile = profileWith(0);
        LoopResult result = loop.run(profile, session, "go");

        assertThat(result.finalText()).isEqualTo("loop not configured");
        assertThat(result.iterations()).isZero();
        assertThat(result.terminatedAtMax()).isTrue(); // 退化为 true（C-LR-3 行为）
        assertThat(provider.invocationCount()).isZero();
        assertThat(tools.invocationCount()).isZero();
        // C-LR-7：max=0 是静态快返回分支，不写任何消息到 session（避免给用户"我收到了"假象）
        assertThat(session.size()).isZero();
    }

    // === Edge case 4 ===

    /**
     * Edge case 4：LLM 响应 text=null + toolCalls=空 → "model returned empty response"。
     */
    @Test
    @DisplayName("Edge case 4: text + toolCalls 都空 → \"model returned empty response\"")
    void emptyResponse_FailFast() {
        provider.enqueue(new LlmResponse(null, List.of(), null, "stop"));
        // 兜底：后续不应被调用
        provider.enqueue(new LlmResponse("never", List.of(), null, "stop"));

        Profile profile = profileWith(10);
        LoopResult result = loop.run(profile, session, "go");

        assertThat(result.finalText()).isEqualTo("model returned empty response");
        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.terminatedAtMax()).isFalse();
        assertThat(provider.invocationCount()).isEqualTo(1);
        assertThat(session.size()).isEqualTo(2);
        assertThat(session.messageAt(1).role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(session.messageAt(1).content()).isEqualTo("model returned empty response");
    }

    /**
     * 同样的 fail-fast：当 text=""（空串）+ toolCalls=空 也触发 empty response。
     */
    @Test
    @DisplayName("Edge case 4'：text=\"\" + toolCalls=空 → 空响应 fail-fast")
    void emptyStringResponse_FailFast() {
        provider.enqueue(new LlmResponse("", List.of(), null, "stop"));

        Profile profile = profileWith(10);
        LoopResult result = loop.run(profile, session, "go");

        assertThat(result.finalText()).isEqualTo("model returned empty response");
        assertThat(result.iterations()).isEqualTo(1);
    }

    // === C-LR-3 ===

    /**
     * C-LR-3：MAX_ITERATIONS=2，最后一次响应是 tool_call → terminatedAtMax=true，
     * finalText 取最后一个 assistant 的 content（"thinking aloud"）。
     *
     * <p>设计选择（与 LangChain 等同类一致）：在循环退出**前**仍执行最后一轮的 tool，
     * 给 LLM 最后一次拿到观察结果的机会 —— 是"被强制结束"而非"提前结束"。
     */
    @Test
    @DisplayName("C-LR-3: MAX_ITERATIONS=2 + 最后一次是 tool_call → terminatedAtMax=true")
    void maxIterations_2_ToolCallFinal() {
        // 第一次：tool_call → Tool 执行
        provider.enqueue(new LlmResponse(null,
            List.of(new LlmResponse.ToolCall("http_get", "{\"u\":\"a\"}", "c1")),
            null, "tool_calls"));
        // 第二次：又 tool_call → 仍执行 tool（拿最后一次观察），但不再迭代下一轮
        provider.enqueue(new LlmResponse("thinking aloud",
            List.of(new LlmResponse.ToolCall("http_get", "{\"u\":\"b\"}", "c2")),
            null, "tool_calls"));

        Profile profile = profileWith(2);
        LoopResult result = loop.run(profile, session, "go");

        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.terminatedAtMax()).isTrue();
        // assistant(tool_call) 的 content 是 "thinking aloud"，作为 finalText 回退
        assertThat(result.finalText()).isEqualTo("thinking aloud");
        // 两轮 tool_call 都执行了 —— 第 2 轮在循环条件检查前 run 完
        assertThat(tools.invocationCount()).isEqualTo(2);
    }

    /**
     * C-LR-3 变种：MAX_ITERATIONS=2，最后一次是 tool_call 但 content 为空 →
     * 回退到 "loop terminated at max_iterations"。
     */
    @Test
    @DisplayName("C-LR-3 fallback: 最后一次 tool_call 但 content 为空 → \"loop terminated at max_iterations\"")
    void maxIterations_ToolCallEmpty_Fallback() {
        provider.enqueue(new LlmResponse(null,
            List.of(new LlmResponse.ToolCall("http_get", "{\"u\":\"a\"}", "c1")),
            null, "tool_calls"));
        provider.enqueue(new LlmResponse(null,
            List.of(new LlmResponse.ToolCall("http_get", "{\"u\":\"b\"}", "c2")),
            null, "tool_calls"));

        Profile profile = profileWith(2);
        LoopResult result = loop.run(profile, session, "go");

        assertThat(result.terminatedAtMax()).isTrue();
        assertThat(result.finalText()).isEqualTo("loop terminated at max_iterations");
    }
}