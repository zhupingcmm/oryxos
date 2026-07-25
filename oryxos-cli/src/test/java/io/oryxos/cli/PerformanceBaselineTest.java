package io.oryxos.cli;

import io.oryxos.cli.command.InitCommand;
import io.oryxos.cli.command.ProfileListCommand;
import io.oryxos.cli.command.StatusCommand;
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
 * SC-003 / SC-004 / SC-005 — performance baselines.
 *
 * <ul>
 *   <li>{@code oryxos init} on a fresh workspace must complete in &le; 2s (SC-005)</li>
 *   <li>{@code oryxos profile list} on &le; 50 profiles must complete in &le; 200ms (SC-004)</li>
 *   <li>{@code oryxos status} on a fully initialised workspace must complete in &le; 200ms (SC-003)</li>
 * </ul>
 *
 * <p>These tests use generous upper bounds (10x the SC target) so they
 * remain green under heavy CI load. If you tighten them, do it deliberately.
 */
class PerformanceBaselineTest {

    private static final long INIT_BUDGET_MS = 20_000L;       // SC-005 ≤ 2s × 10
    private static final long LIST_BUDGET_MS = 2_000L;        // SC-004 ≤ 200ms × 10
    private static final long STATUS_BUDGET_MS = 2_000L;      // SC-003 ≤ 200ms × 10

    @Test
    void initCompletesWithinBudget(@TempDir Path tmp) throws Exception {
        long t0 = System.nanoTime();
        new CommandLine(new InitCommand())
                .setOut(new PrintWriter(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .setErr(new PrintWriter(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .execute("--workspace", tmp.toString());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertThat(elapsedMs)
                .as("init took %d ms (budget %d ms)", elapsedMs, INIT_BUDGET_MS)
                .isLessThanOrEqualTo(INIT_BUDGET_MS);
    }

    @Test
    void profileListCompletesWithinBudget(@TempDir Path tmp) throws Exception {
        // Seed 50 profiles
        Path agents = tmp.resolve(".oryxos/agents");
        Files.createDirectories(agents);
        for (int i = 0; i < 50; i++) {
            Path p = agents.resolve("profile-" + i);
            Files.createDirectories(p);
            Files.writeString(p.resolve("AGENT.md"), "# profile-" + i);
        }

        long t0 = System.nanoTime();
        new CommandLine(new ProfileListCommand())
                .setOut(new PrintWriter(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .setErr(new PrintWriter(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .execute("--workspace", tmp.toString());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertThat(elapsedMs)
                .as("profile list took %d ms (budget %d ms)", elapsedMs, LIST_BUDGET_MS)
                .isLessThanOrEqualTo(LIST_BUDGET_MS);
    }

    /**
     * SC-003 — {@code oryxos status} on a fully initialised workspace
     * (with {@code application.yaml} + {@code mcp_servers.yaml} + a few
     * profiles) must render its full health report within the 200 ms
     * target. Test budget = 10× target = 2 000 ms.
     */
    @Test
    void statusCompletesWithinBudget(@TempDir Path tmp) throws Exception {
        // Seed a realistic workspace so status has actual work to do.
        Path oryxos = tmp.resolve(".oryxos");
        Files.createDirectories(oryxos.resolve("agents"));
        Files.createDirectories(oryxos.resolve("memory"));
        Files.createDirectories(oryxos.resolve("sessions"));
        Files.createDirectories(oryxos.resolve("logs"));
        Files.writeString(oryxos.resolve("application.yaml"), """
                oryxos:
                  providers:
                    deepseek:
                      model: deepseek-chat
                      credentialRef: ORYXOS_TEST_STATUS_KEY
                """);
        Files.writeString(oryxos.resolve("AGENTS.md"), "# bootstrap");
        Files.writeString(oryxos.resolve("SOUL.md"), "# bootstrap");
        Files.writeString(oryxos.resolve("USER.md"), "# bootstrap");
        Files.writeString(oryxos.resolve("mcp_servers.yaml"), """
                servers:
                  - name: github
                    command: mcp-github
                """);
        for (int i = 0; i < 3; i++) {
            Path p = oryxos.resolve("agents").resolve("profile-" + i);
            Files.createDirectories(p);
            Files.writeString(p.resolve("AGENT.md"), "# profile-" + i);
        }

        long t0 = System.nanoTime();
        new CommandLine(new StatusCommand())
                .setOut(new PrintWriter(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .setErr(new PrintWriter(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .execute("--workspace", tmp.toString());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertThat(elapsedMs)
                .as("status took %d ms (budget %d ms)", elapsedMs, STATUS_BUDGET_MS)
                .isLessThanOrEqualTo(STATUS_BUDGET_MS);
    }
}