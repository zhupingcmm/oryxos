<!--
Sync Impact Report
==================
Version change: 0.0.0 (template) → 1.0.0
Modified principles (template placeholder → concrete):
  - [PRINCIPLE_1_NAME] → I. Single-Stack Monolith (JDK 21 + Spring Boot 3.x)
  - [PRINCIPLE_2_NAME] → II. Core-Stage Scope Discipline (五大核心能力优先)
  - [PRINCIPLE_3_NAME] → III. Self-Implemented ReAct Loop
  - [PRINCIPLE_4_NAME] → IV. Spring AI Used at Half-Strength (禁用自动 tool 执行)
  - [PRINCIPLE_5_NAME] → V. Three-Tier Plugin Tooling
  - (added) PRINCIPLE_6 → VI. SQLite + MEMORY.md with Day-One Audit Persistence
  - (added) PRINCIPLE_7 → VII. Demo-First Delivery (跑通优先于完美)
Added sections:
  - Additional Constraints (硬约束/"不要做的事" from CLAUDE.md §18)
  - Development Workflow (Spec-Kit flow + commit gates from CLAUDE.md §10, §17)
Removed sections: none
Templates requiring updates:
  - .specify/templates/spec-template.md    ⚠ pending review (no edits required for v1.0.0; user-story format already compatible with 5 US in CLAUDE.md §10)
  - .specify/templates/plan-template.md     ⚠ pending review (Constitution Check gate already present; SPEC.md placeholder should reference CLAUDE.md §5 nine modules instead of generic options)
  - .specify/templates/tasks-template.md    ⚠ pending review (task phases already use User Story grouping, which matches our 5-US delivery order)
  - .specify/templates/checklist-template.md ✅ no change required
Follow-up TODOs:
  - None (all 7 principles concretely defined; no deferred placeholders)
-->

# OryxOS Constitution

> Enterprise Agent OS — Java/Spring Boot Runtime Kernel.
> The constitution supersedes all other practices; all spec.md / plan.md / tasks.md / code MUST comply.

## Core Principles

### I. Single-Stack Monolith (JDK 21 + Spring Boot 3.x)

OryxOS MUST be a single-binary application built on **JDK 21** and **Spring Boot 3.x**,
packaged as a Maven multi-module project with **exactly nine modules**:
`oryxos-core`, `oryxos-provider`, `oryxos-memory`, `oryxos-tool`, `oryxos-channel-cli`,
`oryxos-storage`, `oryxos-web`, `oryxos-cli`, `oryxos-boot`.
Deployment MUST be a single fat JAR via `java -jar` (extension stage may add GraalVM Native Image).
Any new module beyond these nine MUST be rejected as a structural change.

**Rationale**: regulated enterprises run homogeneous Java stacks (Nacos / Sentinel /
SkyWalking / Arthas / Prometheus+Grafana). A single-stack monolith matches their
operations, security, and observability tooling without requiring a new runtime.

### II. Core-Stage Scope Discipline (五大核心能力优先)

The core stage delivers **exactly the five core capabilities** and their engineering
foundation: (1) LLM Provider abstraction, (2) self-implemented ReAct loop, (3)
three-layer Memory, (4) Plugin Tool + Sandbox + Notify, (5) Web Service.
The following capabilities are **explicitly deferred to the extension stage** and
MUST NOT be implemented, designed-for, or even scaffold-stubbed in the core stage:

- Multi-tenancy
- SSO / authentication
- Full audit query UI
- Tool Policy engine
- Web dashboard
- Cluster high availability

**Rationale**: scope creep is the #1 risk for kernel projects. Defining the
extension boundary in the constitution makes "we'll do it later" a non-negotiable
"not in core", preventing accidental governance-layer leakage.

### III. Self-Implemented ReAct Loop

OryxOS MUST implement its own ReAct (Reason + Act) loop in Java, on top of
`PromptBuilder`, `ToolExecutor`, and `AgentService`. The loop MUST NOT depend on
Spring AI's Agent abstractions or any third-party Agent framework. Maximum
iteration count is **10** by default and MUST be overridable per Profile.

**Rationale**: third-party Agent loops make tool-call semantics, error handling,
and observability opaque. A hand-rolled loop in tens of lines of Java gives full
control over reasoning traces, audit hooks, and Profile overrides.

### IV. Spring AI Used at Half-Strength (禁用自动 tool 执行)

OryxOS MUST use Spring AI Alibaba for **only** the following:

- Provider abstraction over ChatModel implementations
- Protocol conversion between LLM wire formats and our internal message types
- `@Tool` schema generation (function-calling metadata)

OryxOS MUST **disable** Spring AI's automatic tool execution feature. Tool
dispatch MUST be controlled exclusively by `ReActLoop` + `ToolExecutor`.
**Symptom of violation**: the same tool is invoked twice per single user message.

**Rationale**: Spring AI's auto tool execution interleaves with our ReAct loop and
causes double-invocation. Disabling it keeps the audit trail clean (one
`tool_invocations` row per real invocation) and keeps iteration semantics under
our control.

### V. Three-Tier Plugin Tooling

Tool integration MUST support three access tiers, in priority order:

1. **Zero-code**: `AGENT.md` + `SKILL.md` + MCP server config (no Java code).
2. **Light-code**: custom MCP server in any language, declared in `mcp_servers.yaml`.
3. **Heavy-code**: Java `@Tool` bean implementing the `OryxTool` interface.

