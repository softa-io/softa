package io.softa.starter.flow.service.impl;

import java.util.List;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.entity.*;

/**
 * Deletes flow instances together with everything recorded under their instance id: trace rows,
 * approval tasks and records: the purge of expired Automation runs.
 */
@Component
public class FlowInstancePurger {

    private final FlowInstanceServiceImpl instanceService;
    private final FlowExecutionTraceServiceImpl traceService;
    private final FlowApprovalTaskServiceImpl approvalTaskService;
    private final FlowApprovalActionServiceImpl approvalActionService;

    public FlowInstancePurger(FlowInstanceServiceImpl instanceService,
                              FlowExecutionTraceServiceImpl traceService,
                              FlowApprovalTaskServiceImpl approvalTaskService,
                              FlowApprovalActionServiceImpl approvalActionService) {
        this.instanceService = instanceService;
        this.traceService = traceService;
        this.approvalTaskService = approvalTaskService;
        this.approvalActionService = approvalActionService;
    }

    /** Delete {@code instances} (each carrying its row id and instance id) and their records. */
    public void delete(List<FlowInstance> instances) {
        if (instances.isEmpty()) {
            return;
        }
        List<String> instanceIds = instances.stream().map(FlowInstance::getInstanceId).toList();
        traceService.deleteByFilters(new Filters().in(FlowExecutionTrace::getInstanceId, instanceIds));
        approvalTaskService.deleteByFilters(new Filters().in(FlowApprovalTask::getInstanceId, instanceIds));
        approvalActionService.deleteByFilters(new Filters().in(FlowApprovalAction::getInstanceId, instanceIds));
        instanceService.deleteByIds(instances.stream().map(FlowInstance::getId).toList());
    }
}
