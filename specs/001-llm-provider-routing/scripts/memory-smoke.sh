#!/usr/bin/env bash
# memory-smoke.sh —— T050 / 006-memory-layer 端到端冒烟
#
# 编排 5 场景 smoke（quickstart.md）：
#   1. CrossSession   — 跨 Session 召回（SC-002）
#   2. MarkdownBackend — 9 条 C-MD 契约（T020）
#   3. BackendSwitch  — 三后端切换零内核（SC-010 / SC-011 间接）
#   4. ScopeIsolation — 三后端 × 2 scope × 1500 条（SC-003）
#   5. ReActIntegration — Memory Tool 集成（SC-005 / SC-006）
#
# 跑通路径：mvn test (oryxos-memory) + 005-tool-system 已落地的 IT
#
# 用法：
#   ./scripts/memory-smoke.sh [--module=NAME]
#
# 退出码：所有场景 PASS → 0；任一 FAIL → 1

set -uo pipefail

ORYXOS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0
FAIL=0
MODULE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --module=*) MODULE="${1#*=}"; shift;;
        *) echo "Unknown arg: $1" >&2; exit 1;;
    esac
done

heading() { printf "\n\033[1m== %s ==\033[0m\n" "$*"; }
ok()     { printf "  \033[32m✓ %s\033[0m\n" "$*"; PASS=$((PASS+1)); }
bad()    { printf "  \033[31m✗ %s\033[0m\n" "$*"; FAIL=$((FAIL+1)); }
note()   { printf "  \033[36m%s\033[0m\n" "$*"; }

# 选择 mvn 命令
MVN_ARGS=(-q)
[[ -n "$MODULE" ]] && MVN_ARGS+=(-pl "$MODULE")

run_scenario() {
    local title="$1"; shift
    local pattern="$1"; shift
    heading "Scenario: $title"
    if mvn "${MVN_ARGS[@]}" -Dtest="$pattern" test 2>/tmp/memory-smoke.log; then
        ok "$pattern"
    else
        bad "$pattern"
        note "日志：/tmp/memory-smoke.log"
    fi
}

cd "$ORYXOS_DIR"

# ===== 场景 1: 跨 Session 召回 =====
run_scenario "1. CrossSession 跨 Session 召回" \
    "CrossSessionMemoryIT"

# ===== 场景 2: Markdown 后端 9 条 C-MD 契约 =====
run_scenario "2. MarkdownBackend 9 条 C-MD 契约" \
    "MarkdownMemoryStoreTest"

# ===== 场景 3: 三后端切换零内核 =====
run_scenario "3. BackendSwitch 三后端零内核" \
    "BackendSwitchIT,MarkdownToSqliteMigrationIT,SwitchToMem0IT,ZeroKernelChangeIT"

# ===== 场景 4: Scope 隔离 (1500 条) =====
run_scenario "4. ScopeIsolation 三后端 × 2 scope × 1500 条" \
    "ScopeContractTest,ScopeHardConstraintTest,ScopeIsolationIT"

# ===== 场景 5: Memory Tool 集成 =====
heading "Scenario: 5. ReActIntegration Memory Tool 集成 (跨模块)"
if mvn -q -pl oryxos-tool -am -Dtest='MemoryExceptionTranslationTest,MemoryToolInReActIT' test 2>/tmp/memory-smoke-5.log; then
    ok "oryxos-tool MemoryExceptionTranslationTest + MemoryToolInReActIT"
else
    bad "oryxos-tool MemoryExceptionTranslationTest + MemoryToolInReActIT"
    note "日志：/tmp/memory-smoke-5.log"
fi

# ===== 额外：审计还原 =====
run_scenario "6. AuditRestore 5 维审计链 (SC-011)" \
    "MemoryAuditRestoreIT"

# ===== 总览 =====
heading "Memory Smoke 总览"
TOTAL=$((PASS + FAIL))
printf "  通过：\033[32m%d\033[0m / 总计 %d\n" "$PASS" "$TOTAL"
if [[ "$FAIL" -gt 0 ]]; then
    printf "  \033[31m失败：%d\033[0m\n" "$FAIL"
    exit 1
fi
printf "\n  \033[1;32m✓ 006-memory-layer 端到端冒烟全 PASS\033[0m\n"
exit 0
