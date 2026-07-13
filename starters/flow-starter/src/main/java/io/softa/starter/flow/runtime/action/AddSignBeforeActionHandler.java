package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowAddSignBeforeRequest;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalLifecycleService;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.AddSignPosition;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Handles add-sign-before actions for pending approvals.
 */
@Component
public class AddSignBeforeActionHandler extends AbstractAddSignActionHandler<FlowAddSignBeforeRequest> {

    public AddSignBeforeActionHandler(FlowActionContextService contextService,
                                      ApprovalActorValidator actorValidator,
                                      ApprovalLifecycleService lifecycleService,
                                      FlowAuditService auditService) {
        super(contextService, actorValidator, lifecycleService, auditService);
    }

    @Override
    public FlowExecutionState handle(FlowAddSignBeforeRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getInstanceId())
                || !StringUtils.hasText(request.getNodeId())
                || !StringUtils.hasText(request.getActorId())
                || !StringUtils.hasText(request.getTargetActorId())) {
            throw new FlowActionValidationException("instanceId, nodeId, actorId, and targetActorId are required to add sign before a pending task");
        }
        if (request.getActorId().equals(request.getTargetActorId())) {
            throw new FlowActionValidationException("Add-sign target must be different from the source actor");
        }

        return addSign(request, AddSignPosition.BEFORE,
                "Flow instance is not waiting for approval and cannot add sign before: " + request.getInstanceId());
    }
}
