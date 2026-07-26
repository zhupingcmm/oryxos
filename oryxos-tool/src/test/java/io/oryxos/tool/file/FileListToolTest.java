package io.oryxos.tool.file;

import io.oryxos.core.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T015：{@code file_list} —— 列目录 + glob pattern + 错误路径。 */
class FileListToolTest {

    @TempDir Path tmp;
    FileListTool tool;

    @BeforeEach
    void setUp() {
        tool = new FileListTool();
    }

    @Test
    @DisplayName("成功：无 pattern → 列所有条目")
    void list_success() throws Exception {
        Files.writeString(tmp.resolve("a.md"), "x");
        Files.writeString(tmp.resolve("b.txt"), "y");
        ToolResult r = tool.execute(Map.of("path", tmp.toString()));
        assertThat(r.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<String> entries = (List<String>) r.payload().get("entries");
        assertThat(entries).containsExactly("a.md", "b.txt");
    }

    @Test
    @DisplayName("成功：pattern='*.txt' → 只列 .txt")
    void glob_filter() throws Exception {
        Files.writeString(tmp.resolve("a.md"), "x");
        Files.writeString(tmp.resolve("b.txt"), "y");
        Files.writeString(tmp.resolve("c.txt"), "z");
        ToolResult r = tool.execute(Map.of(
            "path", tmp.toString(), "pattern", "*.txt"
        ));
        assertThat(r.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<String> entries = (List<String>) r.payload().get("entries");
        assertThat(entries).containsExactly("b.txt", "c.txt");
    }

    @Test
    @DisplayName("失败：路径不是目录 → errorMessage 含 'not a directory'")
    void not_a_directory() throws Exception {
        Path f = tmp.resolve("file.txt");
        Files.writeString(f, "x");
        ToolResult r = tool.execute(Map.of("path", f.toString()));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("not a directory");
    }

    @Test
    @DisplayName("失败：目录不存在 → errorMessage 含 'directory not found'")
    void directory_not_found() {
        ToolResult r = tool.execute(Map.of("path", tmp.resolve("nope").toString()));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("directory not found");
    }
}

