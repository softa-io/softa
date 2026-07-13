package io.softa.starter.flow.api;

/**
 * Optional filters for approval task / history queries (pending, completed, approval history).
 * The actor is resolved server-side from the context, never carried here.
 */
public record TaskQuery(String flowCode, String instanceId, String nodeId) {
}
