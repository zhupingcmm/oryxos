package io.oryxos.core;

import io.oryxos.core.testing.FakeProviderService;
import io.oryxos.core.testing.FakeToolExecutor;
import io.oryxos.core.testing.InMemorySession;
import org.junit.jupiter.api.AfterEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-2 AG stage: {@link DefaultAgentService} unit tests -- 7 scenarios covering
 * the core contract clauses (C-AS-1..C-AS-5, C-AS-7).
 */
@DisplayName("DefaultAgentService unit tests (C-AS-*)")
class DefaultAgentServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-07-25T07:00:00Z"), ZoneId.of("Asia/Shanghai")
    );

    private InMemorySession session;
    private FakeProviderService provider;
    private ReActLoop loop;
    private DefaultAgentService service;

    @BeforeEach
    void setUp() {
        session = new InMemorySession(
            UUID.fromString("00000000-0000-0000-0000-000000000601"),
            "weather-bot"
        );
        provider = new FakeProviderService(List.of(
            new LlmResponse("hi", List.of(), null, "stop")
        ));
        PromptBuilder pb = new PromptBuilder(
            new MemoryInjector.NoopMemoryInjector(),
            new ToolSchemaProvider.NoopToolSchemaProvider(),
            new BootstrapLoader.NoopBootstrapLoader(),
            FIXED_CLOCK
        );
        loop = new ReActLoop(provider, pb, new FakeToolExecutor(Map.of()));
        service = new DefaultAgentService(
            InMemoryProfileRegistry.of(profile("weather-bot")), loop
        );
    }

    @AfterEach
    void cleanup() {
        ProfileContext.clear();
    }

    private Profile profile(String name) {
        return new Profile(
            name,
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            List.of(),
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of()
        );
    }

    // === C-AS-1 + happy path ===

    @Test
    @DisplayName("C-AS-1: happy path -- process succeeds, returns LoopResult")
    void happyPath_returnsLoopResult() {
        LoopResult result = service.process(session, "go");

        assertThat(result.iterations()).isEqualTo(1);
        assertThat(result.finalText()).isEqualTo("hi");
        assertThat(result.sessionId()).isEqualTo(session.id());
        assertThat(session.size()).isEqualTo(2);
    }

    // === C-AS-3 ===

    @Test
    @DisplayName("C-AS-3: unknown Profile -> IllegalArgumentException, no ProfileContext pollution")
    void unknownProfileThrows() {
        InMemorySession badSession = new InMemorySession(
            UUID.fromString("00000000-0000-0000-0000-000000000602"),
            "no-such-agent"
        );

        assertThatThrownBy(() -> service.process(badSession, "go"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown profile: 'no-such-agent'");

        assertThat(ProfileContext.current()).isEmpty();
    }

    // === C-AS-4 (Profile invariant, not DefaultAgentService) ===

    @Test
    @DisplayName("C-AS-4 (Profile invariant): blank provider.name rejected by Profile constructor")
    void profileInvariantRejectsBlankProviderName() {
        // C-AS-4 is enforced by Profile's compact constructor (not DefaultAgentService).
        // This test documents the invariant layer so future refactors don't accidentally
        // relax Profile-side validation.
        assertThatThrownBy(() -> new Profile(
            "broken-bot",
            new Provider("", "model-x", null, "X_API_KEY", Map.of()),
            List.of(),
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("provider.name must not be blank");
    }

    // === C-AS-2 / C-AS-5 ===

    @Test
    @DisplayName("C-AS-5: loop throws -> ProfileContext still cleared (finally holds)")
    void profileContextClearedOnException() {
        FakeProviderService emptyProvider = new FakeProviderService(List.of());
        ReActLoop throwingLoop = new ReActLoop(
            emptyProvider,
            new PromptBuilder(
                new MemoryInjector.NoopMemoryInjector(),
                new ToolSchemaProvider.NoopToolSchemaProvider(),
                new BootstrapLoader.NoopBootstrapLoader(),
                FIXED_CLOCK
            ),
            new FakeToolExecutor(Map.of())
        );
        DefaultAgentService svc = new DefaultAgentService(
            InMemoryProfileRegistry.of(profile("weather-bot")), throwingLoop
        );

        assertThatThrownBy(() -> svc.process(session, "go"))
            .isInstanceOf(IllegalStateException.class);

        assertThat(ProfileContext.current()).isEmpty();
    }

    @Test
    @DisplayName("C-AS-2: success path -> ProfileContext cleared (finally still runs)")
    void profileContextClearedOnSuccess() {
        assertThat(ProfileContext.current()).isEmpty();
        service.process(session, "go");
        assertThat(ProfileContext.current()).isEmpty();
    }

    @Test
    @DisplayName("C-AS-7: null session -> NPE")
    void nullSessionThrows() {
        assertThatThrownBy(() -> service.process(null, "go"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("session");
    }

    @Test
    @DisplayName("C-AS-7: null userMessage -> NPE")
    void nullUserMessageThrows() {
        assertThatThrownBy(() -> service.process(session, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("userMessage");
    }
}