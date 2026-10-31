package io.softa.starter.flow.service.query;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.dto.FlowInstanceSummaryView;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.ApprovalModel;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.ApprovalStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.RecordEffectStatus;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;
import io.softa.starter.flow.runtime.engine.UrgeThrottle;
import io.softa.starter.flow.runtime.engine.WithdrawPolicyGuard;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;
import io.softa.starter.flow.service.support.FlowUserNames;

/**
 * Turns a page of instance summary rows into the rows an instance list shows: the steps each instance
 * waits on now with the approvers still to act — ONE {@code IN} query on the task projection per page —
 * whether a returned approval still waits for its record to be edited, and every user, employee and
 * department named. The tasks and names are read past the caller's data permissions: the instances were
 * already authorized, as the caller's own or by the monitoring console's grant.
 *
 * <p>On the requester's own approvals waiting on their approvers it also says what the requester may do
 * now — withdraw (the flow's withdraw policy), urge (the flow's urge settings) — from ONE state read per page; and
 * on every record's approval whether the record still exists, from one id read per model on the page.
 */
@Component
public class FlowInstanceSummaryComposer {

    private static final String TASK_MODEL = FlowApprovalTask.class.getSimpleName();

    private static final List<String> STEP_FIELDS = List.of(
            LambdaUtils.getAttributeName(FlowApprovalTask::getInstanceId),
            LambdaUtils.getAttributeName(FlowApprovalTask::getNodeId),
            LambdaUtils.getAttributeName(FlowApprovalTask::getNodeLabel),
            LambdaUtils.getAttributeName(FlowApprovalTask::getActorId));

    private final ModelService<Long> modelService;

    private final FlowUserNames names;

    private final FlowInstanceStore instanceStore;

    private final WithdrawPolicyGuard withdrawPolicyGuard;

    private final UrgeThrottle urgeThrottle;

    private final ModelApprovalHandlerRegistry handlerRegistry;

    @SuppressWarnings("unchecked")
    public FlowInstanceSummaryComposer(ModelService<?> modelService, FlowUserNames names,
                                       FlowInstanceStore instanceStore, WithdrawPolicyGuard withdrawPolicyGuard,
                                       UrgeThrottle urgeThrottle, ModelApprovalHandlerRegistry handlerRegistry) {
        this.modelService = (ModelService<Long>) modelService;
        this.names = names;
        this.instanceStore = instanceStore;
        this.withdrawPolicyGuard = withdrawPolicyGuard;
        this.urgeThrottle = urgeThrottle;
        this.handlerRegistry = handlerRegistry;
    }

    /** The rows of a page, with what {@code requesterId} may do to their own waiting approvals. */
    public Page<FlowInstanceSummaryView> summarize(Page<FlowInstance> page, String requesterId) {
        Page<FlowInstanceSummaryView> target = Page.of(page.getPageNumber(), page.getPageSize());
        target.setTotalCount(page.getTotalCount());
        target.setRows(summarize(page.getRows(), requesterId));
        return target;
    }

