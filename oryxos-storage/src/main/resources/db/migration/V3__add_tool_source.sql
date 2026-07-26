-- V3: Tool 来源字段（US-4 Tool 系统完整视角，data-model §7.2）
--
-- 新增 1 列到 tool_invocations：
--   source             TEXT      NOT NULL DEFAULT 'builtin'
--                                 — 区分 builtin / mcp / java_bean 三类 Tool 来源
--                                 — 详见 spec FR-005 / research.md R-06 / R-09
--
-- 演进原则（CLAUDE.md §13）：
--   - 手动 DDL 维护，不依赖 hibernate.ddl-auto=update（SQLite ALTER 能力有限）
--   - 不破坏既有不变量：success=0 → error_message IS NULL CHECK 仍成立
--   - 不创建新表，复用 tool_invocations（避免审计分裂）
--   - 历史行通过 DEFAULT 'builtin' 自动填充（V3 之前所有 Tool 都是 builtin 概念；
--     引入 mcp / java_bean 概念后才开始有真正的 source 区分，not OK to backtrack history）

ALTER TABLE tool_invocations ADD COLUMN source TEXT NOT NULL DEFAULT 'builtin';

-- 索引：审计员按 source 维度过滤（特别是 mcp / java_bean 调用的可观测性）
CREATE INDEX IF NOT EXISTS idx_tool_source ON tool_invocations(tool_name, source, started_at);

-- 回滚（spec data-model §7.4 风险缓解）：
-- DROP INDEX IF EXISTS idx_tool_source;
-- ALTER TABLE tool_invocations DROP COLUMN source;

