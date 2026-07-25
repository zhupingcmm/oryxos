# T054 — Quickstart §4 Concurrency Load Test (deferred)

## Status

**Deferred** to US-5 (Web/Scheduler triggers in `oryxos-channel-cli` / `oryxos-web`).

## Why

T054 calls for spawning 20 parallel CLI `chat small-talk` sessions against a shared `ApplicationContext`
and asserting the SQLite `llm_calls` table has 20 distinct session_ids.

This is structurally an **integration** test that requires:

1. `oryxos-channel-cli` CLI runner (US-1 + US-5 wiring)
2. `oryxos-storage` SQLite DB (datasource, table migrations)
3. `application.yaml` with `spring-boot-starter-data-jpa` + H2/SQLite driver
4. Spring boot for `ApplicationContext`

None of these are in `oryxos-core` (US-2's scope per Constitution §I 9-module layout).

## What covers SC-003 in US-2 instead

`ReActLoopConcurrencyTest` (T051) at `oryxos-core/src/test/java/io/oryxos/core/ReActLoopConcurrencyTest.java`
covers the equivalent SC-003 semantics **without** the SQLite dependency:

- 20 concurrent threads, each with its own `InMemorySession`
- Each session ends with exactly 2 messages (own user + synthesized assistant)
- No cross-session message bleed
- `ProfileContext.current()` empty after loop ends
- `ProfileContext` is cross-thread isolated

That is the spec contract for SC-003, fulfilled at the unit layer that US-2 owns.

## When this gets unblocked

US-5 (`005-web-service`) will add `oryxos-web` + Spring Boot integration tests with `oryxos-storage`
backed by H2/SQLite. T054 will be relit there as an end-to-end concurrency IT.
