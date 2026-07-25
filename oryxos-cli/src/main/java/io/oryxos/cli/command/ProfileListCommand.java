package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code oryxos profile list} — print one row per Profile directory.
 *
 * <p>Reads only directory names — the actual {@code AGENT.md} content is
 * scanned lazily by {@code profile show}. The list command must complete
 * in &le; 200 ms (SC-004) for &le; 50 profiles.
 */
@CommandLine.Command(
        name = "list",
        mixinStandardHelpOptions = true,
        description = "List all agent Profiles in the workspace.")
public class ProfileListCommand extends CommandBase {

    @Override
    protected Integer runBody() throws IOException {
        Path agents = ProfileCommand.agentsDir(workspaceRoot());
        if (!Files.isDirectory(agents)) {
            spec.commandLine().getOut().println("(no profiles found)");
            spec.commandLine().getOut().flush();
            return Sysexits.OK;
        }
        // Build a sorted list of child directory names.
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(agents)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    names.add(p.getFileName().toString());
                }
            }
        }
        Collections.sort(names);
        if (names.isEmpty()) {
            spec.commandLine().getOut().println("(no profiles found)");
        } else {
            spec.commandLine().getOut().println("NAME");
            for (String n : names) {
                spec.commandLine().getOut().println(n);
            }
        }
        spec.commandLine().getOut().flush();
        return Sysexits.OK;
    }
}