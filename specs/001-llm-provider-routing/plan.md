# Implementation Plan: LLM Provider 路由（US-1）

**Branch**: `[001-llm-provider-routing]` | **Date**: 2026-07-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-llm-provider-routing/spec.md`

**Note**: 本模板由 `/speckit-plan` 命令填充；下面的章节描述了实际设计决策。Phase 0 / Phase 1 产物见同级目录。

---

## Summary

为 OryxOS 核心阶段构建**对 LLM 的统一入口**：

- **按名路由**到已配置的 Provider（DeepSeek + Qwen + MiniMax 三家不同供应商；MVP 路由闭环 + 审计 day-one）
- **每次调用产出一行 `llm_calls` 审计记录**，无论成功失败（day-one 可审计）
- **工具 schema 翻译**为 Provider 原生格式随请求发出，但本层**不执行工具**
- **凭证走环境变量**，启动期 fail-fast，零硬编码
- **不做** ReAct 循环、retry / fallback、流式响应、成本看板

实现位于 `oryxos-provider` 模块，依赖 `oryxos-core`（Profile 消费）与 `oryxos-storage`（`llm_calls` 持久化）。

---

## Technical Context

**Language/Version**: Java 21（records、sealed types、pattern matching、virtual threads、sequenced collections 全用）

**Primary Dependencies**:

- Spring Boot 3.3.5
- Spring AI Alibaba 1.0+（**仅**使用 `ChatModel` + `ChatOptions` + `ToolSpecification` 翻译；**不**引入 `ChatClient`）
- Spring Data JPA + Hibernate（`llm_calls` 表）
- Hypersistence Utils（SQLite JSON 列支持，可选）
- SnakeYAML（Profile 解析，归 `oryxos-core`）
- Logback / SLF4J（结构化日志）
- JUnit 5 + Mockito + AssertJ

**Storage**: SQLite（`.oryxos/oryxos.db`），Spring Data JPA 自动建表（`llm_calls` 表 DDL 见 [data-model.md](./data-model.md)）

**Testing**: JUnit 5，单元测试覆盖每个 FR；集成测试用 `WireMock` 模拟 DeepSeek / Qwen 端点（不依赖真实 key）；一个 e2e 测试用真实 key 跑一次冒烟

**Target Platform**: Linux / Windows 服务器，Java 21 运行时；Maven fat JAR（`java -jar`）

**Project Type**: 库（`oryxos-provider` 模块），是 9 模块 Maven 多模块项目的一部分；Spring Boot 启动模块 `oryxos-boot` 引入它

**Performance Goals**:

- 启动期 Provider 校验 < 1 秒（即使 20 个 Provider）
- 单次 LLM 调用额外开销（不含网络）< 50 ms（audit 写库 + 协议转换）
- 无业务失败场景下，audit 写不影响调用方延迟

**Constraints**:

- **不重试 / 不回退**（spec FR-011）
- **不同步流**（spec FR-012）
- **不聚合成本**（spec FR-013）
- **凭证**只来自 `${ENV_VAR}`，启动期解析（spec FR-002 / FR-003）

**Scale/Scope**:

- 典型部署：2~10 个 Provider 目录条目
- 典型负载：每 Agent 每天 1~1000 次调用
- `llm_calls` 表在单实例部署下可承受 100 万行（SQLite 上限远超此），超过需手动归档

---

## Constitution Check

*GATE: Phase 0 研究前必过；Phase 1 设计后重新过。*

| 原则 | 验证 | 状态 |
|------|------|------|
| **I. Single-Stack Monolith** | JDK 21 + Spring Boot 3.3.5 + 9 Maven 模块；单 fat JAR 部署 | ✅ 通过 |
| **II. Core-Stage Scope Discipline** | 本 spec 仅实现"五大能力"之能力一（Provider）；不碰多租户 / SSO / 审计查询 UI / Tool Policy / 仪表板 / 集群 HA | ✅ 通过 |
| **III. Self-Implemented ReAct Loop** | 本 spec 是 Provider 层，**不**实现 ReAct；ReAct 是 US-2 的事。本 spec 显式声明"不调循环、不重试" | ✅ 通过 |
| **IV. Spring AI Used at Half-Strength** | 仅用 `ChatModel.call(Prompt)` 底层 API；不引入 `ChatClient`（物理上消除自动 tool 执行）；工具 schema 通过 `ChatOptions.toolSpecifications()` 传入但**不提供 ToolCallback** | ✅ 通过 |
| **V. Three-Tier Plugin Tooling** | 本 spec 不新增 Tool；消费的 `tools` 列表是 `oryxos-tool` 模块已经注册的（HTTP / Notify 等内置工具 + 未来 MCP 工具） | ✅ 通过 |
| **VI. SQLite + MEMORY.md with Day-One Audit** | `llm_calls` 表在应用启动时由 Hibernate 自动建表；audit 写入用 `REQUIRES_NEW` 事务保证 100% 覆盖 | ✅ 通过 |
| **VII. Demo-First Delivery** | quickstart.md 给出 9 步端到端 demo，覆盖 SC-001 ~ SC-007 全部 7 条 | ✅ 通过 |

**Constitution 违规**：**无**。
**复杂度豁免**：**无**（Complexity Tracking 表留空）。

---

## Project Structure

### 文档（本特性）

```text
specs/001-llm-provider-routing/
├── spec.md                # /speckit.specify 产物
├── plan.md                # 本文件
├── research.md            # Phase 0 产物：7 项技术决策（R-01 ~ R-07）
├── data-model.md          # Phase 1 产物：Provider / LlmCallRecord 实体
├── quickstart.md          # Phase 1 产物：9 步端到端 demo
├── contracts/
│   ├── ProviderService.java              # Java 接口契约
│   ├── application-provider-config.md    # application.yml 格式
│   └── profile-provider-section.md       # Profile YAML 格式
└── checklists/
    └── requirements.md     # spec 质量检查表（16/16 通过）
