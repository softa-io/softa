package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.FlowDelegationService;

/**
 * Applies auto-delegation rules to newly created pending approvals.
 * <p>
 * For each approver in the pending list, checks whether an active delegation
 * rule exists and replaces the approver with the delegate. Circular delegation
 * chains (A→B→A) are detected and skipped. A rule whose delegate cannot act — their account
 * is frozen, say ({@link ActorAccounts}) — does not apply: the task stays with the approver it
 * reached, the trace says why, and nobody is told.
 * </p>
 * <p>
 * The rules are read and their usage counted past the caller's data permissions: they belong to the
 * approvers, not to whoever's action reached the node.
 * </p>
 */
@Slf4j
@Component
public class AutoDelegationService {

    @Autowired(required = false)
    private FlowDelegationService delegationService;

    @Autowired(required = false)
    private FlowAuditService auditService;

    @Autowired(required = false)
    private ActorAccounts actorAccounts;

    /**
     * Apply auto-delegation to a pending approval.
     * Modifies the pending approval in place by replacing delegated approvers.
     *
     * @param recordUsage whether each delegation applied counts as used — false for the approval
     *                    path preview, which delegates without recording anything
     */
    public void applyAutoDelegation(PendingApproval pending, FlowExecutionState state, CompiledFlowNode node,
                                    boolean recordUsage) {
        if (delegationService == null || pending == null || pending.getApprovers() == null || pending.getApprovers().isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        String flowId = pending.getFlowId();
        String nodeId = pending.getNodeId();
        List<String> approvers = new ArrayList<>(pending.getApprovers());
        boolean changed = false;

        for (int i = 0; i < approvers.size(); i++) {
            String currentApprover = approvers.get(i);
            String resolvedDelegate = resolveDelegateChain(currentApprover, flowId, nodeId, now, approvers, recordUsage,
                    state, node);
            if (resolvedDelegate != null && !resolvedDelegate.equals(currentApprover)) {
                log.info("Auto-delegating approval at node {} from {} to {}", nodeId, currentApprover, resolvedDelegate);
                approvers.set(i, resolvedDelegate);
                changed = true;

                if (auditService != null && state != null && node != null) {
                    auditService.addTrace(state, flowId, node, FlowTraceEventType.APPROVAL_DELEGATED,
                            "Auto-delegated from " + currentApprover + " to " + resolvedDelegate + " at node " + nodeId);
                }
            }
        }

        if (changed) {
            pending.setApprovers(approvers);
        }
    }

    /**
     * Follow delegation chain, detecting cycles. It stops at a delegate who cannot act: the task stays with
     * whoever the chain reached before them.
     *
     * @return the final delegate, or null if no delegation applies
     */
    private String resolveDelegateChain(String actorId, String flowId, String nodeId,
                                        LocalDateTime now, List<String> existingApprovers, boolean recordUsage,
                                        FlowExecutionState state, CompiledFlowNode node) {
        Set<String> visited = new HashSet<>();
        visited.add(actorId);
        String current = actorId;

        while (true) {
            String delegator = current;
            Optional<FlowDelegation> delegation = ContextUtils.withoutPermissionChecks(
                    () -> delegationService.findActiveDelegation(delegator, flowId, nodeId, now));
            if (delegation.isEmpty()) {
                break;
            }
            String delegate = delegation.get().getDelegateId();
            if (visited.contains(delegate)) {
                log.warn("Circular delegation chain detected: {} -> {}. Stopping at {}", current, delegate, current);
                break;
            }
            // Avoid delegating to someone who is already an approver
            if (existingApprovers.contains(delegate)) {
                log.info("Delegate {} is already an approver for this node. Skipping delegation from {}.", delegate, current);
                break;
            }
            String unusable = whyUnusable(delegate);
            if (unusable != null) {
                log.info("Delegate {} cannot act ({}). Skipping delegation from {}.", delegate, unusable, current);
                if (auditService != null && state != null && node != null) {
                    auditService.addTrace(state, flowId, node, FlowTraceEventType.APPROVAL_DELEGATED,
                            "Delegation from " + current + " to " + delegate + " not applied at node " + nodeId
                                    + ": " + unusable);
                }
                break;
            }
            visited.add(delegate);
            if (recordUsage) {
                Long delegationId = delegation.get().getId();
                ContextUtils.withoutPermissionChecks(() -> delegationService.recordDelegationUsage(delegationId));
            }
            current = delegate;
        }

        return current;
    }

    /** Why the delegate cannot act, or null when they can. */
    private String whyUnusable(String delegate) {
        return actorAccounts == null ? null : actorAccounts.whyUnusable(List.of(delegate)).get(delegate);
    }
}
