package io.oryxos.tool;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central registry of {@link ToolDefinition}s visible to the current OryxOS
 * workspace. Boot Spring, ask for this bean, and you can enumerate every
 * Tool the runtime knows about — without needing the Tool implementations
 * themselves.
 *
 * <p>The CLI's {@code oryxos tool list} command reads this registry. US-4
 * (Plugin Tool) fills it with the 9 built-in tools, MCP-served tools, and
 * SKILL.md-discovered tools (CLAUDE.md §6).
 *
 * <p>The default constructor yields an empty registry (no Tool beans in
 * the container). The Spring auto-configuration in US-4 replaces it with a
 * fully-populated instance via {@link #of(Map)}.
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolDefinition> byName;

    public ToolRegistry() {
        this.byName = Collections.emptyMap();
    }

    private ToolRegistry(Map<String, ToolDefinition> byName) {
        this.byName = Collections.unmodifiableMap(new LinkedHashMap<>(byName));
    }

    /** Build a populated registry from a name → {@link ToolDefinition} map. */
    public static ToolRegistry of(Map<String, ToolDefinition> definitions) {
        return new ToolRegistry(definitions);
    }

    /** All registered Tool definitions, in insertion order. */
    public Collection<ToolDefinition> all() {
        return byName.values();
    }

    /** Look up a single Tool by name; {@code null} if not registered. */
    public ToolDefinition get(String name) {
        return byName.get(name);
    }
}