```

### 源码（9 模块 Maven 多模块，本特性主要落在 3 个模块）

```text
oryxos-core/                              # Profile 加载、ContextLoader（本 spec 仅消费其 Profile 对象）
└── src/main/java/io/oryxos/core/
    ├── profile/Profile.java               # 已存在；本特性扩展其 provider / tools 段的消费
    └── context/ContextLoader.java        # 已存在；启动期校验 provider.name 在 ProviderRegistry 中

oryxos-provider/                          # 本特性的主战场
└── src/main/java/io/oryxos/provider/
    ├── ProviderService.java              # 对外接口（见 contracts/ProviderService.java）
    ├── LlmRequest.java                   # 入参 DTO
    ├── LlmResponse.java                  # 出参 DTO（含 ToolCall / TokenUsage）
    ├── DefaultProviderService.java       # 主实现：按名查 ChatModel + 翻译 + 调 + 写 audit
    ├── ProviderRegistry.java             # name → ChatModel 显式 Map（R-01）
    ├── ToolSchemaTranslator.java         # 工具 schema 翻译为 Provider 原生格式（R-03）
    ├── CredentialResolver.java           # ${ENV_VAR} 启动期解析 + 双保险校验（R-04）
    ├── AuditWriter.java                  # 独立事务 REQUIRES_NEW 写入 llm_calls（R-05）
    ├── exception/
    │   ├── UnknownProviderException.java
    │   └── LlmInvocationException.java
    └── config/
        ├── ProviderProperties.java       # @ConfigurationProperties("oryxos.providers")
        ├── ProviderAutoConfiguration.java # 注册所有 @Bean
        └── ChatModelConfig.java          # 按 Provider 类型创建 ChatModel Bean

oryxos-storage/                           # JPA 实体与 Repository
└── src/main/java/io/oryxos/storage/
    ├── entity/
    │   └── LlmCallRecord.java            # @Entity，对应 llm_calls 表
    └── repository/
        └── LlmCallRecordRepository.java  # JpaRepository<LlmCallRecord, UUID>

oryxos-boot/                              # 启动模块：装配 application.yml
└── src/main/resources/
    └── application.yml                   # 增加 oryxos.providers 段（见 contracts/）

└── src/test/java/io/oryxos/provider/
    ├── DefaultProviderServiceTest.java       # 单元：按名路由、异常路径、audit 调用
    ├── ProviderRegistryTest.java             # 单元：name 唯一 / 格式 / 缺失环境变量
    ├── ToolSchemaTranslatorTest.java         # 单元：N 个工具 → N 个 schema
    ├── AuditWriterTest.java                  # 单元：成功失败双路径
    └── e2e/
        └── ProviderRoutingE2ETest.java       # 集成：起 Spring + SQLite + mock 两个 Provider

