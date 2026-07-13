package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowTransferRequest;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalLifecycleService;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;

/**
 * Handles transfer actions for pending approvals.
 */
@Component
public class TransferActionHandler extends AbstractReplaceApproverActionHandler<FlowTransferRequest> {

    public TransferActionHandler(FlowActionContextService contextService,
                                 ApprovalActorValidator actorValidator,
                                 ApprovalLifecycleService lifecycleService,
                                 FlowAuditService auditService,
                                 ApprovalNotificationService notificationService) {
        super(contextService, actorValidator, lifecycleService, auditService, notificationService);
    }

    @Override
    public FlowExecutionState handle(FlowTransferRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getInstanceId())
                || !StringUtils.hasText(request.getNodeId())
                || !StringUtils.hasText(request.getActorId())
                || !StringUtils.hasText(request.getTargetActorId())) {
            throw new FlowActionValidationException("instanceId, nodeId, actorId, and targetActorId are required to transfer a pending task");
        }
        if (request.getActorId().equals(request.getTargetActorId())) {
            throw new FlowActionValidationException("Transfer target must be different from the source actor");
        }

        return replaceApprover(request, "transferred",
                actorValidator::validateTransferActors,
                FlowTraceEventType.APPROVAL_TRANSFERRED,
                auditService::buildTransferMessage,
                ApprovalActionType.TRANSFER,
                (state, pendingApproval) ->
                        new FlowNotificationEvent.TaskTransferred(state, pendingApproval, request.getTargetActorId()));
    }
}
