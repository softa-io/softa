package io.softa.starter.flow.runtime.engine;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.AddSignPosition;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Unified validation for approval actor eligibility across all action types.
 */
@Component
public class ApprovalActorValidator {

    private final ApprovalLifecycleService lifecycleService;

    private final ApprovalAuditReader auditReader;

    public ApprovalActorValidator(ApprovalLifecycleService lifecycleService, ApprovalAuditReader auditReader) {
        this.lifecycleService = lifecycleService;
        this.auditReader = auditReader;
    }

    public void validateApprovalActor(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        if (!StringUtils.hasText(actorId) || pendingApproval.getApprovers() == null || pendingApproval.getApprovers().isEmpty()) {
            return;
        }
        if (!pendingApproval.getApprovers().contains(actorId)) {
            throw new FlowActionValidationException("Actor '" + actorId + "' is not an approver for node: " + node.getNodeId());
        }
    }

    public void validateRejectActor(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        if (!StringUtils.hasText(actorId) || pendingApproval.getApprovers() == null || pendingApproval.getApprovers().isEmpty()) {
            return;
        }
        if (!pendingApproval.getApprovers().contains(actorId)) {
            throw new FlowActionValidationException("Actor '" + actorId + "' is not an approver for node: " + node.getNodeId());
        }
    }

    public void validateTransferActors(PendingApproval pendingApproval, FlowTransferRequest request, CompiledFlowNode node) {
        requireNonEmptyApprovers(pendingApproval, node, "transferrable");
        requireActorIsApprover(pendingApproval, request.getActorId(), node);
        requireTargetNotExisting(pendingApproval, request.getTargetActorId(), node);
        requireActorNotAlreadyVoted(pendingApproval, request.getActorId());
        lifecycleService.assertActorNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
    }

    public void validateDelegateActors(PendingApproval pendingApproval, FlowDelegateRequest request, CompiledFlowNode node) {
        requireNonEmptyApprovers(pendingApproval, node, "delegatable");
        requireActorIsApprover(pendingApproval, request.getActorId(), node);
        requireTargetNotExisting(pendingApproval, request.getTargetActorId(), node);
        requireActorNotAlreadyVoted(pendingApproval, request.getActorId());
        lifecycleService.assertActorNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
    }

    /**
     * Position-aware dispatcher: routes BEFORE to the add-sign-before validation and AFTER to the
     * add-sign-after validation so the per-position messages are preserved exactly.
     */
    public void validateAddSign(PendingApproval pendingApproval,
                                AbstractFlowNodeTargetActorRequest request,
                                CompiledFlowNode node,
                                AddSignPosition position) {
        switch (position) {
            case BEFORE -> validateAddSignCommon(pendingApproval, request, node, "add sign before",
                    "Approval node already has an unresolved add-sign-before prerequisite: " + node.getNodeId());
            case AFTER -> validateAddSignCommon(pendingApproval, request, node, "add sign after",
                    "Approval node already has an unresolved add-sign dependency: " + node.getNodeId());
        }
    }

    private void validateAddSignCommon(PendingApproval pendingApproval,
                                       AbstractFlowNodeTargetActorRequest request,
                                       CompiledFlowNode node,
                                       String emptyApproversLabel,
                                       String unresolvedDependencyMessage) {
        requireNonEmptyApprovers(pendingApproval, node, emptyApproversLabel);
        requireActorIsApprover(pendingApproval, request.getActorId(), node);
        requireTargetNotExisting(pendingApproval, request.getTargetActorId(), node);
        requireActorNotAlreadyVoted(pendingApproval, request.getActorId());
        lifecycleService.assertActorNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
        if (lifecycleService.hasUnresolvedAddSignDependency(pendingApproval)) {
            throw new FlowActionValidationException(unresolvedDependencyMessage);
        }
    }

    public void validateCcRequest(FlowExecutionState state,
                                  PendingApproval pendingApproval,
                                  FlowCcRequest request,
                                  CompiledFlowNode node) {
        requireNonEmptyApprovers(pendingApproval, node, "CC");
        requireActorIsApprover(pendingApproval, request.getActorId(), node);
        requireTargetNotExisting(pendingApproval, request.getTargetActorId(), node);
        lifecycleService.assertActorNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
        requireActorNotAlreadyVoted(pendingApproval, request.getActorId());
        boolean duplicateCc = auditReader.fullHistory(state).stream()
                .anyMatch(entry -> ApprovalActionType.CC.equals(entry.getAction())
                        && Objects.equals(pendingApproval.getNodeId(), entry.getNodeId())
                        && Objects.equals(pendingApproval.getCycleNumber(), entry.getCycleNumber())
                        && Objects.equals(request.getTargetActorId(), entry.getTargetActorId()));
        if (duplicateCc) {
            throw new FlowActionValidationException("Target actor '" + request.getTargetActorId() + "' is already CCed for node: " + node.getNodeId());
        }
    }

    public FlowCcRequest buildCcRequest(FlowBatchCcRequest request, String targetActorId) {
        FlowCcRequest ccRequest = new FlowCcRequest();
        ccRequest.setInstanceId(request.getInstanceId());
        ccRequest.setNodeId(request.getNodeId());
        ccRequest.setActorId(request.getActorId());
        ccRequest.setTargetActorId(targetActorId);
        ccRequest.setComment(request.getComment());
        return ccRequest;
    }

    public List<String> normalizeCcRecipients(List<String> targetActorIds) {
        if (targetActorIds == null || targetActorIds.isEmpty()) {
            return List.of();
        }
        Set<String> orderedUnique = new LinkedHashSet<>();
        for (String targetActorId : targetActorIds) {
            if (StringUtils.hasText(targetActorId)) {
                orderedUnique.add(targetActorId.trim());
            }
        }
        return List.copyOf(orderedUnique);
    }

    // --- shared building blocks ---

    private void requireNonEmptyApprovers(PendingApproval pendingApproval, CompiledFlowNode node, String actionLabel) {
        if (pendingApproval.getApprovers() == null || pendingApproval.getApprovers().isEmpty()) {
            throw new FlowActionValidationException("Approval node has no " + actionLabel + " approvers: " + node.getNodeId());
        }
    }

    private void requireActorIsApprover(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        if (!pendingApproval.getApprovers().contains(actorId)) {
            throw new FlowActionValidationException("Actor '" + actorId + "' is not an approver for node: " + node.getNodeId());
        }
    }

    private void requireTargetNotExisting(PendingApproval pendingApproval, String targetActorId, CompiledFlowNode node) {
        if (pendingApproval.getApprovers().contains(targetActorId)) {
            throw new FlowActionValidationException("Target actor '" + targetActorId + "' is already an approver for node: " + node.getNodeId());
        }
    }

    private void requireActorNotAlreadyVoted(PendingApproval pendingApproval, String actorId) {
        if (pendingApproval.getApprovedActors() != null && pendingApproval.getApprovedActors().contains(actorId)) {
            throw new FlowActionValidationException("Approver has already approved this approval node: " + actorId);
        }
        if (pendingApproval.getRejectedActors() != null && pendingApproval.getRejectedActors().contains(actorId)) {
            throw new FlowActionValidationException("Approver has already rejected this approval node: " + actorId);
        }
    }
}

