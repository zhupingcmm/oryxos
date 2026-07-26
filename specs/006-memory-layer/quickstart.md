# Quickstart：Memory 层（让 Agent 记得住事的可插拔记忆层）

**目的**：用 5 个可运行的验证场景证明 Memory 层端到端工作
**分支**：`006-memory-layer` | **日期**：2026-07-26 | **Spec**：[spec.md](./spec.md) | **Plan**：[plan.md](./plan.md)

> **运行方式**：本 quickstart 假设 OryxOS 已按 [005-tool-system quickstart](../005-tool-system/quickstart.md) 编译并启动。本 spec 新增的 `save_memory` / `recall_memory` Tool 与 `MemoryService` 三层门面在 006 任务阶段集成。

---

## 前置条件

- JDK 21 + Maven 3.9+
- OryxOS 仓库根目录：`d:\code\java\oryxos\`（或当前工作目录）
- V4 DDL 已应用（`agent_memories` 表存在）
- 5 个验证场景假设 OryxOS 启动后 `oryxos chat` / `POST /api/v1/agents/{name}/invoke` 可用

---

## 场景 1：跨会话记住用户偏好（US-1 MVP）

**对应 spec 验收场景**：US-1 场景 1（[spec.md §用户故事 1](./spec.md)）
**对应 [SC-001](./spec.md)**：「每日科技日报」Demo 端到端跑通

### 步骤

```bash
# 1. 启动 OryxOS（默认 MarkdownMemoryStore）
./scripts/oryxos.sh serve

# 2. Session 1：通过 CLI 与"每日科技日报" Agent 对话，保存用户偏好
./scripts/oryxos.sh chat --agent daily-tech-digest
# > 帮我保存一个偏好：用户偏好 PR 标签 = bug+enhancement，只看 zhupingcmm 组织
# [LLM 调用 save_memory(scope=core, content="用户偏好 PR 标签 = bug+enhancement，只看 zhupingcmm 组织", tags=["preference", "github"])]
# ToolResult.success=true; 已写入 1 条记录
# 退出 CLI

# 3. 检查 Markdown 文件确实含 core 段记录
cat .oryxos/memory/MEMORY.md
# 期望看到：
#   ## Core
#   - [2026-07-26T10:00:00Z] [550e8400-...] 用户偏好 PR 标签 = bug+enhancement，只看 zhupingcmm 组织 #tags=preference,github

# 4. Session 2：重启 OryxOS（跨进程），启动新对话，让 Agent 调 recall_memory
./scripts/oryxos.sh serve  # 重启
./scripts/oryxos.sh chat --agent daily-tech-digest
# > 帮我汇总今天的 GitHub PR
# [LLM 调用 recall_memory(query="PR 标签 偏好", topK=5, scopeFilter=core)]
# ToolResult 命中 1 条记录：用户偏好 PR 标签 = bug+enhancement，只看 zhupingcmm 组织
# [LLM 不再追问用户，直接按偏好汇总 PR]
```

### 预期结果

- ✅ Session 2 的 Agent **不**追问用户偏好（因为 recall_memory 已命中 Session 1 保存的偏好）
- ✅ Markdown 文件 `## Core` 段含 Session 1 保存的记录
- ✅ `tool_invocations` 表含 2 行：`save_memory`（Session 1）+ `recall_memory`（Session 2）

### 失败判定

- ❌ Session 2 的 Agent 仍追问用户偏好 → recall_memory 未命中 → 违反 SC-002
- ❌ Markdown 文件无 core 段记录 → save_memory 未落库 → 违反 FR-005

---

## 场景 2：默认 Markdown 后端 + 文件可见性（US-2）

**对应 spec 验收场景**：US-2 场景 1-4
**对应 [SC-005/FR-004](./spec.md)**：Markdown 后端文件结构契约

### 步骤

