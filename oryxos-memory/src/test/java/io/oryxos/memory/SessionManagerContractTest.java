package io.oryxos.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T015（006-memory-layer Phase 3）—— SessionManager 契约测试（spec FR-002）。
 *
 * <p>验证会话层与长期层边界分明（CLAUDE.md §9.6 契约 ②）：
 * <ul>
 *   <li>管理当前 Session 的对话消息（{@link SessionManager.Message}）</li>
 *   <li>**不**暴露 {@link io.oryxos.memory.backend.LongTermMemoryStore} 引用 —— 避免会话层误调长期层</li>
 *   <li>**不**触发任何形式的"会话结束 → save 到长期" 副作用（Agent 必须显式调 {@code save_memory} Tool）</li>
 * </ul>
 */
class SessionManagerContractTest {

    SessionManager session;

    @BeforeEach
    void setUp() {
        session = new SessionManager("sess-001");
    }

    @Test
    @DisplayName("FR-002：构造器接受非空 sessionId")
    void constructor_rejects_blank_session_id() {
        assertThatThrownBy(() -> new SessionManager(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> new SessionManager(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionManager("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FR-002：addMessage + getMessages 正常累积并返回不可变快照")
    void add_message_accumulates() {
        assertThat(session.getMessages()).isEmpty();
        session.addMessage(SessionManager.Message.user("hi"));
        session.addMessage(SessionManager.Message.assistant("hello"));
        session.addMessage(SessionManager.Message.tool("{\"ok\":true}"));
        assertThat(session.getMessages()).hasSize(3);
        assertThat(session.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("FR-002：getMessages 返回 List.copyOf 不可变快照 —— 调用方修改不影响内部状态")
    void get_messages_returns_immutable_snapshot() {
        session.addMessage(SessionManager.Message.user("x"));
        var snapshot = session.getMessages();
        assertThatThrownBy(() -> snapshot.add(SessionManager.Message.user("y")))
            .isInstanceOf(UnsupportedOperationException.class);
        // 内部状态未变
        assertThat(session.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-002：addMessage(null) MUST 抛 IllegalArgumentException")
    void add_null_message_throws() {
        assertThatThrownBy(() -> session.addMessage(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FR-002：Message 构造器校验 role / content 非空")
    void message_construction_validates() {
        assertThatThrownBy(() -> new SessionManager.Message(null, "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionManager.Message("", "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionManager.Message("user", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FR-002：Message 工厂方法 user/assistant/system/tool 正确生成")
    void message_factory_methods() {
        assertThat(SessionManager.Message.user("hi").role()).isEqualTo("user");
        assertThat(SessionManager.Message.assistant("hi").role()).isEqualTo("assistant");
        assertThat(SessionManager.Message.system("hi").role()).isEqualTo("system");
        assertThat(SessionManager.Message.tool("hi").role()).isEqualTo("tool");
    }

    @Test
    @DisplayName("FR-002：Message.toMap() 输出 {role, content} Map —— 兼容 LLM SDK 调用方")
    void message_to_map_format() {
        var m = SessionManager.Message.user("hi");
        assertThat(m.toMap()).containsEntry("role", "user").containsEntry("content", "hi");
    }

    @Test
    @DisplayName("边界：SessionManager 不暴露 LongTermMemoryStore 引用（编译期保证）")
    void session_manager_does_not_expose_long_term_store() {
        // SessionManager 类没有 LongTermMemoryStore 字段 / 方法 —— 通过反射防御性确认
        boolean hasLongTermStoreField = false;
        for (var f : SessionManager.class.getDeclaredFields()) {
            if (f.getType().getName().contains("LongTermMemoryStore")) {
                hasLongTermStoreField = true;
                break;
            }
        }
        assertThat(hasLongTermStoreField)
            .as("SessionManager MUST NOT have any LongTermMemoryStore reference (spec FR-002)")
            .isFalse();
    }

    @Test
    @DisplayName("边界：SessionManager 不持有 MemoryService / DefaultMemoryService 引用（编译期保证）")
    void session_manager_does_not_expose_memory_service() {
        boolean hasMemoryServiceField = false;
        for (var f : SessionManager.class.getDeclaredFields()) {
            String typeName = f.getType().getName();
            if (typeName.contains("MemoryService") || typeName.contains("DefaultMemoryService")) {
                hasMemoryServiceField = true;
                break;
            }
        }
        assertThat(hasMemoryServiceField)
            .as("SessionManager MUST NOT reference MemoryService (会话层不调长期层)")
            .isFalse();
    }

    @Test
    @DisplayName("边界：SessionManager.getSessionId() 返回构造时传入的 ID")
    void get_session_id_returns_constructor_value() {
        assertThat(session.getSessionId()).isEqualTo("sess-001");
        SessionManager s2 = new SessionManager("sess-xyz");
        assertThat(s2.getSessionId()).isEqualTo("sess-xyz");
    }
}