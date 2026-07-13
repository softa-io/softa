package io.softa.starter.flow.runtime.action;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.enums.ApprovalReturnTarget;
import io.softa.starter.flow.runtime.engine.FlowStatusTransitions;
import io.softa.starter.flow.runtime.api.FlowReturnRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.ApprovalLifecycleService;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.engine.PendingApprovalFactory;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.state.*;

/**
 * Handles return-approval actions for pending approvals.
 *
 * <p>Return policy is read from {@link ApprovalNodeConfig} (embedded in
 * {@link CompiledFlowNode#getParsedConfig()}), replacing the former separate
 * {@code returnPolicy} field on the compiled node.</p>
 */
@Component
public class ReturnApprovalActionHandler implements FlowActionHandler<FlowReturnRequest> {

    private final FlowActionContextService contextService;
    private final ApprovalLifecycleService lifecycleService;
    private final FlowAuditService auditService;
    private final PendingApprovalFactory pendingApprovalFactory;

    public ReturnApprovalActionHandler(FlowActionContextService contextService,
                                       ApprovalLifecycleService lifecycleService,
                                       FlowAuditService auditService,
                                       PendingApprovalFactory pendingApprovalFactory) {
        this.contextService = contextService;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
        this.pendingApprovalFactory = pendingApprovalFactory;
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
        if (state.getPendingApprovals().size() > 1) {
            throw new FlowActionValidationException("Approval return is not supported when multiple approvals are pending: "
                    + state.getPendingApprovals().stream().map(PendingApproval::getNodeId).toList());
        }

        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        CompiledFlowNode node = contextService.requiredNode(definition, pendingApproval.getNodeId());
        ApprovalNodeConfig cfg = resolveConfig(node);
        if (cfg == null || !Boolean.TRUE.equals(cfg.getReturnEnabled())) {
            throw new FlowActionValidationException("Approval node does not allow return: " + node.getNodeId());
        }
        ApprovalReturnTarget target = cfg.getReturnTarget();
        if (ApprovalReturnTarget.INITIATOR.equals(target)) {
            return returnToInitiator(state, request, pendingApproval, definition, node, target);
        }
        if (ApprovalReturnTarget.PREVIOUS_APPROVAL.equals(target)) {
            return returnToPreviousApproval(state, request, pendingApproval, definition, node, target);
        }
        if (ApprovalReturnTarget.SPECIFIC_NODE.equals(target)) {
            return returnToSpecificNode(state, request, pendingApproval, definition, node, cfg, target);
        }
        throw new FlowActionValidationException("Unsupported approval return target for node '" + node.getNodeId() + "': " + target);
    }

