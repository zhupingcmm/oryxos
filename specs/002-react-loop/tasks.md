---
description: "Task list for US-2 ReAct Loop implementation"
---

# Tasks: ReAct Loop (US-2)

**Input**: Design documents from `/specs/002-react-loop/` — [spec.md](spec.md), [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)
**Prerequisites**: plan.md ✓, spec.md ✓, research.md ✓, data-model.md ✓, contracts/ ✓, quickstart.md ✓, constitution.md ✓
**Tests**: **Required** for US-2 — spec SC-001..SC-007 + NFR-001..NFR-003 demand unit + integration + e2e test artifacts.
**Organization**: Three priority slices within US-2 (P1 / P2 / P3) plus a foundational phase for the interface migration (R-1) and shared types, plus an integration phase wiring `AgentService`. P1 is the MVP.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependencies)
- **[Story]**: which priority slice this task belongs to — `[US-2/P1]` (Pure Reason), `[US-2/P2]` (Single Tool), `[US-2/P3]` (Multi-Tool Chain), `[US-2/AG]` (AgentService integration — across slices)
- File paths are absolute, repo-relative (`d:/code/java/oryxos/...` abbreviated to `oryxos-*/src/main/java/...`).
- Story labels disambiguate from the umbrella US-2 deliverable name.

## Path Conventions

Multi-module Maven layout (Constitution §I: exactly 9 modules). Tasks land here:

- `oryxos-core/src/main/java/io/oryxos/core/*.java` — `ReActLoop`、interface/record、ToolExecutor 接口、ProfileContext
- `oryxos-core/src/test/java/io/oryxos/core/*Test.java` + `*IT.java` — unit + Spring Boot 集成测试
- `oryxos-storage/src/main/java/io/oryxos/storage/{entity,repository}/*.java` — JPA 实体 / Repository
- `oryxos-storage/src/main/resources/db/migration/V2__*.sql`（可选，US-2 内手动 SQL；Flyway/Liquibase 留扩展阶段）
- `oryxos-provider/src/main/java/io/oryxos/provider/*.java` — 仅针对 R-1 接口下沉做 import path 调整

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm the workspace + branch + Maven module state are clean before any code touches US-2. Verify that the foundation laid by US-1 compiles end-to-end (this is the regression baseline — see "Risk & Mitigation" § of [plan.md](plan.md)).

- [ ] T001 Confirm `git branch` is `002-react-loop` and working tree is clean; if not, `git checkout 002-react-loop && git pull`
- [ ] T002 Run `mvn -pl oryxos-core,oryxos-storage,oryxos-provider,oryxos-boot -am clean compile` and capture build output to `specs/002-react-loop/evidence/T002-baseline-compile.log`; expected: BUILD SUCCESS, zero warnings beyond the pre-existing Spring AI deprecation note
- [ ] T003 [P] Run `mvn -pl oryxos-provider test` and capture to `evidence/T003-baseline-tests.log`; expected: 35/35 pass (US-1 baseline must stay green throughout US-2)
- [ ] T004 [P] Verify `oryxos-storage/pom.xml` already declares `spring-boot-starter-data-jpa` and `hypersistence-utils-hibernate-63` (US-1 introduced these for `LlmCallRecord`); if missing, add them and re-run T002

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Block all US-2 sub-stories until the interface migration (R-1), the new core data types, and the new storage entities are in place. **CRITICAL** — every subsequent phase depends on this.

