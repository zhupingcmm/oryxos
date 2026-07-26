package io.oryxos.core;

import io.oryxos.core.tool.ToolRegistry;
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
 * <p>US-4 起：通过 {@link ToolRegistry#find(String)} 拿到实现并派发；
 * 白名单 / 注册表双层检查，任一不满足都返回 {@link ToolResult#error}。
 *
 * <p>行为表：
 * <ul>
 *   <li>{@code toolName} 不在 {@code profile.tools()} → 返回 {@code ToolResult.error("tool not in profile: ...")}
 *       （审计行 success=false，channel=null，notifyStatusCode=null）</li>
 *   <li>{@code toolName} 在白名单但 {@code ToolRegistry} 未注册 →
 *       返回 {@code ToolResult.error("tool not registered: ...")}（审计行 success=false）</li>
 *   <li>{@code toolName} 在白名单且已注册 → 调 {@link OryxTool#execute} 派发；
 *       {@code ToolResult} 透传（审计行 success / errorMessage 取 ToolResult 字段）</li>
 *   <li>每次 invoke MUST 写一行审计（C-TE-2）——无论成功失败</li>
 * </ul>
 *
 * <p>C-TE-3：从 {@code ProfileContext.current()} 捕获 {@code sessionId} + {@code sessionIteration}。
 * C-TE-9：审计写入由调用方控制事务边界；本类不强制 Spring 事务包裹。
 */
@Component
public class DefaultToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolExecutor.class);

    private final ToolAuditWriter auditWriter;
    private final ToolRegistry toolRegistry;

    public DefaultToolExecutor() {
        this(new ToolAuditWriter.NoopToolAuditWriter(), null);
    }

    public DefaultToolExecutor(ToolAuditWriter auditWriter) {
        this(auditWriter, null);
    }

    /**
     * 完整构造（US-4 起）。
     *
     * @param auditWriter 审计写入器（null → Noop）
     * @param toolRegistry  Tool 注册表（null → 走 stub 语义：白名单通过仍抛 UOE，
     *                      保留 US-2 测试行为兼容）
     */
    public DefaultToolExecutor(ToolAuditWriter auditWriter, ToolRegistry toolRegistry) {
        this.auditWriter = auditWriter == null
            ? new ToolAuditWriter.NoopToolAuditWriter() : auditWriter;
        this.toolRegistry = toolRegistry;
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
            writeAudit(profile, toolName, arguments, false, message, durationMs, startedAt, iteration,
                null, null);
            log.info("tool.refused profile={} tool={} reason={}", profile.name(), toolName, message);
            return ToolResult.error(message);
        }

        // 白名单通过 —— 派发或 stub
        if (toolRegistry == null) {
            // 兼容 US-2 stub 语义：未注入 ToolRegistry → 抛 UOE
            durationMs = elapsedMs(startedAtNanos);
            writeAudit(profile, toolName, arguments, false, "US-2 stub: tool not yet implemented",
                durationMs, startedAt, iteration, null, null);
            log.info("tool.unsupported profile={} tool={} reason=US-2-stub", profile.name(), toolName);
            throw new UnsupportedOperationException(
                "Default stub — Tool '" + toolName + "' not implemented in US-2");
        }

        Optional<OryxTool> toolOpt = toolRegistry.find(toolName);
        if (toolOpt.isEmpty()) {
            String message = "tool not registered: " + toolName;
            durationMs = elapsedMs(startedAtNanos);
            writeAudit(profile, toolName, arguments, false, message, durationMs, startedAt, iteration,
                null, null);
            log.info("tool.unregistered profile={} tool={}", profile.name(), toolName);
            return ToolResult.error(message);
        }

        // 派发
        ToolResult result;
        try {
            result = toolOpt.get().execute(arguments);
        } catch (RuntimeException ex) {
            durationMs = elapsedMs(startedAtNanos);
            String message = "tool execution failed: " + ex.getMessage();
            writeAudit(profile, toolName, arguments, false, message, durationMs, startedAt, iteration,
                null, null);
            log.warn("tool.execute.failed profile={} tool={} error={}",
                profile.name(), toolName, ex.getMessage());
            return ToolResult.error(message);
        }

        durationMs = elapsedMs(startedAtNanos);
        // Notify 工具（US-4 004 spec FR-007..013）从 payload 抽取 channel + status_code 写审计
        NotifyAuditFields notify = extractNotifyAuditFields(toolName, result);
        writeAudit(profile, toolName, arguments, result.success(), result.errorMessage(),
            durationMs, startedAt, iteration, notify.channel(), notify.statusCode());
        log.info("tool.dispatched profile={} tool={} success={} channel={} notifyStatus={}",
            profile.name(), toolName, result.success(), notify.channel(), notify.statusCode());
        return result;
    }

    /**
     * Notify 工具审计字段抽取 —— 仅 {@code toolName == "notify"} 时生效。
     *
     * <p>{@link io.oryxos.tool.notify.NotifyTool} 在 ToolResult payload 里写
     * {@code channel}（String）+ {@code status_code}（Integer）两个字段；
     * 本方法把它们读出来写到 {@code tool_invocations.channel} +
     * {@code tool_invocations.notify_status_code} 列（spec §7）。
     *
     * <p>非 notify 工具返回 {@code (null, null)}，不影响既有行为（I-NT-4）。
     */
    private NotifyAuditFields extractNotifyAuditFields(String toolName, ToolResult result) {
        if (!"notify".equals(toolName) || result == null || result.payload() == null) {
            return NotifyAuditFields.EMPTY;
        }
        Map<String, Object> payload = result.payload();
        Object channelRaw = payload.get("channel");
        Object statusRaw = payload.get("status_code");
        String channel = channelRaw instanceof String s ? s : null;
        Integer statusCode = statusRaw instanceof Number n ? n.intValue() : null;
        return new NotifyAuditFields(channel, statusCode);
    }

    /** Notify 审计字段对（不可变 record）。 */
    private record NotifyAuditFields(String channel, Integer statusCode) {
        static final NotifyAuditFields EMPTY = new NotifyAuditFields(null, null);
    }

    /**
     * 审计写入（带 Notify 字段）。{@code channel} / {@code notifyStatusCode} 仅 notify 工具填；
     * 其他工具传 null（MVP 阶段由调用方决定具体语义）。
     */
    private void writeAudit(Profile profile, String toolName, Map<String, Object> arguments,
                            boolean success, String errorMessage, long durationMs,
                            Instant startedAt, int sessionIteration,
                            String channel, Integer notifyStatusCode) {
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
                sessionIteration,
                channel,
                notifyStatusCode
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