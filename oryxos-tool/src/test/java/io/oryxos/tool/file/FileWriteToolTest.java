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

/** T014：{@code file_write} —— 写入成功 + 父目录创建 + append 模式 + 缺参数。 */
class FileWriteToolTest {

    @TempDir Path tmp;
    FileWriteTool tool;

    @BeforeEach
    void setUp() {
        tool = new FileWriteTool();
    }

    @Test
    @DisplayName("成功：写新文件 → 文件存在 + content 匹配")
    void write_success() throws Exception {
        Path f = tmp.resolve("out.txt");
        ToolResult r = tool.execute(Map.of("path", f.toString(), "content", "by tool"));
        assertThat(r.success()).isTrue();
        assertThat(Files.exists(f)).isTrue();
        assertThat(Files.readString(f)).isEqualTo("by tool");
    }

    @Test
    @DisplayName("成功：父目录不存在 → 自动创建")
    void parent_dir_auto_created() throws Exception {
        Path f = tmp.resolve("nested/dir/out.txt");
        ToolResult r = tool.execute(Map.of("path", f.toString(), "content", "deep"));
        assertThat(r.success()).isTrue();
        assertThat(Files.exists(f)).isTrue();
        assertThat(Files.readString(f)).isEqualTo("deep");
    }

    @Test
    @DisplayName("成功：append=true → 追加到已有文件末尾")
    void append_mode() throws Exception {
        Path f = tmp.resolve("a.txt");
        Files.writeString(f, "first");
        ToolResult r = tool.execute(Map.of(
            "path", f.toString(), "content", "-second", "append", true
        ));
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(f)).isEqualTo("first-second");
    }

    @Test
    @DisplayName("失败：缺 content → 写空串不报错（但文件被清空）")
    void missing_content() throws Exception {
        Path f = tmp.resolve("empty.txt");
        Files.writeString(f, "previous");
        ToolResult r = tool.execute(Map.of("path", f.toString()));
        assertThat(r.success()).isTrue();
        assertThat(Files.readString(f)).isEmpty();
    }
}

