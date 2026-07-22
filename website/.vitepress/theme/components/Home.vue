<script setup>
import { computed } from 'vue'
import { useData } from 'vitepress'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh, en) => (isZh.value ? zh : en)

const capabilities = computed(() => [
  {
    icon: '🔌',
    title: t('多 LLM Provider 路由', 'Multi-LLM Provider Routing'),
    subtitle: t(
      'DeepSeek · Qwen · Kimi · Zhipu · Doubao · Ollama',
      'DeepSeek · Qwen · Kimi · Zhipu · Doubao · Ollama',
    ),
    code: `// Spring AI Alibaba + 自定义 Provider 映射
@Bean(name = "deepseek")
ChatModel deepseekChatModel() {
    return DeepSeekChatModel.builder()
        .apiKey("\${DEEPSEEK_API_KEY}")
        .model("deepseek-chat")
        .temperature(0.7)
        .build();
}

providerService.register("deepseek", deepseekChatModel());
providerService.register("kimi",    kimiChatModel());`,
  },
  {
    icon: '🧠',
    title: t('自实现 ReAct 循环', 'Self-implemented ReAct Loop'),
    subtitle: t('Reason + Act · Profile 上下文 · max_iterations 守门', 'Reason + Act · profile context · max_iterations cap'),
    code: `// ReActLoop 不感知消息来自 CLI / Web / Scheduler
for (int i = 0; i < profile.maxIterations(); i++) {
    Prompt prompt = promptBuilder.build(session, profile);
    LlmResponse resp = providerService.call(profile, prompt);
    session.append(resp);
    if (!resp.hasToolCall()) break;
    ToolResult r = toolExecutor.execute(profile, resp.toolCall());
    session.append(r);
}
return session.lastMessage();`,
  },
  {
    icon: '💾',
    title: t('三层记忆门面', 'Three-layer Memory Facade'),
    subtitle: t('会话 · 长期 MEMORY.md · 三档可插拔后端', 'session · long-term MEMORY.md · 3 pluggable backends'),
    code: `// MemoryService 是 ReAct 的唯一记忆入口
memory.write("user.prefers",    "verbose",          Scope.CORE);
memory.write("archive.2025-07", weeklyDigest,      Scope.ARCHIVE);
List<String> hits = memory.findByKeyword("prefer");

// 后端可插拔（核心阶段全部实现）：
//   MarkdownMemoryStore · SqliteMemoryStore · Mem0MemoryStore`,
  },
  {
    icon: '🛠️',
    title: t('插件 Tool + 沙箱', 'Plugin Tools + Sandbox'),
    subtitle: t('内置 9 Tool · MCP 接入 · 应用层白名单', '9 built-in tools · MCP clients · app-layer whitelist'),
    code: `// 三档接入方式（同一接口 OryxTool）
@Tool(description = "GET a whitelisted URL")
String httpGet(@P("url") String url) {
    sandbox.enforce(ActionType.HTTP_REQUEST, url);   // ← 域白名单
    return HttpClient.send(url);
}

// 零代码：.oryxos/agents/<name>/AGENT.md + skills/*.md
// 轻代码：自实现 MCP server
// 重代码：@OryxTool 注解 Java bean`,
  },
  {
    icon: '🌐',
    title: t('REST API · 10 端点', 'REST API · 10 Endpoints'),
    subtitle: t(
      'Session / Agent 调用 / Profile / Memory / Tool 查询',
      'session · agent invoke · profile · memory · tool discovery',
    ),
    code: `# 从任何外部系统触发 Agent
curl -X POST http://localhost:8080/api/v1/agents/daily-weather/invoke \\
  -H "Content-Type: application/json" \\
  -d '{"message":"今天上海天气怎么样？"}'

# Spring MVC + Java 21 virtual threads
# 同一条 AgentService 链路：CLI · Web · Scheduler 三入口共用`,
  },
])

