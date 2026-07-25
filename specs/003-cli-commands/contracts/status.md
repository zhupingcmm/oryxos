# Command Contract: `oryxos status`

**Command group**: 零 Spring 启动（[FR-011](../003-cli-commands/spec.md)）
**Spec section**: [US-2](../003-cli-commands/spec.md)

## 用法

```
oryxos status [--format table|json] [--verbose]
```

| 参数 | 必需 | 含义 |
|------|------|------|
| `--format <fmt>` | 可选 | 输出格式；默认 `table`（人类可读） |
| `--verbose` | flag | 展示更多细节（如 SQLite 表行数、Profile 的工具列表） |

## 行为契约

1. **不启动 Spring**（[FR-004](../003-cli-commands/spec.md)）。
2. **报告内容**（[FR-004](../003-cli-commands/spec.md)）：
   - JVM / JDK / OS 版本
   - `.oryxos/` 绝对路径（`Path.toRealPath(LinkOption.NOFOLLOW_LINKS)`）
   - 已发现 Profile 数
   - Provider 配置矩阵（name / model / `api_key_resolved: true|false`）
   - MCP server 数
   - Spring Context 可启动性（**仅**检查不启动 —— 用 `OryxosApplication.main(args)` 试启动 3 秒超时）
3. **stdout**：人类可读表格（或 JSON 视 `--format`）。
4. **退出码分级**（[SC-007](../003-cli-commands/spec.md)）：

   | 健康度 | 退出码 |
   |--------|--------|
   | 全绿（所有 Provider API key resolved） | 0 |
   | Warning（如有 Provider API key 未 resolved） | 2 |
   | Error（如 `.oryxos/` 不存在 / SQLite 无法打开） | 1 |

## 禁止行为

- ❌ 启动 Spring（[FR-004](../003-cli-commands/spec.md)）
- ❌ 在 stdout / 日志打印 API key（[FR-020](../003-cli-commands/spec.md)）—— 展示前 4 位 + `...` 掩码

## 测试要点

- 单元：空目录 → Error + exit 1
- 单元：完整 `.oryxos/` + 全绿 Provider → exit 0
- 单元：缺一个 API key → Warning + exit 2
- 性能：≤ 200 ms 首输出（[SC-003](../003-cli-commands/spec.md)）
