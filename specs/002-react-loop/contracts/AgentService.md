# Contract: `AgentService`

**Package**: `io.oryxos.core`
**Module**: `oryxos-core`
**Stability**: Stable
**Consumers**: `oryxos-channel-cli`（CLI `chat` 子命令，US-2 阶段 stub）、`oryxos-web`（`POST /api/v1/agents/{name}/invoke`，US-5）、`AgentScheduler`（clock-push，US-5）
**Implementors**: `DefaultAgentService`

---

## 1. 接口签名

```java
package io.oryxos.core;

public interface AgentService {
    /**
     * 处理一条用户消息，返回 Agent 的最终回复。这是 OryxOS 所有触发源的统一入口。
     *
     * <p>调用前要求：
     * <ul>
     *   <li>{@code session.profileName()} 已经指向一个**已注册**的 Profile。</li>
     *   <li>{@code session} 已通过 {@code SessionRepository} 持久化（id 已知）。</li>
     * </ul>
     *
     * <p>调用期间：
     * <ul>
     *   <li>设置 thread-local {@code ProfileContext}；在返回前必然清除（finally 块）。</li>
     *   <li>调 {@code ReActLoop.run(profile, session, userMessage)}。</li>
     * </ul>
     *
     * @throws IllegalArgumentException Session 引用的 Profile 未注册 / 未配置 Provider
     * @throws LlmInvocationException Provider 抛出的原样异常
     */
    LoopResult process(Session session, String userMessage);
}
```

---

## 2. 契约条款

| ID | 条款 | 强制性 | 验证方式 |
|----|------|--------|----------|
| C-AS-1 | 唯一公开入口；CLI / Web / Scheduler 全部走 `process(...)` | MUST | spec FR-001 / FR-021 |
| C-AS-2 | `ProfileContext.set(...)` 在入口一次性设置，`clear()` 在 finally 块清理 | MUST | spec FR-017 / I-06 |
| C-AS-3 | `Session.profileName()` 不在注册表时立即抛 `IllegalArgumentException` | MUST | spec FR-002 |
| C-AS-4 | Provider 未配置同样抛 `IllegalArgumentException`（与 C-AS-3 合并语义） | MUST | spec FR-002 / Edge case |
| C-AS-5 | 异常路径不破坏 `ProfileContext` 与 `Session.appendMessage` 已写入的状态 | MUST | spec NFR-002 / I-06 |
| C-AS-6 | 返回值 `LoopResult` 是构造完成的 record；调用方可直接 `.finalText()` 取用户最终答复 | MUST | spec FR-013 |
| C-AS-7 | 不接受 `null` session / null userMessage——抛 `NullPointerException`（Java 标准） | MUST | 接口语义 |
| C-AS-8 | 同步 API；不暴露 `.processAsync(...)` | MUST | R-7 论证 |

---

## 3. 三个触发源的对接方式

| 触发源 | 文件 | 调用方式 |
|--------|------|----------|
| CLI chat | `oryxos-channel-cli/CliChannel.java` | `agentService.process(session, line)` 在用户敲完一行后 |
| Web Service `POST /api/v1/agents/{name}/invoke` | `oryxos-web`（US-5 阶段） | Controller 反序列化请求 → `agentService.process(...)` |
| AgentScheduler | `oryxos-core` 内 `AgentScheduler`（US-5 真正实现） | Cron 触发 → `agentService.process(session, message_from_profile.schedules)` |

三种触发源**不应该** 在 `AgentService` 内部区别对待——`AgentService.process` 不感知消息从哪个入口来（spec FR-001 / CLAUDE.md §9.3）。

---

## 4. 与 spec 的对应

| spec 条目 | 对应契约 |
|----------|---------|
| spec FR-001：单一公开入口 | C-AS-1 |
| spec FR-002：Profile 缺失 fail-fast | C-AS-3 / C-AS-4 |
| spec FR-017：`ProfileContext` `finally` 清零 | C-AS-2 / C-AS-5 / I-06 |
| spec FR-021：触发源无关 | C-AS-1 |
| spec SC-005：每日天气 Demo 端到端 | 三类触发源同一接口 |
| spec SC-006：每日科技日报 Demo 端到端 | 同上 |

---

## 5. 实现骨架（`DefaultAgentService`）

```java
@Service
public final class DefaultAgentService implements AgentService {

    private final ProfileRegistry profileRegistry;     // 由 ContextLoader 实现
    private final ReActLoop reactLoop;

    public DefaultAgentService(ProfileRegistry profileRegistry, ReActLoop reactLoop) {
        this.profileRegistry = profileRegistry;
        this.reactLoop = reactLoop;
    }

    @Override
    public LoopResult process(Session session, String userMessage) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(userMessage, "userMessage");

        // 1. 解析 Profile
        Profile profile = profileRegistry.find(session.profileName())
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown profile: '" + session.profileName() + "'; registered profiles: "
                + profileRegistry.names()));

        // 2. 设置 ProfileContext（finally 保证清理）
        ProfileContext.Snapshot snap = new ProfileContext.Snapshot(
            profile.name(),
            session.id(),
            new AtomicInteger(0)
        );
        ProfileContext.set(snap);
        try {
            // 3. 委托 ReActLoop
            return reactLoop.run(profile, session, userMessage);
        } finally {
            // 4. 清理（无论正常/异常路径）
            ProfileContext.clear();
        }
    }
}
```

---

## 6. 测试义务

| 测试类 | 断言 |
|--------|------|
| `DefaultAgentServiceTest#happyPath` | mock ReActLoop 返回固定 LoopResult；AgentService 返回相同 |
| `DefaultAgentServiceTest#unknownProfileThrows` | ProfileRegistry 返回空 → `IllegalArgumentException` |
| `DefaultAgentServiceTest#profileContextClearedOnException` | ReActLoop 抛异常后 `ProfileContext.current() == Optional.empty()` |
| `DefaultAgentServiceTest#profileContextClearedOnSuccess` | ReActLoop 正常返回后 `ProfileContext.current() == Optional.empty()` |
| `AgentServiceE2EIT#dailyWeatherEndToEnd` | 启动完整 Spring Boot 应用 + WireMock 模拟 deepseek → "今日天气晴" 文本回复 |
