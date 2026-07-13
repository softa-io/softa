package io.softa.starter.flow.runtime.engine;

import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Immutable carrier for the shared preamble of node-scoped approval actions:
 * the resolved execution state, compiled definition, pending approval, compiled node,
 * and the status snapshotted before the action runs.
 *
 * @see FlowActionContextService#loadApprovalContext(String, String)
 * @see FlowActionContextService#loadApprovalContext(String, String, String)
 */
public record ApprovalContext(FlowExecutionState state,
                              CompiledFlowDefinition definition,
                              PendingApproval pendingApproval,
                              CompiledFlowNode node,
                              FlowExecutionStatus statusBefore) {
}
