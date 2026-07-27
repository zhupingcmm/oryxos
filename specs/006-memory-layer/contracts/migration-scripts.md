# 契约：数据迁移脚本（Markdown ↔ SQLite）

**目的**：定义 Markdown ↔ SQLite 双向迁移脚本契约（[spec.md NFR-005](../spec.md)），支持业务方切换后端 0 业务中断
**归属**：`scripts/` 目录
**关联契约**：[markdown-backend.md](./markdown-backend.md) | [sqlite-backend.md](./sqlite-backend.md) | [data-model.md §3](../data-model.md)

---

## 1. 双向迁移总览

| 方向 | 脚本 | 触发场景 | 0 业务中断？ |
|------|------|---------|-------------|
| Markdown → SQLite | `scripts/migrate-markdown-to-sqlite.sh` | 业务方 Profile 从 `markdown` 切到 `sqlite` | ✅ 迁移期间旧 Markdown 文件保留，迁移完成 + 业务验证后手动删除 |
| SQLite → Markdown | `scripts/migrate-sqlite-to-markdown.sh` | 业务方 Profile 从 `sqlite` 切到 `markdown` | ✅ 迁移期间 SQLite 表保留，迁移完成 + 业务验证后手动删除 |
| Markdown → Mem0 | **不提供**（research 决策） | 业务方切到 Mem0 后端 | ❌ Mem0 服务端的 embedding 由业务方配置；建议业务方重写（"fresh start"） |
| SQLite → Mem0 | **不提供** | 同上 | ❌ 同上 |
| Mem0 → 其他 | **不提供** | 同上 | ❌ Mem0 → Markdown/SQLite 需要重新 parse embedding，扩展阶段 |

> **关键决策**：Markdown ↔ SQLite 是**结构化数据 ↔ 结构化数据**的双向迁移；Mem0 是 embedding 数据，与 Markdown/SQLite 不可直接迁移。本 spec 仅提供前者的双向脚本。

---

## 2. `scripts/migrate-markdown-to-sqlite.sh`

### 2.1 接口契约

```bash
#!/usr/bin/env bash
# 用法：./migrate-markdown-to-sqlite.sh [--dry-run] [--in-place]
#
# 参数：
#   --dry-run   只打印迁移计划，不实际写入
#   --in-place  迁移后保留 Markdown 文件（默认行为）
#
# 行为：
#   1. 读 .oryxos/memory/MEMORY.md 文件
#   2. 解析 ## Core 和 ## Archive 段（每行 - [timestamp] [uuid] content #tags=t1,t2）
#   3. INSERT INTO agent_memories (id, scope, content, tags, source, created_at) VALUES (...)
#   4. 校验：行数 = INSERT 行数
#   5. 输出迁移报告
#
# 前置条件：
#   - OryxOS 未运行（或已停服）
#   - V4 DDL 已应用（agent_memories 表存在）
#   - SQLite 数据库路径已知（默认 .oryxos/oryxos.db）
#
# 退出码：
#   0 = 成功
#   1 = 文件不存在 / 解析失败
#   2 = 数据库写入失败
#   3 = 校验失败（行数不匹配）
```

### 2.2 输出格式（成功时）

```
=== Markdown → SQLite 迁移报告 ===
源文件：.oryxos/memory/MEMORY.md（12.4 KB）
目标数据库：.oryxos/oryxos.db

迁移统计：
  - Core 段： 8 条
  - Archive 段：14 条
  - 总计：22 条

写入统计：
  - INSERT 成功：22 条
  - INSERT 失败：0 条

校验：
  ✓ 行数匹配（22 = 22）

迁移完成。Markdown 文件保留在原路径 .oryxos/memory/MEMORY.md
下一步：
  1. 启动 OryxOS（Profile.memo.backend=sqlite）
  2. 运行 smoke 测试：scripts/memory-smoke.sh
  3. 验证通过后，可手动删除 Markdown 文件
```

### 2.3 实现细节

