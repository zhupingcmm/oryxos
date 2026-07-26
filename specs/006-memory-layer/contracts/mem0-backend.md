# 契约：Mem0MemoryStore（自托管语义检索后端）

**目的**：定义 `Mem0MemoryStore` 实现契约（[spec.md FR-015](../spec.md)），调自托管 Mem0 服务的 HTTP 客户端
**归属模块**：`oryxos-memory`
**位置**：`oryxos-memory/src/main/java/io/oryxos/memory/backend/Mem0MemoryStore.java`
**关联契约**：[long-term-store.md §C-LT](./long-term-store.md) | [data-model.md §6](../data-model.md)

---

## 1. 类签名

```java
package io.oryxos.memory.backend;

import io.oryxos.memory.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 自托管 Mem0 服务客户端（spec FR-015）。
 *
 * 架构：
 *   Mem0MemoryStore ──HTTP POST /memories──> Mem0 Service
 *                  ──HTTP POST /memories/search──> Mem0 Service
 *                  ──HTTP DELETE /memories/{id}──> Mem0 Service
 *                  └───local memory_index 映射表（SQLite）
 *
 * 不可达容错（research R-03）：
 * - save: Mem0 不可达 → 记录 pending=true 返回 success=true + metadata={pending:true}
 * - recallByKeyword: 先查本地映射表 + 调 Mem0 服务；两者失败 → 返回空 + warning
 *
 * 注意：本 spec 不实现 Mem0 服务本身（宪法 §II），仅实现 HTTP 客户端。
 */
@Component("mem0MemoryStore")
public class Mem0MemoryStore implements LongTermMemoryStore {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final Duration timeout;
    private final MemoryEntryIndexRepository indexRepository;  // memory_index 表 JPA Repository

    public Mem0MemoryStore(
        HttpClient httpClient,
        ObjectMapper objectMapper,
        @Value("${oryxos.memory.mem0.base-url:http://localhost:8000}") String baseUrl,
        @Value("${oryxos.memory.mem0.timeout-seconds:5}") int timeoutSeconds,
        MemoryEntryIndexRepository indexRepository
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.indexRepository = indexRepository;
    }

    @Override
    public MemoryEntry save(MemoryScope scope, String content, List<String> tags) {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        String entryId = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();

        try {
            // 1. POST /memories with metadata.user_id = entryId
            Map<String, Object> body = Map.of(
                "content", content,
                "metadata", Map.of(
                    "user_id", entryId,
                    "scope", scope.name(),
                    "tags", tags == null ? List.of() : tags
                )
            );
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/memories"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Map<String, Object> resp = objectMapper.readValue(response.body(), Map.class);
                String mem0Id = (String) resp.get("id");
                indexRepository.save(new MemoryEntryIndexEntity(entryId, mem0Id, scope, createdAt.toEpochMilli(), false));
                return new MemoryEntry(entryId, scope, content, tags, createdAt, scope.name());
            } else {
                throw new MemoryException("mem0 save failed: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            // 不可达容错（research R-03）：写入本地 pending=true 映射
            indexRepository.save(new MemoryEntryIndexEntity(entryId, null, scope, createdAt.toEpochMilli(), true));
            // 返回 success 状态的 MemoryEntry；Tool 层转 ToolResult.success=true + metadata={pending:true}
            return new MemoryEntry(entryId, scope, content, tags, createdAt, scope.name(), "pending=true");
        }
    }

    @Override
    public List<MemoryEntry> recallByKeyword(String query, int topK, MemoryScope scopeFilter) {
        try {
            Map<String, Object> body = Map.of("query", query, "limit", topK);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/memories/search"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new MemoryException("mem0 search failed: HTTP " + response.statusCode());
            }
            // 解析响应 → List<MemoryEntry>
            Map<String, Object> resp = objectMapper.readValue(response.body(), Map.class);
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
            List<MemoryEntry> entries = new ArrayList<>();
            for (Map<String, Object> r : results) {
                String mem0Id = (String) r.get("id");
                // 通过 memory_index 表查找本地 entryId + scope
                MemoryEntryIndexEntity index = indexRepository.findByMem0Id(mem0Id).orElse(null);
                if (index == null) continue;
                entries.add(new MemoryEntry(
                    index.getLocalId(),
                    index.getScope(),
                    (String) r.get("memory"),
                    null,
                    Instant.ofEpochMilli(index.getCreatedAt()),
                    index.getScope().name()
                ));
            }
            return entries;
        } catch (Exception e) {
            // 不可达容错：返回本地 memory_index 表的最近快照
            return loadLocalSnapshot(query, topK, scopeFilter);
        }
    }

    @Override
    public List<MemoryEntry> recallByScope(MemoryScope scope, int topK) {
        // 读本地 memory_index 表
        return indexRepository.findByScopeOrderByCreatedAtDesc(scope, topK)
            .stream().map(this::indexToMemoryEntry).toList();
    }

    @Override
    public boolean delete(String entryId) {
        Optional<MemoryEntryIndexEntity> indexOpt = indexRepository.findByLocalId(entryId);
        if (indexOpt.isEmpty()) return false;
        MemoryEntryIndexEntity index = indexOpt.get();
        if (index.getMem0Id() != null) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/memories/" + index.getMem0Id()))
                    .timeout(timeout)
                    .DELETE()
                    .build();
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                // Mem0 删除失败但本地删了 → 后台 flush 会重试
            }
        }
        indexRepository.delete(index);
        return true;
    }

    @Override
    public void clear(MemoryScope scope) {
        if (scope == MemoryScope.core) {
            throw new IllegalStateException("core scope cannot be cleared");
        }
        // Mem0 服务端无 bulk delete 接口；逐条删除
        List<MemoryEntryIndexEntity> indices = indexRepository.findByScope(scope);
        for (MemoryEntryIndexEntity index : indices) {
            if (index.getMem0Id() != null) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/memories/" + index.getMem0Id()))
                        .timeout(timeout)
                        .DELETE()
                        .build();
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    // 容错
                }
            }
        }
        indexRepository.deleteByScope(scope);
    }

    @Override
    public boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private List<MemoryEntry> loadLocalSnapshot(String query, int topK, MemoryScope scopeFilter) {
        // research R-03 容错：从 memory_index 表查最近 N 条 + content 子串匹配
        List<MemoryEntryIndexEntity> indices = scopeFilter == null
            ? indexRepository.findRecent(topK)
            : indexRepository.findByScopeOrderByCreatedAtDesc(scopeFilter, topK);
        return indices.stream()
            .filter(i -> query == null || i.getLocalId().contains(query))   // 简化版子串匹配
            .map(this::indexToMemoryEntry)
            .toList();
    }

    private MemoryEntry indexToMemoryEntry(MemoryEntryIndexEntity index) {
        return new MemoryEntry(
            index.getLocalId(),
            index.getScope(),
            "(content stored in Mem0)",
            null,
            Instant.ofEpochMilli(index.getCreatedAt()),
            index.getScope().name()
        );
    }
}
```

