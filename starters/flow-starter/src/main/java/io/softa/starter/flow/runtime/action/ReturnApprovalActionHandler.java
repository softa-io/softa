package io.softa.starter.flow.runtime.action;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.engine.FlowStatusTransitions;
import io.softa.starter.flow.runtime.api.FlowReturnRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.ApprovalActorValidator;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.*;

/**
 * Returns a pending approval to the initiator, which ends the instance as {@code RETURNED}: the
 * initiator edits and submits again, and that submission starts a new instance from the beginning.
 *
 * <p>Allowed when the node's {@link ApprovalNodeConfig#getReturnEnabled() returnEnabled} is set, to one
 * of the node's approvers whose turn it is — the same actor rules as a rejection — and with a comment
 * saying what to change.
 */
@Component
public class ReturnApprovalActionHandler implements FlowActionHandler<FlowReturnRequest> {

    private final FlowActionContextService contextService;
    private final ApprovalActorValidator actorValidator;
    private final FlowAuditService auditService;
    private final ApprovalNotificationService notificationService;

    public ReturnApprovalActionHandler(FlowActionContextService contextService,
                                       ApprovalActorValidator actorValidator,
                                       FlowAuditService auditService,
                                       ApprovalNotificationService notificationService) {
        this.contextService = contextService;
        this.actorValidator = actorValidator;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Override
    public FlowExecutionState handle(FlowReturnRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getNodeId())
                || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId, nodeId, and actorId are required to return a pending node");
        }
        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        contextService.requireWaitingApproval(state, request.getInstanceId(), "returned");

        PendingApproval pendingApproval = contextService.requirePendingApproval(state, request.getNodeId());
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        CompiledFlowNode node = contextService.requiredNode(definition, pendingApproval.getNodeId());
        ApprovalNodeConfig cfg = resolveConfig(node);
        if (cfg == null || !Boolean.TRUE.equals(cfg.getReturnEnabled())) {
            throw new FlowActionValidationException("Approval node does not allow return: " + node.getNodeId());
        }
        if (!StringUtils.hasText(state.getInitiatorId())) {
            throw new FlowActionValidationException("Flow instance does not record an initiator and cannot be returned: " + state.getInstanceId());
        }
        actorValidator.validateApprovalActor(pendingApproval, request.getActorId(), node);
        actorValidator.requireNotBlockedByPrerequisite(pendingApproval, request.getActorId(), node);
        // A return says what to change: the initiator edits the record and submits it again.
        if (!StringUtils.hasText(request.getComment())) {
            throw new FlowActionValidationException("A comment is required to return approval node: " + request.getNodeId());
        }

        FlowExecutionStatus statusBefore = state.getStatus();
        state.getPendingApprovals().clear();
        FlowStatusTransitions.apply(state, FlowExecutionStatus.RETURNED);
        contextService.mergeVariables(state, Map.of("returnDecision",
                auditService.buildReturnDecisionPayload(request, node, state.getInitiatorId())));
        auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.APPROVAL_RETURNED,
                auditService.buildReturnMessage(node, request, state.getInitiatorId()));
        auditService.appendApprovalAudit(state, auditService.baseEntry(definition, node, pendingApproval, statusBefore, state.getStatus()).toBuilder()
                .action(ApprovalActionType.RETURN)
                .actorId(request.getActorId())
                .comment(request.getComment())
                .targetActorId(state.getInitiatorId())
                .build());
        contextService.persistState(state);
        notificationService.notify(new FlowNotificationEvent.Returned(state, pendingApproval,
                request.getActorId(), request.getComment()));
        return state;
    }

    private static ApprovalNodeConfig resolveConfig(CompiledFlowNode node) {
        Object parsed = node.getParsedConfig();
        return parsed instanceof ApprovalNodeConfig cfg ? cfg : null;
    }
}
