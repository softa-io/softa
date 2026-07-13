package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowCommentRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Handles adding a comment to a flow instance without changing its status.
 */
@Component
public class CommentActionHandler implements FlowActionHandler<FlowCommentRequest> {

    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;

    public CommentActionHandler(FlowActionContextService contextService,
                                FlowAuditService auditService) {
        this.contextService = contextService;
        this.auditService = auditService;
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
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        FlowExecutionStatus statusBefore = state.getStatus();

        auditService.appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.COMMENT)
                .flowCode(definition.getFlowCode())
                .flowRevision(definition.getRevision())
                .actorId(request.getActorId())
                .comment(request.getComment())
                .nodeId(request.getNodeId())
                .statusBefore(statusBefore)
                .statusAfter(statusBefore));
        contextService.persistState(state);
        return state;
    }
}
