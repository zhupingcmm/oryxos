package io.oryxos.tool.memory.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.DefaultToolExecutor;
import io.oryxos.core.OryxTool;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileContext;
import io.oryxos.core.Provider;
import io.oryxos.core.ToolAuditWriter;
import io.oryxos.core.ToolResult;
import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.memory.DefaultMemoryService;
import io.oryxos.memory.MemoryException;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
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
 * T047（006-memory-layer Phase 7 / US-5）—— 场景 5
 * （[quickstart.md §场景 5](../specs/006-memory-layer/quickstart.md)）：
 * Memory Tool 跑通 DefaultToolExecutor（ReAct loop 的 Tool 调度入口），
 * 验证 SC-005 100% 审计 + SC-006 errorMessage 干净。
 *
 * <p>本测试模拟 ReAct loop 调用 Tool 的完整路径：
 * <ol>
 *   <li>注册真实 {@link SaveMemoryTool} / {@link RecallMemoryTool}（走真 MemoryService）</li>
 *   <li>{@link DefaultToolExecutor#invoke} 触发 Tool → ToolAuditWriter 落 1 行</li>
 *   <li>断言审计行 source='builtin'、success=true、含 1 个 tool call</li>
 *   <li>模拟 MemoryService 抛 MemoryException → 审计行 success=false + errorMessage 不含 stack trace</li>
 * </ol>
 *
 * <p>注意：本 IT 关注 Tool ↔ ReAct 集成边界，不重复测试 Tool 内部（已在
 * {@code SaveMemoryToolTest} / {@code RecallMemoryToolTest}）。
 */
class MemoryToolInReActIT {

    /** 模拟 {@code tool_invocations} 表。 */
    static final class InMemoryAuditTable implements ToolAuditWriter {
        final List<ToolAuditData> rows = new CopyOnWriteArrayList<>();
        @Override public void record(ToolAuditData data) { rows.add(data); }
        int count() { return rows.size(); }
    }

    InMemoryAuditTable auditTable;
    DefaultToolExecutor executor;
    MarkdownMemoryStore mdStore;
    Path tmpDir;

    @BeforeEach
    void setUp() throws IOException {
        auditTable = new InMemoryAuditTable();
        tmpDir = Files.createTempDirectory("oryxos-mem-react-");
        mdStore = new MarkdownMemoryStore(tmpDir.resolve("MEMORY.md"));
        MemoryService memoryService = new DefaultMemoryService(mdStore);

        // 注册两个 Memory Tool + 一个"会失败"的 variant
        SaveMemoryTool save = new SaveMemoryTool(memoryService);
        RecallMemoryTool recall = new RecallMemoryTool(memoryService);
        // 失败 variant：自定义 OryxTool 抛 MemoryException（不能用 SaveMemoryTool 因为 name 冲突）
        OryxTool saveFail = new OryxTool() {
            @Override public String name() { return "save_memory_fail"; }
            @Override public String description() { return "always-fail save (test stub)"; }
            @Override public ToolResult execute(Map<String, Object> args) {
                throw new MemoryException("simulated disk full: code=28");
            }
        };

        Map<String, ToolRegistration> map = new java.util.LinkedHashMap<>();
        map.put(SaveMemoryTool.NAME, new ToolRegistration(
            new ToolDefinition(SaveMemoryTool.NAME, "save memory", "builtin"), save, "save"));
        map.put(RecallMemoryTool.NAME, new ToolRegistration(
            new ToolDefinition(RecallMemoryTool.NAME, "recall memory", "builtin"), recall, "recall"));
        map.put("save_memory_fail", new ToolRegistration(
            new ToolDefinition("save_memory_fail", "always-fail save", "builtin"), saveFail, "save_fail"));

        ToolRegistry registry = ToolRegistry.of(map);
        executor = new DefaultToolExecutor(auditTable, registry);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpDir != null) deleteRecursively(tmpDir);
    }

    // ===== SC-005: 100% 审计 =====
    // ReAct 调 Tool → tool_invocations 含 1 行 source='builtin' success=true

    @Test
    @DisplayName("SC-005: ReAct 调 recall_memory（先 save 写入 1 条，再 recall） → 审计含 2 行 success=true source=builtin")
    void react_recall_memory_writes_audit_row() {
        Profile profile = profileWithTools("recall_memory", "save_memory");
        ProfileContext.set(new ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new AtomicInteger(0)));
        try {
            // 步骤 1: save 一条（ReAct 第一次 tool_call）
            ToolResult saveResult = executor.invoke(SaveMemoryTool.NAME,
                Map.of("content", "user prefers tabs", "scope", "core"), profile);
            assertThat(saveResult.success()).isTrue();
            assertThat(auditTable.count()).isEqualTo(1);
            assertThat(auditTable.rows.get(0).source()).isEqualTo("builtin");
            assertThat(auditTable.rows.get(0).success()).isTrue();
            assertThat(auditTable.rows.get(0).toolName()).isEqualTo(SaveMemoryTool.NAME);

            // 步骤 2: recall（ReAct 第二次 tool_call）
            ToolResult recallResult = executor.invoke(RecallMemoryTool.NAME,
                Map.of("query", "tabs", "top_k", 5), profile);
            assertThat(recallResult.success()).isTrue();
            @SuppressWarnings("unchecked")
            List<String> snippets = (List<String>) recallResult.payload().get("snippets");
            assertThat(snippets).hasSize(1);
            assertThat(snippets.get(0)).contains("tabs");

            // 步骤 3: 审计表 = 2 行（save + recall）
            assertThat(auditTable.count()).isEqualTo(2);
            assertThat(auditTable.rows.get(1).toolName()).isEqualTo(RecallMemoryTool.NAME);
            assertThat(auditTable.rows.get(1).source()).isEqualTo("builtin");
            assertThat(auditTable.rows.get(1).success()).isTrue();
        } finally {
            ProfileContext.clear();
        }
    }

    // ===== SC-006: 失败也审计 + errorMessage 干净 =====

    @Test
    @DisplayName("SC-006: save 抛 MemoryException → 审计行 success=false + errorMessage 不含 stack trace")
    void react_save_memory_failure_writes_audit_with_clean_error() {
        Profile profile = profileWithTools("save_memory_fail");
        ProfileContext.set(new ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new AtomicInteger(0)));
        try {
            ToolResult r = executor.invoke("save_memory_fail",
                Map.of("content", "x", "scope", "core"), profile);
            assertThat(r.success()).isFalse();

            // 审计行数 = 1
            assertThat(auditTable.count()).isEqualTo(1);
            ToolAuditWriter.ToolAuditData row = auditTable.rows.get(0);
            assertThat(row.success()).isFalse();
            assertThat(row.source()).isEqualTo("builtin");
            assertThat(row.errorMessage()).isNotNull();
            // SC-006 / NFR-004: errorMessage 不含 stack trace
            assertThat(row.errorMessage().length()).isLessThan(500);
            assertThat(row.errorMessage())
                .doesNotContain("\n\tat ")
                .doesNotContain("at io.oryxos.")
                .doesNotContain("Exception:");
            assertThat(row.errorMessage()).doesNotStartWith("io.oryxos.");
        } finally {
            ProfileContext.clear();
        }
    }

    // ===== FR-012: 审计字段完整 =====
    // tool_invocations 全字段：tool_name / success / duration_ms / error_message? / source

    @Test
    @DisplayName("FR-012: 审计行包含 tool_name + success + duration_ms + source（不含 error_message 当成功）")
    void audit_row_contains_all_required_columns() {
        Profile profile = profileWithTools("save_memory");
        ProfileContext.set(new ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new AtomicInteger(0)));
        try {
            executor.invoke(SaveMemoryTool.NAME,
                Map.of("content", "hello", "scope", "core"), profile);
            assertThat(auditTable.count()).isEqualTo(1);
            ToolAuditWriter.ToolAuditData row = auditTable.rows.get(0);
            assertThat(row.toolName()).isEqualTo(SaveMemoryTool.NAME);
            assertThat(row.success()).isTrue();
            assertThat(row.source()).isEqualTo("builtin");
            assertThat(row.durationMs()).isGreaterThanOrEqualTo(0L);
            assertThat(row.errorMessage()).isNull();
        } finally {
            ProfileContext.clear();
        }
    }

    // ===== 工具 =====

    private Profile profileWithTools(String... tools) {
        return new Profile(
            "memory-react-it",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            List.of(tools),
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            new ArrayList<>()
        );
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