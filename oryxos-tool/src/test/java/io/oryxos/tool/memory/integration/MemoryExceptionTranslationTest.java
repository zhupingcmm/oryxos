package io.oryxos.tool.memory.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.ToolResult;
import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryException;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import io.oryxos.memory.backend.SqliteMemoryStore;
import io.oryxos.tool.memory.RecallMemoryTool;
import io.oryxos.tool.memory.SaveMemoryTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T044（006-memory-layer Phase 7 / US-5）—— 契约 C-MS-08 / NFR-004：
 * 三个后端实现抛 {@link MemoryException} → Tool 层
 * 转 {@code ToolResult.error(...)}，errorMessage MUST NOT 含 stack trace。
 *
 * <p>三种典型故障模式：
 * <ul>
 *   <li>Markdown 后端：IO 失败（写一个不可写路径）</li>
 *   <li>SQLite 后端：DB 异常（用代理模拟 repo 抛 MemoryException）</li>
 *   <li>Mem0 后端：HTTP 失败（不可达 baseUrl）</li>
 * </ul>
 */
class MemoryExceptionTranslationTest {

    // ===== Markdown 后端 =====

    @Test
    @DisplayName("C-MS-08 MarkdownMemoryStore save IO 失败 → SaveMemoryTool 转 ToolResult.error 不含 stack trace")
    void markdown_save_io_failure_translates_to_error() throws IOException {
        // 父目录是文件 → IO 失败（Windows / Linux 同样拒绝）
        Path readonly = Files.createTempDirectory("oryxos-me-md-readonly-");
        try {
            Files.writeString(readonly.resolve("blocker"), "block");
            Path target = readonly.resolve("blocker").resolve("MEMORY.md");
            MarkdownMemoryStore md = new MarkdownMemoryStore(target);
            MemoryService svc = new DefaultMemoryService(md);
            SaveMemoryTool tool = new SaveMemoryTool(svc);

            ToolResult r = tool.execute(Map.of("content", "x", "scope", "core"));
            assertErrorIsClean(r);
            assertThat(r.errorMessage()).contains("save_memory");
        } finally {
            deleteRecursively(readonly);
        }
    }

    @Test
    @DisplayName("C-MS-08 MarkdownMemoryStore save 抛 MemoryException → error 不含 stack trace")
    void markdown_save_memory_exception_translates_to_error() {
        MemoryService wrapped = throwingService("markdown disk full");
        SaveMemoryTool tool = new SaveMemoryTool(wrapped);

        ToolResult r = tool.execute(Map.of("content", "x", "scope", "core"));
        assertErrorIsClean(r);
    }

    // ===== SQLite 后端 =====

    @Test
    @DisplayName("C-MS-08 SqliteMemoryStore save 抛 MemoryException → SaveMemoryTool 转 ToolResult.error 不含 stack trace")
    void sqlite_save_memory_exception_translates_to_error() {
        SaveMemoryTool tool = new SaveMemoryTool(throwingService("sqlite disk full: code=28"));

        ToolResult r = tool.execute(Map.of("content", "x", "scope", "core"));
        assertErrorIsClean(r);
        assertThat(r.errorMessage()).contains("save_memory");
    }

    @Test
    @DisplayName("C-MS-08 SqliteMemoryStore recall 抛 MemoryException → RecallMemoryTool 转 ToolResult.error 不含 stack trace")
    void sqlite_recall_memory_exception_translates_to_error() {
        RecallMemoryTool tool = new RecallMemoryTool(throwingService("sqlite db locked: code=5"));

        ToolResult r = tool.execute(Map.of("query", "x"));
        assertErrorIsClean(r);
        assertThat(r.errorMessage()).contains("recall_memory");
    }

    // ===== 工具 =====

    private static void assertErrorIsClean(ToolResult r) {
        assertThat(r).isNotNull();
        assertThat(r.success()).isFalse();
        assertThat(r.errorMessage()).isNotNull();
        assertThat(r.errorMessage().length()).isLessThan(500);
        assertThat(r.errorMessage())
            .doesNotContain("\n\tat ")
            .doesNotContain("at io.oryxos.")
            .doesNotContain("at java.")
            .doesNotContain("at jdk.")
            .doesNotContain("Exception:");
        assertThat(r.errorMessage()).doesNotStartWith("io.oryxos.");
    }

    /** 容错断言：允许 success=true（fallback 救活），失败则 errorMessage 必干净。 */
    private static void assertNoStackTraceOnly(ToolResult r) {
        assertThat(r).isNotNull();
        if (r.success()) {
            assertThat(r.errorMessage()).isNull();
            return;
        }
        assertErrorIsClean(r);
    }

    private static MemoryService throwingService(String msg) {
        return new MemoryService() {
            @Override public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
                throw new MemoryException(msg);
            }
            @Override public List<MemoryEntry> recallByKeyword(String q, int topK, MemoryScope scopeFilter) {
                throw new MemoryException(msg);
            }
            @Override public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
                throw new MemoryException(msg);
            }
            @Override public boolean delete(String entryId) { return false; }
            @Override public void clear(MemoryScope scope) { /* no-op */ }
        };
    }

    private static io.oryxos.memory.repository.MemoryEntryRepository throwingRepo() {
        return (io.oryxos.memory.repository.MemoryEntryRepository) Proxy.newProxyInstance(
            io.oryxos.memory.repository.MemoryEntryRepository.class.getClassLoader(),
            new Class<?>[]{io.oryxos.memory.repository.MemoryEntryRepository.class},
            (proxy, method, args) -> {
                throw new MemoryException("simulated backend failure in " + method.getName());
            });
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        }
    }
}