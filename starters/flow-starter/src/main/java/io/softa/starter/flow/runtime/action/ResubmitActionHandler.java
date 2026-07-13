package io.softa.starter.flow.runtime.action;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.engine.FlowStatusTransitions;
import io.softa.starter.flow.runtime.api.FlowResubmitRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.engine.PendingApprovalFactory;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.*;

/**
 * Handles resubmission of a previously returned approval flow instance.
 */
@Component
public class ResubmitActionHandler implements FlowActionHandler<FlowResubmitRequest> {

    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;
    private final PendingApprovalFactory pendingApprovalFactory;

    public ResubmitActionHandler(FlowActionContextService contextService,
                                 FlowAuditService auditService,
                                 PendingApprovalFactory pendingApprovalFactory) {
        this.contextService = contextService;
        this.auditService = auditService;
        this.pendingApprovalFactory = pendingApprovalFactory;
    }

    @Override
    public FlowExecutionState handle(FlowResubmitRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId and actorId are required to resubmit a returned flow");
        }
        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        if (!FlowExecutionStatus.RETURNED.equals(state.getStatus())) {
            throw new FlowActionValidationException("Flow instance is not returned and cannot be resubmitted: " + request.getInstanceId());
        }
        if (!StringUtils.hasText(state.getInitiatorId())) {
            throw new FlowActionValidationException("Flow instance does not record an initiator and cannot be resubmitted: " + request.getInstanceId());
        }
        if (!state.getInitiatorId().equals(request.getActorId())) {
            throw new FlowActionValidationException("Only the initiator can resubmit this flow instance: " + request.getInstanceId());
        }
        ReturnedApprovalContext returnedApproval = state.getReturnedApproval();
        if (returnedApproval == null || returnedApproval.getPendingApproval() == null) {
            throw new FlowActionValidationException("Flow instance does not have a returned approval to resubmit: " + request.getInstanceId());
        }

        PendingApproval pendingApproval = pendingApprovalFactory.copyPendingApproval(returnedApproval.getPendingApproval());
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        CompiledFlowNode node = contextService.requiredNode(definition, pendingApproval.getNodeId());
        FlowExecutionStatus statusBefore = state.getStatus();

        contextService.mergeVariables(state, request.getVariables());
        PendingApproval resolvedPendingApproval = pendingApprovalFactory.buildPendingApproval(definition, node, state);
        state.getPendingApprovals().clear();
        state.getPendingApprovals().add(resolvedPendingApproval);
        state.setReturnedApproval(null);
        state.setResubmissionCount((state.getResubmissionCount() == null ? 0 : state.getResubmissionCount()) + 1);
        FlowStatusTransitions.apply(state, FlowExecutionStatus.WAITING);
        contextService.mergeVariables(state, Map.of("resubmissionDecision",
                auditService.buildResubmissionDecisionPayload(request, node, state.getResubmissionCount())));
        auditService.addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.FLOW_RESUBMITTED,
                auditService.buildResubmissionMessage(node, request, state.getResubmissionCount()));
        auditService.addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.WAIT_APPROVAL,
                "Waiting for approval at node " + node.getNodeId() + " after resubmission");
        auditService.appendApprovalAudit(state, auditService.baseBuilder(definition, node, resolvedPendingApproval, statusBefore, state.getStatus())
                .action(ApprovalActionType.RESUBMIT)
                .actorId(request.getActorId())
                .comment(request.getComment())
                .resubmissionCount(state.getResubmissionCount())
                .variableKeys(request.getVariables() == null ? List.of() : List.copyOf(request.getVariables().keySet())));
        contextService.persistState(state);
        return state;
    }
}
