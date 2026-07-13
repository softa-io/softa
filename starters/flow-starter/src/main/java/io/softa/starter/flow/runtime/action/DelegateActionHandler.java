package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowDelegateRequest;
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
 * Handles delegate actions for pending approvals.
 */
@Component
public class DelegateActionHandler extends AbstractReplaceApproverActionHandler<FlowDelegateRequest> {

    public DelegateActionHandler(FlowActionContextService contextService,
                                 ApprovalActorValidator actorValidator,
                                 ApprovalLifecycleService lifecycleService,
                                 FlowAuditService auditService,
                                 ApprovalNotificationService notificationService) {
        super(contextService, actorValidator, lifecycleService, auditService, notificationService);
    }

    @Override
    public FlowExecutionState handle(FlowDelegateRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getInstanceId())
                || !StringUtils.hasText(request.getNodeId())
                || !StringUtils.hasText(request.getActorId())
                || !StringUtils.hasText(request.getTargetActorId())) {
            throw new FlowActionValidationException("instanceId, nodeId, actorId, and targetActorId are required to delegate a pending task");
        }
        if (request.getActorId().equals(request.getTargetActorId())) {
            throw new FlowActionValidationException("Delegate target must be different from the source actor");
        }

        return replaceApprover(request, "delegated",
                actorValidator::validateDelegateActors,
                FlowTraceEventType.APPROVAL_DELEGATED,
                auditService::buildDelegateMessage,
                ApprovalActionType.DELEGATE,
                (state, pendingApproval) ->
                        new FlowNotificationEvent.TaskDelegated(state, pendingApproval,
                                request.getActorId(), request.getTargetActorId()));
    }
}