```bash
#!/usr/bin/env bash
set -euo pipefail

# 默认路径
MD_FILE="${ORYXOS_MEMORY_MD:-.oryxos/memory/MEMORY.md}"
DB_FILE="${ORYXOS_DB:-.oryxos/oryxos.db}"
DRY_RUN=false
IN_PLACE=true

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run) DRY_RUN=true; shift ;;
        --in-place) IN_PLACE=true; shift ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# 1. 检查前置条件
if [[ ! -f "$MD_FILE" ]]; then
    echo "ERROR: Markdown file not found: $MD_FILE"; exit 1
fi

if [[ ! -f "$DB_FILE" ]]; then
    echo "ERROR: SQLite DB not found: $DB_FILE (apply V4 DDL first)"; exit 1
fi

# 2. 解析 Markdown（用 Python 一行脚本，避免 Bash 字符串处理复杂度）
# 格式：- [<ISO-8601>] [<uuid>] <content> [#tags=tag1,tag2]
PARSED=$(python3 -c "
import re, sys
with open('$MD_FILE', 'r', encoding='utf-8') as f:
    lines = f.readlines()

current_scope = None
records = []
for line in lines:
    line = line.rstrip('\n')
    if line.strip() == '## Core':
        current_scope = 'core'
        continue
    if line.strip() == '## Archive':
        current_scope = 'archive'
        continue
    if not line.startswith('- ['):
        continue
    if current_scope is None:
        continue
    # 解析 - [timestamp] [uuid] content #tags=t1,t2
    m = re.match(r'^- \[([^\]]+)\] \[([a-f0-9-]{36})\] (.*?)( #tags=(.*))?$', line)
    if not m:
        continue
    ts, uuid, content, _, tags = m.groups()
    records.append((uuid, current_scope, content, ts, tags or ''))

for r in records:
    print(f'{r[0]}|{r[1]}|{r[2]}|{r[3]}|{r[4]}')
")

CORE_COUNT=$(echo "$PARSED" | grep -c '|core|' || echo 0)
ARCHIVE_COUNT=$(echo "$PARSED" | grep -c '|archive|' || echo 0)
TOTAL=$((CORE_COUNT + ARCHIVE_COUNT))

echo "=== Markdown → SQLite 迁移报告 ==="
echo "源文件：$MD_FILE"
echo "目标数据库：$DB_FILE"
echo ""
echo "迁移统计："
echo "  - Core 段：$CORE_COUNT 条"
echo "  - Archive 段：$ARCHIVE_COUNT 条"
echo "  - 总计：$TOTAL 条"
echo ""

if $DRY_RUN; then
    echo "[DRY-RUN] 跳过实际写入"
    exit 0
fi

# 3. 写入 SQLite
SUCCESS=0
FAIL=0
while IFS='|' read -r uuid scope content ts tags; do
    # ISO-8601 → epoch millis
    created_at=$(python3 -c "from datetime import datetime; print(int(datetime.fromisoformat('$ts'.replace('Z','+00:00')).timestamp() * 1000))")
    # tags JSON
    if [[ -z "$tags" ]]; then
        tags_json='[]'
    else
        tags_json=$(python3 -c "import json; print(json.dumps('$tags'.split(',')))")
    fi
    # INSERT
    if sqlite3 "$DB_FILE" "INSERT INTO agent_memories (id, scope, content, tags, source, created_at) VALUES ('$uuid', '$scope', '$content', '$tags_json', '$scope', $created_at);"; then
        SUCCESS=$((SUCCESS+1))
    else
        FAIL=$((FAIL+1))
    fi
done <<< "$PARSED"

echo "写入统计："
echo "  - INSERT 成功：$SUCCESS 条"
echo "  - INSERT 失败：$FAIL 条"
echo ""

# 4. 校验
DB_TOTAL=$(sqlite3 "$DB_FILE" "SELECT COUNT(*) FROM agent_memories;")
if [[ $DB_TOTAL -eq $TOTAL ]]; then
    echo "校验："
    echo "  ✓ 行数匹配（$DB_TOTAL = $TOTAL）"
    echo ""
    echo "迁移完成。Markdown 文件保留在原路径 $MD_FILE"
    echo "下一步："
    echo "  1. 启动 OryxOS（Profile.memo.backend=sqlite）"
    echo "  2. 运行 smoke 测试：scripts/memory-smoke.sh"
    echo "  3. 验证通过后，可手动删除 Markdown 文件"
    exit 0
else
    echo "校验失败：行数不匹配（数据库 $DB_TOTAL ≠ Markdown $TOTAL）"
    exit 3
fi
```

---

## 3. `scripts/migrate-sqlite-to-markdown.sh`

### 3.1 接口契约

