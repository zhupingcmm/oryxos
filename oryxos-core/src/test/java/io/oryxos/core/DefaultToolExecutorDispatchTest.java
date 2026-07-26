package io.oryxos.core;

import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-4 起 {@link DefaultToolExecutor} 的派发路径单测 —— T021。
 *
 * <p>覆盖矩阵：
 * <ul>
 *   <li>工具在白名单 + 已注册 → 派发到 {@link OryxTool#execute}，结果透传</li>
 *   <li>工具在白名单 + 未注册 → 返回 {@code ToolResult.error("tool not registered: ...")}，不抛异常</li>
 *   <li>工具不在白名单 → 返回 {@code ToolResult.error("tool not in profile: ...")}（不调派发器）</li>
 *   <li>工具执行抛异常 → 返回 {@code ToolResult.error}，审计行 success=false</li>
 *   <li>审计写入字段（channel / notifyStatusCode）= null（非 notify 工具）</li>
 * </ul>
 */
@DisplayName("DefaultToolExecutor dispatch path (US-4)")
class DefaultToolExecutorDispatchTest {

    private DefaultToolExecutor executor;
    private RecordingAuditWriter audit;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        sessionId = UUID.fromString("00000000-0000-0000-0000-000000000123");
        audit = new RecordingAuditWriter();
        executor = new DefaultToolExecutor(audit, buildRegistry());
        ProfileContext.set(new ProfileContext.Snapshot(
            "weather-bot", sessionId, new AtomicInteger(0)));
    }

    @AfterEach
    void cleanup() {
        ProfileContext.clear();
    }

    private static ToolRegistry buildRegistry() {
        OryxTool notify = new OryxTool() {
            @Override public String name() { return "notify"; }
            @Override public String description() { return "Notify mock"; }
            @Override public ToolResult execute(Map<String, Object> arguments) {
                return ToolResult.ok(Map.of("status", "notified",
                    "echo", String.valueOf(arguments.get("content"))));
            }
        };
        OryxTool flaky = new OryxTool() {
            @Override public String name() { return "flaky"; }
            @Override public ToolResult execute(Map<String, Object> arguments) {
                throw new RuntimeException("boom!");
            }
        };
        ToolDefinition notifyDef = new ToolDefinition(notify.name(), notify.description(), "builtin");
        ToolDefinition flakyDef  = new ToolDefinition(flaky.name(),  "",                 "builtin");
        return ToolRegistry.of(Map.of(
            "notify", new ToolRegistration(notifyDef, notify, "test-notify"),
            "flaky",  new ToolRegistration(flakyDef,  flaky,  "test-flaky")
        ));
    }

    private Profile profileWithTools(List<String> tools) {
        return new Profile(
            "weather-bot",
            new Provider("deepseek", "deepseek-chat", null, "X", Map.of()),
            tools,
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            List.of()
        );
    }

    @Test
    @DisplayName("dispatch: 工具在白名单 + 已注册 → 派发到 OryxTool.execute，返回 ToolResult.ok")
    void dispatchedAndReturnedOk() {
        Profile profile = profileWithTools(List.of("notify"));
        ToolResult result = executor.invoke("notify",
            Map.of("content", "hello"), profile);

        assertThat(result.success()).isTrue();
        assertThat(result.payload()).containsEntry("echo", "hello");
        assertThat(audit.last().toolName()).isEqualTo("notify");
        assertThat(audit.last().success()).isTrue();
    }

    @Test
    @DisplayName("dispatch: 工具在白名单但 ToolRegistry 未注册 → ToolResult.error 不抛异常")
    void whitelistedButUnregisteredReturnsError() {
        Profile profile = profileWithTools(List.of("ghost_tool"));
        ToolResult result = executor.invoke("ghost_tool", Map.of(), profile);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("tool not registered: ghost_tool");
        assertThat(audit.last().toolName()).isEqualTo("ghost_tool");
        assertThat(audit.last().success()).isFalse();
    }

    @Test
    @DisplayName("dispatch: 工具不在白名单 → ToolResult.error，不调派发")
    void notWhitelistedReturnsErrorWithoutDispatch() {
        Profile profile = profileWithTools(List.of("notify"));
        ToolResult result = executor.invoke("http_get", Map.of(), profile);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("tool not in profile: http_get");
        // http_get 不会被 notify 工具误派发
        assertThat(audit.last().toolName()).isEqualTo("http_get");
        assertThat(audit.last().success()).isFalse();
    }

    @Test
    @DisplayName("dispatch: 工具抛 RuntimeException → ToolResult.error 包裹")
    void executionFailureIsCaptured() {
        Profile profile = profileWithTools(List.of("flaky"));
        ToolResult result = executor.invoke("flaky", Map.of(), profile);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("tool execution failed").contains("boom!");
        assertThat(audit.last().toolName()).isEqualTo("flaky");
        assertThat(audit.last().success()).isFalse();
    }

    @Test
    @DisplayName("audit: channel + notifyStatusCode = null（非 notify 派发路径）")
    void auditFieldsNullForNonNotify() {
        Profile profile = profileWithTools(List.of("notify"));
        executor.invoke("notify", Map.of("content", "x"), profile);

        ToolAuditWriter.ToolAuditData data = audit.last();
        assertThat(data.channel()).isNull();
        assertThat(data.notifyStatusCode()).isNull();
    }

    @Test
    @DisplayName("audit: notify 工具 payload 含 channel + status_code → 审计行写两列")
    void auditFieldsExtractedFromNotifyPayload() {
        // 替换 buildRegistry：notify 工具返回带 channel + status_code 的 payload
        OryxTool notify = new OryxTool() {
            @Override public String name() { return "notify"; }
            @Override public String description() { return "mock"; }
            @Override public ToolResult execute(Map<String, Object> arguments) {
                // 模拟 NotifyTool.toToolResult 行为
                return new ToolResult(true,
                    new java.util.LinkedHashMap<>(java.util.Map.of(
                        "channel", "default",
                        "status_code", 200,
                        "duration_ms", 234L,
                        "success", true)),
                    null);
            }
        };
        ToolDefinition def = new ToolDefinition("notify", "mock", "builtin");
        ToolRegistry reg = ToolRegistry.of(Map.of(
            "notify", new ToolRegistration(def, notify, "mock")));
        DefaultToolExecutor ex = new DefaultToolExecutor(audit, reg);

        Profile profile = profileWithTools(List.of("notify"));
        ex.invoke("notify", Map.of("content", "x"), profile);

        ToolAuditWriter.ToolAuditData data = audit.last();
        assertThat(data.channel()).isEqualTo("default");
        assertThat(data.notifyStatusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("audit: notify 工具 payload 缺 status_code（网络错误）→ notifyStatusCode=null")
    void auditStatusCodeNullOnNetworkError() {
        OryxTool notify = new OryxTool() {
            @Override public String name() { return "notify"; }
            @Override public ToolResult execute(Map<String, Object> arguments) {
                return new ToolResult(false,
                    new java.util.LinkedHashMap<>(java.util.Map.of(
                        "channel", "default",
                        "duration_ms", 5000L,
                        "success", false,
                        "error", "timeout after 5000ms",
                        "error_class", "timeout")),
                    "timeout after 5000ms");
            }
        };
        ToolDefinition def = new ToolDefinition("notify", "mock", "builtin");
        ToolRegistry reg = ToolRegistry.of(Map.of(
            "notify", new ToolRegistration(def, notify, "mock")));
        DefaultToolExecutor ex = new DefaultToolExecutor(audit, reg);

        Profile profile = profileWithTools(List.of("notify"));
        ex.invoke("notify", Map.of("content", "x"), profile);

        ToolAuditWriter.ToolAuditData data = audit.last();
        assertThat(data.channel()).isEqualTo("default");
        assertThat(data.notifyStatusCode()).isNull();  // 网络错误无 status code
        assertThat(data.success()).isFalse();
    }

    @Test
    @DisplayName("dispatch: ToolResult.error 自带 errorMessage → 审计行写入同样 errorMessage")
    void auditErrorMessageMatchesToolResult() {
        OryxTool err = new OryxTool() {
            @Override public String name() { return "err"; }
            @Override public ToolResult execute(Map<String, Object> arguments) {
                return ToolResult.error("explicit error");
            }
        };
        ToolDefinition def = new ToolDefinition("err", "", "builtin");
        ToolRegistry reg = ToolRegistry.of(Map.of("err", new ToolRegistration(def, err, "test")));
        DefaultToolExecutor ex = new DefaultToolExecutor(audit, reg);

        Profile profile = profileWithTools(List.of("err"));
        ToolResult result = ex.invoke("err", Map.of(), profile);

        assertThat(result.success()).isFalse();
        assertThat(audit.last().errorMessage()).isEqualTo("explicit error");
        assertThat(audit.last().success()).isFalse();
    }

    /** 最小审计记录器 —— 把每次 record 调用存到 last 引用。 */
    private static final class RecordingAuditWriter implements ToolAuditWriter {
        private final AtomicReference<ToolAuditData> lastRef = new AtomicReference<>();

        @Override public void record(ToolAuditData data) {
            lastRef.set(data);
        }

        ToolAuditData last() {
            return lastRef.get();
        }
    }
}