```bash
# 1. 确认 Profile 未配 memo.backend（默认 markdown）
cat .oryxos/agents/daily-tech-digest/profile.yaml | grep memo || echo "no memo config (default markdown)"

# 2. 通过 CLI 保存两条记录（一条 core + 一条 archive）
./scripts/oryxos.sh chat --agent daily-tech-digest
# > save_memory(scope=core, content="用户偏好 timezone = Asia/Shanghai")
# > save_memory(scope=archive, content="2026-07-26 fetched GitHub PR #1234")

# 3. 检查 Markdown 文件结构
cat .oryxos/memory/MEMORY.md
# 期望看到：
#   # MEMORY
#
#   ## Core
#   - [2026-07-26T...] [uuid-1] 用户偏好 timezone = Asia/Shanghai
#
#   ## Archive
#   - [2026-07-26T...] [uuid-2] 2026-07-26 fetched GitHub PR #1234

# 4. 删除 ## Core 段（模拟外部修改），再 save 一条 core
sed -i '/## Core/,/## Archive/d' .oryxos/memory/MEMORY.md
./scripts/oryxos.sh chat --agent daily-tech-digest
# > save_memory(scope=core, content="test lenient recovery")

# 5. 再次检查文件
cat .oryxos/memory/MEMORY.md
# 期望：## Core 段被重建（spec 边界情况 1 / FR-004 lenient recovery）
```

### 预期结果

- ✅ 文件含 `# MEMORY` + `## Core` + `## Archive` 三段
- ✅ 删除 `## Core` 段后 save 一条 → 文件重建 `## Core` 段
- ✅ `## Core` 段内容不被任何 save 操作破坏（C-MD-01 追加方式）

### 失败判定

- ❌ 文件只有 `## Archive` 段没有 `## Core` 段 → 违反 FR-004
- ❌ 删除 `## Core` 段后 save 没重建 → 违反边界情况 1

---

## 场景 3：可插拔后端切换 — Markdown → SQLite（US-3）

**对应 spec 验收场景**：US-3 场景 1-2
**对应 [SC-004/FR-014](./spec.md)**：后端切换 0 业务中断 + SQLite 后端契约

### 步骤

```bash
# 1. 跑场景 1 后，MEMORY.md 已含若干 core + archive 记录
cat .oryxos/memory/MEMORY.md
# 假设 8 条 core + 14 条 archive

# 2. 运行迁移脚本
./scripts/migrate-markdown-to-sqlite.sh
# 期望输出：
#   === Markdown → SQLite 迁移报告 ===
#   源文件：.oryxos/memory/MEMORY.md
#   迁移统计：Core 段 8 条, Archive 段 14 条, 总计 22 条
#   写入统计：INSERT 成功 22 条
#   校验：✓ 行数匹配

# 3. 验证 SQLite 数据库
sqlite3 .oryxos/oryxos.db "SELECT scope, COUNT(*) FROM agent_memories GROUP BY scope;"
# 期望：core|8, archive|14

# 4. 修改 Profile 把 backend 切到 sqlite
sed -i 's/^# memo:/memo:/; s/^#   backend: sqlite/  backend: sqlite/' .oryxos/agents/daily-tech-digest/profile.yaml

# 5. 重启 OryxOS
./scripts/oryxos.sh serve

# 6. 验证 recall 命中迁移后的数据
./scripts/oryxos.sh chat --agent daily-tech-digest
# > recall_memory(query="timezone 偏好", scopeFilter=core)
# 期望：命中 1 条（从 agent_memories 表查到）
```

### 预期结果

- ✅ 迁移脚本输出行数匹配
- ✅ SQLite `agent_memories` 表行数 = Markdown 文件行数
- ✅ 切换 backend 后 recall_memory 仍命中迁移后的数据（SC-004）
- ✅ Markdown 文件**未删除**（保留供业务方验证后手动清理）

### 失败判定

- ❌ 迁移脚本报告校验失败（行数不匹配）→ 违反 C-MG-02
- ❌ 切换 backend 后 recall_memory 找不到迁移的数据 → 违反 SC-004

---

## 场景 4：Scope 显式隔离 — core 永不被截断（US-4）

**对应 spec 验收场景**：US-4 场景 1-4
**对应 [SC-003/FR-009/FR-010](./spec.md)**：core 永不被截断 + archive 容量上限裁剪