- [ ] T005 Move `ProviderService` interface from `oryxos-provider/src/main/java/io/oryxos/provider/ProviderService.java` to `oryxos-core/src/main/java/io/oryxos/core/ProviderService.java`; update its package declaration and javadoc per [contracts/ProviderService.md](contracts/ProviderService.md) §1 + §2 (cite each C-PS-1..C-PS-7 as a `@implNote` block-comment)
- [ ] T006 [P] Move `LlmRequest`, `LlmResponse`, `Provider` records the same way (deletion in `oryxos-provider`, recreation in `oryxos-core`); preserve exact field signatures since US-1's `LlmCallRecord` deserialization depends on them
- [ ] T007 [P] Update `DefaultProviderService` in `oryxos-provider` to `implements io.oryxos.core.ProviderService`; change its import + `implements` clause only — no logic change
- [ ] T008 [P] Update US-1 imports across `oryxos-provider` (35 tests + ~10 production classes): replace `io.oryxos.provider.{ProviderService,LlmRequest,LlmResponse,Provider}` with `io.oryxos.core.{...}`; run `mvn -pl oryxos-provider test` after sweep — must remain 35/35 green
- [ ] T009 [P] Create `Message` record in `oryxos-core/src/main/java/io/oryxos/core/Message.java` with fields per [data-model.md §3.1](data-model.md) + 4 static factories (`user`/`assistantText`/`assistantToolCalls`/`toolResult`) and a `Role` enum; compact constructor validates role-specific invariants
- [ ] T010 [P] Create `ToolCall` record in `oryxos-core/src/main/java/io/oryxos/core/ToolCall.java` with fields `{id, name, arguments}` and a constructor accepting `null` arguments (treat as empty map)
- [ ] T011 [P] Create `ToolResult` record in `oryxos-core/src/main/java/io/oryxos/core/ToolResult.java` with fields `{success, payload, errorMessage}` + static `ok(Map)` / `error(String)` factories; compact constructor rejects `success=true && errorMessage != null`
- [ ] T012 [P] Create `LoopResult` record in `oryxos-core/src/main/java/io/oryxos/core/LoopResult.java` per [contracts/LoopResult.md](contracts/LoopResult.md) §1 + §2 (compact constructor enforces C-LR-1 / C-LR-2)
- [ ] T013 [P] Create `Session` interface in `oryxos-core/src/main/java/io/oryxos/core/Session.java` per [data-model.md §3.2.1](data-model.md) (id, profileName, messages, appendMessage, createdAt, updatedAt)
- [ ] T014 [P] Create `OryxTool` placeholder interface in `oryxos-core/src/main/java/io/oryxos/core/OryxTool.java` — minimal signature `{String name(); ToolResult execute(Map<String,Object>);}`; no implementation in US-2 (per R-2; US-4 owns it)
- [ ] T015 Create `ProfileContext` final class in `oryxos-core/src/main/java/io/oryxos/core/ProfileContext.java` per [contracts/ProfileContext.md](contracts/ProfileContext.md) §1 — including nested `Snapshot` record + thread-local + `set`/`current`/`clear` static methods; `set` throws `IllegalStateException` on double-set (C-PC-1)
- [ ] T016 [P] Create `Profile` record in `oryxos-core/src/main/java/io/oryxos/core/Profile.java` per [data-model.md §3.3](data-model.md) with nested `Settings` record + `extra: Map<String,Object>` passthrough; pattern-validate `name` matching `^[a-z][a-z0-9-]{0,63}$` in compact constructor
- [ ] T017 Create `SessionEntity` JPA entity in `oryxos-storage/src/main/java/io/oryxos/storage/entity/SessionEntity.java` per [data-model.md §3.2.2](data-model.md) — table `sessions`, JSON `messages` via `JsonType`, `@Transactional` on `appendMessage`, `create(UUID, String)` factory
- [ ] T018 [P] Create `SessionRepository` interface in `oryxos-storage/src/main/java/io/oryxos/storage/repository/SessionRepository.java` extending `JpaRepository<SessionEntity, UUID>` with custom finder `findByProfileName(String)` and `findByUpdatedAtAfter(Instant)`
- [ ] T019 Create `ToolInvocationRecord` JPA entity in `oryxos-storage/src/main/java/io/oryxos/storage/entity/ToolInvocationRecord.java` per [data-model.md §3.9](data-model.md) — table `tool_invocations`, all `@Check` constraints, `session_iteration` column for cross-table joins
- [ ] T020 [P] Create `ToolInvocationRepository` interface in `oryxos-storage/src/main/java/io/oryxos/storage/repository/ToolInvocationRepository.java` with `countBySessionId(UUID)` and `findBySessionIdOrderByStartedAt(UUID)`
- [ ] T021 [P] Create `InMemorySession` test helper in `oryxos-core/src/test/java/io/oryxos/core/testing/InMemorySession.java` — implements `Session`, stores messages in `ArrayList<Message>`, **not** for production use
- [ ] T022 [P] Create `FakeProviderService` test helper in `oryxos-core/src/test/java/io/oryxos/core/testing/FakeProviderService.java` — implements `io.oryxos.core.ProviderService` with a queue of canned `LlmResponse`s (pop in order; empty queue → throw `IllegalStateException("test stub empty")` to surface exhaustion in tests)
- [ ] T023 [P] Create `FakeToolExecutor` test helper in `oryxos-core/src/test/java/io/oryxos/core/testing/FakeToolExecutor.java` — implements `io.oryxos.core.ToolExecutor` with a `Map<String, ToolResult>` lookup, also captures `(toolName, arguments, profile)` per call for assertion
- [ ] T024 Write `ProfileContextTest` in `oryxos-core/src/test/java/io/oryxos/core/ProfileContextTest.java` covering: `setAndClear`, `doubleSetThrows` (I-06 / C-PC-1), `isolatedAcrossThreads` (verify two threads see separate state), `clearWithoutSetIsNoop`; expected: 4 tests, all green
- [ ] T025 Run `mvn -pl oryxos-core,oryxos-storage,oryxos-provider -am clean compile` after the migration; expected: BUILD SUCCESS, US-1 tests still 35/35 (T003 baseline preserved); capture log to `evidence/T025-foundation-compile.log`

