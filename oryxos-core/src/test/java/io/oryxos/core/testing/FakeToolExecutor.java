package io.oryxos.core.testing;

import io.oryxos.core.Profile;
import io.oryxos.core.ProfileContext;
import io.oryxos.core.ToolAuditWriter;
import io.oryxos.core.ToolExecutor;
import io.oryxos.core.ToolResult;

import java.util.Optional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ToolExecutor} 的测试替身 —— 按 {@code Map<toolName, ToolResult>} 查表。
 *
 * <p>同时按调用顺序捕获 {@link Call} 记录（{@link #calls()}），便于顺序断言。
 *
 * <p>工具未注册时：
 * <ul>
 *   <li>若 {@code missingBehavior == AllowAsError}：返回 {@code ToolResult.error("tool not in profile: <name>")}（与 C-TE-1 一致）</li>
 *   <li>若 {@code missingBehavior == FailHard}：抛 {@code IllegalStateException}（让测试即时失败）</li>
 * </ul>
 */
public final class FakeToolExecutor implements ToolExecutor {

    public enum MissingBehavior { AllowAsError, FailHard }

    public record Call(String toolName, Map<String, Object> arguments, Profile profile) {}

    private final Map<String, ToolResult> table;
    private final MissingBehavior missingBehavior;
    private final ToolAuditWriter auditWriter;
    private final List<Call> captured = new ArrayList<>();

    public FakeToolExecutor(Map<String, ToolResult> table) {
        this(table, MissingBehavior.AllowAsError, new ToolAuditWriter.NoopToolAuditWriter());
    }

    public FakeToolExecutor(Map<String, ToolResult> table, MissingBehavior missingBehavior) {
        this(table, missingBehavior, new ToolAuditWriter.NoopToolAuditWriter());
    }

    public FakeToolExecutor(Map<String, ToolResult> table, ToolAuditWriter auditWriter) {
        this(table, MissingBehavior.AllowAsError, auditWriter);
    }

    public FakeToolExecutor(Map<String, ToolResult> table, MissingBehavior missingBehavior, ToolAuditWriter auditWriter) {
        this.table = table == null ? Map.of() : Map.copyOf(table);
        this.missingBehavior = Objects.requireNonNull(missingBehavior, "missingBehavior");
        this.auditWriter = auditWriter == null
            ? new ToolAuditWriter.NoopToolAuditWriter() : auditWriter;
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments, Profile profile) {
        captured.add(new Call(toolName, arguments, profile));
        ToolResult result = table.get(toolName);
        ToolResult actual;
        if (result != null) {
            actual = result;
        } else {
            actual = switch (missingBehavior) {
                case AllowAsError -> ToolResult.error("tool not in profile: " + toolName);
                case FailHard -> throw new IllegalStateException(
                    "FakeToolExecutor: no preset for toolName='" + toolName
                        + "' (set a Map entry, change to AllowAsError, "
                        + "or include it in profile.tools())");
            };
        }
        try {
            Optional<ProfileContext.Snapshot> ctx = ProfileContext.current();
            java.util.UUID sid = ctx.map(ProfileContext.Snapshot::sessionId).orElse(null);
            int iter = ctx
                .map(ProfileContext.Snapshot::currentIteration)
                .map(java.util.concurrent.atomic.AtomicInteger::get)
                .orElse(0);
            auditWriter.record(new ToolAuditWriter.ToolAuditData(
                sid,
                profile.name(), toolName, arguments,
                actual.success(),
                actual.success() ? null : actual.errorMessage(),
                0L,
                java.time.Instant.now(),
                iter,
                null,            // channel (T024: Notify-specific)
                null,            // notifyStatusCode (T024: Notify-specific)
                "builtin"        // source (T008: FQCN-derived)
            ));
        } catch (RuntimeException ignore) {
            // C-TE-9：审计失败不阻塞主流程
        }
        return actual;
    }

    /** 已发生的调用记录（按调用顺序）。 */
    public List<Call> calls() {
        return List.copyOf(captured);
    }

    public int invocationCount() {
        return captured.size();
    }

    public static Map<String, ToolResult> emptyTable() {
        return Map.of();
    }
}
