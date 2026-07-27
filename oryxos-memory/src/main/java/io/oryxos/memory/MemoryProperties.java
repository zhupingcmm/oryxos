package io.oryxos.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Memory 层 @ConfigurationProperties（006-memory-layer）。
 *
 * <p>绑定 application.yaml 的 {@code oryxos.memory.*} 配置块。
 *
 * <p>字段（[data-model.md §2.4](../specs/006-memory-layer/data-model.md) +
 * [research.md R-08](../specs/006-memory-layer/research.md)）：
 * <ul>
 *   <li>{@link #backend} —— 长期层后端选择（markdown / sqlite / mem0）</li>
 *   <li>{@link #archive} —— 归档区容量配置（仅 sqlite 后端生效）</li>
 *   <li>{@link #mem0} —— Mem0 HTTP 客户端配置</li>
 *   <li>{@link #markdown} —— Markdown 文件路径</li>
 * </ul>
 *
 * <p>装配入口：oryxos-boot 的 {@code MemoryConfig}（Phase 5 落地）通过
 * {@code @EnableConfigurationProperties(MemoryProperties.class)} 启用本 record。
 *
 * <p>默认值（research R-08）：backend="markdown" / archiveMaxEntries=1000 /
 * mem0BaseUrl="http://localhost:8000" / markdownPath=".oryxos/memory/MEMORY.md"。
 */
@ConfigurationProperties(prefix = "oryxos.memory")
public record MemoryProperties(
    /** 后端选择：markdown（默认）/ sqlite / mem0。 */
    String backend,
    Archive archive,
    Mem0 mem0,
    Markdown markdown
) {
    /** 默认构造器 —— 当 application.yaml 未配置时使用。 */
    public MemoryProperties {
        if (backend == null || backend.isBlank()) {
            backend = "markdown";
        }
        if (archive == null) {
            archive = new Archive(1000);
        }
        if (mem0 == null) {
            mem0 = new Mem0("http://localhost:8000", 5);
        }
        if (markdown == null) {
            markdown = new Markdown(".oryxos/memory/MEMORY.md");
        }
    }

    /**
     * 归档区容量配置（spec FR-010 + research R-06：仅 SqliteMemoryStore 生效；Markdown 默认无上限）。
     */
    public record Archive(int maxEntries) {
        public Archive {
            if (maxEntries < 1) {
                maxEntries = 1000;
            }
        }
    }

    /**
     * Mem0 HTTP 客户端配置（spec FR-015 + contracts/mem0-backend.md §1）。
     */
    public record Mem0(String baseUrl, int timeoutSeconds) {
        public Mem0 {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://localhost:8000";
            }
            if (timeoutSeconds < 1) {
                timeoutSeconds = 5;
            }
        }
    }

    /**
     * Markdown 后端文件路径配置（spec FR-004 + CLAUDE.md §12 工作区）。
     */
    public record Markdown(String path) {
        public Markdown {
            if (path == null || path.isBlank()) {
                path = ".oryxos/memory/MEMORY.md";
            }
        }
    }
}