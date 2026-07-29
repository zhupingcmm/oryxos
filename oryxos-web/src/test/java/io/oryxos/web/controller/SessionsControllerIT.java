package io.oryxos.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.InMemoryProfileRegistry;
import io.oryxos.core.Message;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.Provider;
import io.oryxos.core.Session;
import io.oryxos.core.scheduler.SessionFactory;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import io.oryxos.web.dto.CreateSessionRequest;
import io.oryxos.web.dto.MessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T018 + US-2 验收场景 1-5 — SessionsController 集成测试.
 *
 * <p>覆盖 4 端点: POST /api/v1/sessions, POST /api/v1/sessions/{id}/messages,
 * GET /api/v1/sessions/{id}, DELETE /api/v1/sessions/{id}.
 *
 * <p>{@code @MockBean ProfileRegistry} + Mockito 桩,避免 @TestConfiguration
 * 在多 IT 类之间共享导致 5 个 ProfileRegistry bean 冲突.
 */
@WebMvcTest(controllers = SessionsController.class)
@org.springframework.test.context.ContextConfiguration(classes = StubBootApp.class)
class SessionsControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SessionFactory sessionFactory;

    @MockBean
    private SessionRepository sessionRepository;

    // Spring's @ComponentScan("io.oryxos.web") pulls in ALL controllers; even if our
    // test class only targets SessionsController, we MUST @MockBean every dependency
    // AgentsController consumes to keep the ApplicationContext satisfiable.
    @MockBean
    private io.oryxos.core.AgentService agentService;

    @MockBean
    private io.oryxos.core.scheduler.TaskExecutionRecorder taskExecutionRecorder;

    // ProfilesController / MemoryController / ToolsController / SystemController deps.
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

    // ===== POST /api/v1/sessions =====

    @Test
    @DisplayName("US-2 验收场景 1: POST /api/v1/sessions 成功 → 201 + Location header + SessionDto")
    void createSuccess() throws Exception {
        UUID sid = UUID.randomUUID();
        Session mockSession = makeSession(sid, "weather-agent");
        when(sessionFactory.create(eq("weather-agent"), eq(null), eq("web"))).thenReturn(mockSession);
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateSessionRequest req = new CreateSessionRequest("weather-agent", Map.of("customer_id", "C-12345"));

        mockMvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/sessions/" + sid))
            .andExpect(jsonPath("$.session_id").value(sid.toString()))
            .andExpect(jsonPath("$.profile_name").value("weather-agent"))
            .andExpect(jsonPath("$.message_count").value(0))
            .andExpect(jsonPath("$.history").isArray())
            .andExpect(jsonPath("$.history.length()").value(0));

        verify(sessionFactory, times(1)).create(eq("weather-agent"), eq(null), eq("web"));
    }

    @Test
    @DisplayName("US-2 边界: missing profileName → 400 invalid_request")
    void createMissingProfileName() throws Exception {
        String body = "{\"metadata\":{\"customer_id\":\"C-12345\"}}";

        mockMvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));

        verify(sessionFactory, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("US-2 边界: unknown agent → 404 agent_not_found")
    void createAgentNotFound() throws Exception {
        CreateSessionRequest req = new CreateSessionRequest("unknown-agent", Map.of());

        mockMvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("agent_not_found"));

        verify(sessionFactory, never()).create(any(), any(), any());
    }

    // ===== POST /api/v1/sessions/{id}/messages =====

    @Test
    @DisplayName("US-2 验收场景 2: 追加消息 → 201 + 消息被持久化 + updated_at 更新")
    void addMessageSuccess() throws Exception {
        UUID sid = UUID.randomUUID();
        SessionEntity mockSession = makeSessionEntity(sid, "weather-agent");
        when(sessionRepository.findByIdAndDeletedAtIsNull(sid)).thenReturn(Optional.of(mockSession));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        MessageDto msg = new MessageDto("user", "今天有什么新闻?", null, Instant.parse("2026-07-28T01:00:00Z"));

        mockMvc.perform(post("/api/v1/sessions/" + sid + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(msg)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.session_id").value(sid.toString()))
            .andExpect(jsonPath("$.message_count").value(1))
            .andExpect(jsonPath("$.message.role").value("user"));

        // 验证 save 被调用
        verify(sessionRepository, times(1)).save(any(SessionEntity.class));
    }

    @Test
    @DisplayName("US-2 边界: 非法 role 枚举 → 400 invalid_request")
    void addMessageInvalidRole() throws Exception {
        UUID sid = UUID.randomUUID();
        MessageDto msg = new MessageDto("bogus-role", "content", null, Instant.now());

        mockMvc.perform(post("/api/v1/sessions/" + sid + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(msg)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));

        verify(sessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("US-2 边界: 不存在的 session_id → 404 session_not_found")
    void addMessageSessionNotFound() throws Exception {
        UUID sid = UUID.randomUUID();
        when(sessionRepository.findByIdAndDeletedAtIsNull(sid)).thenReturn(Optional.empty());

        MessageDto msg = new MessageDto("user", "content", null, Instant.now());

        mockMvc.perform(post("/api/v1/sessions/" + sid + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(msg)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("session_not_found"));
    }

    // ===== GET /api/v1/sessions/{id} =====

    @Test
    @DisplayName("US-2 验收场景 3: GET 成功 → 200 + SessionDto 全字段")
    void getSuccess() throws Exception {
        UUID sid = UUID.randomUUID();
        SessionEntity mockSession = makeSessionEntity(sid, "weather-agent");
        when(sessionRepository.findByIdAndDeletedAtIsNull(sid)).thenReturn(Optional.of(mockSession));

        mockMvc.perform(get("/api/v1/sessions/" + sid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.session_id").value(sid.toString()))
            .andExpect(jsonPath("$.profile_name").value("weather-agent"))
            .andExpect(jsonPath("$.message_count").value(0))
            .andExpect(jsonPath("$.metadata.source").value("web"))
            .andExpect(jsonPath("$.history").isArray());
    }

    @Test
    @DisplayName("US-2 验收场景 3: includeHistory=false → history 为空数组")
    void getWithoutHistory() throws Exception {
        UUID sid = UUID.randomUUID();
        SessionEntity mockSession = makeSessionEntity(sid, "weather-agent");
        when(sessionRepository.findByIdAndDeletedAtIsNull(sid)).thenReturn(Optional.of(mockSession));

        mockMvc.perform(get("/api/v1/sessions/" + sid).param("includeHistory", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.history").isArray())
            .andExpect(jsonPath("$.history.length()").value(0));
    }

    @Test
    @DisplayName("US-2 边界: 不存在的 session_id → 404 session_not_found")
    void getNotFound() throws Exception {
        UUID sid = UUID.randomUUID();
        when(sessionRepository.findByIdAndDeletedAtIsNull(sid)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/sessions/" + sid))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("session_not_found"));
    }

    // ===== DELETE /api/v1/sessions/{id} =====

    @Test
    @DisplayName("US-2 验收场景 5: DELETE → 204 No Content + 空 body")
    void deleteSuccess() throws Exception {
        UUID sid = UUID.randomUUID();
        SessionEntity mockSession = makeSessionEntity(sid, "weather-agent");
        when(sessionRepository.findByIdAndDeletedAtIsNull(sid)).thenReturn(Optional.of(mockSession));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(delete("/api/v1/sessions/" + sid))
            .andExpect(status().isNoContent())
            .andExpect(jsonPath("$").doesNotExist());

        verify(sessionRepository, times(1)).save(any(SessionEntity.class));
    }

    // ===== helpers =====

    private static Session makeSession(UUID id, String profileName) {
        return SessionEntity.create(id, profileName);
    }

    private static SessionEntity makeSessionEntity(UUID id, String profileName) {
        SessionEntity e = SessionEntity.create(id, profileName);
        e.setMetadataValue("source", "web");
        return e;
    }
}
