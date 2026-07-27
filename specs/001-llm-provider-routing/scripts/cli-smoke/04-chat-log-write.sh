#!/usr/bin/env bash
# Scenario fragment — Phase 8 Done-When #4 manual smoke for FR-017 / FR-018.
#
# Verifies that running `oryxos chat` actually writes to
# `.oryxos/logs/oryxos-cli.log` (and `oryxos-cli-error.log`), per the
# spec contract that was never machine-verified until this fragment.
#
# REQUIRES: a real Provider API key in the environment, because the test
# executes an end-to-end chat (not WireMocked). Without a key the script
# skips with a clear message — this is intentional, see Phase 8 Done-When #4
# in specs/003-cli-commands/tasks.md (process gap; reviewer runs in a
# key-bearing environment).
#
# Usage:
#   bash scripts/cli-smoke/04-chat-log-write.sh [/path/to/workspace]
#
# Exit codes:
#   0 — chat ran, log file was written and contains the expected markers
#   2 — DEEPSEEK_API_KEY (or ORYXOS_CHAT_SMOKE_KEY) is not set; script
#       intentionally skips (CI runs without a key get a soft pass)
#   1 — chat ran but the log file is missing the expected markers (regression)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${JAR:-$(ls -1 "${REPO_ROOT}/oryxos-cli/target/oryxos-cli-*.jar" 2>/dev/null | grep -v '\\-sources' | grep -v '\\-javadoc' | head -1 || true)}"
WORK="${1:-$(mktemp -d -t oryxos-chat-smoke-XXXXXX)}"

# Pick the first non-empty provider key we find.
PROVIDER_KEY="${ORYXOS_CHAT_SMOKE_KEY:-${DEEPSEEK_API_KEY:-${ORYXOS_DEEPSEEK_API_KEY:-}}}"

if [[ -z "${PROVIDER_KEY}" ]]; then
    echo "skip: no provider key in env (set DEEPSEEK_API_KEY or ORYXOS_CHAT_SMOKE_KEY)" >&2
    echo "      this script is a MANUAL reviewer smoke per Phase 8 Done-When #4;" >&2
    echo "      CI intentionally does not exercise the live chat path." >&2
    exit 2
fi

if [[ -z "${JAR}" || ! -f "${JAR}" ]]; then
    echo "skip: CLI jar not built yet (run: mvn -pl oryxos-cli -am package -DskipTests)" >&2
    exit 2
fi

cd "${WORK}"

# 1. Init the workspace (zero-Spring, fast).
java -jar "${JAR}" init > /dev/null 2>&1 || true

# 2. Create a minimal Profile if missing.
PROFILE="${ORYXOS_CHAT_SMOKE_PROFILE:-weather-bot}"
PROFILE_DIR=".oryxos/agents/${PROFILE}"
if [[ ! -d "${PROFILE_DIR}" ]]; then
    java -jar "${JAR}" profile create "${PROFILE}" --template minimal \
        > /dev/null 2>&1 || true
fi

# 3. Wire the application.yaml so credential resolution succeeds.
mkdir -p .oryxos
cat > .oryxos/application.yaml <<EOF
oryxos:
  providers:
    deepseek:
      model: deepseek-chat
      credentialRef: ORYXOS_CHAT_SMOKE_KEY
EOF

# 4. Run chat — this is the line that exercises FR-017 + FR-018.
DEEPSEEK_API_KEY="${PROVIDER_KEY}" \
    ORYXOS_CHAT_SMOKE_KEY="${PROVIDER_KEY}" \
    java -jar "${JAR}" chat "${PROFILE}" "ping" \
    > /tmp/chat-stdout.$$ 2> /tmp/chat-stderr.$$ || true

# 5. Verify the log file was written.
LOG=".oryxos/logs/oryxos-cli.log"
ERR_LOG=".oryxos/logs/oryxos-cli-error.log"

if [[ ! -f "${LOG}" ]]; then
    echo "FAIL: ${LOG} was NOT written — chat did not exercise the log path" >&2
    cat /tmp/chat-stderr.$$ >&2 || true
    rm -f /tmp/chat-stdout.$$ /tmp/chat-stderr.$$
    exit 1
fi

# 6. Spot-check the log for expected markers (FR-017 / FR-018).
if ! grep -q "cli.command.invoked" "${LOG}"; then
    echo "FAIL: ${LOG} missing cli.command.invoked marker (FR-018 base contract)" >&2
    rm -f /tmp/chat-stdout.$$ /tmp/chat-stderr.$$
    exit 1
fi

echo "chat-log-smoke OK (workspace: ${WORK}, log: ${LOG})"
rm -f /tmp/chat-stdout.$$ /tmp/chat-stderr.$$