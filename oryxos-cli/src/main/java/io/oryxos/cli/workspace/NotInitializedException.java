package io.oryxos.cli.workspace;

/**
 * Thrown when {@code init} / {@code status} (and any other command that
 * requires an existing workspace) is invoked in a directory that does not
 * contain a {@code .oryxos/} layout.
 *
 * <p>The CLI layer catches this and translates to exit code
 * {@link io.oryxos.cli.exitcode.Sysexits#GENERIC} (1) with a one-line
 * stderr message; see FR-003 / FR-004.
 */
public final class NotInitializedException extends RuntimeException {

    private final String workspaceRoot;

    public NotInitializedException(String workspaceRoot) {
        super("OryxOS workspace not initialized at " + workspaceRoot
                + " — run `oryxos init` first");
        this.workspaceRoot = workspaceRoot;
    }

    public String workspaceRoot() {
        return workspaceRoot;
    }
}