package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
                         String flowId,
                         CompiledFlowNode node,
                         FlowTraceEventType eventType,
                         String message) {
        state.getTrace().add(FlowExecutionTraceEntry.builder()
                .flowId(flowId)
                .nodeId(node.getNodeId())
                .flowNodeType(node.getType())
                .eventType(eventType)
                .eventTime(LocalDateTime.now())
                .message(message)
                .build());
    }

    public void appendApprovalAudit(FlowExecutionState state, ApprovalActionAuditEntry entry) {
        if (state.getApprovalAuditDelta() == null) {
            state.setApprovalAuditDelta(new ArrayList<>());
        }
        // Sequence is assigned at flush time by the ledger (base + index), not here; the
        // event time is stamped here so no caller has to remember it.
        entry.setEventTime(LocalDateTime.now());
        state.getApprovalAuditDelta().add(entry);
    }

    /**
     * Record that an approval node nobody resolved to passed on its {@code AutoApprove}
     * empty-approver strategy. There is no actor; the entry makes the step and why it passed visible
     * in the approval history.
     */
    public void appendEmptyStepAutoApproval(FlowExecutionState state, CompiledFlowDefinition definition,
                                            CompiledFlowNode node) {
        appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                .flowId(definition.getFlowId())
                .flowRevision(definition.getRevision())
                .nodeId(node.getNodeId())
                .nodeLabel(node.getLabel())
                .statusBefore(state.getStatus())
                .statusAfter(state.getStatus())
                .action(ApprovalActionType.AUTO_APPROVE)
                .comment("Auto-approved (no approver)")
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
        addTrace(state, definition.getFlowId(), node, FlowTraceEventType.APPROVAL_CCED,
                buildCcMessage(node, actorId, targetActorId, comment));
        appendApprovalAudit(state, baseEntry(definition, node, pendingApproval, statusBefore, state.getStatus()).toBuilder()
                .action(ApprovalActionType.CC)
                .actorId(actorId)
                .targetActorId(targetActorId)
                .comment(comment)
                .build());
    }

    /**
     * Record that a CC rule CC'd a recipient: a {@code CC} entry like a manual CC's, with no actor — no person
     * sent it — on the node the rule fired at.
     */
    public void appendAutoCcAudit(FlowExecutionState state,
                                  CompiledFlowNode node,
                                  String targetActorId,
                                  String comment) {
        appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.CC)
                .flowId(state.getFlowId())
                .flowRevision(state.getFlowRevision())
                .nodeId(node.getNodeId())
                .nodeLabel(node.getLabel())
                .targetActorId(targetActorId)
                .comment(comment)
                .statusBefore(state.getStatus())
                .statusAfter(state.getStatus())
                .build());
    }

    public Optional<ApprovalActionAuditEntry> findCcAudit(FlowExecutionState state,
                                                          String nodeId,
                                                          String actorId) {
        var history = auditReader.fullHistory(state);
        for (int i = history.size() - 1; i >= 0; i--) {
            var entry = history.get(i);
            if (ApprovalActionType.CC.equals(entry.getAction())
                    && Objects.equals(nodeId, entry.getNodeId())
                    && Objects.equals(actorId, entry.getTargetActorId())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public boolean hasReadCcAudit(FlowExecutionState state,
                                  String nodeId,
                                  String actorId) {
        return auditReader.fullHistory(state).stream()
                .anyMatch(entry -> ApprovalActionType.READ.equals(entry.getAction())
                        && Objects.equals(nodeId, entry.getNodeId())
                        && Objects.equals(actorId, entry.getActorId()));
    }

    /**
     * Pre-fill an audit entry with the common fields of the approval context. Callers add the
     * action-specific fields through {@code toBuilder()} and hand the result to
     * {@link #appendApprovalAudit(FlowExecutionState, ApprovalActionAuditEntry)}.
     * <p>
     * Returns the built entry rather than its Lombok builder on purpose: the builder is a
     * generated type that javadoc never sees, so naming it in a signature both breaks the
     * documentation build and leaks a Lombok artefact into the public API.
     */
    public ApprovalActionAuditEntry baseEntry(
            CompiledFlowDefinition definition,
            CompiledFlowNode node,
            PendingApproval pendingApproval,
            FlowExecutionStatus statusBefore,
            FlowExecutionStatus statusAfter) {
        return ApprovalActionAuditEntry.builder()
                .flowId(definition.getFlowId())
                .flowRevision(definition.getRevision())
                .nodeId(node.getNodeId())
                .nodeLabel(node.getLabel())
                .statusBefore(statusBefore)
                .statusAfter(statusAfter)
                .approvedActors(List.copyOf(pendingApproval.getApprovedActors()))
                .requiredApprovalCount(pendingApproval.getRequiredApprovalCount())
                .build();
    }

    // --- message builders ---

    public String buildPartialApprovalMessage(CompiledFlowNode node, PendingApproval pendingApproval, String actorId) {
        return "Approval recorded for node " + node.getNodeId() + " by " + actorId + " ("
                + pendingApproval.getApprovedActors().size() + "/" + pendingApproval.getRequiredApprovalCount() + ")";
    }

    public String buildTransferMessage(CompiledFlowNode node, FlowTransferRequest request) {
        StringBuilder builder = new StringBuilder("Approval transferred at node ")
                .append(node.getNodeId()).append(" from ").append(request.getActorId())
                .append(" to ").append(request.getTargetActorId());
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

    public String buildRejectionMessage(CompiledFlowNode node, FlowRejectRequest request) {
        StringBuilder builder = new StringBuilder("Approval rejected at node ").append(node.getNodeId());
        if (StringUtils.hasText(request.getActorId())) {
            builder.append(" by ").append(request.getActorId());
        }
        appendComment(builder, request.getComment());
        return builder.toString();
    }

    public String buildReturnMessage(CompiledFlowNode node, FlowReturnRequest request, String initiatorId) {
        StringBuilder builder = new StringBuilder("Approval returned at node ")
                .append(node.getNodeId()).append(" by ").append(request.getActorId())
                .append(" to initiator ").append(initiatorId);
        appendComment(builder, request.getComment());
        return builder.toString();
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