**Checkpoint**: Foundation ready — `ReActLoop` can now be written against stable interfaces. Sub-stories P1/P2/P3 can proceed (sequentially in priority order, or in parallel with careful discipline since P1/P2/P3 each write into distinct method-level slots of `ReActLoop`).

---

## Phase 3: [US-2/P1] Pure Reason Path (Priority: P1) 🎯 MVP

**Goal**: Implement the minimum viable loop — assemble prompt, call LLM once, observe no tool calls, return text. No tool layer exercised.

**Independent Test**: `ReActLoopPureReasonTest` passes with the following assertions (per spec User Story 1 acceptance scenarios):
- Given `Profile{tools=[]}` and a user message, loop returns `LoopResult(iter=1, terminatedAtMax=false)`
- `LlmCallRecord` count grows by exactly 1
- `ToolInvocationRecord` count unchanged (zero tool calls)
- Session messages length grows by exactly 2: `user → assistant(text)`
- Bootstrap files (AGENT.md, SOUL.md, USER.md) appear in the system prompt in declared order
- Current local date/time line appended at end of system prompt (FR-005)

### Tests for [US-2/P1] (write FIRST, must FAIL before implementation)

- [ ] T026 [P] [US-2/P1] Write `ReActLoopPureReasonTest` skeleton in `oryxos-core/src/test/java/io/oryxos/core/ReActLoopPureReasonTest.java` — five `@Test` methods covering US1 acceptance scenarios 1, 2, 3 from [spec.md](spec.md). Expected: 5 failed / 0 passed at this point; commit the failing version as `evidence/T026-failing-tests.log`
- [ ] T027 [P] [US-2/P1] Write `MessageTest` in `oryxos-core/src/test/java/io/oryxos/core/MessageTest.java` — compact constructor invariants from [data-model.md §3.1](data-model.md); expected: 4 tests, all must be GREEN immediately (T009 record has its tests in this same step)

### Implementation for [US-2/P1]

