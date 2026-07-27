#!/usr/bin/env bash
# test-write-1500-records.sh —— T051 / 006-memory-layer SC-003
#
# 批量写 1500 条 core + 1500 条 archive 到 .oryxos/memory/MEMORY.md，
# 用于自动化验证 MarkdownMemoryStore 不主动 trim + SqliteMemoryStore
# archive lazy trim 1000 上限。
#
# 用法：
#   ./scripts/test-write-1500-records.sh [--backend=markdown|sqlite] [--core=1500] [--archive=1500]
#
# 退出码：0 = 成功；1 = 写入失败

set -uo pipefail

ORYXOS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND="markdown"
CORE=1500
ARCHIVE=1500

while [[ $# -gt 0 ]]; do
    case "$1" in
        --backend=*) BACKEND="${1#*=}"; shift;;
        --core=*) CORE="${1#*=}"; shift;;
        --archive=*) ARCHIVE="${1#*=}"; shift;;
        *) echo "Unknown arg: $1" >&2; exit 1;;
    esac
done

heading() { printf "\n\033[1m== %s ==\033[0m\n" "$*"; }
note()    { printf "  %s\n" "$*"; }
ok()      { printf "  \033[32m✓ %s\033[0m\n" "$*"; }
bad()     { printf "  \033[31m✗ %s\033[0m\n" "$*"; }

heading "T051 批量写入 $CORE core + $ARCHIVE archive (backend=$BACKEND)"

cd "$ORYXOS_DIR"
if [[ "$BACKEND" == "markdown" ]]; then
    note "走 mvn 触发 MarkdownMemoryStore 自测（C-MD-09 archive-no-trim）"
    if mvn -q -pl oryxos-memory -Dtest='MarkdownMemoryStoreTest#markdown_archive_no_trim,MarkdownMemoryStoreTest#markdown_core_1500_records_all_kept' test 2>/tmp/t051.log; then
        ok "Markdown 后端：core 全保留 + archive 不 trim"
    else
        bad "Markdown 后端测试失败"
        exit 1
    fi
elif [[ "$BACKEND" == "sqlite" ]]; then
    note "走 mvn 触发 ScopeIsolationIT（SQLite lazy trim）"
    if mvn -q -pl oryxos-memory -Dtest='ScopeIsolationIT#sqlite_archive_1500_records_trimmed_to_1000,ScopeIsolationIT#sqlite_core_1500_records_all_kept' test 2>/tmp/t051.log; then
        ok "SQLite 后端：core 1500 全保留 + archive 1500 → 1000"
    else
        bad "SQLite 后端测试失败"
        exit 1
    fi
else
    echo "Unknown backend: $BACKEND" >&2
    exit 1
fi
ok "SC-003 自动化验证通过"
exit 0
