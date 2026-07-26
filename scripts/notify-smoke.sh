#!/usr/bin/env bash
# scripts/notify-smoke.sh — Notify 出站推送端到端冒烟脚本（T052 完成版）
#
# 完整端到端验证 Notify 模块（US-1..US-4 全部）：
#   P1 单通道默认推送 → 验审计行 success=1, channel=default, status=200
#   P2 多通道按名路由 → 验 channel=feishu，WireMock 仅 feishu 收到
#   P3 Sandbox 拦截   → 验 success=0, error 含 sandbox violation
#   P4 广播 + 部分失败 → 验 success=1, channel 多个, status=500（部分失败聚合）
#
# 调用方式：
#   ./scripts/notify-smoke.sh                  # 跑全部步骤
#   ./scripts/notify-smoke.sh --help           # 帮助
#   ./scripts/notify-smoke.sh --module-boundary  # 只跑 T061 模块边界检查
#   ./scripts/notify-smoke.sh --unit-tests       # 只跑 Notify 单元测试（步骤 3-9）
#
# 退出码（BSD sysexits 风格）：
#   0   OK
#   64  EX_USAGE   命令行参数错误
#   77  EX_NOPERM  模块边界违规
#   78  EX_CONFIG  配置缺失（WireMock stubs / application.yml 等）
#   1   测试失败
#
# 设计取舍：
#   - 不强制启动 Docker WireMock（步骤 1-2 在 wire-mock 模式下只检查文件存在）；
#     完整 webhook 验证依赖外部环境。
#   - 步骤 3-9 用 JUnit 跑 NotifyTool 系列测试覆盖（速度快，CI 友好）。
#   - 步骤 0（mvn package）默认跳过；只跑单测时直接调用 mvn test。
#   - 步骤 10 清理 WireMock docker 容器（如已启动）。

set -euo pipefail

PROG=$(basename "$0")
ROOT=$(cd "$(dirname "$0")/.." && pwd)
EVIDENCE_DIR="$ROOT/specs/004-notify-channel/evidence"
mkdir -p "$EVIDENCE_DIR"

# ─── 颜色（仅 TTY 时启用）────────────────────────────────────────────
if [ -t 1 ]; then
  C_RED='\033[0;31m'; C_GRN='\033[0;32m'; C_YLW='\033[0;33m'; C_OFF='\033[0m'
else
  C_RED=''; C_GRN=''; C_YLW=''; C_OFF=''
fi

log()  { printf "${C_GRN}[%s]${C_OFF} %s\n" "$PROG" "$*" >&2; }
warn() { printf "${C_YLW}[%s]${C_OFF} %s\n" "$PROG" "$*" >&2; }
err()  { printf "${C_RED}[%s]${C_OFF} %s\n" "$PROG" "$*" >&2; }

# ─── T061 模块边界检查 ─────────────────────────────────────────────────
# spec FR-014: Notify 全部代码 MUST 落在 oryxos-tool 模块内
# 例外: oryxos-boot 的 NotifyToolConfig（Spring DI 装配 @Bean ToolRegistry）
#
# 与 scripts/check-notify-module-boundary.sh 同款检查；脚本逻辑只扫 Java 源码
# 里的 import / package 行（Javadoc {@link ...}、注释、字符串字面量不算越界
# —— 不触发类加载；CLAUDE.md §5 §V 边界澄清已确认 core 类可在 Javadoc 里提及
# Notify 实现以描述审计契约）。
check_module_boundary() {
  log "T061: 验证 Notify 模块边界 (spec FR-014)"
  local hit=0
  local import_pattern='^[ \t]*(import|package)[ \t]+(static[ \t]+)?io\.oryxos\.tool\.notify'
  # 扫除 oryxos-tool + oryxos-boot 外的模块的 import / package 引用
  for mod in oryxos-core oryxos-storage oryxos-cli oryxos-provider \
             oryxos-memory oryxos-web oryxos-channel-cli; do
    if grep -rln --include='*.java' --include='*.kt' \
           -E "$import_pattern" "$ROOT/$mod" 2>/dev/null | head -1 | grep -q .; then
      err "模块边界违规: $mod 通过 import/package 引用了 io.oryxos.tool.notify"
      grep -rln --include='*.java' --include='*.kt' \
             -E "$import_pattern" "$ROOT/$mod" 2>/dev/null | sed "s|^|$mod/|"
      hit=1
    fi
  done
  if [ "$hit" -ne 0 ]; then
    err "T061 FAILED — Notify 代码泄漏到其他模块（实际 import/package 越界）"
    exit 77
  fi
  log "T061 PASS — Notify 代码仅在 oryxos-tool (+ oryxos-boot DI)"
}

