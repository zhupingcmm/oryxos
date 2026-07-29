package io.oryxos.web.controller;

import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.MemoryService;
import io.oryxos.memory.MemorySummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T028 + US-3 验收场景 2 + contracts/web-api.md §端点 7 — GET /api/v1/memory.
 *
 * <p>验证 {@link MemoryService#summary()} → {@code MemoryDto} 字段.
 */
@WebMvcTest(controllers = MemoryController.class)
@org.springframework.test.context.ContextConfiguration(classes = io.oryxos.web.controller.StubBootApp.class)
class MemoryControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MemoryService memoryService;

    // ComponentScan pulls other controllers — mock their critical deps.
    @MockBean
    private io.oryxos.core.AgentService agentService;
    @MockBean
    private io.oryxos.core.scheduler.SessionFactory sessionFactory;
    @MockBean
    private io.oryxos.core.scheduler.TaskExecutionRecorder taskExecutionRecorder;
    @MockBean
    private io.oryxos.storage.repository.SessionRepository sessionRepository;
    @MockBean
    private io.oryxos.core.ProfileRegistry profileRegistry;
    @MockBean
    private io.oryxos.core.tool.ToolRegistry toolRegistry;
    @MockBean
    private org.springframework.boot.actuate.health.HealthEndpoint healthEndpoint;

    @Test
    @DisplayName("US-3 场景 2: GET /api/v1/memory → 200 + MemoryDto 字段 (markdown backend)")
    void getMemoryMarkdown() throws Exception {
        when(memoryService.summary()).thenReturn(
            new MemorySummary("markdown", 42, 8, "/tmp/.oryxos/memory/MEMORY.md"));

        mockMvc.perform(get("/api/v1/memory"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backend").value("markdown"))
            .andExpect(jsonPath("$.core_entries").value(42))
            .andExpect(jsonPath("$.archive_entries").value(8))
            .andExpect(jsonPath("$.file_path").value("/tmp/.oryxos/memory/MEMORY.md"));
    }

    @Test
    @DisplayName("US-3 场景 2: GET /api/v1/memory → sqlite backend, filePath=null")
    void getMemorySqlite() throws Exception {
        when(memoryService.summary()).thenReturn(
            new MemorySummary("sqlite", 100, 50, null));

        mockMvc.perform(get("/api/v1/memory"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backend").value("sqlite"))
            .andExpect(jsonPath("$.core_entries").value(100))
            .andExpect(jsonPath("$.archive_entries").value(50))
            // filePath 字段被 @JsonInclude(NON_NULL) 过滤掉
            .andExpect(jsonPath("$.file_path").doesNotExist());
    }
}
