/**
 * Capability 3 — Three-layer memory.
 *
 * <p>{@code MemoryService} is a unified facade exposed to the ReAct loop, internally
 * delegating to:
 * <ul>
 *   <li>{@code SessionManager} — session memory (SQLite in extension)</li>
 *   <li>{@code LongTermMemoryStore} — long-term memory (interface, three backends)</li>
 * </ul>
 *
 * <p>Backends for long-term memory (selected via {@code memory.backend}):
 * <ul>
 *   <li>{@code MarkdownMemoryStore} — default, single {@code MEMORY.md} file, keyword search</li>
 *   <li>{@code SqliteMemoryStore} — {@code memory_entries} table, structured queries</li>
 *   <li>{@code Mem0MemoryStore} — self-hosted Mem0, semantic search</li>
 * </ul>
 *
 * <p>Core stage implements two of three memory layers (session + long-term).
 * Episodic memory is added in the extension stage.
 */
package io.oryxos.memory;