const scenarios = computed(() => [
  {
    num: '01',
    title: t('每日定时报表', 'Scheduled daily reports'),
    desc: t(
      'AgentScheduler 到点推送日报（天气 / 科技 / GitHub trending）。零代码，只写一个 AGENT.md。',
      'AgentScheduler fires the agent at a cron time — daily weather, tech digest, GitHub trending. Zero Java code, just an AGENT.md.',
    ),
  },
  {
    num: '02',
    title: t('工单自动派发', 'Auto ticket dispatch'),
    desc: t(
      '客服 Agent 接住用户提问，从工具箱查 FAQ 兜底，复杂问题升级到人工队列。',
      'A customer-service agent handles incoming questions, looks up FAQ via tool, escalates complex cases to a human queue.',
    ),
  },
  {
    num: '03',
    title: t('客户咨询 + 长期偏好', 'Customer Q&A + long-term preference'),
    desc: t(
      'Agent 读取 MEMORY.md 知道这位用户喜欢"简短+表格"风格，下次自动按偏好回答。',
      'Agent reads MEMORY.md to recall "this user prefers short + table format" and answers accordingly next time.',
    ),
  },
  {
    num: '04',
    title: t('内部知识库问答', 'Internal knowledge base Q&A'),
    desc: t(
      'Agent 接 read_file 工具，按需翻 REFERENCE.md / skills/*.md，不预加载全部内容。',
      'Agent uses read_file to fetch REFERENCE.md / skills/*.md on demand — never preloads the full corpus.',
    ),
  },
  {
    num: '05',
    title: t('多 Agent 协作', 'Multi-Agent collaboration'),
    desc: t(
      '主 Agent 通过 mcp_servers 列表调度多个子 Agent，结果汇总后由主 Agent 出统一答复。',
      'A orchestrator agent dispatches sub-agents via mcp_servers, aggregates their results, and emits one unified answer.',
    ),
  },
  {
    num: '06',
    title: t('审计驱动的运维', 'Audit-driven operations'),
    desc: t(
      'tool_invocations 和 llm_calls 表 day-one 落库，事后可 SQL 拉出每次调用的输入/输出/耗时。',
      'tool_invocations and llm_calls are persisted from day one — replay any past call from SQL for compliance.',
    ),
  },
])
</script>

