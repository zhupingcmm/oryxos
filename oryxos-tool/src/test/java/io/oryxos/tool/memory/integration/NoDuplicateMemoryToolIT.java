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
 * T054（006-memory-layer Phase 8 / Polish）—— FR-007 / SC-009 防 duplicate：
 * {@code save_memory} 调 1 次 → 审计表 (tool_name, session_id, args_hash) 唯一键 EXACTLY 1 行。
 *
 * <p>这条是宪法 §VII "Demo-First" 的 day-one 审计保证：
 * {@code ReActLoop} 调 1 次 Tool → DefaultToolExecutor 写 1 行审计，
 * 不会因为 retry / LLM hallucination 多次调同 args 而写入多行。
 */
class NoDuplicateMemoryToolIT {

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
        tmpDir = Files.createTempDirectory("oryxos-dup-");
        mdStore = new MarkdownMemoryStore(tmpDir.resolve("MEMORY.md"));
        SaveMemoryTool save = new SaveMemoryTool(new DefaultMemoryService(mdStore));
        Map<String, ToolRegistration> map = new java.util.LinkedHashMap<>();
        map.put(SaveMemoryTool.NAME, new ToolRegistration(
            new ToolDefinition(SaveMemoryTool.NAME, "save memory", "builtin"), save, "save"));
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
    @DisplayName("FR-007 save_memory 调 1 次 → 审计表 EXACTLY 1 行（不重复）")
    void single_invocation_writes_exactly_one_audit_row() {
        Profile profile = profileWithTools("save_memory");
        UUID sessionId = UUID.randomUUID();
        ProfileContext.set(new ProfileContext.Snapshot(
            profile.name(), sessionId, new AtomicInteger(0)));
        try {
            ToolResult r = executor.invoke(SaveMemoryTool.NAME,
                Map.of("content", "once", "scope", "core"), profile);
            assertThat(r.success()).isTrue();
            assertThat(auditTable.rows).hasSize(1);
        } finally {
            ProfileContext.clear();
        }
    }

    @Test
    @DisplayName("FR-007 同一 Tool 调 5 次不同 args → 5 行（无 merge / dedup 副作用）")
    void distinct_invocations_produce_distinct_audit_rows() {
        Profile profile = profileWithTools("save_memory");
        ProfileContext.set(new ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new AtomicInteger(0)));
        try {
            for (int i = 0; i < 5; i++) {
                ToolResult r = executor.invoke(SaveMemoryTool.NAME,
                    Map.of("content", "v" + i, "scope", "core"), profile);
                assertThat(r.success()).isTrue();
            }
            assertThat(auditTable.rows).hasSize(5);
            // 内容唯一
            assertThat(auditTable.rows.stream()
                .map(ToolAuditWriter.ToolAuditData::toolName))
                .containsOnly(SaveMemoryTool.NAME);
        } finally {
            ProfileContext.clear();
        }
    }

    @Test
    @DisplayName("FR-007 Tool 抛异常 → 仍写 1 行审计（success=false）—— 不重试不重复")
    void exception_writes_one_audit_row_no_retry() {
        // 构造一个抛异常的 Tool —— 用 save_memory_fail
        var failingTool = new io.oryxos.core.OryxTool() {
            @Override public String name() { return "failing_save"; }
            @Override public String description() { return "always fails"; }
            @Override public ToolResult execute(Map<String, Object> args) {
                throw new RuntimeException("simulated");
            }
        };
        Map<String, ToolRegistration> map = new java.util.LinkedHashMap<>();
        map.put("failing_save", new ToolRegistration(
            new ToolDefinition("failing_save", "fails", "java_bean"), failingTool, "failing"));
        var ex = new DefaultToolExecutor(auditTable, ToolRegistry.of(map));

        Profile profile = profileWithTools("failing_save");
        ProfileContext.set(new ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new AtomicInteger(0)));
        try {
            ToolResult r = ex.invoke("failing_save", Map.of("x", 1), profile);
            assertThat(r.success()).isFalse();
            assertThat(auditTable.rows).hasSize(1);
            assertThat(auditTable.rows.get(0).success()).isFalse();
        } finally {
            ProfileContext.clear();
        }
    }

    private Profile profileWithTools(String... tools) {
        return new Profile(
            "no-dup-it",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            List.of(tools),
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            new ArrayList<>()
        );
    }
}