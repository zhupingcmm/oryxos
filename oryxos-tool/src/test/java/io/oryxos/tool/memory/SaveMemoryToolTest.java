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

/**
 * T042（Phase 7 复用）+ 006-memory-layer 契约回归 —— {@code save_memory} Tool：
 * <ol>
 *   <li>save 成功 + scope 默认 core</li>
 *   <li>scope=archive</li>
 *   <li>非法 scope 报错</li>
 *   <li>缺 content 报错</li>
 *   <li>MemoryService 抛异常 → ToolResult.error 不含 stack trace（C-MS-08 / NFR-004）</li>
 * </ol>
 */
class SaveMemoryToolTest {

    MemoryService mockService;
    SaveMemoryTool tool;

    @BeforeEach
    void setUp() {
        mockService = new InMemoryMock();
        tool = new SaveMemoryTool(mockService);
    }

    @Test
    @DisplayName("成功：scope 缺省 → MemoryService.save 收到 CORE")
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
    @DisplayName("成功：scope='core' + tags=['preference','shortcut'] → MemoryService.save 收到 tags")
    void save_with_tags() {
        ToolResult r = tool.execute(Map.of(
            "content", "x",
            "scope", "core",
            "tags", List.of("preference", "shortcut")));
        assertThat(r.success()).isTrue();
        InMemoryMock mock = (InMemoryMock) mockService;
        assertThat(mock.lastTags).containsExactly("preference", "shortcut");
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

    /** In-memory fake —— 实现 006-memory-layer 5-方法契约。 */
    static class InMemoryMock implements MemoryService {
        final List<MemoryEntry> entries = new ArrayList<>();
        List<String> lastTags;
        @Override public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
            MemoryEntry e = new MemoryEntry(content, scope, Instant.now());
            entries.add(e);
            lastTags = tags;
            return e;
        }
        @Override public List<MemoryEntry> recallByKeyword(String q, int topK, MemoryScope scopeFilter) {
            return entries.stream()
                .filter(e -> scopeFilter == null || e.scope() == scopeFilter)
                .filter(e -> e.content().contains(q))
                .limit(Math.max(1, topK))
                .toList();
        }
        @Override public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
            return entries.stream().filter(e -> e.scope() == scope).limit(topK).toList();
        }
        @Override public boolean delete(String entryId) { return entries.removeIf(e -> entryId.equals(e.id())); }
        @Override public void clear(MemoryScope scope) { entries.removeIf(e -> e.scope() == scope); }
    }

    /** 抛运行时异常的 stub —— 验证 Tool 层捕获并转 error。 */
    static class ThrowingMemoryService implements MemoryService {
        @Override public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
            throw new RuntimeException("disk full");
        }
        @Override public List<MemoryEntry> recallByKeyword(String q, int topK, MemoryScope scopeFilter) {
            return List.of();
        }
        @Override public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) { return List.of(); }
        @Override public boolean delete(String entryId) { return false; }
        @Override public void clear(MemoryScope scope) { /* no-op */ }
    }
}