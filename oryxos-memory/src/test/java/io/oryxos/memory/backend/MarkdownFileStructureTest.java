package io.oryxos.memory.backend;

import io.oryxos.memory.MemoryScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T021（006-memory-layer Phase 4 / US-2）—— Markdown 文件结构契约
 * （[contracts/markdown-backend.md §2](../../../../../specs/006-memory-layer/contracts/markdown-backend.md)）。
 *
 * <p>关注的是**文件作为持久化产物的格式稳定性**，而不是 store 行为（已由 T020 覆盖）。
 * 这组测试同时跑：
 * <ul>
 *   <li>{@link MarkdownMemoryStore} 写出的实际文件格式</li>
 *   <li>{@link MarkdownMemoryStore#formatLine} / {@link MarkdownMemoryStore#parseLineRaw} 单元级</li>
 * </ul>
 *
 * <p>覆盖结构契约：
 * <ul>
 *   <li>SF-1 文件头：首行 {@code # MEMORY}</li>
 *   <li>SF-2 段标题：{@code ## Core} / {@code ## Archive}（大小写不敏感，固定首字母大写）</li>
 *   <li>SF-3 entry 行格式：{@code - [ISO-8601] [UUID] content [#tags=tag1,tag2]}（tags 可选）</li>
 *   <li>SF-4 round-trip：format → parse 后字段完全保留（id / content / tags / createdAt）</li>
 *   <li>SF-5 段顺序：Core 在 Archive 之前；不会出现 Archive 在 Core 之前</li>
 *   <li>SF-6 scope 来源：section 标题决定 entry.scope()（section 之外的 entry 不被加载）</li>
 * </ul>
 */
class MarkdownFileStructureTest {

    @TempDir Path tmpDir;
    Path memoryFile;
    MarkdownMemoryStore store;

    @BeforeEach
    void setUp() {
        memoryFile = tmpDir.resolve("MEMORY.md");
        store = new MarkdownMemoryStore(memoryFile);
    }

    @Test
    @DisplayName("SF-1 文件头：自动初始化时首行为 # MEMORY")
    void header_first_line() {
        store.save(MemoryScope.CORE, "x", List.of());
        String firstLine = readLines(memoryFile).get(0);
        assertThat(firstLine).isEqualTo("# MEMORY");
    }

    @Test
    @DisplayName("SF-2 段标题：自动初始化时同时包含 ## Core 与 ## Archive，且 Core 在 Archive 之前")
    void both_section_headers_in_order() {
        store.save(MemoryScope.CORE, "x", List.of());
        List<String> lines = readLines(memoryFile);
        int coreIdx = indexOfTrimmed(lines, "## Core");
        int archiveIdx = indexOfTrimmed(lines, "## Archive");
        assertThat(coreIdx).isGreaterThanOrEqualTo(0);
        assertThat(archiveIdx).isGreaterThan(coreIdx);
    }

    @Test
    @DisplayName("SF-3 entry 行格式：含 tags 时行尾带 [#tags=t1,t2]；不含 tags 时无 tags 后缀")
    void entry_line_format_with_and_without_tags() {
        store.save(MemoryScope.CORE, "with tags", List.of("foo", "bar"));
        store.save(MemoryScope.ARCHIVE, "no tags", List.of());
        List<String> lines = readLines(memoryFile);

        String withTags = lines.stream()
            .filter(l -> l.startsWith("- ") && l.contains("with tags"))
            .findFirst().orElseThrow();
        String noTags = lines.stream()
            .filter(l -> l.startsWith("- ") && l.contains("no tags"))
            .findFirst().orElseThrow();

        Pattern entryWithTags = Pattern.compile(
            "^-\\s+\\[\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z?\\]\\s+\\[[0-9a-fA-F-]{36}\\]\\s+with tags\\s+\\[#tags=foo,bar\\]$");
        Pattern entryNoTags = Pattern.compile(
            "^-\\s+\\[\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z?\\]\\s+\\[[0-9a-fA-F-]{36}\\]\\s+no tags$");

        assertThat(withTags).matches(entryWithTags);
        assertThat(noTags).matches(entryNoTags);
    }

    @Test
    @DisplayName("SF-4 round-trip：format → parseLineRaw 后字段全保留")
    void format_then_parse_preserves_fields() {
        // 直接走底层 helper，验证 round-trip 不丢信息
        String id = "12345678-90ab-cdef-1234-567890abcdef";
        java.time.Instant ts = java.time.Instant.parse("2026-01-15T08:30:00Z");
        List<String> tags = List.of("alpha", "beta");
        String line = MarkdownMemoryStore.formatLine(
            id, MemoryScope.CORE, "hello world", tags, ts);

        MarkdownMemoryStore.ParsedLine parsed = MarkdownMemoryStore.parseLineRaw(line);
        assertThat(parsed).isNotNull();
        assertThat(parsed.id()).isEqualTo(id);
        assertThat(parsed.content()).isEqualTo("hello world");
        assertThat(parsed.tags()).containsExactly("alpha", "beta");
        assertThat(parsed.createdAt()).isEqualTo(ts);
    }

    @Test
    @DisplayName("SF-4 round-trip：无 tags 时 parseLineRaw 返回 tags=空列表")
    void format_then_parse_no_tags() {
        String id = "id-no-tags-1";
        java.time.Instant ts = java.time.Instant.parse("2026-02-20T12:00:00Z");
        String line = MarkdownMemoryStore.formatLine(id, MemoryScope.CORE, "plain", null, ts);

        MarkdownMemoryStore.ParsedLine parsed = MarkdownMemoryStore.parseLineRaw(line);
        assertThat(parsed).isNotNull();
        assertThat(parsed.tags()).isEmpty();
    }

    @Test
    @DisplayName("SF-4 round-trip：parseLineRaw 对非法行返回 null（不抛）")
    void parse_raw_returns_null_for_invalid_line() {
        assertThat(MarkdownMemoryStore.parseLineRaw(null)).isNull();
        assertThat(MarkdownMemoryStore.parseLineRaw("")).isNull();
        assertThat(MarkdownMemoryStore.parseLineRaw("## Core")).isNull();
        assertThat(MarkdownMemoryStore.parseLineRaw("# comment")).isNull();
        assertThat(MarkdownMemoryStore.parseLineRaw("not a valid entry line")).isNull();
    }

    @Test
    @DisplayName("SF-5 段顺序：save(CORE) 不在 archive 段之前/之后插入新 archive 段")
    void core_saves_never_reorder_sections() {
        store.save(MemoryScope.ARCHIVE, "a1", List.of());
        store.save(MemoryScope.CORE, "c1", List.of());
        store.save(MemoryScope.ARCHIVE, "a2", List.of());
        store.save(MemoryScope.CORE, "c2", List.of());
        List<String> lines = readLines(memoryFile);
        int firstArchive = indexOfTrimmed(lines, "## Archive");
        int firstCore = indexOfTrimmed(lines, "## Core");
        assertThat(firstCore).isGreaterThan(0);
        assertThat(firstArchive).isGreaterThan(firstCore);
        // ## Core 只能出现一次
        long coreHeaderCount = lines.stream()
            .filter(l -> l.trim().equalsIgnoreCase("## Core"))
            .count();
        long archiveHeaderCount = lines.stream()
            .filter(l -> l.trim().equalsIgnoreCase("## Archive"))
            .count();
        assertThat(coreHeaderCount).isEqualTo(1L);
        assertThat(archiveHeaderCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("SF-6 scope 来源：save 时决定 entry.scope()，不是 formatLine 静态决定")
    void scope_comes_from_save_call_not_format() {
        String sameId = "shared-id-aaaa-bbbb-cccc-dddddddddddd";
        String coreLine = MarkdownMemoryStore.formatLine(
            sameId, MemoryScope.CORE, "shared", List.of(), java.time.Instant.parse("2026-01-01T00:00:00Z"));
        String archiveLine = MarkdownMemoryStore.formatLine(
            sameId, MemoryScope.ARCHIVE, "shared", List.of(), java.time.Instant.parse("2026-01-01T00:00:00Z"));
        // 同一 id+content+ts 在不同 scope 下生成的行**完全一样**（scope 不在 entry 行里）
        assertThat(coreLine).isEqualTo(archiveLine);
        // save 时分别写到 CORE / ARCHIVE 段；readAllEntries 按段标题赋予 scope
        store.save(MemoryScope.CORE, "shared", List.of());
        store.save(MemoryScope.ARCHIVE, "shared", List.of());

        List<io.oryxos.memory.MemoryEntry> all = store.recallByKeyword("shared", 10, null);
        assertThat(all).hasSize(2);
        // 两条命中可能同 id（因为 formatLine 用了相同 UUID 概率极小但理论可能）—— 断言 scope 不同即可
        long coreCount = all.stream().filter(e -> e.scope() == MemoryScope.CORE).count();
        long archCount = all.stream().filter(e -> e.scope() == MemoryScope.ARCHIVE).count();
        assertThat(coreCount).isEqualTo(1L);
        assertThat(archCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("SF-6 section 之外：孤儿 entry 行（无段标题）不被加载")
    void orphan_entries_outside_sections_are_ignored() {
        // 手工构造一个"非法"文件：entry 行出现在 # MEMORY 后但在 ## Core 之前
        try {
            String content = "# MEMORY\n"
                + "- [2026-01-01T00:00:00Z] [orphan-uuid-aaaa-bbbb-cccc-dddddddddddd] orphan\n"
                + "## Core\n"
                + "- [2026-01-01T00:00:00Z] [good-uuid-aaaa-bbbb-cccc-dddddddddddd] good\n"
                + "## Archive\n";
            Files.writeString(memoryFile, content);

            List<io.oryxos.memory.MemoryEntry> all = store.recallByKeyword("orphan", 10, null);
            assertThat(all).isEmpty();
            List<io.oryxos.memory.MemoryEntry> good = store.recallByKeyword("good", 10, null);
            assertThat(good).hasSize(1);
            assertThat(good.get(0).scope()).isEqualTo(MemoryScope.CORE);
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    // ===== 工具 =====

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static int indexOfTrimmed(List<String> lines, String target) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equalsIgnoreCase(target)) {
                return i;
            }
        }
        return -1;
    }
}