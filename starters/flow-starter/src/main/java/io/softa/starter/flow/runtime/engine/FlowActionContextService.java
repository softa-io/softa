package io.softa.starter.flow.runtime.engine;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;

/**
 * Provides shared action context operations: state retrieval, definition resolution,
 * node lookup, variable merging, and state persistence with projection sync.
 */
@Component
public class FlowActionContextService {

    private final FlowInstanceStore instanceStore;
    private final FlowBundleRegistry bundleRegistry;
    private final FlowProjectionPublisher projectionPublisher;

    public FlowActionContextService(FlowInstanceStore instanceStore,
                                    FlowBundleRegistry bundleRegistry,
                                    FlowProjectionPublisher projectionPublisher) {
        this.instanceStore = instanceStore;
        this.bundleRegistry = bundleRegistry;
        this.projectionPublisher = projectionPublisher;
    }

    public FlowExecutionState requireState(String instanceId) {
        return instanceStore.get(instanceId)
                .orElseThrow(() -> new FlowRuntimeException("Flow instance not found: " + instanceId));
    }

    public void requireWaitingApproval(FlowExecutionState state, String instanceId, String actionLabel) {
        if (!FlowExecutionStatus.WAITING.equals(state.getStatus())) {
            throw new FlowActionValidationException("Flow instance is not waiting for approval and cannot be "
                    + actionLabel + ": " + instanceId);
        }
    }

    /**
     * The step an action is taken on, while it waits. A step that no longer waits — handled by someone else, or by
     * this person on another page, since theirs was loaded — is refused as a stale request, not failed as an error:
     * the caller is told to look again.
     */
    public PendingApproval requirePendingApproval(FlowExecutionState state, String nodeId) {
        return state.getPendingApprovals().stream()
                .filter(item -> nodeId.equals(item.getNodeId()))
                .findFirst()
                .orElseThrow(() -> new FlowActionValidationException(
                        "This step no longer waits for approval: it may have been handled already. "
                                + "Refresh to see where the approval stands."));
    }

    /**
     * Primary definition lookup by exact bundle id (no tenant collision).
     */
    public CompiledFlowDefinition resolveDefinition(Long bundleId) {
        return bundleRegistry.getByBundleId(bundleId)
                .orElseThrow(() -> new FlowRuntimeException("Compiled flow bundle not found: bundleId=" + bundleId));
    }

    /**
     * The revision a new instance of the flow starts from: its active revision, refused while the flow is
     * disabled or was never published.
     */
    public CompiledFlowDefinition resolveDefinitionByFlowId(String flowId) {
        return bundleRegistry.getActiveByFlowId(flowId)
                .orElseThrow(() -> new FlowRuntimeException(
                        "Flow " + flowId + " cannot start: it was never published, or it is disabled"));
    }

    public CompiledFlowNode requiredNode(CompiledFlowDefinition definition, String nodeId) {
        CompiledFlowNode node = definition.getNodeIndex().get(nodeId);
        if (node == null) {
            throw new FlowRuntimeException("Node not found in compiled definition: " + nodeId);
        }
        return node;
    }

    /**
     * Loads the shared approval context after asserting the instance is in
     * {@link FlowExecutionStatus#WAITING}. The waiting-approval gate is applied
     * immediately after state resolution and before the pending-approval lookup, preserving
     * the exact validation order (and exception type/message) of the inlined handler preambles.
     *
     * @param waitingActionLabel the action label embedded in the validation message
     *                           (see {@link #requireWaitingApproval})
     */
    public ApprovalContext loadApprovalContext(String instanceId, String nodeId, String waitingActionLabel) {
        FlowExecutionState state = requireState(instanceId);
        requireWaitingApproval(state, instanceId, waitingActionLabel);
        return loadApprovalContext(state, nodeId);
    }

    /**
     * Loads the shared approval context from an already-resolved state, for callers (e.g. approve and
     * reject) that check the state themselves before the pending-approval lookup. Equivalent to the tail
     * of the {@code instanceId} overload.
     */
    public ApprovalContext loadApprovalContext(FlowExecutionState state, String nodeId) {
        PendingApproval pendingApproval = requirePendingApproval(state, nodeId);
        CompiledFlowDefinition definition = resolveDefinition(state.getBundleId());
        CompiledFlowNode node = requiredNode(definition, pendingApproval.getNodeId());
        FlowExecutionStatus statusBefore = state.getStatus();
        return new ApprovalContext(state, definition, pendingApproval, node, statusBefore);
    }

    public void mergeVariables(FlowExecutionState state, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        state.getVariables().putAll(updates);
    }

    public void persistState(FlowExecutionState state) {
        instanceStore.save(state);
        projectionPublisher.publish(state);
    }

    /**
     * Persist state in its own transaction so the record survives when the caller's transaction
     * rolls back. Used by failed starts and failed async resumes: they rethrow to roll back
     * the failed attempt's partial business writes, but the FAILED instance must still be recorded
     * for observability.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistStateInNewTransaction(FlowExecutionState state) {
        persistState(state);
    }
}
