# 快速验证：Notify 出站推送

**目的**：端到端跑通 Notify spec 的 P1（单通道默认）+ P2（多通道路由 + Sandbox 拦截）+ P3（广播部分失败），不需要真实 webhook 平台账号，用 WireMock 模拟。
**创建日期**：2026-07-25
**特性**：[spec.md](./spec.md) | [research.md](./research.md) | [data-model.md](./data-model.md) | [contracts/](./contracts/)
**预计耗时**：30 分钟（含编译）

> **前置条件**：
> - 已完成 tasks.md 中 R-01..R-10 的实现任务
> - JDK 21 + Maven 3.9+
> - 至少 1 个 LLM Provider 的 API Key（DeepSeek / Qwen 任选）

---

## 步骤 0 — 编译 & 启动前置

```bash
# 在项目根
mvn -pl oryxos-tool,oryxos-storage,oryxos-boot -am package -DskipTests
```

**预期**：BUILD SUCCESS；`oryxos-boot/target/oryxos.jar` 更新。

---

## 步骤 1 — 启动 WireMock

```bash
docker run -d --name wiremock-notify -p 8089:8089 \
  -v "$PWD/specs/004-notify-channel/quickstart/wiremock:/home/wiremock" \
  wiremock/wiremock:3.5.4
```

**WireMock 预录 stub**（`specs/004-notify-channel/quickstart/wiremock/mappings/`）：

```json
// notify-default.json — 成功路径
{
  "request": { "method": "POST", "urlPath": "/hook/default" },
  "response": { "status": 200, "body": "{\"errcode\":0}" }
}

// notify-feishu.json — 多通道路由
{
  "request": { "method": "POST", "urlPath": "/hook/feishu" },
  "response": { "status": 200, "body": "{\"code\":0}" }
}

// notify-dingtalk-fail.json — 部分失败路径
{
  "request": { "method": "POST", "urlPath": "/hook/dingtalk-fail" },
  "response": { "status": 500, "body": "{\"error\":\"internal\"}" }
}
```

> **路径说明**：WireMock 的 8089 端口允许 Notify 直接发到 `http://localhost:8089/hook/<name>`；Sandbox 白名单须包含 `localhost`。

---

## 步骤 2 — 配置工作区

### 2.1 初始化

```bash
java -jar oryxos-boot/target/oryxos.jar init
cd .oryxos
```

### 2.2 写 `application.yaml`（工作区级）

```yaml
# .oryxos/application.yaml
oryxos:
  providers:
    deepseek:
      model: deepseek-chat
      credentialRef: ${DEEPSEEK_API_KEY}
  tool:
    sandbox:
      http:
        allowed-domains:
          - localhost                  # 关键：让 Notify 能调 WireMock
```

```bash
export DEEPSEEK_API_KEY="<your-real-key>"
```

### 2.3 复制 Profile 模板

```bash
mkdir -p .oryxos/agents/notify-demo
```

把以下内容写入 `.oryxos/agents/notify-demo/AGENT.md`：

```markdown
---
name: notify-demo
description: Notify 模块演示 Agent
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - notify
notify_channels:
  - name: default
    type: webhook
    url: http://localhost:8089/hook/default
  - name: feishu
    type: webhook
    url: http://localhost:8089/hook/feishu
  - name: dingtalk-fail
    type: webhook
    url: http://localhost:8089/hook/dingtalk-fail
settings:
  max_iterations: 10
  max_history_turns: 20
---

# Notify Demo Agent

你是一个测试 Notify 模块的 Agent。当用户说"广播测试"时，调用 notify 工具推
送 "test broadcast"（不指定 channel）。
当用户说"飞书测试"时，调用 notify(content="to feishu", channel="feishu")。
当用户说"默认测试"时，调用 notify(content="hello")（无 channel）。
当用户说"未知通道测试"时，调用 notify(content="x", channel="ghost")。
```

### 2.4 应用 DDL 变更

```bash
sqlite3 .oryxos/oryxos.db < oryxos-storage/src/main/resources/db/migration/V2__add_notify_columns.sql
sqlite3 .oryxos/oryxos.db ".schema tool_invocations" | grep -E "channel|notify_status_code"
```

**预期输出**：看到 `channel TEXT` 和 `notify_status_code INTEGER` 两列。

---

## 步骤 3 — 跑通 P1（单通道默认）

```bash
java -jar oryxos-boot/target/oryxos.jar chat notify-demo "默认测试"
```

**期望对话末尾**：

