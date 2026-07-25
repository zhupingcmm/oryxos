package io.oryxos.cli.diag;

import java.util.List;

/**
 * Diagnostic record for one CLI command invocation — see {@code data-model.md §3.2}.
 *
 * <p>This is <strong>not</strong> persisted to SQLite (no audit table per
 * Constitution §VI "audit day one" — CLI invocations are operational logs,
 * not user-audit records). The {@code OryxOsCli} banner / {@code CommandBase}
 * writes one of these to {@code .oryxos/logs/oryxos-cli.log} per invocation.
 */
public record CommandInvocation(
        String commandName,
        List<String> args,
        long durationMs,
        int exitCode,
        String stderrSummary
) {
}