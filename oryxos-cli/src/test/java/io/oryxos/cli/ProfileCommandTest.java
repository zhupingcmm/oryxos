package io.oryxos.cli;

import io.oryxos.cli.exitcode.Sysexits;
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
 * US-3 / SC-004 / SC-008 — {@code oryxos profile ...} subcommand contract tests.
 *
 * <p>Each test drives a fresh {@link io.oryxos.cli.command.ProfileCommand}
 * via Picocli. We don't bother booting Spring — these are zero-Spring
 * commands (FR-005 / FR-011).
 */
class ProfileCommandTest {

    /** Helper: build a workspace with .oryxos/agents/ pre-created. */
    private static void freshWorkspace(Path tmp) throws Exception {
        Path oryxos = tmp.resolve(".oryxos");
        Files.createDirectories(oryxos.resolve("agents"));
        Files.createDirectories(oryxos.resolve("memory"));
        Files.createDirectories(oryxos.resolve("sessions"));
        Files.createDirectories(oryxos.resolve("logs"));
        Files.writeString(oryxos.resolve("AGENTS.md"), "# x");
        Files.writeString(oryxos.resolve("SOUL.md"), "# x");
        Files.writeString(oryxos.resolve("USER.md"), "# x");
        Files.writeString(oryxos.resolve("mcp_servers.yaml"), "servers: []");
    }

    private static String runAndCapture(String[] args, Path tmp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out, true, StandardCharsets.UTF_8);
        CommandLine cmd = new CommandLine(new io.oryxos.cli.command.ProfileCommand())
                .setOut(pw)
                .setErr(pw);
        int exit = cmd.execute(args);
        pw.flush();
        return out.toString(StandardCharsets.UTF_8) + "\n[exit=" + exit + "]";
    }

    @Test
    void listEmptyWorkspaceSaysNoProfiles(@TempDir Path tmp) throws Exception {
        freshWorkspace(tmp);
        String out = runAndCapture(new String[]{
                "--workspace", tmp.toString(), "list"}, tmp);
        assertThat(out).contains("(no profiles found)");
        assertThat(out).contains("[exit=" + Sysexits.OK + "]");
    }

    @Test
    void listAfterCreateShowsProfile(@TempDir Path tmp) throws Exception {
        freshWorkspace(tmp);
        runAndCapture(new String[]{
                "--workspace", tmp.toString(),
                "create", "weather-bot", "--template", "minimal"}, tmp);
        String out = runAndCapture(new String[]{
                "--workspace", tmp.toString(), "list"}, tmp);
        assertThat(out).contains("weather-bot");
    }

    @Test
    void showExistingProfile(@TempDir Path tmp) throws Exception {
        freshWorkspace(tmp);
        runAndCapture(new String[]{
                "--workspace", tmp.toString(),
                "create", "tech-digest", "--template", "tech-digest"}, tmp);
        String out = runAndCapture(new String[]{
                "--workspace", tmp.toString(), "show", "tech-digest"}, tmp);
        assertThat(out).contains("# Daily Tech Digest");
        assertThat(out).contains("[exit=" + Sysexits.OK + "]");
    }

    @Test
    void showMissingProfileExitsUsage(@TempDir Path tmp) throws Exception {
        freshWorkspace(tmp);
        String out = runAndCapture(new String[]{
                "--workspace", tmp.toString(), "show", "ghost"}, tmp);
        assertThat(out).contains("does not exist");
        assertThat(out).contains("[exit=" + Sysexits.EX_USAGE + "]");
    }

    @Test
    void createThenSecondCreateFails(@TempDir Path tmp) throws Exception {
        freshWorkspace(tmp);
        runAndCapture(new String[]{
                "--workspace", tmp.toString(),
                "create", "weather-bot", "--template", "minimal"}, tmp);
        String out = runAndCapture(new String[]{
                "--workspace", tmp.toString(),
                "create", "weather-bot", "--template", "minimal"}, tmp);
        assertThat(out).contains("already exists");
        assertThat(out).contains("[exit=" + Sysexits.EX_USAGE + "]");
    }

    @Test
    void deleteExistingProfile(@TempDir Path tmp) throws Exception {
        freshWorkspace(tmp);
        runAndCapture(new String[]{
                "--workspace", tmp.toString(),
                "create", "weather-bot", "--template", "minimal"}, tmp);
        String out = runAndCapture(new String[]{
                "--workspace", tmp.toString(), "delete", "weather-bot", "--force"}, tmp);
        assertThat(out).contains("deleted:");
        // Then verify gone
        assertThat(Files.exists(tmp.resolve(".oryxos/agents/weather-bot"))).isFalse();
    }

    @Test
    void deleteMissingProfileExitsUsage(@TempDir Path tmp) throws Exception {
        freshWorkspace(tmp);
        String out = runAndCapture(new String[]{
                "--workspace", tmp.toString(), "delete", "ghost", "--force"}, tmp);
        assertThat(out).contains("does not exist");
        assertThat(out).contains("[exit=" + Sysexits.EX_USAGE + "]");
    }
}