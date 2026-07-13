package io.softa.starter.flow.service.support;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Builds per-actor approval task projections from runtime pending approvals.
 */
@Component
public class FlowApprovalTaskProjector {

    private final ApprovalAuditReader auditReader;

    public FlowApprovalTaskProjector(ApprovalAuditReader auditReader) {
        this.auditReader = auditReader;
    }

    public List<FlowApprovalTask> project(FlowExecutionState state) {
        if (state == null) {
            return List.of();
        }
        // Fetched once per projection: ledger rows + the attempt's unflushed tail.
        List<ApprovalActionAuditEntry> history = auditReader.fullHistory(state);
        List<FlowApprovalTask> result = new ArrayList<>();
        if (state.getPendingApprovals() != null) {
            for (PendingApproval pendingApproval : state.getPendingApprovals()) {
                Set<String> approvers = new LinkedHashSet<>(pendingApproval.getApprovers() == null ? List.of() : pendingApproval.getApprovers());
                for (String actorId : approvers) {
                    ApprovalActionAuditEntry latestAudit = findLatestAudit(
                            history,
                            pendingApproval.getNodeId(),
                            pendingApproval.getCycleNumber(),
                            actorId);
                    FlowApprovalTaskStatus status = resolveStatus(pendingApproval, actorId);
                    result.add(buildTask(state, pendingApproval, actorId, status, latestAudit));
                }
            }
        }
        result.addAll(buildCcTasks(state, history));
        return result;
    }

    private List<FlowApprovalTask> buildCcTasks(FlowExecutionState state, List<ApprovalActionAuditEntry> history) {
        if (history.isEmpty()) {
            return List.of();
        }
        List<FlowApprovalTask> result = new ArrayList<>();
        for (ApprovalActionAuditEntry entry : history) {
            if (!ApprovalActionType.CC.equals(entry.getAction()) || entry.getTargetActorId() == null) {
                continue;
            }
            FlowApprovalTask task = new FlowApprovalTask();
            task.setInstanceId(state.getInstanceId());
            task.setFlowCode(entry.getFlowCode() == null ? state.getFlowCode() : entry.getFlowCode());
            task.setFlowRevision(entry.getFlowRevision() == null ? state.getFlowRevision() : entry.getFlowRevision());
            task.setNodeId(entry.getNodeId());
            task.setNodeLabel(entry.getNodeLabel());
            task.setCycleNumber(entry.getCycleNumber());
            task.setActorId(entry.getTargetActorId());
            task.setStatus(FlowApprovalTaskStatus.PENDING);
            task.setTaskType(FlowApprovalTaskType.CC);
            task.setAction(entry.getAction());
            task.setComment(entry.getComment());
            task.setDynamicApprovers(Boolean.TRUE.equals(entry.getDynamicApprovers()));
            task.setApprovalMode(entry.getApprovalMode());
            task.setRequiredApprovalCount(entry.getRequiredApprovalCount());
            task.setTotalApproverCount(entry.getTotalApproverCount());
            task.setRejectMode(entry.getRejectMode());
            task.setRequiredRejectCount(entry.getRequiredRejectCount());
            task.setCandidateActors(List.of(entry.getTargetActorId()));
            task.setApprovedActors(entry.getApprovedActors() == null ? List.of() : List.copyOf(entry.getApprovedActors()));
            task.setRejectedActors(entry.getRejectedActors() == null ? List.of() : List.copyOf(entry.getRejectedActors()));
            task.setBlocked(Boolean.FALSE);
            task.setBlockedByActorId(null);
            task.setStartTime(resolveEventTime(entry));
            task.setEndTime(resolveEventTime(entry));
            task.setClosedByActorId(entry.getActorId());
            result.add(task);
        }
        return result;
    }


