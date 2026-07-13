package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.enums.ApprovalReturnTarget;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.state.*;

/**
 * Domain service for audit trail, trace entries, and message building.
 */
@Component
public class FlowAuditService {

    private final ApprovalAuditReader auditReader;

    public FlowAuditService(ApprovalAuditReader auditReader) {
        this.auditReader = auditReader;
    }

    public void addTrace(FlowExecutionState state,
                         String flowCode,
                         CompiledFlowNode node,
                         FlowTraceEventType eventType,
                         String message) {
        state.getTrace().add(FlowExecutionTraceEntry.builder()
                .flowCode(flowCode)
                .nodeId(node.getNodeId())
                .flowNodeType(node.getType())
                .eventType(eventType)
                .eventTime(LocalDateTime.now())
                .message(message)
                .build());
    }

    public void appendApprovalAudit(FlowExecutionState state,
                                    ApprovalActionAuditEntry.ApprovalActionAuditEntryBuilder builder) {
        if (state.getApprovalAuditDelta() == null) {
            state.setApprovalAuditDelta(new ArrayList<>());
        }
        // Sequence is assigned at flush time by the ledger (base + index), not here.
        state.getApprovalAuditDelta().add(builder
                .eventTime(LocalDateTime.now())
                .build());
    }

    public void appendCcAudit(FlowExecutionState state,
                              PendingApproval pendingApproval,
                              CompiledFlowDefinition definition,
                              CompiledFlowNode node,
                              String actorId,
                              String targetActorId,
                              String comment,
                              FlowExecutionStatus statusBefore) {
        addTrace(state, definition.getFlowCode(), node, FlowTraceEventType.APPROVAL_CCED,
                buildCcMessage(node, actorId, targetActorId, comment));
        appendApprovalAudit(state, baseBuilder(definition, node, pendingApproval, statusBefore, state.getStatus())
                .action(ApprovalActionType.CC)
                .actorId(actorId)
                .targetActorId(targetActorId)
                .comment(comment));
    }

