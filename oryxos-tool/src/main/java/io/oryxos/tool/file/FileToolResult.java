package io.oryxos.tool.file;

import java.util.List;

/**
 * File Tool 返回值 —— {@code file_read} / {@code file_write} / {@code file_list} 统一使用。
 *
 * <p>{@code read} 模式：{@code content} 非空；{@code entries} 为 {@code null}。
 * {@code list} 模式：{@code entries} 非空；{@code content} 为 {@code null}。
 * {@code write} 模式：{@code sizeBytes} 为写入字节数；{@code content}/{@code entries} 为 {@code null}。
 *
 * @param path       解析后的访问路径（绝对路径或相对 {@code .oryxos/agents/<n>/}）
 * @param sizeBytes  字节数（read 模式为文件大小；write 模式为写入大小；list 模式为目录字节数或 null）
 * @param content    文本内容（read 模式）；其他模式为 {@code null}
 * @param entries    目录条目（list 模式）；其他模式为 {@code null}
 */
public record FileToolResult(
    String path,
    Long sizeBytes,
    String content,
    List<String> entries
) { }

