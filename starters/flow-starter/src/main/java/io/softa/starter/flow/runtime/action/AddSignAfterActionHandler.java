package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowAddSignAfterRequest;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalLifecycleService;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.AddSignPosition;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Handles add-sign-after actions for pending approvals.
 */
@Component
public class AddSignAfterActionHandler extends AbstractAddSignActionHandler<FlowAddSignAfterRequest> {

    public AddSignAfterActionHandler(FlowActionContextService contextService,
                                     ApprovalActorValidator actorValidator,
                                     ApprovalLifecycleService lifecycleService,
                                     FlowAuditService auditService) {
        super(contextService, actorValidator, lifecycleService, auditService);
    }

    @Override
    public FlowExecutionState handle(FlowAddSignAfterRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getInstanceId())
                || !StringUtils.hasText(request.getNodeId())
                || !StringUtils.hasText(request.getActorId())
                || !StringUtils.hasText(request.getTargetActorId())) {
            throw new FlowActionValidationException("instanceId, nodeId, actorId, and targetActorId are required to add sign after a pending task");
        }
        if (request.getActorId().equals(request.getTargetActorId())) {
            throw new FlowActionValidationException("Add-sign target must be different from the source actor");
        }

        return addSign(request, AddSignPosition.AFTER,
                "Flow instance is not waiting for approval and cannot add sign after: " + request.getInstanceId());
    }
}