    private FlowApprovalTask buildTask(FlowExecutionState state,
                                              PendingApproval pendingApproval,
                                              String actorId,
                                              FlowApprovalTaskStatus status,
                                              ApprovalActionAuditEntry latestAudit) {
        FlowApprovalTask task = new FlowApprovalTask();
        task.setInstanceId(state.getInstanceId());
        task.setFlowCode(pendingApproval.getFlowCode());
        task.setFlowRevision(pendingApproval.getFlowRevision());
        task.setNodeId(pendingApproval.getNodeId());
        task.setNodeLabel(pendingApproval.getNodeLabel());
        task.setCycleNumber(pendingApproval.getCycleNumber());
        task.setActorId(actorId);
        task.setStatus(status);
        task.setTaskType(FlowApprovalTaskType.APPROVAL);
        task.setDynamicApprovers(Boolean.TRUE.equals(pendingApproval.getDynamicApprovers()));
        task.setApprovalMode(pendingApproval.getApprovalMode());
        task.setRequiredApprovalCount(pendingApproval.getRequiredApprovalCount());
        task.setTotalApproverCount(pendingApproval.getTotalApproverCount());
        task.setRejectMode(pendingApproval.getRejectMode());
        task.setRequiredRejectCount(pendingApproval.getRequiredRejectCount());
        task.setCandidateActors(List.copyOf(new LinkedHashSet<>(pendingApproval.getApprovers() == null ? List.of() : pendingApproval.getApprovers())));
        task.setApprovedActors(List.copyOf(pendingApproval.getApprovedActors() == null ? List.of() : pendingApproval.getApprovedActors()));
        task.setRejectedActors(List.copyOf(pendingApproval.getRejectedActors() == null ? List.of() : pendingApproval.getRejectedActors()));
        task.setBlocked(isBlocked(pendingApproval, actorId));
        task.setBlockedByActorId(isBlocked(pendingApproval, actorId) ? pendingApproval.getPrerequisiteActorId() : null);
        task.setStartTime(resolveEventTime(latestAudit));
        if (latestAudit != null) {
            task.setAction(latestAudit.getAction());
            task.setComment(latestAudit.getComment());
            task.setClosedByActorId(latestAudit.getActorId());
        }
        if (!FlowApprovalTaskStatus.PENDING.equals(status)) {
            task.setEndTime(resolveEventTime(latestAudit));
        }
        return task;
    }

    private FlowApprovalTaskStatus resolveStatus(PendingApproval pendingApproval, String actorId) {
        if (pendingApproval.getApprovedActors() != null && pendingApproval.getApprovedActors().contains(actorId)) {
            return FlowApprovalTaskStatus.APPROVED;
        }
        if (pendingApproval.getRejectedActors() != null && pendingApproval.getRejectedActors().contains(actorId)) {
            return FlowApprovalTaskStatus.REJECTED;
        }
        return FlowApprovalTaskStatus.PENDING;
    }

    private boolean isBlocked(PendingApproval pendingApproval, String actorId) {
        return Objects.equals(actorId, pendingApproval.getBlockedActorId())
                && !isPrerequisiteResolved(pendingApproval);
    }

    private boolean isPrerequisiteResolved(PendingApproval pendingApproval) {
        return pendingApproval.getApprovedActors() != null
                && pendingApproval.getApprovedActors().contains(pendingApproval.getPrerequisiteActorId());
    }

    private static ApprovalActionAuditEntry findLatestAudit(List<ApprovalActionAuditEntry> history,
                                                             String nodeId,
                                                             Integer cycleNumber,
                                                             String actorId) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ApprovalActionAuditEntry entry = history.get(i);
            if (Objects.equals(nodeId, entry.getNodeId())
                    && Objects.equals(cycleNumber, entry.getCycleNumber())
                    && Objects.equals(actorId, entry.getActorId())
                    && (ApprovalActionType.APPROVE.equals(entry.getAction()) || ApprovalActionType.REJECT.equals(entry.getAction()))) {
                return entry;
            }
        }
        return null;
    }

    private LocalDateTime resolveEventTime(ApprovalActionAuditEntry audit) {
        return audit == null || audit.getEventTime() == null
                ? LocalDateTime.now()
                : audit.getEventTime();
    }
}
