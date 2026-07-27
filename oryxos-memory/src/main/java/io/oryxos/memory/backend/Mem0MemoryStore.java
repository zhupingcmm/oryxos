package io.oryxos.memory.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.memory.MemoryEntry;
import io.oryxos.memory.MemoryException;
import io.oryxos.memory.MemoryScope;
import io.oryxos.memory.repository.MemoryEntryIndexEntity;
import io.oryxos.memory.repository.MemoryEntryIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 长期层 Mem0 后端（006-memory-layer US-3 / T034 / FR-015）。
 *
 * <p>把条目存到自托管 Mem0 服务（HTTP），本地留索引表 {@code memory_index}（V5 DDL）用于：
 * <ul>
 *   <li><b>不可达降级</b>（research R-03）：save 时 Mem0 不可达 → 落 {@code pending=true} 行，recallByKeyword 走本地索引</li>
 *   <li><b>Mem0 恢复后回填</b>：{@code isHealthy()=true} 时把 pending 行批量 POST 到 Mem0 → 更新 mem0_id</li>
 *   <li><b>localId ↔ mem0Id 映射</b>：双 ID 体系，便于审计 + 跨后端追溯</li>
 * </ul>
 *
 * <p>契约条款（[contracts/mem0-backend.md §1](../specs/006-memory-layer/contracts/mem0-backend.md)）：
 * <ul>
 *   <li>C-M0-01 unreachable-save —— Mem0 不可达 → 落 pending=true 行 + 不抛异常</li>
 *   <li>C-M0-02 unreachable-recall —— 降级到本地 memory_index 召回</li>
 *   <li>C-M0-03 timeout-5s —— HTTP 超时 5s（配置可覆盖）</li>
 *   <li>C-M0-04 shared-http-client —— 单 HttpClient 实例</li>
 *   <li>C-M0-05 localId-mapping —— 双 ID 映射在 memory_index 表</li>
 *   <li>C-M0-06 metadata-userId —— 用 user_id=scope 区分 core/archive</li>
 *   <li>C-M0-07 core-no-trim —— 不主动 trim core</li>
 *   <li>C-M0-08 scope-validation —— scope 非法抛 IllegalArgumentException</li>
 *   <li>C-M0-09 delete-double-delete —— delete 本地+远端；重复删返 false 不抛异常</li>
 *   <li>C-M0-10 health-check —— {@code isHealthy() = GET /health 200}</li>
 * </ul>
 */
