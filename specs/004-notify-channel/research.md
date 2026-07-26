# 研究文档：Notify 出站推送（US-4 子能力）

**目的**：把 spec.md 中所有 NEEDS CLARIFICATION 与技术决策汇总，按"决策 / 理由 / 备选"格式固化，给 plan.md 与 tasks.md 提供可追溯依据。
**创建日期**：2026-07-25
**特性**：[spec.md](./spec.md)
**前置文档**：[.specify/memory/constitution.md](../../.specify/memory/constitution.md) | [CLAUDE.md §9.4, §9.5](../../CLAUDE.md)

---

## R-01：HTTP 客户端选型

**决策**：使用 JDK 21 内置的 `java.net.http.HttpClient`（同步 API），配合 Spring `@Async` + JDK 21 virtual thread 实现非阻塞发送。

**理由**：

1. **零额外依赖**——`HttpClient` 自 JDK 11 起稳定，Spring Boot 3.x / JDK 21 已是基线（[CLAUDE.md §4](../../CLAUDE.md)），不需要拉新依赖。
2. **virtual thread 友好**——`HttpClient.send()` 在 virtual thread 上阻塞调用是 JDK 21 推荐用法（[JEP 444](https://openjdk.org/jeps/444)）；比 Spring `RestClient` / `WebClient` 与虚拟线程的整合更直接。
3. **可控的超时与错误语义**——`HttpRequest.Builder.timeout(Duration)` 与 `HttpResponse.BodyHandlers` 的细粒度控制足以覆盖 spec FR-012（超时）和 FR-010（状态码判定）。
4. **更少 Spring 魔法**——Notify 是出站推送，纯粹的 HTTP 客户端，不需要 Spring 的 `@ResponseBody` / 序列化器栈。
5. **依赖方向干净**——`oryxos-tool` 不需要依赖 Spring Web 模块（仅 `spring-context` 即可），符合宪法 §I「不新增模块依赖方向」。

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| Spring `RestClient` (sync) | 与 Spring 生态统一 | 同样阻塞；序列化与错误处理栈对纯 webhook 用途是 over-engineering |
| Spring `WebClient` (reactive) | 内建非阻塞 | 引入 `spring-webflux` 依赖；Reactor 学习成本；与现有同步 `ReActLoop` 心智模型冲突 |
| OkHttp / Apache HC | 工业标准、生态丰富 | 新增第三方依赖；CLAUDE.md §4 明确依赖要最小化 |
| JDK `HttpClient` + CompletableFuture (reactive) | 不阻塞 | 现有 `DefaultToolExecutor` 是同步路径，`@Async` + virtual thread 已够用，避免引入额外异步语义 |

---

## R-02：Profile 形状演进策略

**决策**：在 `oryxos-core` 的 `Profile` record 上**新增一个字段** `List<NotifyChannelConfig> notifyChannels`，默认空列表。

**理由**：

1. **类型安全**优于 `Map<String, Object> extra`：NotifyChannelConfig 包含 `name`、`type`、`url`、`secret?` 等强类型字段，运行时校验比 map.get("name") 更早暴露错误。
2. **兼容现有 Profile 构造器**——使用 record 默认值（`null` → `List.of()`）规避 breaking change；现有调用方（`InMemoryProfileRegistry` / 测试 fixture）不修改即能继续工作。
3. **`extra` map 仍保留**——不为 Notify 占用，未来的次要字段继续用 extra，不污染主 record 形状。
4. **风险**：在 US-2 已落地的 Profile 上加字段，需要同步更新 `ProfileRegistryConfig` 装配路径与所有现有测试的构造调用。

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 用 `extra` map 装 `notify_channels` | 0 行 Profile 改动 | 失去类型安全；运行时才发现字段缺失；与后续 NotifyChannelConfig 校验逻辑割裂 |
| 新建独立 `NotifyProfile` 子类型 | 关注点分离 | US-4 子能力之间没有清晰的 "通知专属 profile" 边界；反而引入多态 |
| 在 `Profile` 外另存 `NotifyConfig` 集合 | 不动 Profile | 增加查询/同步成本；Notify 模块要知道 Profile 之外的"另一张表" |

---

## R-03：Sandbox 抽象在 Notify 中的最小落地

**决策**：本 spec 在 `oryxos-tool` 模块内**新增** `Sandbox` 接口与 `WhitelistSandbox` 实现（最小可用版本），覆盖 `HTTP_REQUEST` 一种 ActionType。这是 [CLAUDE.md §9.4](../../CLAUDE.md) 中描述的"接口先行"原则的具体落实。

**理由**：

1. **Notify 必须有白名单**——spec FR-004 是硬约束；不能等 US-4 的 Sandbox 子能力完整落地再开始。
2. **接口先行，演进路径清晰**——[CLAUDE.md §9.4](../../CLAUDE.md) 已规定升级路径：白名单 → 容器（namespace+cgroups+seccomp）→ microVM。最小可用版本先满足白名单一档。
3. **不破坏其他 US-2 / US-3 已落地的代码**——Sandbox 接口是新增，不修改现有 `ReActLoop` / `DefaultToolExecutor`。
4. **作用域控制**——本 spec 的 `WhitelistSandbox` 仅校验 `HTTP_REQUEST`；`FILE_READ` / `FILE_WRITE` / `SHELL_COMMAND` 留给 US-4 的其他子能力（FileTools / ShellTools）填充。

**接口设计**（最终落到 plan / tasks 阶段定型）：

```java
public interface Sandbox {
    void enforce(SandboxAction action) throws SandboxViolationException;
}
public record SandboxAction(ActionType type, String target) {}
public enum ActionType { FILE_READ, FILE_WRITE, SHELL_COMMAND, HTTP_REQUEST }
```

**配置**：

```yaml
oryxos:
  tool:
    sandbox:
      http:
        allowed-domains:
          - api.weixin.qq.com
          - open.feishu.cn
          - oapi.dingtalk.com
```

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 延迟到 US-4 主 plan | 一次性出全套 Sandbox | Notify 无法独立落地；P1 Demo 永远跑不通；与 CLAUDE.md §9.4 「接口先行」冲突 |
| 直接用现成库（如 `oshi`、`jordanbaird-ice`） | 不自己造轮子 | 没有合适的现成 HTTP 出站白名单库；且 Notify 的需求极简，自写一个 enum + List<String> 比拉依赖更轻 |
| 在 `WebhookNotifyAdapter` 内 inline 白名单检查 | 最小代码量 | 与 CLAUDE.md §9.4 冲突；后续 FileTools / ShellTools 也需要 Sandbox，独立接口更可复用 |

---

## R-04：Tool 注册表扩展方案

**决策**：扩展现有 `ToolRegistry`（[oryxos-tool](oryxos-tool/src/main/java/io/oryxos/tool/ToolRegistry.java)），让其**同时持有** `ToolDefinition`（元数据，给 CLI 用）与 `OryxTool`（实现，给 ToolExecutor 派发用）。

**理由**：

1. **同源数据**——一个 Tool 的"我是谁"（definition）和"我能做什么"（implementation）是同一个对象的两面；分开存会引入同步问题。
2. **改动局部化**——`ToolRegistry.of(Map<String, ToolDefinition>)` 是当前唯一公开 API；扩展为 `ToolRegistry.of(Map<String, ToolRegistration>)`，其中 `ToolRegistration` 是 `(ToolDefinition, OryxTool)` 复合记录。
3. **`DefaultToolExecutor` 需要拿到 OryxTool 实例**才能 dispatch；当前 `ToolRegistry` 只有定义（不含实现），需要补这条线。

**替代方案对比**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 新增独立的 `OryxToolRegistry`，与 `ToolRegistry` 并存 | 单一职责 | 两个 registry 必须同步；对调用方（`DefaultToolExecutor`）心智负担增加 |
| 在 `OryxTool` 接口加 `ToolDefinition definition()` 方法，让实现自报家门 | 单一 registry | 强迫每个实现构造自己的 definition；NotifyTool 等实现里出现样板代码 |
| 通过 `ApplicationContext.getBeansOfType(OryxTool.class)` 自动扫描 | 0 registry 改动 | 与宪法 §IV「显式映射」原则冲突；多 Provider 同类型歧义的坑在 Tool 层会重演（CLAUDE.md §8.2） |

---

## R-05：OryxTool 接口的最小扩展

**决策**：在 `OryxTool` 接口新增 `String description()` 方法（默认实现可由 Spring AI `@Tool` 注解自动生成）。

**理由**：

1. **LLM 必须知道 Tool 是干什么的**——`ToolSchemaProvider` 翻译 Profile 的 `tools[]` 时需要每个 Tool 的 description 才能生成 Function Calling JSON schema（[CLAUDE.md §9.2](../../CLAUDE.md) PromptBuilder 步骤 4）。
2. **影响范围**——US-2 阶段 `OryxTool` 没有该方法（只有 `name()` + `execute()`）；新增方法会让所有现有实现（包括 fake）编译失败。
3. **缓解**：用 JDK 21 接口默认方法 `default String description() { return ""; }`，让现有 fake / 测试桩不修改即可继续编译。
4. **NotifyTool 必须 override**——description 写 "向已配置的群机器人 webhook 推送一条文本消息"。

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 不动 OryxTool，让 ToolSchemaProvider 单独维护 Tool 描述 | 不破接口 | description 与 Tool 实现分散；维护时容易漂移 |
| 用 Spring AI `@Tool` 注解 | 与 Spring 生态统一 | CLAUDE.md §8 强调"自实现 ReAct + 自管 dispatch"；引入 `@Tool` 又走到 Spring AI 半自动的老路上 |
| 改用 sealed interface + pattern matching | JDK 21 风格 | 与现有 `OryxTool` 调用方签名不兼容，破坏面更大 |

---

## R-06：Notify 工具与 DefaultToolExecutor 的协作

**决策**：保留 `DefaultToolExecutor` 的白名单与审计职责，**新增**派发路径：

```
ReActLoop.run()
  → DefaultToolExecutor.invoke(name, args, profile)
      → 白名单检查（已有）
      → 查 ToolRegistry 拿到 OryxTool 实现（新）
      → 调用 OryxTool.execute(args)（替换现有 UOE）
      → ToolAuditWriter 写一行（已有）
```

**理由**：

1. **最小入侵**——只在 `DefaultToolExecutor` 现有 UOE 抛出点替换为 `toolRegistry.get(name).execute(args)`；白名单与审计逻辑不动。
2. **错误语义一致**——Tool 抛异常时包成 `ToolResult.error(...)`，仍走既有审计路径（C-TE-2 / C-TE-9）。
3. **Notify 工具可零代码被 LLM 看到**——只要 Profile `tools: [notify]`，ReAct 主循环走完 PromptBuilder 第 4 步就把 `notify` 的 schema 告诉 LLM。

**风险**：`DefaultToolExecutor` 当前构造函数 `public DefaultToolExecutor()` 默认 `NoopToolAuditWriter`；新增 ToolRegistry 依赖后构造函数变成两参。**所有现有测试**（`ReActLoopPureReasonTest` / `ReActLoopToolChainTest` 等）需要更新 fixture。

---

## R-07：webhook URL 敏感 token 脱敏策略

**决策**：在写 `tool_invocations` 表与日志前，对 webhook URL 的 query 参数做 key-value 脱敏（白名单匹配 `key` / `access_token` / `secret` / `api_key` / `token` 这 5 个常见敏感名）。

**理由**：

1. **企业微信 / 飞书 / 钉钉的 webhook token 通常在 URL 上**——典型 `https://open.feishu.cn/open-apis/bot/v2/hook/<token>` 或 `https://oapi.dingtalk.com/robot/send?access_token=<token>`。
2. **审计基线**——宪法 §VI 要求 day-one 可审计；审计行 url 字段含明文 token 等于把机器人 webhook 凭证落库，违反 CLAUDE.md §18「不要硬编码 API key」的隐含原则。
3. **脱敏不影响发送**——只脱敏写入的 url 字段；HTTP 请求仍带原始 URL。
4. **白名单而非黑名单**——只脱敏 5 个已知敏感字段名，避免过度脱敏导致调试困难。

**实现位置**：`WebhookNotifyAdapter` 内部；返回值 `NotifyResult.url` 已经脱敏，`DefaultToolExecutor` 写审计时直接用。

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 完全不打 URL 进审计 | 最安全 | 失去"哪个 endpoint 被调用"的审计价值；分析通知链路时无法定位 |
| 加密存数据库、查询时解密 | 审计员能看 | 密钥管理超出 Notify 范围；扩展阶段 |
| 写 hash 而非原值 | 折中 | 调试体验差；需要 hash 白名单查询支持 |

---

## R-08：广播语义的并发实现

**决策**：使用 JDK 21 `ExecutorService`（virtual thread per task executor，`Executors.newVirtualThreadPerTaskExecutor()`）并行触发 N 条通道的 HTTP 发送，收集所有结果后聚合 `ToolResult`。

**理由**：

1. **零额外配置**——JDK 21 内置虚拟线程；不引入 Spring `@EnableAsync` + 线程池配置。
2. **失败隔离**——每条通道独立 try/catch；一条 5xx 不影响其他。
3. **超时一致性**——`HttpClient.send` 的 timeout 作用于整次 send；虚拟线程自然 join。
4. **避免 race**——审计行写库顺序不影响 ReAct 循环；`DefaultToolExecutor` 的现有审计路径逐行同步写。

**超时与 wall-time**：

- 单条 HTTP 超时 5 秒（FR-012）
- N 条广播总 wall-time ≤ 5 + 1 × log₂(N) 秒（理论上）
- spec SC-004 要求 N=10 时 P95 ≤ 5 秒——符合预期

**备选**：

| 选项 | 优点 | 否决理由 |
|------|------|---------|
| 串行发送 | 最简 | SC-004 无法满足（10 条串行 wall-time ≥ 50 秒） |
| Spring `@Async` + ThreadPoolTaskExecutor | 与 Spring 生态统一 | 需要配线程池大小；现有 `ReActLoop` 不感知异步结果 |
| Reactor `Flux` + `parallel()` | 响应式 | 与现有同步代码风格冲突；学习成本 |

---

## R-09：失败语义与 ReAct 主循环的衔接

**决策**：所有 `notify` 失败（HTTP 非 2xx / 超时 / Sandbox 拦截 / 未知 channel / 空 content / 超长 content）均**包成** `ToolResult.success=false` 返回给 LLM，**不抛异常**。

**理由**：

1. **ReActLoop 期望** `ToolExecutor.invoke` 返回 `ToolResult`，不期望异常（现有 ReActLoop 也没有 catch 路径）。
2. **LLM 决策**——失败作为 tool 消息喂给 LLM，让 LLM 自己决定放弃 / 改用其他 channel / 向用户报错；spec SC-005 是硬约束。
3. **审计完整性**——所有失败都产生审计行（spec SC-002），便于事后追溯。

**NotifyResult 的聚合语义**：

- 单条调用（LLM 显式指定 channel）→ 直接用该通道的 success
- 广播调用（LLM 不指定 channel 且 N>1 条已配）→ 聚合：
  - 全部成功 → `ToolResult.success=true`，errorMessage=null
  - 部分成功 → `ToolResult.success=true`，errorMessage="partial: <失败通道名>=<状态码>; ..."
  - 全部失败 → `ToolResult.success=false`，errorMessage="all failed: <失败通道名>=<状态码>; ..."

**异常边界**：仅在 Notify 自身 bug（不可恢复的 NPE、JSON 序列化失败）时才抛 `RuntimeException`，由 ReAct 主循环的全局异常处理器捕获——这种情况应罕见，不在 spec FR 覆盖范围。

---

## R-10：测试与可演示策略

**决策**：

- **单元测试**：JUnit 5 + Mockito 覆盖 `NotifyTool`、`WebhookNotifyAdapter`、`WhitelistSandbox` 的核心路径（mock HttpClient）。
- **集成测试**：Spring Boot `@SpringBootTest` + WireMock 模拟 webhook endpoint，覆盖成功 / 4xx / 5xx / 超时 / Sandbox 拦截 5 种场景。
- **可演示脚本**：[scripts/notify-smoke.sh](../../scripts/notify-smoke.sh)（在 tasks.md 阶段创建）跑"单 Profile + WireMock + chat 触发 notify"的端到端冒烟。
- **三个 Demo 关联**：daily-weather / daily-tech / daily-github 三条 Agent 的 `AGENT.md` 在 tasks.md 阶段添加 notify 指令（"把今天的结果通知到 default 群"），与 Notify spec 联调。

**测试数据**：测试夹具的 webhook URL 用 `http://localhost:<wiremock-port>/hook`；白名单配置 `localhost`。

---

## 决策索引

| ID | 主题 | 决策 |
|----|------|------|
| R-01 | HTTP 客户端 | JDK `java.net.http.HttpClient` |
| R-02 | Profile 形状 | 新增 `notifyChannels` 字段 |
| R-03 | Sandbox 落地 | Notify spec 内新增最小可用 Sandbox 接口与实现 |
| R-04 | ToolRegistry 扩展 | 同 registry 同时存 definition 与 OryxTool 实例 |
| R-05 | OryxTool 扩展 | 新增 `default String description()` 方法 |
| R-06 | DefaultToolExecutor | 替换 UOE 为 registry 派发 |
| R-07 | URL 脱敏 | 5 个常见敏感 query 名脱敏 |
| R-08 | 广播并发 | Virtual thread per task executor |
| R-09 | 失败语义 | 全包成 ToolResult，不抛异常 |
| R-10 | 测试策略 | JUnit 5 + Mockito + WireMock 集成测试 + smoke 脚本 |

---

## 待 plan / tasks 阶段固化项

- `SandboxAction` / `ActionType` / `SandboxViolationException` 的具体包路径（在 `io.oryxos.tool.sandbox` 下）
- `NotifyChannelConfig` 在 Profile YAML 的具体解析路径（`oryxos-cli` 的 `ConfigLoader`）
- `tool_invocations` 新增两列（`channel` / `notify_status_code`）的 DDL 变更脚本——本 spec 用 `hibernate.ddl-auto=update` 不可靠，建议手动维护 SQL（[CLAUDE.md §13 风险提示](../../CLAUDE.md)）
- `WebhookNotifyAdapter` 对企业微信 / 飞书 / 钉钉三家 payload 差异的内部适配表（核心阶段用通用 `{"content": "<text>"}` 即可）