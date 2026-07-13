package io.softa.starter.flow.service.support.view;

import java.util.List;

import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.entity.FlowApprovalTask;

/**
 * Maps approval task entities into API views.
 */
public final class FlowApprovalTaskViewMapper {

    private FlowApprovalTaskViewMapper() {
    }

    public static FlowApprovalTaskView toView(FlowApprovalTask task) {
        if (task == null) {
            return null;
        }
        return FlowApprovalTaskView.builder()
                .id(task.getId())
                .instanceId(task.getInstanceId())
                .flowCode(task.getFlowCode())
                .flowRevision(task.getFlowRevision())
                .nodeId(task.getNodeId())
                .nodeLabel(task.getNodeLabel())
                .cycleNumber(task.getCycleNumber())
                .actorId(task.getActorId())
                .status(task.getStatus())
                .taskType(task.getTaskType())
                .action(task.getAction())
                .comment(task.getComment())
                .dynamicApprovers(task.getDynamicApprovers())
                .approvalMode(task.getApprovalMode())
                .requiredApprovalCount(task.getRequiredApprovalCount())
                .totalApproverCount(task.getTotalApproverCount())
                .rejectMode(task.getRejectMode())
                .requiredRejectCount(task.getRequiredRejectCount())
                .candidateActors(task.getCandidateActors())
                .approvedActors(task.getApprovedActors())
                .rejectedActors(task.getRejectedActors())
                .startTime(task.getStartTime())
                .endTime(task.getEndTime())
                .closedByActorId(task.getClosedByActorId())
                .blocked(task.getBlocked())
                .blockedByActorId(task.getBlockedByActorId())
                .build();
    }

    public static List<FlowApprovalTaskView> toViews(List<FlowApprovalTask> tasks) {
        return tasks.stream().map(FlowApprovalTaskViewMapper::toView).toList();
    }
}

