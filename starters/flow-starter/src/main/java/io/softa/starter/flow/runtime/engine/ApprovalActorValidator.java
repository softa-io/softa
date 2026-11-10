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
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Unified validation for approval actor eligibility across all action types. A refusal names the people it is about
 * and the step by its label, as the person who acted sees them; the person an action hands the step to — a transfer,
 * delegation or reassignment target, someone CC'd — must be able to act ({@link ActorAccounts}).
 */
@Component
public class ApprovalActorValidator {

    // The refusals, translated: {0} is a person's name, {1} the step's label (else its node id).
    private static final String NOT_AN_APPROVER = "{0} is not an approver of step ''{1}''.";
    private static final String ALREADY_AN_APPROVER = "{0} is already an approver of step ''{1}''.";
    private static final String ALREADY_APPROVED = "{0} has already approved step ''{1}''.";
    /** {0} is the step's label. */
    private static final String NO_APPROVERS = "Step ''{0}'' has no approvers.";

    /** Names people and tells who can act; without it people are named by their ids and anyone can act. */
    private final ActorAccounts actorAccounts;

    public ApprovalActorValidator() {
        this(null);
    }

    @Autowired
    public ApprovalActorValidator(ActorAccounts actorAccounts) {
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
        requireUsable(List.of(request.getTargetActorId()));
    }

    /**
     * An administrator reassigning {@code actorId}'s task to {@code targetActorId}: the former must be an approver of
     * the step still to act, the latter able to act. The new approver may already be one (the two merge).
     */
    public void validateReassignActor(PendingApproval pendingApproval, String actorId, String targetActorId,
                                      CompiledFlowNode node) {
        requireNonEmptyApprovers(pendingApproval, node);
        requireActorIsApprover(pendingApproval, actorId, node);
        requireNotVoted(pendingApproval, actorId, node);
        requireUsable(List.of(targetActorId));
    }

    /**
     * The actor CC'ing on a step: one of its approvers, still to vote. Who they CC is the action's to sort out — it
     * skips the people who already see the approval.
     */
    public void validateCcActor(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        requireNonEmptyApprovers(pendingApproval, node);
        requireActorIsApprover(pendingApproval, actorId, node);
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

    /**
     * Refuses an actor who already approved the step in this cycle. A rejection ends the approval, so nobody has
     * rejected a step still waiting.
     */
    public void requireNotVoted(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        if (pendingApproval.getApprovedActors() != null && pendingApproval.getApprovedActors().contains(actorId)) {
            throw new FlowActionValidationException(I18n.get(ALREADY_APPROVED, nameOf(actorId), node.displayName()));
        }
    }

    // --- shared building blocks ---

    private void requireNonEmptyApprovers(PendingApproval pendingApproval, CompiledFlowNode node) {
        if (pendingApproval.getApprovers() == null || pendingApproval.getApprovers().isEmpty()) {
            throw new FlowActionValidationException(I18n.get(NO_APPROVERS, node.displayName()));
        }
    }

    private void requireActorIsApprover(PendingApproval pendingApproval, String actorId, CompiledFlowNode node) {
        if (!pendingApproval.getApprovers().contains(actorId)) {
            throw new FlowActionValidationException(I18n.get(NOT_AN_APPROVER, nameOf(actorId), node.displayName()));
        }
    }

    private void requireTargetNotExisting(PendingApproval pendingApproval, String targetActorId, CompiledFlowNode node) {
        if (pendingApproval.getApprovers().contains(targetActorId)) {
            throw new FlowActionValidationException(I18n.get(ALREADY_AN_APPROVER, nameOf(targetActorId), node.displayName()));
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
}
