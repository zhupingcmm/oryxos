package io.oryxos.boot.config;

import io.oryxos.memory.MemoryException;
import io.oryxos.memory.MemoryProperties;
import io.oryxos.memory.backend.LongTermMemoryStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期层后端选择器（006-memory-layer）。
 *
 * <p>按 {@link MemoryProperties#backend()} 字符串选择对应 {@link LongTermMemoryStore} 实现。
 *
 * <p>Bean 发现策略（Phase 2 占位实现 + Phase 5 落地）：
 * <ul>
 *   <li>构造器注入 {@code Map<String, LongTermMemoryStore>} —— Spring 自动按 Bean 名称注入所有 LongTermMemoryStore 实现</li>
 *   <li>三个后端 Bean 名称约定：{@code markdownMemoryStore} / {@code sqliteMemoryStore} / {@code mem0MemoryStore}</li>
 *   <li>Phase 2 时只有 markdownMemoryStore（既有 MarkdownMemoryStore 在 Phase 4 适配本接口），
 *       所以 Map 暂时只含 1 项；Phase 5 完成 SqliteMemoryStore + Mem0MemoryStore 后扩展到 3 项</li>
 * </ul>
 *
 * <p>启动期 fail-fast（spec FR-009 / C-LT-05）：
 * <ol>
 *   <li>backend 字符串合法（{@code markdown} / {@code sqlite} / {@code mem0}）</li>
 *   <li>对应 Bean 存在 —— 否则抛 {@link MemoryException} 阻断 Spring 启动</li>
 *   <li>Bean 已注入且通过 {@link LongTermMemoryStore#isHealthy()}</li>
 * </ol>
 *
 * <p>详见 [data-model.md §2.5](../specs/006-memory-layer/data-model.md) +
 * [research.md R-08](../specs/006-memory-layer/research.md)。
 */
@Component
@ConditionalOnBean(LongTermMemoryStore.class)
public class MemoryBackendSelector {

    private static final Logger log = LoggerFactory.getLogger(MemoryBackendSelector.class);

    private final Map<String, LongTermMemoryStore> backends;
    private final String selectedName;
    private final LongTermMemoryStore selectedBackend;

    /**
     * Spring 自动按 Bean 名称注入所有 LongTermMemoryStore 实现。
     * Bean 名称 → 后端别名映射（research R-08）。
     */
    public MemoryBackendSelector(
        Map<String, LongTermMemoryStore> backendBeans,
        MemoryProperties properties
    ) {
        // Spring 把所有 LongTermMemoryStore Bean 按 Bean 名称注入；统一 key 化为 backend 别名
        this.backends = new HashMap<>();
        for (Map.Entry<String, LongTermMemoryStore> entry : backendBeans.entrySet()) {
            String alias = normalizeAlias(entry.getKey());
            this.backends.put(alias, entry.getValue());
            log.info("MemoryBackendSelector discovered backend: {} (alias={})",
                entry.getKey(), alias);
        }
        this.selectedName = properties.backend().toLowerCase();
        this.selectedBackend = this.backends.get(this.selectedName);
    }

    /**
     * Bean 名称 → 后端别名。
     * 例：{@code markdownMemoryStore} → {@code markdown}；
     *     {@code sqliteMemoryStore} → {@code sqlite}；
     *     {@code mem0MemoryStore} → {@code mem0}。
     */
    private static String normalizeAlias(String beanName) {
        if (beanName == null) return "";
        String lower = beanName.toLowerCase();
        if (lower.contains("markdown")) return "markdown";
        if (lower.contains("sqlite")) return "sqlite";
        if (lower.contains("mem0")) return "mem0";
        return lower;
    }

    /**
     * 启动期 fail-fast 验证（spec FR-009 / C-LT-05）：
     * 1. backend 字符串合法
     * 2. 对应 Bean 已注册
     * 3. Bean 通过 isHealthy()（Markdown 检查文件可读；Sqlite 检查 DB 连接；Mem0 检查 /health）
     */
    @PostConstruct
    public void validateAtStartup() {
        log.info("Memory backend selected: {}", selectedName);
        if (selectedBackend == null) {
            throw new MemoryException(
                "Memory backend '" + selectedName + "' not registered. "
                + "Available backends: " + backends.keySet()
                + ". Configure oryxos.memory.backend=markdown|sqlite|mem0 in application.yaml.");
        }
        try {
            if (!selectedBackend.isHealthy()) {
                throw new MemoryException(
                    "Memory backend '" + selectedName + "' failed isHealthy() check at startup. "
                    + "Check the underlying store (Markdown file / SQLite DB / Mem0 service) is reachable.");
            }
        } catch (RuntimeException ex) {
            throw new MemoryException(
                "Memory backend '" + selectedName + "' health check error: " + ex.getMessage(), ex);
        }
        log.info("Memory backend '{}' healthy.", selectedName);
    }

    /**
     * 取得当前选定的后端（Phase 3 DefaultMemoryService 用）。
     */
    public LongTermMemoryStore select() {
        if (selectedBackend == null) {
            throw new MemoryException(
                "Memory backend '" + selectedName + "' not available (post-startup).");
        }
        return selectedBackend;
    }

    /** 当前选定的后端别名（供 CLI / Web 端点查询）。 */
    public String selectedBackendName() {
        return selectedName;
    }

    /** 所有已注册的后端别名（供运维 + US-3 BackendSwitchIT 用）。 */
    public List<String> availableBackends() {
        return List.copyOf(backends.keySet());
    }
}