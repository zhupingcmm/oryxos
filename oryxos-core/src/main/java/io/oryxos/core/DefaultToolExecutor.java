package io.oryxos.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * US-2 / US-4 阶段的 {@link ToolExecutor} 实现 —— 真正派发 Tool 调用 + 审计写入。
 *
 * <p>行为表（[contracts/ToolExecutor.md §4](../../../../../specs/002-react-loop/contracts/ToolExecutor.md)）：
 * <ul>
 *   <li>{@code toolName} 不在 {@code profile.tools()} → 返回 {@link ToolResult#error}("tool not in profile: ...")，审计行 success=false</li>
 *   <li>{@code toolName} 在白名单 → US-2 抛 {@link UnsupportedOperationException}（保留 stub 语义）；
 *       US-4 替换为真实 Tool 派发（{@link OryxTool#execute}）</li>
 *   <li>每次 invoke MUST 写一行 {@link ToolAuditWriter.ToolAuditData}（无论成功失败）</li>
 * </ul>
 *
 * <p>C-TE-3：从 {@code ProfileContext.current()} 捕获 {@code sessionId} + {@code sessionIteration}。
 * C-TE-9：审计写入由调用方控制事务边界；本类不强制 Spring 事务包裹。
 */
@Component
public class DefaultToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutor.class);

    private final ToolAuditWriter auditWriter;

    public DefaultToolExecutor() {
        this(new ToolAuditWriter.NoopToolAuditWriter());
    }

    public DefaultToolExecutor(ToolAuditWriter auditWriter) {
        this.auditWriter = auditWriter == null
            ? new ToolAuditWriter.NoopToolAuditWriter() : auditWriter;
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments, Profile profile) {
        long startedAtNanos = System.nanoTime();
        Instant startedAt = Instant.now();
        int iteration = currentIteration();
        boolean whitelisted = profile.tools().contains(toolName);
        long durationMs;

        if (!whitelisted) {
            String message = "tool not in profile: " + toolName;
            durationMs = elapsedMs(startedAtNanos);
            writeAudit(profile, toolName, arguments, false, message, durationMs, startedAt, iteration);
            log.info("tool.refused profile={} tool={} reason={}", profile.name(), toolName, message);
            return ToolResult.error(message);
        }

        // 白名单通过 —— US-2 抛 UOE（保留 stub 语义），US-4 替换为真实 Tool 派发
        durationMs = elapsedMs(startedAtNanos);
        writeAudit(profile, toolName, arguments, false, "US-2 stub: tool not yet implemented",
            durationMs, startedAt, iteration);
        log.info("tool.unsupported profile={} tool={} reason=US-2-stub", profile.name(), toolName);
        throw new UnsupportedOperationException(
            "Default stub — Tool '" + toolName + "' not implemented in US-2");
    }

    private void writeAudit(Profile profile, String toolName, Map<String, Object> arguments,
                            boolean success, String errorMessage, long durationMs,
                            Instant startedAt, int sessionIteration) {
        try {
            auditWriter.record(new ToolAuditWriter.ToolAuditData(
                currentSessionId(),
                profile.name(),
                toolName,
                arguments,
                success,
                errorMessage,
                durationMs,
                startedAt,
                sessionIteration
            ));
        } catch (RuntimeException ex) {
            // C-TE-9：审计写入失败不阻塞主流程（spec NFR-002）
            log.warn("tool.audit.failed profile={} tool={} error={}",
                profile.name(), toolName, ex.getMessage());
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static UUID currentSessionId() {
        Optional<ProfileContext.Snapshot> ctx = ProfileContext.current();
        return ctx.map(ProfileContext.Snapshot::sessionId).orElse(null);
    }

    private static int currentIteration() {
        Optional<ProfileContext.Snapshot> ctx = ProfileContext.current();
        return ctx
            .map(ProfileContext.Snapshot::currentIteration)
            .map(AtomicInteger::get)
            .orElse(0);
    }
}