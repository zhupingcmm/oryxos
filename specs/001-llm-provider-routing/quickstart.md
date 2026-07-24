# 快速验证：LLM Provider 路由

**日期**：2026-07-24
**目的**：用最少步骤让开发者从零跑通"按名路由 + 审计写入 + 多 Provider 共存"端到端 demo
**关联**：[spec.md](./spec.md) 全部 SC、[research.md](./research.md) R-07（DeepSeek + Qwen + MiniMax 三 Provider）
**预计耗时**：20 分钟（不计 LLM 真实调用延迟）

---

## 0. 前置条件

```bash
# 工具
java -version        # 21+
mvn -version         # 3.9+

# 凭证（三套独立 API key）
export DEEPSEEK_API_KEY="sk-你的 deepseek key"
export QWEN_API_KEY="sk-你的 qwen key（DashScope OpenAI 兼容端点）"
export MINIMAX_API_KEY="sk-你的 MiniMax key"
```

**注意**：三个 key 来自**三家不同供应商**（DeepSeek / Qwen / MiniMax），目的是同时验证"多 Provider 共存"和"不同供应商并存"两个价值点。

---

## 1. 准备 `application.yml`

在 `oryxos-boot/src/main/resources/application.yml` 加入：

```yaml
oryxos:
  providers:
    - name: deepseek
      model: deepseek-chat
      credentialRef: ${DEEPSEEK_API_KEY}
      options:
        temperature: 0.5

    - name: qwen
      model: qwen-plus
      credentialRef: ${QWEN_API_KEY}
      options:
        temperature: 0.5

    - name: minimax
      model: MiniMax-M3
      credentialRef: ${MINIMAX_API_KEY}
      options:
        temperature: 0.5
```

启动期校验会在 `DEEPSEEK_API_KEY` / `QWEN_API_KEY` / `MINIMAX_API_KEY` 任一未设置时 fail-fast。

---

## 2. 准备 Profile

创建 `.oryxos/agents/route-demo/AGENT.md`：

```markdown
---
name: route-demo
description: 演示 Provider 路由的 Agent
provider:
  name: deepseek
  model: deepseek-chat
  temperature: 0.3
identity:
  agent_name: 路由演示员
  prompt: |
    只回一句话：用一句中文告诉我今天是什么天气感觉。
    不要主动调任何工具。
---

只回一句话：用一句中文告诉我今天什么天气感觉。不要主动调任何工具。
```

> 关键：Profile 里**没**写 `tools` 列表，所以 LLM 不会试图调任何工具——US-5（"工具翻译"）的"零工具"路径也自然通过验收。

---

## 3. 编译并启动

```bash
cd d:/code/java/oryxos
mvn -pl oryxos-boot -am clean package -DskipTests
java -jar oryxos-boot/target/oryxos.jar
```

启动日志应出现：
```
Started OryxOsApplication in X.XXX seconds
ProviderRegistry: 2 providers registered [deepseek, qwen]
```

如果出现 `IllegalStateException: Unknown provider: ...` 或 `PlaceholderResolutionException: ...DEEPSEEK_API_KEY` —— 回到第 0/1 步检查环境变量。

---

## 4. 跑一次端到端调用

```bash
# 通过 REST 端点触发（端点定义在 US-5 阶段，本 quickstart 假定它已存在）
curl -X POST http://localhost:8080/api/v1/agents/route-demo/invoke \
  -H "Content-Type: application/json" \
  -d '{"message":"跑一下"}'
```

期望响应：
```json
{
  "text": "今天天气感觉……",
  "toolCalls": [],
  "usage": {"promptTokens": ..., "completionTokens": ...}
}
```

---

## 5. 验证 `llm_calls` 审计行（验收 SC-001 / SC-002）

```bash
# 查最近 5 条审计行
sqlite3 .oryxos/oryxos.db "SELECT id, profile_name, provider, model, success, error_message, prompt_tokens, completion_tokens, duration_ms, timestamp FROM llm_calls ORDER BY timestamp DESC LIMIT 5;"
```

**期望看到**：

| 字段 | 期望值 |
|------|--------|
| `profile_name` | `route-demo` |
| `provider` | `deepseek`（**不是** `qwen` / `minimax`） |
| `model` | `deepseek-chat` |
| `success` | `1`（true） |
| `error_message` | `NULL` |
| `prompt_tokens` / `completion_tokens` | 非空 |
| `duration_ms` | `> 0` |

**这就通过了 SC-001**（100% 路由到 deepseek 而不是 qwen / minimax）。

---

## 6. 验证失败路径（验收 SC-002）

```bash
# 临时把 MINIMAX key 改成空字符串
export MINIMAX_API_KEY=""

# 修改 application.yml 把 minimax 的 credentialRef 改到一个不存在的环境变量
# （或者在 Profile 里临时把 provider.name 改成 minimax 跑一次再改回 deepseek）

# 再次跑调用
curl -X POST http://localhost:8080/api/v1/agents/route-demo/invoke -d '{"message":"x"}'

# 期望：HTTP 500 + 错误体含 "auth" / "credential" / "401" 之一
```

**然后**：

```bash
sqlite3 .oryxos/oryxos.db "SELECT provider, success, error_message FROM llm_calls ORDER BY timestamp DESC LIMIT 1;"
```