---

## 2. Mem0 HTTP 接口契约

### 2.1 `POST /memories`（保存）

```http
POST {baseUrl}/memories HTTP/1.1
Content-Type: application/json

{
  "content": "user prefers PR tags = bug+enhancement",
  "metadata": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "scope": "core",
    "tags": ["preference", "github"]
  }
}

HTTP/1.1 200 OK
Content-Type: application/json

{
  "id": "mem0-server-generated-id-abc123",
  "memory": "user prefers PR tags = bug+enhancement"
}
```

### 2.2 `POST /memories/search`（recallByKeyword）

```http
POST {baseUrl}/memories/search HTTP/1.1
Content-Type: application/json

{
  "query": "PR tags preference",
  "limit": 10
}

HTTP/1.1 200 OK
Content-Type: application/json

{
  "results": [
    {
      "id": "mem0-server-generated-id-abc123",
      "memory": "user prefers PR tags = bug+enhancement",
      "score": 0.92,
      "metadata": { "user_id": "...", "scope": "core" }
    }
  ]
}
```

### 2.3 `DELETE /memories/{mem0_id}`

```http
DELETE {baseUrl}/memories/mem0-server-generated-id-abc123 HTTP/1.1

HTTP/1.1 200 OK
```

### 2.4 `GET /health`

```http
GET {baseUrl}/health HTTP/1.1

HTTP/1.1 200 OK
```

> **Mem0 服务接口依据**：本 spec 假设 Mem0 自托管服务暴露 `POST /memories` / `POST /memories/search` / `DELETE /memories/{id}` / `GET /health` 四个端点。如果实际 Mem0 部署暴露不同端点，需在 `tasks.md` 阶段调整。

---

## 3. 本地映射表 `memory_index`（SQLite）

```sql
CREATE TABLE IF NOT EXISTS memory_index (
    local_id      TEXT PRIMARY KEY,                              -- UUID v4（OryxOS 本地主键）
    mem0_id       TEXT,                                          -- Mem0 服务端 id；null = pending
    scope         TEXT NOT NULL CHECK (scope IN ('core','archive')),
    created_at    INTEGER NOT NULL,                              -- epoch millis
    pending       INTEGER NOT NULL DEFAULT 0                    -- 0 = synced, 1 = 待重试
);

CREATE INDEX idx_memory_index_scope_created ON memory_index (scope, created_at DESC);
CREATE INDEX idx_memory_index_pending ON memory_index (pending);
```

> **为何单独成表**：`memory_index` **不**与 `agent_memories` 合并 —— 后者是 SqliteMemoryStore 的存储介质，前者是 Mem0MemoryStore 的本地映射。两表结构不同（一个存 content，一个只存 id 映射）；合并会导致存储语义混乱。

---

## 4. 契约条款（Mem0 后端专属）

