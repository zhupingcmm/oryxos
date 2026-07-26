package io.oryxos.tool.sandbox;

/**
 * 出站动作类型 —— {@link Sandbox} 校验的四种动作分类。
 *
 * <p>每个 {@link ActionType} 对应一类工具行为；扩展阶段新增 SMTP / DB 等可在此追加 enum 常量。
 *
 * @see SandboxAction
 * @see Sandbox
 */
public enum ActionType {
    /** 读取本地文件（如 {@code read_file} Tool） */
    FILE_READ,
    /** 写入本地文件 */
    FILE_WRITE,
    /** 执行 Shell 命令（如 {@code shell} Tool） */
    SHELL_COMMAND,
    /** 出站 HTTP 请求（如 {@code notify} / {@code http_get} Tool） */
    HTTP_REQUEST
}