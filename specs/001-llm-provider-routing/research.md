# Phase 0 — 研究：LLM Provider 路由

**日期**：2026-07-24
**目的**：在开始设计前，确认技术决策、消除未知项
**关联**：[spec.md](./spec.md)、[constitution.md](../../.specify/memory/constitution.md)

---

## R-01：ChatModel 的获取方式（按名 vs 按类型）

**决策**：使用 Spring 容器内**显式 name → ChatModel** 的映射，不做类型扫描。

**Rationale**：

- Spring AI Alibaba 的 `ChatModel` 是一个接口，每个 Provider 实现（DashScope、DeepSeek、OpenAI 兼容等）都注册为 `ChatModel` 类型的 Bean。
- 容器中存在多个 `ChatModel` 时，按类型获取会得到多个匹配的 Bean，触发 `NoUniqueBeanDefinitionException`，或被 `@Primary` 注解意外遮蔽。
- 显式 `Map<String, ChatModel>` 通过 `@Bean(name=...)` + `BeanFactory.getBeansOfType(ChatModel.class)`（按类型拿到所有，但用 Bean 名作为 key）实现 name → ChatModel 的稳定路由。
- 与宪法 §IV "Spring AI 只用一半" 一致，也匹配项目核心陷阱 #2（"不要用容器类型扫描区分 Provider"）。

**替代方案**：

- ❌ `@Primary` + 类型注入：只能标记"默认"Provider，第二个 Provider 无解。
- ❌ 按 `ChatModel` 实现类名匹配：需要硬编码实现类名耦合，添加新 Provider 必须改代码。
- ✅ **采纳**：每个 Provider Bean 都用 `@Bean("deepseek")` / `@Bean("qwen")` 显式命名，启动期构建 `Map<String, ChatModel> providerIndex`，运行时按 name 查表。

---

## R-02：禁用 Spring AI 的自动 tool 执行

**决策**：在 Provider 层**不**使用 `ChatClient`（它是带 Advisor 链的"高级 API"），改用 `ChatModel.call(Prompt)` 底层 API；tool schema 通过 `ChatOptions` 传入，但不提供 `ToolCallback` / `FunctionCallback`，让 Spring AI 无可执行的内容。

**Rationale**：

- Spring AI 的"自动 tool 执行"行为出现在 `ChatClient` + `ToolCallingAdvisor` 这条链路上：ChatClient 收到 `tool_calls` 后会自动用 `ToolCallback` 执行工具、把结果回填给 LLM、再调一次 LLM。
- 我们要的是"翻译"而不是"执行"——这正是宪法 §IV 陷阱 #1（"启用 Spring AI 自动 tool 执行 → tool 被调两次"）。
- 用 `ChatModel.call(Prompt)`，传入 `ChatOptions.builder().toolSpecifications(...).build()`（Spring AI 1.0+ 写法），只让 LLM 在响应里**建议**调用哪个工具，**不**提供回调。
- ReAct 循环层（US-2）拿到的 `LlmResponse.toolCalls` 才是它真正用来 dispatch ToolExecutor 的输入；Provider 层不参与。

**替代方案**：

- ❌ 用 `ChatClient` + 在 Advisor 链里过滤 tool calling：复杂、易遗漏副作用。
- ❌ 禁用 Spring AI 的 `ToolCallingAdvisor`：必须知道它的所有启用路径，脆弱。
- ✅ **采纳**：直接走 `ChatModel.call(Prompt)`，不引入 `ChatClient`，物理上消除自动执行路径。

---

## R-03：DeepSeek / Qwen / MiniMax 的 tool calling 协议

**决策**：所有目标 Provider（DeepSeek、Qwen、MiniMax 等）均兼容 OpenAI Chat Completions 的 tool calling 协议，请求字段用 `tools`，响应字段用 `tool_calls`。

**Rationale**：

