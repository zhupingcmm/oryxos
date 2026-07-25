package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.spring.SpringContextHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.util.concurrent.Callable;

/**
 * Base class for every "must Spring" subcommand (FR-012) — those that need
 * beans from the Spring DI container (e.g. {@code AgentService},
 * {@code ProviderService}, {@code SessionRepository}, {@code ToolRegistry}).
 *
 * <p>Subclasses acquire a {@link SpringContextHandle} via
 * {@link #acquireContext(Class)} inside {@link #runBody()} using
 * try-with-resources so the context is closed even if the command body
 * throws.
 *
 * <p>The primary Spring source is the {@code oryxos-boot}
 * {@code OryxosApplication} class — see {@code BootCommandLineRegistrar}
 * for the registration mechanism (T013).
 */
public abstract class CommandSpringBase implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(CommandSpringBase.class);

    @CommandLine.Mixin
    protected WorkspaceOption workspaceOption = new WorkspaceOption();

    @CommandLine.Spec
    protected CommandLine.Model.CommandSpec spec;

    protected final <T> T bean(SpringContextHandle ctx, Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("bean type must not be null");
        }
        return ctx.context().getBean(type);
    }

    /**
     * Boot a Spring context for the given primary source class name and return
     * an {@link AutoCloseable} handle. Use inside try-with-resources.
     *
     * <p>Default args are {@code {"--spring.main.web-application-type=none",
     * "--spring.main.banner-mode=off"}}. Subclasses needing custom args can
     * pass them in.
     *
     * <p>The primary source is passed as a fully-qualified class name to avoid
     * a compile-time dependency from the CLI module on the {@code oryxos-boot}
     * module (which would create a cycle).
     */
    protected SpringContextHandle acquireContext(String primarySourceClassName) {
        return acquireContext(primarySourceClassName, new String[]{
                "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off"
        });
    }

    protected SpringContextHandle acquireContext(String primarySourceClassName, String[] args) {
        LOG.info("Booting Spring context for `{}` (primary={})",
                commandName(), primarySourceClassName);
        return SpringContextHandle.boot(primarySourceClassName, args);
    }

    /**
     * Class-based overload. Prefer the String form to avoid the compile-time
     * dependency on {@code oryxos-boot}.
     */
    protected SpringContextHandle acquireContext(Class<?> primarySource) {
        return acquireContext(primarySource.getName());
    }

    @Override
    public final Integer call() {
        long t0 = System.nanoTime();
        try {
            return runBody();
        } catch (io.oryxos.cli.workspace.NotInitializedException e) {
            return report(e, e.getMessage(), Sysexits.GENERIC);
        } catch (io.oryxos.cli.config.MissingEnvVarException e) {
            return report(e, e.getMessage(), Sysexits.EX_UNAVAILABLE);
        } catch (IllegalArgumentException e) {
            return report(e, e.getMessage(), Sysexits.EX_USAGE);
        } catch (Throwable t) {
            LOG.error("Spring command `{}` failed", commandName(), t);
            return report(t, t.getMessage(), Sysexits.GENERIC);
        } finally {
            long durationMs = (System.nanoTime() - t0) / 1_000_000L;
            LOG.info("cli.command.invoked name={} duration_ms={}", commandName(), durationMs);
        }
    }

    private int report(Throwable t, String message, int exitCode) {
        spec.commandLine().getErr().println("Error: "
                + (message == null ? t.getClass().getSimpleName() : message));
        return exitCode;
    }

    protected String commandName() {
        return spec.commandLine().getCommandName();
    }

    /** Override to plug command-specific body logic. */
    protected abstract Integer runBody() throws Exception;
}