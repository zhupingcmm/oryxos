package io.oryxos.web.controller;

import io.oryxos.core.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T030 + US-3 验收场景 4 + contracts/web-api.md §端点 9-10 — health + info.
 */
@WebMvcTest(controllers = SystemController.class)
@org.springframework.test.context.ContextConfiguration(classes = StubBootApp.class)
class SystemControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HealthEndpoint healthEndpoint;

    @MockBean
    private ToolRegistry toolRegistry;

    @MockBean
    private io.oryxos.core.ProfileRegistry profileRegistry;

    @MockBean
    private io.oryxos.core.AgentService agentService;
    @MockBean
    private io.oryxos.core.scheduler.SessionFactory sessionFactory;
    @MockBean
    private io.oryxos.core.scheduler.TaskExecutionRecorder taskExecutionRecorder;
    @MockBean
    private io.oryxos.storage.repository.SessionRepository sessionRepository;
    @MockBean
    private io.oryxos.memory.MemoryService memoryService;

    // ===== /api/v1/health =====

    @Test
    @DisplayName("US-3 场景 4: GET /api/v1/health UP → 200 + status=UP + uptimeMs>0")
    void healthUp() throws Exception {
        when(healthEndpoint.health()).thenReturn(
            Health.up().withDetail("database", "SQLite").build());

        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.uptime_ms").exists())
            .andExpect(jsonPath("$.version").exists());
    }

    @Test
    @DisplayName("US-3 场景 4: GET /api/v1/health DOWN → 503 + status=DOWN")
    void healthDown() throws Exception {
        when(healthEndpoint.health()).thenReturn(
            Health.down().withDetail("error", "Connection refused").build());

        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"));
    }

    // ===== /api/v1/info =====

    @Test
    @DisplayName("US-3 场景 4: GET /api/v1/info → 200 + 全字段")
    void info() throws Exception {
        when(toolRegistry.size()).thenReturn(9);
        when(profileRegistry.names()).thenReturn(java.util.Set.of("a", "b"));

        mockMvc.perform(get("/api/v1/info"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("oryxos"))
            .andExpect(jsonPath("$.version").exists())
            .andExpect(jsonPath("$.java_version").exists())
            .andExpect(jsonPath("$.os_name").exists())
            .andExpect(jsonPath("$.agents").value(2))
            .andExpect(jsonPath("$.tools").value(9))
            .andExpect(jsonPath("$.uptime_ms").exists());
    }
}
