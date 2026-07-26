#!/usr/bin/env bash
# memory-audit-restore-test.sh —— T036b / SC-011 端到端审计还原脚本
#
# 在 Git Bash / WSL / Linux 上运行。
# Windows 原生命令行请改用 memory-audit-restore-test.bat（见下）。
#
# 跑通 3 个审计还原场景（对应 quickstart.md SC-011）：
#   1. 五维审计链 —— agent_memories JOIN tool_invocations JOIN sessions
#                    还原"哪个 Agent / 哪个 Session / 哪条记忆 / 哪个 Scope / 什么时间"
#   2. 失败调用审计 —— success=false + error_message 也写入 tool_invocations
#   3. scope 隔离 —— 审计员能分别查询 core vs archive 记忆
#
# 用法：
#   cd oryxos
#   bash scripts/memory-audit-restore-test.sh
#
# 退出码：所有场景 PASS → 0；任一 FAIL → 1。

set -uo pipefail

ORYXOS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0
FAIL=0

note() { printf "  \033[36m%s\033[0m\n" "$*"; }
ok()   { printf "  \033[32m✓ %s\033[0m\n" "$*"; PASS=$((PASS+1)); }
bad()  { printf "  \033[31m✗ %s\033[0m\n" "$*"; FAIL=$((FAIL+1)); }

heading() { printf "\n\033[1m== %s ==\033[0m\n" "$*"; }

# ---------- 场景 1: 五维审计链 ----------
heading "Scenario 1: 五维审计链 — 3 表 JOIN 还原 Agent/Session/Memory/Scope/Time"
cd "$ORYXOS_DIR"
mvn -q -pl oryxos-memory test -Dtest=MemoryAuditRestoreIT#audit_restore_reconstructs_five_dimensions \
    >/tmp/memory-audit-1.log 2>&1 \
    && ok "MemoryAuditRestoreIT#audit_restore_reconstructs_five_dimensions PASSED" \
    || bad "MemoryAuditRestoreIT#audit_restore_reconstructs_five_dimensions FAILED (see /tmp/memory-audit-1.log)"

# ---------- 场景 2: 失败调用审计 ----------
heading "Scenario 2: 失败 save_memory 调用审计 — success=false + error_message 落库"
mvn -q -pl oryxos-memory test -Dtest=MemoryAuditRestoreIT#audit_restore_includes_failed_tool_calls \
    >/tmp/memory-audit-2.log 2>&1 \
    && ok "MemoryAuditRestoreIT#audit_restore_includes_failed_tool_calls PASSED" \
    || bad "MemoryAuditRestoreIT#audit_restore_includes_failed_tool_calls FAILED (see /tmp/memory-audit-2.log)"

# ---------- 场景 3: scope 隔离还原 ----------
heading "Scenario 3: scope 隔离 — 审计员能分别查询 core vs archive"
mvn -q -pl oryxos-memory test -Dtest=MemoryAuditRestoreIT#audit_restore_filters_by_scope \
    >/tmp/memory-audit-3.log 2>&1 \
    && ok "MemoryAuditRestoreIT#audit_restore_filters_by_scope PASSED" \
    || bad "MemoryAuditRestoreIT#audit_restore_filters_by_scope FAILED (see /tmp/memory-audit-3.log)"

# ---------- 总览 ----------
heading "SC-011 审计还原总结"
TOTAL=$((PASS + FAIL))
printf "  通过：\033[32m%d\033[0m / 总计 %d\n" "$PASS" "$TOTAL"
if [[ "$FAIL" -gt 0 ]]; then
    printf "  \033[31m失败：%d\033[0m\n" "$FAIL"
    printf "  查看日志：\n"
    [[ -f /tmp/memory-audit-1.log ]] && printf "    /tmp/memory-audit-1.log\n"
    [[ -f /tmp/memory-audit-2.log ]] && printf "    /tmp/memory-audit-2.log\n"
    [[ -f /tmp/memory-audit-3.log ]] && printf "    /tmp/memory-audit-3.log\n"
    exit 1
fi

printf "\n  \033[1;32m✓ SC-011 合规审计还原验证通过\033[0m\n"
printf "  企业合规审计员能从 agent_memories + tool_invocations + sessions\n"
printf "  3 表 JOIN 完整还原长期记忆历史（5 个审计维度）。\n"
exit 0