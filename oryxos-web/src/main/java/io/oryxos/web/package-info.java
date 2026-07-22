/**
 * Capability 5 — REST API / Web Service.
 *
 * <p>Spring MVC + Java 21 virtual threads. Six {@code ApiController}s expose the ten
 * core-stage REST endpoints:
 * <ul>
 *   <li>Session management (4): create, send message, get history, archive</li>
 *   <li>Agent invocation (1): stateless {@code POST /agents/{name}/invoke}</li>
 *   <li>Discovery (3): list profiles, memory, tools</li>
 *   <li>System (2): health, info</li>
 * </ul>
 *
 * <p>Plus: {@code GlobalExceptionHandler} (uniform error envelope),
 * OpenAPI 3.0 spec via springdoc.
 *
 * <p>Out of scope for core stage: authentication, SSE streaming, WebSocket,
 * RBAC, rate limiting.
 */
package io.oryxos.web;