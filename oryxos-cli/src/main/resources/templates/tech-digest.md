---
name: __PROFILE_NAME__
description: Daily tech news digest (Hacker News + Lobsters + arXiv).
provider:
  name: qwen
  model: qwen-turbo
tools:
  - read_file
  - notify
notify_channels:
  - type: webhook
    config:
      url: ${ORYXOS_NOTIFY_WEBHOOK_URL}
settings:
  max_iterations: 15
  max_history_turns: 30
schedules:
  - id: daily-evening
    cron: "0 18 * * *"
    zone: Asia/Shanghai
    message: "Summarise the top 5 stories from today and notify."
---

# Daily Tech Digest

Each evening at 18:00 (Asia/Shanghai), read `.oryxos/agents/__PROFILE_NAME__/skills/hn-fetcher.md`
on demand via `read_file`, summarise the day's top 5 stories in ≤ 120 words total,
and push the digest to the configured `notify` webhook.

Memory: recall previously saved user topic preferences from `.oryxos/memory/MEMORY.md`
to bias the digest (e.g. "user prefers Rust posts over JavaScript").