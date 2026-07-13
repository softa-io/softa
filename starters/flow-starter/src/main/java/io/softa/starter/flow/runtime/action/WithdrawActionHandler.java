package io.softa.starter.flow.runtime.action;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.engine.FlowStatusTransitions;
import io.softa.starter.flow.runtime.api.FlowWithdrawRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.*;

/**
 * Handles initiator withdrawal of an in-progress approval flow.
 */
@Component
public class WithdrawActionHandler implements FlowActionHandler<FlowWithdrawRequest> {

    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;

    public WithdrawActionHandler(FlowActionContextService contextService,
                                 FlowAuditService auditService) {
        this.contextService = contextService;
        this.auditService = auditService;
    }

    @Override
    public FlowExecutionState handle(FlowWithdrawRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId and actorId are required to withdraw a flow");
        }
        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        FlowExecutionStatus statusBefore = state.getStatus();

        if (!FlowExecutionStatus.WAITING.equals(state.getStatus())) {
            throw new FlowActionValidationException("Flow instance is not waiting for approval and cannot be withdrawn: " + request.getInstanceId());
        }
        if (!Boolean.TRUE.equals(definition.getAllowInitiatorWithdraw())) {
            throw new FlowActionValidationException("Flow instance does not allow initiator withdrawal: " + request.getInstanceId());
        }
        if (!StringUtils.hasText(state.getInitiatorId())) {
            throw new FlowActionValidationException("Flow instance does not record an initiator and cannot be withdrawn: " + request.getInstanceId());
        }
        if (!state.getInitiatorId().equals(request.getActorId())) {
            throw new FlowActionValidationException("Only the initiator can withdraw this flow instance: " + request.getInstanceId());
        }

        state.getPendingApprovals().clear();
        FlowStatusTransitions.apply(state, FlowExecutionStatus.WITHDRAWN);
        contextService.mergeVariables(state, Map.of("withdrawalDecision", auditService.buildWithdrawalDecisionPayload(request)));
        state.getTrace().add(FlowExecutionTraceEntry.builder()
                .flowCode(definition.getFlowCode())
                .eventType(FlowTraceEventType.FLOW_WITHDRAWN)
                .eventTime(LocalDateTime.now())
                .message(auditService.buildWithdrawalMessage(request))
                .build());
        auditService.appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.WITHDRAW)
                .flowCode(definition.getFlowCode())
                .flowRevision(definition.getRevision())
                .actorId(request.getActorId())
                .comment(request.getComment())
                .statusBefore(statusBefore)
                .statusAfter(state.getStatus()));
        contextService.persistState(state);
        return state;
    }
}
