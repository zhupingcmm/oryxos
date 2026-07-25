package io.oryxos.cli.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code .oryxos/} workspace layout — see {@code data-model.md §2}.
 *
 * <p>This record is a JVM-only, in-memory description of the layout. It does
 * <strong>not</strong> persist anything; {@link #initialize()} creates files
 * and directories under {@code root}, and {@link #probe(Path)} reads the
 * current state to render health reports.
 *
 * <p>Symlink safety: {@link #probe(Path)} uses
 * {@link LinkOption#NOFOLLOW_LINKS} via {@link Path#toRealPath(LinkOption...)}
 * to refuse following symlinks during initialization. This matches the
 * boundary documented in {@code data-model.md §2.1}.
 */
public record WorkspaceLayout(
        Path root,
        List<Path> requiredDirs,
        List<Path> requiredFiles,
        long createdAtEpochMs,
        int profileCount,
        int providerCountConfigured,
        int providerCountMissingKey
) {

    /** The required directory names (relative to {@link #root}). */
    public static final List<String> REQUIRED_DIR_NAMES =
            List.of("agents", "memory", "sessions", "logs");

    /** The required file names (relative to {@link #root}); SQLite lives under root too. */
    public static final List<String> REQUIRED_FILE_NAMES = List.of(
            "mcp_servers.yaml", "AGENTS.md", "SOUL.md", "USER.md");

    /**
     * Probe the workspace rooted at {@code root} and return a layout summary.
     *
     * @throws NotInitializedException if {@code root/.oryxos/} does not exist
     *                                 (or {@code root} == {@code null})
     */
    public static WorkspaceLayout probe(Path root) {
        Objects.requireNonNull(root, "root");
        Path oryxos = root.resolve(".oryxos");
        if (!Files.exists(oryxos)) {
            throw new NotInitializedException(root.toAbsolutePath().toString());
        }
        Path realRoot;
        try {
            realRoot = oryxos.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot resolve .oryxos real path at " + oryxos + ": " + e.getMessage(), e);
        }
        List<Path> requiredDirs = REQUIRED_DIR_NAMES.stream()
                .map(realRoot::resolve)
                .toList();
        List<Path> requiredFiles = REQUIRED_FILE_NAMES.stream()
                .map(realRoot::resolve)
                .toList();
        // SQLite lives at root/.oryxos/oryxos.db; not strictly required at probe time
        // (status of a partial init should still succeed).
        long earliestMtime = earliestMtimeEpochMs(realRoot);
        int profileCount = countChildDirs(realRoot.resolve("agents"));
        return new WorkspaceLayout(
                realRoot,
                requiredDirs,
                requiredFiles,
                earliestMtime,
                profileCount,
                0,   // providerCountConfigured — populated by StatusCommand after Spring DI
                0);  // providerCountMissingKey  — populated by StatusCommand after Spring DI
    }

    /**
     * Initialise the workspace rooted at {@code root} if it does not yet
     * exist. This method is idempotent: if {@code root/.oryxos/} already
     * exists, it returns the existing layout without modifying files.
     *
     * <p>Bootstrap file contents (AGENTS.md, SOUL.md, USER.md, MEMORY.md)
     * are written by the caller — see {@code InitCommand} →
     * {@code BootstrapContent}.
     */
    public void initialize() {
        if (Files.exists(root)) {
            throw new IllegalStateException(
                    "Already initialized at " + root.toAbsolutePath());
        }
        try {
            createDirRefusingSymlinks(root);
            for (String dirName : REQUIRED_DIR_NAMES) {
                createDirRefusingSymlinks(root.resolve(dirName));
            }
            // SQLite DB placeholder; the schema is initialised by InitCommand via storage module.
            Files.createFile(root.resolve("oryxos.db"));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to initialize workspace at " + root + ": " + e.getMessage(), e);
        }
    }

    /**
     * Create a directory, refusing to traverse a symlink at the target path.
     * {@link Files#createDirectories(Path, java.nio.file.attribute.FileAttribute...)}
     * does not accept {@link LinkOption#NOFOLLOW_LINKS}, so we guard manually.
     */
    private static void createDirRefusingSymlinks(Path dir) throws IOException {
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(dir)) {
            throw new IOException("Refusing to traverse symlink at " + dir);
        }
        Files.createDirectories(dir);
    }

    /** Human-readable table for {@code status} (FR-004). */
    public String renderHumanReadable() {
        StringBuilder sb = new StringBuilder();
        sb.append("Workspace: ").append(root).append('\n');
        sb.append("Created:   ").append(Instant.ofEpochMilli(createdAtEpochMs)).append('\n');
        sb.append("Profiles:  ").append(profileCount).append('\n');
        sb.append("Providers: configured=").append(providerCountConfigured)
                .append(", missing_key=").append(providerCountMissingKey).append('\n');
        sb.append("Required dirs (").append(requiredDirs.size()).append("/4 present):\n");
        for (Path p : requiredDirs) {
            sb.append("  [")
                    .append(Files.isDirectory(p) ? "x" : " ")
                    .append("] ").append(p.getFileName()).append('\n');
        }
        sb.append("Required files (").append(requiredFiles.size()).append("/4 present):\n");
        for (Path p : requiredFiles) {
            sb.append("  [")
                    .append(Files.isRegularFile(p) ? "x" : " ")
                    .append("] ").append(p.getFileName()).append('\n');
        }
        return sb.toString();
    }

    /** JSON shape for {@code status --format json} (FR-004). */
    public String renderJson() {
        Map<String, Object> root2 = new LinkedHashMap<>();
        root2.put("workspace", root.toString());
        root2.put("createdAtEpochMs", createdAtEpochMs);
        root2.put("profileCount", profileCount);
        root2.put("providerCountConfigured", providerCountConfigured);
        root2.put("providerCountMissingKey", providerCountMissingKey);
        return root2.toString();
    }

    // --- helpers ----------------------------------------------------------

    private static long earliestMtimeEpochMs(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream
                    .map(p -> {
                        try {
                            return Long.valueOf(Files.getLastModifiedTime(p).toMillis());
                        } catch (IOException e) {
                            return Long.valueOf(Long.MAX_VALUE);
                        }
                    })
                    .min(Long::compareTo)
                    .orElse(Long.valueOf(System.currentTimeMillis()))
                    .longValue();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    private static int countChildDirs(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(Files::isDirectory).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Touch an existing file's mtime to current time (used by init's bootstrap write). */
    public static void touch(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, "",
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        }
    }
}