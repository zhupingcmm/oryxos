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
     *
     * <p>US-4 起：两个 {@link ToolRegistration} 声明同名 Tool 时抛
     * {@link IllegalStateException}（spec FR-015 / [research.md R-08](../../../../../../../specs/005-tool-system/research.md)）。
     * 错误信息含两个冲突类的全限定名，便于排错。Spring Boot 启动期调用此方法时
     * 失败 → 启动失败（fail-fast）。
     */
    public static ToolRegistry of(Map<String, ToolRegistration> registrations) {
        Map<String, ToolRegistration> normalized = new LinkedHashMap<>();
        if (registrations != null) {
            for (Map.Entry<String, ToolRegistration> e : registrations.entrySet()) {
                ToolRegistration reg = e.getValue();
                if (reg == null) {
                    continue;
                }
                String name = reg.definition().name();
                ToolRegistration existing = normalized.get(name);
                if (existing != null) {
                    throw new IllegalStateException(String.format(
                        "Tool name conflict: '%s' registered by both %s and %s",
                        name,
                        existing.tool().getClass().getName(),
                        reg.tool().getClass().getName()));
                }
                normalized.put(name, reg);
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

    /**
     * 按 Tool 名查注册项 —— US-3 / MCP 用：需要注册之后回过头来调 / 卸载。
     * 未注册返 {@code null}。
     */
    public ToolRegistration getRegistration(String name) {
        if (name == null) return null;
        return byName.get(name);
    }

    /**
     * 追加一组注册项 —— US-3 MCP 在握手后追加，远端 Tools 才能被本地调度。
     *
     * <p>注意：{@link ToolRegistry} 默认是不可变的（FR-015）；只有包内 / 框架代码才能
     * 通过这个包级入口追加（测试时直接 new 一个 mutable 的 registry）。
     * 重复名同样抛 {@link IllegalStateException}。
     */
    public void registerAll(java.util.Map<String, ToolRegistration> additions) {
        if (additions == null || additions.isEmpty()) return;
        Map<String, ToolRegistration> next = new LinkedHashMap<>(byName);
        for (java.util.Map.Entry<String, ToolRegistration> e : additions.entrySet()) {
            ToolRegistration reg = e.getValue();
            if (reg == null) continue;
            String name = reg.definition().name();
            ToolRegistration existing = next.get(name);
            if (existing != null) {
                throw new IllegalStateException(String.format(
                    "Tool name conflict: '%s' already registered by %s; cannot add %s",
                    name,
                    existing.tool().getClass().getName(),
                    reg.tool().getClass().getName()));
            }
            next.put(name, reg);
        }
        // 替换为新的不可变视图
        try {
            java.lang.reflect.Field f = ToolRegistry.class.getDeclaredField("byName");
            f.setAccessible(true);
            f.set(this, Collections.unmodifiableMap(next));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("cannot mutate ToolRegistry.byName", ex);
        }
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