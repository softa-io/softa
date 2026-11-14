package io.softa.starter.flow.service.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;

/**
 * Where waiting instances stand now: the steps each waits on, in the order their tasks opened, each with the approvers
 * still to act — what an instance list shows as "now at a step, waiting on whom". ONE {@code IN} query on the task
 * projection for a page of instances, read past the caller's data permissions: the instances were already authorized.
 * The approvers come unnamed; the caller names them with the rest of its page.
 */
@Component
public class CurrentStepsReader {

    private static final String TASK_MODEL = FlowApprovalTask.class.getSimpleName();

    private static final List<String> STEP_FIELDS = List.of(
            LambdaUtils.getAttributeName(FlowApprovalTask::getInstanceId),
            LambdaUtils.getAttributeName(FlowApprovalTask::getNodeId),
            LambdaUtils.getAttributeName(FlowApprovalTask::getNodeLabel),
            LambdaUtils.getAttributeName(FlowApprovalTask::getActorId));

    private final ModelService<Long> modelService;

    @SuppressWarnings("unchecked")
    public CurrentStepsReader(ModelService<?> modelService) {
        this.modelService = (ModelService<Long>) modelService;
    }

    /** The steps each of {@code waitingInstanceIds} waits on, by instance id; an instance waiting on none is left out. */
    public Map<String, List<FlowRecordApprovalsView.PendingStep>> of(Collection<String> waitingInstanceIds) {
        if (waitingInstanceIds == null || waitingInstanceIds.isEmpty()) {
            return Map.of();
        }
        FlexQuery query = new FlexQuery(new Filters()
                .in(FlowApprovalTask::getInstanceId, List.copyOf(waitingInstanceIds))
                .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.APPROVAL)
                .eq(FlowApprovalTask::getStatus, FlowApprovalTaskStatus.PENDING),
                Orders.ofAsc(FlowApprovalTask::getStartTime).addAsc(FlowApprovalTask::getId));
        query.setFields(STEP_FIELDS);
        List<Map<String, Object>> tasks = ContextUtils.withoutPermissionChecks(
                () -> modelService.searchList(TASK_MODEL, query));
        Map<String, Map<String, FlowRecordApprovalsView.PendingStep>> byInstance = new LinkedHashMap<>();
        for (Map<String, Object> task : tasks) {
            String instanceId = text(task.get(STEP_FIELDS.get(0)));
            String nodeId = text(task.get(STEP_FIELDS.get(1)));
            String nodeLabel = text(task.get(STEP_FIELDS.get(2)));
            String actorId = text(task.get(STEP_FIELDS.get(3)));
            if (instanceId == null || actorId == null) {
                continue;
            }
            FlowRecordApprovalsView.PendingStep step = byInstance
                    .computeIfAbsent(instanceId, id -> new LinkedHashMap<>())
                    .computeIfAbsent(Objects.toString(nodeId, ""), key -> new FlowRecordApprovalsView.PendingStep(
                            nodeId, nodeLabel, new ArrayList<>()));
            boolean listed = step.getApprovers().stream().anyMatch(actor -> actorId.equals(actor.getId()));
            if (!listed) {
                step.getApprovers().add(new FlowRecordApprovalsView.Actor(actorId, null));
            }
        }
        Map<String, List<FlowRecordApprovalsView.PendingStep>> result = new LinkedHashMap<>();
        byInstance.forEach((instanceId, steps) -> result.put(instanceId, new ArrayList<>(steps.values())));
        return result;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