    /**
     * The rows of a page for the monitoring console, whose caller administers them: every waiting approval says
     * whether an administrator may urge it now, and if not, when — the flow's interval applies, its initiator-only
     * setting does not.
     */
    public Page<FlowInstanceSummaryView> summarizeForAdministrator(Page<FlowInstance> page, String requesterId) {
        Page<FlowInstanceSummaryView> target = summarize(page, requesterId);
        Map<String, FlowInstanceSummaryView> waiting = target.getRows().stream()
                .filter(FlowInstanceSummaryComposer::isWaitingApproval)
                .collect(Collectors.toMap(FlowInstanceSummaryView::getInstanceId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        if (!waiting.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            for (FlowExecutionState state : instanceStore.listByInstanceIds(waiting.keySet())) {
                FlowInstanceSummaryView view = waiting.get(state.getInstanceId());
                if (view != null) {
                    view.setUrge(urgeThrottle.status(state, now));
                }
            }
        }
        return target;
    }

    /** An approval waiting on its approvers. The scenario is a joined field a query may leave out; a record's approval is one either way. */
    private static boolean isWaitingApproval(FlowInstanceSummaryView view) {
        return (FlowScenario.APPROVAL.equals(view.getScenario()) || Boolean.TRUE.equals(view.getRecordApproval()))
                && FlowExecutionStatus.WAITING.equals(view.getStatus());
    }

    public List<FlowInstanceSummaryView> summarize(List<FlowInstance> instances, String requesterId) {
        if (instances == null || instances.isEmpty()) {
            return List.of();
        }
        Map<String, List<FlowRecordApprovalsView.PendingStep>> stepsByInstance = currentSteps(instances);
        List<FlowInstanceSummaryView> views = instances.stream()
                .map(instance -> toView(instance, stepsByInstance.getOrDefault(instance.getInstanceId(), List.of())))
                .toList();
        List<FlowRecordApprovalsView.Actor> users = new ArrayList<>();
        views.forEach(view -> {
            users.add(view.getInitiator());
            view.getCurrentSteps().forEach(step -> users.addAll(step.getApprovers()));
        });
        names.name(users);
        names.nameEmployees(views.stream().map(FlowInstanceSummaryView::getSubject).toList());
        names.nameDepartments(views.stream().map(FlowInstanceSummaryView::getSubjectDept).toList());
        applyInitiatorActions(views, requesterId);
        markDeletedRecords(views);
        applyEffectStatuses(views);
        return views;
    }

    /** Where each approved record's change stands, as its model's approval handler reads it: one call per model. */
    private void applyEffectStatuses(List<FlowInstanceSummaryView> views) {
        Map<String, List<FlowInstanceSummaryView>> byModel = views.stream()
                .filter(view -> FlowExecutionStatus.COMPLETED.equals(view.getStatus())
                        && Boolean.TRUE.equals(view.getRecordApproval())
                        && view.getModelName() != null && view.getRowId() != null
                        && !Boolean.TRUE.equals(view.getRecordDeleted()))
                .collect(Collectors.groupingBy(FlowInstanceSummaryView::getModelName, LinkedHashMap::new,
                        Collectors.toList()));
        byModel.forEach((modelName, rows) -> handlerRegistry.find(modelName).ifPresent(handler -> {
            Map<String, RecordEffectStatus> statuses = ContextUtils.withoutPermissionChecks(() -> handler.effectStatuses(
                    rows.stream().map(FlowInstanceSummaryView::getRowId).distinct().toList()));
            rows.forEach(view -> view.setEffectStatus(statuses.get(view.getRowId())));
        }));
    }

    /** Withdraw and urge, on the requester's own approvals that wait on their approvers. */
    private void applyInitiatorActions(List<FlowInstanceSummaryView> views, String requesterId) {
        if (requesterId == null) {
            return;
        }
        Map<String, FlowInstanceSummaryView> own = views.stream()
                .filter(view -> isWaitingApproval(view)
                        && view.getInitiator() != null && requesterId.equals(view.getInitiator().getId()))
                .collect(Collectors.toMap(FlowInstanceSummaryView::getInstanceId, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        if (own.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (FlowExecutionState state : instanceStore.listByInstanceIds(own.keySet())) {
            FlowInstanceSummaryView view = own.get(state.getInstanceId());
            if (view != null) {
                view.setWithdrawable(withdrawPolicyGuard.withdrawable(state, requesterId));
                view.setUrge(urgeThrottle.status(state, requesterId, now));
            }
        }
    }

    /**
     * Flags the record approvals whose record is gone — a draft deleted after its approval was returned or
     * withdrawn — so a list offers no link that ends in "not found", and those whose record was cancelled for good,
     * so a list says so rather than the approval's last state (Returned, Withdrawn) alone. Read past the caller's
     * permissions: the question is what became of the row, not whether they may open it.
     */
    @SuppressWarnings("unchecked")
    private void markDeletedRecords(List<FlowInstanceSummaryView> views) {
        Map<String, List<FlowInstanceSummaryView>> byModel = views.stream()
                .filter(view -> Boolean.TRUE.equals(view.getRecordApproval())
                        && view.getModelName() != null && view.getRowId() != null)
                .collect(Collectors.groupingBy(FlowInstanceSummaryView::getModelName, LinkedHashMap::new,
                        Collectors.toList()));
        byModel.forEach((modelName, rows) -> {
            if (!ModelManager.existModel(modelName)) {
                return;
            }
            List<Serializable> ids = IdUtils.formatIds(modelName,
                    rows.stream().map(FlowInstanceSummaryView::getRowId).distinct().toList());
            ModelService<Serializable> records = (ModelService<Serializable>) (ModelService<?>) modelService;
            List<String> fields = ModelManager.existField(modelName, ApprovalModel.APPROVAL_STATUS)
                    ? List.of(ModelConstant.ID, ApprovalModel.APPROVAL_STATUS)
                    : List.of(ModelConstant.ID);
            Map<String, Map<String, Object>> existing = ContextUtils.withoutPermissionChecks(
                            () -> records.getByIds(modelName, ids, fields)).stream()
                    .collect(Collectors.toMap(row -> Objects.toString(row.get(ModelConstant.ID), null),
                            Function.identity(), (a, b) -> a));
            rows.forEach(view -> {
                Map<String, Object> record = existing.get(view.getRowId());
                view.setRecordDeleted(record == null);
                view.setRecordCancelled(record != null
                        && ApprovalStatus.from(record.get(ApprovalModel.APPROVAL_STATUS)) == ApprovalStatus.CANCELLED);
            });
        });
    }

    /** The steps each waiting instance waits on, in the order their tasks opened, with the approvers still to act. */
    private Map<String, List<FlowRecordApprovalsView.PendingStep>> currentSteps(List<FlowInstance> instances) {
        List<String> waiting = instances.stream()
                .filter(instance -> FlowExecutionStatus.WAITING.equals(instance.getStatus()))
                .map(FlowInstance::getInstanceId)
                .toList();
        if (waiting.isEmpty()) {
            return Map.of();
        }
        FlexQuery query = new FlexQuery(new Filters()
                .in(FlowApprovalTask::getInstanceId, waiting)
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

    private static FlowInstanceSummaryView toView(FlowInstance instance, List<FlowRecordApprovalsView.PendingStep> steps) {
        FlowInstanceSummaryView view = new FlowInstanceSummaryView();
        view.setId(instance.getId());
        view.setInstanceId(instance.getInstanceId());
        view.setBundleId(instance.getBundleId());
        view.setFlowId(instance.getFlowId());
        view.setFlowName(instance.getFlowName());
        view.setScenario(instance.getScenario());
        view.setFlowRevision(instance.getFlowRevision());
        view.setTitle(instance.getTitle());
        view.setModelName(instance.getModelName());
        view.setRowId(instance.getRowId());
        view.setInitiator(instance.getInitiatorId() == null ? null
                : new FlowRecordApprovalsView.Actor(instance.getInitiatorId(), null));
        view.setRecordApproval(instance.getRecordApproval());
        view.setSubject(instance.getSubjectId() == null ? null
                : new FlowRecordApprovalsView.Actor(instance.getSubjectId().toString(), null));
        view.setSubjectDept(instance.getDepartmentId() == null ? null
                : new FlowInstanceSummaryView.Department(instance.getDepartmentId()));
        view.setEffectiveDate(instance.getEffectiveDate());
        view.setRecordCode(instance.getRecordCode());
        view.setStatus(instance.getStatus());
        view.setCurrentSteps(steps);
        view.setAwaitingRevision(Boolean.TRUE.equals(instance.getRecordApproval())
                && FlowExecutionStatus.RETURNED.equals(instance.getStatus())
                && !Boolean.TRUE.equals(instance.getSuperseded()));
        view.setFailedNodeId(instance.getFailedNodeId());
        view.setNextFireAt(instance.getNextFireAt());
        view.setCreatedTime(instance.getCreatedTime());
        view.setUpdatedTime(instance.getUpdatedTime());
        return view;
    }
}
