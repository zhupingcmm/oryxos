package io.oryxos.memory;

/**
 * Memory 层统一异常 —— 长期层 IO / 网络 / 反序列化失败统一封装为 {@code RuntimeException}
 * 子类，供 ReAct 主循环与 Tool 层捕获。
 *
 * <p>契约来源：[data-model.md §2.3](../specs/006-memory-layer/data-model.md) +
 * [spec.md FR-013](../specs/006-memory-layer/spec.md)。
 *
 * <p>Tool 层（005-tool-system 既有 {@code DefaultToolExecutor}） MUST 把本异常
 * 转 {@code ToolResult.error("memory save failed: <错误原因>")}，
 * 不抛异常到 ReAct 主循环（NFR-004：不携带 stack trace 到 errorMessage）。
 *
 * <p>注意：与既有 {@code MarkdownMemoryStore.MemoryStoreException}（static nested class）
 * 是两个独立的类型 —— 本类是 spec 定义的对外契约；既有类保留以避免破坏既有调用点。
 */
public class MemoryException extends RuntimeException {

    public MemoryException(String message) {
        super(message);
    }

    public MemoryException(String message, Throwable cause) {
        super(message, cause);
    }
}