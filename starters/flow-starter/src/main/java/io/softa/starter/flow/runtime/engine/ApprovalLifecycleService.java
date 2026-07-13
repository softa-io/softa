package io.softa.starter.flow.runtime.engine;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.state.AddSignPosition;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
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

    public void assertActorNotBlockedByPrerequisite(PendingApproval pendingApproval,
                                                    String actorId,
                                                    CompiledFlowNode node) {
        if (isBlockedByPrerequisite(pendingApproval, actorId)) {
            throw new FlowActionValidationException("Actor '" + actorId + "' is blocked by prerequisite signer '"
                    + pendingApproval.getPrerequisiteActorId() + "' for node: " + node.getNodeId());
        }
    }

    public boolean hasUnresolvedAddSignDependency(PendingApproval pendingApproval) {
        return hasAddSignDependency(pendingApproval) && blockedActorPending(pendingApproval);
    }

    public CompiledFlowNode resolvePreviousApprovalNode(CompiledFlowDefinition definition,
                                                        FlowExecutionState state,
                                                        CompiledFlowNode currentNode) {
        ArrayDeque<UpstreamNodeRef> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        visited.add(currentNode.getNodeId());
        queue.add(new UpstreamNodeRef(currentNode.getNodeId(), 0));

        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            List<CompiledFlowNode> matches = new ArrayList<>();
            for (int i = 0; i < levelSize; i++) {
                UpstreamNodeRef ref = queue.removeFirst();
                CompiledFlowNode node = requiredNode(definition, ref.nodeId());
                for (String edgeId : node.getIncomingEdgeIds()) {
                    CompiledFlowTransition transition = definition.getTransitionIndex().get(edgeId);
                    if (transition == null || !visited.add(transition.getSource())) {
                        continue;
                    }
                    CompiledFlowNode upstreamNode = requiredNode(definition, transition.getSource());
                    if (FlowNodeType.APPROVAL.equals(upstreamNode.getType())
                            && state.getCompletedNodeIds().contains(upstreamNode.getNodeId())) {
                        matches.add(upstreamNode);
                        continue;
                    }
                    queue.addLast(new UpstreamNodeRef(upstreamNode.getNodeId(), ref.depth() + 1));
                }
            }
            if (matches.size() > 1) {
                throw new FlowRuntimeException("Previous approval is ambiguous for node '" + currentNode.getNodeId() + "': "
                        + matches.stream().map(CompiledFlowNode::getNodeId).toList());
            }
            if (matches.size() == 1) {
                return matches.getFirst();
            }
        }
        throw new FlowRuntimeException("No previous completed approval found for node: " + currentNode.getNodeId());
    }

    // --- internal helpers ---

    private boolean hasAddSignDependency(PendingApproval pendingApproval) {
        return StringUtils.hasText(pendingApproval.getBlockedActorId())
                && StringUtils.hasText(pendingApproval.getPrerequisiteActorId());
    }

    private boolean isBlockedByPrerequisite(PendingApproval pendingApproval, String actorId) {
        return hasAddSignDependency(pendingApproval)
                && blockedActorPending(pendingApproval)
                && blockedActorMatches(pendingApproval, actorId)
                && !isPrerequisiteResolved(pendingApproval);
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

    private CompiledFlowNode requiredNode(CompiledFlowDefinition definition, String nodeId) {
        CompiledFlowNode node = definition.getNodeIndex().get(nodeId);
        if (node == null) {
            throw new FlowRuntimeException("Node not found in compiled definition: " + nodeId);
        }
        return node;
    }

    private record UpstreamNodeRef(String nodeId, int depth) {
    }
}

