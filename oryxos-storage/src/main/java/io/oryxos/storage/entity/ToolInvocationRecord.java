package io.oryxos.storage.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Tool 调用审计行（对应 {@code tool_invocations} 表）—— day-one 审计地基（Constitution §VI）。
 *
 * <p>US-2 阶段：<strong>仅</strong>建立 schema + Repository 框架。真实写入由 US-4 的
 * {@code ToolExecutor} 实现触发；US-2 stub 阶段为虚拟调用生成行也写此表。
 *
 * <p>详见 [data-model.md §3.9](../../../../../specs/002-react-loop/data-model.md)。
 */
@Entity
@Table(
    name = "tool_invocations",
    indexes = {
        @Index(name = "idx_session",     columnList = "session_id"),
        @Index(name = "idx_profile",     columnList = "profile_name"),
        @Index(name = "idx_tool_ts",     columnList = "tool_name, started_at"),
        @Index(name = "idx_success",     columnList = "success, started_at")
    }
)
@Check(constraints = "success = 0 OR error_message IS NULL")
@Check(constraints = "length(tool_name) > 0")
@Check(constraints = "length(profile_name) > 0")
@Check(constraints = "duration_ms >= 0")
@Check(constraints = "session_iteration >= 0")
public class ToolInvocationRecord {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "TEXT")
    private UUID id;

    /** 可空：CLI 直调无 session。 */
    @Column(name = "session_id", columnDefinition = "TEXT")
    private UUID sessionId;

    @Column(name = "profile_name", nullable = false, columnDefinition = "TEXT")
    private String profileName;

    @Column(name = "tool_name", nullable = false, columnDefinition = "TEXT")
    private String toolName;

    /** 实际传给 Tool 的参数 map。 */
    @Type(JsonType.class)
    @Column(name = "arguments", columnDefinition = "TEXT")
    private Map<String, Object> arguments;

    @Column(name = "success", nullable = false)
    private boolean success;

    /** {@code success=true} 时为 null；失败时非空。 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    /** 本地时间（spec A-007）。 */
    @Column(name = "started_at", nullable = false, columnDefinition = "TEXT")
    private Instant startedAt;

    /** 来自 {@code ProfileContext.current().currentIteration()}，用于跨表 join（spec I-05）。 */
    @Column(name = "session_iteration", nullable = false)
    private int sessionIteration;

    // --- 构造器 ---

    protected ToolInvocationRecord() {
        // JPA
    }

    public ToolInvocationRecord(UUID id, UUID sessionId, String profileName, String toolName,
                                Map<String, Object> arguments, boolean success, String errorMessage,
                                long durationMs, Instant startedAt, int sessionIteration) {
        this.id = id;
        this.sessionId = sessionId;
        this.profileName = profileName;
        this.toolName = toolName;
        this.arguments = arguments;
        this.success = success;
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
        this.startedAt = startedAt;
        this.sessionIteration = sessionIteration;

        validate();
    }

    private void validate() {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (profileName == null || profileName.isBlank()) {
            throw new IllegalArgumentException("profileName must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (!success && (errorMessage == null || errorMessage.isBlank())) {
            throw new IllegalArgumentException(
                "success=false requires non-blank errorMessage");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0, got " + durationMs);
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("startedAt must not be null");
        }
        if (sessionIteration < 0) {
            throw new IllegalArgumentException(
                "sessionIteration must be >= 0, got " + sessionIteration);
        }
    }

    // --- getters ---

    public UUID getId()                       { return id; }
    public UUID getSessionId()                { return sessionId; }
    public String getProfileName()            { return profileName; }
    public String getToolName()               { return toolName; }
    public Map<String, Object> getArguments() { return arguments; }
    public boolean isSuccess()                { return success; }
    public String getErrorMessage()           { return errorMessage; }
    public long getDurationMs()               { return durationMs; }
    public Instant getStartedAt()             { return startedAt; }
    public int getSessionIteration()          { return sessionIteration; }
}
