/**
 * SQLite persistence layer.
 *
 * <p>Holds Spring Data JPA repositories and entity definitions for the five core
 * SQLite tables:
 * <ul>
 *   <li>{@code sessions} — Session metadata + JSON-serialized conversation history</li>
 *   <li>{@code tool_invocations} — every Tool call (audit-grade, written day-one)</li>
 *   <li>{@code llm_calls} — every LLM call (audit-grade + cost transparency)</li>
 *   <li>{@code scheduled_tasks} — scheduler task registration and run state</li>
 *   <li>{@code task_executions} — per-task execution history</li>
 * </ul>
 *
 * <p>Note: SQLite's {@code ALTER TABLE} capability is limited, and
 * {@code hibernate.ddl-auto=update} does not reliably evolve schemas.
 * Schema evolution must use manual migration scripts or Flyway/Liquibase.
 */
package io.oryxos.storage;