package io.oryxos.cli.command;

import picocli.CommandLine;

/**
 * Shared {@code --debug} flag — bound to every command and subcommand via
 * Picocli {@code @Mixin} (same shape as {@link WorkspaceOption}).
 *
 * <p>When {@link #enabled} is {@code true}, the {@code CommandBase} top-level
 * {@link Throwable} handler appends the exception stack trace to stderr
 * (in addition to logging it) so operators can correlate the user-facing
 * error with the full cause chain. When {@code false} the
 * <em>no-stack-trace-leak-to-terminal</em> contract of FR-018 holds.
 *
 * <p>This implements the spec's "除非 {@code --debug}" escape hatch
 * (FR-018, Phase 9 T054): the absence of the flag is the steady-state
 * (FR-018 main clause); the flag is the explicit opt-in to see the stack
 * on the terminal.
 */
public class DebugOption {

    @CommandLine.Option(
            names = {"-d", "--debug"},
            description = "Print exception stack traces to stderr in addition to the log file (FR-018 escape hatch).")
    public boolean enabled;

    /** True iff the user passed {@code --debug} on this command or any ancestor. */
    public boolean isEnabled() {
        return enabled;
    }
}