```
[Agent] 已推送: hello
```

**WireMock 验证**（开另一个终端）：

```bash
docker exec wiremock-notify cat /tmp/wiremock-log 2>/dev/null || \
  curl -s http://localhost:8089/__admin/requests | jq '.requests[] | select(.url=="/hook/default") | {method, url}'
```

**预期**：看到一次 `POST /hook/default`，body 含 `hello`。

**审计验证**：

```bash
sqlite3 .oryxos/oryxos.db \
  "SELECT tool_name, success, channel, notify_status_code, error_message
   FROM tool_invocations
   WHERE tool_name='notify' AND started_at > datetime('now', '-1 minute');"
```

**预期**：

```
notify|1|default|200|
```

---

## 步骤 4 — 跑通 P2（多通道路由）

```bash
java -jar oryxos-boot/target/oryxos.jar chat notify-demo "飞书测试"
```

**WireMock 验证**：

```bash
curl -s http://localhost:8089/__admin/requests | \
  jq '[.requests[] | select(.url|test("/hook/"))] | group_by(.url) | map({url: .[0].url, count: length})'
```

**预期**：只有 `/hook/feishu` 收到 1 次；`/hook/default` 与 `/hook/dingtalk-fail` 计数不变。

**审计验证**：

```bash
sqlite3 .oryxos/oryxos.db \
  "SELECT tool_name, channel, notify_status_code FROM tool_invocations
   WHERE tool_name='notify' ORDER BY started_at DESC LIMIT 1;"
```

**预期**：`channel=feishu`, `notify_status_code=200`。

---

## 步骤 5 — 跑通 P3（Sandbox 拦截）

临时修改 Profile，加一条非白名单 URL：

```yaml
notify_channels:
  # ... 原有 3 条 ...
  - name: evil
    type: webhook
    url: http://evil.example.com/hook
```

```bash
java -jar oryxos-boot/target/oryxos.jar chat notify-demo "把内容推到 evil 通道"
# （Agent 会因 channel=evil 不存在而失败——这是预期的；继续下一步）
```

更直接的验证：写一个最小 Profile **只**配 `evil` 通道：

```bash
mkdir -p .oryxos/agents/notify-sandbox
cat > .oryxos/agents/notify-sandbox/AGENT.md <<EOF
---
name: notify-sandbox
provider: {name: deepseek, model: deepseek-chat}
tools: [notify]
notify_channels:
  - {name: default, type: webhook, url: http://evil.example.com/hook}
settings: {max_iterations: 10, max_history_turns: 20}
---
调用 notify(content="secret")。
EOF

java -jar oryxos-boot/target/oryxos.jar chat notify-sandbox ""
```

**期望对话末尾**：

```
[Tool 错误] sandbox violation: host 'evil.example.com' not in allowed-domains
```

**WireMock 验证**：evil.example.com 不是 WireMock；用 WireMock 日志看不到 `/hook/evil` 请求（应为 0）。

**审计验证**：

```bash
sqlite3 .oryxos/oryxos.db \
  "SELECT tool_name, success, error_message FROM tool_invocations
   WHERE tool_name='notify' ORDER BY started_at DESC LIMIT 1;"
```

**预期**：

```
notify|0|sandbox violation: host 'evil.example.com' not in allowed-domains
```

---

## 步骤 6 — 跑通 P3（广播部分失败）

恢复 Profile 为步骤 2.3 的 3 通道版本，**保留 `dingtalk-fail` 这条返回 500 的通道**。

```bash
java -jar oryxos-boot/target/oryxos.jar chat notify-demo "广播测试"
```

**WireMock 验证**：

```bash
curl -s http://localhost:8089/__admin/requests | \
  jq '[.requests[] | select(.url|test("/hook/"))] | group_by(.url) | map({url: .[0].url, count: length})'
```

**预期**：

```json
[
  {"url": "/hook/default",      "count": 1},
  {"url": "/hook/dingtalk-fail","count": 1},
  {"url": "/hook/feishu",       "count": 1}
]
```

**审计验证**（广播聚合）：

```bash
sqlite3 .oryxos/oryxos.db \
  "SELECT success, channel, notify_status_code, error_message FROM tool_invocations
   WHERE tool_name='notify' ORDER BY started_at DESC LIMIT 1;"
```

**预期**：

```
1|default;feishu;dingtalk-fail|500|partial: dingtalk-fail=500
```

聚合 `success=1`（部分成功），`notify_status_code=500`（最差那条），`error_message` 含失败明细。

