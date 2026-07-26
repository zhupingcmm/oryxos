package io.oryxos.tool.shell;

import io.oryxos.core.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T016：{@code shell} —— 安全命令 + 黑名单命中 + 超时 + 命令不存在（[contracts/builtin-tools.md §4]）。 */
class ShellToolTest {

    ShellToolProperties props;
    ShellTool tool;

    @BeforeEach
    void setUp() {
        props = new ShellToolProperties(30, 1024, List.of("rm", "shutdown", "mkfs"));
        tool = new ShellTool(props, action -> { });
    }

    @Test
    @DisplayName("成功：echo 'hi' → exit_code=0, stdout='hi'")
    void echo_returns_stdout() {
        ToolResult r = tool.execute(Map.of("command", isWindows() ? "echo hi" : "echo hi"));
        assertThat(r.success()).isTrue();
        assertThat((String) r.payload().get("stdout")).contains("hi");
        assertThat(((Number) r.payload().get("exit_code")).intValue()).isZero();
    }

    @Test
    @DisplayName("失败：'rm' 命中黑名单 → 'shell command blocked'")
    void rm_blocked() {
        ToolResult r = tool.execute(Map.of("command", "rm -rf /tmp/nothing"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("shell command blocked").contains("rm");
    }

    @Test
    @DisplayName("失败：'shutdown' 命中黑名单")
    void shutdown_blocked() {
        ToolResult r = tool.execute(Map.of("command", "shutdown now"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("shell command blocked").contains("shutdown");
    }

    @Test
    @DisplayName("失败：timeout_seconds=1 + 长睡 → 超时")
    void timeout() {
        ToolResult r = tool.execute(Map.of(
            "command", isWindows() ? "ping -n 3 127.0.0.1" : "sleep 2",
            "timeout_seconds", 1
        ));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("timeout");
    }

    @Test
    @DisplayName("失败：未知命令 → 'command not found'")
    void command_not_found() {
        ToolResult r = tool.execute(Map.of(
            "command", "definitely-does-not-exist-xyz-12345"
        ));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("not found");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}

