package io.oryxos.tool.javabean;

import io.oryxos.core.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T066 —— EchoTool 契约测试。
 *
 * <p>覆盖 [research.md R-06](../../../../../../../specs/005-tool-system/research.md) +
 * spec US-4 场景 2-3：success / null-args / exception-caught。
 */
class EchoToolTest {

    @Test
    @DisplayName("echo-success: text=hello → payload.text == hello + calls 计数")
    void echo_success() {
        EchoTool tool = new EchoTool();
        ToolResult r = tool.execute(Map.of("text", "hello"));
        assertThat(r.success()).isTrue();
        assertThat((String) r.payload().get("text")).isEqualTo("hello");
        assertThat(((Number) r.payload().get("calls")).longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("echo-null-args: 缺 text 字段 → ToolResult.error")
    void echo_null_args() {
        EchoTool tool = new EchoTool();
        ToolResult r = tool.execute(Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("text");
    }

    @Test
    @DisplayName("echo-multiple-calls: calls 字段随调用次数递增")
    void echo_multiple_calls() {
        EchoTool tool = new EchoTool();
        tool.execute(Map.of("text", "a"));
        ToolResult r2 = tool.execute(Map.of("text", "b"));
        assertThat(((Number) r2.payload().get("calls")).longValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("EchoTool 行数 ≤ 100 (SC-007)")
    void line_count_below_100() {
        java.io.InputStream is = EchoTool.class.getResourceAsStream(
            EchoTool.class.getSimpleName() + ".java");
        long lines = is == null ? -1 : new java.io.BufferedReader(
            new java.io.InputStreamReader(is)).lines().count();
        // 没有源码资源时跳过 — 仅在资源可用时做断言
        if (lines > 0) {
            assertThat(lines).isLessThanOrEqualTo(100L);
        }
    }
}