@Component("mem0MemoryStore")
@ConditionalOnProperty(
    name = "oryxos.memory.backend",
    havingValue = "mem0"
)
public class Mem0MemoryStore implements LongTermMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(Mem0MemoryStore.class);
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final MemoryEntryIndexRepository indexRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration timeout;

    public Mem0MemoryStore(
        MemoryEntryIndexRepository indexRepository,
        ObjectMapper objectMapper,
        @Value("${oryxos.memory.mem0.base-url:http://localhost:8000}") String baseUrl,
        @Value("${oryxos.memory.mem0.timeout-seconds:5}") int timeoutSeconds
    ) {
        if (indexRepository == null) {
            throw new IllegalArgumentException("indexRepository must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8000";
        }
        this.indexRepository = indexRepository;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    /** 测试可见的注入工厂（让 WireMock 测试能注入 baseUrl）。 */
    public static Mem0MemoryStore forTest(
        MemoryEntryIndexRepository repo, ObjectMapper mapper, String baseUrl, int timeoutSeconds
    ) {
        return new Mem0MemoryStore(repo, mapper, baseUrl, timeoutSeconds);
    }

    @Override
    @Transactional
    public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        List<String> safeTags = tags == null ? List.of() : List.copyOf(tags);

        String localId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        String tagsJson = serializeTags(safeTags);

        // 1. 尝试 POST 到 Mem0 服务
        String mem0Id;
        boolean pending;
        try {
            mem0Id = postMemoryToMem0(scope, content, safeTags);
            pending = false;
            log.debug("Mem0MemoryStore.save: POST /memories success, mem0_id={}", mem0Id);
        } catch (Exception ex) {
            // C-M0-01 unreachable-save：Mem0 不可达 → 落 pending=true，不抛异常
            log.warn("Mem0MemoryStore.save unreachable, falling back to pending=true: {}", ex.getMessage());
            mem0Id = null;
            pending = true;
        }

        // 2. 写本地 memory_index
        MemoryEntryIndexEntity entity = new MemoryEntryIndexEntity(
            localId, mem0Id, scope, content, tagsJson, scope.name().toLowerCase(),
            pending, createdAt.toEpochMilli());
        indexRepository.save(entity);

        return new MemoryEntry(localId, scope, content, safeTags, createdAt, scope.name().toLowerCase());
    }

    @Override
    public List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = normalizeTopK(topK);

        // 1. 优先走 Mem0 检索
        try {
            List<MemoryEntry> remote = searchMem0(query, scopeFilter, limit);
            if (!remote.isEmpty()) {
                return remote;
            }
        } catch (Exception ex) {
            log.warn("Mem0MemoryStore.recallByKeyword remote search failed, falling back to local: {}", ex.getMessage());
        }

        // 2. C-M0-02 不可达降级到本地 memory_index
        List<MemoryEntryIndexEntity> localHits = indexRepository.findByContentLikeAndPendingFalse(
            query, PageRequest.of(0, limit));
        return localHits.stream()
            .map(e -> e.toMemoryEntry(deserializeTags(e.getTagsJson())))
            .toList();
    }

    @Override
    public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        int limit = normalizeTopK(topK);
        List<MemoryEntryIndexEntity> entities =
            indexRepository.findByScopeAndPendingFalseOrderByCreatedAtMillisDesc(
                scope, PageRequest.of(0, limit));
        return entities.stream()
            .map(e -> e.toMemoryEntry(deserializeTags(e.getTagsJson())))
            .toList();
    }

    @Override
    @Transactional
    public boolean delete(String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return false;
        }
        Optional<MemoryEntryIndexEntity> opt = indexRepository.findByLocalId(entryId);
        if (opt.isEmpty()) {
            return false;
        }
        MemoryEntryIndexEntity entity = opt.get();
        // C-M0-09 delete-double-delete：远端删除失败也返 true（本地已删），重复删 entryId 不存在返 false
        if (entity.getMem0Id() != null) {
            try {
                deleteMemoryFromMem0(entity.getMem0Id());
            } catch (Exception ex) {
                log.warn("Mem0MemoryStore.delete remote DELETE failed for mem0_id={}: {}",
                    entity.getMem0Id(), ex.getMessage());
            }
        }
        indexRepository.deleteById(entryId);
        return true;
    }

    @Override
    @Transactional
    public void clear(MemoryScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        // C-LT-05 硬约束：core 永不被 clear
        if (scope == MemoryScope.CORE) {
            throw new IllegalStateException(
                "clear(core) is forbidden: core scope is never truncated (CLAUDE.md §9.6 契约 ②)");
        }
        indexRepository.deleteByScope(scope);
    }

    @Override
    public boolean isHealthy() {
        // C-M0-10 health-check：GET /health 200
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .timeout(timeout)
                .GET()
                .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception ex) {
            log.warn("Mem0MemoryStore.isHealthy failed: {}", ex.getMessage());
            return false;
        }
    }

    // ===== HTTP helpers =====

    /**
     * POST /memories 把 content + tags 写到 Mem0。返回 mem0_id；失败抛 RuntimeException 由 save() 兜底。
     */
    String postMemoryToMem0(MemoryScope scope, String content, List<String> tags) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("user_id", scope.name().toLowerCase()); // C-M0-06 metadata-userId
        if (!tags.isEmpty()) {
            body.put("metadata", Map.of("tags", tags));
        }
        String json = objectMapper.writeValueAsString(body);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/memories"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new MemoryException("Mem0 POST /memories failed: " + resp.statusCode() + " " + resp.body());
        }
        // 解析 {"id":"mem0-xxx"} → mem0_id
        Map<String, Object> parsed = objectMapper.readValue(resp.body(), new TypeReference<>() {});
        Object idObj = parsed.get("id");
        if (idObj == null) {
            throw new MemoryException("Mem0 POST /memories response missing 'id' field");
        }
        return idObj.toString();
    }

    /**
     * GET /memories/search?q=...&user_id=... → Mem0 命中列表。失败抛 RuntimeException。
     */
    List<MemoryEntry> searchMem0(String query, MemoryScope scopeFilter, int limit) throws Exception {
        StringBuilder url = new StringBuilder(baseUrl).append("/memories/search?q=")
            .append(java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
        if (scopeFilter != null) {
            url.append("&user_id=").append(scopeFilter.name().toLowerCase());
        }
        url.append("&limit=").append(limit);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url.toString()))
            .timeout(timeout)
            .GET()
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new MemoryException("Mem0 GET /memories/search failed: " + resp.statusCode());
        }
        // 简化解析：Mem0 响应是 [{id, content, user_id, metadata}, ...]
        List<Map<String, Object>> hits = objectMapper.readValue(
            resp.body(), new TypeReference<>() {});
        List<MemoryEntry> out = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            String hitContent = String.valueOf(hit.getOrDefault("content", ""));
            String userId = String.valueOf(hit.getOrDefault("user_id", "core"));
            MemoryScope hitScope = "archive".equals(userId) ? MemoryScope.ARCHIVE : MemoryScope.CORE;
            @SuppressWarnings("unchecked")
            List<String> hitTags = (List<String>) Optional.ofNullable(
                (Map<String, Object>) hit.get("metadata"))
                .map(m -> m.get("tags"))
                .filter(List.class::isInstance)
                .orElse(List.of());
            out.add(new MemoryEntry(
                String.valueOf(hit.getOrDefault("id", UUID.randomUUID().toString())),
                hitScope, hitContent, hitTags,
                Instant.now(),  // Mem0 不一定回 createdAt；fallback
                userId));
        }
        return out;
    }

    /**
     * DELETE /memories/{mem0_id} —— Mem0 服务端删除。
     */
    void deleteMemoryFromMem0(String mem0Id) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/memories/" + mem0Id))
            .timeout(timeout)
            .DELETE()
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2 && resp.statusCode() != 404) {
            throw new MemoryException("Mem0 DELETE /memories/" + mem0Id + " failed: " + resp.statusCode());
        }
    }

    // ===== Internal utilities =====

    /** 序列化 tags 列表为 JSON 数组字符串（与 SqliteMemoryStore 一致）。 */
    String serializeTags(List<String> tags) {
        try {
            return tags == null || tags.isEmpty() ? "[]" : objectMapper.writeValueAsString(tags);
        } catch (Exception ex) {
            throw new MemoryException("failed to serialize tags: " + ex.getMessage(), ex);
        }
    }

    /** 反序列化 JSON 数组字符串为 tags 列表；解析失败返回空列表。 */
    List<String> deserializeTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank() || "[]".equals(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, STRING_LIST_TYPE);
        } catch (Exception ex) {
            log.warn("Mem0MemoryStore.deserializeTags failed (json='{}'): {}", tagsJson, ex.getMessage());
            return List.of();
        }
    }

    private static int normalizeTopK(int topK) {
        if (topK <= 0) return 1;
        if (topK > 100) return 100;
        return topK;
    }
}