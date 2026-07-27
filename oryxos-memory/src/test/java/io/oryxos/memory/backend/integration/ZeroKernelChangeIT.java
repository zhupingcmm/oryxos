package io.oryxos.memory.backend.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T036a（006-memory-layer Phase 5 / US-3）—— SC-010 零内核修改契约测试。
 *
 * <p>目的：{@code oryxos-core} + {@code oryxos-tool} 不感知具体 Memory 后端实现——
 * 切换后端（markdown ↔ SQLite ↔ Mem0）只能改 {@code oryxos-memory} + {@code oryxos-boot}。
 *
 * <p>验证方法：
 * <ol>
 *   <li>扫描 {@code oryxos-core/src/main/java} 与 {@code oryxos-tool/src/main/java} 的 import</li>
 *   <li>禁止 import 任何具体后端实现类（黑名单）：
 *     <ul>
 *       <li>{@code io.oryxos.memory.backend.MarkdownMemoryStore}</li>
 *       <li>{@code io.oryxos.memory.backend.SqliteMemoryStore}</li>
 *       <li>{@code io.oryxos.memory.backend.Mem0MemoryStore}</li>
 *       <li>{@code io.oryxos.memory.backend.LongTermMemoryStore}（接口也不 import——只能从
 *           {@code io.oryxos.memory.MemoryService} 门面走）</li>
 *     </ul>
 *   </li>
 *   <li>必须 import 的接口（白名单）：
 *     <ul>
 *       <li>{@code io.oryxos.memory.MemoryService}（门面）</li>
 *       <li>{@code io.oryxos.memory.MemoryScope}（枚举，公开）</li>
 *       <li>{@code io.oryxos.memory.MemoryEntry}（record，公开）</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>实现：根目录相对路径硬编码（from oryxos-memory/pom.xml 看 ../oryxos-{core,tool}）。
 * 集成测试，不用 @SpringBootTest，只做静态扫描。
 */
class ZeroKernelChangeIT {

    private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path CORE_MAIN = REPO_ROOT.resolve("oryxos-core/src/main/java");
    private static final Path TOOL_MAIN = REPO_ROOT.resolve("oryxos-tool/src/main/java");

    /** 黑名单：禁止出现在 oryxos-core / oryxos-tool 的 import 中。 */
    private static final Set<String> FORBIDDEN_IMPORTS = Set.of(
        "io.oryxos.memory.backend.MarkdownMemoryStore",
        "io.oryxos.memory.backend.SqliteMemoryStore",
        "io.oryxos.memory.backend.Mem0MemoryStore",
        "io.oryxos.memory.backend.LongTermMemoryStore",
        "io.oryxos.memory.backend.integration.MarkdownToSqliteMigrationIT",
        "io.oryxos.memory.backend.integration.SwitchToMem0IT"
    );

    /** 白名单：允许出现的 io.oryxos.memory.* imports（接口层）。 */
    private static final Set<String> ALLOWED_IMPORTS = Set.of(
        "io.oryxos.memory.MemoryService",
        "io.oryxos.memory.MemoryScope",
        "io.oryxos.memory.MemoryEntry"
    );

    @Test
    @DisplayName("SC-010：oryxos-core 不 import 任何 Memory 后端实现")
    void core_has_no_backend_imports() throws IOException {
        List<String> violations = scanForForbiddenImports(CORE_MAIN);
        assertThat(violations)
            .withFailMessage("""
                oryxos-core 必须只依赖 io.oryxos.memory.MemoryService 门面，
                不能感知具体后端（MarkdownMemoryStore / SqliteMemoryStore / Mem0MemoryStore）。
                违规 imports:
                %s""".formatted(violations))
            .isEmpty();
    }

    @Test
    @DisplayName("SC-010：oryxos-tool 不 import 任何 Memory 后端实现")
    void tool_has_no_backend_imports() throws IOException {
        List<String> violations = scanForForbiddenImports(TOOL_MAIN);
        assertThat(violations)
            .withFailMessage("""
                oryxos-tool 必须只依赖 io.oryxos.memory.MemoryService 门面，
                不能感知具体后端。
                违规 imports:
                %s""".formatted(violations))
            .isEmpty();
    }

    @Test
    @DisplayName("SC-010：oryxos-core + oryxos-tool 只通过白名单接口与 Memory 交互")
    void only_whitelisted_memory_imports_in_core_and_tool() throws IOException {
        // 已有的 import 白名单（已知合规）
        // 我们这里只额外断言：扫到的所有 io.oryxos.memory.* imports 必须都在白名单里
        List<String> coreImports = scanForPackageImports(CORE_MAIN, "io.oryxos.memory");
        List<String> toolImports = scanForPackageImports(TOOL_MAIN, "io.oryxos.memory");

        List<String> unexpectedCore = coreImports.stream()
            .filter(imp -> !ALLOWED_IMPORTS.contains(imp))
            .toList();
        List<String> unexpectedTool = toolImports.stream()
            .filter(imp -> !ALLOWED_IMPORTS.contains(imp))
            .toList();

        assertThat(unexpectedCore)
            .withFailMessage("""
                oryxos-core 引入了未在白名单的 io.oryxos.memory.* imports:
                %s""".formatted(unexpectedCore))
            .isEmpty();
        assertThat(unexpectedTool)
            .withFailMessage("""
                oryxos-tool 引入了未在白名单的 io.oryxos.memory.* imports:
                %s""".formatted(unexpectedTool))
            .isEmpty();
    }

    /**
     * 扫目录里所有 .java 文件，匹配 {@code import <forbidden>...} 行（精确匹配）。
     * 返回形如 {@code file.java: import io.oryxos.memory.backend.X} 的可读违规列表。
     */
    private static List<String> scanForForbiddenImports(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of("WARNING: directory does not exist: " + dir);
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                .filter(p -> p.toString().endsWith(".java"))
                .flatMap(p -> {
                    try {
                        return Files.readAllLines(p).stream()
                            .map(line -> new String[]{p.getFileName().toString(), line.trim()});
                    } catch (IOException ex) {
                        return Stream.empty();
                    }
                })
                .filter(arr -> arr[1].startsWith("import "))
                .map(arr -> {
                    String imp = arr[1].substring("import ".length()).trim();
                    // 去掉 static / 通配符 / 末尾分号
                    imp = imp.replace("static ", "")
                             .replace(".*", "")
                             .replace(";", "")
                             .trim();
                    return new String[]{arr[0], imp};
                })
                .filter(arr -> FORBIDDEN_IMPORTS.contains(arr[1]))
                .map(arr -> arr[0] + ": import " + arr[1])
                .sorted()
                .collect(Collectors.toList());
        }
    }

    /**
     * 扫目录里所有匹配 {@code import <package>.X} 的行（X 是非通配）。
     * 用于查 io.oryxos.memory.* imports。
     */
    private static List<String> scanForPackageImports(Path dir, String pkg) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                .filter(p -> p.toString().endsWith(".java"))
                .flatMap(p -> {
                    try {
                        return Files.readAllLines(p).stream();
                    } catch (IOException ex) {
                        return Stream.empty();
                    }
                })
                .filter(line -> line.trim().startsWith("import "))
                .map(line -> line.trim().substring("import ".length()).trim())
                .map(imp -> imp.replace(";", "").trim())
                .filter(imp -> !imp.contains("*"))
                .filter(imp -> imp.startsWith(pkg + "."))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        }
    }
}