package io.oryxos.tool.sandbox;

/**
 * Sandbox 接口 —— 应用层"出站动作白名单"校验的统一入口。
 *
 * <p>所有工具在真正执行"对外操作"前 MUST 调用 {@link #enforce(SandboxAction)}；
 * 校验失败抛 {@link SandboxViolationException}，由 {@link io.oryxos.core.DefaultToolExecutor}
 * 走既有审计路径捕获。
 *
 * <p>核心阶段唯一实现：{@link WhitelistSandbox}（host 后缀匹配 + IP 拒绝）。
 * 升级路径：白名单 → 容器（namespace + cgroups + seccomp）→ microVM（Firecracker / Kata / gVisor）；
 * 接口不变，扩展阶段只需替换实现。
 *
 * <p>详见 <a href="../../../../../../../CLAUDE.md">CLAUDE.md §9.4</a>。
 */
public interface Sandbox {

    /**
     * 校验一次出站动作是否被允许。
     *
     * @param action 待校验的动作
     * @throws SandboxViolationException 不被允许时抛出；调用方应让异常传播
     */
    void enforce(SandboxAction action);
}