- DeepSeek API 文档（`https://api-docs.deepseek.com/`）声明"与 OpenAI Chat Completions API 兼容"，`tools` 是顶层数组，每个元素 `{type: "function", function: {name, description, parameters}}`。
- Qwen（DashScope）的 `qwen-plus` / `qwen-max` 模型走 OpenAI 兼容端点时，字段名一致。
- MiniMax `MiniMax-M3` 同样提供 OpenAI 兼容端点，字段命名一致。
- 这意味着 Profile 里的 tool schema 可以**统一**翻译成 OpenAI 格式，Provider 层不需要为每个 Provider 写不同翻译逻辑（US-5 验收要求"N 个 tool schema 出现在请求里"自然成立）。
- 未来如果出现非 OpenAI 兼容协议（如 Anthropic 原生、Qwen 旧版），需要新增一个 `ToolSchemaTranslator` 抽象；核心阶段先按"全 OpenAI 兼容"做。

**替代方案**：

- ❌ 为每个 Provider 写专有翻译器：4+ Provider × 2+ 工具协议 = 工作量爆炸，宪法禁止。
- ✅ **采纳**：假设所有目标 Provider 都 OpenAI 兼容；这一点写入 `application.yaml` provider 配置的注释里作为硬约定。

---

## R-04：凭证环境变量解析时机

**决策**：启动期一次性解析；缺失则启动失败（fail-fast）；不延迟到首次调用。

**Rationale**：

- 宪法 Additional Constraints 明文："系统必须在启动期 fail-fast：若任何已配置 Provider 的凭证环境变量缺失或为空，进程不应继续启动。"
- spec 边界情况条目同样要求 fail-fast。
- 延迟到首次调用解析会让"未配置凭证"成为运行中错误而非启动错误，运维体验差（要在调用栈里找是哪个 env 变量）。

**实现**：

- `application.yaml` 用 `${DEEPSEEK_API_KEY}` 占位符（Spring Boot 原生支持）。
- 启动期 Spring 解析所有 `${...}` 占位符；任一解析为 `${DEEPSEEK_API_KEY:}`（空）或字面 `${DEEPSEEK_API_KEY}`（未找到）即触发 `PlaceholderResolutionException`，进程退出。
- 此外在 `ProviderRegistry` 初始化时再校验一次"凭证字段非空"，作为双保险（防止有人绕过 Spring 占位符直接注入字符串）。

**替代方案**：

- ❌ 首次调用时才检查：违反宪法。
- ❌ 用 `@Value` + SpEL 写 `#{environment.getProperty('DEEPSEEK_API_KEY') ?: throw ...}`：能跑但 Spring 占位符已经够用，再加一层无收益。
- ✅ **采纳**：纯 Spring 占位符 + 启动期 `ProviderRegistry` 二次校验。

---

## R-05：`llm_calls` 审计行的写入策略

**决策**：**调用返回前**写入；成功/失败两条路径都写；写库失败本身**也**记一行（用 `errorMessage="audit write failed: <原因>"`）。

**Rationale**：

- spec SC-002 要求"100% 调用产生审计行，零静默失败"。把审计行写入放在调用返回前是实现 100% 覆盖率的唯一办法。
- 用 `@Transactional(propagation = REQUIRES_NEW)` 把审计写入独立成自己的事务，避免被业务事务回滚"吃掉"。
- 如果审计写入本身抛异常（SQLite 满 / 磁盘满），捕获后构造一个"最小可记录"的 `LlmCallRecord`（`success=false, errorMessage="audit write failed: <原因>", durationMs=已用时间`）再尝试一次；连这次都失败，则**记日志并继续返回原始错误**（不让审计失败阻塞业务失败）。

**替代方案**：

- ❌ 异步写：用 `@Async` 或消息队列 → 进程崩溃会丢审计行，违反 day-one 可审计。
- ❌ 同步写但放在 `@Transactional` 主事务里：业务回滚会连审计一起回滚，违反"100% 写入"。
- ✅ **采纳**：独立 `REQUIRES_NEW` 事务 + 双层 try/catch（先写审计；写失败再写一次"审计失败"行；再失败就告警日志）。

