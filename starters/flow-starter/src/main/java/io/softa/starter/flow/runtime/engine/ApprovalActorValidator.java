package io.softa.starter.flow.runtime.engine;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.i18n.I18n;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.AddSignPosition;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Unified validation for approval actor eligibility across all action types. A refusal names the people it is about
 * and the step by its label, as the person who acted sees them; the person an action hands the step to — a transfer,
 * delegation or reassignment target, a signer added, someone CC'd — must be able to act ({@link ActorAccounts}).
 */
@Component
public class ApprovalActorValidator {

    // The refusals, translated: {0} is a person's name, {1} the step's label (else its node id).
    private static final String NOT_AN_APPROVER = "{0} is not an approver of step ''{1}''.";
    private static final String ALREADY_AN_APPROVER = "{0} is already an approver of step ''{1}''.";
    private static final String ALREADY_APPROVED = "{0} has already approved step ''{1}''.";
    private static final String ALREADY_REJECTED = "{0} has already rejected step ''{1}''.";
    /** {2} is the signer added before {0}. */
    private static final String BLOCKED_BY_PREREQUISITE = "{0} cannot act on step ''{1}'' until {2}, the signer "
            + "added before them, has approved it.";
    /** {0} is the step's label. */
    private static final String NO_APPROVERS = "Step ''{0}'' has no approvers.";
    private static final String UNRESOLVED_ADD_SIGN_BEFORE = "Step ''{0}'' already has a signer added before an "
            + "approver, who has not approved it yet.";
    private static final String UNRESOLVED_ADD_SIGN_AFTER = "Step ''{0}'' already has an added signer who has not "
            + "approved it yet.";

    private final ApprovalLifecycleService lifecycleService;

    /** Names people and tells who can act; without it people are named by their ids and anyone can act. */
    private final ActorAccounts actorAccounts;

    public ApprovalActorValidator(ApprovalLifecycleService lifecycleService) {
        this(lifecycleService, null);
    }

    @Autowired
    public ApprovalActorValidator(ApprovalLifecycleService lifecycleService, ActorAccounts actorAccounts) {
        this.lifecycleService = lifecycleService;
        this.actorAccounts = actorAccounts;
    }

    public void validateApprovalActor(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        if (!StringUtils.hasText(actorId) || pendingApproval.getApprovers() == null || pendingApproval.getApprovers().isEmpty()) {
            return;
        }
        requireActorIsApprover(pendingApproval, actorId, node);
    }

    public void validateRejectActor(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        validateApprovalActor(pendingApproval, actorId, node);
    }

    public void validateTransferActors(PendingApproval pendingApproval, FlowTransferRequest request, CompiledFlowNode node) {
        requireNonEmptyApprovers(pendingApproval, node);
        requireActorIsApprover(pendingApproval, request.getActorId(), node);
        requireTargetNotExisting(pendingApproval, request.getTargetActorId(), node);
        requireNotVoted(pendingApproval, request.getActorId(), node);
        requireNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
        requireUsable(List.of(request.getTargetActorId()));
    }

    /**
     * An administrator reassigning {@code actorId}'s task to {@code targetActorId}: the former must be an approver of
     * the step still to act, the latter able to act. The new approver may already be one (the two merge), and an
     * add-sign prerequisite moves with the task.
     */
    public void validateReassignActor(PendingApproval pendingApproval, String actorId, String targetActorId,
                                      CompiledFlowNode node) {
        requireNonEmptyApprovers(pendingApproval, node);
        requireActorIsApprover(pendingApproval, actorId, node);
        requireNotVoted(pendingApproval, actorId, node);
        requireUsable(List.of(targetActorId));
    }

    public void validateDelegateActors(PendingApproval pendingApproval, FlowDelegateRequest request, CompiledFlowNode node) {
        requireNonEmptyApprovers(pendingApproval, node);
        requireActorIsApprover(pendingApproval, request.getActorId(), node);
        requireTargetNotExisting(pendingApproval, request.getTargetActorId(), node);
        requireNotVoted(pendingApproval, request.getActorId(), node);
        requireNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
        requireUsable(List.of(request.getTargetActorId()));
    }