| 编号 | 条款 | 验证手段 |
|------|------|---------|
| C-M0-01 | **不可达 save 容错**：Mem0 服务不可达时 MUST 落本地 `memory_index` 的 `pending=true` 行（research R-03） | WireMock stub 503 → 测试本地 memory_index 含 1 行 pending=true |
| C-M0-02 | **不可达 recall 容错**：Mem0 服务不可达时 MUST 降级到本地 memory_index 快照（research R-03） | WireMock stub 503 → 测试 recall 返回本地最近 N 条 |
| C-M0-03 | **超时 5s**：HTTP 请求 MUST 用 `timeout = Duration.ofSeconds(5)`（spec NFR-001） | WireMock stub delay(10s) → 测试抛 `MemoryException` 或降级 |
| C-M0-04 | **HTTP 客户端共享**：复用 `oryxos-boot` 已装配的 `HttpClient` Bean（[research.md R-01](./research.md) 共享） | 测试用 `@MockBean HttpClient` 验证 |
| C-M0-05 | **local_id 与 mem0_id 映射**：每次 save MUST 写入 `memory_index` 表记录 localId → mem0Id 映射 | 测试 save 后查表有 1 行 |
| C-M0-06 | **metadata.user_id = localId**：Mem0 请求 MUST 把 localId 放在 `metadata.user_id` 字段 | WireMock 验证收到的 JSON 含 user_id 字段 |
| C-M0-07 | **core 不 trim**：Mem0 后端 MUST 不主动 trim core 区（CLAUDE.md §9.6 契约 ②） | 测试 save(core) 1500 条 → recall 命中 1500 条 |
| C-M0-08 | **scope 校验**：HTTP 请求 metadata.scope MUST 是 `core` 或 `archive`（SQLite CHECK 约束） | 测试 save(scope=invalid) → IllegalArgumentException |
| C-M0-09 | **delete 双删**：delete MUST 先删 Mem0 服务端（若 mem0Id 非空），再删本地 mapping；任一失败不抛异常 | WireMock stub DELETE 503 → 测试本地 mapping 仍被删 |
| C-M0-10 | **isHealthy via /health**：isHealthy MUST 调 Mem0 `/health` 端点，2s 超时 | WireMock stub /health 200/500 |

---

## 5. 性能特征

| 操作 | 量级 | P95 wall-time（含 HTTP） |
|------|------|-------------------------|
| save（Mem0 可达） | N=1 条 | ≤ 200ms（HTTP 50ms + 本地索引 5ms） |
| save（Mem0 不可达） | N=1 条 | ≤ 10ms（仅本地 mapping） |
| recallByKeyword | N=100 条 | ≤ 300ms（HTTP 200ms + 本地 join 50ms） |
| delete | N=1 条 | ≤ 100ms（HTTP 50ms + 本地 5ms） |

> **不可达降级时**：recallByKeyword ≤ 50ms（仅本地表查询，不发 HTTP）。

---

## 6. 测试用例

| TestID | 场景 | 断言 |
|--------|------|------|
| M0-IT-01 | WireMock stub /memories 200 → save 成功 | local memory_index 含 1 行 pending=false |
| M0-IT-02 | WireMock stub /memories 503 → save "降级" | local memory_index 含 1 行 pending=true + entry.createdAt 正确 |
| M0-IT-03 | WireMock stub /memories/search 200 → recall 命中 | 返回 List<MemoryEntry> |
| M0-IT-04 | WireMock stub /memories/search 503 → recall 降级 | 返回本地最近 N 条 + warning |
| M0-IT-05 | WireMock stub delay(10s) → save/recall 超时 5s | 抛 `MemoryException` 或降级（不阻塞 Agent） |
| M0-IT-06 | clear(core) → IllegalStateException（C-LT-05） | 抛异常 |
| M0-IT-07 | save 1500 条 core → recall 命中 1500 条（C-M0-07） | count = 1500 |
| M0-IT-08 | save(metadata.user_id=localId) → WireMock 收到 user_id 字段（C-M0-06） | WireMock 验证请求体 |
| M0-IT-09 | delete(mem0_id present) → Mem0 DELETE + 本地 delete | WireMock 收到 DELETE 请求 + 本地 mapping 删 |
| M0-IT-10 | delete(mem0_id null / pending) → 仅本地 delete | WireMock 收到 0 个 DELETE 请求 |
| M0-IT-11 | isHealthy: WireMock stub /health 200 | true |
| M0-IT-12 | isHealthy: WireMock stub /health 503 | false |
| M0-IT-13 | 性能：save 100 次 P95 ≤ 200ms（NFR-001） | 100 次 save P95 < 200ms |

---

## 7. 与既有契约的关系

| 既有契约 | 关系 |
|----------|------|
| [CLAUDE.md §9.6](../CLAUDE.md) | 4 条契约码化为 C-M0-07 + C-LT-01/02/03/05 |
| [spec.md FR-015](../spec.md) | HTTP 客户端 + 本地 memory_index 映射表 |
| [research.md R-03](./research.md) | 不可达 save 容错 + recall 降级 + 后台 flush |
| [data-model.md §6](../data-model.md) | memory_index 表 DDL + 与 agent_memories 区分 |
| [005-tool-system HttpClient Config](../005-tool-system/plan.md) | 共享 HttpClient Bean（`HttpClient.newBuilder().connectTimeout(5s)`） |