- [ ] T028 [P] [US-2/P1] Create `Prompt` record in `oryxos-core/src/main/java/io/oryxos/core/Prompt.java` per [data-model.md §3.6](data-model.md) — fields `{systemBlocks, memoryBlocks, historyBlocks, toolSchemas}`, all `List<Map<String,Object>>`; include `flatten()` helper that concatenates all four lists in spec FR-004 order
- [ ] T029 [P] [US-2/P1] Create `ToolExecutor` interface in `oryxos-core/src/main/java/io/oryxos/core/ToolExecutor.java` per [contracts/ToolExecutor.md](contracts/ToolExecutor.md) §1 — `invoke(String, Map<String,Object>, Profile) -> ToolResult`
- [ ] T030 [P] [US-2/P1] Create `DefaultToolExecutor` stub in `oryxos-core/src/main/java/io/oryxos/core/DefaultToolExecutor.java` — implements `ToolExecutor` for US-2 scope: returns `ToolResult.error("tool not in profile: <name>")` when `toolName ∉ profile.tools()` (always true in P1 tests since profile has empty `tools`); on allowed tool, throws `UnsupportedOperationException("Default stub — US-4 will implement")` (this satisfies C-TE-2 + C-TE-7 audit-write responsibility, but for P1 tests we'll use `FakeToolExecutor` which skips the audit-write step entirely)
- [ ] T031 [P] [US-2/P1] Create stub `MemoryInjector` interface in `oryxos-core/src/main/java/io/oryxos/core/MemoryInjector.java` with single method `List<Message> inject(Profile, Session);` — package-private `NoopMemoryInjector` returns empty list for US-2; US-3 will provide real impl
- [ ] T032 [P] [US-2/P1] Create stub `ToolSchemaProvider` interface in `oryxos-core/src/main/java/io/oryxos/core/ToolSchemaProvider.java` with single method `List<Map<String,Object>> schemasFor(Profile);` — default impl returns `List.of()` for P1 (no tool schemas needed)
- [ ] T033 [US-2/P1] Implement `PromptBuilder` in `oryxos-core/src/main/java/io/oryxos/core/PromptBuilder.java` — four-part assembly per FR-004: (1) AGENT.md content + Bootstrap files + local datetime line; (2) `memoryInjector.inject(...)`; (3) last N history messages (from `Session.messages()` truncated by `settings.maxHistoryTurns`); (4) `toolSchemaProvider.schemasFor(...)`. Constructor injects the two stub interfaces. (No-AGENT.md fallback: use `""` to keep tests deterministic.)
- [ ] T034 [US-2/P1] Implement `ReActLoop` skeleton in `oryxos-core/src/main/java/io/oryxos/core/ReActLoop.java` — minimal shape that supports P1: single iteration, no tool handling, returns `LoopResult(r.text(), 1, false, profile.name(), session.id())`. Will be extended in T040 (P2) and T052 (P3). Constructor: `(ProviderService, PromptBuilder, ToolExecutor)`. Add `@Component` annotation for Spring pickup. (NOTE: real `DefaultToolExecutor` not wired yet at P1; use `FakeToolExecutor` in tests — wiring comes in T041.)
- [ ] T035 [US-2/P1] Add structured logging hooks to `ReActLoop`: emit `react.iteration session_id={id} iteration=1/{max} tool_calls=0` and `react.completed session_id={id} iterations=1 duration_ms=N final_tool_call=false` per FR-019 + FR-020; use SLF4J `Logger` at INFO level with `{}` placeholders (NEVER string concat)
- [ ] T036 [US-2/P1] Run `ReActLoopPureReasonTest` and `MessageTest`; expected: all 9 tests green. Capture results to `evidence/T036-P1-green.log`
- [ ] T037 [US-2/P1] `git add` only the files created/modified in Phase 3 (do NOT include `tasks.md`); commit with message `feat(core): implement US-2/P1 Pure Reason Path (single LLM, no tools)`; capture `git log --oneline -1` to `evidence/T037-P1-commit.txt`

**Checkpoint**: `ReActLoop` runs for the simplest case. P1 demo (quickstart §1) works.

---

## Phase 4: [US-2/P2] Single Reason-Act-Observe Cycle (Priority: P2)

**Goal**: Extend `ReActLoop` to dispatch `Tool_call`s to `ToolExecutor` and feed results back; loop now iterates at most `MAX_ITERATIONS` times.

**Independent Test**: `ReActLoopToolChainTest` covers spec US2 acceptance scenarios:
- profile with `tools=[http_get]` + user message → 2 LLM calls + 1 Tool audit row + 4 Session messages
- Tool fails → 1 LLM call + 1 failure Tool audit row + 4 messages + loop continues
- Tool name not in profile → `ToolResult.error("tool not in profile: ...")` synthesized, audit row written, loop continues

### Tests for [US-2/P2] (write FIRST, must FAIL before implementation)

- [ ] T038 [P] [US-2/P2] Write `ReActLoopToolChainTest` skeleton in `oryxos-core/src/test/java/io/oryxos/core/ReActLoopToolChainTest.java` — 6 `@Test` methods covering all 3 US2 acceptance scenarios (each scenario branches: success path + failure path → ~6 total). Expected: all FAIL initially.
- [ ] T039 [P] [US-2/P2] Write `DefaultToolExecutorTest` in `oryxos-core/src/test/java/io/oryxos/core/DefaultToolExecutorTest.java` — at minimum: `refusedToolReturnsError` (C-TE-1) + `allowedToolThrowsUnsupported` (US-2 stub behavior). Tests must FAIL initially since `DefaultToolExecutor` exists but doesn't write audit rows yet — that's T044's responsibility.

### Implementation for [US-2/P2]

- [ ] T040 [US-2/P2] Extend `ReActLoop.run(...)` to support tool dispatch: after each LLM response, if `r.toolCalls()` non-empty, iterate over them, call `toolExecutor.invoke(tc.name(), tc.arguments(), profile)`, append `Message.toolResult(...)`, increment `ProfileContext.current().currentIteration()` is NOT done here (iteration counter increments once per LLM call, see FR-013). Update return logic to handle Path (a) — no tool calls → return `LoopResult` per FR-013.
- [ ] T041 [US-2/P2] Wire `DefaultToolExecutor` into the dependency injection container: create `ToolExecutorConfig` in `oryxos-core/src/main/java/io/oryxos/core/config/ToolExecutorConfig.java` exposing `@Bean ToolExecutor toolExecutor(ToolInvocationRepository, MemoryInjector)` (default wiring for non-test env). Add a `@Bean @Primary` for `FakeToolExecutor` scoped to `application-e2e-test` profile (spring `@Profile("test")`).
- [ ] T042 [US-2/P2] Update `DefaultToolExecutor` (T030 stub) to write `ToolInvocationRecord` rows on both paths: refused (write `success=false`, `error_message="tool not in profile: <name>"`) and allowed (write row then throw `UnsupportedOperationException` if invoked — preserves the stub semantics while satisfying C-TE-2 audit-on-write). Capture `ToolInvocationContext` from `ProfileContext.current()` for `session_id` + `session_iteration` per C-TE-3.
- [ ] T043 [US-2/P2] Add MAX_ITERATIONS termination to `ReActLoop`: when `currentIteration.get() >= profile.settings().maxIterations()` and the last LLM response was a tool_call, return `LoopResult(lastText, currentIteration.get(), true, ...)`. Edge case (MAX_ITERATIONS=0) — see T047.
- [ ] T044 [US-2/P2] Add fail-fast empty-response handling per Edge case 4: if LLM response has both `text == null` and `toolCalls.isEmpty()`, return `LoopResult("model returned empty response", iter, false, ...)` instead of looping forever.
- [ ] T045 [US-2/P2] Run `ReActLoopToolChainTest` + `DefaultToolExecutorTest`; expected: all 8 tests green. Capture log to `evidence/T045-P2-green.log`
- [ ] T046 [US-2/P2] Quickstart §2 Daily Weather demo with stubbed `http_get` tool (using `FakeToolExecutor` returning canned weather); verify SC-001 + SC-004 against H2 + `tool_invocations` tables. Record evidence to `evidence/T046-P2-quickstart.log`
- [ ] T047 [US-2/P2] Add edge case unit: `ReActLoopMaxIterZeroTest` (Edge case 5) + `ReActLoopEmptyResponseTest` (Edge case 4); expected: 2 tests green
- [ ] T048 [US-2/P2] `git commit` with message `feat(core): implement US-2/P2 single Reason-Act-Observe cycle (tool dispatch + audit)`. Capture commit hash to `evidence/T048-P2-commit.txt`

**Checkpoint**: P2 demo (quickstart §2 daily weather) works end-to-end with stub Tool.

---

## Phase 5: [US-2/P3] Multi-Iteration Tool Chain (Priority: P3)

**Goal**: Loop iterates Reason → Act → Observe multiple times; concurrency isolation; SPEC-003 20-thread test; SPEC-004 100% audit coverage enforced.

**Independent Test**:
- `ReActLoopTerminationTest` — mock LLM that emits `tool_call` indefinitely; loop terminates at exactly `MAX_ITERATIONS` (SC-002)
- `ReActLoopMultiToolTest` — LLM emits 2 sequential tool_calls; loop completes with K+1 LLM calls + K Tool audit rows + messages `[user, assistant(tool_a), tool_a, assistant(tool_b), tool_b, assistant(text)]`
- `ReActLoopConcurrencyTest` — 20 concurrent `process()` invocations on same ApplicationContext; zero message bleed (SC-003); `llm_calls` + `tool_invocations` rows attributed correctly via `session_id`

### Tests for [US-2/P3] (write FIRST, must FAIL before implementation)

- [ ] T049 [P] [US-2/P3] Write `ReActLoopTerminationTest` in `oryxos-core/src/test/java/io/oryxos/core/ReActLoopTerminationTest.java` — uses a `FakeProviderService` whose queue has `MAX_ITERATIONS` tool_call responses pre-loaded; expected: `LoopResult(iter=10, terminatedAtMax=true)` (SC-002). Test must FAIL if T043's MAX guard is missing.
- [ ] T050 [P] [US-2/P3] Write `ReActLoopMultiToolTest` in `oryxos-core/src/test/java/io/oryxos/core/ReActLoopMultiToolTest.java` — covers spec US3 acceptance scenario 1 (3 LLM calls + 2 Tool calls + 6 messages in exact order). Should FAIL until `ReActLoop` handles sequential tool calls correctly.
- [ ] T051 [P] [US-2/P3] Write `ReActLoopConcurrencyTest` in `oryxos-core/src/test/java/io/oryxos/core/ReActLoopConcurrencyTest.java` — spins up Spring `ApplicationContext` once, fires 20 concurrent `ReActLoop.run(...)` calls each with its own `InMemorySession`, asserts (a) no exception, (b) each session ends with exactly 2 messages (its own user message + a synth assistant), (c) `ProfileContext.current()` returns `Optional.empty()` on a non-loop thread (verifies R-7 isolation).

### Implementation for [US-2/P3]

- [ ] T052 [US-2/P3] Verify that the existing `ReActLoop` from T040+ already handles sequential multi-tool dispatch correctly (per R-5 design — already done). If gaps surface in `ReActLoopMultiToolTest` (T050), fix in-place; otherwise no code change.
- [ ] T053 [US-2/P3] Run all US-2/P3 tests; expected: `Termination` + `MultiTool` + `Concurrency` tests all green. Capture to `evidence/T053-P3-green.log`
- [ ] T054 [US-2/P3] Quickstart §4 concurrency load test: launch 20 parallel CLI `chat small-talk` sessions against a single shared `ApplicationContext`; query SQLite: `SELECT COUNT(DISTINCT session_id) FROM llm_calls WHERE profile_name = 'small-talk'` should equal 20. Capture SQL query + result to `evidence/T054-concurrency.txt`
- [ ] T055 [US-2/P3] `git commit` with message `feat(core): implement US-2/P3 multi-iteration termination + 20-thread concurrency isolation`. Capture commit hash to `evidence/T055-P3-commit.txt`

**Checkpoint**: P3 demo (quickstart §3 daily digest, stubbed tools) works; SPEC-001/002/003/004 all verified end-to-end.

---

## Phase 6: [US-2/AG] AgentService Integration

**Purpose**: Wire the unified entry point that ties together ProfileContext + Profile lookup + ReActLoop. Without `AgentService`, three trigger sources (CLI/Web/Scheduler) cannot share the same loop behavior (FR-001 / FR-021).

- [ ] T056 [P] [US-2/AG] Create `ProfileRegistry` interface in `oryxos-core/src/main/java/io/oryxos/core/ProfileRegistry.java` with `Optional<Profile> find(String name);` + `Set<String> names();`
- [ ] T057 [P] [US-2/AG] Create `FilesystemProfileRegistry` stub in `oryxos-core/src/main/java/io/oryxos/core/FilesystemProfileRegistry.java` — scans `.oryxos/agents/*/AGENT.md` at startup, parses YAML frontmatter into `Profile` records; this is the minimum needed for US-2 (US-5 will swap in a registry backed by the SQLite `profiles` table once that exists)
- [ ] T058 [US-2/AG] Implement `DefaultAgentService` in `oryxos-core/src/main/java/io/oryxos/core/DefaultAgentService.java` per [contracts/AgentService.md](contracts/AgentService.md) §5 — Spring `@Service`; constructor injects `ProfileRegistry` + `ReActLoop`; `process(session, message)` sets `ProfileContext` in `try` + clears in `finally` (C-AS-2 / I-06)
- [ ] T059 [P] [US-2/AG] Write `DefaultAgentServiceTest` in `oryxos-core/src/test/java/io/oryxos/core/DefaultAgentServiceTest.java` — 4 tests per [contracts/AgentService.md §6](contracts/AgentService.md): `happyPath`, `unknownProfileThrows` (C-AS-3 / C-AS-4), `profileContextClearedOnException` (C-AS-2 / C-AS-5), `profileContextClearedOnSuccess` (C-AS-2). Expected: 4 green.
- [ ] T060 [P] [US-2/AG] Write `AgentServiceE2EIT` in `oryxos-core/src/test/java/io/oryxos/core/AgentServiceE2EIT.java` — full Spring Boot `@SpringBootTest` + `@ActiveProfiles("e2e")`; uses WireMock to stub deepseek (port 8081), runs one full `Daily Weather` flow through `DefaultAgentService`. Asserts:
  - `LlmCallRecord` count == 2
  - `ToolInvocationRecord` count == 1, `success=true`
  - Session messages length == 4
  - Captured to `evidence/T060-AgentServiceE2E-green.log`
- [ ] T061 [US-2/AG] Wire `FilesystemProfileRegistry` as the production `@Bean` (T058 constructor injection); add `@Configuration @Profile("!test")` to register it; add `@Bean @Primary` for an in-memory `ProfileRegistry` to be used by `DefaultAgentServiceTest`
- [ ] T062 [US-2/AG] Run all US-2 tests: `mvn -pl oryxos-core test -DfailIfNoTests=false`; expected: total ≥ 30 tests, all green; US-1 baseline (35/35) still passes when `mvn -pl oryxos-provider test` is rerun. Capture both to `evidence/T062-all-tests.log`
- [ ] T063 [US-2/AG] `git commit` with message `feat(core): implement US-2/AG AgentService unified entry point + ProfileContext lifecycle`. Capture commit hash to `evidence/T063-AG-commit.txt`

**Checkpoint**: `AgentService.process(...)` is the single entry point. SC-005 (daily weather via CLI) ready; SC-005 (via Web/Scheduler) deferred to US-5 spec.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Hardening, validation, deliverables. Required to satisfy Constitution §VII "Demo-First Delivery" + the per-US `git commit` + `/speckit-analyze` gates.

- [ ] T064 Run `mvn -pl oryxos-core,oryxos-storage,oryxos-provider,oryxos-boot -am clean verify`; expected: BUILD SUCCESS, ≥ 30 US-2 tests green + 35 US-1 tests green (zero regressions). Capture to `evidence/T064-final-verify.log`
- [ ] T065 [P] Run quickstart.md §1~§4 end-to-end against the real codebase (not just unit tests); capture each section's expected output + actual output to `evidence/T065-quickstart-§N.txt` files
- [ ] T066 [P] Re-run `/speckit-analyze` against the completed US-2 artifacts; document any critical/high findings and fix them or document deferral in `evidence/T066-analyze.md`
- [ ] T067 Code cleanup: remove unused imports across all US-2 files; ensure no `System.out.println` (use SLF4J); ensure all `record` classes declare `Objects.requireNonNull` for non-null fields in compact constructors (FR-018 invariant on records)
- [ ] T068 [P] Verification of Constitution compliance: re-walk [constitution.md §I..§VII](../.specify/memory/constitution.md) — confirm Complexity Tracking in [plan.md](plan.md) is still empty; no new module added; no new third-party Agent framework dep; `tool_invocations` day-one table now has rows from real runs (not just unit-test stubs)
- [ ] T069 [P] Translate `tasks.md` + key spec docs to Chinese (per project convention established in CLAUDE.md §2). Keep English section headings (so `/speckit-analyze` still matches anchors); translate prose. Skip code blocks.
- [ ] T070 [P] Update CLAUDE.md if any new patterns emerged (e.g., if the loop discovered a non-obvious gotcha worth documenting for future agents) — but **DO NOT** modify constitution.md (Constitution §V §5: "Constitution immutability")
- [ ] T071 [P] Run `git status` to confirm only expected files in changeset (no debug logs, no `.oryxos/sessions/*.db` from dev runs, no stale `.class` files captured); capture to `evidence/T071-pre-commit-status.txt`
- [ ] T072 [P] `git add specs/002-react-loop/evidence/` + any US-2 source files NOT yet committed; **do NOT** add `tasks.md` itself (kept unstaged for human review per Constitution V.5 per-US convention)
- [ ] T073 Per-US commit: ensure commits `T037`, `T048`, `T055`, `T063` exist; if any phase's commit failed silently, recreate it
- [ ] T074 (final) `git push origin 002-react-loop`; capture `git log origin/002-react-loop..002-react-loop --oneline` to `evidence/T074-push.txt`; open a PR against `main` if remote exists

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no internal dependencies; safe to start immediately after T001.
- **Foundational (Phase 2)**: depends on Phase 1 (T001..T004) completing cleanly. **CRITICAL** — every subsequent phase is blocked until T025 completes (foundation verify-build green).
- **User Story phases (3/4/5)**: each depends on Phase 2 completion. Within US-2, the three priority slices **must run sequentially in order P1 → P2 → P3** because they all write into the same `ReActLoop.run(...)` method body. (P1 lays the skeleton; P2 adds tool dispatch; P3 hardens termination + concurrency.) Parallelism at the story level is not safe here — different from the canonical template.
- **AgentService Integration (Phase 6)**: depends on Phase 5 (P3) because `DefaultAgentService.process` calls `ReActLoop.run` for the full path; P3's concurrency-test infrastructure (`@SpringBootTest` ApplicationContext) is reused as the boot foundation for `AgentServiceE2EIT`.
- **Polish (Phase 7)**: depends on all prior phases.

### Within Each Phase

- Tests FIRST (must FAIL before implementation), then impl, then re-run tests for green.
- Within a phase, tasks marked `[P]` may run in parallel; non-`[P]` tasks have a sequential dep.
- Foundation tasks touching *different files* are `[P]`; the move-and-rewrite tasks (T005/T007) form a chain (T007 depends on T005) and are not `[P]`.

### User Story Dependencies

- **[US-2/P1] Pure Reason**: independent; reuses the foundation interfaces (T009, T013, T014). No dependency on P2 or P3.
- **[US-2/P2] Single Tool**: depends on P1 (extends `ReActLoop.run`); uses `ToolExecutor` interface from P1.
- **[US-2/P3] Multi-Tool**: depends on P2 (further extends `ReActLoop.run`); introduces nothing new in `ReActLoop` itself, mostly adds tests + quickstart.
- **[US-2/AG] AgentService**: depends on P3 (full loop behavior ready); introduces `ProfileRegistry` + `FilesystemProfileRegistry` (no prior dependency).

### Critical Path

T001 → T002 → T005 → T007 → T008 → T017 → T019 → T025 (foundation build green)
  → T026 (failing test for P1) → T033 (PromptBuilder) → T034 (ReActLoop skeleton) → T036 (P1 green)
  → T038 (failing test for P2) → T040 (extend ReActLoop) → T043 (MAX_ITERATIONS guard) → T045 (P2 green)
  → T049 (failing test for P3) → T053 (P3 green)
  → T060 (AgentServiceE2E IT) → T062 (all tests pass)
  → T064 (final verify) → T066 (speckit-analyze) → T074 (push)

---

## Parallel Opportunities

Within a phase, `[P]` tasks may run on different files concurrently:

- **Phase 1**: T003, T004 — different Maven goals + different `pom.xml` lines; can be done in parallel.
- **Phase 2**: T006, T009, T010, T011, T012, T013, T014, T016, T018, T020, T021, T022, T023 — different `.java` files; can all be created in parallel (the **move** tasks T005/T007 must be sequential). Watch out: T008 (US-1 import sweep) should run after T007 to avoid double-import fix.
- **Phase 3**: T026, T027 (tests) can be parallel; T028..T032 (records/interfaces) can be parallel; T033+ are sequential because T033 depends on T029 (`ToolExecutor` interface), T034 depends on T028 (`Prompt`).
- **Phase 5**: T049/T050/T051 tests can all be parallel (different files).
- **Phase 6**: T056/T057 (ProfileRegistry + impl) + T059 (test) parallel; T058 depends on T056; T060 depends on T058.
- **Phase 7**: T065 (multiple quickstart runs), T066, T069, T070, T072 all `[P]`. T067, T068, T071 must precede T073/T074.

**Note on parallelism**: the project's coding convention is sequential one-developer-one-branch; parallelism in this section is for tooling-assisted fleet execution (e.g., multiple Sonnet instances editing different files simultaneously). The single-developer path runs these in the listed order.

---

## Parallel Example: User Story P2 (single Reason-Act-Observe)

A fleet could launch T038 + T039 (failing tests) in parallel; once red, run T040 + T041 + T042 + T043 + T044 (impl) sequentially within the same `ReActLoop.java` (so they're sequential, NOT parallel — same file). After green, T046 + T047 can be parallel (different demo + different edge-case test).

---

## Implementation Strategy

### MVP First (User Story P1 only)

1. Complete Phase 1 (Setup) — quick win
2. Complete Phase 2 (Foundational) — R-1 migration is the **costliest single step**; treat T005/T008 as a PR of their own
3. Complete Phase 3 (US-2/P1) — first end-to-end loop body
4. **STOP and VALIDATE**: Re-run `ReActLoopPureReasonTest`; manually run quickstart §1 against real DeepSeek
5. Demo the MVP: `chat small-talk` returns a single deterministic greeting; check `llm_calls` table

### Incremental Delivery (P1 → P2 → P3 → AG)

1. Setup → Foundation → P1 → P2 demo (daily weather, stub tool) → P3 demo (daily digest, stub tools) → AgentService → polish
2. Each step adds one capability, all earlier steps remain green.
3. Per-US `git commit` boundary (Constitution §III Per-US commit) — T037/T048/T055/T063 are the four key commit hashes to capture.

### Recommended Branching Strategy

- Single branch `002-react-loop` for all of US-2 (mirrors how `001-llm-provider-routing` worked)
- Per-US commits are intra-branch (not separate branches) — preserves linear history + per-US revert granularity
- PR opened at end (T074) for human review + merge to main

---

## Suggested MVP Scope

**Just Phase 3 [US-2/P1]** is sufficient for the in-PR review milestone. Beyond P1, P2/P3/AG each unlock a specific spec acceptance scenario:

| Story | Spec Mapping | Independent Demo |
|-------|-------------|------------------|
| P1 | spec US1 / SC-007 (logging) | "Hello" → 1 LLM call, 1 audit row |
| P2 | spec US2 / SC-001 / SC-005 / SC-004 | Weather bot: 2 LLM calls + 1 Tool call + 4 messages |
| P3 | spec US3 / SC-002 / SC-003 | Multi-tool + 20 concurrent threads |
| AG | spec FR-001 / FR-021 | Same bot triggered via 3 sources (CLI/Web/Scheduler) — CLI only in US-2 |

A natural release candidate includes all of P1+P2+P3+AG (Constitution §VII "Demo-First Delivery" requires end-to-end demo for "completed US").

---

## Notes

- The mandatory checksum: **(all tasks above + Constitution §I..§VII still PASS + 35 US-1 tests still green + ≥ 30 US-2 tests green + quickstart.md §1~§4 walked)**.
- Total task count: **74** (excluding Phase 7 overhead).
- Story breakdown:
  - Phase 1 (Setup): 4 tasks
  - Phase 2 (Foundational): 21 tasks
  - Phase 3 (US-2/P1 Pure Reason): 12 tasks
  - Phase 4 (US-2/P2 Single Tool): 11 tasks
  - Phase 5 (US-2/P3 Multi-Tool + Concurrency): 7 tasks
  - Phase 6 (US-2/AG AgentService): 8 tasks
  - Phase 7 (Polish): 11 tasks
- Total `[P]` parallelizable tasks: **22** (~30%).
- Format validation: every task follows `- [ ] TNNN [P] [Story] Description with file path` form; each `[Story]` matches a spec story or `[US-2/AG]` for cross-cutting.
- Pre-flight before any implementation: read [research.md §R-1](research.md) — the interface downward-migration is the highest-risk operation in this entire plan; budget at least half a day.
