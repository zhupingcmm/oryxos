package io.oryxos.tool.sandbox;

/**
 * Sandbox 校验未通过 —— 工具调用在执行前被拦截。
 *
 * <p>走 {@link io.oryxos.core.DefaultToolExecutor} 既有审计路径：
 * 调用方应让异常"自然"抛出，由执行器捕获并写一行 {@code success=false} 审计行。
 *
 * <p>详见 <a href="../../../../../../../specs/004-notify-channel/data-model.md">specs/004-notify-channel/data-model.md §5</a>。
 */
public class SandboxViolationException extends RuntimeException {

    private final SandboxAction action;

    public SandboxViolationException(SandboxAction action, String message) {
        super(message);
        this.action = action;
    }

    public SandboxAction action() {
        return action;
    }
}