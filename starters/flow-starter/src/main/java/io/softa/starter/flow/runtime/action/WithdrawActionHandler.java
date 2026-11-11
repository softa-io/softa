package io.softa.starter.flow.runtime.action;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.WithdrawPolicy;
import io.softa.starter.flow.runtime.engine.FlowStatusTransitions;
import io.softa.starter.flow.runtime.api.FlowWithdrawRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.engine.WithdrawPolicyGuard;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.*;

/**
 * Handles initiator withdrawal of an in-progress approval flow, within the flow's {@link WithdrawPolicy}.
 */
@Component
public class WithdrawActionHandler implements FlowActionHandler<FlowWithdrawRequest> {

    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;
    private final WithdrawPolicyGuard policyGuard;

    public WithdrawActionHandler(FlowActionContextService contextService,
                                 FlowAuditService auditService,
                                 WithdrawPolicyGuard policyGuard) {
        this.contextService = contextService;
        this.auditService = auditService;
        this.policyGuard = policyGuard;
    }

    @Override
    public FlowExecutionState handle(FlowWithdrawRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId and actorId are required to withdraw a flow");
        }
        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        FlowExecutionStatus statusBefore = state.getStatus();

        // Withdrawal takes back a submission; only an approval has one.
        if (!FlowScenario.APPROVAL.equals(definition.getScenario())) {
            throw new FlowActionValidationException("Only an approval can be withdrawn; flow instance "
                    + request.getInstanceId() + " is not one");
        }
        if (!FlowExecutionStatus.WAITING.equals(state.getStatus())) {
            throw new FlowActionValidationException("Flow instance is not waiting for approval and cannot be withdrawn: " + request.getInstanceId());
        }
        if (WithdrawPolicyGuard.policyOf(definition) == WithdrawPolicy.NEVER) {
            throw new FlowActionValidationException("Flow instance does not allow initiator withdrawal: " + request.getInstanceId());
        }
        if (!StringUtils.hasText(state.getInitiatorId())) {
            throw new FlowActionValidationException("Flow instance does not record an initiator and cannot be withdrawn: " + request.getInstanceId());
        }
        if (!state.getInitiatorId().equals(request.getActorId())) {
            throw new FlowActionValidationException("Only the initiator can withdraw this flow instance: " + request.getInstanceId());
        }
        if (!policyGuard.policyPermits(definition, state)) {
            throw new FlowActionValidationException("An approver has already acted on this approval, so it can no "
                    + "longer be withdrawn: " + request.getInstanceId());
        }

        state.getPendingApprovals().clear();
        FlowStatusTransitions.apply(state, FlowExecutionStatus.WITHDRAWN);
        state.getTrace().add(FlowExecutionTraceEntry.builder()
                .flowId(definition.getFlowId())
                .eventType(FlowTraceEventType.FLOW_WITHDRAWN)
                .eventTime(LocalDateTime.now())
                .message(auditService.buildWithdrawalMessage(request))
                .build());
        auditService.appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.WITHDRAW)
                .flowId(definition.getFlowId())
                .flowRevision(definition.getRevision())
                .actorId(request.getActorId())
                .comment(request.getComment())
                .statusBefore(statusBefore)
                .statusAfter(state.getStatus())
                .build());
        contextService.persistState(state);
        return state;
    }
}
