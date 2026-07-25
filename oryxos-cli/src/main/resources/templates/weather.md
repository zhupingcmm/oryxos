---
name: __PROFILE_NAME__
description: Daily weather notifier (city-configurable).
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - http_get
  - notify
notify_channels:
  - type: webhook
    config:
      url: ${ORYXOS_NOTIFY_WEBHOOK_URL}
settings:
  max_iterations: 10
  max_history_turns: 20
schedules:
  - id: daily-morning
    cron: "0 8 * * *"
    zone: Asia/Shanghai
    message: "Fetch today's weather for Shanghai and notify."
---

# Daily Weather Notifier

Every morning at 08:00 (Asia/Shanghai), fetch the current weather for the configured
city via `http_get`, summarise the forecast in ≤ 80 words, and push the summary to
the configured `notify` webhook.

If the upstream weather service returns an error, retry once with a 3 s delay, then
fall back to "weather unavailable" and still push the notification so the user is
aware the run completed.