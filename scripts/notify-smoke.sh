#!/usr/bin/env bash
# scripts/notify-smoke.sh — Notify 模块端到端冒烟脚本（占位）
#
# 创建时间：2026-07-26
# 关联：specs/004-notify-channel/quickstart.md 步骤 0-10
#
# 当前状态：仅占位 + help 文本（T028）；Phase 7 Polish 阶段（T052）补全 10 步端到端。
#
# 调用方式：
#   ./scripts/notify-smoke.sh          # 默认：显示帮助
#   ./scripts/notify-smoke.sh --help   # 同上
#
# 退出码（BSD sysexits 风格）：
#   0  OK
#   64 EX_USAGE  命令行参数错误
#   78 EX_CONFIG 配置缺失（WireMock / .oryxos / API key 等）

set -euo pipefail

PROG=$(basename "$0")

usage() {
  cat <<EOF
$PROG — Notify 出站推送端到端冒烟

当前为占位（T028），具体步骤在 Phase 7 Polish 阶段（T052）落地。

完整验证路径：参见 specs/004-notify-channel/quickstart.md

Usage:
  $PROG [--help]

Steps (planned):
  0. 编译 & 启动前置（mvn package -DskipTests）
  1. 启动 WireMock（docker run，3 个 stub）
  2. 配置工作区（.oryxos/application.yaml + agents/notify-demo/AGENT.md）
  3. P1 单通道默认 → 验审计行 success=1, channel=default, status=200
  4. P2 多通道路由 → 验 channel=feishu，WireMock 仅 feishu 收到
  5. P3 Sandbox 拦截 evil URL → 验 success=0, error 含 sandbox
  6. P4 广播部分失败 → 验 success=1, channel 多个, status=500
  7. 失败通知不中断 ReAct → 验会话完成无 stack trace
  8. URL token 脱敏 → 验审计数据不含明文 key=
  9. 性能 NFR（10 通道 ≤ 6s）
 10. 清理（docker stop, rm tmp profiles）

EOF
}

main() {
  case "${1:-}" in
    ""|--help|-h)
      usage
      exit 0
      ;;
    *)
      echo "$PROG: unknown argument: $1" >&2
      usage >&2
      exit 64
      ;;
  esac
}

main "$@"