/**
 * Capability 4 — Tool system (all-in-one module).
 *
 * <p>This module deliberately combines what would otherwise be three separate modules:
 * built-in tools, MCP client, and sandbox/notify infrastructure. They share the same
 * {@code OryxTool} abstraction and {@code ToolRegistry}, with high internal coupling,
 * so the core stage keeps them together.
 *
 * <p>Contents:
 * <ul>
 *   <li><strong>Built-in tools (9):</strong> FileTools (read_file/write_file/list_dir),
 *       ShellTools (shell), HttpTools (http_get/http_post), MemoryTools
 *       (save_memory/recall_memory), NotifyTools (notify)</li>
 *   <li><strong>MCP client:</strong> McpClientService + McpToolAdapter</li>
 *   <li><strong>ToolRegistry:</strong> unified registration of built-in, @Tool beans, MCP tools</li>
 *   <li><strong>Sandbox:</strong> interface + {@code WhitelistSandbox} (path/command/domain whitelist)</li>
 *   <li><strong>Notify:</strong> {@code NotifyChannelAdapter} + {@code WebhookNotifyAdapter}</li>
 * </ul>
 *
 * <p>Plugin tier 1 ({@code AGENT.md} + MCP, zero code) is the recommended extension path.
 */
package io.oryxos.tool;