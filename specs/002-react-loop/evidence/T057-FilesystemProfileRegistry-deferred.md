# T057 — FilesystemProfileRegistry (deferred)

## Status

**Deferred** to US-5 (Web Service + Web Service combined with Scheduler).

## Why

T057 calls for parsing `.oryxos/agents/*/AGENT.md` YAML frontmatter into `Profile` record at startup.

This requires:

1. `AGENT.md` loader + YAML parser (SnakeYAML or hand-rolled) — neither present in `oryxos-core`
2. File system scan at Spring `ApplicationContext` bootstrap
3. Error handling for malformed agent directories
4. Coordination with the future `AgentLoader` that distinguishes "AGENT.md exists but unparseable" from "AGENT.md missing" (CLAUDE.md §9.7: "一个目录 = 一个 Agent")

US-2 owns `Profile` record (T016) + `ProfileRegistry` interface (T056) + the `InMemoryProfileRegistry`
reference impl. Wiring the filesystem layer is a US-5 concern (Spring `@Profile("!test")` registration
of an `ApplicationRunner`) — it depends on the YAML parser choice and error-policy that US-5 selects.

## What covers FR-001 / FR-021 in US-2 instead

`DefaultAgentService` + `InMemoryProfileRegistry` (T058 + T056) implement the FR-001 + FR-021 contract:

- Single public method `process(Session, String)`
- Profile lookup by name via `ProfileRegistry.find(name)`
- `ProfileContext` set / cleared around the loop

`DefaultAgentServiceTest` (T059) has 7 tests verifying C-AS-1..C-AS-5 + C-AS-7 across the registry
abstraction. Production code that calls `process(...)` (future CLI/Web/Scheduler) depends on
`ProfileRegistry` interface only — swapping in `FilesystemProfileRegistry` later is a pure
DI change with no surface ripple.

## When this gets unblocked

US-5 (`005-web-service`) will add `FilesystemProfileRegistry` (startup scan + YAML parse) **or**
a `JpaProfileRegistry` reading from a `profiles` table (depending on which proves simpler after the
`AgentLoader` lands). Either choice is a drop-in replacement.
