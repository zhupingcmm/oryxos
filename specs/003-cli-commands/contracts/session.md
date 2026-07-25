# Command Contract: `oryxos session list`

**Command group**: 必须启动 Spring（[FR-012](../003-cli-commands/spec.md)）
**Spec section**: [US-3](../003-cli-commands/spec.md)

## 用法

```
oryxos session list [--limit N] [--profile <name>] [--format table|json]
```

| 参数 | 必需 | 含义 |
|------|------|------|
| `--limit <N>` | 可选 | 最多列出 N 条；默认 20 |
| `--profile <name>` | 可选 | 仅列指定 Profile 的 Session |
| `--format <fmt>` | 可选 | 输出格式；默认 `table` |

## 行为契约

1. **启动 Spring Context**（[FR-007](../003-cli-commands/spec.md)）—— 拿到 `SessionRepository`。
2. 按 `updated_at` 倒序，列出最近 N 条。
3. **stdout**：

   ```
   ID                                   PROFILE        MESSAGES  UPDATED_AT
   9a8178c6-...                         weather-bot    14        2026-07-25 16:20
   7f8265ea-...                         tech-digest    8         2026-07-25 15:00
   ```

4. **退出码**：0 / 1（Spring 启动失败 / SQLite 不可达）。

## 禁止行为

- ❌ 输出 message content（仅 metadata）
- ❌ 启动后忘记 close `ConfigurableApplicationContext`

## 测试要点

- 单元：CommandLine `--help`
- 集成：5 条 Session seed → list → 验证倒序 + 列数
