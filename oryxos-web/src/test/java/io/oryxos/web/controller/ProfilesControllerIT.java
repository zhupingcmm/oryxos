package io.oryxos.web.controller;

import io.oryxos.core.InMemoryProfileRegistry;
import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.Provider;
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
 * T027 + US-3 验收场景 1 + contracts/web-api.md §端点 6 — GET /api/v1/profiles.
 *
 * <p>用 {@code @MockBean ProfileRegistry} + Mockito 桩返回真实 registry 实例,
 * 避免在不同 IT 类之间共享上下文导致 5 个 ProfileRegistry bean 冲突.
 */
@WebMvcTest(controllers = ProfilesController.class)
@org.springframework.test.context.ContextConfiguration(classes = io.oryxos.web.controller.StubBootApp.class)
class ProfilesControllerIT {

    @Autowired
    private MockMvc mockMvc;

    // === Mocks — 让每个测试类有独立的 MergedContextConfiguration,避免共享 context 时
    //     多个 IT 类的 TestConfig 同时贡献 ProfileRegistry bean 导致 autowiring 失败. ===
    @MockBean
    private ProfileRegistry profileRegistry;
    @MockBean
    private io.oryxos.core.AgentService agentService;
    @MockBean
    private io.oryxos.core.scheduler.SessionFactory sessionFactory;
    @MockBean
    private io.oryxos.core.scheduler.TaskExecutionRecorder taskExecutionRecorder;
    @MockBean
    private io.oryxos.storage.repository.SessionRepository sessionRepository;
    @MockBean
    private io.oryxos.core.tool.ToolRegistry toolRegistry;
    @MockBean
    private io.oryxos.memory.MemoryService memoryService;
    @MockBean
    private org.springframework.boot.actuate.health.HealthEndpoint healthEndpoint;

    @BeforeEach
    void stubProfileRegistry() {
        Provider p = new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY",
            Map.of("temperature", 0.7));
        Map<String, Object> extras = Map.of(
            "description", "每日天气查询",
            "identity", Map.of("agent_name", "WeatherBot"),
            "schedules", List.of(Map.of("cron", "0 8 * * *", "zone", "Asia/Shanghai"))
            // notify_channels 不放 extra —— Profile.notifyChannels 是独立字段
        );
        NotifyChannelConfig webhook = new NotifyChannelConfig(
            "default", "webhook", "https://example.com/hook", null);
        Profile weatherAgent = new Profile(
            "weather-agent", p, List.of("http_get"),
            List.of(), List.of("AGENTS.md", "USER.md"), List.of(),
            Profile.Settings.defaults(),
            extras, List.of(webhook)
        );
        ProfileRegistry real = InMemoryProfileRegistry.of(weatherAgent);
        // Mockito mock 转发到真实 registry
        when(profileRegistry.names()).thenAnswer(inv -> real.names());
        when(profileRegistry.find("weather-agent")).thenAnswer(inv -> real.find("weather-agent"));
        when(profileRegistry.find(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> real.find((String) inv.getArgument(0)));
    }

    @Test
    @DisplayName("US-3 场景 1: GET /api/v1/profiles → 200 + ProfileDto[] 字段完整")
    void listProfiles() throws Exception {
        mockMvc.perform(get("/api/v1/profiles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("weather-agent"))
            .andExpect(jsonPath("$[0].description").value("每日天气查询"))
            .andExpect(jsonPath("$[0].agent_name").value("WeatherBot"))
            .andExpect(jsonPath("$[0].provider_name").value("deepseek"))
            .andExpect(jsonPath("$[0].model").value("deepseek-chat"))
            .andExpect(jsonPath("$[0].tool_count").value(1))
            .andExpect(jsonPath("$[0].schedule_count").value(1))
            .andExpect(jsonPath("$[0].notify_channel_count").value(1))
            .andExpect(jsonPath("$[0].bootstrap_files[0]").value("AGENTS.md"))
            .andExpect(jsonPath("$[0].bootstrap_files[1]").value("USER.md"));
    }
}
