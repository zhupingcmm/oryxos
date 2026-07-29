package io.oryxos.web.exception;

/**
 * T006 — AgentsController invoke 时 AgentService.process() 超过 timeoutMs → HTTP 504 agent_timeout.
 */
public class AgentTimeoutException extends RuntimeException {
    private final long durationMs;

    public AgentTimeoutException(long durationMs) {
        super("Agent invocation exceeded timeout: " + durationMs + "ms");
        this.durationMs = durationMs;
    }

    public long durationMs() {
        return durationMs;
    }
}