---

## 步骤 7 — 验证失败通知不中断 ReAct

```bash
# Profile 里把 dingtalk-fail 改成不可达 URL（连接超时）
# url: http://localhost:9999/hook/timeout     # 没服务在 9999
java -jar oryxos-boot/target/oryxos.jar chat notify-demo "广播测试"
```

**期望**：

- 对话正常完成（Agent 收到 tool 错误，调整响应）
- 没有 stack trace 抛给用户
- 审计行 success=0，error_class=timeout / network_error

---

## 步骤 8 — URL 脱敏验证

跑一次 notify，调 `http://localhost:8089/hook/default`（URL 不含 token，不脱敏）。

再手动构造一条带 token 的测试 webhook：

```yaml
- {name: with-token, type: webhook, url: 'http://localhost:8089/hook/x?key=ABCDEFG12345'}
```

跑一次 chat 触发 `notify(content="x", channel="with-token")`。

**审计验证**：

```bash
sqlite3 .oryxos/oryxos.db \
  "SELECT arguments FROM tool_invocations
   WHERE tool_name='notify' AND channel='with-token'
   ORDER BY started_at DESC LIMIT 1;"
```

**预期**：

- `arguments.channel` 字段含 `"with-token"`（通道名）
- 数据库内**没有**出现 `key=ABCDEFG12345` 明文（脱敏生效）
- 仅 `arguments.content` 字段含原始 content（content 不脱敏，是数据本身）

如果脱敏失败，能在 grep 中找到明文：

```bash
sqlite3 .oryxos/oryxos.db "SELECT * FROM tool_invocations;" | grep -i "ABCDEFG"
# 应为空
```

---

## 步骤 9 — 验证性能（NFR）

广播 10 通道（可在 quickstart profile 加 8 条 health 通道凑足 10）：

```bash
time java -jar oryxos-boot/target/oryxos.jar chat notify-demo "广播测试"
```

**预期**：wall-time ≤ 5 秒（SC-004）。如超 5 秒需排查 [research.md R-08](./research.md) 虚拟线程配置。

---

## 步骤 10 — 清理

```bash
docker stop wiremock-notify
rm -rf .oryxos/agents/notify-sandbox   # 步骤 5 的临时 profile
```

---

## 验收清单

| 步骤 | spec 验收场景 | 期望 |
|------|-------------|------|
| 3 | US-1 场景 1（默认通道发送） | WireMock 收到 1 次 POST；审计 success=1 |
| 3 | US-1 场景 2（无 notify_channels） | ToolResult.success=false（已用 notify-sandbox 演示） |
| 3 | US-1 场景 3（webhook 5xx） | success=false，error_message 含 HTTP 500 |
| 4 | US-2 场景 1（按 channel 路由） | 仅 feishu mock 收到请求 |
| 4 | US-2 场景 2（未知 channel） | success=false，error="未知通道" |
| 5 | US-3 场景 2（sandbox 拦截 evil URL） | success=false，error 含 sandbox；WireMock 计数 0 |
| 6 | US-4 场景 2（部分失败） | success=true（聚合），error_message="partial: ..." |
| 6 | US-4 场景 3（全失败） | success=false，error_message 含全部通道状态 |
| 7 | SC-005（不中断 ReAct） | 对话完成，无异常抛给用户 |
| 8 | SC-006（token 脱敏） | 审计数据不含明文 token |

---

## 失败排查

| 症状 | 可能原因 | 修复 |
|------|---------|------|
| `unsupported notify_channel type: webhook` | Profile 解析未识别 type | 检查 [Profile.notifyChannels](../data-model.md) 装配路径 |
| `sandbox violation: host 'localhost' not in allowed-domains` | `application.yaml` 漏 `localhost` | 加 `- localhost` 到 `tool.sandbox.http.allowed-domains` |
| WireMock 计数 0，但对话成功 | Agent 没调 notify（LLM 没理解指令） | 改 `AGENT.md` 指令更明确；或换 temperature 更低的模型 |
| `tool_invocations.channel` 列为空 | DDL 没生效 | 重跑步骤 2.4；或 `sqlite3 ... .schema tool_invocations` 验列存在 |
| `UnknownHostException: localhost` | WireMock 没起来 | 步骤 1 重启 docker |
| `HttpTimeoutException` 永远出现 | WireMock 没在响应 | 检查 `/home/wiremock/mappings/` 路径；WireMock 3.x 默认 mappings 在 `/home/wiremock/mappings/` |