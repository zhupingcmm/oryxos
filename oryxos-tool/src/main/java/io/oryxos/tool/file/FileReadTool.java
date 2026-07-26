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
import java.util.Map;

/**
 * {@code file_read} —— 读取本地文本文件内容。
 *
 * <p>行为（[contracts/builtin-tools.md §1](../../../../../../../specs/005-tool-system/contracts/builtin-tools.md)）：
 * <ol>
 *   <li>解析 {@code path}（绝对路径或相对当前工作目录）</li>
 *   <li>{@link Sandbox#enforce(SandboxAction) sandbox.enforce(FILE_READ, resolvedPath)}（核心阶段 no-op）</li>
 *   <li>{@link Files#readString(Path, java.nio.charset.Charset) Files.readString(... , UTF-8)}</li>
 *   <li>返回 {@link ToolResult#ok} with {@link FileToolResult} payload</li>
 * </ol>
 *
 * <p>错误映射 → {@link ToolResult#error}（4 类：not found / is directory / permission denied / too large）。
 */
@Component
public class FileReadTool implements OryxTool {

    public static final String NAME = "file_read";

    /** 文件大小上限 10 MB（与 sandbox.md 一致；超过此值抛"too large"）。 */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final Sandbox sandbox;

    public FileReadTool() {
        this(action -> { /* 默认无沙箱：no-op，让单测无需 sandbox */ });
    }

    @Autowired
    public FileReadTool(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "读取本地文本文件内容";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object raw = arguments.get("path");
        if (!(raw instanceof String pathStr) || pathStr.isBlank()) {
            return ToolResult.error("file_read: missing required argument 'path'");
        }
        Path path = Paths.get(pathStr);
        sandbox.enforce(new SandboxAction(ActionType.FILE_READ, pathStr));

        if (!Files.exists(path)) {
            return ToolResult.error("file not found: " + pathStr);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.error("path is a directory: " + pathStr);
        }
        try {
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES) {
                return ToolResult.error("file too large: " + size + " bytes (max " + MAX_FILE_BYTES + ")");
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            FileToolResult payload = new FileToolResult(pathStr, size, content, null);
            return ToolResult.ok(Map.of(
                "path", pathStr,
                "size_bytes", size,
                "content", content
            ));
        } catch (java.nio.file.AccessDeniedException ex) {
            return ToolResult.error("permission denied: " + pathStr);
        } catch (IOException ex) {
            return ToolResult.error("read failed: " + ex.getMessage());
        }
    }
}