### 步骤

```bash
# 1. 切到 SqliteMemoryStore（沿用场景 3 的配置）
# 配置 application.yaml: oryxos.memory.archive.max-entries: 1000

# 2. 通过循环脚本批量写 1500 条 core + 1500 条 archive
./scripts/write-1500-records.sh core
./scripts/write-1500-records.sh archive

# 3. 检查 SQLite 数据库
sqlite3 .oryxos/oryxos.db "SELECT scope, COUNT(*) FROM agent_memories GROUP BY scope;"
# 期望：
#   core|1500    ← core 不被截断（违反则违反 SC-003）
#   archive|1000 ← archive 被 lazy trim 到 1000（spec FR-010）

# 4. 验证 recall 命中
./scripts/oryxos.sh chat --agent daily-tech-digest
# > recall_memory(query="test", scopeFilter=core)
# 期望：命中 1500 条（core 全部保留）

# > recall_memory(query="test", scopeFilter=archive)
# 期望：命中 1000 条（archive 被裁剪到 1000）

# 5. 尝试 clear(core) — 期望被拒绝
./scripts/oryxos.sh chat --agent daily-tech-digest
# > clear(core) via custom test command
# 期望：抛 IllegalStateException "core scope cannot be cleared"
```

### 预期结果

- ✅ `core` scope 行数 = 1500（SC-003 永不被截断）
- ✅ `archive` scope 行数 = 1000（FR-010 lazy trim）
- ✅ `clear(core)` 被拒（C-LT-05）

### 失败判定

- ❌ `core` 行数 < 1500 → 违反 SC-003（P0 灾难）
- ❌ `archive` 行数 ≠ 1000 → 违反 FR-010
- ❌ `clear(core)` 成功 → 违反 C-LT-05

---

## 场景 5：Memory Tool 接入 ReAct 循环（US-5）

**对应 spec 验收场景**：US-5 场景 1-4
**对应 [SC-005/FR-011/FR-012](./spec.md)**：Memory Tool 审计 + 异常兜底

### 步骤

```bash
# 1. Profile 配 tools: [save_memory, recall_memory]
cat .oryxos/agents/daily-tech-digest/profile.yaml | grep -A3 "tools:"

# 2. 跑 Agent — 让 LLM 在 ReAct 循环里 save + recall
./scripts/oryxos.sh chat --agent daily-tech-digest
# > 帮我查一下"PR 标签 偏好"，如果没有就保存："用户偏好 PR 标签 = bug+enhancement"
# [LLM 在 ReAct 循环中：
#   第 1 轮：调 recall_memory(query="PR 标签 偏好") → 命中/未命中
#   第 2 轮：若未命中，调 save_memory(scope=core, ...) → 写入
# ]

# 3. 检查 tool_invocations 审计行
sqlite3 .oryxos/oryxos.db "SELECT tool_name, source, success, duration_ms FROM tool_invocations WHERE tool_name IN ('save_memory', 'recall_memory') ORDER BY created_at DESC LIMIT 5;"
# 期望：
#   save_memory|builtin|true|<duration>
#   recall_memory|builtin|true|<duration>

# 4. 模拟 IO 错误场景（让 Markdown 文件设为只读）
chmod -w .oryxos/memory/MEMORY.md
./scripts/oryxos.sh chat --agent daily-tech-digest
# > save_memory(scope=core, content="test io error")
# [Tool 层捕获 → ToolResult.success=false + errorMessage="memory save failed: ..."]
# [tool_invocations 写入 success=false 行]
chmod +w .oryxos/memory/MEMORY.md   # 恢复

# 5. 验证 errorMessage 不含 stack trace
sqlite3 .oryxos/oryxos.db "SELECT error_message FROM tool_invocations WHERE tool_name='save_memory' AND success=false ORDER BY created_at DESC LIMIT 1;"
# 期望：error_message 不含 "at io.oryxos." 或 "Exception:"
```

### 预期结果

