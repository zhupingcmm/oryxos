# Quickstart: CLI 命令行入口（quickstart.md）

> Phase 1 输出 — 端到端可跑的验证场景。每个场景对应 [spec.md Success Criteria](../003-cli-commands/spec.md) 一条或多条 SC。
> 实现细节在 [tasks.md](../003-cli-commands/tasks.md) 与代码中；本文件只**演示验证**，不写实现。

## 前置条件

```bash
# 1. JDK 21 + Maven（既有的 oryxos 仓库）
java -version           # openjdk version "21.0.x"
mvn -version            # Apache Maven 3.9.x, Java version: 21

# 2. 拉取 + 编译（首跑会下 Maven 依赖）
git clone <oryxos-repo> && cd oryxos
git checkout 003-cli-commands
mvn -pl oryxos-cli,oryxos-channel-cli,oryxos-boot -am package -DskipTests

# 3. 准备 API key（DeepSeek demo 用）
export DEEPSEEK_API_KEY=sk-...
```

## 场景 1：初始化工作区（[SC-002](../003-cli-commands/spec.md)）

```bash
# 1.1 在空目录跑 init
cd /tmp/oryxos-smoke && rm -rf .oryxos
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar init
# 预期：stdout 列出 4 目录 + 5 文件 + 1 db；exit 0

# 1.2 验证生成的文件
ls -la .oryxos/
# 预期：agents/ memory/ sessions/ logs/ mcp_servers.yaml AGENTS.md SOUL.md USER.md oryxos.db

# 1.3 二次 init 报已初始化
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar init
# 预期：stderr 写 "Already initialized at <path>"；exit 1
```

## 场景 2：健康度报告（[SC-003](../003-cli-commands/spec.md)）

```bash
# 2.1 status 报告
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar status
# 预期：stdout 表格：JDK 21 / .oryxos 路径 / Profile 数=0 / Provider 配置矩阵
#      exit 0（若所有 Provider API key 已 resolved）

# 2.2 status 缺一个 API key
unset DEEPSEEK_API_KEY
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar status
# 预期：stdout 表格中 DeepSeek 行的 api_key_resolved=false；exit 2

# 2.3 性能：status 首输出 ≤ 200 ms
time java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar status
# 预期：real < 0.5s（无 Spring 启动开销）
```

## 场景 3：Profile CRUD（[SC-004](../003-cli-commands/spec.md)）

```bash
# 3.1 创建一个 minimal profile
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar profile create weather-bot --template minimal
# 预期：exit 0；ls .oryxos/agents/weather-bot/AGENT.md 存在

# 3.2 列出
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar profile list
# 预期：stdout 表格 1 行（weather-bot / 最小描述 / deepseek / 0 tools）

# 3.3 重复 create 报已存在
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar profile create weather-bot --template minimal
# 预期：stderr 写 "Profile already exists"；exit 64

# 3.4 删除
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar profile delete weather-bot
# 预期：exit 0；ls .oryxos/agents/weather-bot/ 不存在
```

## 场景 4：chat 触发 Agent（[SC-001](../003-cli-commands/spec.md)）

```bash
# 4.1 准备环境
export DEEPSEEK_API_KEY=sk-...
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar profile create weather-bot --template weather
ls .oryxos/agents/weather-bot/AGENTS.md && cat .oryxos/agents/weather-bot/AGENT.md
# 预期：AGENT.md 含 deepseek provider + http_get + notify tools

# 4.2 真实 chat
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar chat weather-bot "今天上海天气"
# 预期：≤ 30s 拿到 Agent 文本（"上海今天..."）；exit 0

# 4.3 缺 API key 触发 fail-fast
unset DEEPSEEK_API_KEY
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar chat weather-bot "今天上海天气"
# 预期：stderr "API key missing: DEEPSEEK_API_KEY"；exit 69；llm_calls 表无新行

# 4.4 管道用法（stdout 干净）
unset DEEPSEEK_API_KEY
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar chat weather-bot "今天上海天气" 2>/dev/null | grep "上海"
# 预期：grep 无匹配（错误进 stderr）；exit 1
```

