---
title: MCP 集成
description: Model Context Protocol——OryxOS 里的客户端和服务端。
---

# MCP 集成

[Model Context Protocol (MCP)](https://modelcontextprotocol.io) 是 LLM ↔ tool 集成的开放协议。OryxOS 用 MCP 作为三档 tool 扩展之一。

---

## MCP 是什么

MCP 定义了 tool 的标准 JSON-RPC 接口。MCP server 暴露一组 tool（带 schema），MCP client 发现它们并调用。传输是 stdio（子进程）或 HTTP（长轮询 / SSE）。

好处：**用任何语言写一次 tool，所有 MCP 兼容的 client 都能发现和调用**。OryxOS 是 client。社区是 server 作者。

---

## OryxOS 当 MCP client

### 配置 MCP server

`.oryxos/mcp_servers.yaml`：

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

### AGENT.md 引用

```yaml
mcp_servers:
  - github
  - weather
```

Agent 启动时，运行时：

1. 起 stdio 子进程（或开 HTTP 连接）。
2. 调 `tools/list` 发现每个 server 的 tool。
3. 把每个 tool 包到 `McpToolAdapter`（实现 `OryxTool`）。
4. 注册到 `ToolRegistry`，命名空间化的名字：`github.list_repos`、`weather.get_current` 等。

### Tool 调用

从 Agent 角度看，MCP tool 跟内置 tool 一模一样：

```text
[agent] ▸ tool call: github.list_repos({"owner": "oryxos", "limit": 10})
[agent] ▸ tool call: weather.get_current({"city": "Shanghai"})
```

`ReActLoop` 不知道也不在乎这些来自 MCP。`ToolExecutor` 强制沙箱，写审计行。

---

## OryxOS 当 MCP server（扩展阶段）

在**扩展阶段**，OryxOS 会把自己的 tool 暴露成 MCP server。外部 MCP client（Claude Desktop、Cursor、自定义 agent）就能调 OryxOS tool，不用走 OryxOS REST API。

```
http://<oryxos-host>:<port>/mcp
```

这是让企业内部现有 AI 工具（Cursor、Claude Desktop）调 OryxOS 上跑的内部业务 Agent 的集成方式。

**状态**：扩展阶段规划。核心阶段不做。

---

## 沙箱交互

MCP tool 在自己的子进程（stdio）或远程进程（HTTP）里跑。它们有那些进程的所有权限。

`ToolRegistry` 可以配置成在调用转发给 MCP 之前**强制沙箱**——这能抓住明显违规（比如一个 "github" tool 想读 `/etc/passwd`）。但真正需要网络访问的 MCP tool（比如 `github.fetch_url`）的请求会通过。

> ⚠️ 装一个 MCP server = **信任它的作者**。审计 server 暴露的每个 tool。核心阶段不隔离 MCP 子进程——容器 / microVM 是扩展阶段。

---

## 例子

### 通过 MCP 调 GitHub

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

Agent 现在能像用内置 tool 一样用 `github.list_repos`。

### 本地文件系统 MCP

```yaml
# .oryxos/mcp_servers.yaml
servers:
  - name: filesystem
    transport: stdio
    command: npx
    args: ["-y", "@modelcontextprotocol/server-filesystem", "/data/corp-docs"]
```

filesystem MCP 暴露 `read_file`、`write_file`、`list_directory`——都限定在 `/data/corp-docs`。OryxOS 把它们注册成 `filesystem.read_file` 等。

---

## 核心阶段不做

- ❌ OryxOS 当 MCP server（扩展阶段）
- ❌ MCP 子进程隔离（扩展阶段——容器 / microVM）
- ❌ Tool Policy（profile 能调哪些 MCP tool）——扩展阶段

---

## 下一步

| 目标                                                         | 看到什么                                          |
| ------------------------------------------------------------ | ------------------------------------------------- |
| [Spring AI 集成](./spring-ai)                                 | OryxOS 怎么用 Spring AI                           |
| [LangChain4j 集成](./langchain4j)                             | 社区阶段替代方案                                  |
| [功能特性 — MCP 集成](../features#mcp-集成)                  | MCP tool 怎么进 registry                          |