All Tool-related code MUST live in the **`oryxos-tool` module only**. The Tool
module MUST NOT be split further (no `builtin-tools` / `skill-tools` / `mcp-tools`
sub-modules). The `AGENT.md` / `AgentLoader` machinery belongs to `oryxos-core`'s
`ContextLoader` and MUST NOT be modelled as a Tool.

**Rationale**: regulated enterprises need to onboard Agents quickly with audit
review. Zero-code is the default; heavier tiers exist for edge cases. Keeping
all Tool code in one module prevents accidental privilege boundaries and makes
audit review one-stop.

### VI. SQLite + MEMORY.md with Day-One Audit Persistence

OryxOS MUST use **SQLite** (via Spring Data JPA) as the primary persistence layer,
**plus** plain-file `MEMORY.md` for the default long-term memory backend.
The five core tables (`sessions`, `tool_invocations`, `llm_calls`,
`scheduled_tasks`, `task_executions`) MUST exist from day one. The two audit
tables — **`tool_invocations`** and **`llm_calls`** — MUST be written to **on
every invocation / call**, not just logged. Vector retrieval and episodic
memory are deferred to the extension stage.

**Rationale**: day-one auditability is the differentiation against Node.js /
Python Agent OSes. Logs can be rotated, parsed only with effort, and aren't
queryable; a dedicated table is the only way an enterprise auditor will accept
"every tool call is recorded."

### VII. Demo-First Delivery (跑通优先于完美)

Each User Story MUST end with a runnable demonstration on real infrastructure
(real LLM, real cron, real webhook), not a unit-test-only green build. Demos
are the primary acceptance gate: an un-demoed US is not a completed US.
Running end-to-end MUST take priority over perfection (cleanups, edge cases,
optimization).

**Rationale**: kernel projects die from "code compiles but the system never
runs end-to-end." A demo forces the Agent OS to touch every layer (Provider,
ReAct, Tool, Memory, Web, Scheduler) in a single user-visible scenario.

## Additional Constraints (硬约束 — "不要做的事")

These constraints are non-negotiable. Violations MUST be flagged at code review
and reverted.

- MUST NOT use `java.lang.SecurityManager` (deprecated since JDK 17, unavailable in JDK 21).
  Use the application-layer `Sandbox` interface (`WhitelistSandbox` in core stage).
- MUST NOT hard-code API keys in Profile YAML or source. Use `${ENV_VAR}` placeholders,
  resolved at config load time.
- MUST NOT rely on `hibernate.ddl-auto=update` for table-structure evolution. SQLite's
  `ALTER TABLE` is limited; production-grade schema migration belongs to Flyway or
  Liquibase (extension stage may add this).
- MUST NOT select between `ChatModel` beans by container type scan. Provider dispatch
  MUST use an **explicit name → ChatModel** map. Multiple providers of the same type
  are a first-class use case, not an edge case.
- MUST NOT conflate Session memory with long-term memory. `MemoryService` is the
  unified facade; collapsing it loses the three-layer semantics.
- MUST NOT use non-JDK 21 language features (records, sealed types, pattern matching,
  virtual threads, sequenced collections are all required and assumed; preview
  features beyond what JDK 21 ships stable MUST be avoided).

## Development Workflow

1. **Spec-Kit pipeline**: every substantive change flows through
   `spec.md` → `plan.md` → `tasks.md` → implementation, in that order.
2. **Constitution check gate**: every `plan.md` MUST include a "Constitution
   Check" section that re-verifies compliance with all seven principles
   above. Plans that violate any principle MUST be revised or rejected.
3. **Per-US gate**: each User Story completion MUST trigger `/speckit.analyze`
   before merge. Skipping analyze is a process violation.
4. **Per-US commit**: each User Story MUST be committed as a separate commit
   on a feature branch, so the codebase can be reverted to any US boundary.
5. **Constitution immutability**: this constitution MUST NOT be modified by
   an AI agent acting on its own. Amendments require human review and a
   documented migration plan.
6. **Implementation order** is dependency-driven, not value-driven:
   US-1 (Provider) → US-2 (ReAct) → { US-3 (Memory) ∥ US-4 (Plugin Tool) } → US-5 (Web Service).

## Governance

- **Supremacy**: this constitution supersedes all other project practices,
  including CLAUDE.md guidance, README claims, and inline code comments. Where
  any document conflicts with the constitution, the constitution wins.
- **Versioning** follows semantic versioning:
  - **MAJOR** bump: backward-incompatible removal or redefinition of a principle,
    or governance change that invalidates prior decisions.
  - **MINOR** bump: new principle added, or materially expanded guidance.
  - **PATCH** bump: clarification, wording fix, typo, non-semantic refinement.
- **Amendment procedure**:
  1. Propose change as a PR against `constitution.md`, with rationale and impact analysis.
  2. PR MUST include a "Sync Impact Report" listing every dependent artifact
     (spec.md, plan.md, tasks.md, code modules) that needs to be updated.
  3. PR MUST be reviewed and approved by the project owner.
  4. After merge, propagate changes to all dependent artifacts in the same
     release commit; do not leave artifacts out of sync.
- **Compliance review**:
  - Every `plan.md` Constitution Check is a soft gate (catches violations early).
  - Every `/speckit.analyze` run is a hard gate (catches drift after the fact).
  - Every PR review MUST include an explicit "constitution compliance" line
    in the review summary.
- **Runtime guidance**: for daily development context, AI agents MUST read
  [CLAUDE.md](../../CLAUDE.md) (project memory) before opening any file. CLAUDE.md
  operationalizes this constitution; it does not override it.

**Version**: 1.0.0 | **Ratified**: 2026-07-22 | **Last Amended**: 2026-07-24