## 场景 5：Spring 启动类查询命令

```bash
# 5.1 provider list
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar provider list
# 预期：stdout 表格列出 application.yaml 中配置的 Provider；exit 0

# 5.2 tool list（前置：US-4 接入前可能为空）
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar tool list
# 预期：stdout 表格列 Tool；exit 0

# 5.3 session list
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar session list --limit 5
# 预期：stdout 表格列最近 5 条 Session（按 updated_at 倒序）；exit 0
```

## 场景 6：US-5 stub（`serve` / `gateway`）

```bash
# 6.1 serve 当前为 stub
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar serve --port 8080
# 预期：stdout "not yet implemented (US-5)"；exit 0

# 6.2 gateway 当前为 stub
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar gateway --port 9090
# 预期：stdout "not yet implemented (US-5)"；exit 0
```

## 场景 7：审计行验证（[CLAUDE.md §13](../CLAUDE.md) day-one）

```bash
# 7.1 跑 chat 后查 SQLite
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar chat weather-bot "hi"
sqlite3 .oryxos/oryxos.db "SELECT COUNT(*) FROM sessions; SELECT COUNT(*) FROM llm_calls; SELECT COUNT(*) FROM tool_invocations;"
# 预期：sessions ≥ 1、llm_calls ≥ 1（具体条数视 Agent 是否触发 tool）

# 7.2 API key 不进 audit 行
sqlite3 .oryxos/oryxos.db "SELECT * FROM llm_calls;"
# 预期：无 DEEPSEEK_API_KEY 字面量（request_body 不存敏感字段）
```

## 场景 8：错误消息全走 stderr（[FR-010 / SC-006](../003-cli-commands/spec.md)）

```bash
# 8.1 不存在 profile
java -jar oryxos-boot/target/oryxos-boot-1.0.0.jar chat ghost-bot "hi" >/tmp/stdout.log 2>/tmp/stderr.log
echo "stdout: $(cat /tmp/stdout.log)"    # 预期：空（或只含 --help 行为）
echo "stderr: $(cat /tmp/stderr.log)"    # 预期：含 "Unknown profile: 'ghost-bot'"
echo "exit: $?"                            # 预期：非 0（建议 64）
```

## 场景 9：跨平台 smoke（[A-008](../003-cli-commands/spec.md)）

```bash
# 在 Linux / macOS / Windows 上分别跑场景 1-8
# 预期：除路径分隔符（`/` vs `\`）外，所有输出 + exit code 一致
```

## 一键 smoke 脚本

`scripts/cli-smoke.sh`（新增）把场景 1-8 串起来：

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -pl oryxos-boot -am package -DskipTests -q
JAR="oryxos-boot/target/oryxos-boot-1.0.0.jar"

bash scripts/scenario-01-init.sh "$JAR"
bash scripts/scenario-02-status.sh "$JAR"
bash scripts/scenario-03-profile.sh "$JAR"
bash scripts/scenario-04-chat.sh "$JAR"
bash scripts/scenario-05-spring-queries.sh "$JAR"
bash scripts/scenario-06-stub.sh "$JAR"
bash scripts/scenario-07-audit.sh "$JAR"
bash scripts/scenario-08-stderr.sh "$JAR"
```

CI 矩阵（[CLAUDE.md §11](../CLAUDE.md)）：GitHub Actions matrix 跑 `ubuntu-latest` / `macos-latest` / `windows-latest` 三平台。

## 验收矩阵

| 场景 | 验证的 SC | 失败时排查入口 |
|------|----------|----------------|
| 1 init | SC-002 | `init.md` |
| 2 status | SC-003 | `status.md` |
| 3 profile | SC-004 | `profile.md` |
| 4 chat | SC-001 / SC-006 / SC-007 | `chat.md` |
| 5 provider / tool / session | — | `provider.md` / `tool.md` / `session.md` |
| 6 stub | — | `serve.md` |
| 7 audit | CLAUDE.md §13 | `data-model.md` §6 |
| 8 stderr-only | SC-006 / FR-010 | `data-model.md` §7 INV-CLI-5 |
| 9 cross-platform | A-008 | — |