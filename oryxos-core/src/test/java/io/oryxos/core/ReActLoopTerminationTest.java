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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-2 P3 阶段：MAX_ITERATIONS 终止守卫压力测试（SC-002）。
 *
 * <p>病态场景：LLM 永远返 tool_call，无 text —— loop MUST 在
 * {@code MAX_ITERATIONS} 终止（per FR-013 (b)）；恰好 K+0 次 LLM 调用（最后
 * 一次响应被丢弃 —— 与"产生 tool_call 但不调 Tool"语义对齐）；
 * 终止后不再发生 Tool 调用与 LLM 调用。
 *
 * <p>思路：每次 LLM 响应携带 1 个 tool_call，LLM Stub 队列预先装
 * {@code MAX_ITERATIONS} 条 tool_call 响应；循环最多跑满 {@code MAX_ITERATIONS}
 * 轮后退出，超出的 stub 调用不应发生。
 */
@DisplayName("ReActLoop 终止守卫压力测试（SC-002）")
class ReActLoopTerminationTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000333");
    private static final Provider PROVIDER = new Provider(
        "deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()
    );
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-25T03:00:00Z"), ZoneId.of("Asia/Shanghai")
    );

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

    private Profile profileWithMax(int maxIter) {
        return new Profile(
            "weather-bot", PROVIDER, List.of("http_get"),
            List.of(), List.of(), List.of(),
            new Profile.Settings(maxIter, 20),
            Map.of()
        );
    }

    private static LlmResponse toolCallResponse(String callId) {
        return new LlmResponse(null,
            List.of(new LlmResponse.ToolCall("http_get", "{\"u\":\"x\"}", callId)),
            null, "tool_calls");
    }

    /**
     * SC-002 主断言：MAX=5 时 LLM 连续返 5 次 tool_call → 循环恰好在第 5 次后退出，
     * 第 5 轮的 tool_call 也执行（仍走完整 Reason-Act 周期）。
     */
    @Test
    @DisplayName("SC-002：MAX=5 + 5 次连续 tool_call → iterations=5, terminatedAtMax=true, 第 5 个 tool 仍执行")
    void sc002_maxFiveAlwaysToolCall() {
        ProfileContext.set(new ProfileContext.Snapshot(
            "weather-bot", SESSION_ID, new AtomicInteger(0)
        ));
        try {
            // 预装 5 条 tool_call 响应（与 MAX=5 对齐）
            for (int i = 1; i <= 5; i++) {
                provider.enqueue(toolCallResponse("c-" + i));
            }
            // 故意没第 6 条 —— 若循环跑 6 次就应抛 IllegalStateException（测试替身自带防线）

            Profile profile = profileWithMax(5);
            LoopResult result = loop.run(profile, session, "go");

            assertThat(result.iterations()).isEqualTo(5);
            assertThat(result.terminatedAtMax()).isTrue();
            // 5 次 LLM 调用 + 5 次 Tool 执行（第 N 轮 tool 也跑，与 FR-013 (b) 行为一致）
            assertThat(provider.invocationCount()).isEqualTo(5);
            assertThat(tools.invocationCount()).isEqualTo(5);
            // Session：user + 5 个 (assistant(tool_call) + tool(result))  = 1 + 10 = 11
            assertThat(session.size()).isEqualTo(11);
            // 索引 10 应是最后一个 tool 结果
            assertThat(session.messageAt(10).role()).isEqualTo(Message.Role.TOOL);
        } finally {
            ProfileContext.clear();
        }
    }

    /**
     * SC-002 默认 MAX=10 验证：循环最多 10 次 LLM 终止。
     */
    @Test
    @DisplayName("SC-002：默认 MAX=10 + 10 次连续 tool_call → iterations=10, terminatedAtMax=true")
    void sc002_defaultMaxTen() {
        ProfileContext.set(new ProfileContext.Snapshot(
            "weather-bot", SESSION_ID, new AtomicInteger(0)
        ));
        try {
            for (int i = 1; i <= 10; i++) {
                provider.enqueue(toolCallResponse("c-" + i));
            }
            Profile profile = profileWithMax(10);
            LoopResult result = loop.run(profile, session, "go");

            assertThat(result.iterations()).isEqualTo(10);
            assertThat(result.terminatedAtMax()).isTrue();
            assertThat(provider.invocationCount()).isEqualTo(10);
            assertThat(tools.invocationCount()).isEqualTo(10);
        } finally {
            ProfileContext.clear();
        }
    }

    /**
     * SC-002 MAX=1 边界：单次 tool_call 即终止，迭代数 = 1。
     */
    @Test
    @DisplayName("SC-002：MAX=1 + 立刻 tool_call → iterations=1, terminatedAtMax=true")
    void sc002_maxOne() {
        ProfileContext.set(new ProfileContext.Snapshot(
            "weather-bot", SESSION_ID, new AtomicInteger(0)
        ));
        try {
            provider.enqueue(toolCallResponse("c-1"));
            Profile profile = profileWithMax(1);
            LoopResult result = loop.run(profile, session, "go");

            assertThat(result.iterations()).isEqualTo(1);
            assertThat(result.terminatedAtMax()).isTrue();
            assertThat(provider.invocationCount()).isEqualTo(1);
            assertThat(tools.invocationCount()).isEqualTo(1);
            assertThat(session.size()).isEqualTo(3); // user + assistant + tool
        } finally {
            ProfileContext.clear();
        }
    }

    /**
     * 终止前的迭代 中 LLM 偶尔给出 text 立即退出（混合模式）—— 不应被 max 守卫拦截。
     */
    @Test
    @DisplayName("混合模式：MAX=5 但 LLM 在第 3 次回 text → 第 3 次终止 (非 terminatedAtMax)")
    void mixedMode_textExitsBeforeMax() {
        ProfileContext.set(new ProfileContext.Snapshot(
            "weather-bot", SESSION_ID, new AtomicInteger(0)
        ));
        try {
            provider.enqueue(toolCallResponse("c-1"));
            provider.enqueue(toolCallResponse("c-2"));
            provider.enqueue(new LlmResponse("done", List.of(), null, "stop"));

            Profile profile = profileWithMax(5);
            LoopResult result = loop.run(profile, session, "go");

            assertThat(result.iterations()).isEqualTo(3);
            assertThat(result.terminatedAtMax()).isFalse();
            assertThat(provider.invocationCount()).isEqualTo(3);
            assertThat(tools.invocationCount()).isEqualTo(2);
            assertThat(result.finalText()).isEqualTo("done");
        } finally {
            ProfileContext.clear();
        }
    }

    /**
     * SC-002 + SC-004 协同：MAX_ITERATIONS 终止后，恰好 5 条 tool_invocations 记录被写入审计。
     * 使用 RecordingAuditWriter 验证。
     */
    @Test
    @DisplayName("SC-002 + SC-004：MAX=5 终止 → 恰好 5 条审计行；每次 invoke 写一次")
    void sc002_auditRowsMatchesInvocations() {
        List<String> auditToolNames = new ArrayList<>();
        var auditWriter = new ToolAuditWriter() {
            @Override
            public void record(ToolAuditWriter.ToolAuditData data) {
                auditToolNames.add(data.toolName());
            }
        };
        tools = new FakeToolExecutor(
            Map.of("http_get", ToolResult.ok(Map.of("v", 1))),
            auditWriter
        );
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, pb, tools);

        ProfileContext.set(new ProfileContext.Snapshot(
            "weather-bot", SESSION_ID, new AtomicInteger(0)
        ));
        try {
            for (int i = 1; i <= 5; i++) {
                provider.enqueue(toolCallResponse("c-" + i));
            }
            Profile profile = profileWithMax(5);
            loop.run(profile, session, "go");

            assertThat(auditToolNames).hasSize(5);
            assertThat(auditToolNames).allMatch(n -> n.equals("http_get"));
        } finally {
            ProfileContext.clear();
        }
    }
}