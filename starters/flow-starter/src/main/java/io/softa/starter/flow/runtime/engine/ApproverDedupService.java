package io.softa.starter.flow.runtime.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Approver consolidation ("审批人去重"): when the same actor approves more than one node
 * of an instance, a later node can auto-approve on that actor's behalf per the flow's
 * {@link ApproverDedupStrategy}.
 *
 * <p>This is an approval <em>business rule</em> — it writes a real
 * {@link ApprovalActionType#AUTO_APPROVE} audit record — and is NOT action idempotency (which
 * dedups retried / redelivered physical requests). Applied at node activation, before the node
 * starts waiting.
 *
 * <p>Default (unconfigured) is {@link ApproverDedupStrategy#GLOBAL}, scoped to the current
 * resubmission cycle: approvals recorded before the last return/resubmit are discounted, because
 * the document may have changed since.
 */
@Component
public class ApproverDedupService {

    private final ApprovalLifecycleService lifecycleService;
    private final FlowAuditService auditService;
    private final ApprovalAuditReader auditReader;

    public ApproverDedupService(ApprovalLifecycleService lifecycleService, FlowAuditService auditService,
                                ApprovalAuditReader auditReader) {
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
        this.auditReader = auditReader;
    }

    /**
     * Auto-approve approvers who already approved a qualifying prior node, then report whether the
     * node is thereby complete (and should auto-advance instead of waiting for a human action).
     */
    public boolean applyAndCheckComplete(CompiledFlowDefinition definition, CompiledFlowNode node,
                                         FlowExecutionState state, PendingApproval pending) {
        ApproverDedupStrategy strategy = definition.getApproverDedup() == null
                ? ApproverDedupStrategy.GLOBAL
                : definition.getApproverDedup();
        if (strategy != ApproverDedupStrategy.NONE) {
            Set<String> priorApprovers = priorApprovers(state, node.getNodeId(), strategy);
            for (String approver : pending.getApprovers()) {
                if (priorApprovers.contains(approver) && !pending.getApprovedActors().contains(approver)) {
                    pending.getApprovedActors().add(approver);
                    auditService.appendApprovalAudit(state,
                            auditService.baseBuilder(definition, node, pending, state.getStatus(), state.getStatus())
                                    .action(ApprovalActionType.AUTO_APPROVE)
                                    .actorId(approver)
                                    .comment("Auto-approved (approver dedup: " + strategy + ")"));
                }
            }
        }
        return lifecycleService.canCompleteApproval(pending);
    }

    /**
     * Actors who approved a qualifying prior node in this instance, per the strategy, restricted to
     * the current resubmission cycle (entries after the last RETURN / RESUBMIT).
     */
    private Set<String> priorApprovers(FlowExecutionState state, String currentNodeId,
                                       ApproverDedupStrategy strategy) {
        List<ApprovalActionAuditEntry> history = auditReader.fullHistory(state);
        if (history.isEmpty()) {
            return Set.of();
        }
        // Resubmission-cycle guard: discount everything up to and including the last return/resubmit.
        int start = 0;
        for (int i = 0; i < history.size(); i++) {
            ApprovalActionType action = history.get(i).getAction();
            if (action == ApprovalActionType.RESUBMIT || action == ApprovalActionType.RETURN) {
                start = i + 1;
            }
        }
        List<ApprovalActionAuditEntry> priorApprovals = history.subList(start, history.size()).stream()
                .filter(e -> e.getAction() == ApprovalActionType.APPROVE || e.getAction() == ApprovalActionType.AUTO_APPROVE)
                .filter(e -> e.getNodeId() != null && !e.getNodeId().equals(currentNodeId))
                .toList();
        if (priorApprovals.isEmpty()) {
            return Set.of();
        }
        if (strategy == ApproverDedupStrategy.GLOBAL) {
            Set<String> actors = new HashSet<>();
            for (ApprovalActionAuditEntry e : priorApprovals) {
                if (e.getActorId() != null) {
                    actors.add(e.getActorId());
                }
            }
            return actors;
        }
        // CONTIGUOUS: actors who approved the immediately-preceding approval node.
        String prevNodeId = priorApprovals.getLast().getNodeId();
        Set<String> actors = new HashSet<>();
        for (ApprovalActionAuditEntry e : priorApprovals) {
            if (prevNodeId.equals(e.getNodeId()) && e.getActorId() != null) {
                actors.add(e.getActorId());
            }
        }
        return actors;
    }
}
