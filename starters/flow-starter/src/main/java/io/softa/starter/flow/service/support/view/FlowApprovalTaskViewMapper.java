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
                .flowId(task.getFlowId())
                .flowName(task.getFlowName())
                .flowRevision(task.getFlowRevision())
                .nodeId(task.getNodeId())
                .nodeLabel(task.getNodeLabel())
                .actorId(task.getActorId())
                .status(task.getStatus())
                .taskType(task.getTaskType())
                .action(task.getAction())
                .comment(task.getComment())
                .requiredApprovalCount(task.getRequiredApprovalCount())
                .approvedActors(task.getApprovedActors())
                .startTime(task.getStartTime())
                .endTime(task.getEndTime())
                .closedByActorId(task.getClosedByActorId())
                .dueTime(task.getDueTime())
                // The instance's fields as the task stored them; the enricher sets them again from the instance.
                .instanceTitle(task.getInstanceTitle())
                .modelName(task.getModelName())
                .rowId(task.getRowId())
                .submittedTime(task.getSubmittedTime())
                .effectiveDate(task.getEffectiveDate())
                .recordCode(task.getRecordCode())
                .build();
    }

    public static List<FlowApprovalTaskView> toViews(List<FlowApprovalTask> tasks) {
        return tasks.stream().map(FlowApprovalTaskViewMapper::toView).toList();
    }
}