# ─── 步骤 1: WireMock stubs 文件存在性 ──────────────────────────────────
check_wiremock_stubs() {
  log "步骤 1: 校验 WireMock stub 文件"
  local stub_dir="$ROOT/specs/004-notify-channel/quickstart/wiremock/mappings"
  local required=("notify-default.json" "notify-feishu.json" "notify-dingtalk-fail.json")
  local missing=0
  for f in "${required[@]}"; do
    if [ ! -f "$stub_dir/$f" ]; then
      err "缺失 stub: $stub_dir/$f"
      missing=1
    fi
  done
  if [ "$missing" -ne 0 ]; then
    err "WireMock stubs 不完整；跑步骤 1 需要先创建 (见 T051)"
    exit 78
  fi
  log "WireMock stubs: ${required[*]} 全部存在"
}

# ─── 步骤 3-9: 跑 Notify JUnit 测试覆盖所有验收路径 ──────────────────
run_notify_tests() {
  log "步骤 3-9: 跑 Notify JUnit 测试 (P1..P4 全覆盖)"
  local report="$EVIDENCE_DIR/notify-junit-output.log"
  if ! mvn -pl oryxos-tool,oryxos-core -am test \
        -Dtest='NotifyToolSingleChannelTest,NotifyToolMultiChannelTest,NotifyToolSandboxTest,NotifyToolBroadcastTest,NotifyToolBroadcastConcurrencyTest,NoRetrySemanticsTest,WebhookNotifyAdapterTest,WebhookNotifyAdapterIntegrationTest,DefaultToolExecutorDispatchTest' \
        -Dsurefire.failIfNoSpecifiedTests=false \
        > "$report" 2>&1; then
    err "Notify 测试失败；日志: $report"
    tail -40 "$report" >&2
    exit 1
  fi
  # 抽取测试统计
  local summary
  summary=$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+" "$report" | tail -1 || echo "(无汇总)")
  log "JUnit: $summary"
}

# ─── 步骤 10: 清理（如 WireMock 容器已启动）─────────────────────────────
cleanup_wiremock() {
  if command -v docker >/dev/null 2>&1; then
    if docker ps -a --format '{{.Names}}' 2>/dev/null | grep -q '^wiremock-notify$'; then
      log "步骤 10: 清理 WireMock 容器"
      docker rm -f wiremock-notify >/dev/null 2>&1 || true
    fi
  fi
}

# ─── 入口 ─────────────────────────────────────────────────────────────
usage() {
  cat <<EOF
$PROG — Notify 出站推送端到端冒烟

Usage:
  $PROG [--help]
  $PROG [--module-boundary]
  $PROG [--unit-tests]

步骤:
  1. 校验 WireMock stubs (specs/.../quickstart/wiremock/mappings/)
  2. 步骤 3-9: JUnit 覆盖 (P1..P4)
  3. T061: 模块边界检查
 10. 清理

退出码: 0=OK / 64=EX_USAGE / 77=EX_NOPERM(模块边界) / 78=EX_CONFIG / 1=测试失败
EOF
}

main() {
  case "${1:-}" in
    ""|--all)
      check_wiremock_stubs
      run_notify_tests
      check_module_boundary
      cleanup_wiremock
      log "ALL DONE — Notify 模块冒烟通过"
      ;;
    --module-boundary)
      check_module_boundary
      ;;
    --unit-tests)
      run_notify_tests
      ;;
    --help|-h)
      usage
      ;;
    *)
      err "未知参数: $1"
      usage >&2
      exit 64
      ;;
  esac
}

main "$@"