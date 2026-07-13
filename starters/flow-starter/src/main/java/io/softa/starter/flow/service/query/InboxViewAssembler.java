package io.softa.starter.flow.service.query;

import java.util.List;

import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxView;
import io.softa.starter.flow.enums.FlowApprovalTaskType;

/**
 * Aggregates task query buckets into one inbox view.
 */
public final class InboxViewAssembler {

    private InboxViewAssembler() {
    }

    public static FlowInboxView assemble(List<FlowApprovalTaskView> approvalPendingTasks,
                                                List<FlowApprovalTaskView> ccUnreadTasks,
                                                List<FlowApprovalTaskView> ccReadTasks,
                                                List<FlowApprovalTaskView> completedTasks,
                                                boolean includeCompletedApprovals) {
        return FlowInboxView.builder()
                .approvalPendingTasks(filterByTaskType(approvalPendingTasks, FlowApprovalTaskType.APPROVAL))
                .ccUnreadTasks(filterByTaskType(ccUnreadTasks, FlowApprovalTaskType.CC))
                .ccReadTasks(filterByTaskType(ccReadTasks, FlowApprovalTaskType.CC))
                .approvalCompletedTasks(includeCompletedApprovals
                        ? filterByTaskType(completedTasks, FlowApprovalTaskType.APPROVAL)
                        : List.of())
                .build();
    }

    private static List<FlowApprovalTaskView> filterByTaskType(List<FlowApprovalTaskView> tasks,
                                                                      FlowApprovalTaskType taskType) {
        return tasks.stream()
                .filter(task -> taskType.equals(task.getTaskType()))
                .toList();
    }
}

