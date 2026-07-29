# Quickstart：009-agent-web-service

**生成日期**：2026-07-28
**关联**：[spec.md](spec.md) / [data-model.md](data-model.md) / [contracts/web-api.md](contracts/web-api.md) / [plan.md](plan.md)
**对应 [CLAUDE.md §11 验收 Demo §Demo 四/五](../../CLAUDE.md)**

---

## 概述

本 quickstart 描述如何从零开始验证 008-agent-web-service 的端到端可用性。覆盖：
- 5 分钟 Demo 启动门槛（per spec SC-008）
- 10 个 REST 端点的可执行验收场景
- 性能基线（per spec SC-006）
- 与 CLAUDE.md §11 "Demo 四/五" 的对齐（Web Service 同步调用 + 多端点联动）

**前置条件**：
- JDK 21.0.5+ / Maven 3.9.6+
- 已完成 005-tool-system / 006-memory-layer / 007-sandbox-whitelist / 008-agent-scheduler 四个前置 spec
- `.oryxos/agents/daily-weather-agent/AGENT.md` 已存在（per [008-agent-scheduler/quickstart.md §Demo](../008-agent-scheduler/quickstart.md)）
- API key 已写入环境变量（`DEEPSEEK_API_KEY` / `KIMI_API_KEY`）

---

## 5 分钟启动（per spec SC-008）

### 步骤 1：构建（30 秒）
```bash
cd d:\code\java\oryxos
mvn clean install -DskipTests
```

### 步骤 2：启动 OryxOS serve（10 秒）
```bash
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar serve
```

预期输出：
```text
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

2026-07-28T00:00:00.000Z  INFO ... : Started OryxosApplication in 3.5 seconds
2026-07-28T00:00:00.000Z  INFO ... : Tomcat started on port 8080
2026-07-28T00:00:00.000Z  INFO ... : AgentScheduler bootstrapped with 3 schedules
2026-07-28T00:00:00.000Z  INFO ... : ToolRegistry initialized with 11 tools
2026-07-28T00:00:00.000Z  INFO ... : Total startup time: 4.2 seconds
```

### 步骤 3：第一个 REST 调用（5 秒）
```bash
curl -X POST http://localhost:8080/api/v1/agents/daily-weather-agent/invoke \
  -H "Content-Type: application/json" \
  -d '{"message": "今天上海天气如何？"}'
```

预期响应（HTTP 200）：
```json
{
  "sessionId": "0190a3b4-7c8d-7890-abcd-ef1234567890",
  "reply": "今天上海多云 28°C，偏南风 3 级...",
  "iterations": 3,
  "durationMs": 4250,
  "metadata": {"notify_sent": true, "channel": "feishu-ops"}
}
```

### 步骤 4：健康检查（1 秒）
```bash
curl http://localhost:8080/api/v1/health
```

预期响应（HTTP 200）：
```json
{
  "status": "UP",
  "uptimeMs": 30000,
  "version": "0.1.0-SNAPSHOT",
  "components": {"db": {"status": "UP"}}
}
```

✅ **5 分钟 Demo 门槛达成**：构建 30s + 启动 10s + invoke 5s + health 1s + 浏览器 Swagger UI 验证 1min ≈ 2 分钟内端到端可演示。

---

## 验收场景 1：US-1 业务系统调用 Agent（per spec US-1）

### 场景 1.1：正常 invoke（per spec US-1 验收场景 1）
```bash
curl -X POST http://localhost:8080/api/v1/agents/daily-weather-agent/invoke \
  -H "Content-Type: application/json" \
  -d '{"message": "今天上海天气如何？"}'
```

**预期**：
- HTTP 200 + InvokeResponse JSON
- `sessionId` 是 UUID v7 格式
- `reply` 含天气文本（非空字符串）
- `iterations >= 1`（Agent 调用了工具）
- `durationMs` 在 1s-30s 之间
- `metadata.notify_sent = true`（Agent 通过 notify 工具推送）

### 场景 1.2：指定 sessionId 续接（per spec US-1 验收场景 2）
第一次调用：
```bash
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/v1/agents/daily-weather-agent/invoke \
  -H "Content-Type: application/json" \
  -d '{"message": "今天上海天气"}' | jq -r .sessionId)
```

