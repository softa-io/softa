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
                    ApprovalActionAuditEntry latestAudit = findLatestAudit(history, pendingApproval.getNodeId(), actorId);
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
            task.setFlowId(entry.getFlowId() == null ? state.getFlowId() : entry.getFlowId());
            task.setFlowRevision(entry.getFlowRevision() == null ? state.getFlowRevision() : entry.getFlowRevision());
            task.setNodeId(entry.getNodeId());
            task.setNodeLabel(entry.getNodeLabel());
            task.setActorId(entry.getTargetActorId());
            // Unread until its recipient reads it: a Read of theirs at the CC's node.
            ApprovalActionAuditEntry read = readOf(history, entry);
            task.setStatus(read == null ? FlowApprovalTaskStatus.PENDING : FlowApprovalTaskStatus.READ);
            task.setTaskType(FlowApprovalTaskType.CC);
            task.setAction(entry.getAction());
            task.setComment(entry.getComment());
            task.setRequiredApprovalCount(entry.getRequiredApprovalCount());
            task.setApprovedActors(entry.getApprovedActors() == null ? List.of() : List.copyOf(entry.getApprovedActors()));
            task.setStartTime(resolveEventTime(entry));
            task.setEndTime(resolveEventTime(read == null ? entry : read));
            task.setClosedByActorId(entry.getActorId());
            result.add(task);
        }
        return result;
    }

    /** The recipient's reading of a CC — a Read by them at its node — or null while it is unread. */
    private static ApprovalActionAuditEntry readOf(List<ApprovalActionAuditEntry> history, ApprovalActionAuditEntry cc) {
        for (ApprovalActionAuditEntry entry : history) {
            if (ApprovalActionType.READ.equals(entry.getAction())
                    && Objects.equals(cc.getNodeId(), entry.getNodeId())
                    && Objects.equals(cc.getTargetActorId(), entry.getActorId())) {
                return entry;
            }
        }
        return null;
    }


    private FlowApprovalTask buildTask(FlowExecutionState state,
                                              PendingApproval pendingApproval,
                                              String actorId,
                                              FlowApprovalTaskStatus status,
                                              ApprovalActionAuditEntry latestAudit) {
        FlowApprovalTask task = new FlowApprovalTask();
        task.setInstanceId(state.getInstanceId());
        task.setFlowId(pendingApproval.getFlowId());
        task.setFlowRevision(pendingApproval.getFlowRevision());
        task.setNodeId(pendingApproval.getNodeId());
        task.setNodeLabel(pendingApproval.getNodeLabel());
        task.setActorId(actorId);
        task.setStatus(status);
        task.setTaskType(FlowApprovalTaskType.APPROVAL);
        task.setRequiredApprovalCount(pendingApproval.getRequiredApprovalCount());
        task.setApprovedActors(List.copyOf(pendingApproval.getApprovedActors() == null ? List.of() : pendingApproval.getApprovedActors()));
        // The step's deadline, when it has a timeout: what the inbox says is due or overdue.
        task.setDueTime(pendingApproval.getDueTime());
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
        return FlowApprovalTaskStatus.PENDING;
    }

    private static ApprovalActionAuditEntry findLatestAudit(List<ApprovalActionAuditEntry> history,
                                                             String nodeId,
                                                             String actorId) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ApprovalActionAuditEntry entry = history.get(i);
            if (Objects.equals(nodeId, entry.getNodeId())
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
