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
 * FR-018 / FR-010 — top-level exception capture path.
 *
 * <p>Every command extends {@link CommandBase}, whose
 * {@link CommandBase#call()} catches {@code Throwable}, logs the stack via
 * SLF4J at {@code ERROR} level, and prints a single-line summary to
 * stderr. This contract is what guarantees a failed tool / failed
 * Provider / bad YAML / I/O error never leaks a stack trace to the user
 * terminal.
 *
 * <p>This test exercises that path directly by constructing a command whose
 * {@code runBody()} always throws a {@link RuntimeException}, and
 * asserting:
 * <ul>
 *   <li>exit code is {@link Sysexits#GENERIC} (1) — the catch-all
 *       translation in {@code CommandBase#exitCodeFor(Throwable)};</li>
 *   <li>stderr contains the one-line message {@code Error: boom};</li>
 *   <li>stdout contains <em>no</em> mention of {@code Exception},
 *       {@code RuntimeException}, or {@code at io.oryxos.cli.} (i.e.
 *       no stack trace leakage).</li>
 * </ul>
 */
class UncaughtExceptionTest {

    /**
     * Minimal command whose {@code runBody()} unconditionally throws
     * {@code new RuntimeException("boom")}. Its {@code @Command} annotation
     * is what makes Picocli treat it as a subcommand; the {@code --workspace}
     * option exists so we exercise the same Mixin plumbing as the real
     * commands.
     */
    @CommandLine.Command(name = "throw-on-purpose", mixinStandardHelpOptions = true)
    static final class ThrowingCommand extends CommandBase {
        @Override
        protected Integer runBody() {
            throw new RuntimeException("boom");
        }
    }

    @Test
    void uncaughtExceptionMapsToGenericExitAndStderrOnly(@TempDir Path tmp) {
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

        String outStr = stdout.toString(StandardCharsets.UTF_8);
        String errStr = stderr.toString(StandardCharsets.UTF_8);

        // FR-009 / FR-018: top-level Throwable → Sysexits.GENERIC (1).
        assertThat(exit).isEqualTo(Sysexits.GENERIC);

        // FR-010 / FR-018: one-line summary on stderr.
        assertThat(errStr).contains("Error: boom");

        // FR-010: stdout stays clean of stack-trace leakage.
        assertThat(outStr)
                .as("stdout must not contain stack-trace fragments (FR-010 / FR-018)")
                .doesNotContain("Exception")
                .doesNotContain("RuntimeException")
                .doesNotContain("at io.oryxos.cli.")
                .doesNotContain("\tat ");
    }
}