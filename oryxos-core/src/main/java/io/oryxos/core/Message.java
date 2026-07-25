package io.oryxos.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ReAct 循环的统一消息格式（不可变 record）。
 *
 * <p>三种角色（{@link Role}）合并到一个 record 内，compact constructor 按角色强制最小不变量：
 * <ul>
 *   <li>{@code USER}：仅 {@code content}，其余全 null</li>
 *   <li>{@code ASSISTANT}：分两种形态 —— 纯文本（{@code toolCalls} 为空）、含 tool_call（{@code toolCalls} 非空）</li>
 *   <li>{@code TOOL}：仅 {@code toolResult} + {@code toolName} + {@code toolCallId}，{@code content} 为 null</li>
 * </ul>
 *
 * <p>详见 [data-model.md §3.1](../../../../../specs/002-react-loop/data-model.md) 和
 * 验收规则（来自同文档 Validation rules 段）。
 */
public record Message(
    Role role,
    String content,
    List<ToolCall> toolCalls,
    String toolCallId,
    String toolName,
    ToolResult toolResult,
    Instant createdAt
) {
    public enum Role { USER, ASSISTANT, TOOL }

    /** Compact constructor —— 按角色做不变量校验。 */
    public Message {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
        switch (role) {
            case USER -> {
                Objects.requireNonNull(content, "USER message content");
                require(toolCalls == null,  "USER message must have toolCalls == null");
                require(toolCallId == null, "USER message must have toolCallId == null");
                require(toolName == null,   "USER message must have toolName == null");
                require(toolResult == null, "USER message must have toolResult == null");
            }
            case ASSISTANT -> {
                require(toolCallId == null, "ASSISTANT message must have toolCallId == null");
                require(toolName == null,   "ASSISTANT message must have toolName == null");
                require(toolResult == null, "ASSISTANT message must have toolResult == null");
                // toolCalls == null 也允许（按 data-model.md "toolCalls == empty list" 视为 "no tool_call"）
                List<ToolCall> calls = toolCalls == null ? List.of() : toolCalls;
                if (calls.isEmpty()) {
                    Objects.requireNonNull(content, "ASSISTANT text message content");
                } else {
                    // 含 tool_call：content 可空；calls 必须非空（已校验）
                    for (ToolCall tc : calls) {
                        Objects.requireNonNull(tc, "ASSISTANT message toolCalls must not contain null");
                    }
                }
                // 用规范化后的 list 替换（不可变）
                toolCalls = List.copyOf(calls);
            }
            case TOOL -> {
                require(content == null,    "TOOL message must have content == null");
                Objects.requireNonNull(toolResult, "TOOL message toolResult");
                Objects.requireNonNull(toolName, "TOOL message toolName");
                Objects.requireNonNull(toolCallId, "TOOL message toolCallId");
                require(toolCalls == null, "TOOL message must have toolCalls == null");
            }
        }
    }

    // ---- 工厂方法 ----

    /** 用户消息。 */
    public static Message user(String text) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        return new Message(Role.USER, text,
            null, null, null, null, Instant.now());
    }

    /** 助手纯文本响应。 */
    public static Message assistantText(String text) {
        if (text == null) throw new IllegalArgumentException("text must not be null");
        return new Message(Role.ASSISTANT, text,
            List.of(), null, null, null, Instant.now());
    }

    /** 助手带 tool_call 列表的响应（content 可空，传 null）。 */
    public static Message assistantToolCalls(List<ToolCall> calls) {
        if (calls == null) throw new IllegalArgumentException("calls must not be null");
        if (calls.isEmpty()) throw new IllegalArgumentException("assistantToolCalls requires at least 1 tool call");
        return new Message(Role.ASSISTANT, null,
            new ArrayList<>(calls), null, null, null, Instant.now());
    }

    /** Tool 执行结果消息（与 assistant message 中某条 {@link ToolCall#id()} 对应）。 */
    public static Message toolResult(String id, String name, ToolResult result) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        if (name == null) throw new IllegalArgumentException("name must not be null");
        if (result == null) throw new IllegalArgumentException("result must not be null");
        return new Message(Role.TOOL, null, null,
            id, name, result,
            Instant.now());
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new IllegalArgumentException(msg);
    }
}
