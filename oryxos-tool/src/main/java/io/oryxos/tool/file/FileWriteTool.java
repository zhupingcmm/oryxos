package io.oryxos.tool.file;

import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * {@code file_write} —— 写入本地文本文件（覆盖或追加）。
 *
 * <p>行为（[contracts/builtin-tools.md §2](../../../../../../../specs/005-tool-system/contracts/builtin-tools.md)）：
 * <ol>
 *   <li>解析路径</li>
 *   <li>{@link Sandbox#enforce(SandboxAction) sandbox.enforce(FILE_WRITE, ...)}</li>
 *   <li>{@code Files.createDirectories(parent)}</li>
 *   <li>按 {@code append} 选择 {@link StandardOpenOption#CREATE} + {@code TRUNCATE_EXISTING}
 *       或 {@code CREATE} + {@code APPEND}</li>
 *   <li>{@link Files#writeString(Path, CharSequence, java.nio.charset.Charset, StandardOpenOption...)}</li>
 * </ol>
 */
@Component
public class FileWriteTool implements OryxTool {

    public static final String NAME = "file_write";

    private final Sandbox sandbox;

    public FileWriteTool() {
        this(action -> { });
    }

    @Autowired
    public FileWriteTool(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "写入本地文本文件（覆盖或追加）";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object rawPath = arguments.get("path");
        if (!(rawPath instanceof String pathStr) || pathStr.isBlank()) {
            return ToolResult.error("file_write: missing required argument 'path'");
        }
        Object rawContent = arguments.get("content");
        String content = rawContent == null ? "" : rawContent.toString();
        boolean append = Boolean.TRUE.equals(arguments.get("append"));

        Path path = Paths.get(pathStr);
        sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, pathStr));

        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ex) {
                return ToolResult.error("cannot create parent dir: " + parent);
            }
        }

        try {
            long size;
            if (append) {
                size = Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND).toFile().length();
                // writeString returns Path; for accurate size, re-stat
                size = Files.size(path);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                size = Files.size(path);
            }
            return ToolResult.ok(Map.of(
                "path", pathStr,
                "size_bytes", size,
                "appended", append
            ));
        } catch (IOException ex) {
            return ToolResult.error("write failed: " + ex.getMessage());
        }
    }
}

