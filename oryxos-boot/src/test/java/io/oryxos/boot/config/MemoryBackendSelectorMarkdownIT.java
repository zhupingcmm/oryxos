package io.oryxos.boot.config;

import io.oryxos.memory.backend.LongTermMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * backend="markdown" 选定契约 —— 顶层独立 SpringBootTest（@Nested + SpringBootTest 兼容性差）。
 */
@SpringBootTest(
    classes = {
        MemoryBackendSelectorTest.SelectorTestConfig.class,
        MemoryBackendSelector.class
    },
    properties = "oryxos.memory.backend=markdown"
)
@DisplayName("backend=\"markdown\" → markdownMock 选定")
class MemoryBackendSelectorMarkdownIT {

    @Autowired MemoryBackendSelector selector;
    @Autowired @Qualifier("markdownMemoryStore") LongTermMemoryStore markdownMock;

    @Test
    void markdown_backend_selected() {
        assertThat(selector.selectedBackendName()).isEqualTo("markdown");
        assertThat(selector.select()).isSameAs(markdownMock);
        assertThat(selector.availableBackends())
            .containsExactlyInAnyOrder("markdown", "sqlite", "mem0");
    }
}