package io.oryxos.boot.config;

import io.oryxos.memory.backend.LongTermMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * backend="mem0" 选定契约。
 */
@SpringBootTest(
    classes = {
        MemoryBackendSelectorTest.SelectorTestConfig.class,
        MemoryBackendSelector.class
    },
    properties = "oryxos.memory.backend=mem0"
)
@DisplayName("backend=\"mem0\" → mem0Mock 选定")
class MemoryBackendSelectorMem0IT {

    @Autowired MemoryBackendSelector selector;
    @Autowired @Qualifier("mem0MemoryStore") LongTermMemoryStore mem0Mock;

    @Test
    void mem0_backend_selected() {
        assertThat(selector.selectedBackendName()).isEqualTo("mem0");
        assertThat(selector.select()).isSameAs(mem0Mock);
    }
}