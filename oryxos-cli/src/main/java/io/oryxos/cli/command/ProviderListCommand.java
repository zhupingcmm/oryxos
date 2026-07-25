package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.spring.SpringContextHandle;
import io.oryxos.provider.ProviderRegistry;
import picocli.CommandLine;

/**
 * {@code oryxos provider list} — list all Providers known to the running
 * OryxOS kernel.
 *
 * <p>This is a <strong>must-Spring</strong> command (FR-012): we need the
 * booted {@link ProviderRegistry} bean to enumerate registered Providers.
 * Boot Spring via {@link CommandSpringBase#acquireContext(String)} when the
 * command starts, then shut it down on exit (try-with-resources).
 */
@CommandLine.Command(
        name = "provider",
        mixinStandardHelpOptions = true,
        description = "Inspect LLM Providers (Spring-boot, reads ProviderRegistry).",
        subcommands = { ProviderListCommand.ProviderListSubCommand.class })
public class ProviderListCommand extends CommandSpringBase {

    /**
     * Command-group stub — Picocli only ever dispatches to the inner
     * subcommand below. Invoking {@code oryxos provider} with no subcommand
     * prints the synopsis (FR-009 / SC-007 exit OK).
     */
    @Override
    protected Integer runBody() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return Sysexits.OK;
    }

    /** Inner subcommand — Spring-boot required to enumerate Providers. */
    @CommandLine.Command(
            name = "list",
            mixinStandardHelpOptions = true,
            description = "List all registered LLM Providers and their default models.")
    public static class ProviderListSubCommand extends CommandSpringBase {

        static final String PRIMARY_SOURCE = "io.oryxos.boot.OryxOsApplication";

        @Override
        protected Integer runBody() {
            try (SpringContextHandle ctx = acquireContext(PRIMARY_SOURCE)) {
                ProviderRegistry registry = ctx.context().getBean(ProviderRegistry.class);
                spec.commandLine().getOut().println("NAME\tMODEL\tCREDENTIAL_REF");
                for (String n : registry.names()) {
                    spec.commandLine().getOut().printf("%s\t%s\t%s%n",
                            n,
                            nullSafe(registry.defaultModelFor(n)),
                            nullSafe(registry.credentialRefFor(n)));
                }
                spec.commandLine().getOut().flush();
            }
            return Sysexits.OK;
        }

        private static String nullSafe(String s) {
            return s == null ? "" : s;
        }
    }
}