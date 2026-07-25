package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Base class for every "zero Spring" subcommand (FR-011) — those that
 * operate on the filesystem directly without booting a Spring context.
 *
 * <p>Concrete subclasses are annotated with {@code @Command(name = "...")}
 * and implemented as plain Java; Picocli locates them via reflection and
 * invokes {@link #call()} (per {@code research.md} decision 3, we avoid
 * {@code picocli-spring-boot-starter}).
 *
 * <p>This base provides:
 * <ul>
 *   <li>{@link #workspaceRoot()} — resolves {@code .oryxos/} under the
 *       current directory (or {@code --workspace} override).</li>
 *   <li>Centralised error reporting: stderr-only (FR-010), stack trace
 *       goes to {@code .oryxos/logs/oryxos-cli-error.log} (FR-018).</li>
 *   <li>Translation from {@link RuntimeException} to a {@link Sysexits}
 *       exit code (FR-009).</li>
 * </ul>
 */
public abstract class CommandBase implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(CommandBase.class);

    @CommandLine.Mixin
    protected WorkspaceOption workspaceOption = new WorkspaceOption();

    @CommandLine.Spec
    protected CommandLine.Model.CommandSpec spec;

    /** Override to plug a custom error→exit-code translator. */
    protected int exitCodeFor(Throwable error) {
        if (error instanceof io.oryxos.cli.config.MissingEnvVarException) {
            return Sysexits.EX_UNAVAILABLE;
        }
        if (error instanceof IllegalStateException msg
                && msg.getMessage() != null
                && msg.getMessage().startsWith("Profile YAML frontmatter")) {
            return Sysexits.EX_CONFIG;
        }
        if (error instanceof IllegalArgumentException) {
            return Sysexits.EX_USAGE;
        }
        return Sysexits.GENERIC;
    }

    /** Resolve {@code .oryxos/} relative to the workspace override or cwd.
 * Walks the Picocli parent chain so a {@code --workspace} parsed on the
 * top-level command is visible to dispatched subcommands too. */
    protected Path workspaceRoot() {
        Path override = workspaceOption.workspaceOverride;
        CommandLine.Model.CommandSpec s = spec;
        while (override == null && s != null) {
            Object user = s.userObject();
            if (user instanceof CommandBase cb && cb != this) {
                override = cb.workspaceOption.workspaceOverride;
            }
            s = s.parent();
        }
        Path base = (override != null)
                ? override
                : Path.of("").toAbsolutePath();
        return base.resolve(".oryxos");
    }

    /**
     * Run the command body. Subclasses implement their domain logic here
     * and return either {@link Sysexits#OK} on success or a symbolic exit
     * code from {@link Sysexits} on failure.
     */
    protected abstract Integer runBody() throws Exception;

    @Override
    public final Integer call() {
        long t0 = System.nanoTime();
        try {
            return runBody();
        } catch (io.oryxos.cli.workspace.NotInitializedException e) {
            return report(e, e.getMessage(), Sysexits.GENERIC);
        } catch (io.oryxos.cli.config.MissingEnvVarException e) {
            return report(e, e.getMessage(), exitCodeFor(e));
        } catch (IllegalArgumentException e) {
            return report(e, e.getMessage(), exitCodeFor(e));
        } catch (Throwable t) {
            // FR-018: stack goes to log; user sees one-line stderr.
            LOG.error("Command `{}` failed", commandName(), t);
            return report(t, t.getMessage(), exitCodeFor(t));
        } finally {
            long durationMs = (System.nanoTime() - t0) / 1_000_000L;
            LOG.info("cli.command.invoked name={} duration_ms={}",
                    commandName(), durationMs);
        }
    }

    private int report(Throwable t, String message, int exitCode) {
        PrintWriter err = spec.commandLine().getErr();
        err.println("Error: " + (message == null ? t.getClass().getSimpleName() : message));
        err.flush();
        return exitCode;
    }

    protected String commandName() {
        return spec.commandLine().getCommandName();
    }
}