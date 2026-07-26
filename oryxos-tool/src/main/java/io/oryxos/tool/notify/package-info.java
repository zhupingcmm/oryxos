/**
 * Notify 出站推送 —— OryxOS 结果"主动送出去"的统一出口。
 *
 * <p>详见 <a href="../../../../../../../specs/004-notify-channel/spec.md">specs/004-notify-channel/spec.md</a> 与
 * <a href="../../../../../../../CLAUDE.md">CLAUDE.md §9.5</a>。
 *
 * <p>本子包内模块边界（FR-014）：
 * <ul>
 *   <li>{@link io.oryxos.tool.notify.NotifyTool} —— 入口 OryxTool 实现，路由逻辑</li>
 *   <li>{@link io.oryxos.tool.notify.WebhookNotifyAdapter} —— 出站 HTTP POST</li>
 *   <li>{@link io.oryxos.tool.notify.NotifyResult} —— 单次发送结果 record</li>
 *   <li>{@link io.oryxos.tool.notify.UrlRedactor} —— 审计前 URL token 脱敏</li>
 * </ul>
 *
 * <p>绝对不向 oryxos-core / oryxos-storage / 其他模块泄露"Notify"概念；
 * 仅 oryxos-boot 通过 Spring DI 装配 {@code @Bean ToolRegistration} 引用 {@code NotifyTool}。
 */
package io.oryxos.tool.notify;