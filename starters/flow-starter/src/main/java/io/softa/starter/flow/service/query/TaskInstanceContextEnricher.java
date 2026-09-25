package io.softa.starter.flow.service.query;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * Batch-enriches approval task views with their owning instance's business
 * context (title / bound model / row id / execution status) — ONE `IN` query
 * per page instead of an N+1 the frontend cannot even perform itself (the
 * runtime instance read is participant-scoped).
 */
@Component
public class TaskInstanceContextEnricher {

    private final FlowInstanceService instanceService;

    public TaskInstanceContextEnricher(FlowInstanceService instanceService) {
        this.instanceService = instanceService;
    }

    /**
     * Row scope is bypassed here: the batch lookup runs in the approver's request context, and an
     * approver is not the initiator of the instances behind their tasks — a scoped read returns
     * zero rows and every view stays title-less, which is exactly the "inbox shows no document
     * name" symptom. The task views being enriched are already actor-scoped rows the caller is
     * entitled to; their owning instance's title/model/row-id/status is context of those rows,
     * not a widening of them.
     */
    @SkipPermissionCheck
    public void enrich(List<FlowApprovalTaskView> views) {
        if (views == null || views.isEmpty()) {
            return;
        }
        List<String> instanceIds = views.stream()
                .map(FlowApprovalTaskView::getInstanceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (instanceIds.isEmpty()) {
            return;
        }
        Map<String, FlowInstance> byInstanceId = instanceService.findByInstanceIds(instanceIds).stream()
                .collect(Collectors.toMap(FlowInstance::getInstanceId, Function.identity(), (a, b) -> a));
        for (FlowApprovalTaskView view : views) {
            FlowInstance instance = byInstanceId.get(view.getInstanceId());
            if (instance == null) {
                // Retention cleanup may have removed the instance — the task row stands alone.
                continue;
            }
            view.setInstanceTitle(instance.getTitle());
            view.setModelName(instance.getModelName());
            view.setRowId(instance.getRowId());
            view.setInstanceStatus(instance.getStatus());
        }
    }
}
