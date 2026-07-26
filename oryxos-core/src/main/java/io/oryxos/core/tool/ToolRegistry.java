package io.oryxos.core.tool;

import io.oryxos.core.OryxTool;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry of {@link ToolRegistration}s visible to the current OryxOS
 * workspace.
 *
 * <p>US-4 起，每个注册项同时持有：
 * <ul>
 *   <li>{@link ToolDefinition} —— 给 {@code oryxos tool list} 展示</li>
 *   <li>{@link OryxTool} 实现 —— 给 {@code DefaultToolExecutor} 派发</li>
 * </ul>
 *
 * <p>Spring auto-config 在 US-4 通过 {@link #of(Map)} 构建完整注册表；空构造器仅给单测
 * 与 default Spring 装配用。
 *
 * <p>详见 [research.md R-02](../../../../../../../specs/004-notify-channel/research.md)。
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolRegistration> byName;

    public ToolRegistry() {
        this.byName = Collections.emptyMap();
    }

    private ToolRegistry(Map<String, ToolRegistration> byName) {
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
    }

    /**
     * Build a populated registry from a name → {@link ToolRegistration} map.
     *
     * <p>Map 的 key 通常应等于 {@code registration.definition().name()}；但不强制相等，
     * 冲突时以 {@code definition().name()} 为权威。
     */
    public static ToolRegistry of(Map<String, ToolRegistration> registrations) {
        Map<String, ToolRegistration> normalized = new LinkedHashMap<>();
        if (registrations != null) {
            for (Map.Entry<String, ToolRegistration> e : registrations.entrySet()) {
                ToolRegistration reg = e.getValue();
                if (reg == null) {
                    continue;
                }
                normalized.put(reg.definition().name(), reg);
            }
        }
        return new ToolRegistry(normalized);
    }

    /** 所有已注册 Tool 的元数据列表（插入顺序）。CLI 展示用。 */
    public Collection<ToolDefinition> all() {
        return byName.values().stream()
            .map(ToolRegistration::definition)
            .toList();
    }

    /** 兼容旧 API：按 Tool 名查元数据；未注册返 {@code null}。 */
    public ToolDefinition get(String name) {
        ToolRegistration reg = byName.get(name);
        return reg == null ? null : reg.definition();
    }

    /**
     * 按 Tool 名查实现 —— 派发路径用；未注册返 {@link Optional#empty()}。
     *
     * <p>新 API（US-4 起）：{@code DefaultToolExecutor.invoke()} 通过本方法拿到可执行的
     * {@link OryxTool} 实例。
     */
    public Optional<OryxTool> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        ToolRegistration reg = byName.get(name);
        return reg == null ? Optional.empty() : Optional.of(reg.tool());
    }

    /** 注册数量（诊断 / 测试用）。 */
    public int size() {
        return byName.size();
    }

    /** 已注册的 Tool 名列表（按注册顺序）。 */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }
}