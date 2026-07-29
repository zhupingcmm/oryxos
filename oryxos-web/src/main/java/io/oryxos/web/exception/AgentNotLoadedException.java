package io.oryxos.web.exception;

/**
 * T006 — ProfilesController / 启动期 Agent 未加载完成 → HTTP 503 service_unavailable.
 */
public class AgentNotLoadedException extends RuntimeException {
    public AgentNotLoadedException(String message) {
        super(message);
    }
}