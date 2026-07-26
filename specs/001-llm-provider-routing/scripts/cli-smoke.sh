#!/usr/bin/env bash
# scripts/cli-smoke.sh — end-to-end smoke test for the OryxOS CLI
#
# Exercises the full happy path through zero-Spring and must-Spring commands,
# asserts exit codes per FR-009 / SC-007, and verifies the workspace layout
# written by `oryxos init`. Designed to run from a clean checkout.
#
# Usage:
#   bash scripts/cli-smoke.sh                    # uses default build
#   JAR=oryxos-cli/target/oryxos-cli-*.jar bash scripts/cli-smoke.sh
#
# Exit codes per scenario follow BSD sysexits (see oryxos-cli/src/main/java/io/oryxos/cli/exitcode/Sysexits.java).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

# Locate the CLI jar (built artifact). Skip the build if it's missing.
# Store as ABSOLUTE path (resolved against REPO_ROOT) so it survives later
# `cd "${WORK}/project"` which would break any relative-path `java -jar`.
JAR_REL="${JAR:-$(ls -1 oryxos-cli/target/oryxos-cli-*.jar 2>/dev/null | grep -v '\\-sources' | grep -v '\\-javadoc' | head -1 || true)}"
if [[ -n "${JAR_REL}" && ! "${JAR_REL}" = /* ]]; then
  JAR="${REPO_ROOT}/${JAR_REL}"
else
  JAR="${JAR_REL}"
fi
if [[ -z "${JAR}" || ! -f "${JAR}" ]]; then
  echo "Building CLI jar..."
  mvn -pl oryxos-cli -am package -DskipTests -q
  JAR_REL="$(ls -1 oryxos-cli/target/oryxos-cli-*.jar 2>/dev/null | grep -v '\\-sources' | grep -v '\\-javadoc' | head -1 || true)"
  if [[ -n "${JAR_REL}" && ! "${JAR_REL}" = /* ]]; then
    JAR="${REPO_ROOT}/${JAR_REL}"
  else
    JAR="${JAR_REL}"
  fi
fi

ORYXOS="java -jar ${JAR}"
WORK="$(mktemp -d -t oryxos-smoke-XXXXXX)"
trap 'rm -rf "${WORK}"' EXIT

echo "==> Workspace: ${WORK}"
echo "==> CLI jar:   ${JAR}"

# --- Scenario 1: init on a fresh workspace ---
echo
echo "## Scenario 1: oryxos init (zero-Spring)"
mkdir -p "${WORK}/project"
cd "${WORK}/project"
${ORYXOS} init
test -f .oryxos/AGENTS.md        || { echo "FAIL: missing AGENTS.md"; exit 1; }
test -f .oryxos/SOUL.md          || { echo "FAIL: missing SOUL.md"; exit 1; }
test -f .oryxos/USER.md          || { echo "FAIL: missing USER.md"; exit 1; }
test -f .oryxos/mcp_servers.yaml || { echo "FAIL: missing mcp_servers.yaml"; exit 1; }
test -f .oryxos/memory/MEMORY.md || { echo "FAIL: missing MEMORY.md"; exit 1; }
test -d .oryxos/agents           || { echo "FAIL: missing agents/"; exit 1; }
test -d .oryxos/sessions         || { echo "FAIL: missing sessions/"; exit 1; }
test -d .oryxos/logs             || { echo "FAIL: missing logs/"; exit 1; }
echo "  ok — 9 entries created"

# --- Scenario 2: init is idempotent (second run exits 1) ---
echo
echo "## Scenario 2: oryxos init again (idempotency — must exit 1)"
set +e
${ORYXOS} init > /dev/null 2>&1
RC=$?
set -e
test "${RC}" -eq 1 || { echo "FAIL: expected exit 1, got ${RC}"; exit 1; }
echo "  ok — exit 1 (already initialized)"

# --- Scenario 3: profile create / list / show / delete ---
echo
echo "## Scenario 3: profile CRUD"
${ORYXOS} profile create weather-bot --template minimal
${ORYXOS} profile create tech-digest --template tech-digest
LIST=$(${ORYXOS} profile list)
echo "${LIST}" | grep -q "weather-bot" || { echo "FAIL: list missing weather-bot"; exit 1; }
echo "${LIST}" | grep -q "tech-digest" || { echo "FAIL: list missing tech-digest"; exit 1; }
${ORYXOS} profile show weather-bot | grep -q "weather-bot" || { echo "FAIL: show failed"; exit 1; }
${ORYXOS} profile delete weather-bot --force
${ORYXOS} profile delete tech-digest --force
echo "  ok — create/list/show/delete"

# --- Scenario 4: profile show missing profile → EX_USAGE (64) ---
echo
echo "## Scenario 4: profile show missing (must exit 64)"
set +e
${ORYXOS} profile show ghost > /dev/null 2>&1
RC=$?
set -e
test "${RC}" -eq 64 || { echo "FAIL: expected 64, got ${RC}"; exit 1; }
echo "  ok — exit 64 (EX_USAGE)"

# --- Scenario 5: status with partial config (warning) ---
echo
echo "## Scenario 5: oryxos status (no providers → warning)"
cat > .oryxos/application.yaml <<'EOF'
oryxos:
  providers:
    deepseek:
      model: deepseek-chat
      credentialRef: ORYXOS_DEEPSEEK_API_KEY
    qwen:
      model: qwen-turbo
      credentialRef: ORYXOS_QWEN_API_KEY
EOF
set +e
ORYXOS_DEEPSEEK_API_KEY=dummy ${ORYXOS} status > /dev/null 2>&1
RC=$?
set -e
# Either 0 (both resolved) or 2 (warning) is acceptable depending on env.
test "${RC}" -eq 0 -o "${RC}" -eq 2 || { echo "FAIL: expected 0 or 2, got ${RC}"; exit 1; }
echo "  ok — exit ${RC} (OK or WARNING)"

# --- Scenario 6: missing workspace → EX_GENERIC-ish ---
echo
echo "## Scenario 6: oryxos status in non-initialized dir (exit 1)"
set +e
${ORYXOS} -w /tmp/oryxos-nonexistent-$$ status > /dev/null 2>&1
RC=$?
set -e
test "${RC}" -ne 0 || { echo "FAIL: expected non-zero, got ${RC}"; exit 1; }
echo "  ok — exit ${RC}"

echo
echo "=========================================="
echo "  All CLI smoke scenarios passed"
echo "=========================================="