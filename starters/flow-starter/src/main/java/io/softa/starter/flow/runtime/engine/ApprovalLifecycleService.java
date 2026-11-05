package io.softa.starter.flow.runtime.engine;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.state.AddSignPosition;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Domain service for approval lifecycle state transitions and dependency management.
 */
@Component
public class ApprovalLifecycleService {

    private final PendingApprovalFactory pendingApprovalFactory;

    public ApprovalLifecycleService(PendingApprovalFactory pendingApprovalFactory) {
        this.pendingApprovalFactory = pendingApprovalFactory;
    }

    public boolean canCompleteApproval(PendingApproval pendingApproval) {
        if (pendingApproval.getApprovedActors().size() < pendingApproval.getRequiredApprovalCount()) {
            return false;
        }
        if (!hasAddSignDependency(pendingApproval)) {
            return true;
        }
        return pendingApproval.getApprovedActors().contains(pendingApproval.getPrerequisiteActorId())
                && pendingApproval.getApprovedActors().contains(pendingApproval.getBlockedActorId());
    }

    public boolean requiresTrackedApprover(PendingApproval pendingApproval) {
        return VoteThresholdMode.UNANIMOUS.equals(pendingApproval.getApprovalMode())
                || VoteThresholdMode.MIN_COUNT.equals(pendingApproval.getApprovalMode())
                || VoteThresholdMode.PERCENTAGE.equals(pendingApproval.getApprovalMode())
                || hasAddSignDependency(pendingApproval);
    }

    public boolean requiresTrackedReject(PendingApproval pendingApproval) {
        return pendingApproval.getRejectMode() != null
                && !VoteThresholdMode.ANY_ONE.equals(pendingApproval.getRejectMode());
    }

    public void replaceApprover(PendingApproval pendingApproval, String sourceActorId, String targetActorId) {
        List<String> approvers = new ArrayList<>(pendingApproval.getApprovers());
        for (int i = 0; i < approvers.size(); i++) {
            if (sourceActorId.equals(approvers.get(i))) {
                approvers.set(i, targetActorId);
                pendingApproval.setApprovers(approvers);
                pendingApproval.setTotalApproverCount(approvers.size());
                if (sourceActorId.equals(pendingApproval.getBlockedActorId())) {
                    pendingApproval.setBlockedActorId(targetActorId);
                }
                if (sourceActorId.equals(pendingApproval.getPrerequisiteActorId())) {
                    pendingApproval.setPrerequisiteActorId(targetActorId);
                }
                return;
            }
        }
        throw new FlowRuntimeException("Approver not found during transfer: " + sourceActorId);
    }

    /**
     * Gives {@code sourceActorId}'s place on the step to {@code targetActorId}; when the target is already one of
     * its approvers, the two places merge into theirs — the step asks fewer people, and its thresholds are capped the
     * way a step's are when it starts ({@link ApproverResolutionService#capRequiredApprovals},
     * {@link ApproverResolutionService#capRequiredRejections}): no more approvals than it now has approvers, and no
     * more rejections than a split vote can reach.
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
        if (pendingApproval.getRequiredRejectCount() != null && pendingApproval.getRequiredApprovalCount() != null) {
            pendingApproval.setRequiredRejectCount(ApproverResolutionService.capRequiredRejections(
                    pendingApproval.getRequiredRejectCount(), approvers.size(), pendingApproval.getRequiredApprovalCount()));
        }
        if (sourceActorId.equals(pendingApproval.getBlockedActorId())) {
            pendingApproval.setBlockedActorId(targetActorId);
        }
        if (sourceActorId.equals(pendingApproval.getPrerequisiteActorId())) {
            pendingApproval.setPrerequisiteActorId(targetActorId);
        }
    }

    public void insertAddSignBefore(PendingApproval pendingApproval,
                                    String blockedActorId,
                                    String prerequisiteActorId,
                                    CompiledFlowNode node) {
        List<String> approvers = new ArrayList<>(pendingApproval.getApprovers());
        int index = approvers.indexOf(blockedActorId);
        if (index < 0) {
            throw new FlowRuntimeException("Approver not found during add-sign-before: " + blockedActorId);
        }
        approvers.add(index, prerequisiteActorId);
        pendingApproval.setApprovers(approvers);
        pendingApproval.setBlockedActorId(blockedActorId);
        pendingApproval.setPrerequisiteActorId(prerequisiteActorId);
        pendingApprovalFactory.recomputePendingApprovalThresholds(pendingApproval, node);
    }

    public void insertAddSignAfter(PendingApproval pendingApproval,
                                   String prerequisiteActorId,
                                   String blockedActorId,
                                   CompiledFlowNode node) {
        List<String> approvers = new ArrayList<>(pendingApproval.getApprovers());
        int index = approvers.indexOf(prerequisiteActorId);
        if (index < 0) {
            throw new FlowRuntimeException("Approver not found during add-sign-after: " + prerequisiteActorId);
        }
        approvers.add(index + 1, blockedActorId);
        pendingApproval.setApprovers(approvers);
        pendingApproval.setBlockedActorId(blockedActorId);
        pendingApproval.setPrerequisiteActorId(prerequisiteActorId);
        pendingApprovalFactory.recomputePendingApprovalThresholds(pendingApproval, node);
    }

    /**
     * Position-aware dispatcher for add-sign insertion. BEFORE routes to {@link #insertAddSignBefore}
     * and AFTER to {@link #insertAddSignAfter}, with the actor / target arguments forwarded in the
     * exact same positional order the handlers used, so the before/after ordering semantics are
     * preserved without alteration.
     */
    public void insertAddSign(PendingApproval pendingApproval,
                              String actorId,
                              String targetActorId,
                              CompiledFlowNode node,
                              AddSignPosition position) {
        switch (position) {
            case BEFORE -> insertAddSignBefore(pendingApproval, actorId, targetActorId, node);
            case AFTER -> insertAddSignAfter(pendingApproval, actorId, targetActorId, node);
        }
    }

    public boolean hasUnresolvedAddSignDependency(PendingApproval pendingApproval) {
        return hasAddSignDependency(pendingApproval) && blockedActorPending(pendingApproval);
    }

    /**
     * Whether {@code actorId}'s turn waits on a signer added before them who has not approved yet — refused by
     * {@link ApprovalActorValidator#requireNotBlockedByPrerequisite}.
     */
    public boolean isBlockedByPrerequisite(PendingApproval pendingApproval, String actorId) {
        return hasAddSignDependency(pendingApproval)
                && blockedActorPending(pendingApproval)
                && blockedActorMatches(pendingApproval, actorId)
                && !isPrerequisiteResolved(pendingApproval);
    }

    // --- internal helpers ---

    private boolean hasAddSignDependency(PendingApproval pendingApproval) {
        return StringUtils.hasText(pendingApproval.getBlockedActorId())
                && StringUtils.hasText(pendingApproval.getPrerequisiteActorId());
    }

    private boolean blockedActorPending(PendingApproval pendingApproval) {
        return pendingApproval.getApprovedActors() == null
                || !pendingApproval.getApprovedActors().contains(pendingApproval.getBlockedActorId());
    }

    private boolean blockedActorMatches(PendingApproval pendingApproval, String actorId) {
        return StringUtils.hasText(actorId) && actorId.equals(pendingApproval.getBlockedActorId());
    }

    private boolean isPrerequisiteResolved(PendingApproval pendingApproval) {
        return pendingApproval.getApprovedActors() != null
                && pendingApproval.getApprovedActors().contains(pendingApproval.getPrerequisiteActorId());
    }

}

