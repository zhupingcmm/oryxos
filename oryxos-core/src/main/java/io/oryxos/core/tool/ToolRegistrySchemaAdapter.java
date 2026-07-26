package io.oryxos.core.tool;

import io.oryxos.core.OryxTool;
import io.oryxos.core.Profile;
import io.oryxos.core.ToolSchemaProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ToolSchemaProvider} 的真实实现 —— 从 {@link ToolRegistry} 抽取
 * 当前 Profile 可见的 Tool 列表，翻译为 OpenAI Function Calling JSON Schema。
 *
 * <h2>可见性规则（spec US-5 场景 2 / FR-011）</h2>
 * <ol>
 *   <li>基础过滤：仅返回 {@code profile.tools()} 名单内的 Tool</li>
 *   <li>Notify 工具特殊规则：当 {@code profile.notifyChannels()} 为空时，{@code "notify"}
 *       Tool MUST 不出现在 schema 列表里（避免 LLM 调用一个永远报错的 Tool）</li>
 * </ol>
 *
 * <p>本类只依赖 {@link ToolDefinition} / {@link OryxTool} 接口，不耦合任何具体 Tool 实现
 * —— 满足 [CLAUDE.md §V 边界澄清](../../../CLAUDE.md)（Tool 抽象归 core）。
 *
 * <p>schema 形态（OpenAI 兼容）：
 * <pre>{@code
 * { "type": "function",
 *   "function": { "name": "shell", "description": "...", "parameters": {...} } }
 * }</pre>
 * parameters 暂以 {@code {"type":"object","properties":{}}} 占位 —— 完整 schema
 * 由各 Tool 自描述（本阶段先用最小形态）。
 */
public class ToolRegistrySchemaAdapter implements ToolSchemaProvider {

    /** Function Calling schema 中参数占位（最小 object 形态，避免 LLM 解析失败）。 */
    public static final Map<String, Object> EMPTY_PARAMETERS = Map.of(
        "type", "object",
        "properties", Map.of()
    );

    private final ToolRegistry registry;

    public ToolRegistrySchemaAdapter(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public List<Map<String, Object>> schemasFor(Profile profile) {
        Objects.requireNonNull(profile, "profile");
        List<String> allowed = profile.tools() == null ? List.of() : profile.tools();
        boolean notifyConfigured = profile.notifyChannels() != null
            && !profile.notifyChannels().isEmpty();
        List<Map<String, Object>> out = new ArrayList<>(allowed.size());
        for (String name : allowed) {
            // Notify 隐藏规则（spec FR-011 / US-5 场景 2）
            if ("notify".equals(name) && !notifyConfigured) {
                continue;
            }
            ToolDefinition def = registry.get(name);
            if (def == null) {
                // 白名单里有但未注册 —— 静默跳过，让 DefaultToolExecutor 抛"未注册"错误
                continue;
            }
            out.add(toSchema(def));
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> toSchema(ToolDefinition def) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", def.name());
        function.put("description", def.description() == null ? "" : def.description());
        function.put("parameters", EMPTY_PARAMETERS);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "function");
        wrapper.put("function", function);
        return wrapper;
    }
}