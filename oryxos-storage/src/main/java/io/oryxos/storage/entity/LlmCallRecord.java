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
 * LLM 调用审计记录（对应 {@code llm_calls} 表）。
 *
 * <p>day-one 审计地基——按 spec FR-007 / FR-008 + 宪法 §VI，每条 Provider 调用
 * 不论成败都必须产出一行记录。本实体由 {@code oryxos-provider} 模块的
 * {@code AuditWriter} 写入；应用层用 {@code @Check} 约束兜底，
 * DB 层用 {@code CHECK} 约束保底。
 *
 * <p>字段定义详见 [data-model.md](../../../../specs/001-llm-provider-routing/data-model.md) §2。
 */
@Entity
@Table(
    name = "llm_calls",
    indexes = {
        @Index(name = "idx_session",     columnList = "session_id"),
        @Index(name = "idx_profile",     columnList = "profile_name"),
        @Index(name = "idx_provider_ts", columnList = "provider, timestamp"),
        @Index(name = "idx_success_ts",  columnList = "success, timestamp")
    }
)
@Check(constraints = "success = 0 OR error_message IS NULL")
@Check(constraints = "duration_ms >= 0")
public class LlmCallRecord {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "TEXT")
    private UUID id;

    /** 可空：CLI 直调无 session；逻辑外键到 {@code sessions.id}（不加 SQL FK 约束）。 */
    @Column(name = "session_id", columnDefinition = "TEXT")
    private UUID sessionId;

    /** 系统调用可为空串。 */
    @Column(name = "profile_name", nullable = false, columnDefinition = "TEXT")
    private String profileName;

    /** 路由键（如 {@code deepseek}），必须与 {@code application.yml} 中某条 name 一致。 */
    @Column(name = "provider", nullable = false, columnDefinition = "TEXT")
    private String provider;

    /** 实际使用的模型（如 {@code deepseek-chat}），Profile 的 {@code provider.model} 可覆盖默认。 */
    @Column(name = "model", nullable = false, columnDefinition = "TEXT")
    private String model;

    @Column(name = "success", nullable = false)
    private boolean success;

    /** {@code success=true} 时必须为 null；失败时非空。 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    /** UTC，调用发起时间。 */
    @Column(name = "timestamp", nullable = false, columnDefinition = "TEXT")
    private Instant timestamp;

    /**
     * 请求/响应的原始载荷（脱敏后的 messages + tool_schemas + 响应 tool_calls）。
     * 存 JSON 列，便于审计员日后查具体内容（不在核心阶段做查询 UI，但留字段）。
     */
    @Type(JsonType.class)
    @Column(name = "payload", columnDefinition = "TEXT")
    private Map<String, Object> payload;

    // --- 构造器 / 访问器 ---

    protected LlmCallRecord() {
        // JPA
    }

    public LlmCallRecord(UUID id, UUID sessionId, String profileName,
                         String provider, String model, boolean success,
                         String errorMessage, Integer promptTokens, Integer completionTokens,
                         long durationMs, Instant timestamp, Map<String, Object> payload) {
        this.id = id;
        this.sessionId = sessionId;
        this.profileName = profileName;
        this.provider = provider;
        this.model = model;
        this.success = success;
        this.errorMessage = errorMessage;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.durationMs = durationMs;
        this.timestamp = timestamp;
        this.payload = payload;
    }

    public UUID getId()                              { return id; }
    public UUID getSessionId()                       { return sessionId; }
    public String getProfileName()                   { return profileName; }
    public String getProvider()                      { return provider; }
    public String getModel()                         { return model; }
    public boolean isSuccess()                       { return success; }
    public String getErrorMessage()                  { return errorMessage; }
    public Integer getPromptTokens()                 { return promptTokens; }
    public Integer getCompletionTokens()             { return completionTokens; }
    public long getDurationMs()                      { return durationMs; }
    public Instant getTimestamp()                    { return timestamp; }
    public Map<String, Object> getPayload()          { return payload; }
}