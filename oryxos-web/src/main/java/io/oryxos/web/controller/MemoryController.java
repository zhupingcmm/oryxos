package io.oryxos.web.controller;

import io.oryxos.memory.MemoryService;
import io.oryxos.memory.MemorySummary;
import io.oryxos.web.dto.MemoryDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T032 + data-model.md §实体 7 + contracts/web-api.md §端点 7 — GET /api/v1/memory.
 *
 * <p>仅暴露后端元数据,不暴露内容 (per CLAUDE.md §15 "核心阶段不做 Memory REST 详情").
 */
@RestController
@RequestMapping("/api/v1")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/memory")
    public MemoryDto get() {
        MemorySummary s = memoryService.summary();
        return new MemoryDto(
            s.backend(),
            s.coreEntries(),
            s.archiveEntries(),
            s.filePath()
        );
    }
}
