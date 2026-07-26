/**
 * Sandbox 子系统 —— 出站能力的应用层白名单校验。
 *
 * <p>Notify / HttpTools / ShellTools / FileTools 等所有"工具对外部世界"的操作 MUST 先过
 * {@link io.oryxos.tool.sandbox.Sandbox#enforce}；不允许绕过。
 *
 * <p>核心阶段只实现 {@link io.oryxos.tool.sandbox.WhitelistSandbox}（host 后缀匹配）；
 * 扩展阶段可接容器 / microVM 隔离，接口不变。
 *
 * <p>约束（来自 <a href="../../../../../../../specs/004-notify-channel/data-model.md">specs/004-notify-channel/data-model.md §4</a>）：
 * <ul>
 *   <li>不用 {@code SecurityManager}（JDK 21 不可用，CLAUDE.md §18）</li>
 *   <li>校验失败抛 {@link io.oryxos.tool.sandbox.SandboxViolationException}；走现有审计路径</li>
 *   <li>校验发生在 HTTP / 文件 / Shell 调用 <strong>之前</strong>，避免无效请求泄露</li>
 * </ul>
 */
package io.oryxos.tool.sandbox;