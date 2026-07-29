package io.oryxos.web.exception;

/**
 * T006 — AgentsController invoke 时 Agent 名不存在 → HTTP 404 agent_not_found.
 */
public class AgentNotFoundException extends RuntimeException {
    private final String agentName;

    public AgentNotFoundException(String agentName) {
        super("Agent '" + agentName + "' not found in loaded profiles");
        this.agentName = agentName;
    }

    public String agentName() {
        return agentName;
    }
}