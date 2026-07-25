# Command Contract Index: 12 Picocli 命令

> 本目录是 [spec.md US-3](../003-cli-commands/spec.md) 12 个 Picocli 命令的契约清单。
> 每条命令一个文件，按"启动行为分层（[FR-011 / FR-012](../003-cli-commands/spec.md)）"分组。

## 启动行为分组

| 组别 | 启动 Spring？ | 子命令 | 契约文件 |
|------|---------------|--------|----------|
| 零 Spring（文件 IO / SnakeYAML） | ❌ | `init` | [init.md](init.md) |
| 零 Spring（健康度报告） | ❌ | `status` | [status.md](status.md) |
| 零 Spring（Profile 目录扫描） | ❌ | `profile list` | [profile.md](profile.md) |
| 零 Spring（Profile 目录读） | ❌ | `profile show` | [profile.md](profile.md) |
| 零 Spring（Profile 目录写） | ❌ | `profile create` | [profile.md](profile.md) |
| 零 Spring（Profile 目录删） | ❌ | `profile delete` | [profile.md](profile.md) |
| 必须 Spring（驱动 ReAct） | ✅ | `chat` | [chat.md](chat.md) |
| 必须 Spring（DI 容器查询） | ✅ | `provider list` | [provider.md](provider.md) |
| 必须 Spring（DI 容器查询） | ✅ | `tool list` | [tool.md](tool.md) |
| 必须 Spring（SQLite 读） | ✅ | `session list` | [session.md](session.md) |
| US-5 占位 stub | ❌ | `serve` | [serve.md](serve.md) |
| US-5 占位 stub | ❌ | `gateway` | [serve.md](serve.md) |

12 个子命令 = 6 零 Spring + 5 必须 Spring + 1 stub × 2（serve / gateway 共享同一 stub 契约）。

## 退出码全局约定

| 退出码 | 含义 | 适用命令 |
|--------|------|----------|
| 0 | 成功 | 全部 |
| 1 | 通用失败 | 全部 |
| 2 | 警告（如 status 缺 API key） | `status` |
| 64 | EX_USAGE（参数错 / Profile 不存在） | `chat` / `profile` |
| 69 | EX_UNAVAILABLE（API key 缺） | `chat` / `status` |
| 78 | EX_CONFIG（YAML 解析失败） | `chat` |

## 共享输出约定

- **stdout 仅承载成功的命令输出**（[FR-010](../003-cli-commands/spec.md)）。
- **stderr 仅写错误摘要**（一行 + exit code），stack trace 走 `.oryxos/logs/oryxos-cli-error.log`（[FR-018](../003-cli-commands/spec.md)）。
- **API key 永不进 stdout / 日志**（[FR-020](../003-cli-commands/spec.md)）。
