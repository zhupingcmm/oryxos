#!/usr/bin/env bash
# migrate-markdown-to-sqlite.sh —— T048 / 006-memory-layer NFR-005
#
# 把 .oryxos/memory/MEMORY.md 一次性导入到 SQLite 的 agent_memories 表。
# 幂等：重复跑 DB 行数不变（用 INSERT OR IGNORE + 唯一键保证）。
#
# 用法：
#   ./scripts/migrate-markdown-to-sqlite.sh [--dry-run] [--md PATH] [--db PATH]
#
# 参数：
#   --dry-run   只解析不写入
#   --md PATH   指定 Markdown 源文件（默认 .oryxos/memory/MEMORY.md）
#   --db PATH   指定 SQLite DB（默认 .oryxos/oryxos.db）
#
# 前置：
#   - V4 DDL 已应用（agent_memories 表存在）
#   - 业务方已停服
#
# 退出码：
#   0 = 成功；1 = 文件不存在/解析失败；2 = DB 写入失败；3 = 校验失败

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

# ===== 前置校验 =====
heading "前置检查"
if [[ ! -f "$MD_PATH" ]]; then
    err "Markdown 文件不存在：$MD_PATH"
    err "提示：先用 MarkdownMemoryStore 写入至少 1 条，或传 --md 指定其他路径"
    exit 1
fi
note "源文件：$MD_PATH ($(wc -c < "$MD_PATH") bytes)"

# 检查 sqlite3 命令行工具
if ! command -v sqlite3 >/dev/null 2>&1; then
    err "未找到 sqlite3 命令行工具"
    note "Windows: choco install sqlite  或  Git Bash 自带 sqlite3"
    note "Linux:   apt install sqlite3"
    exit 1
fi
note "sqlite3: $(sqlite3 -version | head -1)"

if [[ $DRY_RUN -eq 1 ]]; then
    note "DRY RUN 模式：只解析不写入"
elif [[ ! -f "$DB_PATH" ]]; then
    err "数据库不存在：$DB_PATH"
    err "提示：先跑一次 OryxOS 启动（Hibernate 会建表），或传 --db 指定已建表的 DB"
    exit 1
fi

# ===== 解析 Markdown =====
heading "解析 Markdown 文件"
core_count=0
archive_count=0
errors=0
declare -a entries=()

current_section=""
while IFS= read -r line; do
    # section header
    if [[ "$line" =~ ^##[[:space:]]+Core[[:space:]]*$ ]]; then
        current_section="core"
        continue
    fi
    if [[ "$line" =~ ^##[[:space:]]+Archive[[:space:]]*$ ]]; then
        current_section="archive"
        continue
    fi
    # entry: - [ISO8601] [uuid] content [#tags=t1,t2]
    if [[ "$line" =~ ^-[[:space:]]*\[([^\]]+)\][[:space:]]+\[([^\]]+)\][[:space:]]+(.*)$ ]]; then
        ts="${BASH_REMATCH[1]}"
        uuid="${BASH_REMATCH[2]}"
        rest="${BASH_REMATCH[3]}"
        # parse tags
        tags_json="[]"
        content="$rest"
        if [[ "$rest" =~ ^(.*)[[:space:]]+\[#tags=(.*)\][[:space:]]*$ ]]; then
            content="${BASH_REMATCH[1]}"
            tags_str="${BASH_REMATCH[2]}"
            # convert t1,t2 to ["t1","t2"]
            tags_json="["
            first=1
            IFS=',' read -ra tag_parts <<< "$tags_str"
            for t in "${tag_parts[@]}"; do
                t="${t// /}"
                if [[ $first -eq 0 ]]; then tags_json+=","; fi
                tags_json+="\"$t\""
                first=0
            done
            tags_json+="]"
        fi
        # epoch millis from ISO8601
        if command -v date >/dev/null 2>&1; then
            epoch_ms=$(date -d "$ts" +%s%3N 2>/dev/null || echo "0")
        else
            epoch_ms="0"
        fi
        if [[ -z "$current_section" ]]; then
            note "警告：section 未识别，跳过 entry uuid=$uuid"
            errors=$((errors+1))
            continue
        fi
        entries+=("$current_section|$uuid|$epoch_ms|$tags_json|$content")
        if [[ "$current_section" == "core" ]]; then
            core_count=$((core_count+1))
        else
            archive_count=$((archive_count+1))
        fi
    fi
done < "$MD_PATH"

total=$((core_count + archive_count))
note "Core 段：$core_count 条"
note "Archive 段：$archive_count 条"
note "总计：$total 条"
if [[ $errors -gt 0 ]]; then
    note "解析告警：$errors 条 entry 跳过"
fi

if [[ $DRY_RUN -eq 1 ]]; then
    note "DRY RUN：退出（不写 DB）"
    exit 0
fi

# ===== 写入 SQLite =====
heading "写入 SQLite 数据库"
note "目标：$DB_PATH"
if [[ ! -f "$DB_PATH" ]]; then
    err "DB 不存在：$DB_PATH"
    exit 1
fi

# 创建临时迁移文件（多条 INSERT）
mig_file=$(mktemp)
trap "rm -f $mig_file" EXIT
echo "BEGIN TRANSACTION;" > "$mig_file"

inserted=0
failed=0
for entry in "${entries[@]}"; do
    IFS='|' read -r scope uuid epoch_ms tags_json content <<< "$entry"
    # 转义 content 中的单引号
    content_esc="${content//\'/\'\'}"
    echo "INSERT OR IGNORE INTO agent_memories (id, scope, content, tags, source, created_at) VALUES ('$uuid', '$scope', '$content_esc', '$tags_json', '$scope', $epoch_ms);" >> "$mig_file"
done
echo "COMMIT;" >> "$mig_file"

# 执行（不输出 raw）
if sqlite3 "$DB_PATH" < "$mig_file" 2>/tmp/mig-err.log; then
    inserted=${#entries[@]}
    note "INSERT 成功：$inserted 条"
else
    err "DB 写入失败：$(cat /tmp/mig-err.log)"
    failed=${#entries[@]}
    note "INSERT 失败：$failed 条"
    exit 2
fi

# ===== 校验 =====
heading "校验"
db_count=$(sqlite3 "$DB_PATH" "SELECT COUNT(*) FROM agent_memories;" 2>/dev/null || echo "?")
note "agent_memories 表当前行数：$db_count"
if [[ "$db_count" == "0" ]]; then
    err "校验失败：DB 表为空"
    exit 3
fi
note "✓ 写入完成"

heading "迁移完成"
note "源文件保留：$MD_PATH"
note "下一步："
note "  1. 启动 OryxOS（Profile.memo.backend=sqlite）"
note "  2. 跑 smoke：scripts/memory-smoke.sh"
note "  3. 验证通过后，可手动删除 Markdown 文件"
exit 0
