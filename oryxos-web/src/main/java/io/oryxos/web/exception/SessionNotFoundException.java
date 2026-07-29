package io.oryxos.web.exception;

/**
 * T006 — SessionsController get / delete 时 session_id 不存在 → HTTP 404 session_not_found.
 */
public class SessionNotFoundException extends RuntimeException {
    public SessionNotFoundException(String sessionId) {
        super("Session '" + sessionId + "' not found");
    }
}