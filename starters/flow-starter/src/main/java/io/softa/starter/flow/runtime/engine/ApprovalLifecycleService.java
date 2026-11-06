package io.softa.starter.flow.runtime.engine;

import java.util.*;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Domain service for approval lifecycle state transitions.
 */
@Component
public class ApprovalLifecycleService {

    public boolean canCompleteApproval(PendingApproval pendingApproval) {
        return pendingApproval.getApprovedActors().size() >= pendingApproval.getRequiredApprovalCount();
    }

    public boolean requiresTrackedApprover(PendingApproval pendingApproval) {
        return VoteThresholdMode.UNANIMOUS.equals(pendingApproval.getApprovalMode())
                || VoteThresholdMode.MIN_COUNT.equals(pendingApproval.getApprovalMode())
                || VoteThresholdMode.PERCENTAGE.equals(pendingApproval.getApprovalMode());
    }

    public void replaceApprover(PendingApproval pendingApproval, String sourceActorId, String targetActorId) {
        List<String> approvers = new ArrayList<>(pendingApproval.getApprovers());
        for (int i = 0; i < approvers.size(); i++) {
            if (sourceActorId.equals(approvers.get(i))) {
                approvers.set(i, targetActorId);
                pendingApproval.setApprovers(approvers);
                pendingApproval.setTotalApproverCount(approvers.size());
                return;
            }
        }
        throw new FlowRuntimeException("Approver not found during transfer: " + sourceActorId);
    }

    /**
     * Gives {@code sourceActorId}'s place on the step to {@code targetActorId}; when the target is already one of
     * its approvers, the two places merge into theirs — the step asks fewer people, and the approvals it needs are
     * capped the way a step's are when it starts ({@link ApproverResolutionService#capRequiredApprovals}): no more than
     * it now has approvers.
     */
    public void mergeApprover(PendingApproval pendingApproval, String sourceActorId, String targetActorId) {
        if (!pendingApproval.getApprovers().contains(targetActorId)) {
            replaceApprover(pendingApproval, sourceActorId, targetActorId);
            return;
        }
        List<String> approvers = new ArrayList<>(pendingApproval.getApprovers());
        if (!approvers.remove(sourceActorId)) {
            throw new FlowRuntimeException("Approver not found during reassignment: " + sourceActorId);
        }
        pendingApproval.setApprovers(approvers);
        pendingApproval.setTotalApproverCount(approvers.size());
        if (pendingApproval.getRequiredApprovalCount() != null) {
            pendingApproval.setRequiredApprovalCount(ApproverResolutionService.capRequiredApprovals(
                    pendingApproval.getRequiredApprovalCount(), approvers.size()));
        }
    }
}

