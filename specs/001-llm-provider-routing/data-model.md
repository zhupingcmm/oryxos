# 数据模型：LLM Provider 路由

**日期**：2026-07-24
**目的**：定义 LLM Provider 路由特性涉及的所有持久化与运行时数据结构
**关联**：[spec.md](./spec.md)、[research.md](./research.md)

---

## 1. `Provider` —— Provider 配置（运行时，**非持久化**）

**来源**：从 `application.yml` 的 `oryxos.providers.*` 节点加载

| 字段 | 类型 | 必填 | 约束 / 说明 |
|------|------|------|------------|
| `name` | String | ✅ | 路由键；全局唯一；匹配 `^[a-z][a-z0-9-]{0,63}$` |
| `model` | String | ✅ | 模型标识（如 `deepseek-chat`、`qwen-plus`） |
| `endpoint` | String | ❌ | 默认走 Provider 类型默认 endpoint |
| `credentialRef` | String | ✅ | 环境变量名（如 `DEEPSEEK_API_KEY`），**不是** key 本身 |
| `options` | Map<String, Object> | ❌ | Provider 私有参数（如 `temperature` 默认值、`top_p` 等） |

**生命周期**：
- 启动期由 Spring 解析 `application.yml` 加载
- 缺失 `credentialRef` 指向的环境变量 → 启动失败（详见 research.md R-04）
- 运行时**不可变**；修改需要重启或配置热重载（重载后由 Spring 重建 Bean）

**不存数据库**，纯运行时对象。

---

## 2. `LlmCallRecord` —— LLM 调用审计记录（**持久化到 `llm_calls` 表**）

**表名**：`llm_calls`（来自宪法 §13 "5 张表" 之一）

| 字段 | Java 类型 | SQL 类型 | 必填 | 索引 | 说明 |
|------|----------|---------|------|------|------|
| `id` | UUID | `TEXT` (UUID 字符串) / `BLOB` | ✅ | PK | 主键 |
| `sessionId` | UUID | `TEXT` (可空) | ❌ | idx_session | 关联到 `sessions.id`；可空（CLI 直调无 session） |
| `profileName` | String | `TEXT` | ✅ | idx_profile | 调用来源的 Profile 名；系统调用则为空串 |
| `provider` | String | `TEXT` | ✅ | idx_provider_ts | Provider 路由键（如 `deepseek`） |
| `model` | String | `TEXT` | ✅ | — | 实际使用的模型（如 `deepseek-chat`） |
| `success` | boolean | `BOOLEAN` / `INTEGER` (0/1) | ✅ | idx_success_ts | 调用是否成功 |
| `errorMessage` | String | `TEXT` | ❌ | — | 失败原因；`success=true` 时必须为 null |
| `promptTokens` | Integer | `INTEGER` | ❌ | — | 成功时由 LLM 响应给出 |
| `completionTokens` | Integer | `INTEGER` | ❌ | — | 成功时由 LLM 响应给出 |
| `durationMs` | long | `INTEGER` | ✅ | — | 从发起调用到拿到响应（含网络 + 解析） |
| `timestamp` | Instant | `TEXT` (ISO-8601) | ✅ | idx_provider_ts, idx_success_ts | 调用发起时间，UTC |

**索引策略**：

```sql
CREATE INDEX idx_session       ON llm_calls (session_id);
CREATE INDEX idx_profile       ON llm_calls (profile_name);
CREATE INDEX idx_provider_ts   ON llm_calls (provider, timestamp);
CREATE INDEX idx_success_ts    ON llm_calls (success, timestamp);
```

| 索引 | 服务的查询 |
|------|----------|
| `idx_session` | "这个 Session 调过哪些 LLM" |
| `idx_profile` | "这个 Profile 跑过多少次 / 调过哪些 Provider" |
| `idx_provider_ts` | "这个 Provider 今天的调用量 / 错误率"（按时间窗口） |
| `idx_success_ts` | "今天的失败调用列表"（审计员视角） |

**写入约束**：
- 同一 `id` 不可重复（PK 约束）
- `success=true` 时 `errorMessage` 必须为 null（应用层校验，DB 层用 CHECK 约束做兜底：`CHECK (success = 0 OR error_message IS NULL)`）
- `durationMs >= 0`（应用层 + DB CHECK 约束）
- `timestamp` 不晚于当前时间 +5 分钟（防时钟漂移）

---

## 3. 关联关系

```
┌─────────────────┐         ┌──────────────────┐
│   sessions      │         │   llm_calls      │
│   (宪法 §13)    │ 1──→ N  │  (本 spec 引入)  │
│                 │         │                  │
│ id (PK)         │         │ id (PK)          │
│ profile_name    │         │ session_id (FK?) │
│ created_at      │         │ profile_name     │
│ updated_at      │         │ provider         │
│ status          │         │ model            │
└─────────────────┘         │ success          │
                            │ error_message    │
                            │ prompt_tokens    │
                            │ completion_tokens│
                            │ duration_ms      │
                            │ timestamp        │
                            └──────────────────┘
```

**外键策略**：
- `llm_calls.session_id` 是**逻辑外键**（应用层保证存在性），**不**加 SQL 外键约束
- 原因：宪法 §13 提到 SQLite 的 `ALTER TABLE` 有限；外键约束会让 `sessions` 表的结构变更影响 `llm_calls`
- 业务约束：删除 `sessions` 行时先 `SELECT FROM llm_calls WHERE session_id = ?` 决定如何处理（核心阶段不实现 Session 删除路径，所以这条只在未来扩展阶段才需要）

---

## 4. 验证规则（应用层）

由 `oryxos-provider` 内的 `LlmCallRecordBuilder` 强制执行：

```text
1. success=true  ⇒ errorMessage 必须为 null
2. success=false ⇒ errorMessage 必须非空（即使是 "audit write failed: ..." 也要有）
3. promptTokens 存在 ⇒ completionTokens 也必须存在（成功时成对出现）
4. durationMs ≥ 0
5. timestamp 在 [now - 5min, now + 5min] 窗口内
6. provider 必须是已配置的 Provider name（写库前在 ProviderRegistry 里查一次）
```

不满足规则时**禁止写入**，抛 `IllegalStateException` 记入 ERROR 日志——这本身是一个**严重错误**（表示 Provider 内部逻辑 bug），需要人工介入，不应被审计表的容错路径吞掉。

---

## 5. 不在范围内（核心阶段不做）

- ❌ 向量化的 `prompt_tokens` / `completion_tokens` 聚合表
- ❌ 成本计算（`cost_usd` 字段）
- ❌ Session 删除时的级联策略
- ❌ 异步批量审计行合并
- ❌ 审计行的 TTL / 归档策略
- ❌ 实时审计推送（Kafka / Webhook）

这些都列在宪法 §II "治理层" 范围内，**核心阶段不实现**。
