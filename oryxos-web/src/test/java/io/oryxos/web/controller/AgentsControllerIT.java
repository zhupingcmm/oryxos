package io.oryxos.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.AgentService;
import io.oryxos.core.InMemoryProfileRegistry;
import io.oryxos.core.LoopResult;
import io.oryxos.core.Message;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.Provider;
import io.oryxos.core.Session;
import io.oryxos.core.scheduler.SessionFactory;
import io.oryxos.core.scheduler.TaskExecutionRecorder;
import io.oryxos.web.dto.InvokeRequest;
import io.oryxos.web.exception.AgentNotFoundException;
import io.oryxos.web.exception.AgentTimeoutException;
import io.oryxos.storage.entity.SessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T011 — AgentsController 集成测试 (US-1 P1 MVP).
 *
 * <p>覆盖 spec 验收场景 1 (基础 invoke) + 验收场景 2 (404) + 验收场景 3 (validation 400).
 *
 * <p>用 {@code @MockBean ProfileRegistry} + Mockito 桩返回真实 registry,
 * 避免 @TestConfiguration 在多个 IT 类之间共享导致 5 个 ProfileRegistry bean 冲突.
 */
@WebMvcTest(controllers = AgentsController.class)
@org.springframework.test.context.ContextConfiguration(classes = StubBootApp.class)
class AgentsControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentService agentService;

    @MockBean
    private SessionFactory sessionFactory;

    @MockBean
    private TaskExecutionRecorder taskExecutionRecorder;

    // Spring's @ComponentScan("io.oryxos.web") pulls in ALL controllers on classpath;
    // we MUST @MockBean every dependency other controllers need, even if not used here.
    @MockBean
    private io.oryxos.storage.repository.SessionRepository sessionRepository;
    @MockBean
    private io.oryxos.core.tool.ToolRegistry toolRegistry;
    @MockBean
    private io.oryxos.memory.MemoryService memoryService;
    @MockBean
    private org.springframework.boot.actuate.health.HealthEndpoint healthEndpoint;
    @MockBean
    private ProfileRegistry profileRegistry;

    @BeforeEach
    void stubProfileRegistry() {
        Provider p = new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY",
            Map.of("temperature", 0.7));
        Profile weatherAgent = new Profile(
            "weather-agent", p, List.of("http_get"),
            List.of(), List.of(), List.of(),
            Profile.Settings.defaults(),
            Map.of(), List.of()
        );
        ProfileRegistry real = InMemoryProfileRegistry.of(weatherAgent);
        when(profileRegistry.find("weather-agent")).thenAnswer(inv -> real.find("weather-agent"));
        when(profileRegistry.find(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> real.find((String) inv.getArgument(0)));
    }

    @Test
    @DisplayName("US-1 验收场景 1: POST /api/v1/agents/weather-agent/invoke 成功 → 200 + InvokeResponse")
    void invokeSuccess() throws Exception {
        // Arrange
        UUID sessionId = UUID.randomUUID();
        Session mockSession = makeSession(sessionId, "weather-agent");
        when(sessionFactory.create(eq("weather-agent"), eq(null), eq("web"))).thenReturn(mockSession);

        LoopResult result = new LoopResult(
            "今天上海晴，25℃", 3, false, "weather-agent", sessionId);
        when(agentService.process(any(Session.class), anyString())).thenReturn(result);

        when(taskExecutionRecorder.record(
            any(TaskExecutionRecorder.ExecutionContext.class),
            any(Instant.class), anyLong(), any(Boolean.class), any()
        )).thenReturn(UUID.randomUUID().toString());

        InvokeRequest req = new InvokeRequest("上海今天天气如何？", null, null, null);

        // Act + Assert
        mockMvc.perform(post("/api/v1/agents/weather-agent/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reply").value("今天上海晴，25℃"))
            .andExpect(jsonPath("$.session_id").value(sessionId.toString()))
            .andExpect(jsonPath("$.iterations").value(3))
            .andExpect(jsonPath("$.duration_ms").exists());

        // Verify task_executions 写入 (success=true)
        ArgumentCaptor<TaskExecutionRecorder.ExecutionContext> ctxCap =
            ArgumentCaptor.forClass(TaskExecutionRecorder.ExecutionContext.class);
        verify(taskExecutionRecorder, times(1)).record(
            ctxCap.capture(), any(Instant.class), anyLong(), eq(true), eq(null));
        assertEquals("web:" + sessionId, ctxCap.getValue().taskId());
        assertEquals(sessionId.toString(), ctxCap.getValue().sessionId());
        assertEquals("web", ctxCap.getValue().triggerSource());
    }

    @Test
    @DisplayName("US-1 验收场景 2: unknown agent → 404 agent_not_found")
    void invokeAgentNotFound() throws Exception {
        InvokeRequest req = new InvokeRequest("hello", null, null, null);

        mockMvc.perform(post("/api/v1/agents/unknown-agent/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("agent_not_found"));

        verify(agentService, never()).process(any(), anyString());
    }

    @Test
    @DisplayName("US-1 验收场景 3: empty message → 400 invalid_request")
    void invokeValidationFailure() throws Exception {
        // empty message 触发 @NotBlank
        InvokeRequest req = new InvokeRequest("", null, null, null);

        mockMvc.perform(post("/api/v1/agents/weather-agent/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"))
            .andExpect(jsonPath("$.field").value("message"));

        verify(agentService, never()).process(any(), anyString());
    }

    @Test
    @DisplayName("US-1: 异常路径 → success=false 写入 task_executions + 异常向上传播")
    void invokeFailureRecordsAudit() throws Exception {
        UUID sessionId = UUID.randomUUID();
        Session mockSession = makeSession(sessionId, "weather-agent");
        when(sessionFactory.create(eq("weather-agent"), eq(null), eq("web"))).thenReturn(mockSession);
        when(agentService.process(any(Session.class), anyString()))
            .thenThrow(new IllegalStateException("LLM provider unreachable"));

        InvokeRequest req = new InvokeRequest("hello", null, null, null);

        mockMvc.perform(post("/api/v1/agents/weather-agent/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("internal_error"));

        // success=false 写入;errorMessage 已 sanitize (no stack trace)
        ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
        verify(taskExecutionRecorder).record(
            any(TaskExecutionRecorder.ExecutionContext.class),
            any(Instant.class), anyLong(), eq(false), errCap.capture());
        String errMsg = errCap.getValue();
        assertNotNull(errMsg);
        assertTrue(!errMsg.contains("\n\tat "), "errorMessage MUST NOT contain stack trace");
        assertTrue(errMsg.contains("LLM provider unreachable"));
    }

    @Test
    @DisplayName("US-1: 路径参数 / 空 agent 名 → 404 (NoHandlerFound)")
    void invokeMissingPathVariable() throws Exception {
        InvokeRequest req = new InvokeRequest("hello", null, null, null);
        // /api/v1/agents//invoke 路径不匹配 → NoHandlerFoundException
        mockMvc.perform(post("/api/v1/agents//invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNotFound());
    }

    // --- helpers ---

    private static Session makeSession(UUID id, String profileName) {
        return SessionEntity.create(id, profileName);
    }
}