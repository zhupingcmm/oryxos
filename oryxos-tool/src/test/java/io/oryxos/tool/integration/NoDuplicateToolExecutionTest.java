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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T078 —— SC-009 / FR-007：禁止重复 Tool 执行。
 *
 * <p>验证：
 * <ol>
 *   <li>1 次 {@code invoke} → EXACTLY 1 行审计（不会因 Spring AI 自动执行触发两次）</li>
 *   <li>execute() 内部调用计数 == 1（防止双执行）</li>
 * </ol>
 *
 * <p>对照 [CLAUDE.md §8 坑 #1]：启用 Spring AI 自动 Tool 执行会导致 Tool 被调两次；
 * 本测试是这条护栏的回归保护。
 */
class NoDuplicateToolExecutionTest {

    private static final class CountingTool implements OryxTool {
        final AtomicInteger execCount = new AtomicInteger();
        @Override public String name() { return "counting"; }
        @Override public String description() { return "counts executions"; }
        @Override public ToolResult execute(Map<String, Object> args) {
            execCount.incrementAndGet();
            return ToolResult.ok(Map.of("count", execCount.get()));
        }
    }

    private static final class CapturingAudit implements ToolAuditWriter {
        final List<ToolAuditData> rows = new CopyOnWriteArrayList<>();
        @Override public void record(ToolAuditData data) { rows.add(data); }
    }

    private CountingTool tool;
    private CapturingAudit audit;
    private DefaultToolExecutor executor;

    @BeforeEach
    void setUp() {
        tool = new CountingTool();
        audit = new CapturingAudit();
        Map<String, ToolRegistration> map = new java.util.LinkedHashMap<>();
        map.put("counting", new ToolRegistration(
            new ToolDefinition("counting", "counts", "java_bean"), tool, "counting"));
        ToolRegistry registry = ToolRegistry.of(map);
        executor = new DefaultToolExecutor(audit, registry);
    }

    @Test
    @DisplayName("1 次 invoke → Tool.execute() 恰好被调 1 次 + 恰好 1 行审计")
    void single_invoke_no_duplicate() {
        Profile profile = profileWithTools("counting");
        io.oryxos.core.ProfileContext.set(new io.oryxos.core.ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new java.util.concurrent.atomic.AtomicInteger(0)));

        try {
            ToolResult r = executor.invoke("counting", Map.of(), profile);
            assertThat(r.success()).isTrue();
            assertThat(tool.execCount.get()).isEqualTo(1);
            assertThat(audit.rows.size()).isEqualTo(1);
        } finally {
            io.oryxos.core.ProfileContext.clear();
        }
    }

    @Test
    @DisplayName("连续 3 次 invoke → 恰好 3 行审计 + execute 被调 3 次（无重复）")
    void three_invocations_no_duplicate() {
        Profile profile = profileWithTools("counting");
        io.oryxos.core.ProfileContext.set(new io.oryxos.core.ProfileContext.Snapshot(
            profile.name(), UUID.randomUUID(), new java.util.concurrent.atomic.AtomicInteger(0)));

        try {
            for (int i = 0; i < 3; i++) {
                executor.invoke("counting", Map.of(), profile);
            }
            assertThat(tool.execCount.get()).isEqualTo(3);
            assertThat(audit.rows.size()).isEqualTo(3);
        } finally {
            io.oryxos.core.ProfileContext.clear();
        }
    }

    private Profile profileWithTools(String... tools) {
        return new Profile(
            "no-dup-test",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            List.of(tools),
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            List.of()
        );
    }
}