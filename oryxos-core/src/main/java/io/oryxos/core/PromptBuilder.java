package io.oryxos.core;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 四段式 Prompt 组装器 —— 按 [CLAUDE.md §9.2](../../../../../CLAUDE.md) + [data-model.md §3.6](../../../../../specs/002-react-loop/data-model.md)：
 * <ol>
 *   <li>systemBlocks —— AGENT.md 内容 + Bootstrap 文件（按 Profile 声明顺序）+ 当前本地日期时间行</li>
 *   <li>memoryBlocks —— {@link MemoryInjector#inject}</li>
 *   <li>historyBlocks —— 最近 N 条历史消息（按 {@code settings.maxHistoryTurns} 截断）</li>
 *   <li>toolSchemas —— {@link ToolSchemaProvider#schemasFor}</li>
 * </ol>
 *
 * <p>无 AGENT.md 文件系统的当前阶段，所有内容由 {@link BootstrapLoader} 注入；
 * 默认 {@link BootstrapLoader.NoopBootstrapLoader} 返回空内容。
 *
 * <p>本类**没有** {@code @Component} —— Spring 装配由
 * {@link io.oryxos.core.config.PromptBuilderConfig} 显式 {@code @Bean} 方法完成，
 * 避免"两个 public 构造 + 无默认构造"导致的
 * {@code NoSuchMethodException: PromptBuilder.&lt;init&gt;()} 启动失败。
 */
public class PromptBuilder {

    /** 系统消息的标准 role 标识（OpenAI 兼容）。 */
    public static final String ROLE_SYSTEM = "system";
    /** 助手消息标识（OpenAI 兼容）。 */
    public static final String ROLE_ASSISTANT = "assistant";
    /** 用户消息标识。 */
    public static final String ROLE_USER = "user";
    /** 工具消息标识。 */
    public static final String ROLE_TOOL = "tool";

    private static final DateTimeFormatter HUMAN_TS = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

    private final MemoryInjector memoryInjector;
    private final ToolSchemaProvider toolSchemaProvider;
    private final BootstrapLoader bootstrapLoader;
    private final Clock clock;

    public PromptBuilder(
        MemoryInjector memoryInjector,
        ToolSchemaProvider toolSchemaProvider,
        BootstrapLoader bootstrapLoader,
        Clock clock
    ) {
        this.memoryInjector = memoryInjector == null
            ? new MemoryInjector.NoopMemoryInjector() : memoryInjector;
        this.toolSchemaProvider = toolSchemaProvider == null
            ? new ToolSchemaProvider.NoopToolSchemaProvider() : toolSchemaProvider;
        this.bootstrapLoader = bootstrapLoader == null
            ? new BootstrapLoader.NoopBootstrapLoader() : bootstrapLoader;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /** 便捷构造（生产 Spring 装配用）—— 用系统时钟 + Noop loader。 */
    public PromptBuilder(MemoryInjector memoryInjector, ToolSchemaProvider toolSchemaProvider) {
        this(memoryInjector, toolSchemaProvider, new BootstrapLoader.NoopBootstrapLoader(), Clock.systemDefaultZone());
    }

    /**
     * 组装完整 Prompt。
     *
     * @param profile 当前 Profile
     * @param session 当前 Session（历史来源）
     * @return 组装后的 Prompt
     */
    public Prompt build(Profile profile, Session session) {
        List<Map<String, Object>> system = buildSystemBlocks(profile);
        List<Map<String, Object>> memory = buildMemoryBlocks(profile, session);
        List<Map<String, Object>> history = buildHistoryBlocks(session, profile.settings().maxHistoryTurns());
        List<Map<String, Object>> schemas = toolSchemaProvider.schemasFor(profile);

        return new Prompt(system, memory, history, schemas);
    }

    /**
     * 段 2：Memory 注入。把 {@link MemoryInjector#inject} 返回的 {@link Message} 序列翻译为
     * {@code Map<String,Object>} block（与 system/history 同形态，便于 LLM 端点统一处理）。
     */
    List<Map<String, Object>> buildMemoryBlocks(Profile profile, Session session) {
        List<Message> injected = memoryInjector.inject(profile, session);
        if (injected == null || injected.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(injected.size());
        for (Message m : injected) {
            out.add(messageToBlock(m));
        }
        return List.copyOf(out);
    }

    /**
     * 段 1：系统提示。
     *
     * <p>顺序（[CLAUDE.md §9.2](../../../../../CLAUDE.md)）：
     * <ol>
     *   <li>AGENT.md 正文（目前 US-2 桩：空，由 BootstrapLoader 注入）</li>
     *   <li>Bootstrap 文件（按 {@code profile.bootstrap()} 顺序）</li>
     *   <li>当前本地日期时间行（FR-005：LLM 自己不知道今天几号）</li>
     * </ol>
     */
    List<Map<String, Object>> buildSystemBlocks(Profile profile) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, String> bootstrap = bootstrapLoader.load(profile);
        // 段 1.a: AGENT.md 正文（CLAUDE.md §9.2 步骤 1 第 1 项）
        String agentMd = bootstrap.getOrDefault("AGENT.md", "");
        if (!agentMd.isEmpty()) {
            blocks.add(systemBlock(agentMd));
        }
        // 段 1.b: Bootstrap 文件（按声明顺序；CLAUDE.md §9.2 步骤 1 第 2 项）
        for (String name : profile.bootstrap()) {
            if (name.equals("AGENT.md")) {
                continue; // 已在段 1.a 注入
            }
            String content = bootstrap.getOrDefault(name, "");
            if (!content.isEmpty()) {
                blocks.add(systemBlock(content));
            }
        }
        // 段 1.c: 当前本地日期时间行（CLAUDE.md §9.2 步骤 1 第 3 项；FR-005）
        blocks.add(systemBlock(currentDateTimeLine()));
        return List.copyOf(blocks);
    }

    /**
     * 段 3：截断历史消息。保留最近 {@code maxHistoryTurns * 2} 条（每轮 = 1 user + 1 assistant）。
     */
    List<Map<String, Object>> buildHistoryBlocks(Session session, int maxHistoryTurns) {
        if (maxHistoryTurns < 1) {
            throw new IllegalArgumentException("maxHistoryTurns must be >= 1, got " + maxHistoryTurns);
        }
        List<Message> messages = session.messages();
        // 一轮 = 一对 user+assistant；为 P1 简化，按消息数截断
        int keep = Math.min(messages.size(), maxHistoryTurns * 2);
        int from = Math.max(0, messages.size() - keep);
        List<Map<String, Object>> out = new ArrayList<>(keep);
        for (int i = from; i < messages.size(); i++) {
            out.add(messageToBlock(messages.get(i)));
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> systemBlock(String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", ROLE_SYSTEM);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> messageToBlock(Message m) {
        Map<String, Object> block = new LinkedHashMap<>();
        switch (m.role()) {
            case USER -> {
                block.put("role", ROLE_USER);
                block.put("content", m.content());
            }
            case ASSISTANT -> {
                block.put("role", ROLE_ASSISTANT);
                if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                    // 把 ToolCall 列表平铺进 assistant block（tool_calls 字段）
                    List<Map<String, Object>> tcList = new ArrayList<>();
                    for (ToolCall tc : m.toolCalls()) {
                        Map<String, Object> tcMap = new LinkedHashMap<>();
                        tcMap.put("id", tc.id());
                        tcMap.put("type", "function");
                        Map<String, Object> fn = new LinkedHashMap<>();
                        fn.put("name", tc.name());
                        // arguments 已是 JSON 字符串（Provider 中立），直接传
                        fn.put("arguments", tc.arguments());
                        tcMap.put("function", fn);
                        tcList.add(tcMap);
                    }
                    block.put("tool_calls", tcList);
                    if (m.content() != null && !m.content().isEmpty()) {
                        block.put("content", m.content());
                    }
                } else {
                    block.put("content", m.content() == null ? "" : m.content());
                }
            }
            case TOOL -> {
                block.put("role", ROLE_TOOL);
                block.put("tool_call_id", m.toolCallId());
                block.put("name", m.toolName());
                // content: 把 ToolResult 序列化为 JSON 字符串（空 payload 时仅 errorMessage）
                block.put("content", toolResultToJson(m.toolResult()));
            }
        }
        return block;
    }

    private static String toolResultToJson(ToolResult result) {
        if (result == null) return "{}";
        if (result.success()) {
            return mapToJsonString(result.payload());
        }
        // 失败时把 errorMessage 包装成单字段 JSON
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", result.errorMessage() == null ? "" : result.errorMessage());
        return mapToJsonString(err);
    }

    /** 极简 JSON 序列化（避免引入 Jackson 依赖；只支持 String/Number/Boolean/null/List/Map）。 */
    @SuppressWarnings("unchecked")
    private static String mapToJsonString(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return "\"" + s.replace("\"", "\\\"") + "\"";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        if (o instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                sb.append(mapToJsonString(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object e : list) {
                if (!first) sb.append(',');
                first = false;
                sb.append(mapToJsonString(e));
            }
            return sb.append(']').toString();
        }
        return "\"" + o.toString().replace("\"", "\\\"") + "\"";
    }

    /** 当前本地日期时间行（FR-005）。 */
    String currentDateTimeLine() {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        return "Current date/time: " + now.format(HUMAN_TS)
            + " (zone=" + zone.getId() + ")";
    }
}