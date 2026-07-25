#!/usr/bin/env bash
# Scenario fragment — Profile CRUD round trip (create / list / show / delete).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="${JAR:-$(ls -1 "${REPO_ROOT}/oryxos-cli/target/oryxos-cli-*.jar" 2>/dev/null | grep -v '\\-sources' | grep -v '\\-javadoc' | head -1 || true)}"
WORK="${1:-$(mktemp -d -t oryxos-profile-XXXXXX)}"

cd "${WORK}"
java -jar "${JAR}" init > /dev/null

java -jar "${JAR}" profile create weather-bot --template minimal
java -jar "${JAR}" profile create tech-digest --template tech-digest

LIST=$(java -jar "${JAR}" profile list)
echo "${LIST}" | grep -q "weather-bot"
echo "${LIST}" | grep -q "tech-digest"

java -jar "${JAR}" profile show weather-bot | grep -q "weather-bot"

java -jar "${JAR}" profile delete weather-bot --force
java -jar "${JAR}" profile delete tech-digest --force

# Both gone
test ! -d .oryxos/agents/weather-bot
test ! -d .oryxos/agents/tech-digest

echo "profile-crud scenario OK (workspace: ${WORK})"