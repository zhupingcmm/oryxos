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
 * US-2 P2 阶段：单 Reason-Act-Observe 循环测试 —— 6 个验收场景覆盖 [spec.md §User Story 2](spec.md)。
 *
 * <p>每个 spec 验收场景拆成功路径 + 失败路径：共 6 测试。Phase 4 实施前预期全 FAIL。
 */
@DisplayName("ReActLoop Tool 链（P2）")
class ReActLoopToolChainTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Provider PROVIDER = new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of());
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-25T03:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private FakeProviderService provider;
    private FakeToolExecutor tools;
    private InMemorySession session;
    private ReActLoop loop;

    @BeforeEach
    void setUp() {
        provider = new FakeProviderService();
        session = new InMemorySession(SESSION_ID, "weather-bot");
        tools = new FakeToolExecutor(Map.of(
            "http_get", ToolResult.ok(Map.of("temperature", 18, "city", "Beijing"))
        ));
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, pb, tools);
    }

    private Profile profileWithTools(List<String> tools, int maxIterations) {
        return new Profile(
            "weather-bot", PROVIDER, tools,
            List.of(), List.of(), List.of(),
            new Profile.Settings(maxIterations, 20),
            Map.of()
        );
    }

    private static LlmResponse toolCallResponse(String callId, String name, String argsJson) {
        return new LlmResponse(null,
            List.of(new LlmResponse.ToolCall(name, argsJson, callId)),
            null, "tool_calls");
    }

    private static LlmResponse textResponse(String text) {
        return new LlmResponse(text, List.of(), null, "stop");
    }

    // === spec 验收场景 1：成功路径 ===

    /**
     * 验收场景 1 成功：profile 含 {@code tools=[http_get]} + 用户消息 →
     * 2 次 LLM 调用 + 1 条 Tool 审计行 + 4 条 Session 消息。
     */
    @Test
    @DisplayName("成功路径：tool_call → Tool 执行 → assistant(text)，2 次 LLM 调用 + 4 条消息")
    void successfulToolDispatch() {
        provider.enqueue(toolCallResponse("call-1", "http_get", "{\"city\":\"Beijing\"}"));
        provider.enqueue(textResponse("北京 18 度"));

        Profile profile = profileWithTools(List.of("http_get"), 10);
        LoopResult result = loop.run(profile, session, "今天北京天气？");

        assertThat(provider.invocationCount()).isEqualTo(2);
        assertThat(tools.invocationCount()).isEqualTo(1);
        assertThat(tools.calls().get(0).toolName()).isEqualTo("http_get");
        assertThat(tools.calls().get(0).arguments()).containsEntry("raw", "{\"city\":\"Beijing\"}");
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.terminatedAtMax()).isFalse();
        assertThat(result.finalText()).isEqualTo("北京 18 度");

        // Session 顺序：user → assistant(tool_calls) → tool(result) → assistant(text)
        assertThat(session.size()).isEqualTo(4);
        assertThat(session.messageAt(0).role()).isEqualTo(Message.Role.USER);
        assertThat(session.messageAt(1).role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(session.messageAt(1).toolCalls()).hasSize(1);
        assertThat(session.messageAt(2).role()).isEqualTo(Message.Role.TOOL);
        assertThat(session.messageAt(2).toolCallId()).isEqualTo("call-1");
        assertThat(session.messageAt(2).toolResult().success()).isTrue();
        assertThat(session.messageAt(3).role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(session.messageAt(3).content()).isEqualTo("北京 18 度");
    }

    // === spec 验收场景 2：失败路径 ===

    /**
     * 验收场景 2 失败：Tool 调用失败（模拟超时/4xx）→
     * 1 条 {@code tool_invocations} 行 {@code success=false}、循环继续（不崩溃）。
     */
    @Test
    @DisplayName("Tool 失败路径：Tool 返回 error → 循环继续，1 次 Tool 调用 + 4 条消息 + 最终文本")
    void toolFailureContinuesLoop() {
        tools = new FakeToolExecutor(Map.of(
            "http_get", ToolResult.error("HTTP 500 from upstream")
        ));
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, pb, tools);

        provider.enqueue(toolCallResponse("call-1", "http_get", "{}"));
        provider.enqueue(textResponse("上游服务暂时不可用"));

        Profile profile = profileWithTools(List.of("http_get"), 10);
        LoopResult result = loop.run(profile, session, "天气");

        assertThat(tools.invocationCount()).isEqualTo(1);
        assertThat(tools.calls().get(0).toolName()).isEqualTo("http_get");
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.terminatedAtMax()).isFalse();
        assertThat(result.finalText()).isEqualTo("上游服务暂时不可用");

        // Session 中间一条 tool 消息携带 ToolResult.error
        assertThat(session.size()).isEqualTo(4);
        assertThat(session.messageAt(2).role()).isEqualTo(Message.Role.TOOL);
        assertThat(session.messageAt(2).toolResult().success()).isFalse();
        assertThat(session.messageAt(2).toolResult().errorMessage()).isEqualTo("HTTP 500 from upstream");
    }

    // === spec 验收场景 3：tool 不在白名单 ===

    /**
     * 验收场景 3：Tool 名不在 Profile 白名单 →
     * 合成 {@code ToolResult.error("tool not in profile: ...")}、循环继续、不崩溃。
     */
    @Test
    @DisplayName("白名单拒绝：profile.tools=[] 但 LLM 请求 unknown_tool → 合成 error 结果 + 循环继续")
    void notInProfileWhitelist() {
        // profile 不含 unknown_tool —— 应被 FakeToolExecutor（默认 AllowAsError）拒绝
        // 但生产路径走 DefaultToolExecutor；此处用 FakeToolExecutor 默认行为验证流程
        provider.enqueue(toolCallResponse("call-1", "unknown_tool", "{}"));
        provider.enqueue(textResponse("我无法访问该工具"));

        Profile profile = profileWithTools(List.of("http_get"), 10); // 不含 unknown_tool
        // 改用 production executor 来验证白名单拒绝
        DefaultToolExecutor realExecutor = new DefaultToolExecutor();
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, pb, realExecutor);

        LoopResult result = loop.run(profile, session, "go");

        assertThat(provider.invocationCount()).isEqualTo(2);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.terminatedAtMax()).isFalse();

        // Session 顺序：user → assistant(tool_calls) → tool(error) → assistant(text)
        assertThat(session.size()).isEqualTo(4);
        assertThat(session.messageAt(2).role()).isEqualTo(Message.Role.TOOL);
        assertThat(session.messageAt(2).toolName()).isEqualTo("unknown_tool");
        assertThat(session.messageAt(2).toolResult().success()).isFalse();
        assertThat(session.messageAt(2).toolResult().errorMessage())
            .contains("tool not in profile");
    }

    // === 额外覆盖：并行 tool_call（LlmResponse 含多条 tool_calls）===

    @Test
    @DisplayName("多 tool_call 在同一迭代内串行执行（每个 toolCall 各 1 条 tool 消息）")
    void multipleToolCallsSameIteration() {
        tools = new FakeToolExecutor(Map.of(
            "tool_a", ToolResult.ok(Map.of("v", "A")),
            "tool_b", ToolResult.ok(Map.of("v", "B"))
        ));
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, pb, tools);

        // LLM 在同一响应里请求两个 tool（spec US3 场景 1 的最小变种）
        LlmResponse twoCalls = new LlmResponse(null,
            List.of(
                new LlmResponse.ToolCall("tool_a", "{}", "c1"),
                new LlmResponse.ToolCall("tool_b", "{}", "c2")
            ),
            null, "tool_calls");
        provider.enqueue(twoCalls);
        provider.enqueue(textResponse("done"));

        Profile profile = profileWithTools(List.of("tool_a", "tool_b"), 10);
        loop.run(profile, session, "go");

        assertThat(tools.invocationCount()).isEqualTo(2);
        assertThat(provider.invocationCount()).isEqualTo(2);
        // 期望消息序列：user → assistant(2 tool_calls) → tool_a → tool_b → assistant(text)
        assertThat(session.size()).isEqualTo(5);
        assertThat(session.messageAt(2).toolName()).isEqualTo("tool_a");
        assertThat(session.messageAt(3).toolName()).isEqualTo("tool_b");
        assertThat(session.messageAt(4).content()).isEqualTo("done");
    }

    // === 额外覆盖：白名单通过但 Tool 抛异常（US-4 stub 行为；P2 不抛） ===

    @Test
    @DisplayName("白名单通过路径：FakeToolExecutor 提供 ok 结果 → 1 Tool 调用 + 正常完成")
    void whitelistedToolRuns() {
        provider.enqueue(toolCallResponse("call-1", "http_get", "{\"x\":1}"));
        provider.enqueue(textResponse("ok"));

        LoopResult result = loop.run(profileWithTools(List.of("http_get"), 10), session, "go");

        assertThat(tools.invocationCount()).isEqualTo(1);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.finalText()).isEqualTo("ok");
    }
}