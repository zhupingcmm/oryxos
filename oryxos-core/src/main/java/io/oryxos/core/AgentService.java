package io.oryxos.core;

/**
 * US-2 / US-5 stage: unified entry point for Agent invocation -- CLI / Web / Scheduler
 * all go through this single method (FR-001 / FR-021).
 *
 * <p>See {@code contracts/AgentService.md}.
 *
 * <h2>Contract clauses</h2>
 * <ul>
 *   <li>C-AS-1: the only public entry point</li>
 *   <li>C-AS-2: {@code ProfileContext} set once at the entry, cleared in {@code finally}</li>
 *   <li>C-AS-3 / C-AS-4: Session references an unknown Profile or has unconfigured Provider
 *       -- throw {@link IllegalArgumentException}</li>
 *   <li>C-AS-7: reject null session / null userMessage</li>
 * </ul>
 */
public interface AgentService {

    /**
     * Process a user message and return the Agent's final response.
     *
     * @param session     current session (must reference a registered Profile)
     * @param userMessage user input text (can be empty string, but not null -- C-AS-7)
     * @return loop result; {@link LoopResult#finalText()} is the user-visible response
     * @throws IllegalArgumentException unknown Profile or unconfigured Provider
     */
    LoopResult process(Session session, String userMessage);
}