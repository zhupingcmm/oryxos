package io.oryxos.core.tool;

import io.oryxos.core.NotifyChannelConfig;
import io.oryxos.core.OryxTool;
import io.oryxos.core.Profile;
import io.oryxos.core.Provider;
import io.oryxos.core.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T073 —— {@link ToolRegistrySchemaAdapter} 的 Profile 可见性过滤 + notify 隐藏规则
 * （spec US-5 场景 2 / FR-011）。
 *
 * <p>关键场景：
 * <ol>
 *   <li>Profile 配 {@code notify_channels=[...]} → notify 出现在 schema 列表</li>
 *   <li>Profile 未配 notify_channels → notify 被过滤掉</li>
 *   <li>Profile.tools() 外的 Tool → 不出现</li>
 *   <li>Profile.tools() 内的 Tool → 出现，schema 字段正确</li>
 * </ol>
 */
class ToolRegistrySchemaAdapterTest {

    @Test
    @DisplayName("Profile 未配 notify_channels → notify 不出现在 schema 列表")
    void notify_hidden_when_no_channels() {
        ToolRegistry registry = registryWith(
            entry("file_read", "read file", "builtin"),
            entry("shell", "shell cmd", "builtin"),
            entry("notify", "send notify", "builtin")
        );
        ToolRegistrySchemaAdapter adapter = new ToolRegistrySchemaAdapter(registry);

        Profile profile = profileWithTools(List.of("file_read", "shell", "notify"), List.of());

        List<String> names = adapter.schemasFor(profile).stream()
            .map(s -> (String) ((Map) s.get("function")).get("name"))
            .toList();

        assertThat(names).containsExactly("file_read", "shell");
        assertThat(names).doesNotContain("notify");
    }

    @Test
    @DisplayName("Profile 配 notify_channels → notify 出现在 schema 列表")
    void notify_visible_when_channels_configured() {
        ToolRegistry registry = registryWith(
            entry("file_read", "read file", "builtin"),
            entry("notify", "send notify", "builtin")
        );
        ToolRegistrySchemaAdapter adapter = new ToolRegistrySchemaAdapter(registry);

        Profile profile = profileWithTools(
            List.of("file_read", "notify"),
            List.of(notifyChannel("feishu", "https://example.com/hook"))
        );

        List<String> names = adapter.schemasFor(profile).stream()
            .map(s -> (String) ((Map) s.get("function")).get("name"))
            .toList();

        assertThat(names).containsExactlyInAnyOrder("file_read", "notify");
    }

    @Test
    @DisplayName("Profile.tools() 外的 Tool → 不出现（即使已注册）")
    void tools_outside_profile_filtered_out() {
        ToolRegistry registry = registryWith(
            entry("file_read", "read file", "builtin"),
            entry("shell", "shell cmd", "builtin")
        );
        ToolRegistrySchemaAdapter adapter = new ToolRegistrySchemaAdapter(registry);

        Profile profile = profileWithTools(List.of("file_read"), List.of());

        List<String> names = adapter.schemasFor(profile).stream()
            .map(s -> (String) ((Map) s.get("function")).get("name"))
            .toList();

        assertThat(names).containsExactly("file_read");
    }

    @Test
    @DisplayName("schema 形态：{type:'function', function:{name, description, parameters}}")
    void schema_format_matches_openai_function_calling() {
        ToolRegistry registry = registryWith(
            entry("shell", "执行 shell 命令", "builtin")
        );
        ToolRegistrySchemaAdapter adapter = new ToolRegistrySchemaAdapter(registry);

        Profile profile = profileWithTools(List.of("shell"), List.of());
        Map<String, Object> schema = adapter.schemasFor(profile).get(0);

        assertThat(schema.get("type")).isEqualTo("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> fn = (Map<String, Object>) schema.get("function");
        assertThat(fn.get("name")).isEqualTo("shell");
        assertThat(fn.get("description")).isEqualTo("执行 shell 命令");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) fn.get("parameters");
        assertThat(params.get("type")).isEqualTo("object");
    }

    @Test
    @DisplayName("白名单里有但注册表没注册 → 静默跳过（让 DefaultToolExecutor 抛未注册错误）")
    void unregistered_tool_silently_skipped() {
        ToolRegistry registry = registryWith(
            entry("file_read", "read file", "builtin")
        );
        ToolRegistrySchemaAdapter adapter = new ToolRegistrySchemaAdapter(registry);

        Profile profile = profileWithTools(List.of("file_read", "unknown_tool"), List.of());

        List<String> names = adapter.schemasFor(profile).stream()
            .map(s -> (String) ((Map) s.get("function")).get("name"))
            .toList();

        assertThat(names).containsExactly("file_read");
    }

    // ---- helpers ----

    private static ToolDefinition entry(String name, String desc, String origin) {
        return new ToolDefinition(name, desc, origin);
    }

    private static ToolRegistry registryWith(ToolDefinition... defs) {
        Map<String, ToolRegistration> map = new java.util.LinkedHashMap<>();
        for (ToolDefinition def : defs) {
            map.put(def.name(), new ToolRegistration(def, stubTool(def.name()), def.name() + "Bean"));
        }
        return ToolRegistry.of(map);
    }

    private static OryxTool stubTool(String name) {
        return new OryxTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "stub"; }
            @Override public ToolResult execute(Map<String, Object> args) {
                return ToolResult.ok(Map.of());
            }
        };
    }

    private static Profile profileWithTools(List<String> tools, List<NotifyChannelConfig> channels) {
        return new Profile(
            "test",
            new Provider("deepseek", "deepseek-chat", null, "DEEPSEEK_API_KEY", Map.of()),
            tools,
            List.of(), List.of(), List.of(),
            new Profile.Settings(10, 20),
            Map.of(),
            channels
        );
    }

    private static NotifyChannelConfig notifyChannel(String name, String url) {
        return new NotifyChannelConfig(name, "webhook", url, null);
    }
}