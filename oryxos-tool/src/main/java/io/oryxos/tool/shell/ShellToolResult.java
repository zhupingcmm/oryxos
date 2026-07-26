package io.oryxos.tool.shell;

/**
 * Shell Tool 返回值（不可变 record）。
 *
 * @param command    执行的命令字符串（便于审计 / 调试）
 * @param exitCode   进程退出码（0 = 成功；非 0 = 失败，但 payload 仍带 stdout/stderr 给 LLM 看）
 * @param stdout     标准输出（截断到 {@code max-output-bytes}；超长尾部加 {@code "...[truncated]"})
 * @param stderr     标准错误（截断到 {@code max-output-bytes}）
 * @param durationMs 进程执行 wall-time（毫秒）
 */
public record ShellToolResult(
    String command,
    int exitCode,
    String stdout,
    String stderr,
    long durationMs
) { }

