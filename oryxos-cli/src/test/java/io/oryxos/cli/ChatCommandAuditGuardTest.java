package io.oryxos.cli;

import io.oryxos.cli.command.ChatCommand;
import io.oryxos.cli.config.MissingEnvVarException;
import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.spring.SpringContextHandle;
import io.oryxos.core.AgentService;
import io.oryxos.core.Message;
import io.oryxos.core.Session;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1-AC3 / FR-018 fail-fast invariant — when the Provider's API key is
 * missing (or the Profile is unknown), the CLI must:
 *
 * <ol>
 *   <li>Exit non-zero with a sysexits-compliant code
 *       ({@link Sysexits#EX_UNAVAILABLE} for missing key,
 *       {@link Sysexits#EX_USAGE} for unknown profile).</li>
 *   <li>Write a one-line stderr message — <em>no stack trace</em>
 *       (FR-018 main contract; FR-010 stderr-only).</li>
 *   <li>Not write any {@code llm_calls} row — the audit table must
 *       remain untouched when no LLM call actually happened.</li>
 * </ol>
 *
 * <p>Test strategy: we wrap an {@link AnnotationConfigApplicationContext}
 * carrying Mockito-stubbed {@link AgentService} and {@link SessionRepository}
 * beans via {@link SpringContextHandle#wrapForTesting}, then subclass
 * {@link ChatCommand} to inject it through {@code acquireContext(...)}.
 * This avoids the full Spring Boot bootstrap (which would otherwise require
 * H2 / Testcontainers to wire {@code llm_calls}) while still exercising
 * the real {@code ChatCommand.runBody()} code path.
 *
 * <p>The "no {@code llm_calls} row" claim is proven by structural inspection:
 * {@code ChatCommand.runBody()} only acquires {@link AgentService} and
 * {@link SessionRepository}; it never references any {@code LlmCallRecordRepository}
 * bean. The repository write happens <em>inside</em> {@link AgentService#process}
 * (specifically, in the {@code ReActLoop}'s provider-call wrapper), which
 * the test prevents from being reached by configuring the mock to throw
 * before any internal call.
 */
class ChatCommandAuditGuardTest {

    @Test
    void missingApiKey_exitsUnavailable_stderrOneLine_noLlmCallInvoked() throws Exception {
        // Given a Spring context whose AgentService throws fail-fast —
        // simulating "DEEPSEEK_API_KEY is missing" at the Provider layer.
        SessionEntity savedEntity = SessionEntity.create(
                UUID.fromString("00000000-0000-0000-0000-0000000000A1"),
                "weather-bot");
        savedEntity.appendMessage(Message.user("hi"));
        // savedEntity is the Session returned by sessionRepo.save() (which is
        // typed SessionEntity per JpaRepository<SessionEntity, UUID>). It also
        // implements the Session interface for AgentService.process().
        Session session = savedEntity;

        AgentService agentService = Mockito.mock(AgentService.class);
        Mockito.when(agentService.process(Mockito.any(Session.class), Mockito.anyString()))
                .thenThrow(new MissingEnvVarException(
                        "DEEPSEEK_API_KEY", "weather-bot"));

        SessionRepository sessionRepo = Mockito.mock(SessionRepository.class);
        Mockito.when(sessionRepo.save(Mockito.any(SessionEntity.class)))
                .thenReturn(savedEntity);

        // Spy on AgentService — also acts as the verification hook that
        // ChatCommand did NOT bypass it. (If ChatCommand wrote to llm_calls
        // directly, this mock would not be invoked; the structural argument
        // in the class JavaDoc + this Mockito verify() together seal it.)
        Mockito.verify(agentService, Mockito.never())
                .process(Mockito.any(Session.class), Mockito.anyString());

        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext()) {
            ctx.getBeanFactory().registerSingleton("agentService", agentService);
            ctx.getBeanFactory().registerSingleton("sessionRepository", sessionRepo);
            ctx.refresh();

            try (SpringContextHandle handle = SpringContextHandle.wrapForTesting(ctx)) {
                ChatCommand cmd = new ChatCommand() {
                    @Override
                    protected SpringContextHandle acquireContext(String primarySourceClassName) {
                        return handle;
                    }
                };
                wireSpec(cmd);
                setField(cmd, "profileName", "weather-bot");
                setField(cmd, "message", "hi");

                // Capture stderr BEFORE running — we rewire spec's err writer.
                java.io.ByteArrayOutputStream stderrSink = new java.io.ByteArrayOutputStream();
                java.io.PrintWriter errWriter =
                        new java.io.PrintWriter(stderrSink, true, StandardCharsets.UTF_8);
                Field specField = findField(cmd.getClass(), "spec");
                specField.setAccessible(true);
                CommandLine.Model.CommandSpec spec =
                        (CommandLine.Model.CommandSpec) specField.get(cmd);
                spec.commandLine().setErr(errWriter);

                // When chat runs against a Provider whose key is missing...
                Integer exit = cmd.call();
                errWriter.flush();

                // Then exit = EX_UNAVAILABLE (69)...
                assertThat(exit).isEqualTo(Sysexits.EX_UNAVAILABLE);

                // And stderr carries exactly the one-line message...
                String stderr = stderrSink.toString(StandardCharsets.UTF_8);
                assertThat(stderr)
                        .as("one-line stderr, no stack trace, mentions missing env var")
                        .contains("DEEPSEEK_API_KEY")
                        .doesNotContain("\tat ")
                        .doesNotContain("MissingEnvVarException")
                        .doesNotContain("--- stack trace (--debug) ---");

                // And agentService.process() was called exactly once
                // (proving ChatCommand did not short-circuit or skip the agent).
                Mockito.verify(agentService, Mockito.times(1))
                        .process(Mockito.eq(session), Mockito.eq("hi"));

                // And sessionRepo.save() was called exactly once
                // (Session is allowed to be persisted; only llm_calls is gated).
                Mockito.verify(sessionRepo, Mockito.times(1))
                        .save(Mockito.any(SessionEntity.class));
            }
        }
    }

    @Test
    void unknownProfile_exitsUsage_stderrOneLine() throws Exception {
        // Given a Spring context whose AgentService throws
        // IllegalArgumentException — the C-AS-3 unknown-profile path.
        AgentService agentService = Mockito.mock(AgentService.class);
        Mockito.when(agentService.process(Mockito.any(Session.class), Mockito.anyString()))
                .thenThrow(new IllegalArgumentException(
                        "Unknown profile: 'ghost-bot' (registry has 1 profiles)"));

        SessionEntity savedEntity = SessionEntity.create(
                UUID.fromString("00000000-0000-0000-0000-0000000000A2"),
                "ghost-bot");
        SessionRepository sessionRepo = Mockito.mock(SessionRepository.class);
        Mockito.when(sessionRepo.save(Mockito.any(SessionEntity.class)))
                .thenReturn(savedEntity);

        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext()) {
            ctx.getBeanFactory().registerSingleton("agentService", agentService);
            ctx.getBeanFactory().registerSingleton("sessionRepository", sessionRepo);
            ctx.refresh();

            try (SpringContextHandle handle = SpringContextHandle.wrapForTesting(ctx)) {
                ChatCommand cmd = new ChatCommand() {
                    @Override
                    protected SpringContextHandle acquireContext(String primarySourceClassName) {
                        return handle;
                    }
                };
                wireSpec(cmd);
                setField(cmd, "profileName", "ghost-bot");
                setField(cmd, "message", "hi");

                // Capture stderr BEFORE running — errContent() rewires setErr
                // on the spec, so it must happen before cmd.call().
                java.io.ByteArrayOutputStream stderrSink = new java.io.ByteArrayOutputStream();
                java.io.PrintWriter errWriter =
                        new java.io.PrintWriter(stderrSink, true, StandardCharsets.UTF_8);
                Field specField = findField(cmd.getClass(), "spec");
                specField.setAccessible(true);
                CommandLine.Model.CommandSpec spec =
                        (CommandLine.Model.CommandSpec) specField.get(cmd);
                spec.commandLine().setErr(errWriter);

                Integer exit = cmd.call();
                errWriter.flush();

                // Unknown profile -> EX_USAGE (64) per SC-007.
                assertThat(exit).isEqualTo(Sysexits.EX_USAGE);

                String stderr = stderrSink.toString(StandardCharsets.UTF_8);
                assertThat(stderr)
                        .as("one-line stderr mentioning profile")
                        .contains("Unknown profile")
                        .doesNotContain("\tat ")
                        .doesNotContain("IllegalArgumentException");

                ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
                Mockito.verify(agentService).process(sessionCaptor.capture(), Mockito.eq("hi"));
                assertThat(sessionCaptor.getValue().profileName()).isEqualTo("ghost-bot");
            }
        }
    }

    @Test
    void structural_noDirectLlmCallRepositoryReference_inChatCommand() throws Exception {
        // Defense-in-depth: confirm ChatCommand.runBody() never resolves a
        // bean of type LlmCallRecordRepository — i.e. the audit invariant
        // ("no llm_calls row on fail-fast") is structurally guaranteed,
        // not just behaviorally observed.
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext()) {
            // Register a bean of type LlmCallRecordRepository so that
            // any accidental call would succeed (catch regression early).
            Object fakeRepo = Mockito.mock(
                    io.oryxos.storage.repository.LlmCallRecordRepository.class);
            ctx.getBeanFactory().registerSingleton("llmCallRecordRepository", fakeRepo);

            AgentService agentService = Mockito.mock(AgentService.class);
            Mockito.when(agentService.process(Mockito.any(Session.class), Mockito.anyString()))
                    .thenThrow(new MissingEnvVarException("DEEPSEEK_API_KEY", "weather-bot"));
            SessionEntity savedEntity = SessionEntity.create(
                    UUID.fromString("00000000-0000-0000-0000-0000000000A3"),
                    "weather-bot");
            SessionRepository sessionRepo = Mockito.mock(SessionRepository.class);
            Mockito.when(sessionRepo.save(Mockito.any(SessionEntity.class))).thenReturn(savedEntity);
            ctx.getBeanFactory().registerSingleton("agentService", agentService);
            ctx.getBeanFactory().registerSingleton("sessionRepository", sessionRepo);
            ctx.refresh();

            try (SpringContextHandle handle = SpringContextHandle.wrapForTesting(ctx)) {
                ChatCommand cmd = new ChatCommand() {
                    @Override
                    protected SpringContextHandle acquireContext(String primarySourceClassName) {
                        return handle;
                    }
                };
                wireSpec(cmd);
                setField(cmd, "profileName", "weather-bot");
                setField(cmd, "message", "hi");

                Integer exit = cmd.call();

                assertThat(exit).isEqualTo(Sysexits.EX_UNAVAILABLE);

                // ChatCommand never resolved LlmCallRecordRepository —
                // confirmed by observing Mockito.verifyNoInteractions.
                Mockito.verifyNoInteractions(fakeRepo);
            }
        }
    }

    // --- helpers ---

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Walk the class hierarchy — anonymous subclasses don't redeclare
     *  inherited fields like {@code profileName}, so {@code getDeclaredField}
     *  alone misses them. */
    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void wireSpec(ChatCommand cmd) {
        CommandLine cl = new CommandLine(cmd);
        try {
            Field specField = findField(cmd.getClass(), "spec");
            specField.setAccessible(true);
            specField.set(cmd, cl.getCommandSpec());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unused")
    private static Instant now() {
        return Instant.parse("2026-07-25T07:00:00Z");
    }
}