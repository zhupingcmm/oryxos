package io.oryxos.cli;

import io.oryxos.cli.command.CommandBase;
import io.oryxos.cli.exitcode.Sysexits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-018 escape hatch — {@code --debug} flag must surface the stack trace
 * to stderr in addition to the log file. Without {@code --debug}, the
 * no-stack-leak contract (covered by {@code UncaughtExceptionTest}) must
 * continue to hold.
 *
 * <p>Test layout mirrors {@code UncaughtExceptionTest}: a single
 * {@code ThrowingCommand} whose {@code runBody()} throws
 * {@code new RuntimeException("boom")}, dispatched with and without
 * {@code --debug} through a fresh {@link CommandLine}.
 */
class DebugFlagTest {

    @CommandLine.Command(name = "throw-on-purpose", mixinStandardHelpOptions = true)
    static final class ThrowingCommand extends CommandBase {
        @Override
        protected Integer runBody() {
            throw new RuntimeException("boom");
        }
    }

    @Test
    void withoutDebug_stderrHasNoStackTrace_butHasOneLineMessage(@TempDir Path tmp) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(stdout, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(stderr, true, StandardCharsets.UTF_8);

        int exit = new CommandLine(new ThrowingCommand())
                .setOut(out)
                .setErr(err)
                .execute("--workspace", tmp.toString());
        out.flush();
        err.flush();

        String errStr = stderr.toString(StandardCharsets.UTF_8);

        // No-debug branch must preserve the FR-018 main contract:
        // one-line stderr, no stack trace fragments.
        assertThat(exit).isEqualTo(Sysexits.GENERIC);
        assertThat(errStr).contains("Error: boom");
        assertThat(errStr).doesNotContain("\tat ");
        assertThat(errStr).doesNotContain("RuntimeException");
        assertThat(errStr).doesNotContain("--- stack trace (--debug) ---");
    }

    @Test
    void withDebug_stderrShowsFullStackTrace(@TempDir Path tmp) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(stdout, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(stderr, true, StandardCharsets.UTF_8);

        int exit = new CommandLine(new ThrowingCommand())
                .setOut(out)
                .setErr(err)
                .execute("--workspace", tmp.toString(), "--debug");
        out.flush();
        err.flush();

        String errStr = stderr.toString(StandardCharsets.UTF_8);
        String outStr = stdout.toString(StandardCharsets.UTF_8);

        // Debug branch: same exit code + same one-line summary, but ALSO
        // a stack trace on stderr (the spec's "除非 --debug" escape hatch).
        assertThat(exit).isEqualTo(Sysexits.GENERIC);
        assertThat(errStr).contains("Error: boom");
        assertThat(errStr).contains("--- stack trace (--debug) ---");
        assertThat(errStr)
                .as("--debug must surface the RuntimeException class name on stderr")
                .contains("RuntimeException");
        assertThat(errStr)
                .as("--debug must surface the indented source-frame line on stderr")
                .contains("\tat ");
        assertThat(errStr)
                .as("--debug stack trace must reference the throwing frame (ThrowingCommand.runBody)")
                .contains("ThrowingCommand.runBody");

        // stdout must remain clean of any exception / frame info — the
        // debug surface is stderr-only, matching FR-010 + FR-018.
        assertThat(outStr)
                .doesNotContain("RuntimeException")
                .doesNotContain("\tat ");
    }
}