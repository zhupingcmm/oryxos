package io.oryxos.core.tool;

/**
 * Lightweight metadata describing a single Tool that has been registered
 * with OryxOS. The CLI's {@code oryxos tool list} command reads these
 * records (never the full Tool implementation) to render a stable
 * NAME | DESCRIPTION table.
 *
 * <p>Real Tool implementations are wired up in US-4 (see CLAUDE.md §6).
 * This record is the contract that the CLI depends on; US-4 builds the
 * registry of {@code ToolDefinition}s from the 9 built-in tools, MCP
 * servers, and SKILL.md-discovered tools.
 *
 * @param name short kebab-case identifier (e.g. {@code read_file}, {@code shell})
 * @param description one-line human-readable description (safe to print)
 * @param origin provenance tag — one of {@code "builtin"}, {@code "mcp"},
 *             {@code "skill"}, {@code "external"}. The CLI surfaces this
 *             so users can see at a glance whether a Tool ships with
 *             OryxOS or comes from an external source.
 */
public record ToolDefinition(
        String name,
        String description,
        String origin) {
}