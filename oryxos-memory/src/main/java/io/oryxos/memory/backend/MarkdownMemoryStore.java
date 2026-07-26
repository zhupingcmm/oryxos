package io.oryxos.memory.backend;

import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryException;
import io.oryxos.memory.MemoryScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 文件型长期记忆实现 —— 把核心 + 归档两区拼到同一个
 * {@code MEMORY.md} 文件里，分隔符为 {@code ## core} / {@code ## archive}。
 *
 * <p>默认 backend（CLAUDE.md §9.6）。文件位置 {@code .oryxos/memory/MEMORY.md}。
 *
 * <p>行格式（[contracts/markdown-backend.md §2](../../../../../specs/006-memory-layer/contracts/markdown-backend.md)）：
 * <pre>
 * # MEMORY
 *
 * ## core
 * - [2026-07-26T10:00:00Z] [uuid-xxx] 用户偏好 PR 标签 = bug+enhancement [#tags=preference]
 *
 * ## archive
 * - [2026-07-26T11:00:00Z] [uuid-yyy] ...
 * </pre>
 *
 * <p>三条核心契约（CLAUDE.md §9.6）：
 * <ol>
 *   <li>不缓存 —— 每次 recallByKeyword 直接读文件</li>
 *   <li>core 永不被截断 —— delete/clear MUST NOT 触碰 core 区</li>
 *   <li>scope 必填 —— save(scope=null) 抛 IllegalArgumentException</li>
 * </ol>
 *
 * <p>详见 [specs/006-memory-layer/contracts/markdown-backend.md](../../../../../specs/006-memory-layer/contracts/markdown-backend.md)。
 */
@Component("markdownMemoryStore")
public class MarkdownMemoryStore implements LongTermMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MarkdownMemoryStore.class);

    /** 行格式：`- [<ISO-8601>] [<UUID>] <content> [#tags=tag1,tag2]` */
    private static final Pattern LINE_PATTERN = Pattern.compile(
        "^-\\s+\\[([^\\]]+)\\]\\s+\\[([^\\]]+)\\]\\s+(.*?)(?:\\s+\\[#tags=(.*?)\\])?\\s*$");

    /** 段标题识别。 */
    private static final Pattern SECTION_PATTERN = Pattern.compile("^##\\s+(core|archive)\\s*$");

    /** 文件路径 —— 非 final 以支持测试 setFilePathForTest 重绑（005-tool-system 集成测试用）。 */
    private Path filePath;
    private final Object writeLock = new Object();

    /** 无参构造器（Spring @Component 默认 + 集成测试用）。默认 {@code ~/.oryxos/memory/MEMORY.md}。 */
    public MarkdownMemoryStore() {
        this(Path.of(System.getProperty("user.home"))
            .resolve(".oryxos")
            .resolve("memory")
            .resolve("MEMORY.md"));
    }

    /** Spring @ConfigurationProperties 注入路径（生产构造器）。 */
    public MarkdownMemoryStore(
        @Value("${oryxos.memory.markdown.path:.oryxos/memory/MEMORY.md}") String path
    ) {
        this(Path.of(path));
    }

    /** 测试 / 显式路径构造器。 */
    public MarkdownMemoryStore(Path filePath) {
        this.filePath = filePath;
    }

    /** 测试专用：切换文件路径（005-tool-system 集成测试要求）。 */
    public void setFilePathForTest(Path filePath) {
        synchronized (writeLock) {
            this.filePath = filePath;
        }
    }

    @Override
    public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        List<String> safeTags = tags == null ? List.of() : List.copyOf(tags);

        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String line = formatLine(id, scope, content, safeTags, now);

        synchronized (writeLock) {
            try {
                Files.createDirectories(filePath.getParent());
                if (Files.notExists(filePath)) {
                    Files.writeString(filePath, "# MEMORY\n\n## core\n\n## archive\n",
                        StandardCharsets.UTF_8);
                }
                List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
                int sectionStart = findSectionStart(lines, scope);
                int sectionEnd = findSectionEnd(lines, sectionStart, scope);
                List<String> updated = new ArrayList<>(lines);
                updated.add(sectionEnd, line);
                Files.writeString(filePath,
                    String.join(System.lineSeparator(), updated) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ex) {
                throw new MemoryException("markdown memory write failed: " + ex.getMessage(), ex);
            }
        }
        return new MemoryEntry(id, scope, content, safeTags, now, scope.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = normalizeTopK(topK);
        List<MemoryEntry> all = readAllEntries();
        String q = query.toLowerCase(Locale.ROOT);
        List<MemoryEntry> hits = new ArrayList<>();
        for (MemoryEntry e : all) {
            if (scopeFilter != null && e.scope() != scopeFilter) continue;
            if (e.content().toLowerCase(Locale.ROOT).contains(q)) {
                hits.add(e);
            }
        }
        // 按 createdAt DESC（C-MS-07 / FR-006）
        hits.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        if (hits.size() > limit) {
            return hits.subList(0, limit);
        }
        return hits;
    }

    @Override
    public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        int limit = normalizeTopK(topK);
        List<MemoryEntry> all = readAllEntries();
        List<MemoryEntry> hits = new ArrayList<>();
        for (MemoryEntry e : all) {
            if (e.scope() == scope) hits.add(e);
        }
        hits.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        if (hits.size() > limit) {
            return hits.subList(0, limit);
        }
        return hits;
    }

    @Override
    public boolean delete(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return false;
        }
        synchronized (writeLock) {
            try {
                if (Files.notExists(filePath)) {
                    return false;
                }
                List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
                boolean removed = false;
                List<String> updated = new ArrayList<>(lines.size());
                for (String line : lines) {
                    String parsedId = parseEntryId(line);
                    if (parsedId != null && parsedId.equals(entryId)) {
                        removed = true;
                        continue;
                    }
                    updated.add(line);
                }
                if (removed) {
                    Files.writeString(filePath,
                        String.join(System.lineSeparator(), updated) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING);
                }
                return removed;
            } catch (IOException ex) {
                throw new MemoryException("markdown memory delete failed: " + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public void clear(MemoryScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (scope == MemoryScope.CORE) {
            // C-LT-05 硬约束 —— core 永不被截断
            throw new IllegalStateException(
                "clear(core) is forbidden: core scope is never truncated (CLAUDE.md §9.6 契约 ②)");
        }
        synchronized (writeLock) {
            try {
                if (Files.notExists(filePath)) {
                    return;
                }
                List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
                int sectionStart = findSectionStart(lines, MemoryScope.ARCHIVE);
                int sectionEnd = findSectionEnd(lines, sectionStart, MemoryScope.ARCHIVE);
                // 删除 sectionStart+1 .. sectionEnd-1（section 标题保留）
                List<String> updated = new ArrayList<>(lines.subList(0, sectionStart + 1));
                updated.addAll(lines.subList(sectionEnd, lines.size()));
                Files.writeString(filePath,
                    String.join(System.lineSeparator(), updated) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ex) {
                throw new MemoryException("markdown memory clear failed: " + ex.getMessage(), ex);
            }
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            if (Files.notExists(filePath)) {
                // 文件不存在 → 检查父目录可写
                Path parent = filePath.getParent();
                return parent != null && Files.isWritable(parent);
            }
            return Files.isReadable(filePath);
        } catch (RuntimeException ex) {
            log.warn("MarkdownMemoryStore isHealthy() failed: {}", ex.getMessage());
            return false;
        }
    }

    // ===== 私有工具方法 =====

    static String formatLine(String id, MemoryScope scope, String content, List<String> tags, Instant ts) {
        StringBuilder sb = new StringBuilder("- [")
            .append(ts.toString())
            .append("] [")
            .append(id)
            .append("] ")
            .append(content.replace("\n", "⏎"));
        if (tags != null && !tags.isEmpty()) {
            sb.append(" [#tags=").append(String.join(",", tags)).append("]");
        }
        return sb.toString();
    }

    static String parseEntryId(String line) {
        if (line == null) return null;
        Matcher m = LINE_PATTERN.matcher(line);
        if (m.matches()) {
            return m.group(2);
        }
        return null;
    }

    static MemoryEntry parseLine(String line) {
        ParsedLine p = parseLineRaw(line);
        return p == null ? null : p.toEntry(MemoryScope.CORE);
    }

    /**
     * 解析一行 —— 返回不含 scope 的内部 record（scope 由调用方根据段标题填充）。
     * 返回 null 表示该行不是合法 entry（解析失败 / 段标题行 / 注释行）。
     */
    static ParsedLine parseLineRaw(String line) {
        if (line == null) return null;
        Matcher m = LINE_PATTERN.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String tsStr = m.group(1);
        String id = m.group(2);
        String content = m.group(3);
        String tagsStr = m.group(4);
        Instant ts;
        try {
            ts = Instant.parse(tsStr);
        } catch (RuntimeException ex) {
            return null; // 解析失败跳过
        }
        List<String> tags = (tagsStr == null || tagsStr.isBlank())
            ? List.of()
            : List.of(tagsStr.split(","));
        return new ParsedLine(id, content, tags, ts);
    }

    /** 解析后的中间态 —— scope 由 readAllEntries 根据当前段标题注入。 */
    record ParsedLine(String id, String content, List<String> tags, Instant createdAt) {
        MemoryEntry toEntry(MemoryScope scope) {
            return new MemoryEntry(id, scope, content, tags, createdAt,
                scope == null ? null : scope.name().toLowerCase(Locale.ROOT));
        }
    }

    /** 找出 {@code scope} 段开始行（含 ## 标题本身）的下标；找不到返回 size。 */
    static int findSectionStart(List<String> lines, MemoryScope scope) {
        String target = "## " + scope.name().toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.size(); i++) {
            if (target.equalsIgnoreCase(lines.get(i).trim())) {
                return i;
            }
        }
        // 缺失 → append 到末尾（lenient recovery — C-MD-08）
        return lines.size();
    }

    /** 找出段结束行下标：下一段 ## 标题行 / 文件末尾。 */
    static int findSectionEnd(List<String> lines, int sectionStart, MemoryScope scope) {
        for (int i = sectionStart + 1; i < lines.size(); i++) {
            Matcher m = SECTION_PATTERN.matcher(lines.get(i).trim());
            if (m.matches() && !m.group(1).equalsIgnoreCase(scope.name().toLowerCase(Locale.ROOT))) {
                return i;
            }
        }
        return lines.size();
    }

    private List<MemoryEntry> readAllEntries() {
        synchronized (writeLock) {
            if (Files.notExists(filePath)) {
                return List.of();
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new MemoryException("markdown memory read failed: " + ex.getMessage(), ex);
            }
            List<MemoryEntry> result = new ArrayList<>();
            MemoryScope currentScope = null;
            for (String line : lines) {
                Matcher sm = SECTION_PATTERN.matcher(line.trim());
                if (sm.matches()) {
                    currentScope = MemoryScope.fromString(sm.group(1));
                    continue;
                }
                ParsedLine p = parseLineRaw(line);
                if (p != null && currentScope != null) {
                    result.add(p.toEntry(currentScope));
                }
            }
            result.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
            return result;
        }
    }

    private static int normalizeTopK(int topK) {
        if (topK <= 0) return 1;
        if (topK > 100) return 100; // C-MS-07
        return topK;
    }
}