```bash
#!/usr/bin/env bash
# 用法：./migrate-sqlite-to-markdown.sh [--dry-run] [--out <markdown-file>]
#
# 参数：
#   --dry-run          只打印迁移计划，不实际写入
#   --out <file>       指定 Markdown 输出路径（默认 .oryxos/memory/MEMORY.md）
#
# 行为：
#   1. 读 SQLite agent_memories 表
#   2. 按 scope 分组（core / archive），按 created_at ASC 排序
#   3. 写 Markdown 文件，含 ## Core 和 ## Archive 段
#   4. 校验：行数 = 写入行数
#
# 前置条件：
#   - OryxOS 未运行
#   - agent_memories 表存在（V4 DDL 已应用）
#
# 退出码：
#   0 = 成功
#   1 = 数据库不存在 / 查询失败
#   2 = 写文件失败
#   3 = 校验失败
```

### 3.2 输出格式（成功时）

```
=== SQLite → Markdown 迁移报告 ===
源数据库：.oryxos/oryxos.db
目标文件：.oryxos/memory/MEMORY.md

迁移统计：
  - core scope：8 条
  - archive scope：14 条
  - 总计：22 条

写入统计：
  - 行写入：22 行
  - 行失败：0 行

校验：
  ✓ 行数匹配

迁移完成。SQLite 表保留在原数据库。
下一步：
  1. 启动 OryxOS（Profile.memo.backend=markdown）
  2. 运行 smoke 测试：scripts/memory-smoke.sh
  3. 验证通过后，可手动删除 agent_memories 行（VACUUM 回收空间）
```

### 3.3 实现细节

```bash
#!/usr/bin/env bash
set -euo pipefail

DB_FILE="${ORYXOS_DB:-.oryxos/oryxos.db}"
MD_FILE="${ORYXOS_MEMORY_MD:-.oryxos/memory/MEMORY.md}"
DRY_RUN=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run) DRY_RUN=true; shift ;;
        --out) MD_FILE="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# 1. 检查前置条件
if [[ ! -f "$DB_FILE" ]]; then
    echo "ERROR: SQLite DB not found: $DB_FILE"; exit 1
fi

# 2. 查询
CORE_COUNT=$(sqlite3 "$DB_FILE" "SELECT COUNT(*) FROM agent_memories WHERE scope='core';" 2>/dev/null || echo 0)
ARCHIVE_COUNT=$(sqlite3 "$DB_FILE" "SELECT COUNT(*) FROM agent_memories WHERE scope='archive';" 2>/dev/null || echo 0)
TOTAL=$((CORE_COUNT + ARCHIVE_COUNT))

echo "=== SQLite → Markdown 迁移报告 ==="
echo "源数据库：$DB_FILE"
echo "目标文件：$MD_FILE"
echo ""
echo "迁移统计："
echo "  - core scope：$CORE_COUNT 条"
echo "  - archive scope：$ARCHIVE_COUNT 条"
echo "  - 总计：$TOTAL 条"
echo ""

if $DRY_RUN; then
    echo "[DRY-RUN] 跳过实际写入"
    exit 0
fi

# 3. 写 Markdown
{
    echo "# MEMORY"
    echo ""
    echo "## Core"
    sqlite3 "$DB_FILE" "SELECT printf('- [%s] [%s] %s', datetime(created_at/1000, 'unixepoch'), id, content) FROM agent_memories WHERE scope='core' ORDER BY created_at ASC;"
    echo ""
    echo "## Archive"
    sqlite3 "$DB_FILE" "SELECT printf('- [%s] [%s] %s', datetime(created_at/1000, 'unixepoch'), id, content) FROM agent_memories WHERE scope='archive' ORDER BY created_at ASC;"
} > "$MD_FILE.tmp"

# 4. 校验
WRITTEN=$(grep -c "^- \[" "$MD_FILE.tmp" || echo 0)
if [[ $WRITTEN -eq $TOTAL ]]; then
    mv "$MD_FILE.tmp" "$MD_FILE"
    echo "写入统计："
    echo "  - 行写入：$WRITTEN 行"
    echo "  - 行失败：0 行"
    echo ""
    echo "校验："
    echo "  ✓ 行数匹配"
    echo ""
    echo "迁移完成。SQLite 表保留在原数据库。"
    echo "下一步："
    echo "  1. 启动 OryxOS（Profile.memo.backend=markdown）"
    echo "  2. 运行 smoke 测试：scripts/memory-smoke.sh"
    echo "  3. 验证通过后，可手动删除 agent_memories 行（VACUUM 回收空间）"
    exit 0
else
    rm -f "$MD_FILE.tmp"
    echo "校验失败：行数不匹配（写入 $WRITTEN ≠ 数据库 $TOTAL）"
    exit 3
fi
```

