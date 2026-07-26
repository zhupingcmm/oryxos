#!/usr/bin/env bash
# tool-smoke.sh —— T076 / R-12 端到端冒烟脚本
#
# 在 Git Bash / WSL / Linux 上运行（spec R-12 偏好 Unix shell）；
# Windows 原生命令行请改用 tool-smoke.bat（见下）。
#
# 跑通 6 个场景（对应 quickstart.md 主路径）：
#   1. ToolList  —— tool list 含 10 行（9 builtin + 1 java_bean echo）
#   2. File      —— file_write → file_read 走通
#   3. Shell     —— shell echo 走通；rm 黑名单拦截
#   4. HTTP      —— http_get localhost 走通
#   5. Memory    —— save_memory → recall_memory 走通
#   6. MCP       —— mock MCP server (WireMock) handshake + tools/list
#
# 用法：
#   cd oryxos
#   bash scripts/tool-smoke.sh
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

# ---------- 场景 1: ToolList 走通（含 echo java_bean）----------
heading "Scenario 1: ToolList — oryxos tool list 含 10 行 (9 builtin + echo)"
cd "$ORYXOS_DIR"
# 通过 JUnit 测试直接验证（CLI 集成需要完整 Spring 装配，CI 已有 mvn verify 覆盖）
mvn -q -pl oryxos-tool test -Dtest=BuiltinToolsIntegrationTest#registry_has_nine_builtin_tools \
    >/tmp/tool-smoke-tool-list.log 2>&1 \
    && ok "BuiltinToolsIntegrationTest#registry_has_nine_builtin_tools PASSED" \
    || bad "BuiltinToolsIntegrationTest#registry_has_nine_builtin_tools FAILED (see /tmp/tool-smoke-tool-list.log)"

# ---------- 场景 2: File Write → Read 走通 ----------
heading "Scenario 2: File — file_write + file_read 往返"
mvn -q -pl oryxos-tool test -Dtest=BuiltinToolsIntegrationTest#file_tools_round_trip \
    >/tmp/tool-smoke-file.log 2>&1 \
    && ok "BuiltinToolsIntegrationTest#file_tools_round_trip PASSED" \
    || bad "BuiltinToolsIntegrationTest#file_tools_round_trip FAILED (see /tmp/tool-smoke-file.log)"

# ---------- 场景 3: Shell 走通 + 黑名单拦截 ----------
heading "Scenario 3: Shell — echo 走通 + rm 黑名单拦截"
mvn -q -pl oryxos-tool test -Dtest=BuiltinToolsIntegrationTest#shell_echo_and_blacklist \
    >/tmp/tool-smoke-shell.log 2>&1 \
    && ok "BuiltinToolsIntegrationTest#shell_echo_and_blacklist PASSED" \
    || bad "BuiltinToolsIntegrationTest#shell_echo_and_blacklist FAILED (see /tmp/tool-smoke-shell.log)"

# ---------- 场景 4: HTTP 走通 + IP 字面值拦截 ----------
heading "Scenario 4: HTTP — localhost GET 走通 + 127.0.0.1 IP 字面值拦截"
mvn -q -pl oryxos-tool test -Dtest=BuiltinToolsIntegrationTest#http_get_wiremock,BuiltinToolsIntegrationTest#http_get_ip_literal_rejected \
    >/tmp/tool-smoke-http.log 2>&1 \
    && ok "BuiltinToolsIntegrationTest#http_get_wiremock + http_get_ip_literal_rejected PASSED" \
    || bad "BuiltinToolsIntegrationTest#http_get_wiremock FAILED (see /tmp/tool-smoke-http.log)"

# ---------- 场景 5: Memory 走通 ----------
heading "Scenario 5: Memory — save_memory → recall_memory 关键词命中"
mvn -q -pl oryxos-tool test -Dtest=BuiltinToolsIntegrationTest#memory_round_trip \
    >/tmp/tool-smoke-memory.log 2>&1 \
    && ok "BuiltinToolsIntegrationTest#memory_round_trip PASSED" \
    || bad "BuiltinToolsIntegrationTest#memory_round_trip FAILED (see /tmp/tool-smoke-memory.log)"

# ---------- 场景 6: MCP 握手 + 工具列表 + 工具调用 ----------
heading "Scenario 6: MCP — handshake + tools/list + tools/call 全链路"
mvn -q -pl oryxos-tool test -Dtest=McpIntegrationTest#full_integration_path \
    >/tmp/tool-smoke-mcp.log 2>&1 \
    && ok "McpIntegrationTest#full_integration_path PASSED" \
    || bad "McpIntegrationTest#full_integration_path FAILED (see /tmp/tool-smoke-mcp.log)"

# ---------- 总结 ----------
heading "Summary"
printf "  PASS: %d\n  FAIL: %d\n" "$PASS" "$FAIL"
if [ "$FAIL" -eq 0 ]; then
    printf "\033[32mAll 6 scenarios passed.\033[0m\n"
    exit 0
else
    printf "\033[31m%d scenario(s) failed. See /tmp/tool-smoke-*.log for details.\033[0m\n" "$FAIL"
    exit 1
fi