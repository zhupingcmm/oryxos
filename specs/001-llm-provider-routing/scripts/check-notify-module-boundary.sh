#!/usr/bin/env bash
# scripts/check-notify-module-boundary.sh — Notify 模块边界单测（T061 / FR-014）
#
# spec FR-014: Notify 模块的全部代码 MUST 落在 oryxos-tool 模块内。
#
# 该脚本独立于 scripts/notify-smoke.sh（虽然 notify-smoke.sh 内部嵌入了同款检查），
# 便于：
#   - CI 直接调用单测 / 快速验证（无需启动 Spring / 跑 JUnit）
#   - PR review 时单独 review FR-014 约束
#   - 与 noti­fy-smoke 的模块边界检查正交耦合
#
# 用法：
#   ./scripts/check-notify-module-boundary.sh
#
# 退出码：
#   0   OK —— Notify 代码仅在 oryxos-tool + oryxos-boot (DI 装配)
#   77  EX_NOPERM —— 模块边界违规（非白名单模块出现 io.oryxos.tool.notify 引用）
#   78  EX_CONFIG —— 检查目录缺失
#
# 例外（allowed_modules）：
#   - oryxos-tool  —— FR-014 主体归属
#   - oryxos-boot  —— Spring DI 装配 NotifyToolConfig 需要 import @Bean 依赖
#
# 例外（allowed_subpackages，若实现里刻意拆分 API 与 impl）：
#   - 当前实现 io.oryxos.tool.notify.* 全部在同一模块内，未拆子包；
#     若未来拆出 API（如 io.oryxos.tool.notify.api）放到 oryxos-core，
#     可在本数组追加白名单。

set -euo pipefail

PROG=$(basename "$0")
ROOT=$(cd "$(dirname "$0")/.." && pwd)

# ─── 颜色（仅 TTY 时启用）────────────────────────────────────────────
if [ -t 1 ]; then
  C_RED='\033[0;31m'; C_GRN='\033[0;32m'; C_YLW='\033[0;33m'; C_OFF='\033[0m'
else
  C_RED=''; C_GRN=''; C_YLW=''; C_OFF=''
fi

log()  { printf "${C_GRN}[%s]${C_OFF} %s\n" "$PROG" "$*" >&2; }
warn() { printf "${C_YLW}[%s]${C_OFF} %s\n" "$PROG" "$*" >&2; }
err()  { printf "${C_RED}[%s]${C_OFF} %s\n" "$PROG" "$*" >&2; }

# ─── 校验目录 ──────────────────────────────────────────────────────────
if [ ! -d "$ROOT/oryxos-tool" ]; then
  err "未找到 oryxos-tool 模块目录 (ROOT=$ROOT)"
  exit 78
fi

# ─── 扫描模块 ──────────────────────────────────────────────────────────
# spec FR-014 检查体：扫除 oryxos-tool + oryxos-boot 外的所有模块
SCAN_MODULES=(
  oryxos-core
  oryxos-storage
  oryxos-cli
  oryxos-provider
  oryxos-memory
  oryxos-web
  oryxos-channel-cli
)

# 实际类加载依赖（import / package 声明）；FR-014 只关心代码组织
# 不抓 javadoc {@link ...}、注释、纯字符串字面量 —— 这些是文档引用，
# 不触发 Java 类加载，不算越界；CLAUDE.md §5 §V 边界澄清已确认
# DefaultToolExecutor 等 core 类允许在 Javadoc 里提及 Notify 实现以描述审计契约。
IMPORT_PATTERN='^[ \t]*(import|package)[ \t]+(static[ \t]+)?io\.oryxos\.tool\.notify'

log "T061: 扫 import/package 引用 io.oryxos.tool.notify (除外: oryxos-tool + oryxos-boot)"
HIT_COUNT=0
HIT_FILES=()
for mod in "${SCAN_MODULES[@]}"; do
  if [ ! -d "$ROOT/$mod" ]; then
    warn "跳过不存在的模块: $mod"
    continue
  fi
  # 仅扫 Java 源码里的 import / package 行（Javadoc / 注释 / 字符串里的字面
  # 引用不影响类加载，刻意排除以避免误报）
  matches=$(grep -rln --include='*.java' --include='*.kt' \
                 -E "$IMPORT_PATTERN" "$ROOT/$mod" 2>/dev/null || true)
  if [ -n "$matches" ]; then
    err "模块边界违规: $mod 通过 import/package 引用了 io.oryxos.tool.notify"
    echo "$matches" | sed "s|^|  |"
    HIT_COUNT=$((HIT_COUNT + $(echo "$matches" | wc -l)))
    HIT_FILES+=("$matches")
  fi
done

# 备注：脚本不扫 maven module pom.xml（依赖通过 oryxos-tool 间接传递，
# 不会被 Spring DI 重新装配）；也不扫 *.properties / *.yml / *.yaml
# （配置层可以引用工具名 "notify"，但 Spring DI 完全靠类路径）。

# ─── 收尾 ──────────────────────────────────────────────────────────────
if [ "$HIT_COUNT" -ne 0 ]; then
  err "T061 FAILED —— Notify 代码泄漏到 ${#HIT_FILES[@]} 个非白名单模块"
  err "合规归属: oryxos-tool (FR-014 主体) + oryxos-boot (NotifyToolConfig DI)"
  exit 77
fi

log "T061 PASS —— io.oryxos.tool.notify 仅在 oryxos-tool (+ oryxos-boot DI)"
exit 0
