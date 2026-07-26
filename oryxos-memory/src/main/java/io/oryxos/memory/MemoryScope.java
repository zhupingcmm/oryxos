package io.oryxos.memory;

/**
 * Memory scope —— 写入分区：{@link #CORE}（核心区，永不被截断）
 * 或 {@link #ARCHIVE}（归档区，可被压缩 / 归档）。
 *
 * <p>核心阶段 Agent 经 {@code save_memory(content, scope)} 显式指定；
 * 默认 {@code CORE}。
 *
 * <p>详见 <a href="../../../../../../../specs/003-cli-commands/spec.md">specs/003-cli-commands/spec.md</a>
 * 与 CLAUDE.md §9.6 Memory 四条契约。
 */
public enum MemoryScope {
    /** 核心区 —— 永不被截断 / 压缩；Agent 重要偏好、长期事实。 */
    CORE,
    /** 归档区 —— 可被压缩 / 归档；日志式记忆、临时上下文。 */
    ARCHIVE;

    /** 字符串名解析（与 {@link #name()} 严格相等；不区分大小写）。 */
    public static MemoryScope fromString(String s) {
        if (s == null || s.isBlank()) {
            return CORE;
        }
        return switch (s.trim().toLowerCase()) {
            case "core" -> CORE;
            case "archive" -> ARCHIVE;
            default -> throw new IllegalArgumentException("Unknown memory scope: " + s);
        };
    }

    /**
     * 校验字符串是否为合法 scope（spec FR-008 配套工具；不抛异常版本）。
     *
     * <p>大小写不敏感；null / blank → false；非 {@code core} / {@code archive} → false。
     */
    public static boolean isValid(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return "core".equals(t) || "archive".equals(t);
    }
}

