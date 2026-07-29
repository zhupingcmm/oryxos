package io.oryxos.web.contract;

import io.oryxos.core.InMemoryProfileRegistry;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.Provider;
import io.oryxos.core.scheduler.SessionFactory;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T020 + spec FR — UUID 格式校验 on {id} 路径参数.
 *
 * <p>覆盖 3 端点 (GET / POST messages / DELETE):非法 UUID → 400 invalid_path_param.
 *
 * <p>UUID 格式定义: {@code [0-9a-fA-F-]+, 36 字符},即标准 8-4-4-4-12 形式.
 */
@WebMvcTest(controllers = io.oryxos.web.controller.SessionsController.class)
@org.springframework.test.context.ContextConfiguration(classes = io.oryxos.web.controller.StubBootApp.class)
class SessionUuidValidationTest {

    @Autowired
    private MockMvc mockMvc;

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
        // UUID 校验测试不依赖 ProfileRegistry 内容,只为避免 autowiring 失败.
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

    // ===== GET =====

    @Test
    @DisplayName("GET: 非法 UUID 字符串 (字母) → 400 invalid_path_param")
    void getNonUuid() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/not-a-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_path_param"))
            .andExpect(jsonPath("$.field").value("id"));

        verify(sessionRepository, never()).findByIdAndDeletedAtIsNull(any());
    }

    @Test
    @DisplayName("GET: UUID 长度不对 (35 字符) → 400 invalid_path_param")
    void getWrongLength() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/0190a3b4-7c8d-7890-abcd-ef123456789"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_path_param"));

        verify(sessionRepository, never()).findByIdAndDeletedAtIsNull(any());
    }

    @Test
    @DisplayName("GET: 含非 UUID 字符的非法 id (短于 36 字符) → 400 invalid_path_param")
    void getSpecialChars() throws Exception {
        // 使用 URL 安全的非 UUID 字符串 (不能含 / 否则走 path normalization → 404)
        mockMvc.perform(get("/api/v1/sessions/not%2Da-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_path_param"));

        verify(sessionRepository, never()).findByIdAndDeletedAtIsNull(any());
    }

    // ===== POST messages =====

    @Test
    @DisplayName("POST messages: 非法 UUID → 400 invalid_path_param")
    void postMessagesBadId() throws Exception {
        String body = "{\"role\":\"user\",\"content\":\"hi\"}";

        mockMvc.perform(post("/api/v1/sessions/not-uuid/messages")
                .contentType("application/json")
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_path_param"));

        verify(sessionRepository, never()).save(any());
    }

    // ===== DELETE =====

    @Test
    @DisplayName("DELETE: 非法 UUID → 400 invalid_path_param")
    void deleteBadId() throws Exception {
        mockMvc.perform(delete("/api/v1/sessions/garbage"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_path_param"));

        verify(sessionRepository, never()).findByIdAndDeletedAtIsNull(any());
        verify(sessionRepository, never()).save(any());
    }
}
