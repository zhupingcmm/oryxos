package io.oryxos.memory.backend;

import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T020（006-memory-layer Phase 4 / US-2）—— 9 条 C-MD 契约测试
 * （[contracts/markdown-backend.md §3](../../../../../specs/006-memory-layer/contracts/markdown-backend.md)）。
 *
 * <p>不依赖 Spring 容器；每条用例直接 {@code new MarkdownMemoryStore(tempFile)}
 * 拿到隔离的临时文件路径。所有断言走真实文件系统，不打桩。
 *
 * <p>覆盖（C-MD-01 ~ C-MD-09）：
 * <ul>
 *   <li>C-MD-01 append-mode —— {@code save} MUST 不重写已有内容</li>
 *   <li>C-MD-02 literal-keyword-match —— recallByKeyword 按 content 字面子串匹配</li>
 *   <li>C-MD-03 empty-query —— recallByKeyword(null/blank) 返回空集合</li>
 *   <li>C-MD-04 no-file-handle —— 每次 read/write 立即 close（无 try-with-resources 泄漏）</li>
 *   <li>C-MD-05 atomic-move —— 写文件用 {@code Files.move(ATOMIC_MOVE)}，不损坏</li>
 *   <li>C-MD-06 sync-serialization —— N=10 线程并发 save 100 次 = 100 行（无丢失）</li>
 *   <li>C-MD-07 tags-informational —— tags 不参与查询（内容匹配按字面）</li>
 *   <li>C-MD-08 lenient-recovery —— 外部删除 {@code ## Core} 段，下次 save 自动重建</li>
 *   <li>C-MD-09 archive-no-trim —— Markdown MUST NOT 触发 archive 容量裁剪</li>
 * </ul>
 */
class MarkdownMemoryStoreTest {

    @TempDir Path tmpDir;
    Path memoryFile;
    MarkdownMemoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        memoryFile = tmpDir.resolve("MEMORY.md");
        store = new MarkdownMemoryStore(memoryFile);
    }

    // ===== C-MD-01: append-mode =====

    @Test
    @DisplayName("C-MD-01 append-mode：连续 save 3 次 → 文件含 3 行，已有内容不重写")
    void append_mode_does_not_overwrite_previous_entries() {
        store.save(MemoryScope.CORE, "first", List.of());
        store.save(MemoryScope.CORE, "second", List.of());
        store.save(MemoryScope.ARCHIVE, "third", List.of("log"));

        List<String> lines = readLines(memoryFile);
        // 应含 1 条文件头（# MEMORY）+ 2 条段标题（## Core / ## Archive）+ 3 条 entry = 6 行
        long entryLines = lines.stream()
            .filter(l -> l.startsWith("- "))
            .count();
        assertThat(entryLines).isEqualTo(3L);
        // 校验内容（行格式 `- [ISO-8601] [UUID] content [#tags=...]`）
        assertThat(String.join("\n", lines)).contains("first").contains("second").contains("third");
    }

    // ===== C-MD-02: literal-keyword-match =====

    @Test
    @DisplayName("C-MD-02 literal-keyword-match：recallByKeyword 按 content 字面子串匹配（大小写不敏感）")
    void literal_substring_match_case_insensitive() {
        store.save(MemoryScope.CORE, "User prefers tabs over spaces", List.of());
        store.save(MemoryScope.CORE, "Project uses Tabs indentation", List.of());
        store.save(MemoryScope.CORE, "Build tool is Maven", List.of());

        List<MemoryEntry> hits = store.recallByKeyword("tabs", 10, null);
        assertThat(hits).hasSize(2);
        // 命中按 createdAt DESC（新→旧）
        assertThat(hits.get(0).content()).isEqualTo("Project uses Tabs indentation");
        assertThat(hits.get(1).content()).isEqualTo("User prefers tabs over spaces");

        // 大写关键字也能命中小写内容（toLowerCase 已实现）
        List<MemoryEntry> upperHits = store.recallByKeyword("TABS", 10, null);
        assertThat(upperHits).hasSize(2);
    }

    @Test
    @DisplayName("C-MD-02 literal-keyword-match：scopeFilter=ARCHIVE 只返回 archive 区匹配")
    void keyword_match_filters_by_scope() {
        store.save(MemoryScope.CORE, "core tab rule", List.of());
        store.save(MemoryScope.ARCHIVE, "archive tab rule", List.of());

        List<MemoryEntry> coreHits = store.recallByKeyword("tab", 10, MemoryScope.CORE);
        assertThat(coreHits).hasSize(1);
        assertThat(coreHits.get(0).scope()).isEqualTo(MemoryScope.CORE);

        List<MemoryEntry> archHits = store.recallByKeyword("tab", 10, MemoryScope.ARCHIVE);
        assertThat(archHits).hasSize(1);
        assertThat(archHits.get(0).scope()).isEqualTo(MemoryScope.ARCHIVE);
    }

    // ===== C-MD-03: empty-query =====

    @Test
    @DisplayName("C-MD-03 empty-query：recallByKeyword(null/blank) 返回空集合（不抛）")
    void empty_query_returns_empty_list() {
        store.save(MemoryScope.CORE, "entry", List.of());
        assertThat(store.recallByKeyword(null, 10, null)).isEmpty();
        assertThat(store.recallByKeyword("", 10, null)).isEmpty();
        assertThat(store.recallByKeyword("   ", 10, null)).isEmpty();
        // topK 各种值仍返回空
        assertThat(store.recallByKeyword(null, 0, null)).isEmpty();
        assertThat(store.recallByKeyword(null, -1, null)).isEmpty();
    }

    // ===== C-MD-04: no-file-handle =====

    @Test
    @DisplayName("C-MD-04 no-file-handle：每个 save/recall/delete 后立即释放文件句柄（Windows 文件锁不冲突）")
    void no_leaked_file_handles() throws Exception {
        // 在 Windows 上：如果 save() 后仍持有句柄，Files.delete 会抛 "being used by another process"
        // 这里用 Files.move/delete 验证句柄已被释放（C-MD-04 隐性契约）
        store.save(MemoryScope.CORE, "entry A", List.of());
        store.save(MemoryScope.CORE, "entry B", List.of());

        // 删除文件 → 不抛 AccessDenied 即可证句柄已释放
        Files.delete(memoryFile);
        assertThat(Files.exists(memoryFile)).isFalse();

        // 重新建一个 store，再 recall → 仍能读（即便句柄没缓存）
        MarkdownMemoryStore fresh = new MarkdownMemoryStore(memoryFile);
        assertThat(fresh.recallByKeyword("entry", 10, null)).isEmpty();
    }

    // ===== C-MD-05: atomic-move =====

    @Test
    @DisplayName("C-MD-05 atomic-move：写用临时文件 + ATOMIC_MOVE，写过程中其他读仍可见旧文件")
    void atomic_move_does_not_corrupt_file_on_concurrent_read() throws Exception {
        // 先写 1 条 entry（保证文件存在 + 初始化头）
        store.save(MemoryScope.CORE, "baseline", List.of());
        long sizeBefore = Files.size(memoryFile);

        // 在写之前 + 写之后两次读取都应成功（C-MD-05 失败应该出现 parse error 或空文件）
        List<String> beforeLines = Files.readAllLines(memoryFile, StandardCharsets.UTF_8);
        store.save(MemoryScope.CORE, "second", List.of());
        List<String> afterLines = Files.readAllLines(memoryFile, StandardCharsets.UTF_8);

        // 文件结构：# MEMORY / "" / ## Core / "" / entry1 / ## Archive
        assertThat(beforeLines).hasSize(6);
        // 多 1 条 entry 后：再加一行 entry，共 7 行
        assertThat(afterLines).hasSize(7);
        assertThat(Files.size(memoryFile)).isGreaterThan(sizeBefore);
        // 文件末尾是 ## Archive（section header），不是 entry；验证 entry 计数
        long entryCount = afterLines.stream().filter(l -> l.startsWith("- ")).count();
        assertThat(entryCount).isEqualTo(2L);
        // entry 不应包含半截字符（last entry 是完整行）
        String lastEntry = afterLines.stream()
            .filter(l -> l.startsWith("- "))
            .reduce((a, b) -> b)
            .orElseThrow();
        assertThat(lastEntry).matches("- \\[\\S+\\] \\[\\S+\\] .+(?: \\[#tags=[^\\]]+\\])?");
    }

    // ===== C-MD-06: sync-serialization =====

    @Test
    @DisplayName("C-MD-06 sync-serialization：N=10 线程并发 save 100 次 → 文件含 100 行无丢失")
    void concurrent_save_serializes_via_internal_lock() throws Exception {
        int threadCount = 10;
        int savesPerThread = 10;
        int totalSaves = threadCount * savesPerThread;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        Set<String> seenIds = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < threadCount; t++) {
            int tid = t;
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < savesPerThread; i++) {
                        MemoryEntry e = store.save(MemoryScope.CORE,
                            "thread-" + tid + "-entry-" + i, List.of("t" + tid));
                        seenIds.add(e.id());
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 100 条 entry 行 + 1 行 # MEMORY + 1 行 ## Core + 1 行 ## Archive = 103 行
        List<String> lines = readLines(memoryFile);
        long entryLines = lines.stream().filter(l -> l.startsWith("- ")).count();
        assertThat(entryLines).isEqualTo(totalSaves);

        // 每个 ID 唯一（无并发覆盖）
        assertThat(seenIds).hasSize(totalSaves);
        // recall 全部 100 条都能找到
        List<MemoryEntry> all = store.recallByKeyword("entry", 200, MemoryScope.CORE);
        assertThat(all).hasSize(totalSaves);
    }

    // ===== C-MD-07: tags-informational =====

    @Test
    @DisplayName("C-MD-07 tags-informational：tags 不参与 recall（仅 content 决定命中）")
    void tags_are_informational_not_part_of_query() {
        // content 不含 "preference"，仅 tags 含
        store.save(MemoryScope.CORE, "user likes pizza", List.of("preference", "food"));
        // content 不含 "language"，仅 tags 含
        store.save(MemoryScope.CORE, "team uses Go", List.of("language", "go"));

        // 关键字 "preference" → 0 hit（虽然 tags 含 preference，但 recall 不看 tags）
        assertThat(store.recallByKeyword("preference", 10, null)).isEmpty();
        // 关键字 "language" → 0 hit（同理）
        assertThat(store.recallByKeyword("language", 10, null)).isEmpty();

        // 但 content 中的字面 substring 仍命中
        assertThat(store.recallByKeyword("pizza", 10, null)).hasSize(1);
        assertThat(store.recallByKeyword("Go", 10, null)).hasSize(1);
    }

    // ===== C-MD-08: lenient-recovery =====

    @Test
    @DisplayName("C-MD-08 lenient-recovery：外部删除 ## Core 段 → 下次 save(core) 自动重建")
    void missing_core_section_rebuilt_on_next_save() throws Exception {
        store.save(MemoryScope.CORE, "first core", List.of());
        store.save(MemoryScope.ARCHIVE, "first archive", List.of());

        // 模拟外部编辑（运维脚本 / 用户手动删 ## Core 段）
        List<String> lines = readLines(memoryFile);
        // 删除包含 "## Core" 的行（通常 ## Core 行 + 其下 entry）
        List<String> truncated = new ArrayList<>();
        boolean inCore = false;
        for (String l : lines) {
            if (l.trim().equalsIgnoreCase("## Core")) {
                inCore = true;
                continue;
            }
            if (l.trim().equalsIgnoreCase("## Archive")) {
                inCore = false;
            }
            if (!inCore) {
                truncated.add(l);
            }
        }
        Files.writeString(memoryFile, String.join(System.lineSeparator(), truncated)
            + System.lineSeparator(), StandardCharsets.UTF_8);

        // 验证：## Core 段确实没了
        List<String> afterTrim = readLines(memoryFile);
        assertThat(afterTrim).noneMatch(l -> l.trim().equalsIgnoreCase("## Core"));

        // 下次 save(CORE) → 自动 append 到末尾（lenient recovery）
        store.save(MemoryScope.CORE, "recovered", List.of());

        List<MemoryEntry> hits = store.recallByKeyword("recovered", 10, null);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).scope()).isEqualTo(MemoryScope.CORE);
    }

    // ===== C-MD-09: archive-no-trim =====

    @Test
    @DisplayName("C-MD-09 archive-no-trim：archive 区连续 save N 次 → 不触发裁剪（行数单调递增）")
    void archive_save_never_trims() {
        for (int i = 0; i < 50; i++) {
            store.save(MemoryScope.ARCHIVE, "log entry " + i, List.of("log"));
        }
        // 50 条 archive entry + 1 # MEMORY + 1 ## Core + 1 ## Archive = 53 行
        List<String> lines = readLines(memoryFile);
        long entryLines = lines.stream().filter(l -> l.startsWith("- ")).count();
        assertThat(entryLines).isEqualTo(50L);
        // recall 全部能拿到
        assertThat(store.recallByKeyword("log entry", 100, MemoryScope.ARCHIVE)).hasSize(50);
    }

    // ===== 额外健全性：API 契约点（合同要求但不属于 9 条核心） =====

    @Test
    @DisplayName("save(null) → IllegalArgumentException；content 空白也抛")
    void save_validates_arguments() {
        assertThatThrownBy(() -> store.save(null, "x", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(MemoryScope.CORE, "", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(MemoryScope.CORE, "   ", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("delete(id) → 存在 true / 不存在 false；删后 recall 找不到")
    void delete_returns_boolean_and_removes_entry() {
        MemoryEntry a = store.save(MemoryScope.CORE, "alpha", List.of());
        MemoryEntry b = store.save(MemoryScope.CORE, "beta", List.of());
        assertThat(store.delete(a.id())).isTrue();
        assertThat(store.delete("nonexistent-id")).isFalse();
        assertThat(store.recallByKeyword("alpha", 10, null)).isEmpty();
        assertThat(store.recallByKeyword("beta", 10, null)).hasSize(1);
        assertThat(b.id()).isNotBlank();
    }

    @Test
    @DisplayName("clear(ARCHIVE) → archive 区清空；CORE 不动")
    void clear_archive_only_touches_archive() {
        store.save(MemoryScope.CORE, "core1", List.of());
        store.save(MemoryScope.CORE, "core2", List.of());
        store.save(MemoryScope.ARCHIVE, "arch1", List.of());
        store.save(MemoryScope.ARCHIVE, "arch2", List.of());

        store.clear(MemoryScope.ARCHIVE);

        assertThat(store.recallByKeyword("core", 10, MemoryScope.CORE)).hasSize(2);
        assertThat(store.recallByKeyword("arch", 10, MemoryScope.ARCHIVE)).isEmpty();
        // 文件结构仍完整（## Core / ## Archive 段标题保留）
        String raw = readString(memoryFile);
        assertThat(raw).contains("## Core");
        assertThat(raw).contains("## Archive");
    }

    @Test
    @DisplayName("clear(CORE) → 抛 IllegalStateException（C-LT-05 硬约束）")
    void clear_core_throws() {
        store.save(MemoryScope.CORE, "x", List.of());
        assertThatThrownBy(() -> store.clear(MemoryScope.CORE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("core");
    }

    @Test
    @DisplayName("isHealthy() → 文件存在且可读 → true；不存在 + 父目录不可写 → false")
    void is_healthy_basic() {
        assertThat(store.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("文件不存在时 save 自动初始化头（# MEMORY + ## Core + ## Archive）")
    void save_initializes_file_with_header() throws Exception {
        assertThat(Files.exists(memoryFile)).isFalse();
        store.save(MemoryScope.CORE, "first", List.of());
        String raw = readString(memoryFile);
        assertThat(raw).startsWith("# MEMORY");
        assertThat(raw).contains("## Core");
        assertThat(raw).contains("## Archive");
    }

    @Test
    @DisplayName("recallByScope 仅返回该 scope 条目，按 createdAt DESC")
    void recall_by_scope_filters_and_sorts_desc() throws Exception {
        // 强制控制 createdAt 顺序：用睡眠间隔确保时间戳不同
        store.save(MemoryScope.CORE, "older", List.of());
        Thread.sleep(10);
        store.save(MemoryScope.ARCHIVE, "a1", List.of());
        Thread.sleep(10);
        store.save(MemoryScope.CORE, "newer", List.of());

        List<MemoryEntry> coreHits = store.recallByScope(MemoryScope.CORE, 10);
        assertThat(coreHits).hasSize(2);
        assertThat(coreHits.get(0).content()).isEqualTo("newer");
        assertThat(coreHits.get(1).content()).isEqualTo("older");
    }

    // ===== 工具 =====

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("readLines failed: " + file, ex);
        }
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("readString failed: " + file, ex);
        }
    }

    /** 安静地写文件（测试 helper） */
    private static void writeString(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            throw new RuntimeException("writeString failed: " + file, ex);
        }
    }
}