package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code oryxos profile show <name>} — print the {@code AGENT.md} contents.
 *
 * <p>Exit code 0 on success, 64 (EX_USAGE) if the profile does not exist or
 * the name regex is violated.
 */
@CommandLine.Command(
        name = "show",
        mixinStandardHelpOptions = true,
        description = "Show the AGENT.md of an agent Profile.")
public class ProfileShowCommand extends CommandBase {

    @CommandLine.Parameters(
            index = "0",
            paramLabel = "<name>",
            description = "Profile name.")
    String name;

    @Override
    protected Integer runBody() throws Exception {
        ProfileCommand.requireValidName(name);
        Path agentMd = ProfileCommand.profileDir(workspaceRoot(), name).resolve("AGENT.md");
        if (!Files.isRegularFile(agentMd)) {
            throw new IllegalArgumentException(
                    "Profile '" + name + "' does not exist (no AGENT.md at " + agentMd + ")");
        }
        spec.commandLine().getOut().println(Files.readString(agentMd));
        spec.commandLine().getOut().flush();
        return Sysexits.OK;
    }
}