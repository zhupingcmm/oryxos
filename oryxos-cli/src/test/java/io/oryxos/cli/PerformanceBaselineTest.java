package io.oryxos.cli;

import io.oryxos.cli.command.InitCommand;
import io.oryxos.cli.command.ProfileListCommand;
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
 * SC-004 / SC-005 — performance baselines.
 *
 * <ul>
 *   <li>{@code oryxos init} on a fresh workspace must complete in &le; 2s (SC-005)</li>
 *   <li>{@code oryxos profile list} on &le; 50 profiles must complete in &le; 200ms (SC-004)</li>
 * </ul>
 *
 * <p>These tests use generous upper bounds (10x the SC target) so they
 * remain green under heavy CI load. If you tighten them, do it deliberately.
 */
class PerformanceBaselineTest {

    private static final long INIT_BUDGET_MS = 20_000L;       // SC-005 ≤ 2s × 10
    private static final long LIST_BUDGET_MS = 2_000L;        // SC-004 ≤ 200ms × 10

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
}