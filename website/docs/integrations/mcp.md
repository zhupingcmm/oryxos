---
title: MCP Integration
description: Model Context Protocol — client and server in OryxOS.
---

# MCP Integration

[Model Context Protocol (MCP)](https://modelcontextprotocol.io) is the open protocol for LLM ↔ tool integration. OryxOS uses MCP as one of its three tool extension tiers.

---

## What is MCP

MCP defines a standard JSON-RPC interface for tools. An MCP server exposes a set of tools (with schemas), and an MCP client discovers them and calls them. The transport is stdio (subprocess) or HTTP (long-poll / SSE).

The benefit: **tools written once in any language can be discovered and called by any MCP-compatible client**. OryxOS is the client. The community is the server author.

---

## OryxOS as MCP client

### Configure MCP servers

`.oryxos/mcp_servers.yaml`:

```yaml
servers:
  - name: github
    transport: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-github"]
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}

  - name: filesystem
    transport: http
    url: http://localhost:3001/mcp
    headers:
      X-API-Key: ${FILESYSTEM_API_KEY}

  - name: weather
    transport: stdio
    command: /usr/local/bin/weather-mcp
    args: []
```

### Reference in AGENT.md

```yaml
mcp_servers:
  - github
  - weather
```

When the Agent boots, the runtime:

1. Spawns stdio subprocesses (or opens HTTP connections).
2. Calls `tools/list` to discover each server's tools.
3. Wraps each tool in a `McpToolAdapter` (which implements `OryxTool`).
4. Registers them in `ToolRegistry` under namespaced names: `github.list_repos`, `weather.get_current`, etc.

### Tool calls

From the Agent's perspective, MCP tools look exactly like built-in tools:

```text
[agent] ▸ tool call: github.list_repos({"owner": "oryxos", "limit": 10})
[agent] ▸ tool call: weather.get_current({"city": "Shanghai"})
```

`ReActLoop` doesn't know or care that these came from MCP. `ToolExecutor` enforces the sandbox and writes the audit row.

---

## OryxOS as MCP server (extension stage)

In the **extension stage**, OryxOS will expose its own tools as an MCP server. External MCP clients (Claude Desktop, Cursor, custom agents) can then call OryxOS tools without going through the OryxOS REST API.

```
http://<oryxos-host>:<port>/mcp
```

This is the integration that lets an enterprise's existing AI tooling (Cursor, Claude Desktop) call in-house business Agents running on OryxOS.

**Status**: planned for extension stage. Not in core.

---

## Sandbox interaction

MCP tools run in their own subprocesses (stdio) or remote processes (HTTP). They have whatever permissions those processes have.

`ToolRegistry` can be configured to enforce the sandbox **before** the call is forwarded to MCP — this catches obvious violations (e.g., a "github" tool trying to read `/etc/passwd`). But MCP tools that genuinely need network access (like `github.fetch_url`) will see their requests go through.

> ⚠️ Installing an MCP server means **trusting its author**. Audit every tool the server exposes. The core stage does not isolate MCP subprocesses — that's container / microVM in the extension stage.

---

## Examples

### Calling GitHub via MCP

```yaml
# .oryxos/mcp_servers.yaml
servers:
  - name: github
    transport: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-github"]
    env:
      GITHUB_TOKEN: ${GITHUB_TOKEN}
```

```yaml
# .oryxos/agents/code-review/AGENT.md frontmatter
name: code-review
mcp_servers:
  - github
tools:
  - github.list_repos
  - github.search_code
  - github.create_issue
```

The Agent can now use `github.list_repos` like any built-in tool.

### Local filesystem MCP

```yaml
# .oryxos/mcp_servers.yaml
servers:
  - name: filesystem
    transport: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-filesystem", "/data/corp-docs"]
```

The filesystem MCP exposes `read_file`, `write_file`, `list_directory` — all scoped to `/data/corp-docs`. OryxOS registers them as `filesystem.read_file`, etc.

---

## What's not in core stage

- ❌ OryxOS as MCP server (extension stage)
- ❌ MCP subprocess isolation (extension stage — container / microVM)
- ❌ Tool Policy (which MCP tools a profile can call) — extension stage

---

## Where to go next

| Destination                                                  | What you'll find                                       |
| ------------------------------------------------------------ | ------------------------------------------------------ |
| [Spring AI integration](./spring-ai)                          | How OryxOS uses Spring AI                              |
| [LangChain4j integration](./langchain4j)                     | Community-stage alternative                            |
| [Features — MCP integration](../features#mcp-integration)   | How MCP tools join the registry                        |