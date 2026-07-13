package io.softa.starter.flow.api;

/**
 * Optional filters for CC task / sent-CC queries. {@code read} narrows to read/unread CC items.
 * The actor is resolved server-side from the context, never carried here.
 */
public record CcTaskQuery(Boolean read, String flowCode, String instanceId, String nodeId) {
}
