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
 * US-2 P2 阶段：quickstart §2 "每日天气" Demo 冒烟测试。
 *
 * <p>验证 [quickstart.md §2](../../../../../specs/002-react-loop/quickstart.md) 的端到端场景：
 * <ol>
 *   <li>AgentScheduler（US-2 简化为方法调用）到点触发 weather-bot</li>
 *   <li>用户消息（自动注入 "请给团队播报今天天气"）→ ReAct 循环</li>
 *   <li>第一次 LLM 返回 tool_call (http_get, url=weather.api)</li>
 *   <li>Tool 执行 → 返回天气数据（stub）</li>
 *   <li>第二次 LLM 返回 "今天北京晴，18°C"</li>
 *   <li>4 条 Session 消息 + 1 条 Tool 审计行</li>
 * </ol>
 *
 * <p>对齐 SC-001（ReAct 端到端可观测）+ SC-004（tool_invocations 落库 / Day-1 审计）的**最小可演示**链路。
 */
@DisplayName("Daily Weather Smoke（Quickstart §2）")
class DailyWeatherSmokeTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000111");
    private static final Provider PROVIDER = new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of());
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-25T08:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private FakeProviderService provider;
    private RecordingAuditWriter auditWriter;
    private FakeToolExecutor tools;
    private InMemorySession session;
    private ReActLoop loop;

    @BeforeEach
    void setUp() {
        provider = new FakeProviderService();
        auditWriter = new RecordingAuditWriter();

        // 模拟 http_get stub —— 真实实现由 US-4 提供
        tools = new FakeToolExecutor(
            Map.of("http_get", ToolResult.ok(Map.of(
                "temperature", 18, "city", "Beijing", "condition", "sunny"
            ))),
            auditWriter
        );
        session = new InMemorySession(SESSION_ID, "weather-bot");

        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, pb, tools);
    }

    private Profile dailyWeatherProfile(List<String> tools, int maxIterations) {
        return new Profile(
            "weather-bot", PROVIDER, tools,
            List.of(), List.of(), List.of(),
            new Profile.Settings(maxIterations, 20),
            Map.of()
        );
    }

    /**
     * quickstart §2 demo：AgentScheduler 触发 weather-bot。
     * 这里用直接 invoke 代替（AgentScheduler 在 US-5 实现）。
     */
    @Test
    @DisplayName("Daily Weather 端到端：tool_call → http_get → assistant 文本 → 4 消息 + 1 审计行")
    void dailyWeatherEndToEnd() {
        // 触发时刻：cron 消息充当"用户输入"（US-2 把 AgentScheduler 的 message 当作 user message）
        String trigger = "请给团队播报今天天气";

        // 设置 ProfileContext（C-TE-3：audit 写入需要 sessionId + iteration）
        ProfileContext.set(new ProfileContext.Snapshot(
            "weather-bot", SESSION_ID, new AtomicInteger(0)
        ));
        try {
            // LLM 第一次：识别意图 → 调 http_get
            provider.enqueue(new LlmResponse(null,
                List.of(new LlmResponse.ToolCall(
                    "http_get",
                    "{\"url\":\"https://api.weather.example/beijing\"}",
                    "call-weather-1"
                )),
                null, "tool_calls"));

            // LLM 第二次：得到 Tool 结果后输出自然语言
            provider.enqueue(new LlmResponse(
                "今天北京晴，18°C。",
                List.of(),
                new LlmResponse.TokenUsage(120, 35),
                "stop"
            ));

            Profile profile = dailyWeatherProfile(List.of("http_get"), 10);

            // invoke —— 替代 AgentScheduler.process(session, message)
            LoopResult result = loop.run(profile, session, trigger);

            // === SC-001：端到端可观测 ===
            assertThat(result.iterations()).isEqualTo(2);
            assertThat(result.terminatedAtMax()).isFalse();
            assertThat(result.finalText()).isEqualTo("今天北京晴，18°C。");

            // === Session 4 条消息：user → assistant(tool_call) → tool → assistant(text) ===
            assertThat(session.size()).isEqualTo(4);
            assertThat(session.messageAt(0).role()).isEqualTo(Message.Role.USER);
            assertThat(session.messageAt(1).role()).isEqualTo(Message.Role.ASSISTANT);
            assertThat(session.messageAt(1).toolCalls()).hasSize(1);
            assertThat(session.messageAt(2).role()).isEqualTo(Message.Role.TOOL);
            assertThat(session.messageAt(2).toolName()).isEqualTo("http_get");
            assertThat(session.messageAt(2).toolResult().success()).isTrue();
            assertThat(session.messageAt(3).role()).isEqualTo(Message.Role.ASSISTANT);
            assertThat(session.messageAt(3).content()).isEqualTo("今天北京晴，18°C。");

            // === SC-004：tool_invocations 落库 / Day-1 审计（用 RecordingAuditWriter 桩验）===
            assertThat(provider.invocationCount()).isEqualTo(2);
            assertThat(tools.invocationCount()).isEqualTo(1);
            assertThat(auditWriter.records).hasSize(1);
            ToolAuditWriter.ToolAuditData row = auditWriter.records.get(0);
            assertThat(row.sessionId()).isEqualTo(SESSION_ID);
            assertThat(row.profileName()).isEqualTo("weather-bot");
            assertThat(row.toolName()).isEqualTo("http_get");
            assertThat(row.success()).isTrue();
            assertThat(row.errorMessage()).isNull();
            assertThat(row.durationMs()).isGreaterThanOrEqualTo(0L);
            assertThat(row.arguments()).containsKey("raw");
        } finally {
            ProfileContext.clear();
        }
    }

    /**
     * Tool 失败路径（quickstart §2 验收场景 2）：http_get 抛错 → 循环继续 → 最终文本仍出。
     */
    @Test
    @DisplayName("Tool 失败（http_get 抛错）→ 循环继续 + assistant 文本 + 1 审计 success=false 行")
    void toolFailureEndToEnd() {
        ProfileContext.set(new ProfileContext.Snapshot(
            "weather-bot", SESSION_ID, new AtomicInteger(0)
        ));
        try {
            // 替换 executor 为返回 error 的 fake
            tools = new FakeToolExecutor(
                Map.of("http_get", ToolResult.error("network down")),
                auditWriter
            );
            PromptBuilder pb = new PromptBuilder(
                new MemoryInjector.NoopMemoryInjector(),
                new ToolSchemaProvider.NoopToolSchemaProvider(),
                new BootstrapLoader.NoopBootstrapLoader(),
                FIXED_CLOCK
            );
            loop = new ReActLoop(provider, pb, tools);

            provider.enqueue(new LlmResponse(null,
                List.of(new LlmResponse.ToolCall("http_get", "{\"url\":\"x\"}", "c1")),
                null, "tool_calls"));
            provider.enqueue(new LlmResponse(
                "暂无法获取天气，但按计划任务已触发。",
                List.of(),
                null, "stop"
            ));

            Profile profile = dailyWeatherProfile(List.of("http_get"), 10);
            LoopResult result = loop.run(profile, session, "go");

            assertThat(result.iterations()).isEqualTo(2);
            assertThat(result.finalText()).contains("暂无法获取天气");
            assertThat(session.size()).isEqualTo(4);
            // 审计行：success=false + errorMessage
            assertThat(auditWriter.records).hasSize(1);
            ToolAuditWriter.ToolAuditData row = auditWriter.records.get(0);
            assertThat(row.success()).isFalse();
            assertThat(row.errorMessage()).isEqualTo("network down");
            // Session 中的 ToolResult：也 success=false
            assertThat(session.messageAt(2).toolResult().success()).isFalse();
            assertThat(session.messageAt(2).toolResult().errorMessage()).isEqualTo("network down");
        } finally {
            ProfileContext.clear();
        }
    }

    /**
     * 简单 {@link ToolAuditWriter}：把所有审计行追加到 list，便于测试断言。
     */
    static final class RecordingAuditWriter implements ToolAuditWriter {
        final List<ToolAuditData> records = new ArrayList<>();

        @Override
        public void record(ToolAuditData data) {
            records.add(data);
        }
    }
}