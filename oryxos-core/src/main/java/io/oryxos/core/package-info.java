/**
 * OryxOS core abstractions and engine.
 *
 * <p>Contains the interfaces and core implementations that all other modules build on:
 * <ul>
 *   <li>{@code OryxTool} — uniform tool abstraction (built-in, @Tool beans, MCP tools)</li>
 *   <li>{@code Session}, {@code Profile}, {@code Message} — runtime data structures</li>
 *   <li>{@code ContextLoader}, {@code AgentLoader} — Bootstrap and AGENT.md loading</li>
 *   <li>{@code ReActLoop}, {@code PromptBuilder}, {@code ToolExecutor} — engine</li>
 *   <li>{@code AgentService} — unified entry point for CLI / Web / Scheduler triggers</li>
 *   <li>{@code AgentScheduler} — clock-push (cron) trigger source</li>
 * </ul>
 *
 * <p>This module has no Spring or LLM dependencies — pure interfaces and core types only.
 */
package io.oryxos.core;