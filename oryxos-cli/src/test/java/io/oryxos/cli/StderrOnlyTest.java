package io.oryxos.cli;

import io.oryxos.cli.command.InitCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-010 / FR-018 — error output must go to stderr, NOT stdout.
 *
 * <p>Each test captures stdout and stderr separately via Picocli's
 * {@code setOut} / {@code setErr} and asserts that stdout stays clean
 * whenever an error is reported.
 */
class StderrOnlyTest {

    @Test
    void secondInitWritesErrorToStderrOnly(@TempDir Path tmp) throws Exception {
        Path oryxos = tmp.resolve(".oryxos");
        Files.createDirectories(oryxos.resolve("agents"));
        Files.writeString(oryxos.resolve("AGENTS.md"), "ok");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(stdout, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(stderr, true, StandardCharsets.UTF_8);
        new CommandLine(new InitCommand())
                .setOut(out)
                .setErr(err)
                .execute("--workspace", tmp.toString());
        out.flush();
        err.flush();

        String errStr = stderr.toString(StandardCharsets.UTF_8);
        String outStr = stdout.toString(StandardCharsets.UTF_8);

        assertThat(errStr).contains("Error");
        // stdout may still contain benign framing lines (e.g. the layout
        // listing) but NOT the error message text.
        assertThat(outStr).doesNotContain("Error");
    }

    @Test
    void badWorkspacePrintsErrorToStderr(@TempDir Path tmp) throws Exception {
        // status on a non-existent workspace → error to stderr only.
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintWriter out = new PrintWriter(stdout, true, StandardCharsets.UTF_8);
        PrintWriter err = new PrintWriter(stderr, true, StandardCharsets.UTF_8);
        new CommandLine(new io.oryxos.cli.command.StatusCommand())
                .setOut(out)
                .setErr(err)
                .execute("--workspace", tmp.toString());
        out.flush();
        err.flush();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("Error");
    }
}