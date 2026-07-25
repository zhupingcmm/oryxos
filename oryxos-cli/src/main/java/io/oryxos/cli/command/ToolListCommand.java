package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.spring.SpringContextHandle;
import io.oryxos.tool.ToolDefinition;
import io.oryxos.tool.ToolRegistry;
import picocli.CommandLine;

/**
 * {@code oryxos tool list} — enumerate every {@link ToolDefinition}
 * registered with the running OryxOS kernel.
 *
 * <p>This is a <strong>must-Spring</strong> command (FR-012) — it reads the
 * {@link ToolRegistry} bean populated by US-4 (Plugin Tool). Until US-4
 * ships, the registry is empty and this command prints
 * {@code "(no tools registered)"} with exit code 0.
 */
@CommandLine.Command(
        name = "tool",
        mixinStandardHelpOptions = true,
        description = "Inspect registered Tools (Spring-boot, reads ToolRegistry).",
        subcommands = { ToolListCommand.ToolListSubCommand.class })
public class ToolListCommand extends CommandSpringBase {

    @Override
    protected Integer runBody() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return Sysexits.OK;
    }

    @CommandLine.Command(
            name = "list",
            mixinStandardHelpOptions = true,
            description = "List all registered Tools (builtins + MCP + skills).")
    public static class ToolListSubCommand extends CommandSpringBase {

        static final String PRIMARY_SOURCE = "io.oryxos.boot.OryxOsApplication";

        @Override
        protected Integer runBody() {
            try (SpringContextHandle ctx = acquireContext(PRIMARY_SOURCE)) {
                ToolRegistry registry = ctx.context().getBean(ToolRegistry.class);
                var tools = registry.all();
                if (tools.isEmpty()) {
                    spec.commandLine().getOut().println("(no tools registered)");
                } else {
                    spec.commandLine().getOut().println("NAME\tORIGIN\tDESCRIPTION");
                    for (ToolDefinition t : tools) {
                        spec.commandLine().getOut().printf("%s\t%s\t%s%n",
                                nullSafe(t.name()),
                                nullSafe(t.origin()),
                                nullSafe(t.description()));
                    }
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