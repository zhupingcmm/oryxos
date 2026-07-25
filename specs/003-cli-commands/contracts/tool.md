# Command Contract: `oryxos tool list`

**Command group**: 必须启动 Spring（[FR-012](../003-cli-commands/spec.md)）
**Spec section**: [US-3](../003-cli-commands/spec.md)

## 用法

```
oryxos tool list [--format table|json] [--filter builtin|mcp|custom]
```

## 行为契约

1. **启动 Spring Context**（[FR-006](../003-cli-commands/spec.md)）—— 拿到 `ToolRegistry.all()` 的实际列表（含内建 + MCP + 自定义）。
2. **stdout**：

   ```
   NAME              KIND     SOURCE                SANDBOX_REQUIRED
   http_get          builtin  oryxos-tool           yes (HTTP_REQUEST)
   notify            builtin  oryxos-tool           yes (HTTP_REQUEST)
   shell             builtin  oryxos-tool           yes (SHELL_COMMAND)
   mcp_github_search mcp      oryxos-mcp-github     yes (HTTP_REQUEST)
   ```

3. **退出码**：0 / 1（Spring 启动失败）。

## 禁止行为

- ❌ 启动 Tool Bean 调用 / 测试 —— 本命令只**列**（read-only）
- ❌ 启动 MCP client 连接

## 测试要点

- 单元：CommandLine `--help` 含 `--format` + `--filter`
- 集成：US-4 接入真实 Tool 后，验证 list 输出与 `ToolRegistry` 同步
