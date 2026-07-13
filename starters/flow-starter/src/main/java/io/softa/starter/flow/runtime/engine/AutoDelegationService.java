package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
 * chains (A→B→A) are detected and skipped.
 * </p>
 */
@Slf4j
@Component
public class AutoDelegationService {

    @Autowired(required = false)
    private FlowDelegationService delegationService;

    @Autowired(required = false)
    private FlowAuditService auditService;

    /**
     * Apply auto-delegation to a pending approval.
     * Modifies the pending approval in place by replacing delegated approvers.
     */
    public void applyAutoDelegation(PendingApproval pending, FlowExecutionState state, CompiledFlowNode node) {
        if (delegationService == null || pending == null || pending.getApprovers() == null || pending.getApprovers().isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        String flowCode = pending.getFlowCode();
        String nodeId = pending.getNodeId();
        List<String> approvers = new ArrayList<>(pending.getApprovers());
        boolean changed = false;

        for (int i = 0; i < approvers.size(); i++) {
            String currentApprover = approvers.get(i);
            String resolvedDelegate = resolveDelegateChain(currentApprover, flowCode, nodeId, now, approvers);
            if (resolvedDelegate != null && !resolvedDelegate.equals(currentApprover)) {
                log.info("Auto-delegating approval at node {} from {} to {}", nodeId, currentApprover, resolvedDelegate);
                approvers.set(i, resolvedDelegate);
                changed = true;

                if (auditService != null && state != null && node != null) {
                    auditService.addTrace(state, flowCode, node, FlowTraceEventType.APPROVAL_DELEGATED,
                            "Auto-delegated from " + currentApprover + " to " + resolvedDelegate + " at node " + nodeId);
                }
            }
        }

        if (changed) {
            pending.setApprovers(approvers);
            pending.setTotalApproverCount(approvers.size());
        }
    }

    /**
     * Follow delegation chain, detecting cycles.
     *
     * @return the final delegate, or null if no delegation applies
     */
    private String resolveDelegateChain(String actorId, String flowCode, String nodeId,
                                        LocalDateTime now, List<String> existingApprovers) {
        Set<String> visited = new HashSet<>();
        visited.add(actorId);
        String current = actorId;

        while (true) {
            Optional<FlowDelegation> delegation = delegationService.findActiveDelegation(current, flowCode, nodeId, now);
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
            visited.add(delegate);
            delegationService.recordDelegationUsage(delegation.get().getId());
            current = delegate;
        }

        return current;
    }
}

