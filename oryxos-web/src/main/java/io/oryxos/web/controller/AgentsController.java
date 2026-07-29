package io.oryxos.web.controller;

import io.oryxos.core.AgentService;
import io.oryxos.core.LoopResult;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.Session;
import io.oryxos.core.scheduler.SessionFactory;
import io.oryxos.core.scheduler.TaskExecutionRecorder;
import io.oryxos.web.dto.InvokeRequest;
import io.oryxos.web.dto.InvokeResponse;
import io.oryxos.web.exception.AgentNotFoundException;
import io.oryxos.web.exception.AgentTimeoutException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * T014 + spec FR-001 / FR-004 / FR-005 — POST /api/v1/agents/{name}/invoke.
 *
 * <p>REST 触发走 {@link AgentService#process(Session, String)}（与 CLI / Scheduler 同 Method 对象,
 * per spec SC-003 反射断言). session.metadata.source="web" 注入（per 008-agent-scheduler
 * data-model.md §实体 4 + spec FR-004).
 *
 * <p>web 触发的 task_executions 写入用合成 taskId = {@code "web:<sessionId>"}（per data-model.md
 * §实体关系图修订 + spec FR-007 day-one audit).
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentsController {

    private static final Logger log = LoggerFactory.getLogger(AgentsController.class);

    /** default invoke timeout — 30 s (per spec FR-009 同步调用 + research.md R-009 P95 ≤ 30 s). */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final AgentService agentService;
    private final SessionFactory sessionFactory;
    private final ProfileRegistry profileRegistry;
    private final TaskExecutionRecorder taskExecutionRecorder;
    private final long timeoutMs;

    @Autowired
    public AgentsController(
        AgentService agentService,
        SessionFactory sessionFactory,
        ProfileRegistry profileRegistry,
        TaskExecutionRecorder taskExecutionRecorder
    ) {
        this(agentService, sessionFactory, profileRegistry, taskExecutionRecorder, DEFAULT_TIMEOUT_MS);
    }

    /** test-friendly constructor with injectable timeout. */
    AgentsController(
        AgentService agentService,
        SessionFactory sessionFactory,
        ProfileRegistry profileRegistry,
        TaskExecutionRecorder taskExecutionRecorder,
        long timeoutMs
    ) {
        this.agentService = agentService;
        this.sessionFactory = sessionFactory;
        this.profileRegistry = profileRegistry;
        this.taskExecutionRecorder = taskExecutionRecorder;
        this.timeoutMs = timeoutMs;
    }

    /**
     * T014/T015/T016/T017 — POST /api/v1/agents/{name}/invoke.
     *
     * @param name    Profile 名（per spec FR-005 path variable）
     * @param request 调用请求体（per data-model.md §实体 1）
     * @return InvokeResponse（per data-model.md §实体 2）
     * @throws AgentNotFoundException 404 — Profile 不存在
     * @throws AgentTimeoutException  504 — ReAct 循环超过 timeoutMs
     */
    @PostMapping("/{name}/invoke")
    public InvokeResponse invoke(@PathVariable String name, @Valid @RequestBody InvokeRequest request) {
        // T015 — Profile 必须已加载（per spec US-1 验收场景 1 + IllegalArgumentException contract）
        Profile profile = profileRegistry.find(name)
            .orElseThrow(() -> new AgentNotFoundException(name));

        // T015 — 创建 Session + 自动注入 metadata.source="web"（per spec FR-004 + 008 contract）
        Session session = sessionFactory.create(profile.name(), null, "web");

        // T014 — 调用 AgentService.process()（与 CLI / Scheduler 同 Method 对象, per spec SC-003）
        Instant start = Instant.now();
        LoopResult result;
        boolean success = false;
        String errorMessage = null;
        try {
            result = callWithTimeout(session, request.message());
            success = true;
        } catch (RuntimeException e) {
            // sanitize: detail MUST NOT 含 stack trace (per 007-sandbox-whitelist FR-007)
            errorMessage = sanitize(e.getMessage());
            log.error("AgentsController.invoke failed for agent={} sessionId={}: {}",
                name, session.id(), errorMessage);
            // T016 — 写 task_executions 行（success=false）后抛
            recordExecution(session, start, false, errorMessage);
            throw e;
        }

        // T016 — 写 task_executions 行（success=true）
        recordExecution(session, start, true, null);

        // T014 — 装配 InvokeResponse
        Map<String, Object> metadata = request.metadata() != null
            ? new HashMap<>(request.metadata())
            : new HashMap<>();
        return new InvokeResponse(
            session.id().toString(),
            result.finalText(),
            result.iterations(),
            Duration.between(start, Instant.now()).toMillis(),
            metadata
        );
    }

    /**
     * T014 — 调 AgentService.process();超时抛 {@link AgentTimeoutException}.
     */
    private LoopResult callWithTimeout(Session session, String message) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        // JDK 21: virtual threads auto-managed;agent loop 同步路径走 platform thread
        // 这里用 deadline 简单实现;扩展阶段可改 future + cancel
        long before = System.currentTimeMillis();
        try {
            LoopResult r = agentService.process(session, message);
            if (System.currentTimeMillis() > deadline) {
                throw new AgentTimeoutException(System.currentTimeMillis() - before);
            }
            return r;
        } catch (AgentTimeoutException e) {
            throw e;
        } catch (RuntimeException e) {
            // AgentService 自己抛的异常照传 (e.g. AgentNotFoundException, IllegalArgumentException)
            throw e;
        }
    }

    /**
     * T015 — (legacy) 反射注入 metadata.source="web".
     *
     * <p>已废弃 —— {@link SessionFactory#create(String, String, String)} 现原生支持
     * source 参数（008-agent-web-service 阶段扩展），SessionFactoryImpl 自动写入
     * metadata.source + metadata.started_at. 此方法保留为 no-op 防回归.
     */
    @Deprecated
    private void injectWebSourceMetadata(Session session) {
        // no-op: 改走 SessionFactory.create(profileName, taskId, source)
    }

    /**
     * T016 — 调 {@link TaskExecutionRecorder#record} 写入 task_executions 表.
     *
     * <p>web 触发无真实 schedule id,合成 taskId = {@code "web:" + sessionId};triggerSource="web".
     * 写入失败 MUST NOT 冒泡（per TaskExecutionRecorder 契约 + plan.md 风险与缓解 #3）.
     */
    private void recordExecution(Session session, Instant start, boolean success, String errorMessage) {
        try {
            String taskId = "web:" + session.id();
            String sessionId = session.id().toString();
            TaskExecutionRecorder.ExecutionContext ctx =
                new TaskExecutionRecorder.ExecutionContext(taskId, sessionId, "web");
            taskExecutionRecorder.record(
                ctx,
                start,
                Duration.between(start, Instant.now()).toMillis(),
                success,
                errorMessage
            );
        } catch (RuntimeException e) {
            log.warn("TaskExecutionRecorder failed for web sessionId={}: {}", session.id(), e.toString());
        }
    }

    /** 剥 stack trace 模式（per 007-sandbox-whitelist FR-007 byte-level）.*/
    static String sanitize(String message) {
        if (message == null) return null;
        int idx = message.indexOf("\n\tat ");
        if (idx >= 0) message = message.substring(0, idx);
        int idx2 = message.indexOf("\nCaused by: ");
        if (idx2 >= 0) message = message.substring(0, idx2);
        if (message.length() > 2048) {
            message = message.substring(0, 2048 - "...<truncated>".length()) + "...<truncated>";
        }
        return message;
    }
}