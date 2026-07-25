---
name: __PROFILE_NAME__
description: Minimal agent scaffold — replaces this description with one line of intent.
provider:
  name: deepseek
  model: deepseek-chat
tools: []
memory_blocks: []
notify_channels: []
schedules: []
settings:
  max_iterations: 10
  max_history_turns: 20
---

# __PROFILE_NAME__

You are an OryxOS agent. Replace this body with the task instructions this agent
should follow on every invocation.

See `docs/DemandAnalysis.md §4` for the Profile YAML schema and
`specs/003-cli-commands/contracts/profile.md` for `profile create --template` semantics.