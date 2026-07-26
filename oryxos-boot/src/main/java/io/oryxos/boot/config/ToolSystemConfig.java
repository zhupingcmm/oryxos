package io.oryxos.boot.config;

import io.oryxos.core.tool.ToolDefinition;
import io.oryxos.core.tool.ToolRegistration;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolRegistrySchemaAdapter;
import io.oryxos.core.ToolSchemaProvider;
import io.oryxos.memory.MemoryService;
import io.oryxos.tool.file.FileListTool;
import io.oryxos.tool.file.FileReadTool;
import io.oryxos.tool.file.FileWriteTool;
import io.oryxos.tool.http.HttpGetTool;
import io.oryxos.tool.http.HttpPostTool;
import io.oryxos.tool.http.HttpToolProperties;
import io.oryxos.tool.memory.RecallMemoryTool;
import io.oryxos.tool.memory.SaveMemoryTool;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.shell.ShellTool;
import io.oryxos.tool.shell.ShellToolProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool 系统的 Spring 装配中心 —— 把 9 个内置 Tool（{@code notify} + 8 个新增）打包成
 * {@link ToolRegistration}，再用 {@link ToolRegistry#of} 构建包含全部 Tool 的注册表。
 *
 * <p>T036 落地。把 {@link io.oryxos.boot.config.NotifyToolConfig} 的
 * {@link NotifyTool @Bean ToolRegistration} + {@code @Primary @Bean ToolRegistry}
 * 合到这一处集中管理；NotifyToolConfig 仍保留 notify 的注册方法供本 config 复用。
 *
 * <p>依赖：
 * <ul>
 *   <li>{@link HttpClient} —— {@code HttpClientConfig.sharedHttpClient()}</li>
 *   <li>{@link Sandbox} —— {@code SandboxConfig.@EnableConfigurationProperties} 触发
 *       {@code WhitelistSandbox} 自动注册（既有 {@code @Component}）</li>
 *   <li>{@link MemoryService} —— {@code MarkdownMemoryStore.@Component} 自动注册</li>
 * </ul>
 *
 * <p>冲突检测（FR-015 / R-08）：{@link ToolRegistry#of} 在重复名时抛
 * {@link IllegalStateException}；本 config 通过把 9 个 Tool Bean 全部显式列出来避免
 * 重复（无分散）。
 */
@Configuration
@EnableConfigurationProperties({ HttpToolProperties.class, ShellToolProperties.class })
public class ToolSystemConfig {

    // ─────────────────────────────────────────────────────────────────────
    // 9 个内置 Tool 的 ToolRegistration
    // ─────────────────────────────────────────────────────────────────────

    @Bean
    public ToolRegistration fileReadToolRegistration(FileReadTool tool) {
        return new ToolRegistration(
            new ToolDefinition(FileReadTool.NAME, tool.description(), "builtin"),
            tool, "fileReadTool"
        );
    }

    @Bean
    public ToolRegistration fileWriteToolRegistration(FileWriteTool tool) {
        return new ToolRegistration(
            new ToolDefinition(FileWriteTool.NAME, tool.description(), "builtin"),
            tool, "fileWriteTool"
        );
    }

    @Bean
    public ToolRegistration fileListToolRegistration(FileListTool tool) {
        return new ToolRegistration(
            new ToolDefinition(FileListTool.NAME, tool.description(), "builtin"),
            tool, "fileListTool"
        );
    }

    @Bean
    public ToolRegistration shellToolRegistration(ShellTool tool) {
        return new ToolRegistration(
            new ToolDefinition(ShellTool.NAME, tool.description(), "builtin"),
            tool, "shellTool"
        );
    }

    @Bean
    public ToolRegistration httpGetToolRegistration(HttpGetTool tool) {
        return new ToolRegistration(
            new ToolDefinition(HttpGetTool.NAME, tool.description(), "builtin"),
            tool, "httpGetTool"
        );
    }

    @Bean
    public ToolRegistration httpPostToolRegistration(HttpPostTool tool) {
        return new ToolRegistration(
            new ToolDefinition(HttpPostTool.NAME, tool.description(), "builtin"),
            tool, "httpPostTool"
        );
    }

    @Bean
    public ToolRegistration saveMemoryToolRegistration(SaveMemoryTool tool) {
        return new ToolRegistration(
            new ToolDefinition(SaveMemoryTool.NAME, tool.description(), "builtin"),
            tool, "saveMemoryTool"
        );
    }

    @Bean
    public ToolRegistration recallMemoryToolRegistration(RecallMemoryTool tool) {
        return new ToolRegistration(
            new ToolDefinition(RecallMemoryTool.NAME, tool.description(), "builtin"),
            tool, "recallMemoryTool"
        );
    }

    // notify 的 @Bean ToolRegistration 由 NotifyToolConfig 暴露，避免重复定义

    // ─────────────────────────────────────────────────────────────────────
    // 主注册表 —— 包含全部 9 个内置 Tool
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 覆盖 {@link ToolRegistry} 默认空实现：本 Bean 含 9 个内置 Tool。
     * {@code @Primary} 让 {@code DefaultToolExecutor} 自动装配此 Bean。
     */
    @Bean
    @Primary
    public ToolRegistry toolRegistry(
        @Qualifier("fileReadToolRegistration")      ToolRegistration fileRead,
        @Qualifier("fileWriteToolRegistration")     ToolRegistration fileWrite,
        @Qualifier("fileListToolRegistration")      ToolRegistration fileList,
        @Qualifier("shellToolRegistration")         ToolRegistration shell,
        @Qualifier("httpGetToolRegistration")       ToolRegistration httpGet,
        @Qualifier("httpPostToolRegistration")      ToolRegistration httpPost,
        @Qualifier("saveMemoryToolRegistration")    ToolRegistration saveMemory,
        @Qualifier("recallMemoryToolRegistration")  ToolRegistration recallMemory,
        @Qualifier("notifyToolRegistration")        ToolRegistration notify
    ) {
        // 用 LinkedHashMap 保留插入顺序以便 tool list 输出稳定
        Map<String, ToolRegistration> map = new LinkedHashMap<>();
        put(map, fileRead);
        put(map, fileWrite);
        put(map, fileList);
        put(map, shell);
        put(map, httpGet);
        put(map, httpPost);
        put(map, saveMemory);
        put(map, recallMemory);
        put(map, notify);
        return ToolRegistry.of(map);
    }

    private static void put(Map<String, ToolRegistration> map, ToolRegistration reg) {
        if (reg == null) return;
        map.put(reg.definition().name(), reg);
    }

    /**
     * 真实 {@link ToolSchemaProvider} 实现 —— 从 {@link ToolRegistry} 抽取 Profile 可见 Tool
     * 的 Function Calling schema（spec FR-011 / US-5 场景 2 + [CLAUDE.md §V 边界澄清]）。
     *
     * <p>{@code @Primary} 覆盖 {@code PromptBuilderConfig.toolSchemaProvider()} 的 Noop 实现。
     */
    @Bean
    @Primary
    public ToolSchemaProvider toolSchemaProvider(ToolRegistry registry) {
        return new ToolRegistrySchemaAdapter(registry);
    }
}
