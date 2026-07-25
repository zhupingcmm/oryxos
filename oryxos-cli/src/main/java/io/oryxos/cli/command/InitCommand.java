package io.oryxos.cli.command;

import io.oryxos.cli.exitcode.Sysexits;
import io.oryxos.cli.workspace.BootstrapContent;
import io.oryxos.cli.workspace.WorkspaceLayout;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code oryxos init} — create a fresh {@code .oryxos/} workspace in the
 * current directory or under {@code --workspace <path>}.
 *
 * <p>Zero-Spring (FR-003 / FR-011). Idempotent: a second invocation reports
 * "Already initialized" and exits {@link Sysexits#GENERIC} (1) without
 * overwriting anything (A-006).
 *
 * <p>Layout created on a fresh workspace:
 * <pre>
 *   .oryxos/
 *   ├── agents/      (empty)
 *   ├── memory/      (contains MEMORY.md)
 *   ├── sessions/    (empty)
 *   ├── logs/        (empty)
 *   ├── mcp_servers.yaml  (minimal skeleton)
 *   ├── AGENTS.md    (BootstrapContent.AGENTS_MD)
 *   ├── SOUL.md      (BootstrapContent.SOUL_MD)
 *   ├── USER.md      (BootstrapContent.USER_MD)
 *   └── oryxos.db    (empty file — schema initialised when Spring boots)
 * </pre>
 */
@CommandLine.Command(
        name = "init",
        mixinStandardHelpOptions = true,
        description = "Initialise a fresh .oryxos/ workspace (idempotent).")
public class InitCommand extends CommandBase {

    @Override
    protected Integer runBody() throws Exception {
        Path oryxos = workspaceRoot();

        // Symlink refusal (matches WorkspaceLayout contract).
        if (Files.exists(oryxos, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(oryxos)) {
            throw new IllegalStateException(
                    "Refusing to traverse symlink at " + oryxos);
        }

        if (Files.exists(oryxos)) {
            // Idempotent guard (FR-003, A-006).
            throw new IllegalStateException(
                    "Already initialized at " + oryxos.toRealPath(LinkOption.NOFOLLOW_LINKS));
        }

        // 1. Create the directory tree (4 required dirs).
        WorkspaceLayout layout = new WorkspaceLayout(
                oryxos,
                WorkspaceLayout.REQUIRED_DIR_NAMES.stream().map(oryxos::resolve).toList(),
                WorkspaceLayout.REQUIRED_FILE_NAMES.stream().map(oryxos::resolve).toList(),
                System.currentTimeMillis(),
                0, 0, 0);
        layout.initialize();

        // 2. Write bootstrap files. Each one is created with CREATE_NEW semantics
        //    so a partial init cannot accidentally overwrite an existing file.
        writeIfAbsent(oryxos.resolve("AGENTS.md"), BootstrapContent.AGENTS_MD);
        writeIfAbsent(oryxos.resolve("SOUL.md"), BootstrapContent.SOUL_MD);
        writeIfAbsent(oryxos.resolve("USER.md"), BootstrapContent.USER_MD);
        writeIfAbsent(oryxos.resolve("memory").resolve("MEMORY.md"), BootstrapContent.MEMORY_MD);
        writeIfAbsent(oryxos.resolve("mcp_servers.yaml"), MCP_SERVERS_YAML_SKELETON);

        // 3. Stdout: list what we created.
        List<Path> created = List.of(
                oryxos.resolve("AGENTS.md"),
                oryxos.resolve("SOUL.md"),
                oryxos.resolve("USER.md"),
                oryxos.resolve("mcp_servers.yaml"),
                oryxos.resolve("memory").resolve("MEMORY.md"),
                oryxos.resolve("agents"),
                oryxos.resolve("sessions"),
                oryxos.resolve("logs"),
                oryxos.resolve("oryxos.db"));
        for (Path p : created) {
            spec.commandLine().getOut().println("created: " + p);
        }
        spec.commandLine().getOut().flush();
        return Sysexits.OK;
    }

    /**
     * Write {@code content} to {@code file} only if it does not yet exist.
     * The {@code CREATE_NEW} open option fails fast if a parallel caller
     * somehow races us — we surface that as {@code IllegalStateException}
     * (mapped to {@link Sysexits#GENERIC} by {@link CommandBase#call()}).
     */
    private void writeIfAbsent(Path file, String content) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        Files.writeString(file, content);
    }

    /** Minimal MCP server config skeleton — required file per contracts/init.md. */
    private static final String MCP_SERVERS_YAML_SKELETON = """
            # MCP server registry — populated by `oryxos profile <name> --add-mcp <server>`
            # or by hand. See docs/TechnicalSolution.md §"MCP" for the schema.
            servers: []
            """;
}