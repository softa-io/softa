package io.softa.starter.flow.api;

/**
 * Optional filters for the aggregated inbox query. {@code includeCompletedApprovals}
 * toggles whether completed approval tasks are included alongside the pending ones.
 * The actor is resolved server-side from the context, never carried here.
 */
public record InboxQuery(String flowCode, String instanceId, String nodeId, Boolean includeCompletedApprovals) {
}