第二次调用（续接同一 Session）：
```bash
curl -X POST http://localhost:8080/api/v1/agents/daily-weather-agent/invoke \
  -H "Content-Type: application/json" \
  -d "{\"message\": \"明天呢？\", \"sessionId\": \"$SESSION_ID\"}"
```

**预期**：
- 第二次返回的 `sessionId` 与第一次**完全相同**（`$SESSION_ID`）
- Agent 能感知上下文（"明天" 理解为"明天上海天气"）
- DB 直查：`sessions.history` 含 2 条 user + 2 条 assistant + 中间 tool

### 场景 1.3：并发 10 路 invoke 无串话（per spec US-1 验收场景 3）
```bash
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/v1/agents/daily-weather-agent/invoke \
    -H "Content-Type: application/json" \
    -d "{\"message\": \"城市$i 的天气\"}" &
done
wait
```

**预期**：
- 10 个响应含 10 个**不同** `sessionId`（UUID v7 字节级互异）
- 每个 sessionId 仅出现一次（无重复）
- DB 直查：`sessions` 表新增 10 行（每行 `metadata.source="web"`）

### 场景 1.4：Agent 不存在返回 404（per spec US-1 验收场景 4）
```bash
curl -X POST http://localhost:8080/api/v1/agents/no-such-agent/invoke \
  -H "Content-Type: application/json" \
  -d '{"message": "test"}'
```

**预期**：
- HTTP 404 + ErrorResponse JSON
- `error == "agent_not_found"`
- `detail` 含 agent 名 `"no-such-agent"`

### 场景 1.5：消息为空返回 400（边界情况）
```bash
curl -X POST http://localhost:8080/api/v1/agents/daily-weather-agent/invoke \
  -H "Content-Type: application/json" \
  -d '{"message": ""}'
```

**预期**：
- HTTP 400 + ErrorResponse JSON
- `error == "invalid_request"`
- `field == "message"`

### 场景 1.6：非法 JSON 返回 400（边界情况）
```bash
curl -X POST http://localhost:8080/api/v1/agents/daily-weather-agent/invoke \
  -H "Content-Type: application/json" \
  -d '{invalid json}'
```

**预期**：
- HTTP 400 + ErrorResponse JSON
- `error == "invalid_json"`
- `detail` 不含 stack trace（per 007-sandbox-whitelist 契约）

---

## 验收场景 2：US-2 会话管理（per spec US-2）

### 场景 2.1：创建 Session
```bash
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"profileName": "daily-weather-agent", "metadata": {"customer_id": "C-001"}}'
```

**预期**：
- HTTP 201
- `Location` header：`/api/v1/sessions/0190a3b4-...`
- Body `metadata.source == "web"`
- Body `messageCount == 0`

### 场景 2.2：追加消息
```bash
curl -X POST http://localhost:8080/api/v1/sessions/$SESSION_ID/messages \
  -H "Content-Type: application/json" \
  -d '{"role": "user", "content": "今天天气？"}'
```

**预期**：
- HTTP 201
- `messageCount == 1`
- `updatedAt` 时间戳更新

### 场景 2.3：查询 Session
```bash
curl http://localhost:8080/api/v1/sessions/$SESSION_ID
```

**预期**：
- HTTP 200
- `history[]` 含追加的消息
- `metadata.source == "web"`

### 场景 2.4：删除 Session
```bash
curl -X DELETE http://localhost:8080/api/v1/sessions/$SESSION_ID
```

**预期**：
- HTTP 204
- 后续 `GET /api/v1/sessions/$SESSION_ID` 返回 404 session_not_found
- DB 直查：`sessions.deleted_at IS NOT NULL`（软删除）

### 场景 2.5：UUID 格式错返回 400
```bash
curl http://localhost:8080/api/v1/sessions/not-a-uuid
```

**预期**：
- HTTP 400 + `error == "invalid_path_param"`

---

## 验收场景 3：US-3 系统查询（per spec US-3）

### 场景 3.1：Profile 列表
```bash
curl http://localhost:8080/api/v1/profiles
```

**预期**：
- HTTP 200
- 列表长度 ≥ 1（含 daily-weather-agent）
- 每个元素的 `toolCount` / `scheduleCount` 与 YAML 一致

### 场景 3.2：Memory 元数据
```bash
curl http://localhost:8080/api/v1/memory
```

**预期**：
- HTTP 200
- `backend` 字段枚举（markdown / sqlite / mem0）
- `coreEntries` / `archiveEntries` 是非负整数

