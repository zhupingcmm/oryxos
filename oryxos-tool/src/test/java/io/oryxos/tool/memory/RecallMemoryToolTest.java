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

/** T020：{@code recall_memory} —— 关键词命中 + 0 命中 + top_k 上限。 */
class RecallMemoryToolTest {

    MemoryService mock;
    RecallMemoryTool tool;

    @BeforeEach
    void setUp() {
        mock = new FakeMemoryService();
        tool = new RecallMemoryTool(mock);
    }

    @Test
    @DisplayName("成功：关键词命中 → snippets 含条目片段")
    void recall_keyword_match() {
        ((FakeMemoryService) mock).seed("user likes markdown",
            "weather is sunny today", "github stars increased");
        ToolResult r = tool.execute(Map.of("query", "markdown", "top_k", 3));
        assertThat(r.success()).isTrue();
        assertThat(((Number) r.payload().get("entry_count")).intValue()).isOne();
        @SuppressWarnings("unchecked")
        List<String> snippets = (List<String>) r.payload().get("snippets");
        assertThat(snippets.get(0)).contains("markdown");
    }

    @Test
    @DisplayName("成功：0 命中 → entry_count=0 + 空 snippets")
    void no_hits() {
        ((FakeMemoryService) mock).seed("foo", "bar");
        ToolResult r = tool.execute(Map.of("query", "missing-keyword"));
        assertThat(r.success()).isTrue();
        assertThat(((Number) r.payload().get("entry_count")).intValue()).isZero();
        @SuppressWarnings("unchecked")
        List<String> snippets = (List<String>) r.payload().get("snippets");
        assertThat(snippets).isEmpty();
    }

    @Test
    @DisplayName("成功：top_k 限制命中数")
    void top_k_limit() {
        FakeMemoryService fm = (FakeMemoryService) mock;
        for (int i = 0; i < 20; i++) fm.seed("matching " + i);
        ToolResult r = tool.execute(Map.of("query", "matching", "top_k", 5));
        assertThat(r.success()).isTrue();
        assertThat(((Number) r.payload().get("entry_count")).intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("失败：query 空白 → errorMessage 含 'missing required argument query'")
    void missing_query() {
        ToolResult r = tool.execute(Map.of());
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).contains("missing required argument 'query'");
    }

    private static class FakeMemoryService implements MemoryService {
        final List<MemoryEntry> entries = new ArrayList<>();
        void seed(String... contents) {
            for (String c : contents) entries.add(new MemoryEntry(c, MemoryScope.CORE, Instant.now()));
        }
        @Override public MemoryEntry save(String content, MemoryScope scope) { return null; }
        @Override public List<MemoryEntry> recallByKeyword(String q, int topK) {
            return entries.stream()
                .filter(e -> e.content().contains(q))
                .limit(Math.max(1, topK))
                .toList();
        }
    }
}

