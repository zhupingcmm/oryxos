package io.oryxos.tool.memory;

import io.oryxos.core.ToolResult;
import io.oryxos.memory.MemoryException;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T055（006-memory-layer Phase 8 / Polish）—— NFR-004 / SC-006 复测：
 * Tool errorMessage 0% 含 stack trace。
 *
 * <p>对 {@code SaveMemoryTool} + {@code RecallMemoryTool} 各种失败场景，
 * 断言 errorMessage 不含 Java stack trace 标记（{@code at io.oryxos.*}、
 * {@code Exception:}、{@code at java.*}、{@code at jdk.*}、{@code .printStackTrace()}）。
 */
class MemoryToolErrorMessageTest {

    private static final Pattern STACK_TRACE_PATTERN = Pattern.compile(
        "(?:\\sat\\s+[\\w.$]+\\.[\\w$.]+\\([^)]*\\)"  // "at io.foo.bar(File.java:123)"
        + "|Exception:"                                 // "java.lang.RuntimeException:"
        + "|\\.printStackTrace\\(\\)"
        + ")"
    );

    private SaveMemoryTool save;
    private RecallMemoryTool recall;

    @BeforeEach
    void setUp() {
        save = new SaveMemoryTool(throwingService());
        recall = new RecallMemoryTool(throwingService());
    }

    @Test
    @DisplayName("NFR-004: save_memory 失败 → errorMessage 不含 stack trace")
    void save_error_no_stack_trace() {
        ToolResult r = save.execute(Map.of("content", "x", "scope", "core"));
        assertThat(r.success()).isFalse();
        assertNoStackTrace(r.errorMessage());
        assertThat(r.errorMessage()).contains("save_memory");
    }

    @Test
    @DisplayName("NFR-004: recall_memory 失败 → errorMessage 不含 stack trace")
    void recall_error_no_stack_trace() {
        ToolResult r = recall.execute(Map.of("query", "x"));
        assertThat(r.success()).isFalse();
        assertNoStackTrace(r.errorMessage());
        assertThat(r.errorMessage()).contains("recall_memory");
    }

    @Test
    @DisplayName("NFR-004: save_memory 非法 scope → errorMessage 不含 stack trace（这是参数错误而非 IO 错误）")
    void save_invalid_scope_no_stack_trace() {
        ToolResult r = save.execute(Map.of("content", "x", "scope", "garbage"));
        assertThat(r.success()).isFalse();
        assertNoStackTrace(r.errorMessage());
        assertThat(r.errorMessage()).contains("invalid scope");
    }

    @Test
    @DisplayName("NFR-004: recall_memory 非法 scope → errorMessage 不含 stack trace")
    void recall_invalid_scope_no_stack_trace() {
        ToolResult r = recall.execute(Map.of("query", "x", "scope", "garbage"));
        assertThat(r.success()).isFalse();
        assertNoStackTrace(r.errorMessage());
        assertThat(r.errorMessage()).contains("invalid scope");
    }

    @Test
    @DisplayName("NFR-004: save_memory 缺 content → errorMessage 不含 stack trace")
    void save_missing_content_no_stack_trace() {
        ToolResult r = save.execute(Map.of());
        assertThat(r.success()).isFalse();
        assertNoStackTrace(r.errorMessage());
    }

    @Test
    @DisplayName("NFR-004: recall_memory 缺 query → errorMessage 不含 stack trace")
    void recall_missing_query_no_stack_trace() {
        ToolResult r = recall.execute(Map.of());
        assertThat(r.success()).isFalse();
        assertNoStackTrace(r.errorMessage());
    }

    // ===== helpers =====

    private static void assertNoStackTrace(String msg) {
        assertThat(msg).isNotNull();
        assertThat(msg.length())
            .as("errorMessage should be short, was: %s", msg)
            .isLessThan(300);
        assertThat(STACK_TRACE_PATTERN.matcher(msg).find())
            .as("errorMessage must not match stack trace pattern: %s", msg)
            .isFalse();
        assertThat(msg)
            .doesNotContain("\n\tat ")
            .doesNotContain("Caused by:");
    }

    private static MemoryService throwingService() {
        return new MemoryService() {
            @Override public io.oryxos.memory.MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
                throw new MemoryException("simulated backend failure");
            }
            @Override public List<io.oryxos.memory.MemoryEntry> recallByKeyword(String q, int topK, MemoryScope scopeFilter) {
                throw new MemoryException("simulated backend failure");
            }
            @Override public List<io.oryxos.memory.MemoryEntry> recallByScope(MemoryScope scope, int topK) {
                throw new MemoryException("simulated backend failure");
            }
            @Override public boolean delete(String entryId) { return false; }
            @Override public void clear(MemoryScope scope) { /* no-op */ }
        };
    }
}