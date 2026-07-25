package io.oryxos.cli.spring;

import io.oryxos.cli.command.CommandSpringBase;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * After Spring has booted, any {@link CommandSpringBase} bean in the
 * application context is collected and registered as a subcommand on the
 * root {@link CommandLine}. This is how {@code chat}, {@code provider list},
 * {@code tool list}, {@code session list} become reachable from the CLI
 * without us resorting to {@code picocli-spring-boot-starter} (per
 * {@code research.md} decision 3).
 *
 * <p>The class is intentionally a plain object (no {@code @Component}) —
 * it is invoked manually by the top-level {@code OryxOsCli} runner after
 * {@code SpringContextHandle#boot} returns.
 */
public final class BootCommandLineRegistrar {

    private BootCommandLineRegistrar() {
        // utility holder
    }

    /**
     * Register every {@link CommandSpringBase} bean in {@code context} as a
     * subcommand of {@code root}.
     *
     * <p>Beans that are themselves annotated with Picocli's
     * {@code @Command(subcommands = {...})} are handled transparently:
     * Picocli will pick up their declared subcommands automatically.
     */
    public static void registerSpringCommands(
            ConfigurableApplicationContext context, CommandLine root) {
        ListableBeanFactory bf = context;
        Map<String, CommandSpringBase> beans = bf.getBeansOfType(CommandSpringBase.class);
        List<CommandSpringBase> ordered = new ArrayList<>(beans.values());
        // Stable ordering by class name so --help output is deterministic.
        ordered.sort((a, b) -> a.getClass().getName().compareTo(b.getClass().getName()));
        for (CommandSpringBase bean : ordered) {
            root.addSubcommand(bean);
        }
    }
}