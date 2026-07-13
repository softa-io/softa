package io.softa.starter.flow.api;

import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Filter for "my submitted applications" (J2). Both fields are optional (nullable);
 * the initiator is always resolved server-side from the context, never carried here.
 */
public record MineQuery(String flowCode, FlowExecutionStatus status) {
}
