# Command Contract: `oryxos init`

**Command group**: 零 Spring 启动（[FR-011](../003-cli-commands/spec.md)）
**Spec section**: [US-2](../003-cli-commands/spec.md)

## 用法

```
oryxos init [--workspace <path>]
```

| 参数 | 必需 | 含义 |
|------|------|------|
| `--workspace <path>` | 可选 | 工作区根目录；省略时用当前目录的 `.oryxos/` |

## 行为契约

1. **不启动 Spring**（[FR-003](../003-cli-commands/spec.md)）。
2. **检查** `.oryxos/` 是否已存在：
   - 不存在 → 创建完整工作区（4 目录 + 5 文件 + 1 SQLite db）：
     ```
     .oryxos/
     ├── agents/                # 空目录
     ├── memory/                # 含 MEMORY.md 模板
     ├── sessions/              # 空目录
     ├── logs/                  # 空目录
     ├── mcp_servers.yaml       # 含最小骨架
     ├── AGENTS.md              # Bootstrap: 项目级 agent 行为说明
     ├── SOUL.md                # Bootstrap: 默认 agent 人格
     ├── USER.md                # Bootstrap: 用户偏好
     └── oryxos.db              # 初始化（schema 走 US-1/2 day-one）
     ```
   - 存在 → 报 `Already initialized at <realpath>` + exit 1（[FR-003](../003-cli-commands/spec.md)）。
3. **幂等**：[A-006](../003-cli-commands/spec.md) 二次运行**不**覆盖任何文件。
4. **stdout**：列出创建的文件清单（一行一项）。
5. **退出码**：

   | 场景 | 退出码 |
   |------|--------|
   | 成功 | 0 |
   | `.oryxos/` 已存在 | 1 |
   | 路径不可写 | 1 |
   | `oryxos.db` 初始化失败 | 1 |

## 禁止行为

- ❌ 启动 Spring（[FR-003](../003-cli-commands/spec.md)）
- ❌ 覆盖已存在文件（[A-006](../003-cli-commands/spec.md)）
- ❌ 跟随 symlink（`LinkOption.NOFOLLOW_LINKS`）

## 测试要点

- 单元：空目录跑 → 4 目录 5 文件 1 db；二次跑 → 报已初始化
- 单元：跨设备 mount（用 `Files.createSymbolicLink` mock）→ 不跟随
