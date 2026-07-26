/**
 * Tool 注册派发基础设施 —— Registry / Definition / Registration 三件套。
 *
 * <p>位于 {@code core.tool} 子包，区别于 {@code io.oryxos.tool.notify}（Notify 实现）
 * 与 {@code io.oryxos.tool.sandbox}（沙箱基础设施）。
 *
 * <p>为什么放在 core：{@code DefaultToolExecutor}（core 内）需要依赖 {@code ToolRegistry}
 * 来 dispatch Tool 调用；如果 {@code ToolRegistry} 放在 {@code oryxos-tool} 模块，
 * 会形成 core ↔ tool 循环依赖。把"注册派发契约"下沉到 core 让 core 单向依赖成为可能，
 * 各 Tool 实现仍归 {@code oryxos-tool}。
 *
 * <p>详见 <a href="../../../../../../../specs/004-notify-channel/research.md">specs/004-notify-channel/research.md R-02</a>。
 */
package io.oryxos.core.tool;