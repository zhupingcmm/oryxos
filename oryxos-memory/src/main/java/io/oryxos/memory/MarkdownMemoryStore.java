package io.oryxos.memory;

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

/**
 * Markdown 文件型长期记忆实现 —— 把核心 + 归档两区拼到同一个
 * {@code MEMORY.md} 文件里，分隔符为 {@code ## core} / {@code ## archive}。
 *
 * <p>默认 backend（CLAUDE.md §9.6）。文件位置 {@code .oryxos/memory/MEMORY.md}；
 * 简化实现：核心阶段不缓存、不并发锁 —— 单进程 Agent 调用足够。
 *
 * <p>检索（{@link #recallByKeyword}）走简单的"包含关键词大小写不敏感行匹配"
 * 倒序扫描；核心阶段不引入向量检索（与 CLAUDE.md §9.6 第 4 条契约一致）。
 *
 * <p>核心区（{@code ## core} 到 {@code ## archive} 或文件末尾）永不被截断；
 * 归档区可被未来扩展压缩。
 *
 * <p>详见 <a href="../../../../../../../specs/003-cli-commands/spec.md">specs/003-cli-commands/spec.md</a>。
 */
@Component
public class MarkdownMemoryStore implements MemoryService {

    /** 默认文件路径 —— 可被构造器注入覆盖（测试用）。 */
    private Path filePath;

    public MarkdownMemoryStore() {
        this(Path.of(System.getProperty("user.home"))
            .resolve(".oryxos")
            .resolve("memory")
            .resolve("MEMORY.md"));
    }

    public MarkdownMemoryStore(Path filePath) {
        this.filePath = filePath;
    }

    /** 测试专用：在 Spring 上下文里重新指向文件（final 字段 → 改成可变 + setter）。 */
    public void setFilePathForTest(Path filePath) {
        this.filePath = filePath;
    }

    @Override
    public MemoryEntry save(String content, MemoryScope scope) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }

        Instant now = Instant.now();
        MemoryEntry entry = new MemoryEntry(content, scope, now);

        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException ex) {
            throw new MemoryStoreException("cannot create memory dir: " + ex.getMessage(), ex);
        }

        String line = "- " + now.toString() + " [" + scope.name().toLowerCase(Locale.ROOT) + "] "
            + content.replace("\n", "⏎") + System.lineSeparator();

        try {
            if (Files.notExists(filePath)) {
                Files.writeString(filePath,
                    "# MEMORY" + System.lineSeparator()
                        + System.lineSeparator()
                        + "## core" + System.lineSeparator()
                        + System.lineSeparator()
                        + "## archive" + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            }
            // 找到 ## archive 行，在它**之前**插入；core 区在 archive 标题之前
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            int insertIdx = lines.size();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).trim().equalsIgnoreCase("## archive")) {
                    insertIdx = i;
                    break;
                }
            }
            // 跳过空行不直接接 core 行内容 — 若紧邻位置是空行，保持原行为
            lines.add(insertIdx, line.stripTrailing());
            Files.writeString(filePath, String.join(System.lineSeparator(), lines)
                + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
            return entry;
        } catch (IOException ex) {
            throw new MemoryStoreException("write failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<MemoryEntry> recallByKeyword(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        int limit = topK <= 0 ? 1 : topK;

        if (Files.notExists(filePath)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new MemoryStoreException("read failed: " + ex.getMessage(), ex);
        }

        String q = query.toLowerCase(Locale.ROOT);
        List<MemoryEntry> hits = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && hits.size() < limit; i--) {
            String line = lines.get(i);
            if (line == null || !line.startsWith("- ")) {
                continue;
            }
            if (!line.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            // 简化解析：`- <timestamp> [<scope>] <content>`
            int firstBracket = line.indexOf('[');
            int firstClose = line.indexOf(']', firstBracket + 1);
            if (firstBracket < 0 || firstClose < 0) {
                continue;
            }
            String scopeStr = line.substring(firstBracket + 1, firstClose);
            MemoryScope scope;
            try {
                scope = MemoryScope.fromString(scopeStr);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            String content = line.substring(firstClose + 1).strip();
            Instant ts = Instant.now(); // 简化：解析失败时回退 now
            hits.add(new MemoryEntry(content, scope, ts));
        }
        return hits;
    }

    /** 文件 / 读写失败的统一异常 —— MemoryService 调用方应让异常传播。 */
    public static class MemoryStoreException extends RuntimeException {
        public MemoryStoreException(String message, Throwable cause) { super(message, cause); }
    }
}

