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
    private final FlowApprovalRecordServiceImpl approvalRecordService;

    public FlowInstancePurger(FlowInstanceServiceImpl instanceService,
                              FlowExecutionTraceServiceImpl traceService,
                              FlowApprovalTaskServiceImpl approvalTaskService,
                              FlowApprovalRecordServiceImpl approvalRecordService) {
        this.instanceService = instanceService;
        this.traceService = traceService;
        this.approvalTaskService = approvalTaskService;
        this.approvalRecordService = approvalRecordService;
    }

    /** Delete {@code instances} (each carrying its row id and instance id) and their records. */
    public void delete(List<FlowInstance> instances) {
        if (instances.isEmpty()) {
            return;
        }
        List<String> instanceIds = instances.stream().map(FlowInstance::getInstanceId).toList();
        traceService.deleteByFilters(new Filters().in(FlowExecutionTrace::getInstanceId, instanceIds));
        approvalTaskService.deleteByFilters(new Filters().in(FlowApprovalTask::getInstanceId, instanceIds));
        approvalRecordService.deleteByFilters(new Filters().in(FlowApprovalRecord::getInstanceId, instanceIds));
        instanceService.deleteByIds(instances.stream().map(FlowInstance::getId).toList());
    }
}
