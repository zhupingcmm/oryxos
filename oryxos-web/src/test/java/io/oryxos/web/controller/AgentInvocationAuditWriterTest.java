package io.oryxos.web.controller;

import io.oryxos.core.AgentService;
import io.oryxos.core.LoopResult;
import io.oryxos.core.Message;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.Provider;
import io.oryxos.core.Session;
import io.oryxos.core.InMemoryProfileRegistry;
import io.oryxos.core.scheduler.SessionFactory;
import io.oryxos.core.scheduler.TaskExecutionRecorder;
import io.oryxos.storage.entity.SessionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T013 — Sessions.metadata.source="web" + task_executions.trigger_source="web" 字节级断言.
 *
 * <p>spec FR-004 + 008-agent-scheduler data-model.md §实体 4 双向对齐.
 *
 * <p>绕开 @WebMvcTest 直接构造 AgentsController 调用 invoke(),用 mock 验证 metadata
 * 注入 + ExecutionContext 三参.
 */
class AgentInvocationAuditWriterTest {

    @Test
    @DisplayName("T013: invoke 调用 SessionFactory.create(profileName, null, \"web\") — byte-level per data-model.md §实体 4")
    void webSourcePassedToSessionFactory() throws Exception {
        AgentService agentService = mock(AgentService.class);
        SessionFactory sessionFactory = mock(SessionFactory.class);
        TaskExecutionRecorder recorder = mock(TaskExecutionRecorder.class);

        Provider p = new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY",
            Map.of("temperature", 0.7));
        Profile profile = new Profile(
            "weather-agent", p, List.of("http_get"),
            List.of(), List.of(), List.of(),
            Profile.Settings.defaults(),
            Map.of(), List.of()
        );
        ProfileRegistry registry = InMemoryProfileRegistry.of(profile);

        UUID sessionId = UUID.randomUUID();
        SessionEntity session = SessionEntity.create(sessionId, "weather-agent");
        when(sessionFactory.create(org.mockito.ArgumentMatchers.eq("weather-agent"),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq("web"))).thenReturn(session);

        LoopResult result = new LoopResult(
            "ok", 1, false, "weather-agent", sessionId);
        when(agentService.process(any(Session.class), anyString())).thenReturn(result);
        when(recorder.record(any(), any(Instant.class), anyLong(), anyBoolean(), any()))
            .thenReturn(UUID.randomUUID().toString());

        AgentsController controller = new AgentsController(
            agentService, sessionFactory, registry, recorder, 30_000L);

        var resp = controller.invoke("weather-agent",
            new io.oryxos.web.dto.InvokeRequest("hello", null, null, null));

        // assert InvokeResponse.sessionId 与 sessions.id 一致
        assertEquals(sessionId.toString(), resp.sessionId());

