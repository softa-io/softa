package io.softa.starter.flow.runtime.action;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowCcRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.ApprovalContext;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Handles single-recipient CC actions for pending approvals.
 */
@Component
public class CcActionHandler implements FlowActionHandler<FlowCcRequest> {

    private final FlowActionContextService contextService;
    private final ApprovalActorValidator actorValidator;
    private final FlowAuditService auditService;
    private final ApprovalNotificationService notificationService;

    public CcActionHandler(FlowActionContextService contextService,
                           ApprovalActorValidator actorValidator,
                           FlowAuditService auditService,
                           ApprovalNotificationService notificationService) {
        this.contextService = contextService;
        this.actorValidator = actorValidator;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Override
    public FlowExecutionState handle(FlowCcRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getInstanceId())
                || !StringUtils.hasText(request.getNodeId())
                || !StringUtils.hasText(request.getActorId())
                || !StringUtils.hasText(request.getTargetActorId())) {
            throw new FlowActionValidationException("instanceId, nodeId, actorId, and targetActorId are required to CC a pending task");
        }
        if (request.getActorId().equals(request.getTargetActorId())) {
            throw new FlowActionValidationException("CC target must be different from the source actor");
        }

        ApprovalContext ctx = contextService.loadApprovalContext(request.getInstanceId(), request.getNodeId(), "CCed");
        FlowExecutionState state = ctx.state();
        PendingApproval pendingApproval = ctx.pendingApproval();
        CompiledFlowDefinition definition = ctx.definition();
        CompiledFlowNode node = ctx.node();
        FlowExecutionStatus statusBefore = ctx.statusBefore();

        actorValidator.validateCcRequest(state, pendingApproval, request, node);
        auditService.appendCcAudit(state, pendingApproval, definition, node,
                request.getActorId(), request.getTargetActorId(), request.getComment(), statusBefore);
        contextService.persistState(state);
        notificationService.notify(new FlowNotificationEvent.CcSent(state, request.getNodeId(),
                List.of(request.getTargetActorId()), request.getComment()));
        return state;
    }
}
