package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowCommentRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

/**
 * Handles adding a comment to a flow instance without changing its status. Only its participants comment on it —
 * its initiator and the people it gives or gave a task to, by the rule its viewers are admitted by
 * ({@link FlowInstanceAccessGuard#isParticipant}): an approver whose step passed on the others' votes still
 * comments while the approval runs, and an administrator who urged it does not.
 */
@Component
public class CommentActionHandler implements FlowActionHandler<FlowCommentRequest> {

    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;
    private final FlowInstanceAccessGuard accessGuard;

    public CommentActionHandler(FlowActionContextService contextService,
                                FlowAuditService auditService,
                                FlowInstanceAccessGuard accessGuard) {
        this.contextService = contextService;
        this.auditService = auditService;
        this.accessGuard = accessGuard;
    }

    @Override
    public FlowExecutionState handle(FlowCommentRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId and actorId are required to add a comment");
        }
        if (!StringUtils.hasText(request.getComment())) {
            throw new FlowActionValidationException("comment is required");
        }
        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        // A comment belongs to an approval while it runs; one that has ended keeps its history as it closed.
        if (state.getStatus() == null || state.getStatus().isTerminal()) {
            throw new FlowActionValidationException("Flow instance has ended and takes no more comments: "
                    + request.getInstanceId());
        }
        if (!accessGuard.isParticipant(state, request.getActorId())) {
            throw new FlowAuthorizationException("Only the initiator and the people flow instance "
                    + request.getInstanceId() + " gives a task to may comment on it");
        }
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        FlowExecutionStatus statusBefore = state.getStatus();

        auditService.appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.COMMENT)
                .flowId(definition.getFlowId())
                .flowRevision(definition.getRevision())
                .actorId(request.getActorId())
                .comment(request.getComment())
                .statusBefore(statusBefore)
                .statusAfter(statusBefore)
                .build());
        contextService.persistState(state);
        return state;
    }
}
