---
title: Roadmap
description: Core stage → Extension stage → Community stage.
---

# Roadmap

OryxOS delivery is staged. **The core stage is the runtime kernel — a foundation, not a complete enterprise product.** The differentiated governance layer is the end state, built on top of the core kernel.

---

## Phase 1 — Core Stage (in progress)

**Target**: a runnable Agent OS runtime kernel with the 5 core capabilities, demonstrated by 3 daily-running end-to-end demos.

**Timeline**: 4 weeks × 3 hours.

| Week | Focus | Demonstrable result |
|------|-------|--------------------|
| 1 | LLM Provider + ReAct loop | `oryxos chat` multi-turn + Agent calls HTTP tool |
| 2 | Memory + Tool system | Agent remembers preferences; calls local files + external MCP |
| 3 | Web Service | External systems invoke OryxOS via 10 REST endpoints |
| 4 | Multi-Agent + engineering | Multiple agents coexist; Session persists across restart; scheduled jobs |

### 5 user stories

| US | Capability | Demo |
|----|------------|------|
| US-1 | LLM Provider | (with US-2) |
| US-2 | ReAct Loop | Demo 1 (Daily Weather) |
| US-3 | Memory | Demo 2 (Daily Tech Digest) |
| US-4 | Tool + Sandbox | Demo 3 (Daily GitHub Digest) |
| US-5 | REST API | Demo 4/5 (sync + multi-endpoint) |

Each user story ends with `/speckit.analyze` — drift prevention is mandatory.

### 3 demos

- **Demo 1 — Daily Weather**: HTTP + Notify + Scheduler.
- **Demo 2 — Daily Tech Digest**: Memory + MCP + read_file.
- **Demo 3 — Daily GitHub Digest**: Shell + script sandbox + Memory.

All three run **clock-push** but also support **manual trigger** (CLI or REST), proving all three trigger paths share the same `AgentService` chain.

---

## Phase 2 — Extension Stage

Production-grade capabilities layered on the core kernel. **The core stage is the foundation, not the product.** This phase builds the differentiated governance layer.

### Authentication & Multi-tenancy

- SAML / OIDC SSO
- Three-level tenant model (Org → Workspace → Project)
- RBAC down to Agent / Tool / Skill granularity
- Per-tenant rate limits and quotas

### Full Audit & Traceability

- Audit query REST API
- Trace IDs propagated through LLM and tool calls
- SIEM export (Splunk / ELK / OpenTelemetry)
- Configurable retention policy + cold storage archive

### Web Dashboard

- Profile management UI (CRUD on `AGENT.md`)
- Session browser (search / replay / export)
- Audit query UI (filters by date / profile / tool / success)
- Real-time monitoring (calls / minute, tokens / minute, error rate)

### Tool Policy

- Profile-level allow / deny rules
- Time-of-day restrictions
- Per-tool argument validation (regex / schema)
- Quota enforcement

### Sandboxing upgrade

- Container isolation: namespace + cgroups + seccomp
- MicroVM isolation: Firecracker / Kata Containers / gVisor
- **Same `Sandbox` interface** — bean swap

### Vector Memory

- Pluggable vector backend: LanceDB Java / pgvector / JVector (TBD)
- Semantic search replaces keyword-only `recallByKeyword`
- Still respects CORE / ARCHIVE scopes

### Adaptive Routing

- Fallback (provider A → provider B on timeout)
- Hedge racing (parallel call, take first response)
- Circuit breaker per provider
- Cost-aware routing (prefer cheap provider for trivial queries)

### Cluster HA

- Multi-node deployment via Nacos / ETCD
- Leader election for `AgentScheduler`
- Session replication (or sticky routing)
- Rolling upgrade with zero downtime

---

## Phase 3 — Community Stage

Open-ended, community-driven. None of these are committed — they emerge from contributor interest.

### IM Channels

- WeCom, Feishu, DingTalk, Slack
- Bidirectional (inbound commands + outbound notifications)

### Skills Marketplace

- Compatible with [agentskills.io](https://agentskills.io)
- Curated index of community-contributed `AGENT.md` + `skills/`

### Multi-language SDKs

- Python SDK (community-led)
- TypeScript SDK (community-led)
- Go SDK (community-led)

### Visual Profile Editor

- WYSIWYG editor for `AGENT.md`
- Live preview of the rendered system prompt
- Tool palette for drag-and-drop tool selection

### Kubernetes Operator

- Declarative `OryxOSCluster` CRD
- Helm chart for production deployment
- Auto-scaling based on queue depth

### Mobile Admin Console

- iOS / Android app for on-the-go monitoring
- Push notifications on critical errors

### Multi-region Deployment

- Cross-region Session replication
- Geo-routing of Agent invocations

---

## How to contribute

Pick an item from any phase, open an issue, propose a design, ship a PR.

- 🐛 Bug reports: open an issue with reproduction steps
- 💡 Feature requests: discuss in issues before opening a PR
- 🔧 Pull requests: fork, implement against an active user story, add tests, run `mvn verify`
- 📖 Docs: typos, clarifications, examples in `docs/`
- 🧩 Plugins: new MCP servers, new Provider adapters, new Tools

---

## Where to go next

| Destination                              | What you'll find                                       |
| ---------------------------------------- | ------------------------------------------------------ |
| [Quick Start](./quick-start)            | Run three demo Agents locally                          |
| [Architecture](./architecture)           | Layer-by-layer walkthrough                             |
| [Constitution](./constitution)           | The seven non-negotiable principles                    |
| [GitHub Discussions](https://github.com/oryxos/oryxos/discussions) | Discuss the roadmap |