<template>
  <div class="oryx-page">

    <!-- ── HERO ── -->
    <section class="oryx-hero">
      <div class="oryx-hero-inner">
        <div class="oryx-badge">
          <span class="oryx-badge-dot"></span>
          {{ t('企业级 Agent OS · Java / Spring Boot', 'Enterprise Agent OS · Java / Spring Boot') }}
        </div>

        <h1 class="oryx-title">
          <span class="oryx-title-name">OryxOS</span>
        </h1>

        <p class="oryx-title-sub">
          {{ t('严监管企业专属的 Agent 运行时内核', 'A private Agent OS runtime for regulated enterprises') }}
        </p>

        <p class="oryx-hero-desc">
          {{
            t(
              'OryxOS 把多 LLM Provider、ReAct 引擎、三层记忆、插件 Tool 和 REST API 整合在同一个 Java 进程里——单二进制部署在你自己的 K8s 上。让业务 Agent 之间的协作 just work，数据完全不离开企业内网。',
              'OryxOS unifies multi-LLM routing, a self-implemented ReAct engine, three-layer memory, plugin tools, and a REST API in a single Java process — one executable JAR, deployed on your own K8s. Agent-to-agent collaboration, just works — with data that never leaves your perimeter.',
            )
          }}
        </p>

        <div class="oryx-hero-actions">
          <a class="oryx-btn-primary" :href="t('/zh/docs/', '/docs/')">
            {{ t('开始使用', 'Get Started') }} →
          </a>
          <a class="oryx-btn-ghost" :href="t('/zh/docs/architecture', '/docs/architecture')">
            {{ t('系统架构', 'Architecture') }}
          </a>
          <a class="oryx-btn-ghost" href="https://github.com/oryxos/oryxos" target="_blank" rel="noopener">
            GitHub
          </a>
        </div>

        <div class="oryx-hero-note">
          {{
            t(
              'JDK 21 · Spring Boot 3.x · Spring AI · MCP · SQLite · Maven 9 模块',
              'JDK 21 · Spring Boot 3.x · Spring AI · MCP · SQLite · Maven 9 modules',
            )
          }}
        </div>
      </div>
    </section>

    <!-- ── PROBLEM + COMPARE ── -->
    <section class="oryx-section">
      <div class="oryx-section-inner">
        <div class="oryx-problem">
          <div class="oryx-problem-text">
            <h2 class="oryx-section-title">
              {{ t('两个核心问题', 'Two Foundational Problems') }}
            </h2>
            <p>
              {{
                t(
                  '任何企业级多 Agent 系统，都会遇到同样的两个问题。',
                  'Every enterprise multi-agent system hits the same two problems.',
                )
              }}
            </p>
            <p class="oryx-problem-item">
              <strong>{{ t('① Agent 如何在自己的技术栈里跑起来？', '① How do agents run inside the existing stack?') }}</strong>
              {{
                t(
                  '银行 / 政府 / 电信的 IT 主力是 Java。Python + Node.js 的 Agent 框架再先进，进不了 Nacos / Sentinel / SkyWalking 这套基础设施。',
                  'Banks, governments, telcos run on Java. Python- or Node-based agent frameworks — however advanced — cannot plug into Nacos, Sentinel, SkyWalking.',
                )
              }}
            </p>
            <p class="oryx-problem-item">
              <strong>{{ t('② Agent 跑起来之后，如何审计 + 如何私有？', '② How do you audit and keep them private?') }}</strong>
              {{
                t(
                  '公有云 SaaS 把对话发到厂商域。每次 tool 调用、每次 LLM 调用都必须可追溯。',
                  'Public-cloud SaaS ships your prompts to vendor domains. Every tool call and every LLM call must be traceable.',
                )
              }}
            </p>
            <p class="oryx-solution-line">
              {{
                t(
                  'OryxOS 专门解决这两个问题，所以业务团队可以专注 Agent 逻辑，而不是合规和基础设施。',
                  'OryxOS solves exactly these two problems — so your business teams focus on agent logic, not compliance and infrastructure.',
                )
              }}
            </p>
          </div>
          <div class="oryx-problem-compare">
            <div class="oryx-compare-item oryx-compare-bad">
              <div class="oryx-compare-label">{{ t('今天的做法', 'Today') }}</div>
              <div class="oryx-compare-rows">
                <div class="oryx-compare-row">
                  <span class="oryx-compare-icon">✗</span>
                  <span>{{ t('Python/Node 为主，进不了 Java 技术栈', 'Python/Node only — cannot plug into Java stacks') }}</span>
                </div>
                <div class="oryx-compare-row">
                  <span class="oryx-compare-icon">✗</span>
                  <span>{{ t('公有云 SaaS，数据出域，合规审查难过', 'Public SaaS — data leaves your perimeter') }}</span>
                </div>
                <div class="oryx-compare-row">
                  <span class="oryx-compare-icon">✗</span>
                  <span>{{ t('没有审计表，黑盒运行', 'No audit trail — black box operation') }}</span>
                </div>
                <div class="oryx-compare-row">
                  <span class="oryx-compare-icon">✗</span>
                  <span>{{ t('每个团队重写一套多 Agent 框架', 'Every team rebuilds the multi-agent framework') }}</span>
                </div>
              </div>
            </div>
            <div class="oryx-compare-item oryx-compare-good">
              <div class="oryx-compare-label">OryxOS</div>
              <div class="oryx-compare-rows">
                <div class="oryx-compare-row">
                  <span class="oryx-compare-icon oryx-icon-ok">✓</span>
                  <span>{{ t('Java 原生，对接 Nacos / Sentinel / SkyWalking', 'Java-native — slots into Nacos / Sentinel / SkyWalking') }}</span>
                </div>
                <div class="oryx-compare-row">
                  <span class="oryx-compare-icon oryx-icon-ok">✓</span>
                  <span>{{ t('本地私有部署，单二进制 fat JAR', 'On-prem deployment — single fat JAR') }}</span>
                </div>
                <div class="oryx-compare-row">
                  <span class="oryx-compare-icon oryx-icon-ok">✓</span>
                  <span>{{ t('tool_invocations / llm_calls 表 day-one 落库', 'tool_invocations / llm_calls tables — day-one writes') }}</span>
                </div>
                <div class="oryx-compare-row">
                  <span class="oryx-compare-icon oryx-icon-ok">✓</span>
                  <span>{{ t('AGENT.md 零代码定义，目录即 Agent', 'AGENT.md zero-code — directory is the agent') }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── FLOW DIAGRAM ── -->
    <section class="oryx-section oryx-flow-section">
      <div class="oryx-section-inner">
        <img src="/flow.svg" alt="OryxOS architecture flow" class="oryx-flow-img" />
      </div>
    </section>

    <!-- ── CAPABILITIES ── -->
    <section class="oryx-section oryx-primitives-section">
      <div class="oryx-section-inner oryx-primitives-inner">
        <div class="oryx-section-header">
          <div class="oryx-section-tag">{{ t('核心能力', 'Core Capabilities') }}</div>
          <h2 class="oryx-section-title">
            {{
              t(
                '五大核心能力 · 同一条 AgentService 链路',
                'Five core capabilities · one AgentService chain',
              )
            }}
          </h2>
        </div>
        <div class="oryx-primitives">
          <div
            v-for="p in capabilities"
            :key="p.title"
            class="oryx-primitive"
          >
            <div class="oryx-primitive-header">
              <span class="oryx-primitive-icon">{{ p.icon }}</span>
              <div>
                <h3 class="oryx-primitive-title">{{ p.title }}</h3>
                <p class="oryx-primitive-subtitle">{{ p.subtitle }}</p>
              </div>
            </div>
            <pre class="oryx-code"><code>{{ p.code }}</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- ── SCENARIOS ── -->
    <section class="oryx-section">
      <div class="oryx-section-inner">
        <div class="oryx-section-header">
          <div class="oryx-section-tag">{{ t('真实场景', 'Real Scenarios') }}</div>
          <h2 class="oryx-section-title">
            {{ t('六个企业级使用场景', 'Six enterprise use cases') }}
          </h2>
        </div>
        <div class="oryx-scenarios">
          <div v-for="s in scenarios" :key="s.num" class="oryx-scenario">
            <div class="oryx-scenario-num">{{ s.num }}</div>
            <div>
              <h3 class="oryx-scenario-title">{{ s.title }}</h3>
              <p class="oryx-scenario-desc">{{ s.desc }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── INTEGRATION ── -->
    <section class="oryx-section oryx-sdk-section">
      <div class="oryx-section-inner">
        <div class="oryx-section-header">
          <div class="oryx-section-tag">{{ t('接入方式', 'Integration') }}</div>
          <h2 class="oryx-section-title">
            {{ t('三种接入方式 · 按需选择', 'Three ways to connect — pick what fits') }}
          </h2>
        </div>
        <div class="oryx-sdk-cards">
          <div class="oryx-sdk-card">
            <div class="oryx-sdk-card-icon">📦</div>
            <h3 class="oryx-sdk-card-title">
              {{ t('Maven 依赖', 'Maven Dependency') }}
            </h3>
            <p class="oryx-sdk-card-desc">
              {{
                t(
                  '在自己的 Spring Boot 工程里加 oryxos-boot 依赖，几行配置就能拉起一个完整的 Agent 运行时。',
                  'Drop oryxos-boot into your own Spring Boot project. A few lines of config boots a full agent runtime.',
                )
              }}
            </p>
            <div class="oryx-sdk-installs">
              <code>&lt;dependency&gt;</code>
              <code>  &lt;groupId&gt;io.oryxos&lt;/groupId&gt;</code>
              <code>  &lt;artifactId&gt;oryxos-boot&lt;/artifactId&gt;</code>
              <code>  &lt;version&gt;1.0.0&lt;/version&gt;</code>
              <code>&lt;/dependency&gt;</code>
            </div>
          </div>
          <div class="oryx-sdk-card oryx-sdk-card-featured">
            <div class="oryx-sdk-card-icon">🚀</div>
            <h3 class="oryx-sdk-card-title">
              {{ t('Spring Boot Starter', 'Spring Boot Starter') }}
            </h3>
            <p class="oryx-sdk-card-desc">
              {{
                t(
                  'Spring Boot 自动装配。声明 Profile 文件位置 + Provider API key，应用启动即自带 5 大能力。',
                  'Spring Boot auto-configuration. Declare profile location + provider API key — the app boots with all 5 capabilities wired.',
                )
              }}
            </p>
            <div class="oryx-sdk-installs">
              <code>application.yml:</code>
              <code>oryxos:</code>
              <code>  profiles-dir: ./agents</code>
              <code>  provider:</code>
              <code>    deepseek: \${DEEPSEEK_API_KEY}</code>
            </div>
          </div>
          <div class="oryx-sdk-card">
            <div class="oryx-sdk-card-icon">💻</div>
            <h3 class="oryx-sdk-card-title">{{ t('CLI', 'CLI') }}</h3>
            <p class="oryx-sdk-card-desc">
              {{
                t(
                  'Picocli 主入口，12 个子命令：init / chat / serve / gateway / profile / provider / tool / session。无需写 Java。',
                  'Picocli main entry — 12 sub-commands: init / chat / serve / gateway / profile / provider / tool / session. No Java needed.',
                )
              }}
            </p>
            <div class="oryx-sdk-installs">
              <code>$ oryxos init</code>
              <code>$ oryxos chat --profile daily-weather</code>
              <code>$ oryxos serve    # listens on :8080</code>
              <code>$ oryxos gateway  # serve + scheduled jobs</code>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ── CTA ── -->
    <section class="oryx-section oryx-cta-section">
      <div class="oryx-section-inner">
        <div class="oryx-cta">
          <h2 class="oryx-cta-title">{{ t('开始构建', 'Start Building') }}</h2>
          <p class="oryx-cta-desc">
            {{
              t(
                '克隆仓库、初始化工作区、第一次跟 Agent 对话——5 分钟。',
                'Clone, init, first chat — under 5 minutes.',
              )
            }}
          </p>
          <pre class="oryx-code oryx-cta-code"><code># 1. 克隆仓库
git clone https://github.com/oryxos/oryxos.git
cd oryxos

# 2. 构建单二进制 fat JAR
mvn -pl oryxos-boot -am clean package -DskipTests

# 3. 初始化工作区 + 启动 gateway
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar gateway

# 4. CLI 第一次跟 Agent 对话
oryxos chat --profile daily-weather</code></pre>
          <div class="oryx-cta-links">
            <a class="oryx-btn-primary" :href="t('/zh/docs/', '/docs/')">
              {{ t('查看文档', 'Read the Docs') }}
            </a>
            <a class="oryx-btn-ghost" href="https://github.com/oryxos/oryxos" target="_blank" rel="noopener">
              GitHub
            </a>
          </div>
        </div>
      </div>
    </section>

  </div>
</template>

<style scoped>
.oryx-page {
  min-height: 100vh;
  background: #ffffff;
  color: #000000;
  font-family: inherit;
}

/* ── Hero ── */
.oryx-hero {
  position: relative;
  padding: 100px 24px 80px;
  text-align: center;
  overflow: hidden;
}
.oryx-hero-inner {
  position: relative;
  max-width: 760px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.oryx-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  border-radius: 20px;
  border: 1px solid #d4d4d4;
  background: #f5f5f5;
  color: #555555;
  font-size: 12px;
  margin-bottom: 28px;
}
.oryx-badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #000000;
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(1.4); }
}
.oryx-title {
  margin: 0 0 12px;
  line-height: 1;
}
.oryx-title-name {
  font-size: clamp(72px, 14vw, 120px);
  font-weight: 900;
  letter-spacing: -0.03em;
  color: #000000;
}
.oryx-title-sub {
  font-size: 18px;
  color: #666666;
  margin: 0 0 20px;
}
.oryx-hero-desc {
  font-size: 16px;
  line-height: 1.7;
  color: #444444;
  max-width: 600px;
  margin: 0 0 32px;
}
.oryx-hero-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
  margin-bottom: 20px;
}
.oryx-btn-primary {
  padding: 11px 28px;
  border-radius: 8px;
  background: #000000;
  color: #ffffff;
  font-weight: 600;
  font-size: 14px;
  text-decoration: none;
  transition: opacity 0.2s, transform 0.15s;
}
.oryx-btn-primary:hover { opacity: 0.75; transform: translateY(-1px); }
.oryx-btn-ghost {
  padding: 11px 28px;
  border-radius: 8px;
  border: 1px solid #d4d4d4;
  color: #333333;
  font-weight: 600;
  font-size: 14px;
  text-decoration: none;
  transition: border-color 0.2s, background 0.2s;
}
.oryx-btn-ghost:hover { border-color: #000000; background: #f5f5f5; }
.oryx-hero-note {
  font-size: 12px;
  color: #999999;
}

/* ── Section ── */
.oryx-section { padding: 72px 24px; }
.oryx-section-inner { max-width: 1000px; margin: 0 auto; }
.oryx-primitives-inner { max-width: 1400px; }
.oryx-section-header { text-align: center; margin-bottom: 48px; }
.oryx-section-tag {
  display: inline-block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #555555;
  padding: 4px 12px;
  border-radius: 20px;
  border: 1px solid #d4d4d4;
  background: #f5f5f5;
  margin-bottom: 14px;
}
.oryx-section-title {
  font-size: clamp(22px, 4vw, 32px);
  font-weight: 700;
  color: #000000;
  margin: 0 0 12px;
}
.oryx-section-desc {
  font-size: 15px;
  color: #666666;
  max-width: 600px;
  margin: 0 auto;
  line-height: 1.6;
}

/* ── Problem + Compare ── */
.oryx-problem {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 48px;
  align-items: start;
}
.oryx-problem-text p {
  color: #666666;
  line-height: 1.7;
  margin: 0 0 14px;
  font-size: 15px;
}
.oryx-problem-item strong {
  color: #000000;
  display: block;
  margin-bottom: 4px;
}
.oryx-solution-line {
  color: #000000 !important;
  font-weight: 600;
}
.oryx-problem-compare {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.oryx-compare-item {
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #e5e5e5;
}
.oryx-compare-bad { background: #fafafa; }
.oryx-compare-good { background: #f5f5f5; border-color: #d4d4d4; }
.oryx-compare-label {
  font-size: 11px;
  font-weight: 700;
  color: #999999;
  margin-bottom: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.oryx-compare-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.oryx-compare-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 13px;
  color: #555555;
  line-height: 1.5;
}
.oryx-compare-icon {
  flex-shrink: 0;
  font-style: normal;
  color: #bbbbbb;
  font-weight: 700;
  width: 14px;
}
.oryx-icon-ok { color: #000000; }

/* ── Primitives (capabilities) ── */
.oryx-primitives-section { background: #f5f5f5; }
.oryx-primitives {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-auto-rows: 1fr;
  gap: 16px;
}
.oryx-primitive {
  padding: 20px;
  border-radius: 14px;
  border: 1px solid #e5e5e5;
  background: #ffffff;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: border-color 0.2s, box-shadow 0.2s;
  min-width: 0;
  overflow: hidden;
}
.oryx-primitive .oryx-code { flex: 1; }
.oryx-primitive:hover {
  border-color: #000000;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}
.oryx-primitive-header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.oryx-primitive-icon {
  font-size: 28px;
  flex-shrink: 0;
}
.oryx-primitive-title {
  font-size: 17px;
  font-weight: 700;
  color: #000000;
  margin: 0 0 2px;
}
.oryx-primitive-subtitle {
  font-size: 12px;
  color: #999999;
  margin: 0;
}
.oryx-code {
  background: #f5f5f5;
  border: 1px solid #e5e5e5;
  border-radius: 8px;
  padding: 14px 16px;
  font-size: 12px;
  line-height: 1.6;
  color: #333333;
  overflow-x: auto;
  margin: 0;
  white-space: pre;
}
.oryx-code code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  background: none;
  color: inherit;
}

/* ── Scenarios ── */
.oryx-scenarios {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}
.oryx-scenario {
  display: flex;
  gap: 16px;
  padding: 20px;
  border-radius: 12px;
  border: 1px solid #e5e5e5;
  background: #fafafa;
}
.oryx-scenario-num {
  font-size: 28px;
  font-weight: 900;
  color: #e5e5e5;
  line-height: 1;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
.oryx-scenario-title {
  font-size: 15px;
  font-weight: 600;
  color: #000000;
  margin: 0 0 6px;
}
.oryx-scenario-desc {
  font-size: 13px;
  color: #666666;
  line-height: 1.6;
  margin: 0;
}

/* ── SDK cards ── */
.oryx-sdk-section { background: #f5f5f5; }
.oryx-sdk-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}
.oryx-sdk-card {
  background: #ffffff;
  border: 1px solid #e5e5e5;
  border-radius: 16px;
  padding: 28px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.oryx-sdk-card-featured { border-color: #000000; }
.oryx-sdk-card-icon { font-size: 28px; }
.oryx-sdk-card-title {
  font-size: 17px;
  font-weight: 700;
  color: #000000;
  margin: 0;
}
.oryx-sdk-card-desc {
  font-size: 14px;
  color: #666666;
  line-height: 1.6;
  margin: 0;
  flex: 1;
}
.oryx-sdk-installs {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.oryx-sdk-installs code {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  background: #f5f5f5;
  border: 1px solid #e5e5e5;
  border-radius: 6px;
  padding: 5px 10px;
  color: #000000;
  display: block;
}

/* ── CTA ── */
.oryx-cta-section { background: #f5f5f5; }
.oryx-cta { text-align: center; max-width: 680px; margin: 0 auto; }
.oryx-cta-title {
  font-size: 28px;
  font-weight: 700;
  color: #000000;
  margin: 0 0 12px;
}
.oryx-cta-desc {
  font-size: 15px;
  color: #666666;
  margin: 0 0 24px;
}
.oryx-cta-code {
  text-align: left;
  margin-bottom: 28px;
}
.oryx-cta-links {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}

/* ── Flow diagram ── */
.oryx-flow-section { padding: 0 24px 72px; }
.oryx-flow-img {
  width: 100%;
  display: block;
  border: 1px solid #e5e5e5;
  border-radius: 12px;
}

/* ── Responsive ── */
@media (max-width: 900px) {
  .oryx-sdk-cards { grid-template-columns: 1fr; }
}
@media (max-width: 768px) {
  .oryx-hero { padding: 72px 20px 60px; }
  .oryx-problem { grid-template-columns: 1fr; }
  .oryx-primitives { grid-template-columns: 1fr; }
  .oryx-scenarios { grid-template-columns: 1fr; }
  .oryx-section { padding: 48px 20px; }
}
</style>