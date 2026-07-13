package io.softa.starter.flow.api;

import java.time.LocalDateTime;

import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Lightweight projection of a flow instance for an initiator's "my submitted applications"
 * list (J2). Excludes the heavy runtime payload (variables, trace, pending approvals) that
 * {@code FlowExecutionState} carries — fetch the full state via {@code getInstance} when needed.
 */
public record FlowInstanceView(
        String instanceId,
        String flowCode,
        String title,
        FlowExecutionStatus status,
        Integer resubmissionCount,
        LocalDateTime submittedAt) {
}
