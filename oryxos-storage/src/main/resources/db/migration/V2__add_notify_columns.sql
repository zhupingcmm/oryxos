-- V2: Notify 出站审计扩展字段（US-4 Notify 子能力，data-model §8）
--
-- 新增 2 列到 tool_invocations：
--   channel           TEXT      — notify 专用；多通道广播时用 ";" 分隔（如 "default;feishu;dingtalk-fail"）
--                                 其他工具为 NULL。
--   notify_status_code INTEGER  — notify 专用；HTTP 状态码（2xx/4xx/5xx）；网络错误时 NULL；
--                                 广播时按"最差"规则取（参见 spec §NFR-002 / tasks T049）。
--
-- 演进原则（CLAUDE.md §13）：
--   - 手动 DDL 维护，不依赖 hibernate.ddl-auto=update（SQLite ALTER 能力有限）
--   - 不破坏既有不变量：success=0 → error_message IS NULL CHECK 仍成立
--   - 不创建新表，复用 tool_invocations（避免审计分裂）

ALTER TABLE tool_invocations ADD COLUMN channel TEXT;
ALTER TABLE tool_invocations ADD COLUMN notify_status_code INTEGER;

-- 索引：notify 审计按 channel 过滤（多 Profile 多通道场景下 channel 维度查询）
CREATE INDEX IF NOT EXISTS idx_tool_channel ON tool_invocations(tool_name, channel, started_at);