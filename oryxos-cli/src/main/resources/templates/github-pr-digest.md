---
name: __PROFILE_NAME__
description: Daily GitHub PR digest across watched repos.
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - shell
  - notify
notify_channels:
  - type: webhook
    config:
      url: ${ORYXOS_NOTIFY_WEBHOOK_URL}
settings:
  max_iterations: 12
  max_history_turns: 25
schedules:
  - id: weekday-morning
    cron: "0 9 * * 1-5"
    zone: Asia/Shanghai
    message: "Run scripts/fetch-prs.sh and summarise open PRs."
---

# Daily GitHub PR Digest

Each weekday morning at 09:00 (Asia/Shanghai), run `scripts/fetch-prs.sh` (which calls
`gh pr list --json number,title,author,url --repo <watched-repos>` via the `shell` tool),
group open PRs by author, and push the formatted digest to the configured `notify`
webhook.

Sandbox: `shell` invocations must respect the script-level trust boundary (see
`docs/DemandAnalysis.md §6.4` and `CLAUDE.md §11`).

If a repo's `gh` call fails, log the error and continue with the remaining repos.