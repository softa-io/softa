package io.softa.starter.flow.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxView;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;
import io.softa.starter.flow.service.FlowInboxService;
import io.softa.starter.flow.service.query.InboxViewAssembler;

/**
 * Default inbox aggregation service for flow tasks.
 */
@Service
public class FlowInboxServiceImpl implements FlowInboxService {

    private final FlowApprovalTaskQueryService approvalTaskService;

    public FlowInboxServiceImpl(FlowApprovalTaskQueryService approvalTaskService) {
        this.approvalTaskService = approvalTaskService;
    }

    @Override
    public FlowInboxView getInbox(String actorId,
                                         String flowCode,
                                         String instanceId,
                                         String nodeId,
                                         Boolean includeCompletedApprovals) {
        return getInbox(actorId, flowCode, instanceId, nodeId, includeCompletedApprovals, 1, 50);
    }

    @Override
    public FlowInboxView getInbox(String actorId,
                                  String flowCode,
                                  String instanceId,
                                  String nodeId,
                                  Boolean includeCompletedApprovals,
                                  Integer pageNumber,
                                  Integer pageSize) {
        Page<FlowApprovalTaskView> approvalPendingTasks =
                approvalTaskService.pagePendingTasks(actorId, flowCode, instanceId, nodeId, pageNumber, pageSize);
        Page<FlowApprovalTaskView> ccUnreadTasks =
                approvalTaskService.pageCcTasks(actorId, false, flowCode, instanceId, nodeId, pageNumber, pageSize);
        Page<FlowApprovalTaskView> ccReadTasks =
                approvalTaskService.pageCcTasks(actorId, true, flowCode, instanceId, nodeId, pageNumber, pageSize);
        List<FlowApprovalTaskView> approvalCompletedTasks = Boolean.TRUE.equals(includeCompletedApprovals)
                ? approvalTaskService.pageCompletedTasks(actorId, flowCode, instanceId, nodeId, pageNumber, pageSize).getRows()
                : List.of();

        return InboxViewAssembler.assemble(
                approvalPendingTasks.getRows(),
                ccUnreadTasks.getRows(),
                ccReadTasks.getRows(),
                approvalCompletedTasks,
                Boolean.TRUE.equals(includeCompletedApprovals));
    }
}
