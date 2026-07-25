package io.oryxos.cli;

import io.oryxos.cli.command.ChatCommand;
import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.spring.SpringContextHandle;
import io.oryxos.core.AgentService;
import io.oryxos.core.Session;
import io.oryxos.provider.exception.LlmInvocationException;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EC-4 (spec.md §"边界情况") — when the Provider's LLM response fails
 * JSON Schema validation (e.g. tool_call arguments are not parseable,
 * or the assistant message is not a valid object), the CLI must:
 *
 * <ul>
 *   <li>Catch the {@link LlmInvocationException} at the top level.</li>
 *   <li>Print a one-line stderr summary (the exception message).</li>
 *   <li>Exit with a non-zero code (sysexits: {@link Sysexits#GENERIC}
 *       for "LLM 4xx-5xx", per ChatCommand contract table).</li>
 *   <li>NOT leak the full stack trace to the terminal — that's FR-018
 *       and FR-010's joint contract (stderr-only + no stack leak).</li>
 * </ul>
 *
 * <p>Spec text: <em>"抛 {@code ProviderException} 由 CLI 包成非零退出码 +
 * stderr 摘要（不打印完整堆栈）；详细堆栈走 {@code .oryxos/logs/}。"</em>
 *
 * <p>The actual JSON Schema validation happens inside
 * {@code oryxos-provider}'s ChatModel adapter; here we test the CLI's
 * fail-fast contract by stubbing {@link AgentService} to throw the
 * matching exception class.
 */
class MalformedJsonSchemaTest {

    @Test
    void malformedJsonResponse_exitsGeneric_stderrOneLine_noStackLeak() throws Exception {
        // Given a Spring context whose AgentService throws LlmInvocationException
        // (mirrors the real flow: provider adapter fails JSON parse, wraps in
        // LlmInvocationException, propagates up).
        AgentService agentService = Mockito.mock(AgentService.class);
        Mockito.when(agentService.process(Mockito.any(Session.class), Mockito.anyString()))
                .thenThrow(new LlmInvocationException(
                        "deepseek",
                        "Provider 'deepseek' returned malformed tool_call JSON: "
                                + "expected {\"url\":\"...\"} but got '{not json' (offset 4)",
                        42L,
                        null));

        SessionEntity savedEntity = SessionEntity.create(
                UUID.fromString("00000000-0000-0000-0000-0000000000C1"),
                "weather-bot");
        SessionRepository sessionRepo = Mockito.mock(SessionRepository.class);
        Mockito.when(sessionRepo.save(Mockito.any(SessionEntity.class))).thenReturn(savedEntity);

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
                setField(cmd, "message", "weather?");

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

                // Then exit = GENERIC (1) — LLM 4xx-5xx family per ChatCommand contract.
                assertThat(exit).isEqualTo(Sysexits.GENERIC);

                String stderr = stderrSink.toString(StandardCharsets.UTF_8);
                assertThat(stderr)
                        .as("one-line stderr carrying the summary, no stack trace")
                        .contains("malformed tool_call JSON")
                        .doesNotContain("\tat ")
                        .doesNotContain("LlmInvocationException")
                        .doesNotContain("--- stack trace (--debug) ---");
            }
        }
    }

    // --- helpers ---

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

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
}