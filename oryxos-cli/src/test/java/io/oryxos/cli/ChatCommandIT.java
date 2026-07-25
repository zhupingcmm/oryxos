package io.oryxos.cli;

import io.oryxos.cli.command.ChatCommand;
import io.oryxos.cli.exitcode.Sysexits;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation-focused tests for {@link ChatCommand} (US-1).
 *
 * <p>The full happy-path integration test (boot Spring + mock {@code AgentService}
 * + mock {@code SessionRepository} via WireMock + assert {@code finalText})
 * is deferred until the Spring Data JPA datasource is wired for tests
 * ({@code oryxos-storage} has no H2 / Testcontainers profile yet). This
 * file covers the validation contract:
 *
 * <ul>
 *   <li><b>FR-015</b> profile name matches {@code ^[a-z][a-z0-9-]{0,63}$}
 *       or the command exits {@link Sysexits#EX_USAGE} (64).</li>
 *   <li><b>FR-009</b> empty message → exit {@link Sysexits#EX_USAGE} (64).</li>
 * </ul>
 *
 * <p>Construction path: we instantiate {@link ChatCommand}, set the
 * Picocli-bound fields ({@code profileName}, {@code message}) via
 * reflection (the fields are package-private and inherited from
 * {@code @Parameters} / {@code @Option} discovery), then call
 * {@link ChatCommand#call()}. The {@code call()} method delegates to
 * {@code runBody()} which throws {@link IllegalArgumentException} for
 * any validation failure, and {@link io.oryxos.cli.command.CommandSpringBase}
 * maps that to exit code {@code 64}.
 *
 * <p>{@link CommandLine.Model.CommandSpec#commandLine()} must be wired
 * via reflection too, because Picocli normally injects it after the
 * command is registered into a {@link CommandLine}.
 */
class ChatCommandIT {

    @Test
    void rejectsNullProfileNameWithExitUsage() throws Exception {
        ChatCommand cmd = new ChatCommand();
        wireSpec(cmd);
        setField(cmd, "profileName", null);
        setField(cmd, "message", "hello");

        Integer exit = cmd.call();

        assertThat(exit).isEqualTo(Sysexits.EX_USAGE);
    }

    @Test
    void rejectsProfileNameStartingWithDigit() throws Exception {
        ChatCommand cmd = new ChatCommand();
        wireSpec(cmd);
        setField(cmd, "profileName", "1weather-bot"); // starts with digit → invalid
        setField(cmd, "message", "hello");

        Integer exit = cmd.call();

        assertThat(exit).isEqualTo(Sysexits.EX_USAGE);
    }

    @Test
    void rejectsProfileNameContainingUppercase() throws Exception {
        ChatCommand cmd = new ChatCommand();
        wireSpec(cmd);
        setField(cmd, "profileName", "WeatherBot"); // uppercase → invalid
        setField(cmd, "message", "hello");

        Integer exit = cmd.call();

        assertThat(exit).isEqualTo(Sysexits.EX_USAGE);
    }

    @Test
    void rejectsProfileNameWithUnderscore() throws Exception {
        ChatCommand cmd = new ChatCommand();
        wireSpec(cmd);
        setField(cmd, "profileName", "weather_bot"); // underscore → invalid
        setField(cmd, "message", "hello");

        Integer exit = cmd.call();

        assertThat(exit).isEqualTo(Sysexits.EX_USAGE);
    }

    @Test
    void rejectsEmptyMessageWithExitUsage() throws Exception {
        ChatCommand cmd = new ChatCommand();
        wireSpec(cmd);
        setField(cmd, "profileName", "weather-bot"); // valid name
        setField(cmd, "message", "");              // empty → invalid

        Integer exit = cmd.call();

        assertThat(exit).isEqualTo(Sysexits.EX_USAGE);
    }

    // --- helpers ---

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Inject a {@link CommandLine.Model.CommandSpec} so that
     * {@link io.oryxos.cli.command.CommandSpringBase#call()} can write
     * error text to {@code spec.commandLine().getErr()} without NPE.
     */
    private static void wireSpec(ChatCommand cmd) throws Exception {
        CommandLine cl = new CommandLine(cmd);
        Field specField = ChatCommand.class.getSuperclass().getDeclaredField("spec");
        specField.setAccessible(true);
        specField.set(cmd, cl.getCommandSpec());
    }
}