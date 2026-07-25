package io.oryxos.core;

import io.oryxos.core.config.ToolExecutorConfig;
import io.oryxos.core.testing.FakeProviderService;
import io.oryxos.core.testing.FakeToolExecutor;
import io.oryxos.core.testing.InMemorySession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-2/AG stage: Spring-slice end-to-end test for {@link AgentService}.
 *
 * <p>Equivalent to the T060 spec in <code>tasks.md</code>, simplified so it stays inside
 * <code>oryxos-core</code>:
 * <ul>
 *   <li>Wires the production {@link DefaultAgentService} + {@link ReActLoop} via a real Spring
 *       {@link AnnotationConfigApplicationContext} (no Spring Boot, no WireMock)</li>
 *   <li>Uses {@link FakeProviderService} + {@link FakeToolExecutor} so the LLM and Tool
 *       plumbing is deterministic</li>
 *   <li>Asserts the full daily-weather flow (US2 success scenario):
 *       2 LLM calls + 1 Tool call + 4 Session messages</li>
 * </ul>
 *
 * <p>The "real" WireMock-backed IT moves to US-5 once {@code oryxos-provider} + Spring Boot
 * datasource land in {@code oryxos-storage}. See {@code evidence/T057-FilesystemProfileRegistry-deferred.md}
 * for the rationale.
 */
@DisplayName("AgentService Spring slice (T060)")
class AgentServiceSpringSliceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-25T07:00:00Z"), ZoneId.of("Asia/Shanghai")
    );

    private AnnotationConfigApplicationContext ctx;
    private AgentService agentService;

    @BeforeEach
    void bootContext() {
        ctx = new AnnotationConfigApplicationContext();
        // Register the real config + a test profile + test fake beans.
        ctx.register(ToolExecutorConfig.class);
        ctx.register(FakeBeansConfig.class);
        ctx.refresh();
        agentService = ctx.getBean(AgentService.class);
        assertThat(agentService).isInstanceOf(DefaultAgentService.class);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) ctx.close();
        ProfileContext.clear();
    }

    // === T060 happy path: DailyWeather end-to-end ===

    @Test
    @DisplayName("T060: full DailyWeather flow through Spring bean -> 2 LLM + 1 tool + 4 messages")
    void dailyWeatherEndToEnd() {
        // Session for a profile that allows `http_get`.
        InMemorySession session = new InMemorySession(
            UUID.fromString("00000000-0000-0000-0000-0000000000A1"),
            "weather-bot"
        );
        // Lookup via the InMemoryProfileRegistry bean.
        agentService.process(session, "北京今天天气怎么样");

        assertThat(session.size())
            .as("user + assistant(tool_call) + tool_result + assistant(text) = 4 messages")
            .isEqualTo(4);

        FakeProviderService fakeProvider =
            ctx.getBean(FakeProviderService.class);
        assertThat(fakeProvider.invocationCount())
            .as("2 LLM calls (act -> after tool -> answer)")
            .isEqualTo(2);

        FakeToolExecutor fakeTool = ctx.getBean(FakeToolExecutor.class);
        assertThat(fakeTool.invocationCount())
            .as("1 tool invocation (http_get)")
            .isEqualTo(1);
        assertThat(fakeTool.calls())
            .extracting(FakeToolExecutor.Call::toolName)
            .containsExactly("http_get");
    }

    // === T060 + Spring bean wiring assertions ===

    @Test
    @DisplayName("T060: ProfileRegistry bean is the InMemoryProfileRegistry we registered")
    void profileRegistryBeanIsTheInMemoryImpl() {
        ProfileRegistry reg = ctx.getBean(ProfileRegistry.class);
        assertThat(reg).isInstanceOf(InMemoryProfileRegistry.class);
        assertThat(reg.names()).containsExactly("weather-bot");
        assertThat(reg.find("weather-bot")).isPresent();
    }

    @Test
    @DisplayName("T060: ToolExecutor bean -- @Primary fake wins for the slice, real bean still registered")
    void toolExecutorBeanWiring() {
        // By type (highest priority): @Primary fake wins for ReActLoop injection.
        ToolExecutor primary = ctx.getBean(ToolExecutor.class);
        assertThat(primary).isInstanceOf(FakeToolExecutor.class);
        // Both beans still exist by name: production bean keeps its registration.
        assertThat(ctx.getBeanNamesForType(ToolExecutor.class))
            .as("DefaultToolExecutor (production) and FakeToolExecutor (test slice) "
                + "are both registered; @Primary decides which ReActLoop receives")
            .containsExactlyInAnyOrder("toolExecutor", "fakeTool");
    }

    @Test
    @DisplayName("T060: unknown profile -> IllegalArgumentException propagates from Spring bean")
    void unknownProfileThrowsViaSpringBean() {
        InMemorySession bad = new InMemorySession(UUID.randomUUID(), "no-such-agent");
        assertThatThrownBy(() -> agentService.process(bad, "hi"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown profile: 'no-such-agent'");
    }

    // === Test slice bean configuration ===

    /**
     * Test-scope Spring config: registers the fakes + the weather-bot Profile so the
     * {@link AgentService} bean can be wired end-to-end.
     */
    @org.springframework.context.annotation.Configuration
    static class FakeBeansConfig {

        @org.springframework.context.annotation.Bean
        FakeProviderService fakeProvider() {
            return new FakeProviderService(List.of(
                new LlmResponse(null, List.of(
                    // LlmResponse.ToolCall signature is (name, arguments, callId)
                    new LlmResponse.ToolCall("http_get",
                        "{\"url\": \"https://wttr.in/Beijing?format=3\"}",
                        "tc-1")
                ), null, "tool_calls"),
                new LlmResponse("北京 24°C, 晴", List.of(), null, "stop")
            ));
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        FakeToolExecutor fakeTool() {
            // http_get returns a successful weather payload. @Primary so the test fake wins
            // against ToolExecutorConfig's DefaultToolExecutor bean for this slice.
            return new FakeToolExecutor(Map.of(
                "http_get", ToolResult.ok(Map.of(
                    "body", "Beijing: +24C, clear sky"
                ))
            ));
        }

        @org.springframework.context.annotation.Bean
        ProfileRegistry profileRegistry() {
            Profile weatherBot = new Profile(
                "weather-bot",
                new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
                List.of("http_get"),     // tools allowed
                List.of(), List.of(), List.of(),
                new Profile.Settings(10, 20),
                Map.of()
            );
            return InMemoryProfileRegistry.of(weatherBot);
        }

        @org.springframework.context.annotation.Bean
        PromptBuilder promptBuilder(MemoryInjector mem, ToolSchemaProvider schemas, BootstrapLoader boot) {
            return new PromptBuilder(mem, schemas, boot, FIXED_CLOCK);
        }

        @org.springframework.context.annotation.Bean
        MemoryInjector memoryInjector() {
            return new MemoryInjector.NoopMemoryInjector();
        }

        @org.springframework.context.annotation.Bean
        ToolSchemaProvider toolSchemaProvider() {
            return new ToolSchemaProvider.NoopToolSchemaProvider();
        }

        @org.springframework.context.annotation.Bean
        BootstrapLoader bootstrapLoader() {
            return new BootstrapLoader.NoopBootstrapLoader();
        }

        @org.springframework.context.annotation.Bean
        ReActLoop reactLoop(ProviderService provider, PromptBuilder pb, ToolExecutor te) {
            return new ReActLoop(provider, pb, te);
        }

        @org.springframework.context.annotation.Bean
        AgentService agentService(ProfileRegistry reg, ReActLoop loop) {
            return new DefaultAgentService(reg, loop);
        }
    }
}