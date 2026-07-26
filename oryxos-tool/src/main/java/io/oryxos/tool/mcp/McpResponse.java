package io.oryxos.tool.mcp;

import java.util.Map;

/**
 * JSON-RPC 响应封装 —— {@code id} 对应请求 id，{@code result} 与 {@code error} 二选一
 * （[contracts/mcp-adapter.md §4.3](../../../../../../../specs/005-tool-system/contracts/mcp-adapter.md)）。
 *
 * <p>{@link #isError()} 与 {@link #errorMessage()} 提供标准错误读取路径。
 *
 * @param id     JSON-RPC 请求 ID（与发送端对齐）
 * @param result 成功时的 result 字典（错误时为 null）
 * @param error  失败时的 error 字典（成功时为 null）
 */
public record McpResponse(int id, Map<String, Object> result, Map<String, Object> error) {

    /** JSON-RPC 标准错误码字段名。 */
    public static final String ERR_CODE = "code";
    public static final String ERR_MESSAGE = "message";

    public boolean isError() {
        return error != null;
    }

    public String errorMessage() {
        if (error == null) {
            return "";
        }
        Object msg = error.get(ERR_MESSAGE);
        return msg == null ? "unknown mcp error" : msg.toString();
    }
}
