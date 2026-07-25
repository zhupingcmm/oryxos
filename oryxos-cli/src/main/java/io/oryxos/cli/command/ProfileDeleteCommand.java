package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code oryxos profile delete <name> [--force]} — recursively delete a Profile.
 *
 * <p>Exit 0 on success, 64 (EX_USAGE) when the profile is missing or
 * non-empty and {@code --force} is not provided.
 */
@CommandLine.Command(
        name = "delete",
        mixinStandardHelpOptions = true,
        description = "Delete an agent Profile (use --force to remove non-empty).")
public class ProfileDeleteCommand extends CommandBase {

    @CommandLine.Parameters(
            index = "0",
            paramLabel = "<name>",
            description = "Profile name to delete.")
    String name;

    @CommandLine.Option(
            names = {"-f", "--force"},
            description = "Delete even if the Profile directory has extra files.")
    boolean force;

    @Override
    protected Integer runBody() throws Exception {
        ProfileCommand.requireValidName(name);
        Path dir = ProfileCommand.profileDir(workspaceRoot(), name);
        if (!Files.exists(dir)) {
            throw new IllegalArgumentException(
                    "Profile '" + name + "' does not exist (no dir at " + dir + ")");
        }

        if (!force && !isEmpty(dir)) {
            throw new IllegalArgumentException(
                    "Profile '" + name + "' is not empty — pass --force to delete anyway");
        }

        deleteRecursive(dir);
        spec.commandLine().getOut().println("deleted: " + dir);
        spec.commandLine().getOut().flush();
        return Sysexits.OK;
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    deleteRecursive(child);
                } else {
                    Files.delete(child);
                }
            }
        }
        Files.delete(dir);
    }
}