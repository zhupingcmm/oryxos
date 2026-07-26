#!/usr/bin/env bash
# Scenario fragment — exercises `oryxos init` end-to-end. Used by
# scripts/cli-smoke.sh as the first scenario; can also be invoked directly
# when debugging layout issues.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${JAR:-$(ls -1 "${REPO_ROOT}/oryxos-cli/target/oryxos-cli-*.jar" 2>/dev/null | grep -v '\\-sources' | grep -v '\\-javadoc' | head -1 || true)}"
WORK="${1:-$(mktemp -d -t oryxos-init-XXXXXX)}"

cd "${WORK}"
java -jar "${JAR}" init

test -f .oryxos/AGENTS.md
test -f .oryxos/SOUL.md
test -f .oryxos/USER.md
test -f .oryxos/mcp_servers.yaml
test -f .oryxos/memory/MEMORY.md
test -d .oryxos/agents
test -d .oryxos/sessions
test -d .oryxos/logs

echo "init scenario OK (workspace: ${WORK})"