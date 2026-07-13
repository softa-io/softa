package io.softa.starter.flow.runtime.action;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.runtime.api.FlowCcReadRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;

/**
 * Handles CC read acknowledgement actions.
 */
@Component
public class ReadCcActionHandler implements FlowActionHandler<FlowCcReadRequest> {

    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;

    public ReadCcActionHandler(FlowActionContextService contextService, FlowAuditService auditService) {
        this.contextService = contextService;
        this.auditService = auditService;
    }

    @Override
    public FlowExecutionState handle(FlowCcReadRequest request) {
        if (request == null
                || !StringUtils.hasText(request.getInstanceId())
                || !StringUtils.hasText(request.getNodeId())
                || request.getCycleNumber() == null
                || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId, nodeId, cycleNumber, and actorId are required to mark a CC task as read");
        }

        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        ApprovalActionAuditEntry ccAudit = auditService.findCcAudit(state, request.getNodeId(), request.getCycleNumber(), request.getActorId())
                .orElseThrow(() -> new FlowActionValidationException("CC task not found for node '" + request.getNodeId()
                        + "', cycle " + request.getCycleNumber() + ", actor '" + request.getActorId() + "'"));
        if (auditService.hasReadCcAudit(state, request.getNodeId(), request.getCycleNumber(), request.getActorId())) {
            throw new FlowActionValidationException("CC task has already been marked as read for node '" + request.getNodeId()
                    + "', cycle " + request.getCycleNumber() + ", actor '" + request.getActorId() + "'");
        }

        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        CompiledFlowNode node = contextService.requiredNode(definition, ccAudit.getNodeId());

        auditService.addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.APPROVAL_CC_READ,
                auditService.buildCcReadMessage(node, request, ccAudit));
        auditService.appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.READ)
                .flowCode(definition.getFlowCode())
                .flowRevision(definition.getRevision())
                .nodeId(node.getNodeId())
                .nodeLabel(node.getLabel())
                .cycleNumber(request.getCycleNumber())
                .actorId(request.getActorId())
                .targetActorId(ccAudit.getActorId())
                .comment(request.getComment())
                .statusBefore(state.getStatus())
                .statusAfter(state.getStatus()));
        contextService.persistState(state);
        return state;
    }
}