---

## R-06：Profile 加载与 Provider 路由的时序

**决策**：Profile 加载归 `oryxos-core` 的 `ContextLoader`（CLAUDE.md §5 既有约定，不属于本 spec 范围）；`oryxos-provider` 只**消费** Profile 的 `provider.name` / `provider.model` / `tools` 字段。

**Rationale**：

- 宪法陷阱 #3 明文："把 AGENT.md / AgentLoader 当成 Tool → 归 core 的 ContextLoader"——Profile 加载是 core 的事，Provider 层不重新实现。
- Provider 层只通过 `Profile` 对象（或一个轻量 `ProviderRequest` DTO）读取 `provider.name`，不直接读 YAML。
- 这样 Provider 层依赖方向是单向的：`provider → core`，不会反向耦合。

**替代方案**：

- ❌ Provider 层自己读 `application.yaml` 找 Profile 文件：把 IO 逻辑复制到 provider 模块，违反 CLAUDE.md §5。
- ✅ **采纳**：Provider 层只定义 `ProviderService.invoke(name, request)` 接口；`request` 里已经包含 `providerName` / `messages` / `toolSchemas` / `temperature` / `maxTokens`，调用方（ReAct 循环层）负责把 Profile 翻译成 `request`。

---

## R-07：MVP 必须支持的 Provider 清单

**决策**：核心阶段 MVP 必须能在真实端到端 demo 中跑通 **DeepSeek + Qwen + MiniMax** 三家不同供应商的 Provider。

**Rationale**：

- spec 假设条款（澄清 Q1 后更新）："MVP 演示必须包含 DeepSeek、Qwen、MiniMax 三家不同供应商，以同时证明"多 Provider 共存"和"不同供应商并存"两个价值点。"
- US-3（多 Provider 共存）验收场景要求能同时配置并调用多个 Provider。
- 三家分别来自不同厂商、互不共享基础设施、最便宜的 demo 默认与最稳定的兼容端点都能覆盖。
- MiniMax 提供 OpenAI 兼容端点 + 最新旗舰 `MiniMax-M3`，与 R-03 假设"全 OpenAI 兼容"完全契合，不需要新增翻译器。

**替代方案**：

- ❌ 只演示 1 个 Provider：US-3 验收失败。
- ❌ 演示 2 个 DeepSeek 账号（同 type 不同 name）：同 type 多实例这条路径能走通，但"不同供应商并存"路径无法真实证明。
- ❌ 演示 DeepSeek + Qwen 两家 + 一个 mock：第三家是 mock 时，US-3 验收"无凭证串扰"变成"无 mock 串扰"，力度不够。
- ✅ **采纳**：DeepSeek + Qwen + MiniMax 三家**真实不同供应商**。

---

## 总结：已消除的未知项

| 未知项 | 决策 | 对应 spec 约束 |
| ------ | ---- | -------------- |
| ChatModel 怎么按 name 选 | 显式 `Map<String, ChatModel>` Bean | FR-005、FR-006 |
| 怎么禁用 Spring AI 自动 tool 执行 | 用 `ChatModel.call(Prompt)` 而非 `ChatClient` | FR-010 |
| 各 Provider 的 tool 协议 | 全部按 OpenAI 兼容翻译 | FR-009 |
| 凭证 env var 解析时机 | 启动期 fail-fast | FR-002、FR-003 |
| 审计行怎么保证 100% 写入 | `REQUIRES_NEW` 事务 + 双层 try/catch | FR-007、SC-002 |
| Profile 加载归谁 | core 负责，provider 只消费 | （宪法陷阱 #3） |
| MVP 演示 Provider 选谁 | DeepSeek + Qwen + MiniMax（3 家不同供应商） | US-3 假设条款（澄清 Q1） |

**无遗留 NEEDS CLARIFICATION。** Phase 0 完成。