oryxos-provider/src/test/resources/
├── application-test.yml                    # 2 个测试 Provider（mock 端点）
└── llm_calls-schema.sql                    # 期望的 DDL（用于 Hibernate 自动建表的对账）
```

**结构调整决策**：

- `ProviderService` 接口放在 `oryxos-provider` 模块的根包，**不**放 `core.api` 之类的子包——这是本特性的对外契约，应该一眼能看到
- `LlmCallRecord` 实体放 `oryxos-storage` 模块（持久化职责单一模块所有），不放 `oryxos-provider`（避免 provider 模块依赖 storage 模块的具体 JPA 注解）——通过 `AuditWriter` 接口解耦
- 测试全在 `oryxos-provider/src/test/`，**不**在 `oryxos-boot`（避免启动模块测试代码膨胀）
- e2e 测试用 Spring Boot Test + 真实 SQLite 文件（临时目录） + WireMock 模拟 Provider HTTP 端点

---

## Complexity Tracking

> *仅当 Constitution Check 有违规需要豁免时填写*

本特性无 Complexity Tracking 条目 —— 所有 7 条宪法原则直接通过，无任何"为简化而破例"的环节。Complexity Tracking 表留空。

---

## 关键设计决策摘要（详见 research.md）

| 决策 | 简短理由 |
|------|----------|
| **R-01** 用显式 `Map<String, ChatModel>` 而非容器类型扫描 | 多 Provider 同 type 共存，避免 `NoUniqueBeanDefinitionException` |
| **R-02** 用 `ChatModel.call(Prompt)` 而非 `ChatClient` | 物理上消除 Spring AI 自动 tool 执行（宪法陷阱 #1） |
| **R-03** 所有 Provider 按 OpenAI 兼容协议翻译工具 | DeepSeek / Qwen / MiniMax 均 OpenAI 兼容，单一翻译器覆盖 |
| **R-04** 凭证 env var 启动期 fail-fast | 宪法硬约束；运维体验更好 |
| **R-05** audit 写入用 `REQUIRES_NEW` 独立事务 + 双层 try/catch | 100% 写入保证，连"写 audit 失败"也记一行 |
| **R-06** Profile 加载归 `oryxos-core`，Provider 只消费 | 遵守宪法陷阱 #3（避免重复实现 Profile IO） |
| **R-07** MVP 演示用 DeepSeek + Qwen + MiniMax 三家不同供应商 | 同时证明"多 Provider 共存"和"不同供应商并存"，不靠模拟（澄清 Q1） |

---

## 实施顺序（按依赖）

```text
Step 1  [骨架]  oryxos-storage 加 LlmCallRecord 实体 + Repository
Step 2  [骨架]  oryxos-provider 加 ProviderProperties + ProviderRegistry + 启动校验
Step 3  [骨架]  oryxos-provider 加 ChatModel Bean 配置（DeepSeek + Qwen + MiniMax 三个）
Step 4  [契约]  ProviderService 接口 + LlmRequest/Response DTO
Step 5  [核心]  DefaultProviderService 主实现：按名路由 + ChatModel.call + 异常分类
Step 6  [核心]  ToolSchemaTranslator 工具 schema 翻译
Step 7  [核心]  AuditWriter 独立事务写 llm_calls
Step 8  [集成]  DefaultProviderService 串起：路由 → 翻译 → 调用 → 写 audit → 返回
Step 9  [测试]  单元测试覆盖每个 FR
Step 10 [测试]  e2e 测试：起 Spring + WireMock + SQLite，验证 SC-001~SC-007
Step 11 [Demo]  按 quickstart.md 9 步真实跑一次 DeepSeek + Qwen + MiniMax
Step 12 [验收]  /speckit.analyze 检查 spec/plan/实现 三者一致
Step 13 [提交]  git commit（独立分支 001-llm-provider-routing）
```

每一步完成都跑一遍 `mvn -pl oryxos-provider -am test`，全绿再进下一步。

---

## Phase 0 / Phase 1 产物

| 产物 | 路径 | 状态 |
|------|------|------|
| 研究 | [research.md](./research.md) | ✅ 完成，7 项决策全部落地，0 NEEDS CLARIFICATION |
| 数据模型 | [data-model.md](./data-model.md) | ✅ 完成，覆盖 Provider / LlmCallRecord / 索引 / 验证规则 |
| 接口契约 | [contracts/ProviderService.java](./contracts/ProviderService.java) | ✅ 完成，Java 形态契约 |
| 配置契约 | [contracts/application-provider-config.md](./contracts/application-provider-config.md) | ✅ 完成，application.yml 格式 + 校验规则 + 反例 |
| Profile 契约 | [contracts/profile-provider-section.md](./contracts/profile-provider-section.md) | ✅ 完成，Profile provider/tools 段 + 加载错误传播 |
| 快速验证 | [quickstart.md](./quickstart.md) | ✅ 完成，9 步 demo + 验收清单 + 故障排查 |

**Plan 完成。下一步：跑 `/speckit.tasks` 生成实施任务清单。**
