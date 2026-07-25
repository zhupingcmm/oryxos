package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * {@code oryxos profile ...} command group — see {@code contracts/profile.md}
 * and FR-005.
 *
 * <p>Zero-Spring (FR-005 / FR-011). Operates on the filesystem only; never
 * touches SQLite. Subcommands:
 *
 * <ul>
 *   <li>{@code oryxos profile list} — print one row per {@code .oryxos/agents/<name>/}</li>
 *   <li>{@code oryxos profile show <name>} — print the {@code AGENT.md} contents</li>
 *   <li>{@code oryxos profile create <name> --template <tpl>} — copy a template
 *       to {@code .oryxos/agents/<name>/AGENT.md}</li>
 *   <li>{@code oryxos profile delete <name> [--force]} — recursively remove the directory</li>
 * </ul>
 *
 * <p>Profile names match {@code ^[a-z][a-z0-9-]{0,63}$} per FR-015 — same
 * regex as {@code ChatCommand}.
 *
 * <p>Subcommands extend {@link CommandBase} directly (NOT {@code ProfileCommand})
 * because Picocli refuses to register a subcommand class that is a subclass of
 * its parent. Shared helpers live as {@code static} methods on this class.
 */
@CommandLine.Command(
        name = "profile",
        mixinStandardHelpOptions = true,
        description = "Manage agent Profiles (zero-Spring, file-only).",
        subcommands = {
                ProfileListCommand.class,
                ProfileShowCommand.class,
                ProfileCreateCommand.class,
                ProfileDeleteCommand.class
        })
public class ProfileCommand extends CommandBase {

    /** Profile name regex from FR-015 — shared with ChatCommand. */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    /** Resolve the agents directory under the supplied workspace root. */
    public static Path agentsDir(Path workspaceRoot) {
        return workspaceRoot.resolve("agents");
    }

    /** Resolve a single Profile directory under the supplied workspace root. */
    public static Path profileDir(Path workspaceRoot, String name) {
        return agentsDir(workspaceRoot).resolve(name);
    }

    /** Throw IllegalArgumentException (→ EX_USAGE) if {@code name} violates the regex. */
    public static void requireValidName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Invalid profile name '" + name
                            + "' — must match ^[a-z][a-z0-9-]{0,63}$");
        }
    }

    /**
     * Command-group stub — Picocli only ever dispatches to the four
     * subcommands below, never to {@code ProfileCommand} itself. If a user
     * ever invokes {@code oryxos profile} with no subcommand, Picocli
     * prints the synopsis before this is reached, but we still need to
     * satisfy {@link CommandBase#runBody()}.
     */
    @Override
    protected Integer runBody() throws Exception {
        spec.commandLine().usage(spec.commandLine().getOut());
        return Sysexits.OK;
    }
}