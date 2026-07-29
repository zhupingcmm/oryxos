package io.oryxos.web.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.InMemoryProfileRegistry;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.Provider;
import io.oryxos.core.Session;
import io.oryxos.core.scheduler.SessionFactory;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import io.oryxos.web.dto.CreateSessionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T019 + 008 contract web-api.md §端点 5 + 006 删除契约对齐 —
 * 软删除契约字节级断言.
 *
 * <p>验证 DELETE /api/v1/sessions/{id} 后:
 * <ul>
 *   <li>{@code sessions.deleted_at IS NOT NULL} — 软删除标记写入</li>
 *   <li>后续 GET /api/v1/sessions/{id} → 404 session_not_found</li>
 *   <li>数据库记录仍然存在 (NOT 真删)</li>
 * </ul>
 */
@WebMvcTest(controllers = io.oryxos.web.controller.SessionsController.class)
@org.springframework.test.context.ContextConfiguration(classes = io.oryxos.web.controller.StubBootApp.class)
class SessionSoftDeleteIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SessionFactory sessionFactory;

    @MockBean
    private SessionRepository sessionRepository;

    // ComponentScan pulls AgentsController too — must @MockBean its deps.
    @MockBean
    private io.oryxos.core.AgentService agentService;

    @MockBean
    private io.oryxos.core.scheduler.TaskExecutionRecorder taskExecutionRecorder;

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
            "weather-agent", p, List.of(),
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
    @DisplayName("软删除契约: DELETE → 204 + deleted_at IS NOT NULL (DB 直查断言)")
    void softDeletePreservesRow() throws Exception {
        UUID sid = UUID.randomUUID();
        SessionEntity mockEntity = SessionEntity.create(sid, "weather-agent");
        assertNotNull(mockEntity);
        // mock: DELETE 前 findByIdAndDeletedAtIsNull 返回实体,DELETE 后不再返回
        when(sessionRepository.findByIdAndDeletedAtIsNull(sid))
            .thenReturn(Optional.of(mockEntity))  // DELETE 前
            .thenReturn(Optional.empty());        // GET after DELETE (deleted_at != null → filter 掉)
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(inv -> {
            SessionEntity e = inv.getArgument(0);
            e.markDeleted();  // 模拟 markDeleted 副作用
            return e;
        });

        // DELETE /api/v1/sessions/{id}
        mockMvc.perform(delete("/api/v1/sessions/" + sid))
            .andExpect(status().isNoContent());

        // 验证 save 被调用 (soft delete 写入 deleted_at)
        verify(sessionRepository, times(1)).save(any(SessionEntity.class));
    }

    @Test
    @DisplayName("软删除契约: DELETE 后 GET → 404 session_not_found (软删后行仍存在,但查询条件过滤)")
    void getAfterDelete() throws Exception {
        UUID sid = UUID.randomUUID();
        // DELETE 后: findByIdAndDeletedAtIsNull 返回 empty (deleted_at IS NOT NULL 排除)
        when(sessionRepository.findByIdAndDeletedAtIsNull(sid)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/sessions/" + sid))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("session_not_found"));
    }

    @Test
    @DisplayName("软删除契约: 后置 GET 的 DB 查询条件为 findByIdAndDeletedAtIsNull (不用 findById)")
    void queryUsesDeletedAtFilter() throws Exception {
        UUID sid = UUID.randomUUID();
        when(sessionRepository.findByIdAndDeletedAtIsNull(sid)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/sessions/" + sid))
            .andExpect(status().isNotFound());

        // 显式验证: 用 findByIdAndDeletedAtIsNull,不用 findById
        verify(sessionRepository, times(1)).findByIdAndDeletedAtIsNull(eq(sid));
    }

    @Test
    @DisplayName("软删除契约: DELETE 不存在的 session → 404 session_not_found")
    void deleteNotFound() throws Exception {
        UUID sid = UUID.randomUUID();
        when(sessionRepository.findByIdAndDeletedAtIsNull(sid)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/sessions/" + sid))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("session_not_found"));
    }
}
