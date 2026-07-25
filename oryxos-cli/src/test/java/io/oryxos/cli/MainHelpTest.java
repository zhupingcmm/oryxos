package io.oryxos.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-1 / FR-022 — verifies that {@code oryxos --help} lists the {@code chat}
 * subcommand so users can discover it without booting Spring.
 *
 * <p>The full 12-command assertion is deferred to Phase 5 (US-3) when
 * {@code profile}, {@code provider}, {@code tool}, {@code session} are added
 * to {@link OryxOsCli#subcommands} either statically or via
 * {@code BootCommandLineRegistrar}.
 */
class MainHelpTest {

    @Test
    void rootHelpListsChatSubcommand() {
        // Given: a fresh Picocli command tree rooted at OryxOsCli
        // Picocli writes help to both getOut() (PrintWriter) and, for the
        // root --help variant, the auto-generated UsageFormatter writes
        // directly via the PrintWriter bound to System.out. We capture
        // both streams.
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
            CommandLine cmd = new CommandLine(new OryxOsCli());
            // exit code 0 + --help exits; we don't care about return value
            cmd.execute("--help");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        // Then: combined output mentions the `chat` subcommand
        String combined = outBuf.toString(StandardCharsets.UTF_8)
                + errBuf.toString(StandardCharsets.UTF_8);
        assertThat(combined).contains("chat");
    }

    @Test
    void chatHelpDescribesItself() {
        // Given: a fresh command tree that has ChatCommand wired in
        PrintStream originalOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            CommandLine cmd = new CommandLine(new OryxOsCli());
            cmd.execute("chat", "--help");
        } finally {
            System.setOut(originalOut);
        }

        // Then: chat-specific help text appears
        String stdout = buffer.toString(StandardCharsets.UTF_8);
        assertThat(stdout)
                .contains("Trigger an Agent run")
                .contains("<profile-name>")
                .contains("--message");
    }
}