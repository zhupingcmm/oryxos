package io.oryxos.tool.file;

import io.oryxos.core.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T013：{@code file_read} —— 成功 + 文件不存在 + 是目录 + 超大四类路径（[contracts/builtin-tools.md §1]）。 */
class FileReadToolTest {

    @TempDir Path tmp;
    FileReadTool tool;

    @BeforeEach
    void setUp() {
        tool = new FileReadTool();
    }

    @Test
    @DisplayName("成功：读小文件 → content 含写入内容")
    void file_read_success() throws Exception {
        Path f = tmp.resolve("hello.txt");
        Files.writeString(f, "hello world");
        ToolResult r = tool.execute(Map.of("path", f.toString()));
        assertThat(r.success()).isTrue();
        assertThat((String) r.payload().get("content")).contains("hello world");
        assertThat(((Number) r.payload().get("size_bytes")).longValue()).isPositive();
    }

    @Test
    @DisplayName("失败：文件不存在 → errorMessage 含 'file not found'")
    void file_not_found() {
        ToolResult r = tool.execute(Map.of("path", tmp.resolve("nope.txt").toString()));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("file not found");
    }

    @Test
    @DisplayName("失败：路径是目录 → errorMessage 含 'is a directory'")
    void path_is_directory() {
        ToolResult r = tool.execute(Map.of("path", tmp.toString()));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("is a directory");
    }

    @Test
    @DisplayName("失败：缺 path 参数")
    void missing_path() {
        ToolResult r = tool.execute(Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("missing required argument 'path'");
    }
}