        // 验证 SessionFactory.create 被调, source 参数为 "web"（per spec FR-004 byte-level）
        // 真实 SessionFactoryImpl 会把 source 写入 metadata.source 字段;
        // 此处 mock 出 verify SessionFactory 接收了正确 source, 是 source 注入的入口契约断言.
        org.mockito.Mockito.verify(sessionFactory, org.mockito.Mockito.times(1))
            .create(org.mockito.ArgumentMatchers.eq("weather-agent"),
                    org.mockito.ArgumentMatchers.eq(null),
                    org.mockito.ArgumentMatchers.eq("web"));
    }

    @Test
    @DisplayName("T013: task_executions ExecutionContext 写 (taskId=\"web:<sessionId>\", sessionId=<sid>, triggerSource=\"web\")")
    void executionContextFieldsCorrect() throws Exception {
        AgentService agentService = mock(AgentService.class);
        SessionFactory sessionFactory = mock(SessionFactory.class);
        TaskExecutionRecorder recorder = mock(TaskExecutionRecorder.class);

        Provider p = new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY",
            Map.of("temperature", 0.7));
        Profile profile = new Profile(
            "weather-agent", p, List.of(), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), Map.of(), List.of()
        );
        ProfileRegistry registry = InMemoryProfileRegistry.of(profile);

        UUID sessionId = UUID.randomUUID();
        SessionEntity session = SessionEntity.create(sessionId, "weather-agent");
        when(sessionFactory.create(org.mockito.ArgumentMatchers.eq("weather-agent"),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq("web"))).thenReturn(session);

        LoopResult result = new LoopResult(
            "ok", 1, false, "weather-agent", sessionId);
        when(agentService.process(any(Session.class), anyString())).thenReturn(result);
        when(recorder.record(any(), any(Instant.class), anyLong(), anyBoolean(), any()))
            .thenReturn(UUID.randomUUID().toString());

        AgentsController controller = new AgentsController(
            agentService, sessionFactory, registry, recorder, 30_000L);

        controller.invoke("weather-agent",
            new io.oryxos.web.dto.InvokeRequest("hi", null, null, null));

        ArgumentCaptor<TaskExecutionRecorder.ExecutionContext> ctxCap =
            ArgumentCaptor.forClass(TaskExecutionRecorder.ExecutionContext.class);
        verify(recorder, times(1)).record(
            ctxCap.capture(), any(Instant.class), anyLong(), eq(true), eq(null));

        TaskExecutionRecorder.ExecutionContext ctx = ctxCap.getValue();
        assertEquals("web:" + sessionId, ctx.taskId(),
            "taskId MUST be \"web:<sessionId>\" per data-model.md §实体关系图修订");
        assertEquals(sessionId.toString(), ctx.sessionId());
        assertEquals("web", ctx.triggerSource(),
            "triggerSource MUST be \"web\" per data-model.md §实体关系图修订");
    }

    @Test
    @DisplayName("T013: invoke 失败路径 success=false 写入, errorMessage sanitize (无 stack trace)")
    void failurePathSanitizesError() throws Exception {
        AgentService agentService = mock(AgentService.class);
        SessionFactory sessionFactory = mock(SessionFactory.class);
        TaskExecutionRecorder recorder = mock(TaskExecutionRecorder.class);

        Provider p = new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY",
            Map.of("temperature", 0.7));
        Profile profile = new Profile(
            "weather-agent", p, List.of(), List.of(), List.of(), List.of(),
            Profile.Settings.defaults(), Map.of(), List.of()
        );
        ProfileRegistry registry = InMemoryProfileRegistry.of(profile);

        UUID sessionId = UUID.randomUUID();
        SessionEntity session = SessionEntity.create(sessionId, "weather-agent");
        when(sessionFactory.create(org.mockito.ArgumentMatchers.eq("weather-agent"),
                org.mockito.ArgumentMatchers.eq(null),
                org.mockito.ArgumentMatchers.eq("web"))).thenReturn(session);

        // 抛一个含 stack trace 的异常 (使用真实异常而不是 mock 内置)
        when(agentService.process(any(Session.class), anyString()))
            .thenAnswer(inv -> {
                throw new IllegalStateException("LLM error\n\tat com.example.Foo.bar(Foo.java:42)");
            });
        when(recorder.record(any(), any(Instant.class), anyLong(), anyBoolean(), any()))
            .thenReturn(UUID.randomUUID().toString());

        AgentsController controller = new AgentsController(
            agentService, sessionFactory, registry, recorder, 30_000L);

        try {
            controller.invoke("weather-agent",
                new io.oryxos.web.dto.InvokeRequest("hi", null, null, null));
        } catch (IllegalStateException expected) {
            // expected
        }

        ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
        verify(recorder).record(
            any(TaskExecutionRecorder.ExecutionContext.class),
            any(Instant.class), anyLong(), eq(false), errCap.capture());

        String errMsg = errCap.getValue();
        assertNotNull(errMsg);
        assertTrue(!errMsg.contains("\n\tat "),
            "errorMessage MUST NOT contain stack trace (per 007-sandbox-whitelist FR-007)");
        assertTrue(errMsg.startsWith("LLM error"),
            "errorMessage MUST start with original exception message");
    }
}