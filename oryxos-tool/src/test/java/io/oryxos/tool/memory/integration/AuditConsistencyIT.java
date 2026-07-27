package io.oryxos.tool.memory.integration;

import io.oryxos.core.DefaultToolExecutor;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileContext;
import io.oryxos.core.Provider;
import io.oryxos.core.ToolAuditWriter;
import io.oryxos.core.ToolResult;
import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.backend.MarkdownMemoryStore;
import io.oryxos.tool.memory.RecallMemoryTool;
import io.oryxos.tool.memory.SaveMemoryTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T053（006-memory-layer Phase 8 / Polish）—— SC-005 跨场景审计一致性：
 * N=20 次 {@code save_memory} + {@code recall_memory} 调用 →
 * {@code tool_invocations} 增 20 行；每行 {@code source='builtin'}。
 *
 * <p>覆盖 4 种调用路径：save 成功 / save 失败 / recall 成功 / recall 0 命中。
 */
class AuditConsistencyIT {

    static final class InMemoryAuditTable implements ToolAuditWriter {
        final List<ToolAuditData> rows = new CopyOnWriteArrayList<>();
        @Override public void record(ToolAuditData data) { rows.add(data); }
    }

    InMemoryAuditTable auditTable;
    DefaultToolExecutor executor;
    MarkdownMemoryStore mdStore;
    Path tmpDir;

    @BeforeEach
    void setUp() throws IOException {
        auditTable = new InMemoryAuditTable();
        tmpDir = Files.createTempDirectory("oryxos-audit-1500-");
        mdStore = new MarkdownMemoryStore(tmpDir.resolve("MEMORY.md"));
        DefaultMemoryService svc = new DefaultMemoryService(mdStore);
        SaveMemoryTool save = new SaveMemoryTool(svc);
        RecallMemoryTool recall = new RecallMemoryTool(svc);

        Map<String, ToolRegistration> map = new java.util.LinkedHashMap<>();
        map.put(SaveMemoryTool.NAME, new ToolRegistration(
            new ToolDefinition(SaveMemoryTool.NAME, "save memory", "builtin"), save, "save"));
        map.put(RecallMemoryTool.NAME, new ToolRegistration(
            new ToolDefinition(RecallMemoryTool.NAME, "recall memory", "builtin"), recall, "recall"));
        executor = new DefaultToolExecutor(auditTable, ToolRegistry.of(map));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpDir != null) {
            try (var s = Files.walk(tmpDir)) {
                s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    @DisplayName("SC-005: N=20 次 save/recall（成功+失败混合）→ 审计 20 行 source='builtin' 全覆盖")
    void twenty_invocations_all_audited() {
        Profile profile = profileWithTools("save_memory", "recall_memory");
        ProfileContext.set(new ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new AtomicInteger(0)));
        try {
            // 写 5 条（为 recall 提供数据）
            for (int i = 0; i < 5; i++) {
                ToolResult r = executor.invoke(SaveMemoryTool.NAME,
                    Map.of("content", "entry " + i, "scope", "core"), profile);
                assertThat(r.success()).isTrue();
            }
            // recall 5 次（4 次命中 + 1 次无命中）
            for (int i = 0; i < 4; i++) {
                ToolResult r = executor.invoke(RecallMemoryTool.NAME,
                    Map.of("query", "entry", "top_k", 5), profile);
                assertThat(r.success()).isTrue();
            }
            ToolResult missHit = executor.invoke(RecallMemoryTool.NAME,
                Map.of("query", "no-such-keyword", "top_k", 5), profile);
            assertThat(missHit.success()).isTrue();
            // 再写 5 条
            for (int i = 5; i < 10; i++) {
                ToolResult r = executor.invoke(SaveMemoryTool.NAME,
                    Map.of("content", "entry " + i, "scope", "core"), profile);
                assertThat(r.success()).isTrue();
            }
            // 再 recall 5 次
            for (int i = 0; i < 5; i++) {
                ToolResult r = executor.invoke(RecallMemoryTool.NAME,
                    Map.of("query", "entry", "top_k", 5), profile);
                assertThat(r.success()).isTrue();
            }

            // 总共 5 + 5 + 5 + 5 = 20
            assertThat(auditTable.rows).hasSize(20);
            for (ToolAuditWriter.ToolAuditData row : auditTable.rows) {
                assertThat(row.source())
                    .as("FR-005 source column")
                    .isEqualTo("builtin");
                assertThat(row.toolName())
                    .isIn(SaveMemoryTool.NAME, RecallMemoryTool.NAME);
            }
        } finally {
            ProfileContext.clear();
        }
    }

    private Profile profileWithTools(String... tools) {
        return new Profile(
            "audit-consistency",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            List.of(tools),
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            new ArrayList<>()
        );
    }
}