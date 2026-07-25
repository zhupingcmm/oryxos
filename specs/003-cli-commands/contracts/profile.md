# Command Contract: `oryxos profile ...`

**Command group**: 零 Spring 启动（list / show）/ 文件 IO（create / delete）（[FR-005](../003-cli-commands/spec.md)）
**Spec section**: [US-3](../003-cli-commands/spec.md)

## 子命令

```
oryxos profile list
oryxos profile show <name>
oryxos profile create <name> --template <tpl>
oryxos profile delete <name> [--force]
```

## `oryxos profile list`

- **不启动 Spring**。
- 扫描 `.oryxos/agents/<name>/` 下所有 Profile，stdout 输出表格：

  ```
  NAME              DESCRIPTION                PROVIDER  TOOLS
  weather-bot       Daily weather notifier     deepseek  http_get, notify
  tech-digest       Daily tech news digest     qwen      read_file, notify
  ```

- 退出码：0；无 Profile 时输出 `(no profiles found)` 不报错。

## `oryxos profile show <name>`

- **不启动 Spring**。
- 打印 `.oryxos/agents/<name>/AGENT.md` 完整内容（YAML frontmatter + 正文）。
- 退出码：0 / 64（profile 不存在）。

## `oryxos profile create <name> --template <tpl>`

- **仅写文件系统**（**不**触 SQLite）。
- `--template <tpl>` 支持：`minimal` / `weather` / `tech-digest` / `github-pr-digest`。
- 写入 `.oryxos/agents/<name>/AGENT.md`（frontmatter + body）。
- **不覆盖**已存在 Profile（fail-fast + exit 64）。
- 退出码：0 / 64。

## `oryxos profile delete <name> [--force]`

- **仅写文件系统**（**不**触 SQLite）。
- 递归删除 `.oryxos/agents/<name>/`。
- 不存在 → 64；`--force` 缺失但 dir 非空 → 64 + 提示 `--force`。
- 退出码：0 / 64。

## 禁止行为

- ❌ 启动 Spring（[FR-005](../003-cli-commands/spec.md)）
- ❌ 触 SQLite（[FR-005](../003-cli-commands/spec.md)）
- ❌ 在 US-3 memory / US-4 tool 接管前允许其他隐式 CRUD 入口

## 测试要点

- 单元：list / show / create / delete 四子命令 happy path
- 单元：create 覆盖已存在 → 64
- 性能：list ≤ 200 ms（[SC-004](../003-cli-commands/spec.md)）
