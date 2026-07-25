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
 * US-2 P3 阶段：跨多迭代的 Tool 串联（spec US3 验收场景 1）。
 *
 * <p>两个 Tool（tool_a、tool_b），按需顺序使用，每条 user 消息需要 3 次 LLM 调用 +
 * 2 次 Tool 执行；最终消息序列：{@code user → assistant(tool_a) → tool_a →
 * assistant(tool_b) → tool_b → assistant(text)}。
 *
 * <p>关键约束：
 * <ul>
 *   <li>每个 Tool 至多执行一次（无双调用，满足 Constitution §IV）</li>
 *   <li>每次 Tool 调用都触发审计行（SC-004）</li>
 *   <li>消息序列严格满足数据模型（图状规则）</li>
 * </ul>
 */
@DisplayName("ReActLoop 多 Tool 串联（US3 验收场景 1）")
class ReActLoopMultiToolTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000444");
    private static final Provider PROVIDER = new Provider(
        "deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()
    );
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-25T05:00:00Z"), ZoneId.of("Asia/Shanghai")
    );

    private FakeProviderService provider;
    private FakeToolExecutor tools;
    private InMemorySession session;
    private ReActLoop loop;

    @BeforeEach
    void setUp() {
        provider = new FakeProviderService();
        tools = new FakeToolExecutor(Map.of(
            "http_get", ToolResult.ok(Map.of("temp", 20)),
            "read_file", ToolResult.ok(Map.of("contents", "demo"))
        ));
        session = new InMemorySession(SESSION_ID, "github-bot");
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, pb, tools);
    }

    private Profile profileWithMax(int maxIter) {
        return new Profile(
            "github-bot", PROVIDER, List.of("http_get", "read_file"),
            List.of(), List.of(), List.of(),
            new Profile.Settings(maxIter, 20),
            Map.of()
        );
    }

    /**
     * 主验收：3 次 LLM 调用 + 2 次 Tool 顺序 + 6 条 Session 消息。
     * 对应 spec US3 验收场景 1。
     */
    @Test
    @DisplayName("spec US3 验收场景 1：3 LLM + 2 Tool + 6 消息（每个 Tool 恰好调一次）")
    void specUs3_validationScenario1() {
        // Iter 1: tool_a (http_get)
        provider.enqueue(new LlmResponse(null,
            List.of(new LlmResponse.ToolCall("http_get", "{\"u\":\"x\"}", "c1")),
            null, "tool_calls"));
        // Iter 2: tool_b (read_file)
        provider.enqueue(new LlmResponse(null,
            List.of(new LlmResponse.ToolCall("read_file", "{\"path\":\"diff.md\"}", "c2")),
            null, "tool_calls"));
        // Iter 3: text
        provider.enqueue(new LlmResponse(
            "PR #123 diff 摘要：新增 50 行、删除 12 行。",
            List.of(),
            new LlmResponse.TokenUsage(80, 30),
            "stop"
        ));

        Profile profile = profileWithMax(10);
        LoopResult result = loop.run(profile, session, "give me PR digest");

        // SC-001：N tool → N+1 LLM
        assertThat(result.iterations()).isEqualTo(3);
        assertThat(provider.invocationCount()).isEqualTo(3);
        assertThat(tools.invocationCount()).isEqualTo(2);

        // Session 6 条按严格顺序
        assertThat(session.size()).isEqualTo(6);
        // [0] user
        assertThat(session.messageAt(0).role()).isEqualTo(Message.Role.USER);
        assertThat(session.messageAt(0).content()).isEqualTo("give me PR digest");
        // [1] assistant(tool_call) - tool_a
        assertThat(session.messageAt(1).role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(session.messageAt(1).toolCalls()).hasSize(1);
        assertThat(session.messageAt(1).toolCalls().get(0).name()).isEqualTo("http_get");
        // [2] tool result - tool_a
        assertThat(session.messageAt(2).role()).isEqualTo(Message.Role.TOOL);
        assertThat(session.messageAt(2).toolName()).isEqualTo("http_get");
        assertThat(session.messageAt(2).toolCallId()).isEqualTo("c1");
        // [3] assistant(tool_call) - tool_b
        assertThat(session.messageAt(3).role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(session.messageAt(3).toolCalls()).hasSize(1);
        assertThat(session.messageAt(3).toolCalls().get(0).name()).isEqualTo("read_file");
        // [4] tool result - tool_b
        assertThat(session.messageAt(4).role()).isEqualTo(Message.Role.TOOL);
        assertThat(session.messageAt(4).toolName()).isEqualTo("read_file");
        assertThat(session.messageAt(4).toolCallId()).isEqualTo("c2");
        // [5] assistant text
        assertThat(session.messageAt(5).role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(session.messageAt(5).content()).contains("PR #123");

        // 每个 Tool 恰好 1 次（无双调用）
        Map<String, Integer> invocationsByTool = new java.util.HashMap<>();
        for (FakeToolExecutor.Call call : tools.calls()) {
            invocationsByTool.merge(call.toolName(), 1, Integer::sum);
        }
        assertThat(invocationsByTool.get("http_get")).isEqualTo(1);
        assertThat(invocationsByTool.get("read_file")).isEqualTo(1);

        // finalText = 最后一次 assistant 的 text
        assertThat(result.finalText()).isEqualTo("PR #123 diff 摘要：新增 50 行、删除 12 行。");
    }

    /**
     * 中途一个 Tool 失败：失败作为 tool 消息回喂 → LLM 决定下一步。
     */
    @Test
    @DisplayName("中段 Tool 失败 → tool(error) 消息回喂 → LLM 后续选择其他 Tool 继续")
    void midChainToolFailure_continues() {
        // Iter 1: tool_a - 失败
        provider.enqueue(new LlmResponse(null,
            List.of(new LlmResponse.ToolCall("http_get", "{\"u\":\"x\"}", "c1")),
            null, "tool_calls"));
        // Iter 2: tool_b - 成功
        provider.enqueue(new LlmResponse(null,
            List.of(new LlmResponse.ToolCall("read_file", "{\"path\":\"diff.md\"}", "c2")),
            null, "tool_calls"));
        // Iter 3: text
        provider.enqueue(new LlmResponse(
            "我用 read_file 拿到了部分内容。",
            List.of(),
            null, "stop"
        ));

        // 替换 http_get 为错误返回（用 AllowAsError 路径）
        tools = new FakeToolExecutor(Map.of(
            "http_get", ToolResult.error("network timeout"),
            "read_file", ToolResult.ok(Map.of("contents", "partial"))
        ));
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, pb, tools);

        Profile profile = profileWithMax(10);
        LoopResult result = loop.run(profile, session, "go");

        assertThat(result.iterations()).isEqualTo(3);
        assertThat(result.terminatedAtMax()).isFalse();
        assertThat(tools.invocationCount()).isEqualTo(2);
        // session[2] 是 tool 错误消息
        assertThat(session.messageAt(2).toolResult().success()).isFalse();
        assertThat(session.messageAt(2).toolResult().errorMessage()).isEqualTo("network timeout");
        assertThat(result.finalText()).isEqualTo("我用 read_file 拿到了部分内容。");
    }

    /**
     * 一个 Tool 被调用多次（合法 —— 不同迭代）—— 这是宪法 §IV "double-invocation" 的精确反例：
     * 同一 Tool 不在**一次** user 消息中被调两次，但跨迭代可以。
     */
    @Test
    @DisplayName("跨迭代同一 Tool 被多次调用：不算 double-invoke（架构 §IV 限定为同一 user message）")
    void crossIterationSameToolRepeated() {
        // Iter 1: http_get
        provider.enqueue(new LlmResponse(null,
            List.of(new LlmResponse.ToolCall("http_get", "{\"u\":\"a\"}", "c1")),
            null, "tool_calls"));
        // Iter 2: 又是 http_get (同 Tool 再调用一次)
        provider.enqueue(new LlmResponse(null,
            List.of(new LlmResponse.ToolCall("http_get", "{\"u\":\"b\"}", "c2")),
            null, "tool_calls"));
        // Iter 3: text
        provider.enqueue(new LlmResponse("done", List.of(), null, "stop"));

        Profile profile = profileWithMax(10);
        LoopResult result = loop.run(profile, session, "go");

        assertThat(result.iterations()).isEqualTo(3);
        assertThat(tools.invocationCount()).isEqualTo(2);
        // 两次都是 http_get，但跨不同迭代 —— 这是合法的
        for (FakeToolExecutor.Call call : tools.calls()) {
            assertThat(call.toolName()).isEqualTo("http_get");
        }
        // Session 6 条
        assertThat(session.size()).isEqualTo(6);
        assertThat(session.messageAt(2).toolCallId()).isEqualTo("c1");
        assertThat(session.messageAt(4).toolCallId()).isEqualTo("c2");
    }
}