### 场景 3.3：Tool 列表
```bash
curl http://localhost:8080/api/v1/tools
```

**预期**：
- HTTP 200
- 列表长度 ≥ 9（per CLAUDE.md §9.7 9 个内置 Tool）
- `source` 字段枚举（builtin / mcp / java_bean）

按 source 过滤：
```bash
curl "http://localhost:8080/api/v1/tools?source=mcp"
```

**预期**：
- HTTP 200
- 所有元素的 `source == "mcp"`

---

## 验收场景 4：OpenAPI 契约（per spec FR-013 + contracts/web-api.md）

### 步骤 1：访问 Swagger UI
```bash
start http://localhost:8080/swagger-ui.html
```

**预期**：
- 浏览器显示 Swagger UI
- 列出 10 个 REST 端点

### 步骤 2：下载 OpenAPI YAML
```bash
curl http://localhost:8080/v3/api-docs.yaml > openapi.yaml
```

**预期**：
- HTTP 200
- YAML 含 10 个 paths（`/api/v1/agents/{name}/invoke` 等）
- 每个 path 的 requestBody schema 与本契约字段名一致
- 每个 path 的 responses schema 与本契约字段名一致

### 步骤 3：断言契约一致性（CI 脚本）
```bash
# 任何字段漂移会让此断言失败
grep -q "sessionId" openapi.yaml || echo "FAIL: missing sessionId"
grep -q "InvokeResponse" openapi.yaml || echo "FAIL: missing InvokeResponse"
```

---

## 验收场景 5：与 CLI / Scheduler 共用 AgentService.process()（per spec SC-003）

### 步骤 1：CLI 触发 Agent
```bash
java -jar oryxos-boot/target/oryxos-boot-0.1.0-SNAPSHOT.jar chat \
  --agent daily-weather-agent \
  --message "今天北京天气"
```

**预期**：
- CLI 输出 Agent reply
- `sessions.metadata.source == "cli"`

### 步骤 2：REST 触发同一 Agent
```bash
curl -X POST http://localhost:8080/api/v1/agents/daily-weather-agent/invoke \
  -H "Content-Type: application/json" \
  -d '{"message": "今天上海天气"}'
```

**预期**：
- HTTP 200 + reply
- `sessions.metadata.source == "web"`

### 步骤 3：字节级断言三入口调用同一 Method
```bash
sqlite3 .oryxos/oryxos.db "SELECT session_id, profile_name, metadata FROM sessions ORDER BY created_at DESC LIMIT 3;"
```

**预期输出**（按时间倒序 3 行）：
```text
0190a3b4-...|daily-weather-agent|{"source":"web",...}
0190a3b4-...|daily-weather-agent|{"source":"scheduler",...}
0190a3b4-...|daily-weather-agent|{"source":"cli",...}
```

三行 `metadata.source` 枚举三选一 byte-level 一致；与 008-agent-scheduler 契约对齐。

---

## 验收场景 6：性能基线（per spec SC-006）

### 步骤 1：health 延迟 P95 ≤ 50ms
```bash
# 100 次迭代 + 10 次预热
for i in {1..10}; do curl -s -o /dev/null http://localhost:8080/api/v1/health; done  # warmup
TIMES=""
for i in {1..100}; do
  TIMES="$TIMES $(curl -s -o /dev/null -w '%{time_total}' http://localhost:8080/api/v1/health)"
done
echo "$TIMES" | tr ' ' '\n' | sort -n | awk 'NR==95{print "P95="$1"s"}'
```

**预期**：`P95 <= 0.050s`（50ms）。

### 步骤 2：invoke 端到端延迟（10 次迭代）
```bash
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/v1/agents/daily-weather-agent/invoke \
    -H "Content-Type: application/json" \
    -d '{"message": "今天天气"}' | jq .durationMs
done
```

**预期**：每次 `durationMs` 1s-30s；与 008-agent-scheduler 性能基线对齐。

### 步骤 3：10 并发零串话（per spec SC-004）
参见场景 1.3。

---

## 验收场景 7：与三个 Demo 闭环（per CLAUDE.md §11）

### Demo 四：Web Service 同步调用
- **触发**：`curl POST /api/v1/agents/daily-weather-agent/invoke`
- **流程**：Agent 调用 `http_get` 查天气 → 解析 → 调用 `notify` 推送 → 返回 reply
- **验证**：reply 含天气文本 + `metadata.notify_sent=true` + notify 通道收到推送

