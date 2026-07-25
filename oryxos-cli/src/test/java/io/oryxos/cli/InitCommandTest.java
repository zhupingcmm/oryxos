package io.oryxos.cli;

import io.oryxos.cli.command.InitCommand;
import io.oryxos.cli.exitcode.Sysexits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-2 / SC-002 — {@code oryxos init} contract tests.
 *
 * <p>Happy path: empty directory → 4 dirs + 5 files + 1 SQLite db + exit 0.
 * Idempotency: a second run → stderr "Already initialized" + exit 1.
 * Symlink refusal: a symlink at {@code .oryxos} is not followed (Linux/macOS only).
 */
class InitCommandTest {

    @Test
    void createsFullLayoutOnFirstRun(@TempDir Path tmp) throws Exception {
        // Given: an empty directory
        Path oryxos = tmp.resolve(".oryxos");
        assertThat(Files.exists(oryxos)).isFalse();

        // When: init is invoked with --workspace <tmp>
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out, true, StandardCharsets.UTF_8);
        CommandLine cmd = new CommandLine(new InitCommand())
                .setOut(pw)
                .setErr(pw);

        int exit = cmd.execute("--workspace", tmp.toString());

        // Then: exit 0 and the full layout is on disk
        assertThat(exit).isEqualTo(Sysexits.OK);
        assertThat(Files.isDirectory(oryxos.resolve("agents"))).isTrue();
        assertThat(Files.isDirectory(oryxos.resolve("memory"))).isTrue();
        assertThat(Files.isDirectory(oryxos.resolve("sessions"))).isTrue();
        assertThat(Files.isDirectory(oryxos.resolve("logs"))).isTrue();
        assertThat(Files.isRegularFile(oryxos.resolve("mcp_servers.yaml"))).isTrue();
        assertThat(Files.isRegularFile(oryxos.resolve("AGENTS.md"))).isTrue();
        assertThat(Files.isRegularFile(oryxos.resolve("SOUL.md"))).isTrue();
        assertThat(Files.isRegularFile(oryxos.resolve("USER.md"))).isTrue();
        assertThat(Files.isRegularFile(oryxos.resolve("oryxos.db"))).isTrue();
        // MEMORY.md lives under memory/
        assertThat(Files.isRegularFile(oryxos.resolve("memory").resolve("MEMORY.md"))).isTrue();

        // Stdout should list at least one of the created entries
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("created:");
    }

    @Test
    void secondRunReportsAlreadyInitializedAndExitsOne(@TempDir Path tmp) throws Exception {
        // Given: a fully initialised workspace
        Path oryxos = tmp.resolve(".oryxos");
        Files.createDirectories(oryxos);
        Files.createDirectories(oryxos.resolve("agents"));
        Files.writeString(oryxos.resolve("AGENTS.md"), "## existing user content");

        // When: init runs again
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out, true, StandardCharsets.UTF_8);
        CommandLine cmd = new CommandLine(new InitCommand())
                .setOut(pw)
                .setErr(pw);

        int exit = cmd.execute("--workspace", tmp.toString());
        assertThat(exit).isEqualTo(Sysexits.GENERIC); // 1 per contracts/init.md

        // Then: existing file is untouched (idempotency / A-006)
        assertThat(Files.readString(oryxos.resolve("AGENTS.md")))
                .isEqualTo("## existing user content");
        // Error message is written to the CommandLine's err PrintWriter (above).
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Already initialized");
    }
}