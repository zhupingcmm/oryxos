# Command Contract: `oryxos serve` / `oryxos gateway`

**Command group**: US-5 占位 stub（[FR-008](../003-cli-commands/spec.md)）
**Spec section**: [Out of Scope](../003-cli-commands/spec.md)

> ⚠️ **本 US 不实现**。CLI 层只暴露 stub 入口，把 `serve` / `gateway` 留作 US-5 接管。

## 用法

```
oryxos serve [--port <p>]
oryxos gateway [--port <p>]
```

## 行为契约（stub）

1. 解析参数（**不**启动 Spring）。
2. stdout：单行 `not yet implemented (US-5)`。
3. 退出码：0（**不抛异常**）。
4. 解析到的参数写到 `System.getProperty("oryxos.cli.us5.placeholder")` 供未来 US-5 取用 —— 防止参数被无声吞掉。

## 禁止行为

- ❌ 启动 HTTP server（即便最小化）
- ❌ 抛异常或非零退出码
- ❌ 引导到 US-3 之前的简易实现

## 测试要点

- 单元：`oryxos serve --help` + `oryxos serve --port 8080` 均 exit 0
- 单元：`oryxos serve --bogus-flag` → exit 64（参数解析错误，非 stub 缺失）
