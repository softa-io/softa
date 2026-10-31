package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.dto.FlowBatchActionResult;
import io.softa.starter.flow.dto.FlowBatchActionResult.Item;
import io.softa.starter.flow.dto.FlowBatchActionResult.Outcome;
import io.softa.starter.flow.dto.FlowReassignPreview;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.runtime.api.FlowReassignRequest;
import io.softa.starter.flow.runtime.api.FlowTerminateRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.service.FlowAdministrationService;
import io.softa.starter.flow.service.FlowDelegationService;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;

/**
 * {@link FlowAdministrationService}: deliberately not transactional — each instance is its own engine call and so
 * its own transaction, and one refused instance changes nothing for the others. The tasks to reassign are read past
 * the administrator's permissions and kept only on the instances those permissions read.
 */
@Slf4j
@Service
public class FlowAdministrationServiceImpl implements FlowAdministrationService {

    private static final String TASK_MODEL = FlowApprovalTask.class.getSimpleName();

    private static final String INSTANCE_ID = LambdaUtils.getAttributeName(FlowApprovalTask::getInstanceId);

    private static final String NODE_ID = LambdaUtils.getAttributeName(FlowApprovalTask::getNodeId);

    private final FlowRuntimeEngine runtimeEngine;

    private final FlowInstanceAccessGuard accessGuard;

    private final FlowDelegationService delegationService;

    private final ModelService<Long> modelService;

    @SuppressWarnings("unchecked")
    public FlowAdministrationServiceImpl(FlowRuntimeEngine runtimeEngine, FlowInstanceAccessGuard accessGuard,
                                         FlowDelegationService delegationService, ModelService<?> modelService) {
        this.runtimeEngine = runtimeEngine;
        this.accessGuard = accessGuard;
        this.delegationService = delegationService;
        this.modelService = (ModelService<Long>) modelService;
    }

    @Override
    public FlowBatchActionResult terminate(List<String> instanceIds, String reason, String operatorId) {
        List<String> requested = List.copyOf(new LinkedHashSet<>(instanceIds));
        Set<String> administrable = accessGuard.administrableInstanceIds(requested);
        List<Item> items = new ArrayList<>(requested.size());
        for (String instanceId : requested) {
            if (!administrable.contains(instanceId)) {
                items.add(new Item(instanceId, null, Outcome.FAILED, "You may not administer this instance"));
                continue;
            }
            try {
                FlowTerminateRequest request = new FlowTerminateRequest();
                request.setInstanceId(instanceId);
                request.setActorId(operatorId);
                request.setComment(reason);
                runtimeEngine.terminate(request);
                items.add(new Item(instanceId, null, Outcome.SUCCEEDED, null));
            } catch (RuntimeException e) {
                log.info("Terminate refused on instance {}: {}", instanceId, e.getMessage());
                items.add(new Item(instanceId, null, Outcome.FAILED, e.getMessage()));
            }
        }
        return FlowBatchActionResult.of(items);
    }

    @Override
    public FlowReassignPreview previewReassign(String fromActorId, String toActorId) {
        requireDifferent(fromActorId, toActorId);
        List<Map<String, Object>> tasks = reassignableTasks(fromActorId);
        int instances = (int) tasks.stream().map(task -> task.get(INSTANCE_ID)).distinct().count();
        return new FlowReassignPreview(tasks.size(), instances);
    }

    @Override
    public FlowBatchActionResult reassign(String fromActorId, String toActorId, String reason, String operatorId) {
        requireDifferent(fromActorId, toActorId);
        if (!StringUtils.hasText(reason)) {
            throw new FlowActionValidationException("A reason is required to reassign approval tasks");
        }
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> task : reassignableTasks(fromActorId)) {
            String instanceId = Objects.toString(task.get(INSTANCE_ID), null);
            String nodeId = Objects.toString(task.get(NODE_ID), null);
            try {
                FlowReassignRequest request = new FlowReassignRequest();
                request.setInstanceId(instanceId);
                request.setNodeId(nodeId);
                request.setActorId(fromActorId);
                request.setTargetActorId(toActorId);
                request.setOperatorId(operatorId);
                request.setComment(reason);
                runtimeEngine.reassign(request);
                items.add(new Item(instanceId, nodeId, Outcome.SUCCEEDED, null));
            } catch (RuntimeException e) {
                log.info("Reassign refused on instance {} step {}: {}", instanceId, nodeId, e.getMessage());
                items.add(new Item(instanceId, nodeId, Outcome.FAILED, e.getMessage()));
            }
        }
        return FlowBatchActionResult.of(items);
    }

    /**
     * The approver's pending approval tasks (a CC is not reassigned), one per step, on the instances the
     * administrator may administer.
     */
    private List<Map<String, Object>> reassignableTasks(String fromActorId) {
        FlexQuery query = new FlexQuery(new Filters()
                .eq(FlowApprovalTask::getActorId, fromActorId)
                .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.APPROVAL)
                .eq(FlowApprovalTask::getStatus, FlowApprovalTaskStatus.PENDING),
                Orders.ofAsc(FlowApprovalTask::getStartTime));
        query.setFields(List.of(INSTANCE_ID, NODE_ID));
        List<Map<String, Object>> tasks = ContextUtils.withoutPermissionChecks(() -> modelService.searchList(TASK_MODEL, query));
        Set<String> administrable = accessGuard.administrableInstanceIds(tasks.stream()
                .map(task -> Objects.toString(task.get(INSTANCE_ID), null))
                .filter(Objects::nonNull)
                .toList());
        Set<String> seen = new LinkedHashSet<>();
        return tasks.stream()
                .filter(task -> administrable.contains(Objects.toString(task.get(INSTANCE_ID), null)))
                .filter(task -> seen.add(task.get(INSTANCE_ID) + "/" + task.get(NODE_ID)))
                .toList();
    }

    private static void requireDifferent(String fromActorId, String toActorId) {
        if (!StringUtils.hasText(fromActorId) || !StringUtils.hasText(toActorId)) {
            throw new FlowActionValidationException("Both the original and the new approver are required");
        }
        if (fromActorId.equals(toActorId)) {
            throw new FlowActionValidationException("The new approver must be someone else");
        }
    }

    @Override
    public FlowDelegation delegateOnBehalf(FlowDelegation delegation) {
        // The rule is checked as any other is, by the delegation service. Authorized by the endpoint's own
        // permission: the administrator need not hold the delegation model.
        return ContextUtils.withoutPermissionChecks(() -> delegationService.createDelegation(delegation));
    }

    @Override
    public List<FlowDelegation> listDelegations() {
        return ContextUtils.withoutPermissionChecks(delegationService::listAll);
    }

    @Override
    public void cancelDelegation(Long delegationId) {
        ContextUtils.withoutPermissionChecks(() -> delegationService.deactivate(delegationId));
    }
}
