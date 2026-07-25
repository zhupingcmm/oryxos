# Command Contract: `oryxos provider list`

**Command group**: 必须启动 Spring（[FR-012](../003-cli-commands/spec.md)）
**Spec section**: [US-3](../003-cli-commands/spec.md)

## 用法

```
oryxos provider list [--format table|json]
```

## 行为契约

1. **启动 Spring Context**（[FR-006](../003-cli-commands/spec.md)）—— 拿到 `ProviderService.allProviders()` 的实际列表。
2. **stdout**：

   ```
   NAME        MODEL                  API_KEY_RESOLVED  TEMPLATE
   deepseek    deepseek-chat          true
   qwen        qwen-turbo             false             → check QWEN_API_KEY
   kimi        moonshot-v1-8k         true
   ```

3. **退出码**：0 / 1（Spring 启动失败）。

## 禁止行为

- ❌ 容器类型扫描拿 `ChatModel` Bean（[Constitution §IV](../../.specify/memory/constitution.md)）
- ❌ 在 stdout 打印 API key 明文（[FR-020](../003-cli-commands/spec.md)）—— 仅显示 `true` / `false`

## 测试要点

- 单元：CommandLine `--help` 含 `--format`
- 集成：WireMock + 3 个 Provider（含 1 个未配 key）→ 验证表行
