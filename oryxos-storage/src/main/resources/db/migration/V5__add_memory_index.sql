-- V5: Mem0 后端本地索引表（006-memory-layer US-3 / FR-015）
--
-- 作用：Mem0MemoryStore 在远端 Mem0 服务不可达时把"待同步"条目落本地（pending=true），
--      recallByKeyword 降级路径读本地索引；与 agent_memories（V4，SQLite 后端专用）**并列**——
--      同一进程可同时存在（取决于 application.yaml 的 oryxos.memory.backend 配置）。
--
-- 设计原则（spec FR-015 + data-model.md §6）：
--   ① local_id 是本表 PK（UUID），mem0_id 是 Mem0 服务端返回 ID（可能为 NULL —— 待同步）
--   ② pending=true 标识"本地写入但 Mem0 不可达"，下次健康检查 / 启动期回填
--   ③ created_at 用 BIGINT 存毫秒（与 agent_memories 一致）
--   ④ scope CHECK 用 LOWER() 大小写不敏感（H2/SQLite 兼容 + 与 V4 风格统一）
--
-- 索引：
--   idx_memory_index_pending_created (pending, created_at DESC)
--     —— Mem0 不可达时的降级召回：扫所有 pending=false 已同步条目
--   idx_memory_index_scope_created (scope, created_at DESC)
--     —— 按 scope 过滤的 recallByScope 走索引
--   idx_memory_index_mem0_id (mem0_id)
--     —— Mem0 服务端回包后用 mem0_id 找本地条目

CREATE TABLE memory_index (
    local_id    VARCHAR(36) PRIMARY KEY,
    mem0_id     VARCHAR(64),
    scope       VARCHAR(16) NOT NULL CHECK (LOWER(scope) IN ('core', 'archive')),
    content     TEXT NOT NULL,
    tags        TEXT NOT NULL DEFAULT '[]',
    source      VARCHAR(16) NOT NULL CHECK (LOWER(source) IN ('core', 'archive')),
    pending     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  BIGINT NOT NULL
);

CREATE INDEX idx_memory_index_pending_created
    ON memory_index (pending, created_at DESC);

CREATE INDEX idx_memory_index_scope_created
    ON memory_index (scope, created_at DESC);

CREATE INDEX idx_memory_index_mem0_id
    ON memory_index (mem0_id);

-- DOWN rollback
-- DROP INDEX IF EXISTS idx_memory_index_mem0_id;
-- DROP INDEX IF EXISTS idx_memory_index_scope_created;
-- DROP INDEX IF EXISTS idx_memory_index_pending_created;
-- DROP TABLE IF EXISTS memory_index;