**期望**：

| 字段 | 期望值 |
|------|--------|
| `provider` | `minimax`（或你 Profile 改后路由到的那个） |
| `success` | `0`（false） |
| `error_message` | 非空，且包含凭证相关关键词 |

**这就通过了 SC-002**（失败也落审计，零静默失败）。

---

## 7. 验证多 Provider 切换（验收 SC-004）

依次在 3 个 Provider 之间切换 Profile，各跑一次，验证归属正确。

```bash
# Profile 改一下：provider.name: deepseek → qwen → minimax → deepseek（3 轮循环）
for name in qwen minimax deepseek; do
  sed -i "s/name: deepseek/name: $name/" .oryxos/agents/route-demo/AGENT.md
  java -jar oryxos-boot/target/oryxos.jar > /dev/null 2>&1
  curl -s -X POST http://localhost:8080/api/v1/agents/route-demo/invoke -d '{"message":"x"}' > /dev/null
  sqlite3 .oryxos/oryxos.db "SELECT provider FROM llm_calls ORDER BY timestamp DESC LIMIT 1;"
done
```

**期望**：三次循环输出依次是 `qwen` / `minimax` / `deepseek`（最后一次保持 deepseek）。**3 家独立供应商共存成立**。

---

## 8. 验证热切换模型（验收 SC-003）

```bash
# 不改 provider.name，只改 provider.model
sed -i 's/model: deepseek-chat/model: deepseek-coder/' .oryxos/agents/route-demo/AGENT.md

# 重启 + 调用
java -jar oryxos-boot/target/oryxos.jar
curl -X POST http://localhost:8080/api/v1/agents/route-demo/invoke -d '{"message":"x"}'

# 查最近一条
sqlite3 .oryxos/oryxos.db "SELECT provider, model FROM llm_calls ORDER BY timestamp DESC LIMIT 1;"
```

**期望**：`provider='deepseek'`（不变），`model='deepseek-coder'`（变了）。**SC-003 通过**。

---

## 9. 验证工具翻译（验收 SC-005 / SC-006）

给 Profile 加一个 `tools` 列表：

```yaml
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - http_get          # 内置 HTTP 工具
  - notify            # 内置通知工具
identity:
  ...
prompt: |
  你的任务：根据用户问题决定要不要调 http_get 抓取网页。
  ...
```

跑一次调用（问题里给个 URL 让 LLM 决定调工具），然后：

```bash
# 验证请求出去时确实带了 2 个 tool schema：在 LLM SDK debug 日志里搜 "tools"
# 或在 Provider 层加一个临时 println（实现阶段会加，正式版可以由 SC-005 单元测试断言）
```

**SC-006 的硬验证**（不变量级）：在测试里加一个 mock `OryxTool.execute` 计数器，跑一次 `ProviderService.invoke`，断言计数器为 0。这是 US-5 的"绝不执行工具"硬约束。

---

## 10. 验收清单

完成上面 1~9 步后，对照打勾：

- [ ] **SC-001**：Profile 声明 `provider.name: deepseek` → 100% 审计行 `provider='deepseek'`
- [ ] **SC-002**：凭证错误时 → 失败仍写入 `llm_calls`，`success=false`，`error_message` 非空
- [ ] **SC-003**：改 `provider.model` → 重启后审计行反映新 model
- [ ] **SC-004**：DeepSeek + Qwen + MiniMax 切换 → 各自审计行归属正确
- [ ] **SC-005**：Profile 声明 N 个工具 → LLM 请求恰好 N 个 tool schema
- [ ] **SC-006**：Provider 调用期间 `OryxTool.execute` 计数 = 0
- [ ] **SC-007**：5 分钟内完成新 Provider 配置（写 yaml + 设 env + 重启）

7 项全打勾 = US-1 验收通过，可以进 US-2（ReAct 循环）。

---

## 11. 故障排查

| 症状 | 排查方向 |
|------|----------|
| 启动期 `IllegalStateException: Unknown provider: 'deepseek'` | Profile 拼写错 / `application.yml` 缺失该 name |
| 启动期 `PlaceholderResolutionException: ...DEEPSEEK_API_KEY` | 三个 env var（DEEPSEEK/QWEN/MINIMAX）任一未设置 / 设成了空字符串 |
| 启动期 `BeanDefinitionStoreException: Circular reference` | Provider Bean 互相依赖 → 检查 `@Bean` 顺序 |
| 调用期 HTTP 401 | 凭证错（key 无效或过期）→ 查 provider 控制台 |
| 调用期 HTTP 429 | Provider 限流 → 等几分钟重试（核心阶段不重试，是要看到审计行） |
| `llm_calls` 没新行 | 检查 SQLite 文件路径；启动日志里搜 "llm_calls" 看 Hibernate 是否建表 |
| `llm_calls.success=true` 但 `error_message` 非空 | 数据校验规则 1 被破坏 → 立即报 bug |

---

## 12. 清理

```bash
unset DEEPSEEK_API_KEY
unset QWEN_API_KEY
unset MINIMAX_API_KEY
rm -f .oryxos/oryxos.db    # 重置审计表
```

下个 quickstart 重新跑时 SQLite 会自动建表。
