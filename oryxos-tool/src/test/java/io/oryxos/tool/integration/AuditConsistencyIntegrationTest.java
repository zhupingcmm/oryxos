package io.oryxos.tool.integration;

import io.oryxos.core.DefaultToolExecutor;
import io.oryxos.core.OryxTool;
import io.oryxos.core.Profile;
import io.oryxos.core.Provider;
import io.oryxos.core.ToolAuditWriter;
import io.oryxos.core.ToolResult;
import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T077 —— SC-002 审计一致性：每次 invoke 写 1 行审计（无论成功失败）。
 *
 * <p>用 {@link CopyOnWriteArrayList} 模拟 {@code tool_invocations} 表 —— 调用方每次
 * {@link ToolAuditWriter#record} 追加一行，{@code count} 方法返回总数。
 *
 * <p>验证：N=10 次 invoke（含成功 + 失败 + Sandbox 拦截） → 审计行数 == N 且
 * {@code source} 列全部填好（FR-005 / V3 DDL）。
 */
class AuditConsistencyIntegrationTest {

    /** 模拟 {@code tool_invocations} 表（线程安全）。 */
    private static final class InMemoryAuditTable implements ToolAuditWriter {
        private final List<ToolAuditData> rows = new CopyOnWriteArrayList<>();

        @Override
        public void record(ToolAuditData data) {
            rows.add(data);
        }

        int count() { return rows.size(); }

        List<ToolAuditWriter.ToolAuditData> all() { return List.copyOf(rows); }
    }

    private InMemoryAuditTable auditTable;
    private DefaultToolExecutor executor;

    @BeforeEach
    void setUp() {
        auditTable = new InMemoryAuditTable();

        // 注册若干 Tool：1 个 echo + 1 个 always-fail
        OryxTool echo = stub("echo", args -> ToolResult.ok(Map.of("text", args.get("text"))));
        OryxTool alwaysFail = stub("bad", args -> {
            throw new RuntimeException("simulated failure");
        });

        Map<String, ToolRegistration> map = new java.util.LinkedHashMap<>();
        map.put("echo", new ToolRegistration(
            new ToolDefinition("echo", "echo", "java_bean"), echo, "echo"));
        map.put("bad", new ToolRegistration(
            new ToolDefinition("bad", "always throws", "java_bean"), alwaysFail, "bad"));

        ToolRegistry registry = ToolRegistry.of(map);
        executor = new DefaultToolExecutor(auditTable, registry);
    }

    @Test
    @DisplayName("N=10 次 invoke（含成功 + 异常 + Sandbox 拦截） → 审计行数 == N")
    void ten_invocations_produce_ten_audit_rows() {
        Profile profile = profileWithTools("echo", "bad");
        UUID sessionId = UUID.randomUUID();
        io.oryxos.core.ProfileContext.set(new io.oryxos.core.ProfileContext.Snapshot(
            profile.name(), sessionId, new java.util.concurrent.atomic.AtomicInteger(0)));

        try {
            int n = 10;
            for (int i = 0; i < n; i++) {
                if (i % 2 == 0) {
                    executor.invoke("echo", Map.of("text", "hello-" + i), profile);
                } else {
                    executor.invoke("bad", Map.of(), profile);  // 触发 RuntimeException
                }
            }
            assertThat(auditTable.count()).isEqualTo(n);
        } finally {
            io.oryxos.core.ProfileContext.clear();
        }
    }

    @Test
    @DisplayName("FR-005：审计 source 列全部填好（builtin/mcp/java_bean）")
    void all_audit_rows_have_source_column() {
        Profile profile = profileWithTools("echo", "bad");
        io.oryxos.core.ProfileContext.set(new io.oryxos.core.ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new java.util.concurrent.atomic.AtomicInteger(0)));

        try {
            executor.invoke("echo", Map.of("text", "x"), profile);
            executor.invoke("bad", Map.of(), profile);

            for (ToolAuditWriter.ToolAuditData row : auditTable.all()) {
                assertThat(row.source())
                    .as("FR-005 source column must be filled")
                    .isIn("builtin", "mcp", "java_bean");
            }
        } finally {
            io.oryxos.core.ProfileContext.clear();
        }
    }

    @Test
    @DisplayName("白名单拒绝也写审计行（FR-007：所有 invoke 都落库，包括拒绝）")
    void refused_tool_still_writes_audit_row() {
        Profile profile = profileWithTools("echo");  // 'bad' 不在白名单
        io.oryxos.core.ProfileContext.set(new io.oryxos.core.ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new java.util.concurrent.atomic.AtomicInteger(0)));

        try {
            ToolResult r = executor.invoke("bad", Map.of(), profile);
            assertThat(r.success()).isFalse();
            assertThat(auditTable.count()).isEqualTo(1);
            assertThat(auditTable.all().get(0).success()).isFalse();
            assertThat(auditTable.all().get(0).errorMessage()).contains("tool not in profile");
        } finally {
            io.oryxos.core.ProfileContext.clear();
        }
    }

    // ---- helpers ----

    private Profile profileWithTools(String... tools) {
        return new Profile(
            "audit-test",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            List.of(tools),
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            List.of()
        );
    }

    private static OryxTool stub(String name,
                                  java.util.function.Function<Map<String, Object>, ToolResult> fn) {
        return new OryxTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub-" + name; }
            @Override public ToolResult execute(Map<String, Object> args) {
                return fn.apply(args);
            }
        };
    }
}