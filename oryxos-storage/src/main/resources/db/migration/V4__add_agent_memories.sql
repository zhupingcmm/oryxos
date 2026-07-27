-- V4__add_agent_memories.sql
-- 006-memory-layer: SqliteMemoryStore 的存储介质
-- 依据：spec.md FR-014 + data-model.md §3.2 + research.md R-01
-- 宪法"Additional Constraints"第 3 条：不依赖 hibernate.ddl-auto=update；DDL 手动维护
-- 上一版本：V3__add_tool_source.sql（005-tool-system 落地）

CREATE TABLE IF NOT EXISTS agent_memories (
    id          TEXT PRIMARY KEY,                              -- UUID v4
    scope       TEXT NOT NULL CHECK (scope IN ('core', 'archive')),
    content     TEXT NOT NULL,
    tags        TEXT NOT NULL DEFAULT '[]',                   -- JSON 数组字符串（research R-02）
    source      TEXT NOT NULL,                                 -- 写入来源（save_memory Tool / Profile name / migration）
    created_at  INTEGER NOT NULL,                              -- epoch millis
    UNIQUE(id)
);

-- 核心索引：覆盖 WHERE scope = ? AND content LIKE ? ORDER BY created_at DESC（C-SQ-06）
CREATE INDEX IF NOT EXISTS idx_agent_memories_scope_created
    ON agent_memories (scope, created_at DESC);

-- 辅助索引：tags 子串扫描（C-SQ-06）
CREATE INDEX IF NOT EXISTS idx_agent_memories_tags
    ON agent_memories (tags);

-- DOWN rollback（宪法"DDL 演进路径"硬约束：DDL 必须可回滚）
-- 仅在迁移失败 / 回滚场景下手动执行；不参与 hibernate.ddl-auto
-- DROP INDEX IF EXISTS idx_agent_memories_tags;
-- DROP INDEX IF EXISTS idx_agent_memories_scope_created;
-- DROP TABLE IF EXISTS agent_memories;