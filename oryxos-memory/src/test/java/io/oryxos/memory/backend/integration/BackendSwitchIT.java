package io.oryxos.memory.backend.integration;

import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T036 第一步（006-memory-layer Phase 5 / US-3）—— markdown 后端写入 3 条 entry。
 *
 * <p>位于 {@code integration} 子包 —— 真实文件系统集成测试（不用 @DataJpaTest）。
 *
 * <p>完整三步走（SC-004 0 业务中断）：
 * <ol>
 *   <li>markdown 后端写 3 条 → MEMORY.md 含 ## Core / ## Archive + 3 行 entry（本测试）</li>
 *   <li>迁移 → SQLite agent_memories 含 3 行（见 {@link MarkdownToSqliteMigrationIT}）</li>
 *   <li>切到 mem0 + WireMock → save 成功 + memory_index 落 1 行（见 {@link SwitchToMem0IT}）</li>
 * </ol>
 */
class BackendSwitchIT {

    @Test
    @DisplayName("SC-004 markdown 后端：写 3 条 → 文件含 3 行 entry")
    void markdown_backend_writes_three_entries() throws IOException {
        Path tmp = Files.createTempDirectory("oryxos-bsw-");
        try {
            Path memoryFile = tmp.resolve("MEMORY.md");
            MarkdownMemoryStore store = new MarkdownMemoryStore(memoryFile);

            store.save(MemoryScope.CORE, "fact one", List.of("a"));
            store.save(MemoryScope.CORE, "fact two", List.of("b"));
            store.save(MemoryScope.ARCHIVE, "archived fact", List.of());

            String content = Files.readString(memoryFile);
            assertThat(content).contains("# MEMORY");
            assertThat(content).contains("## Core");
            assertThat(content).contains("## Archive");
            assertThat(content).contains("fact one");
            assertThat(content).contains("fact two");
            assertThat(content).contains("archived fact");
        } finally {
            deleteRecursively(tmp);
        }
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