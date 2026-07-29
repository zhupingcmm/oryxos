package io.oryxos.web.controller;

import io.oryxos.core.Message;
import io.oryxos.core.Profile;
import io.oryxos.core.ProfileRegistry;
import io.oryxos.core.Session;
import io.oryxos.core.ToolResult;
import io.oryxos.core.scheduler.SessionFactory;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import io.oryxos.web.dto.AddMessageResponse;
import io.oryxos.web.dto.CreateSessionRequest;
import io.oryxos.web.dto.MessageDto;
import io.oryxos.web.dto.SessionDto;
import io.oryxos.web.exception.AgentNotFoundException;
import io.oryxos.web.exception.SessionNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T021/T022/T023/T024/T025/T026 + US-2 — SessionsController.
 *
 * <p>4 个端点 (per [contracts/web-api.md §端点 2-5](../../../../../../specs/008-agent-web-service/contracts/web-api.md)):
 * <ul>
 *   <li>{@code POST   /api/v1/sessions}              — 创建 Session</li>
 *   <li>{@code POST   /api/v1/sessions/{id}/messages} — 追加消息到 history</li>
 *   <li>{@code GET    /api/v1/sessions/{id}}          — 查询 Session</li>
 *   <li>{@code DELETE /api/v1/sessions/{id}}          — 软删除</li>
 * </ul>
 *
 * <h2>UUID 校验</h2>
 * <p>{@code {id}} 路径参数走 {@link Pattern} 正则校验 (36 字符标准 UUID 格式);
 * 失败抛 {@code ConstraintViolationException} → {@code GlobalExceptionHandler}
 * 兜底 400 invalid_path_param.
 *
 * <h2>软删除契约</h2>
 * <p>DELETE 走 {@link SessionRepository#findByIdAndDeletedAtIsNull} 查询活跃行,命中后调
 * {@link SessionEntity#markDeleted()} 写入 {@code deleted_at = now()};后续 GET 走同一查询
 * 路径,因 {@code deleted_at IS NOT NULL} 过滤返回 404 session_not_found.
 *
 * <h2>审计 day-one</h2>
 * <p>所有写入均落 {@code sessions} 表,write 直接通过 {@link SessionFactory#create(String, String, String)}
 * (POST) 或 {@link SessionRepository#save} (POST messages / DELETE).
 */
@RestController
@RequestMapping("/api/v1/sessions")
@Validated
public class SessionsController {

    private static final Logger log = LoggerFactory.getLogger(SessionsController.class);

    /** UUID v7 标准格式 (8-4-4-4-12). */
    private static final String UUID_PATTERN =
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final SessionFactory sessionFactory;
    private final ProfileRegistry profileRegistry;
    private final SessionRepository sessionRepository;

    public SessionsController(
        SessionFactory sessionFactory,
        ProfileRegistry profileRegistry,
        SessionRepository sessionRepository
    ) {
        this.sessionFactory = sessionFactory;
        this.profileRegistry = profileRegistry;
        this.sessionRepository = sessionRepository;
    }

    // ===== POST /api/v1/sessions =====

    /**
     * T022 — 创建 Session.
     *
     * <p>调 {@link SessionFactory#create(String, String, String)} (source="web") 自动写入
     * {@code sessions.metadata.source="web"};请求体 {@code metadata} 字段作为附加键
     * 合并到 sessions.metadata.
     *
     * @return 201 Created + Location header + SessionDto
     * @throws AgentNotFoundException 404 — profileName 未注册
     */
    @PostMapping
    public ResponseEntity<SessionDto> create(@Valid @RequestBody CreateSessionRequest req) {
        // profile 必须已加载 (per C-AS-3 + spec US-2 验收场景)
        Profile profile = profileRegistry.find(req.profileName())
            .orElseThrow(() -> new AgentNotFoundException(req.profileName()));

        // SessionFactoryImpl 写 metadata.source="web" + started_at;taskId=null (非 scheduler 路径)
        Session session = sessionFactory.create(profile.name(), null, "web");

        // 用户附加 metadata 合并
        SessionEntity entity = (SessionEntity) session;
        if (req.metadata() != null) {
            for (Map.Entry<String, Object> e : req.metadata().entrySet()) {
                entity.setMetadataValue(e.getKey(), e.getValue());
            }
            entity = sessionRepository.save(entity);
        }

        SessionDto dto = toDto(entity, true);
        return ResponseEntity
            .created(URI.create("/api/v1/sessions/" + entity.id()))
            .body(dto);
    }

    // ===== POST /api/v1/sessions/{id}/messages =====

    /**
     * T023 — 追加消息到 Session history.
     *
     * <p>追加一条 {@link Message} → 更新 updatedAt → save() (JPA 脏检查自动 flush).
     *
     * @param id   Session UUID
     * @param msg  消息体 (role / content / toolName / timestamp)
     * @return 201 Created + 添加结果 + 新 session 信息
     * @throws SessionNotFoundException 404 — session 不存在或已软删除
     */
    @PostMapping("/{id}/messages")
    public ResponseEntity<AddMessageResponse> addMessage(
        @PathVariable @Pattern(regexp = UUID_PATTERN, message = "id must be a valid UUID")
        String id,
        @Valid @RequestBody MessageDto msg
    ) {
        UUID uuid = UUID.fromString(id);
        SessionEntity entity = sessionRepository.findByIdAndDeletedAtIsNull(uuid)
            .orElseThrow(() -> new SessionNotFoundException(id));

        Message message = toMessage(msg);
        entity.appendMessage(message);
        entity = sessionRepository.save(entity);

        return ResponseEntity.status(HttpStatus.CREATED).body(new AddMessageResponse(
            entity.id().toString(),
            entity.messages().size(),
            entity.updatedAt(),
            msg
        ));
    }

    // ===== GET /api/v1/sessions/{id} =====

    /**
     * T024 — 查询 Session 完整信息.
     *
     * @param id              Session UUID
     * @param includeHistory  是否包含 history;默认 true;false 时返回空数组避免大 body
     * @return 200 OK + SessionDto
     * @throws SessionNotFoundException 404 — session 不存在或已软删除
     */
    @GetMapping("/{id}")
    public SessionDto get(
        @PathVariable @Pattern(regexp = UUID_PATTERN, message = "id must be a valid UUID")
        String id,
        @RequestParam(defaultValue = "true") boolean includeHistory
    ) {
        UUID uuid = UUID.fromString(id);
        SessionEntity entity = sessionRepository.findByIdAndDeletedAtIsNull(uuid)
            .orElseThrow(() -> new SessionNotFoundException(id));
        return toDto(entity, includeHistory);
    }

    // ===== DELETE /api/v1/sessions/{id} =====

    /**
     * T025 — 软删除 Session.
     *
     * <p>{@code UPDATE sessions SET deleted_at = now()} 而非真删 (per data-model.md §端点 5);
     * 行仍在 DB,后续 GET 走 {@code findByIdAndDeletedAtIsNull} 查询条件过滤掉 deleted 行,
     * 表现为 404 session_not_found.
     *
     * @param id Session UUID
     * @return 204 No Content
     * @throws SessionNotFoundException 404 — session 不存在或已软删除 (幂等无副作用)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @PathVariable @Pattern(regexp = UUID_PATTERN, message = "id must be a valid UUID")
        String id
    ) {
        UUID uuid = UUID.fromString(id);
        Optional<SessionEntity> opt = sessionRepository.findByIdAndDeletedAtIsNull(uuid);
        if (opt.isEmpty()) {
            // 幂等: 已删 / 不存在 都返回 404;行删除场景的 DELETE verb 推荐反模式
            // (返回 204 让客户端无感);此处与 spec contract 对齐 → 404
            throw new SessionNotFoundException(id);
        }
        SessionEntity entity = opt.get();
        entity.markDeleted();
        sessionRepository.save(entity);
        log.debug("Session {} soft-deleted (deleted_at set)", id);
        return ResponseEntity.noContent().build();
    }

    // ===== helpers =====

    /** SessionEntity → SessionDto (控制 history 是否输出). */
    private static SessionDto toDto(SessionEntity entity, boolean includeHistory) {
        List<Message> msgs = entity.messages();
        List<MessageDto> history = includeHistory ? toMessageDtos(msgs) : List.of();
        return new SessionDto(
            entity.id().toString(),
            entity.profileName(),
            entity.createdAt(),
            entity.updatedAt(),
            msgs.size(),
            entity.getMetadata(),
            history
        );
    }

    private static List<MessageDto> toMessageDtos(List<Message> msgs) {
        List<MessageDto> out = new ArrayList<>(msgs.size());
        for (Message m : msgs) {
            String role = m.role().name().toLowerCase();
            String content = m.content();
            String toolName = m.toolName();
            Instant ts = m.createdAt();
            out.add(new MessageDto(role, content, toolName, ts));
        }
        return out;
    }

    /** MessageDto → Message record 转换 (按 role 三选一构造). */
    private static Message toMessage(MessageDto dto) {
        Instant ts = dto.timestamp() != null ? dto.timestamp() : Instant.now();
        String role = dto.role() == null ? "user" : dto.role().toLowerCase();
        switch (role) {
            case "user":
                return new Message(
                    Message.Role.USER, dto.content(),
                    null, null, null, null, ts);
            case "assistant":
                return new Message(
                    Message.Role.ASSISTANT, dto.content() == null ? "" : dto.content(),
                    new ArrayList<>(), null, null, null, ts);
            case "tool":
                // role=tool 需要 toolResult (success, payload, errorMessage)
                // 本端点是用户手动注入历史,ToolResult 用 ok() + payload={content: text}
                ToolResult result = ToolResult.ok(
                    dto.content() != null
                        ? Map.of("content", dto.content())
                        : Map.of());
                return new Message(
                    Message.Role.TOOL, null, null,
                    "manual:" + UUID.randomUUID(),
                    dto.toolName() != null ? dto.toolName() : "unknown",
                    result, ts);
            default:
                throw new IllegalArgumentException("role must be user|assistant|tool, got: " + dto.role());
        }
    }
}
