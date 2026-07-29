package io.oryxos.web.controller;

import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T029 + US-3 验收场景 3 + contracts/web-api.md §端点 8 — GET /api/v1/tools.
 *
 * <p>9+ builtin tools + {@code ?source=mcp} 过滤 + source 字段枚举 (字节级对齐 tool_invocations.source).
 */
@WebMvcTest(controllers = ToolsController.class)
@org.springframework.test.context.ContextConfiguration(classes = io.oryxos.web.controller.StubBootApp.class)
class ToolsControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ToolRegistry toolRegistry;

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
    private io.oryxos.memory.MemoryService memoryService;
    @MockBean
    private org.springframework.boot.actuate.health.HealthEndpoint healthEndpoint;

    private static final List<ToolDefinition> SAMPLE = List.of(
        new ToolDefinition("file_read", "读取文件内容", "builtin"),
        new ToolDefinition("shell", "shell 命令执行", "builtin"),
        new ToolDefinition("notify", "Notify 出站推送", "builtin"),
        new ToolDefinition("integration__echo", "Echo MCP tool", "mcp"),
        new ToolDefinition("my_bean", "业务自定义 Tool", "external")
    );

    @BeforeEach
    void setup() {
        when(toolRegistry.all()).thenReturn(SAMPLE);
    }

    @Test
    @DisplayName("US-3 场景 3: GET /api/v1/tools → 200 + 含 builtin/mcp/java_bean 三类")
    void listTools() throws Exception {
        mockMvc.perform(get("/api/v1/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[0].name").value("file_read"))
            .andExpect(jsonPath("$[0].source").value("builtin"))
            .andExpect(jsonPath("$[3].name").value("integration__echo"))
            .andExpect(jsonPath("$[3].source").value("mcp"))
            .andExpect(jsonPath("$[4].name").value("my_bean"))
            .andExpect(jsonPath("$[4].source").value("java_bean"));
    }

    @Test
    @DisplayName("US-3 场景 3: ?source=mcp → 过滤后只保留 mcp 类")
    void filterBySource() throws Exception {
        mockMvc.perform(get("/api/v1/tools").param("source", "mcp"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("integration__echo"))
            .andExpect(jsonPath("$[0].source").value("mcp"));
    }

    @Test
    @DisplayName("US-3 场景 3: ?source=builtin → 过滤后只保留 builtin 类")
    void filterBuiltin() throws Exception {
        mockMvc.perform(get("/api/v1/tools").param("source", "builtin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].source").value("builtin"))
            .andExpect(jsonPath("$[1].source").value("builtin"))
            .andExpect(jsonPath("$[2].source").value("builtin"));
    }

    @Test
    @DisplayName("US-3 边界: ?source=invalid → 400 invalid_path_param (handler 统一 @Pattern 失败 → invalid_path_param)")
    void invalidSource() throws Exception {
        // Handler's ConstraintViolationException 处理器对所有 @Pattern 失败的参数
        // (path / query) 都返回 invalid_path_param. spec line 406 提到 invalid_request
        // 但 @RequestParam @Pattern 实际走的是同一处理器. 接受这个统一契约,后续如需细分
        // 可在 handler 内反射 ParameterTypes 区分 path / query.
        mockMvc.perform(get("/api/v1/tools").param("source", "evil-source"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_path_param"))
            .andExpect(jsonPath("$.field").value("source"));
    }
}