    private FlowExecutionState returnToInitiator(FlowExecutionState state,
                                                 FlowReturnRequest request,
                                                 PendingApproval pendingApproval,
                                                 CompiledFlowDefinition definition,
                                                 CompiledFlowNode node,
                                                 ApprovalReturnTarget target) {
        FlowExecutionStatus statusBefore = state.getStatus();
        if (!StringUtils.hasText(state.getInitiatorId())) {
            throw new FlowActionValidationException("Flow instance does not record an initiator and cannot be returned: " + state.getInstanceId());
        }

        state.getPendingApprovals().clear();
        FlowStatusTransitions.apply(state, FlowExecutionStatus.RETURNED);
        state.setReturnedApproval(ReturnedApprovalContext.builder()
                .pendingApproval(pendingApprovalFactory.resetPendingApprovalProgress(pendingApproval))
                .target(target)
                .targetActorId(state.getInitiatorId())
                .actorId(request.getActorId())
                .comment(request.getComment())
                .returnedAt(LocalDateTime.now())
                .build());
        contextService.mergeVariables(state, Map.of("returnDecision",
                auditService.buildReturnDecisionPayload(request, node, state.getInitiatorId(), target, null)));
        auditService.addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.APPROVAL_RETURNED,
                auditService.buildReturnMessage(node, request, target, state.getInitiatorId(), null));
        auditService.appendApprovalAudit(state, auditService.baseBuilder(definition, node, pendingApproval, statusBefore, state.getStatus())
                .action(ApprovalActionType.RETURN)
                .actorId(request.getActorId())
                .comment(request.getComment())
                .targetType(target)
                .targetActorId(state.getInitiatorId()));
        contextService.persistState(state);
        return state;
    }

    private FlowExecutionState returnToPreviousApproval(FlowExecutionState state,
                                                        FlowReturnRequest request,
                                                        PendingApproval pendingApproval,
                                                        CompiledFlowDefinition definition,
                                                        CompiledFlowNode node,
                                                        ApprovalReturnTarget target) {
        FlowExecutionStatus statusBefore = state.getStatus();
        CompiledFlowNode targetNode = lifecycleService.resolvePreviousApprovalNode(definition, state, node);
        PendingApproval reopenedApproval = pendingApprovalFactory.buildPendingApproval(definition, targetNode, state);

        state.getPendingApprovals().removeIf(item -> pendingApproval.getNodeId().equals(item.getNodeId()));
        state.getPendingApprovals().clear();
        state.getPendingApprovals().add(reopenedApproval);
        state.setReturnedApproval(null);
        FlowStatusTransitions.apply(state, FlowExecutionStatus.WAITING);
        contextService.mergeVariables(state, Map.of("returnDecision",
                auditService.buildReturnDecisionPayload(request, node, null, target, targetNode)));
        auditService.addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.APPROVAL_RETURNED,
                auditService.buildReturnMessage(node, request, target, null, targetNode));
        auditService.addTrace(state, definition.getFlowCode(), targetNode, FlowTraceEventType.WAIT_APPROVAL,
                "Waiting for approval at node " + targetNode.getNodeId() + " after return from " + node.getNodeId());
        auditService.appendApprovalAudit(state, auditService.baseBuilder(definition, node, pendingApproval, statusBefore, state.getStatus())
                .action(ApprovalActionType.RETURN)
                .actorId(request.getActorId())
                .comment(request.getComment())
                .targetType(target)
                .targetNodeId(targetNode.getNodeId())
                .targetNodeLabel(targetNode.getLabel()));
        contextService.persistState(state);
        return state;
    }

    private FlowExecutionState returnToSpecificNode(FlowExecutionState state,
                                                    FlowReturnRequest request,
                                                    PendingApproval pendingApproval,
                                                    CompiledFlowDefinition definition,
                                                    CompiledFlowNode node,
                                                    ApprovalNodeConfig cfg,
                                                    ApprovalReturnTarget target) {
        FlowExecutionStatus statusBefore = state.getStatus();
        String targetNodeId = cfg.getReturnTargetNodeId();
        if (!StringUtils.hasText(targetNodeId)) {
            throw new FlowActionValidationException("Approval node '" + node.getNodeId()
                    + "' uses SPECIFIC_NODE return target but returnTargetNodeId is not configured");
        }
        CompiledFlowNode targetNode = contextService.requiredNode(definition, targetNodeId);
        PendingApproval reopenedApproval = pendingApprovalFactory.buildPendingApproval(definition, targetNode, state);

        state.getPendingApprovals().removeIf(item -> pendingApproval.getNodeId().equals(item.getNodeId()));
        state.getPendingApprovals().clear();
        state.getPendingApprovals().add(reopenedApproval);
        state.setReturnedApproval(null);
        FlowStatusTransitions.apply(state, FlowExecutionStatus.WAITING);
        contextService.mergeVariables(state, Map.of("returnDecision",
                auditService.buildReturnDecisionPayload(request, node, null, target, targetNode)));
        auditService.addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.APPROVAL_RETURNED,
                auditService.buildReturnMessage(node, request, target, null, targetNode));
        auditService.addTrace(state, definition.getFlowCode(), targetNode, FlowTraceEventType.WAIT_APPROVAL,
                "Waiting for approval at node " + targetNode.getNodeId() + " after return from " + node.getNodeId());
        auditService.appendApprovalAudit(state, auditService.baseBuilder(definition, node, pendingApproval, statusBefore, state.getStatus())
                .action(ApprovalActionType.RETURN)
                .actorId(request.getActorId())
                .comment(request.getComment())
                .targetType(target)
                .targetNodeId(targetNode.getNodeId())
                .targetNodeLabel(targetNode.getLabel()));
        contextService.persistState(state);
        return state;
    }

    private static ApprovalNodeConfig resolveConfig(CompiledFlowNode node) {
        Object parsed = node.getParsedConfig();
        return parsed instanceof ApprovalNodeConfig cfg ? cfg : null;
    }
}
