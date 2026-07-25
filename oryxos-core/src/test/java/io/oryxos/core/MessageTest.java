package io.oryxos.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Message record compact constructor 不变量单元测试 —— [data-model.md §3.1](../../../../../specs/002-react-loop/data-model.md) 中
 * 按角色专属约束的条款。
 *
 * <p>所有断言在 T009 record 落地后立即绿；属 T027 范围。
 */
@DisplayName("Message record compact-constructor 不变量")
class MessageTest {

    @Test
    @DisplayName("USER：content 必须非空；toolCalls/toolCallId/toolName 必须为空")
    void userRequiresContent() {
        Message m = Message.user("hello");
        assertThat(m.role()).isEqualTo(Message.Role.USER);
        assertThat(m.content()).isEqualTo("hello");
        assertThat(m.toolCalls()).isNull();
        assertThat(m.toolCallId()).isNull();
        assertThat(m.toolName()).isNull();
        assertThat(m.toolResult()).isNull();
        assertThat(m.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("USER：content=null 抛 IllegalArgumentException")
    void userRejectsNullContent() {
        assertThatThrownBy(() -> Message.user(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ASSISTANT(text)：toolCalls 必须为空列表")
    void assistantTextRejectsToolCalls() {
        Message m = Message.assistantText("done");
        assertThat(m.role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(m.content()).isEqualTo("done");
        assertThat(m.toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("ASSISTANT(tool_calls)：toolCalls 至少 1 条；content 可空")
    void assistantToolCallsRequiresNonEmptyList() {
        Message m = Message.assistantToolCalls(List.of(
            new ToolCall("id1", "weather", Map.of("city", "Beijing"))
        ));
        assertThat(m.role()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(m.toolCalls()).hasSize(1);
        assertThat(m.toolCalls().get(0).name()).isEqualTo("weather");
        assertThat(m.toolCalls().get(0).arguments()).containsEntry("city", "Beijing");
        // content 可为空（含 tool_call 时允许 content 为空）
        assertThat(m.content()).isIn(null, "");
    }

    @Test
    @DisplayName("TOOL：content=null；toolResult/toolName/toolCallId 必须非空")
    void toolRequiresResult() {
        ToolResult r = ToolResult.ok(Map.of("temperature", 18));
        Message m = Message.toolResult("call-id-1", "weather", r);
        assertThat(m.role()).isEqualTo(Message.Role.TOOL);
        assertThat(m.content()).isNull();
        assertThat(m.toolCallId()).isEqualTo("call-id-1");
        assertThat(m.toolName()).isEqualTo("weather");
        assertThat(m.toolResult()).isSameAs(r);
    }

    @Test
    @DisplayName("TOOL：toolResult=null 抛 IllegalArgumentException")
    void toolRejectsNullResult() {
        assertThatThrownBy(() -> Message.toolResult("id", "name", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("构造时可指定 createdAt，便于测试断言")
    void createdAtIsConfigurable() {
        Instant t = Instant.parse("2026-07-25T00:00:00Z");
        Message m = new Message(Message.Role.USER, "x", null, null, null, null, t);
        assertThat(m.createdAt()).isEqualTo(t);
    }
}