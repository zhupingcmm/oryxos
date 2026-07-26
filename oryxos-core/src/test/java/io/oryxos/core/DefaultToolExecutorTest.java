package io.oryxos.core;

import io.oryxos.core.testing.FakeToolExecutor;
import io.oryxos.core.testing.InMemorySession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-2 P2 阶段：{@link DefaultToolExecutor} 单元测试 —— C-TE-1（白名单拒绝）+ C-TE-2（审计写入）。
 *
 * <p>US-2 范围内验证 DefaultToolExecutor 两条主路径：
 * <ul>
 *   <li>toolName 不在 profile.tools() → 返回 {@code ToolResult.error}，不抛异常</li>
 *   <li>toolName 在白名单 → 抛 {@link UnsupportedOperationException}（US-4 stub 语义）</li>
 * </ul>
 *
 * <p>审计写入验证留到 T042（DefaultToolExecutor 改造后）。
 */
@DisplayName("DefaultToolExecutor 行为（P2 stub）")
class DefaultToolExecutorTest {

    private DefaultToolExecutor executor;
    private InMemorySession session;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        executor = new DefaultToolExecutor();
        sessionId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        session = new InMemorySession(sessionId, "weather-bot");
        // 设置 ProfileContext 用于 C-TE-3 session_iteration 捕获（改造后生效）
        ProfileContext.set(new ProfileContext.Snapshot(
            "weather-bot", sessionId, new AtomicInteger(0)
        ));
    }

    private Profile profileWithTools(List<String> tools) {
        return new Profile(
            "weather-bot",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            tools,
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            List.of()
        );
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        ProfileContext.clear();
    }

    /**
     * C-TE-1：toolName 不在 profile.tools() → 返回 error，不抛。
     */
    @Test
    @DisplayName("refusedToolReturnsError：toolName 不在白名单 → success=false + errorMessage")
    void refusedToolReturnsError() {
        Profile profile = profileWithTools(List.of("http_get"));
        ToolResult result = executor.invoke("unknown_tool", Map.of(), profile);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("tool not in profile: unknown_tool");
    }

    /**
     * 桩行为：toolName 在白名单 → 抛 UnsupportedOperationException（US-4 实现）。
     */
    @Test
    @DisplayName("allowedToolThrowsUnsupported：toolName 在白名单 → 抛 UnsupportedOperationException（stub）")
    void allowedToolThrowsUnsupported() {
        Profile profile = profileWithTools(List.of("http_get"));

        assertThatThrownBy(() -> executor.invoke("http_get", Map.of(), profile))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("http_get")
            .hasMessageContaining("US-2");
    }

    /**
     * 与 FakeToolExecutor 对照：Fake 是测试替身；Default 是 production 默认。
     * 此测试仅证明两个实现都不在 P2 调用真实 Tool。
     */
    @Test
    @DisplayName("FakeToolExecutor.refusedToolReturnsError：对照测试（Fake 与 Default 行为对齐）")
    void fakeRefusedToolReturnsError() {
        FakeToolExecutor fake = new FakeToolExecutor(FakeToolExecutor.emptyTable());
        Profile profile = profileWithTools(List.of("http_get"));

        ToolResult result = fake.invoke("unknown_tool", Map.of(), profile);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("tool not in profile");
    }
}