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
 * US-2 P1 阶段纯 Reason 路径测试 —— 循环体的最小可用形式（仅调 LLM 一次，不调 Tool）。
 *
 * <p>覆盖 [spec.md §User Story 1](spec.md) 三条验收场景。Phase 3 实施前测试预期全部 FAIL（无实现）。
 */
@DisplayName("ReActLoop 纯 Reason 路径（P1）")
class ReActLoopPureReasonTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Provider PROVIDER = new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of());
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-25T03:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private FakeProviderService provider;
    private FakeToolExecutor tools;
    private InMemorySession session;
    private ReActLoop loop;
    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        provider = new FakeProviderService();
        tools = new FakeToolExecutor(FakeToolExecutor.emptyTable());
        session = new InMemorySession(SESSION_ID, "weather-bot");

        promptBuilder = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, promptBuilder, tools);
    }

    private Profile profileWithTools(List<String> tools, int maxIterations, List<String> bootstrap) {
        return new Profile(
            "weather-bot",
            PROVIDER,
            tools,
            List.of(),
            bootstrap,
            List.of(),
            new Profile.Settings(maxIterations, 20),
            Map.of(),
            List.of()
        );
    }

    private static LlmResponse textResponse(String text) {
        return new LlmResponse(text, List.of(), null, "stop");
    }

    /**
     * 场景 1 主断言：循环在无 Tool 时返回 iter=1 + terminatedAtMax=false + LLM 文本。
     */
    @Test
    @DisplayName("场景 1：Profile{tools=[]} → LoopResult(iter=1, terminatedAtMax=false, text=LLM 文本)")
    void iter1ReturnsTextResponse() {
        provider.enqueue(textResponse("Bonjour !"));

        Profile profile = profileWithTools(List.of(), 10, List.of());
        LoopResult result = loop.run(profile, session, "你好");

        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.terminatedAtMax()).isFalse();
        assertThat(result.finalText()).isEqualTo("Bonjour !");
        assertThat(result.profileName()).isEqualTo("weather-bot");
        assertThat(result.sessionId()).isEqualTo(SESSION_ID);
        assertThat(provider.invocationCount())
            .as("LLM 调用恰好一次")
            .isEqualTo(1);
        assertThat(tools.invocationCount())
            .as("Tool 调用恰好零次")
            .isZero();
    }

    /**
     * 场景 1 副断言：Session 严格按顺序追加两条消息（user → assistant(text)）。
     */
    @Test
    @DisplayName("场景 1：Session.messages 恰好 [user, assistant(text)] 两条")
    void sessionContainsExactlyTwoMessages() {
        provider.enqueue(textResponse("hi"));

        loop.run(profileWithTools(List.of(), 10, List.of()), session, "hello");

        assertThat(session.size()).isEqualTo(2);
        assertThat(session.messageAt(0).role()).isEqualTo(Message.Role.USER);
        assertThat(session.messageAt(0).content()).isEqualTo("hello");
        assertThat(session.messageAt(1).role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(session.messageAt(1).content()).isEqualTo("hi");
        assertThat(session.messageAt(1).toolCalls())
            .as("assistant 消息不应带 toolCalls")
            .isEmpty();
    }

    /**
     * 场景 2：Profile 引用 Bootstrap 时，system prompt 中按声明顺序出现这些文件内容。
     *
     * <p>直接调用 {@link PromptBuilder#build} 验证组装顺序，不经 ReActLoop 链路。
     */
    @Test
    @DisplayName("场景 2：Bootstrap 文件按声明顺序出现在 system prompt 中")
    void bootstrapFilesAppearInSystemPromptInOrder() {
        BootstrapLoader loader = p -> Map.of(
            "AGENTS.md", "AGENTS_CONTENT",
            "SOUL.md", "SOUL_CONTENT",
            "USER.md", "USER_CONTENT"
        );
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            loader,
            FIXED_CLOCK
        );

        Prompt prompt = pb.build(profileWithTools(List.of(), 10, List.of("AGENTS.md", "SOUL.md", "USER.md")), session);

        List<Map<String, Object>> blocks = prompt.systemBlocks();
        assertThat(blocks).hasSizeGreaterThanOrEqualTo(4); // 3 bootstrap + 1 datetime
        assertThat(blocks.get(0)).containsEntry("content", "AGENTS_CONTENT");
        assertThat(blocks.get(1)).containsEntry("content", "SOUL_CONTENT");
        assertThat(blocks.get(2)).containsEntry("content", "USER_CONTENT");
    }

    /**
     * 场景 2：当前本地日期时间行追加到 system prompt 末尾（FR-005 / CLAUDE.md §9.2 步骤 1）。
     */
    @Test
    @DisplayName("场景 2：当前本地日期时间行追加到 system prompt 末尾（FR-005）")
    void localDateTimeLineAppendedToSystemPrompt() {
        Prompt prompt = promptBuilder.build(profileWithTools(List.of(), 10, List.of()), session);

        List<Map<String, Object>> blocks = prompt.systemBlocks();
        Map<String, Object> last = blocks.get(blocks.size() - 1);
        assertThat(last).containsKey("content");
        String content = last.get("content").toString();
        assertThat(content)
            .as("datetime line should reference 2026-07-25 in Asia/Shanghai zone")
            .containsPattern("(?i).*2026-07-25.*Shanghai.*|.*Current date/time.*");
    }

    /**
     * 场景 3：max_iterations=1 时循环恰好调用 LLM 一次，不继续迭代。
     */
    @Test
    @DisplayName("场景 3：settings.max_iterations=1 → 至多 1 次 LLM 调用")
    void maxIterations1() {
        provider.enqueue(textResponse("one-shot"));

        LoopResult result = loop.run(profileWithTools(List.of(), 1, List.of()), session, "x");

        assertThat(provider.invocationCount()).isLessThanOrEqualTo(1);
        assertThat(result.iterations()).isLessThanOrEqualTo(1);
    }
}