    /**
     * Position-aware dispatcher: routes BEFORE to the add-sign-before validation and AFTER to the
     * add-sign-after validation, each with its own message for a signer already added.
     */
    public void validateAddSign(PendingApproval pendingApproval,
                                AbstractFlowNodeTargetActorRequest request,
                                CompiledFlowNode node,
                                AddSignPosition position) {
        switch (position) {
            case BEFORE -> validateAddSignCommon(pendingApproval, request, node, UNRESOLVED_ADD_SIGN_BEFORE);
            case AFTER -> validateAddSignCommon(pendingApproval, request, node, UNRESOLVED_ADD_SIGN_AFTER);
        }
    }

    private void validateAddSignCommon(PendingApproval pendingApproval,
                                       AbstractFlowNodeTargetActorRequest request,
                                       CompiledFlowNode node,
                                       String unresolvedDependencyMessage) {
        requireNonEmptyApprovers(pendingApproval, node);
        requireActorIsApprover(pendingApproval, request.getActorId(), node);
        requireTargetNotExisting(pendingApproval, request.getTargetActorId(), node);
        requireNotVoted(pendingApproval, request.getActorId(), node);
        requireNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
        if (lifecycleService.hasUnresolvedAddSignDependency(pendingApproval)) {
            throw new FlowActionValidationException(I18n.get(unresolvedDependencyMessage, stepOf(node)));
        }
        requireUsable(List.of(request.getTargetActorId()));
    }

    /**
     * The actor CC'ing on a step: one of its approvers, still to vote and not waiting on a signer added before them.
     * Who they CC is the action's to sort out — it skips the people who already see the approval.
     */
    public void validateCcActor(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        requireNonEmptyApprovers(pendingApproval, node);
        requireActorIsApprover(pendingApproval, actorId, node);
        requireNotBlockedByPrerequisite(pendingApproval, actorId, node);
        requireNotVoted(pendingApproval, actorId, node);
    }

    /** The people chosen to CC: refused as a whole when any of them cannot act, naming them. */
    public void validateCcRecipients(List<String> recipients) {
        requireUsable(recipients);
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

    /** Refuses an actor who already approved or rejected the step in this cycle. */
    public void requireNotVoted(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        if (pendingApproval.getApprovedActors() != null && pendingApproval.getApprovedActors().contains(actorId)) {
            throw new FlowActionValidationException(I18n.get(ALREADY_APPROVED, nameOf(actorId), stepOf(node)));
        }
        if (pendingApproval.getRejectedActors() != null && pendingApproval.getRejectedActors().contains(actorId)) {
            throw new FlowActionValidationException(I18n.get(ALREADY_REJECTED, nameOf(actorId), stepOf(node)));
        }
    }

    /** Refuses an approver whose turn waits on a signer added before them, until that signer has approved. */
    public void requireNotBlockedByPrerequisite(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        if (lifecycleService.isBlockedByPrerequisite(pendingApproval, actorId)) {
            throw new FlowActionValidationException(I18n.get(BLOCKED_BY_PREREQUISITE, nameOf(actorId), stepOf(node),
                    nameOf(pendingApproval.getPrerequisiteActorId())));
        }
    }

    // --- shared building blocks ---

    private void requireNonEmptyApprovers(PendingApproval pendingApproval, CompiledFlowNode node) {
        if (pendingApproval.getApprovers() == null || pendingApproval.getApprovers().isEmpty()) {
            throw new FlowActionValidationException(I18n.get(NO_APPROVERS, stepOf(node)));
        }
    }

    private void requireActorIsApprover(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        if (!pendingApproval.getApprovers().contains(actorId)) {
            throw new FlowActionValidationException(I18n.get(NOT_AN_APPROVER, nameOf(actorId), stepOf(node)));
        }
    }

    private void requireTargetNotExisting(PendingApproval pendingApproval, String targetActorId, CompiledFlowNode node) {
        if (pendingApproval.getApprovers().contains(targetActorId)) {
            throw new FlowActionValidationException(I18n.get(ALREADY_AN_APPROVER, nameOf(targetActorId), stepOf(node)));
        }
    }

    private void requireUsable(Collection<String> userIds) {
        if (actorAccounts != null) {
            actorAccounts.requireUsable(userIds);
        }
    }

    private String nameOf(String userId) {
        if (actorAccounts == null || !StringUtils.hasText(userId)) {
            return userId;
        }
        return actorAccounts.namesOf(List.of(userId)).getOrDefault(userId, userId);
    }

    /** The step as people see it: its label, else its node id. */
    private static String stepOf(CompiledFlowNode node) {
        return StringUtils.hasText(node.getLabel()) ? node.getLabel() : node.getNodeId();
    }
}