### Demo 五：多端点联动
- **触发**：业务方系统连续 3 次调用
  1. `POST /api/v1/sessions` 创建 Session
  2. `POST /api/v1/agents/{name}/invoke` 用同一 sessionId
  3. `GET /api/v1/sessions/{id}` 查询历史
- **流程**：Session 在 3 个端点间保持一致 → Agent 上下文连贯
- **验证**：3 次操作的 sessionId 完全一致 + DB 直查 `sessions.history` 累积所有交互

### 与 Demo 一/二/三 的关系
- **Demo 一**（每日天气）：CLI 触发 —— 008-agent-scheduler 已闭环
- **Demo 二**（每日科技日报）：Scheduler 触发 —— 008-agent-scheduler 已闭环
- **Demo 三**（每日 GitHub 日报）：脚本信任边界 —— 005-tool-system 已闭环
- **Demo 四/五**（Web Service）：REST 触发 —— **本 spec 闭环**

四个 Demo 共享同一 `AgentService.process(Session, String)` 入口；REST 触发与 CLI / Scheduler 走同一 Java 方法对象（per spec SC-003 反射断言）。

---

## 集成测试矩阵（per contracts/web-api.md §集成测试断言）

| 测试类 | 文件路径 | 覆盖端点 | 测试类型 |
|--------|---------|---------|---------|
| `AgentsControllerIT` | `oryxos-web/src/test/java/io/oryxos/web/controller/AgentsControllerIT.java` | 端点 1 | `@SpringBootTest` + `MockMvc` |
| `SessionsControllerIT` | 同上 | 端点 2-5 | `@SpringBootTest` + `MockMvc` |
| `ProfilesControllerIT` | 同上 | 端点 6 | `@SpringBootTest` + `MockMvc` |
| `MemoryControllerIT` | 同上 | 端点 7 | `@SpringBootTest` + `MockMvc` |
| `ToolsControllerIT` | 同上 | 端点 8 | `@SpringBootTest` + `MockMvc` |
| `SystemControllerIT` | 同上 | 端点 9-10 | `@SpringBootTest` + `MockMvc` |
| `GlobalExceptionHandlerIT` | 同上 | 全局错误响应 | `@SpringBootTest` + `MockMvc` |
| `OpenApiContractIT` | 同上 | `/v3/api-docs.yaml` | 反射 + YAML 解析 |
| `WebServiceEndToEndIT` | 同上 | 端点 1-10 端到端 | `@SpringBootTest` + `TestRestTemplate` |
| `WebPerformanceBenchmarkIT` | `oryxos-web/src/test/java/io/oryxos/web/perf/WebPerformanceBenchmarkIT.java` | health latency | `WebPerformanceBenchmarkIT` |

**全量运行**：
```bash
mvn -pl oryxos-web verify
```

预期：`Tests run: XX, Failures: 0, Errors: 0, Skipped: 0`

---

## 故障排查

| 现象 | 可能原因 | 排查命令 |
|------|---------|---------|
| `agent_not_found` | Profile 未加载 | `curl /api/v1/profiles` 看列表 |
| `internal_error` | Agent 处理抛异常 | 看 Logback 日志（`tail -f .oryxos/logs/oryxos.log`） |
| `agent_timeout` | LLM 调用超时 | 看 `llm_calls` 表 `duration_ms`；检查 API key |
| `service_unavailable` (health) | DB 不可达 | 看 `/actuator/health` 详情 |
| 启动失败 | Bean wiring 失败 | 看 Spring Boot 启动日志 ERROR 行 |

---

## 引用

- [spec.md](spec.md) — 4 User Story + 15 FR + 8 SC + 3 NEEDS_CLARIFICATION
- [data-model.md](data-model.md) — 10 个 DTO 实体
- [contracts/web-api.md](contracts/web-api.md) — 10 端点字节级契约
- [research.md](research.md) — R-001 Spring MVC + R-007 错误响应 + R-009 性能基线
- [CLAUDE.md §11](../../CLAUDE.md) — Demo 一/二/三/四/五
- [CLAUDE.md §15](../../CLAUDE.md) — REST 10 端点
- [008-agent-scheduler/quickstart.md](../008-agent-scheduler/quickstart.md) — AgentScheduler 端到端基线
- [005-tool-system/quickstart.md](../005-tool-system/quickstart.md) — Tool 体系基线