package io.oryxos.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * US-2 阶段循环层看到的 Profile 形态 —— 从 Profile YAML 翻译而来的不可变 record。
 *
 * <p>只取循环关心的字段：路由键 + 模型 + 温度 + maxTokens + 可用 Tool 名 + Bootstrap/Skill 引用 + Settings。
 *
 * <p>详见 [data-model.md §3.3](../../../../../specs/002-react-loop/data-model.md)。
 *
 * <h2>字段约束</h2>
 * <ul>
 *   <li>{@code name} 匹配 {@code ^[a-z][a-z0-9-]{0,63}$}（同 Provider name 规则）</li>
 *   <li>{@code provider.name} / {@code provider.model} 非空</li>
 *   <li>{@code settings.maxIterations >= 0}（{@code 0} 是合法值；对应 spec Edge case 4 / 5）</li>
 *   <li>{@code settings.maxHistoryTurns >= 1}</li>
 *   <li>所有 {@code List} 字段、{@code extra} map 不可为 null —— null 视为空集合</li>
 * </ul>
 */
public record Profile(
    String name,
    Provider provider,
    List<String> tools,
    List<String> mcpServers,
    List<String> bootstrap,
    List<String> skills,
    Settings settings,
    Map<String, Object> extra,
    List<NotifyChannelConfig> notifyChannels
) {

    private static final Pattern NAME_PATTERN =
        Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    public record Settings(
        int maxIterations,         // 默认 10；spec FR-014；0 是合法值（边界 5 路径）
        int maxHistoryTurns        // 默认 20
    ) {
        public Settings {
            if (maxIterations < 0) {
                throw new IllegalArgumentException("maxIterations must be >= 0, got " + maxIterations);
            }
            if (maxHistoryTurns < 1) {
                throw new IllegalArgumentException("maxHistoryTurns must be >= 1, got " + maxHistoryTurns);
            }
        }

        /** 工厂方法 —— 循环默认（max_iterations=10, max_history_turns=20）。 */
        public static Settings defaults() {
            return new Settings(10, 20);
        }
    }

    public Profile {
        // name 校验
        Objects.requireNonNull(name, "name");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "Profile name '" + name + "' must match ^[a-z][a-z0-9-]{0,63}$");
        }
        // provider 必须非空；其字段非空（C-PS-6 显式路由需要）
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(provider.name(), "provider.name");
        if (provider.name().isBlank()) {
            throw new IllegalArgumentException("provider.name must not be blank");
        }
        Objects.requireNonNull(provider.model(), "provider.model");
        if (provider.model().isBlank()) {
            throw new IllegalArgumentException("provider.model must not be blank");
        }
        // settings 必非空
        Objects.requireNonNull(settings, "settings");
        // 集合字段 null 视为空
        tools = tools == null ? List.of() : List.copyOf(tools);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
        skills = skills == null ? List.of() : List.copyOf(skills);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
        notifyChannels = notifyChannels == null ? List.of() : List.copyOf(notifyChannels);
    }
}
