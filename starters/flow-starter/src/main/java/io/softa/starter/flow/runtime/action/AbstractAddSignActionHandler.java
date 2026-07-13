package io.softa.starter.flow.runtime.action;

import io.softa.starter.flow.runtime.api.AbstractFlowNodeTargetActorRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.*;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.*;

/**
 * Shared template for add-sign actions, which differ only in their {@link AddSignPosition}
 * (BEFORE vs AFTER) and the request-field validation messages kept in the concrete handlers.
 * The position drives the position-aware collaborator dispatchers each of which routes BEFORE to the original
 * before-logic and AFTER to the original after-logic, so the ordering semantics are preserved.
 *
 * @param <R> the concrete target-actor request type
 */
public abstract class AbstractAddSignActionHandler<R extends AbstractFlowNodeTargetActorRequest>
        implements FlowActionHandler<R> {

    protected final FlowActionContextService contextService;
    protected final ApprovalActorValidator actorValidator;
    protected final ApprovalLifecycleService lifecycleService;
    protected final FlowAuditService auditService;

    protected AbstractAddSignActionHandler(FlowActionContextService contextService,
                                           ApprovalActorValidator actorValidator,
                                           ApprovalLifecycleService lifecycleService,
                                           FlowAuditService auditService) {
        this.contextService = contextService;
        this.actorValidator = actorValidator;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
    }

    /**
     * Runs the shared add-sign body after the concrete handler has validated request fields.
     * Applies the position-specific waiting-approval gate (preserving the exact inline message),
     * loads the approval context, validates the actor, inserts the add-sign approver, records the
     * trace and audit, and persists.
     *
     * @param notWaitingMessage the exact message thrown when the instance is not waiting for approval
     */
    protected final FlowExecutionState addSign(R request, AddSignPosition position, String notWaitingMessage) {
        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        if (!FlowExecutionStatus.WAITING.equals(state.getStatus())) {
            throw new FlowActionValidationException(notWaitingMessage);
        }

        ApprovalContext ctx = contextService.loadApprovalContext(state, request.getNodeId());
        PendingApproval pendingApproval = ctx.pendingApproval();
        CompiledFlowDefinition definition = ctx.definition();
        CompiledFlowNode node = ctx.node();

        actorValidator.validateAddSign(pendingApproval, request, node, position);
        lifecycleService.insertAddSign(pendingApproval, request.getActorId(), request.getTargetActorId(), node, position);

        auditService.addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.APPROVAL_ADD_SIGNED,
                auditService.buildAddSignMessage(node, request, position));
        auditService.appendApprovalAudit(state, auditService.baseBuilder(definition, node, pendingApproval, ctx.statusBefore(), state.getStatus())
                .action(ApprovalActionType.ADD_SIGN)
                .actorId(request.getActorId())
                .targetActorId(request.getTargetActorId())
                .addSignPosition(position)
                .comment(request.getComment()));
        contextService.persistState(state);
        return state;
    }
}