    public Optional<ApprovalActionAuditEntry> findCcAudit(FlowExecutionState state,
                                                          String nodeId,
                                                          Integer cycleNumber,
                                                          String actorId) {
        List<ApprovalActionAuditEntry> history = auditReader.fullHistory(state);
        for (int i = history.size() - 1; i >= 0; i--) {
            ApprovalActionAuditEntry entry = history.get(i);
            if (ApprovalActionType.CC.equals(entry.getAction())
                    && Objects.equals(nodeId, entry.getNodeId())
                    && Objects.equals(cycleNumber, entry.getCycleNumber())
                    && Objects.equals(actorId, entry.getTargetActorId())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public boolean hasReadCcAudit(FlowExecutionState state,
                                  String nodeId,
                                  Integer cycleNumber,
                                  String actorId) {
        return auditReader.fullHistory(state).stream()
                .anyMatch(entry -> ApprovalActionType.READ.equals(entry.getAction())
                        && Objects.equals(nodeId, entry.getNodeId())
                        && Objects.equals(cycleNumber, entry.getCycleNumber())
                        && Objects.equals(actorId, entry.getActorId()));
    }

    /**
     * Pre-fill a builder with common fields from the approval context.
     */
    public ApprovalActionAuditEntry.ApprovalActionAuditEntryBuilder baseBuilder(
            CompiledFlowDefinition definition,
            CompiledFlowNode node,
            PendingApproval pendingApproval,
            FlowExecutionStatus statusBefore,
            FlowExecutionStatus statusAfter) {
        return ApprovalActionAuditEntry.builder()
                .flowCode(definition.getFlowCode())
                .flowRevision(definition.getRevision())
                .nodeId(node.getNodeId())
                .nodeLabel(node.getLabel())
                .cycleNumber(pendingApproval.getCycleNumber())
                .statusBefore(statusBefore)
                .statusAfter(statusAfter)
                .approvalMode(pendingApproval.getApprovalMode())
                .approvedActors(List.copyOf(pendingApproval.getApprovedActors()))
                .requiredApprovalCount(pendingApproval.getRequiredApprovalCount())
                .totalApproverCount(pendingApproval.getTotalApproverCount())
                .dynamicApprovers(pendingApproval.getDynamicApprovers())
                .rejectMode(pendingApproval.getRejectMode())
                .requiredRejectCount(pendingApproval.getRequiredRejectCount())
                .rejectedActors(List.copyOf(pendingApproval.getRejectedActors()));
    }

    // --- message builders ---

    public String buildPartialApprovalMessage(CompiledFlowNode node, PendingApproval pendingApproval, String actorId) {
        return "Approval recorded for node " + node.getNodeId() + " by " + actorId + " ("
                + pendingApproval.getApprovedActors().size() + "/" + pendingApproval.getRequiredApprovalCount() + ")";
    }

    public String buildPartialRejectMessage(CompiledFlowNode node, PendingApproval pendingApproval, String actorId) {
        return "Reject recorded for node " + node.getNodeId() + " by " + actorId + " ("
                + pendingApproval.getRejectedActors().size() + "/" + pendingApproval.getRequiredRejectCount() + ")";
    }

    public String buildTransferMessage(CompiledFlowNode node, FlowTransferRequest request) {
        StringBuilder builder = new StringBuilder("Approval transferred at node ")
                .append(node.getNodeId()).append(" from ").append(request.getActorId())
                .append(" to ").append(request.getTargetActorId());
        appendComment(builder, request.getComment());
        return builder.toString();
    }

    public String buildDelegateMessage(CompiledFlowNode node, FlowDelegateRequest request) {
        StringBuilder builder = new StringBuilder("Approval delegated at node ")
                .append(node.getNodeId()).append(" from ").append(request.getActorId())
                .append(" to ").append(request.getTargetActorId());
        appendComment(builder, request.getComment());
        return builder.toString();
    }

    /**
     * Position-aware dispatcher: produces the add-sign-before message for BEFORE
     * and the add-sign-after message for AFTER.
     */
    public String buildAddSignMessage(CompiledFlowNode node,
                                      AbstractFlowNodeTargetActorRequest request,
                                      AddSignPosition position) {
        return switch (position) {
            case BEFORE -> buildAddSignMessage(node, request, "Approval add-sign-before applied at node ", " to prerequisite ");
            case AFTER -> buildAddSignMessage(node, request, "Approval add-sign-after applied at node ", " to follow-up ");
        };
    }

    private String buildAddSignMessage(CompiledFlowNode node,
                                       AbstractFlowNodeTargetActorRequest request,
                                       String prefix,
                                       String targetConnector) {
        StringBuilder builder = new StringBuilder(prefix)
                .append(node.getNodeId()).append(" from ").append(request.getActorId())
                .append(targetConnector).append(request.getTargetActorId());
        appendComment(builder, request.getComment());
        return builder.toString();
    }

    public String buildCcReadMessage(CompiledFlowNode node, FlowCcReadRequest request, ApprovalActionAuditEntry ccAudit) {
        StringBuilder builder = new StringBuilder("Approval CC read at node ")
                .append(node.getNodeId()).append(" by ").append(request.getActorId());
        if (StringUtils.hasText(ccAudit.getActorId())) {
            builder.append(" from ").append(ccAudit.getActorId());
        }
        appendComment(builder, request.getComment());
        return builder.toString();
    }

    public Map<String, Object> buildApprovalDecisionPayload(String outcome, FlowRejectRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", outcome);
        payload.put("nodeId", request.getNodeId());
        payload.put("comment", request.getComment());
        payload.put("actor", request.getActorId());
        payload.put("time", LocalDateTime.now().toString());
        return payload;
    }

    public String buildRejectionMessage(CompiledFlowNode node, FlowRejectRequest request) {
        StringBuilder builder = new StringBuilder("Approval rejected at node ").append(node.getNodeId());
        if (StringUtils.hasText(request.getActorId())) {
            builder.append(" by ").append(request.getActorId());
        }
        appendComment(builder, request.getComment());
        return builder.toString();
    }

    public Map<String, Object> buildReturnDecisionPayload(FlowReturnRequest request,
                                                          CompiledFlowNode node,
                                                          String targetActorId,
                                                          ApprovalReturnTarget target,
                                                          CompiledFlowNode targetNode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", "Returned");
        payload.put("nodeId", node.getNodeId());
        payload.put("target", target == null ? null : target.getType());
        payload.put("targetActorId", targetActorId);
        payload.put("targetNodeId", targetNode == null ? null : targetNode.getNodeId());
        payload.put("targetNodeLabel", targetNode == null ? null : targetNode.getLabel());
        payload.put("actorId", request.getActorId());
        payload.put("comment", request.getComment());
        payload.put("time", LocalDateTime.now().toString());
        return payload;
    }

    public String buildReturnMessage(CompiledFlowNode node,
                                     FlowReturnRequest request,
                                     ApprovalReturnTarget target,
                                     String targetActorId,
                                     CompiledFlowNode targetNode) {
        StringBuilder builder = new StringBuilder("Approval returned at node ")
                .append(node.getNodeId()).append(" by ").append(request.getActorId());
        if (target != null) {
            builder.append(" to ").append(target.getType());
        }
        if (StringUtils.hasText(targetActorId)) {
            builder.append(" ").append(targetActorId);
        }
        if (targetNode != null) {
            builder.append(" ").append(targetNode.getNodeId());
        }
        appendComment(builder, request.getComment());
        return builder.toString();
    }

    public Map<String, Object> buildResubmissionDecisionPayload(FlowResubmitRequest request,
                                                                CompiledFlowNode node,
                                                                Integer resubmissionCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", "Resubmitted");
        payload.put("nodeId", node.getNodeId());
        payload.put("actorId", request.getActorId());
        payload.put("comment", request.getComment());
        payload.put("resubmissionCount", resubmissionCount);
        payload.put("variableKeys", request.getVariables() == null ? List.of() : List.copyOf(request.getVariables().keySet()));
        payload.put("time", LocalDateTime.now().toString());
        return payload;
    }

    public String buildResubmissionMessage(CompiledFlowNode node, FlowResubmitRequest request, Integer resubmissionCount) {
        StringBuilder builder = new StringBuilder("Flow resubmitted to node ")
                .append(node.getNodeId()).append(" by ").append(request.getActorId())
                .append(" (#").append(resubmissionCount).append(")");
        appendComment(builder, request.getComment());
        return builder.toString();
    }

    public Map<String, Object> buildWithdrawalDecisionPayload(FlowWithdrawRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("outcome", "Withdrawn");
        payload.put("actorId", request.getActorId());
        payload.put("comment", request.getComment());
        payload.put("time", LocalDateTime.now().toString());
        return payload;
    }

    public String buildWithdrawalMessage(FlowWithdrawRequest request) {
        StringBuilder builder = new StringBuilder("Flow withdrawn by ").append(request.getActorId());
        appendComment(builder, request.getComment());
        return builder.toString();
    }

    // --- private helpers ---

    private String buildCcMessage(CompiledFlowNode node, String actorId, String targetActorId, String comment) {
        StringBuilder builder = new StringBuilder("Approval CC sent at node ")
                .append(node.getNodeId()).append(" from ").append(actorId).append(" to ").append(targetActorId);
        appendComment(builder, comment);
        return builder.toString();
    }

    private void appendComment(StringBuilder builder, String comment) {
        if (StringUtils.hasText(comment)) {
            builder.append(": ").append(comment);
        }
    }
}