- ✅ `tool_invocations` 含 save_memory / recall_memory 两行，source='builtin'，success=true
- ✅ IO 错误时 ToolResult.success=false，errorMessage 不含 stack trace
- ✅ `tool_invocations` 写入 success=false 审计行（day-one audit，宪法 §VI）

### 失败判定

- ❌ `tool_invocations` 缺 save_memory / recall_memory 审计行 → 违反 FR-012
- ❌ IO 错误时 errorMessage 含 stack trace → 违反 NFR-004
- ❌ LLM 调用导致 ReAct 循环崩 → 违反 FR-013

---

## 端到端冒烟脚本

5 个场景可串成一个冒烟脚本 `scripts/memory-smoke.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

PASS=0
FAIL=0

run_scenario() {
    local name=$1
    local cmd=$2
    echo "=== $name ==="
    if eval "$cmd"; then
        echo "✓ PASS"
        PASS=$((PASS+1))
    else
        echo "✗ FAIL"
        FAIL=$((FAIL+1))
    fi
}

# 场景 1：跨会话记忆
run_scenario "scenario-1-cross-session" "
    ./scripts/test-cross-session-memory.sh
"

# 场景 2：Markdown 默认后端
run_scenario "scenario-2-markdown-default" "
    ./scripts/test-markdown-backend.sh
"

# 场景 3：后端切换
run_scenario "scenario-3-backend-switch" "
    ./scripts/test-backend-switch.sh
"

# 场景 4：Scope 隔离
run_scenario "scenario-4-scope-isolation" "
    ./scripts/test-scope-isolation.sh
"

# 场景 5：ReAct 接入
run_scenario "scenario-5-react-integration" "
    ./scripts/test-react-integration.sh
"

echo ""
echo "=== Summary ==="
echo "PASS: $PASS / 5"
echo "FAIL: $FAIL / 5"

if [[ $FAIL -eq 0 ]]; then
    echo "✓ All scenarios passed"
    exit 0
else
    echo "✗ Some scenarios failed"
    exit 1
fi
```

---

## 验收清单（与 spec SC 对应）

| SC | 验证手段 | 期望结果 |
|----|---------|---------|
| SC-001 | 场景 1 | 跨 Session 偏好召回 100% |
| SC-002 | 场景 1 | N=100 次 save 跨 Session 100% 召回（自动化跑） |
| SC-003 | 场景 4 | core 写 1500 条 → recall 命中 1500 条 |
| SC-004 | 场景 3 | 切换 backend 0 业务中断 |
| SC-005 | 场景 5 | tool_invocations 100% 覆盖 save/recall_memory |
| SC-006 | 场景 5 | errorMessage 不含 stack trace |
| SC-007 | `mvn verify` | 全绿 |
| SC-008 | 性能测试 | P95 ≤ 200ms（NFR-001） |
| SC-009 | 场景 5 IO 错误 | Mem0/Markdown 不可达 → save 不阻塞 Agent |

---

## 与既有 quickstart 的关系

| 既有 quickstart | 关系 |
|----------------|------|
| [005-tool-system/quickstart.md](../005-tool-system/quickstart.md) | 5 个场景中 US-5 复用 Tool 派发机制；Memory Tool 集成与既有 9 Tool 同源 |
| [003-cli-commands](../003-cli-commands/spec.md) | `oryxos chat` CLI 是本 quickstart 的入口 |
| 三个 Demo（[CLAUDE.md §11](../CLAUDE.md)） | 场景 1 = 每日科技日报 Demo 端到端 |

---

## 备注

- **不引入新 Maven 模块**（宪法 §I）
- **不引入新第三方依赖**（Mem0 走 HTTP + Jackson）
- **冒烟脚本不依赖 Mock LLM**：场景 5 的 LLM 调用可用 mock LLM（如 `oryxos-cli` 的 stub provider）替代，避免 CI 时需要真实 LLM API key
- **Windows / Linux 兼容**：所有 bash 脚本在 Git Bash / WSL 下可运行；Java 代码用 `Files.writeString` 避免 platform-specific IO