#!/usr/bin/env bash
# migrate-sqlite-to-markdown.sh —— T049 / 006-memory-layer NFR-005
#
# 把 SQLite 的 agent_memories 表导出为 .oryxos/memory/MEMORY.md。
# 幂等：重复跑 Markdown 行数不变（按 created_at 排序 + UUID 一致）。
# 保留 SQLite 表（业务方验证后手动 DROP）。
#
# 用法：
#   ./scripts/migrate-sqlite-to-markdown.sh [--dry-run] [--md PATH] [--db PATH]
#
# 参数：
#   --dry-run   只生成不写入
#   --md PATH   输出 Markdown 路径（默认 .oryxos/memory/MEMORY.md）
#   --db PATH   输入 SQLite DB（默认 .oryxos/oryxos.db）
#
# 退出码：
#   0 = 成功；1 = DB 不可读；2 = 写入失败；3 = 校验失败

set -uo pipefail

ORYXOS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
MD_PATH="$ORYXOS_DIR/.oryxos/memory/MEMORY.md"
DB_PATH="$ORYXOS_DIR/.oryxos/oryxos.db"
DRY_RUN=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run) DRY_RUN=1; shift;;
        --md) MD_PATH="$2"; shift 2;;
        --db) DB_PATH="$2"; shift 2;;
        *) echo "Unknown arg: $1" >&2; exit 1;;
    esac
done

heading() { printf "\n=== %s ===\n" "$*"; }
note()    { printf "  %s\n" "$*"; }
err()     { printf "\033[31m%s\033[0m\n" "$*" >&2; }

heading "前置检查"
if [[ ! -f "$DB_PATH" ]]; then
    err "数据库不存在：$DB_PATH"
    err "提示：先用 SqliteMemoryStore 写入至少 1 条，或传 --db 指定其他 DB"
    exit 1
fi
note "源 DB：$DB_PATH"
if ! command -v sqlite3 >/dev/null 2>&1; then
    err "未找到 sqlite3 命令行工具"
    exit 1
fi

# 校验表存在
table_count=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='agent_memories';" 2>/dev/null || echo "0")
if [[ "$table_count" != "1" ]]; then
    err "agent_memories 表不存在：$DB_PATH"
    exit 1
fi

# 统计行数
core_count=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM agent_memories WHERE scope='core';" 2>/dev/null || echo "0")
archive_count=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM agent_memories WHERE scope='archive';" 2>/dev/null || echo "0")
total=$((core_count + archive_count))
note "Core 段：$core_count 条"
note "Archive 段：$archive_count 条"
note "总计：$total 条"

if [[ $total -eq 0 ]]; then
    err "agent_memories 表为空，无数据可导出"
    exit 1
fi

# 构造 Markdown
out=$(mktemp)
trap "rm -f $out" EXIT
{
    echo "# MEMORY"
    echo ""
    echo "## Core"
    echo ""
    sqlite3 -separator '|' "$DB_PATH" \
        "SELECT id, created_at, content, tags FROM agent_memories WHERE scope='core' ORDER BY created_at ASC;" 2>/dev/null \
        | while IFS='|' read -r uuid created_at content tags; do
            # ISO 8601 from epoch millis
            iso=$(date -u -d "@$((created_at / 1000))" +"%Y-%m-%dT%H:%M:%S.%3NZ" 2>/dev/null \
                  || date -u -r "$((created_at / 1000))" +"%Y-%m-%dT%H:%M:%S.%3NZ" 2>/dev/null \
                  || echo "1970-01-01T00:00:00.000Z")
            # 还原 tags JSON 数组 → t1,t2
            tag_str=""
            if [[ "$tags" != "[]" && -n "$tags" ]]; then
                tag_str=$(echo "$tags" | sed 's/\["//;s"\]"//;s"","",",g')
            fi
            if [[ -n "$tag_str" ]]; then
                echo "- [$iso] [$uuid] $content [#tags=$tag_str]"
            else
                echo "- [$iso] [$uuid] $content"
            fi
        done
    echo "## Archive"
    echo ""
    sqlite3 -separator '|' "$DB_PATH" \
        "SELECT id, created_at, content, tags FROM agent_memories WHERE scope='archive' ORDER BY created_at ASC;" 2>/dev/null \
        | while IFS='|' read -r uuid created_at content tags; do
            iso=$(date -u -d "@$((created_at / 1000))" +"%Y-%m-%dT%H:%M:%S.%3NZ" 2>/dev/null \
                  || date -u -r "$((created_at / 1000))" +"%Y-%m-%dT%H:%M:%S.%3NZ" 2>/dev/null \
                  || echo "1970-01-01T00:00:00.000Z")
            tag_str=""
            if [[ "$tags" != "[]" && -n "$tags" ]]; then
                tag_str=$(echo "$tags" | sed 's/\["//;s"\]"//;s"","",",g')
            fi
            if [[ -n "$tag_str" ]]; then
                echo "- [$iso] [$uuid] $content [#tags=$tag_str]"
            else
                echo "- [$iso] [$uuid] $content"
            fi
        done
} > "$out"

if [[ $DRY_RUN -eq 1 ]]; then
    note "DRY RUN：已生成 $(wc -l < "$out") 行到 $out（未覆盖目标）"
    head -20 "$out"
    exit 0
fi

# 写入
mkdir -p "$(dirname "$MD_PATH")"
if ! cp "$out" "$MD_PATH"; then
    err "写入失败：$MD_PATH"
    exit 2
fi
note "已写入：$MD_PATH ($(wc -l < "$MD_PATH") 行)"

# 校验：entry 行数
written=$(grep -c '^- \[' "$MD_PATH" || echo "0")
if [[ "$written" != "$total" ]]; then
    err "校验失败：DB=$total 行，文件=$written 行"
    exit 3
fi
note "✓ 行数匹配（$total = $written）"

heading "迁移完成"
note "源 DB 保留：$DB_PATH（验证后手动 DROP TABLE agent_memories）"
note "下一步："
note "  1. 启动 OryxOS（Profile.memo.backend=markdown）"
note "  2. 跑 smoke：scripts/memory-smoke.sh"
note "  3. 验证通过后，可手动清理 DB"
exit 0
