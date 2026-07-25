package io.oryxos.cli.command;

import picocli.CommandLine;

import java.nio.file.Path;

/**
 * Shared {@code --workspace} option — bound to every command and subcommand
 * via Picocli {@code @Mixin} so the override is visible regardless of which
 * subcommand is dispatched.
 *
 * <p>Without this mixin the option is only present on the top-level
 * command class; Picocli does not propagate parsed parent options down to
 * children, so {@code workspaceRoot()} would silently return the current
 * working directory for any subcommand.
 */
public class WorkspaceOption {

    @CommandLine.Option(
            names = {"-w", "--workspace"},
            description = "Workspace root directory (the parent of .oryxos/)")
    public Path workspaceOverride;

    /** Resolve {@code .oryxos/} relative to the workspace override or cwd. */
    public Path resolve() {
        Path base = (workspaceOverride != null)
                ? workspaceOverride
                : Path.of("").toAbsolutePath();
        return base.resolve(".oryxos");
    }

    /** Resolve the parent of {@code .oryxos/} — the workspace itself. */
    public Path workspaceRoot() {
        Path base = (workspaceOverride != null)
                ? workspaceOverride
                : Path.of("").toAbsolutePath();
        return base;
    }
}