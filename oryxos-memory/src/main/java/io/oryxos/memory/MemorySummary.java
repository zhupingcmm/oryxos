package io.oryxos.memory;

/**
 * 008-agent-web-service 阶段新增 —— {@link MemoryService#summary()} 返回的非敏感元信息.
 *
 * <p>用于 REST {@code GET /api/v1/memory} 响应 — 仅暴露后端类型与条数,不暴露内容
 * (per CLAUDE.md §15 "核心阶段不做 Memory REST 详情").
 */
public record MemorySummary(
    String backend,
    int coreEntries,
    int archiveEntries,
    String filePath
) {
}
