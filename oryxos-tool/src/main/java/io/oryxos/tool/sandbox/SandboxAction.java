package io.oryxos.tool.sandbox;

import java.util.Objects;

/**
 * 单次 Sandbox 校验请求 —— 一次"工具对外部世界"的操作。
 *
 * <p>{@code target} 语义按 {@link ActionType} 区分：
 * <ul>
 *   <li>{@code FILE_READ} / {@code FILE_WRITE} —— 目标文件绝对路径</li>
 *   <li>{@code SHELL_COMMAND} —— 完整命令行字符串</li>
 *   <li>{@code HTTP_REQUEST} —— 完整 URL（含 query string）</li>
 * </ul>
 *
 * <p>字段不可空校验在 record compact constructor 中完成；构造时即抛
 * {@link NullPointerException}（{@code Objects.requireNonNull} 默认行为）。
 *
 * @param type  动作类型
 * @param target 动作目标；{@code ActionType} 决定语义
 */
public record SandboxAction(ActionType type, String target) {
    public SandboxAction {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(target, "target");
        if (target.isBlank()) {
            throw new IllegalArgumentException("target must not be blank");
        }
    }
}