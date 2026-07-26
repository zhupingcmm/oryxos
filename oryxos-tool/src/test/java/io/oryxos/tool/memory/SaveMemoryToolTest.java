package io.oryxos.tool.memory;

import io.oryxos.core.ToolResult;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T019：{@code save_memory} —— save 成功 + scope 校验 + MemoryService 失败。 */
class SaveMemoryToolTest {

    MemoryService mockService;
    SaveMemoryTool tool;

    @BeforeEach
    void setUp() {
        mockService = new InMemoryMock();
        tool = new SaveMemoryTool(mockService);
    }

    @Test
    @DisplayName("成功：默认 scope=core → MemoryService.save 收到 CORE")
    void save_success_default_scope() {
        ToolResult r = tool.execute(Map.of("content", "user prefers markdown"));
        assertThat(r.success()).isTrue();
        assertThat((String) r.payload().get("scope")).isEqualTo("core");
        assertThat(((Number) r.payload().get("entry_count")).intValue()).isOne();
        assertThat((String) r.payload().get("operation")).isEqualTo("save");
    }

    @Test
    @DisplayName("成功：scope='archive' → MemoryService.save 收到 ARCHIVE")
    void save_archive() {
        ToolResult r = tool.execute(Map.of("content", "2024-01 archive", "scope", "archive"));
        assertThat(r.success()).isTrue();
        assertThat((String) r.payload().get("scope")).isEqualTo("archive");
    }

    @Test
    @DisplayName("失败：scope='invalid' → errorMessage 含 'invalid scope'")
    void invalid_scope() {
        ToolResult r = tool.execute(Map.of("content", "x", "scope", "garbage"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("invalid scope");
    }

    @Test
    @DisplayName("失败：content 空白 → errorMessage 含 'missing required argument content'")
    void missing_content() {
        ToolResult r = tool.execute(Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("missing required argument 'content'");
    }

    @Test
    @DisplayName("失败：MemoryService 抛异常 → errorMessage 不含 stack trace")
    void memory_service_failure() {
        tool = new SaveMemoryTool(new ThrowingMemoryService());
        ToolResult r = tool.execute(Map.of("content", "x"));
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).doesNotContain("\n", "at io.oryxos");
    }

    /** In-memory fake，仅供本测试。 */
    private static class InMemoryMock implements MemoryService {
        final List<MemoryEntry> entries = new ArrayList<>();
        @Override public MemoryEntry save(String content, MemoryScope scope) {
            MemoryEntry e = new MemoryEntry(content, scope, Instant.now());
            entries.add(e);
            return e;
        }
        @Override public List<MemoryEntry> recallByKeyword(String q, int topK) {
            return entries.stream()
                .filter(e -> e.content().contains(q))
                .limit(topK > 0 ? topK : 1)
                .toList();
        }
    }

    /** 抛运行时异常的 stub —— 验证 Tool 层捕获并转 error。 */
    private static class ThrowingMemoryService implements MemoryService {
        @Override public MemoryEntry save(String content, MemoryScope scope) {
            throw new RuntimeException("disk full");
        }
        @Override public List<MemoryEntry> recallByKeyword(String q, int topK) { return List.of(); }
    }
}

