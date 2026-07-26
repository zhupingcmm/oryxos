package io.oryxos.tool.file;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code file_list} —— 列出目录下条目（不递归）。可选 glob pattern 过滤。
 *
 * <p>行为（[contracts/builtin-tools.md §3](../../../../../../../specs/005-tool-system/contracts/builtin-tools.md)）：
 * <ol>
 *   <li>{@link Sandbox#enforce(SandboxAction) sandbox.enforce(FILE_READ, path)}</li>
 *   <li>{@link Files#list(Path)}</li>
 *   <li>如 {@code pattern} 非空，按 {@link PathMatcher}（{@code glob:...}）过滤</li>
 * </ol>
 */
@Component
public class FileListTool implements OryxTool {

    public static final String NAME = "file_list";

    private final Sandbox sandbox;

    public FileListTool() {
        this(action -> { });
    }

    @Autowired
    public FileListTool(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "列出目录下条目（不递归）";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object raw = arguments.get("path");
        if (!(raw instanceof String pathStr) || pathStr.isBlank()) {
            return ToolResult.error("file_list: missing required argument 'path'");
        }
        Path path = Paths.get(pathStr);
        sandbox.enforce(new SandboxAction(ActionType.FILE_READ, pathStr));

        if (!Files.exists(path)) {
            return ToolResult.error("directory not found: " + pathStr);
        }
        if (!Files.isDirectory(path)) {
            return ToolResult.error("not a directory: " + pathStr);
        }

        Object rawPattern = arguments.get("pattern");
        String pattern = rawPattern == null ? null : rawPattern.toString();
        PathMatcher matcher = (pattern == null || pattern.isBlank())
            ? null
            : FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        try (Stream<Path> stream = Files.list(path)) {
            List<String> entries = stream
                .map(p -> matcher == null ? p : matcher.matches(p.getFileName()) ? p : null)
                .filter(p -> p != null)
                .map(p -> p.getFileName().toString())
                .sorted()
                .collect(Collectors.toList());
            return ToolResult.ok(Map.of(
                "path", pathStr,
                "entries", entries
            ));
        } catch (IOException ex) {
            return ToolResult.error("list failed: " + ex.getMessage());
        }
    }
}