---

## 4. 契约条款（迁移脚本）

| 编号 | 条款 | 验证手段 |
|------|------|---------|
| C-MG-01 | **幂等性**：同一脚本对同一源重复执行 MUST 不产生重复行（依赖 SQLite 主键约束 + Markdown 行去重） | 测试：跑 2 次 migrate-markdown-to-sqlite.sh → DB 总行数不变 |
| C-MG-02 | **行数校验**：写入行数 MUST 等于源行数；不一致 exit 3 | 测试：人为造 1 行解析失败 → exit 3 + 文件不动 |
| C-MG-03 | **ATOMIC_MOVE**：临时文件 → rename（spec NFR-003 同源） | 测试：迁移中断 → 旧文件不损坏 |
| C-MG-04 | **dry-run**：支持 `--dry-run` 只输出计划不写入 | 测试：--dry-run 跑后 DB 行数不变 |
| C-MG-05 | **保留源**：默认 --in-place，源文件 / 源表不动；业务方手动验证后删除 | 测试：跑完脚本后源文件仍在 |
| C-MG-06 | **exit code**：成功 0 / 文件不存在 1 / 写失败 2 / 校验失败 3 | 测试各场景 exit code |
| C-MG-07 | **UTF-8**：源/目标都用 UTF-8 编码（Windows GBK 兼容，CLAUDE.md §18 坑 4） | 测试：中文 content 完整迁移 |
| C-MG-08 | **Mem0 迁移不提供**：迁移脚本 MUST 不尝试 Markdown/SQLite ↔ Mem0 转换（research 决策） | 文档明确说明 |

---

## 5. 测试用例

| TestID | 场景 | 断言 |
|--------|------|------|
| MG-IT-01 | migrate-markdown-to-sqlite.sh：10 条 core + 5 条 archive → DB 含 15 行 | count = 15（C-MG-02） |
| MG-IT-02 | migrate-markdown-to-sqlite.sh 重复跑 2 次 → DB 仍 15 行 | count = 15（C-MG-01 幂等） |
| MG-IT-03 | migrate-markdown-to-sqlite.sh --dry-run → DB 0 行 | count = 0（C-MG-04） |
| MG-IT-04 | migrate-markdown-to-sqlite.sh 源文件不存在 → exit 1（C-MG-06） | exit code = 1 |
| MG-IT-05 | migrate-markdown-to-sqlite.sh 1 行解析失败 → exit 3 + DB 不动 | exit code = 3 |
| MG-IT-06 | migrate-sqlite-to-markdown.sh：15 行 DB → 15 行 Markdown 文件 | 文件行数 = 15（C-MG-02） |
| MG-IT-07 | migrate-sqlite-to-markdown.sh：中文 content 完整迁移（C-MG-07） | 字节级匹配 |
| MG-IT-08 | migrate-sqlite-to-markdown.sh DB 不存在 → exit 1 | exit code = 1 |
| MG-IT-09 | 端到端：markdown→sqlite→markdown 循环 → 最终 Markdown 等于初始 Markdown | diff = 空 |
| MG-IT-10 | Windows 环境（GBK locale）：UTF-8 source 迁移不乱码 | 字节级匹配 |

---

## 6. 与既有契约的关系

| 既有契约 | 关系 |
|----------|------|
| [spec.md NFR-005](../spec.md) | "Memory 后端切换 MUST 不破坏既有数据" — 双向迁移脚本实现 |
| [data-model.md §3](../data-model.md) | agent_memories 表结构 + V4 DDL |
| [markdown-backend.md §2](./markdown-backend.md) | 文件结构契约（迁移脚本读写同一格式） |
| [sqlite-backend.md §2](./sqlite-backend.md) | agent_memories 表 DDL（迁移脚本读写同一表） |
| [005-tool-system tool-smoke.sh](../005-tool-system/scripts/tool-smoke.sh) | smoke 模式对齐（统一格式：场景列表 + 通过/失败汇总） |