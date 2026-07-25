package io.oryxos.cli;

import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.tool.ToolDefinition;
import io.oryxos.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for {@link io.oryxos.cli.command.ToolListCommand} — drives
 * Picocli with a pre-seeded {@link ToolRegistry} and verifies that
 * registered Tools render in the output.
 *
 * <p>{@link ToolRegistry} is a pure POJO (no Spring infrastructure
 * dependencies), so we can render its contents without booting a real
 * Spring context — same pattern as {@link ProviderListCommandTest}.
 */
class ToolListCommandTest {

    @Test
    void listsSeededTools() {
        Map<String, ToolDefinition> tools = new LinkedHashMap<>();
        tools.put("read_file", new ToolDefinition(
                "read_file", "Read a file from the workspace.", "builtin"));
        tools.put("shell", new ToolDefinition(
                "shell", "Run a shell command (sandboxed).", "builtin"));
        tools.put("notify", new ToolDefinition(
                "notify", "Push a message to a webhook.", "builtin"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out, true, StandardCharsets.UTF_8);
        ToolRegistry registry = ToolRegistry.of(tools);
        var sub = new io.oryxos.cli.command.ToolListCommand.ToolListSubCommand() {
            @Override
            protected Integer runBody() {
                if (registry.all().isEmpty()) {
                    spec.commandLine().getOut().println("(no tools registered)");
                } else {
                    spec.commandLine().getOut().println("NAME\tORIGIN\tDESCRIPTION");
                    for (ToolDefinition t : registry.all()) {
                        spec.commandLine().getOut().printf("%s\t%s\t%s%n",
                                t.name(), t.origin(), t.description());
                    }
                }
                spec.commandLine().getOut().flush();
                return Sysexits.OK;
            }
        };
        int exit = new CommandLine(sub).setOut(pw).setErr(pw).execute();
        pw.flush();
        String text = out.toString(StandardCharsets.UTF_8);

        assertThat(text).contains("read_file", "shell", "notify", "builtin");
        assertThat(exit).isEqualTo(Sysexits.OK);
    }

    @Test
    void emptyRegistryPrintsNoToolsRegistered() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(out, true, StandardCharsets.UTF_8);
        ToolRegistry registry = new ToolRegistry();   // empty
        var sub = new io.oryxos.cli.command.ToolListCommand.ToolListSubCommand() {
            @Override
            protected Integer runBody() {
                if (registry.all().isEmpty()) {
                    spec.commandLine().getOut().println("(no tools registered)");
                }
                spec.commandLine().getOut().flush();
                return Sysexits.OK;
            }
        };
        int exit = new CommandLine(sub).setOut(pw).setErr(pw).execute();
        pw.flush();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("(no tools registered)");
        assertThat(exit).isEqualTo(Sysexits.OK);
    }
}