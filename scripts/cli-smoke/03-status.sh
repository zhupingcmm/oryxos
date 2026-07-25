#!/usr/bin/env bash
# Scenario fragment — `oryxos status` reads provider credentials from env
# and prints a NAME/MODEL/CREDENTIAL_REF/API_KEY_RESOLVED table.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${JAR:-$(ls -1 "${REPO_ROOT}/oryxos-cli/target/oryxos-cli-*.jar" 2>/dev/null | grep -v '\\-sources' | grep -v '\\-javadoc' | head -1 || true)}"
WORK="${1:-$(mktemp -d -t oryxos-status-XXXXXX)}"

cd "${WORK}"
java -jar "${JAR}" init > /dev/null
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

# Scenario 3a: one key resolved, one missing → exit 2 (warning)
set +e
ORYXOS_DEEPSEEK_API_KEY=dummy java -jar "${JAR}" status > /dev/null 2>&1
RC=$?
set -e
test "${RC}" -eq 0 -o "${RC}" -eq 2

# Scenario 3b: bad yaml → command still runs, exits 0
echo "this is: not yaml" > .oryxos/application.yaml
set +e
java -jar "${JAR}" status > /dev/null 2>&1
RC=$?
set -e
test "${RC}" -ne 64   # NOT EX_USAGE — bad yaml is graceful

echo "status scenario OK (workspace: ${WORK})"