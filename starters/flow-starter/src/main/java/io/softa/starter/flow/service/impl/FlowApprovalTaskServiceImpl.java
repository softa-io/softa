package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.dto.FlowApprovalTaskSearchRequest;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxCountView;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.engine.FlowStateChangeListener;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.query.ApprovalTaskQuerySupport;
import io.softa.starter.flow.service.query.TaskInstanceContextEnricher;
import io.softa.starter.flow.service.support.CallerConditions;
import io.softa.starter.flow.service.support.FlowApprovalTaskProjector;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.view.FlowApprovalTaskViewMapper;

/**
 * ORM-backed approval task query service and runtime projection synchronizer.
 */
@Service
public class FlowApprovalTaskServiceImpl extends EntityServiceImpl<FlowApprovalTask, Long>
        implements FlowApprovalTaskQueryService, FlowStateChangeListener {

    /** What a task's holder does that closes their task. */
    private static final Set<ApprovalActionType> HOLDER_DECISIONS = EnumSet.of(ApprovalActionType.APPROVE,
            ApprovalActionType.REJECT, ApprovalActionType.RETURN, ApprovalActionType.TRANSFER,
            ApprovalActionType.DELEGATE);

    private final FlowApprovalTaskProjector projector;
    private final FlowInstanceAccessGuard accessGuard;
    private final ApprovalAuditReader auditReader;
    private final TaskInstanceContextEnricher instanceContextEnricher;
    private final FlowInstanceService instanceService;

    public FlowApprovalTaskServiceImpl(FlowApprovalTaskProjector projector,
                                       FlowInstanceAccessGuard accessGuard,
                                       ApprovalAuditReader auditReader,
                                       TaskInstanceContextEnricher instanceContextEnricher,
                                       FlowInstanceService instanceService) {
        this.projector = projector;
        this.accessGuard = accessGuard;
        this.auditReader = auditReader;
        this.instanceContextEnricher = instanceContextEnricher;
        this.instanceService = instanceService;
    }

    @Override
    public Page<FlowApprovalTaskView> searchTasks(String actorId, FlowApprovalTaskSearchRequest request) {
        ApprovalTaskQuerySupport.requireActorId(actorId);
        if (request == null || request.box() == null) {
            throw new FlowActionValidationException("Choose the tasks to search: Pending, Handled or Cc");
        }
        CallerConditions.requireStoredFields(modelName, request.filters(), request.orders());
        // The box pins the rows to the actor; the caller's filters only narrow them.
        Filters own = switch (request.box()) {
            case PENDING -> ApprovalTaskQuerySupport.pendingApprovalFilters(actorId);
            case HANDLED -> ApprovalTaskQuerySupport.completedApprovalFilters(actorId);
            case CC -> ApprovalTaskQuerySupport.ccFilters(actorId);
        };
        // Each box's default puts what reached the caller last first: pending and copied by when they arrived,
        // handled by when they closed. The CC box holds read and unread alike, so oldest first left the copy just
        // received on the last page.
        Orders defaults = switch (request.box()) {
            case PENDING, CC -> newestStartedFirst();
            case HANDLED -> latestClosedFirst();
        };
        return page(CallerConditions.and(own, request.filters()),
                CallerConditions.ordersOr(request.orders(), defaults), request.pageNumber(), request.pageSize());
    }

    @Override
    public FlowInboxCountView countInbox(String actorId) {
        ApprovalTaskQuerySupport.requireActorId(actorId);
        long pendingApprovals = this.count(ApprovalTaskQuerySupport.pendingApprovalFilters(actorId));
        long unreadCc = this.count(ApprovalTaskQuerySupport.unreadCcFilters(actorId));
        return new FlowInboxCountView(pendingApprovals, unreadCc);
    }

    @Override
    public List<FlowApprovalTaskView> getTasksByInstanceId(String instanceId, String requesterId) {
        List<FlowApprovalTask> tasks = getTaskEntitiesByInstanceId(instanceId);
        boolean participant = tasks.stream()
                .anyMatch(task -> requesterId != null && requesterId.equals(task.getActorId()));
        accessGuard.requireInstanceViewer(instanceId, requesterId, participant);
        // The tasks of one instance, read on its own page: the instance is already in hand there, so the tasks carry
        // none of its context — what the lists of many instances' tasks add.
        return FlowApprovalTaskViewMapper.toViews(tasks);
    }

    /**
     * Every task of an instance, read past the caller's data permissions: for the projection sync, the
     * engine's own bookkeeping; for {@link #getTasksByInstanceId}, rows the access guard rules on next.
     */
    private List<FlowApprovalTask> getTaskEntitiesByInstanceId(String instanceId) {
        Filters filters = new Filters().eq(FlowApprovalTask::getInstanceId, instanceId);
        return ContextUtils.withoutPermissionChecks(() -> this.searchList(filters)).stream()
                .sorted(ApprovalTaskQuerySupport.pendingTaskComparator())
                .toList();
    }

    private Page<FlowApprovalTaskView> page(Filters filters, Orders orders, Integer pageNumber, Integer pageSize) {
        return mapPage(this.searchPage(new FlexQuery(filters, orders), Page.of(pageNumber, pageSize)),
                pageNumber, pageSize);
    }

    /** Newest first: what reached the approver last is what they look for. */
    private static Orders newestStartedFirst() {
        return Orders.ofDesc(FlowApprovalTask::getStartTime).addDesc(FlowApprovalTask::getId);
    }

    /** Latest closed first. */
    private static Orders latestClosedFirst() {
        return Orders.ofDesc(FlowApprovalTask::getEndTime).addDesc(FlowApprovalTask::getId);
    }

    private Page<FlowApprovalTaskView> mapPage(Page<FlowApprovalTask> source,
                                               Integer pageNumber,
                                               Integer pageSize) {
        Page<FlowApprovalTaskView> target = Page.of(pageNumber, pageSize);
        target.setTotalCount(source.getTotalCount());
        List<FlowApprovalTaskView> views = FlowApprovalTaskViewMapper.toViews(source.getRows());
        instanceContextEnricher.enrich(views);
        target.setRows(views);
        return target;
    }

    private void syncFromState(FlowExecutionState state) {
        if (state == null || state.getInstanceId() == null) {
            return;
        }
        List<FlowApprovalTask> existingTasks = new ArrayList<>(getTaskEntitiesByInstanceId(state.getInstanceId()));
        List<FlowApprovalTask> desiredTasks = projector.project(state);

        Map<String, FlowApprovalTask> openTasksByKey = new LinkedHashMap<>();
        Map<String, FlowApprovalTask> latestTasksByKey = new LinkedHashMap<>();
        for (FlowApprovalTask task : existingTasks) {
            String key = keyOf(task.getNodeId(), task.getActorId(), task.getTaskType());
            latestTasksByKey.merge(key, task, FlowApprovalTaskServiceImpl::latestTask);
            if (isOpen(task)) {
                openTasksByKey.putIfAbsent(key, task);
            }
        }

        // collect into batches instead of issuing one SQL per task.
        List<FlowApprovalTask> toCreate = new ArrayList<>();
        List<FlowApprovalTask> toUpdate = new ArrayList<>();

        for (FlowApprovalTask desired : desiredTasks) {
            String key = keyOf(desired.getNodeId(), desired.getActorId(), desired.getTaskType());
            FlowApprovalTask openTask = openTasksByKey.remove(key);
            if (openTask != null) {
                copyMutableFields(openTask, desired);
                toUpdate.add(openTask);
                latestTasksByKey.put(key, openTask);
                continue;
            }
            FlowApprovalTask latest = latestTasksByKey.get(key);
            if (latest != null && sameState(latest, desired)) {
                continue;
            }
            toCreate.add(desired);
            latestTasksByKey.put(key, desired);
        }

        for (FlowApprovalTask staleOpenTask : openTasksByKey.values()) {
            closeStaleTask(staleOpenTask, state);
            toUpdate.add(staleOpenTask);
        }

        copyInstanceFields(state.getInstanceId(), toCreate, toUpdate);
        if (!toCreate.isEmpty()) {
            this.createList(toCreate);
        }
        if (!toUpdate.isEmpty()) {
            this.updateList(toUpdate);
        }
    }

    /**
     * Gives the rows about to be written their instance's list fields, as its row holds them — what the views are
     * enriched with: every new row, and a row written before tasks stored them when the sync next updates it. The
     * instance is read once, and only when a row lacks them; the fields never change for its life.
     */
    private void copyInstanceFields(String instanceId, List<FlowApprovalTask> toCreate, List<FlowApprovalTask> toUpdate) {
        List<FlowApprovalTask> lacking = Stream.concat(toCreate.stream(), toUpdate.stream())
                .filter(task -> task.getSubmittedTime() == null)
                .toList();
        if (lacking.isEmpty()) {
            return;
        }
        ContextUtils.withoutPermissionChecks(() -> instanceService.findByInstanceIds(List.of(instanceId))).stream()
                .findFirst()
                .ifPresent(instance -> lacking.forEach(task -> {
                    task.setInstanceTitle(instance.getTitle());
                    task.setRecordCode(instance.getRecordCode());
                    task.setModelName(instance.getModelName());
                    task.setRowId(instance.getRowId());
                    task.setInitiatorId(instance.getInitiatorId());
                    task.setSubmittedTime(instance.getCreatedTime());
                    task.setSubjectId(instance.getSubjectId());
                    task.setDepartmentId(instance.getDepartmentId());
                    task.setEffectiveDate(instance.getEffectiveDate());
                }));
    }

    private static FlowApprovalTask latestTask(FlowApprovalTask left, FlowApprovalTask right) {
        return Comparator.comparing(FlowApprovalTask::getId,
                        Comparator.nullsLast(Long::compareTo))
                .compare(left, right) >= 0 ? left : right;
    }

    /**
     * A row the sync updates in place: a task still waiting on its holder, and a CC not yet read — which carries
     * the time it was sent as its end, so that its reading lands on it rather than on a copy of it.
     */
    private static boolean isOpen(FlowApprovalTask task) {
        return FlowApprovalTaskStatus.PENDING.equals(task.getStatus())
                && (task.getEndTime() == null || FlowApprovalTaskType.CC.equals(task.getTaskType()));
    }

    /**
     * Whether a closed row already is what the projection asks for. The step's vote counts are left out: they move
     * as the others vote, and are no new task of the holder's — comparing them, every later action copied each closed
     * row whose counts it changed (and a CC's, which read back empty as null, on every action).
     */
    private static boolean sameState(FlowApprovalTask current, FlowApprovalTask desired) {
        return Objects.equals(current.getStatus(), desired.getStatus())
                && Objects.equals(current.getTaskType(), desired.getTaskType())
                && Objects.equals(current.getAction(), desired.getAction())
                && Objects.equals(current.getComment(), desired.getComment());
    }

    private static void copyMutableFields(FlowApprovalTask target, FlowApprovalTask source) {
        target.setStatus(source.getStatus());
        target.setTaskType(source.getTaskType());
        target.setAction(source.getAction());
        target.setComment(source.getComment());
        target.setRequiredApprovalCount(source.getRequiredApprovalCount());
        target.setApprovedActors(source.getApprovedActors());
        target.setClosedByActorId(source.getClosedByActorId());
        // An escalation sets a new deadline on the step it hands on.
        target.setDueTime(source.getDueTime());
        if (target.getStartTime() == null) {
            target.setStartTime(source.getStartTime());
        }
        target.setEndTime(source.getEndTime());
    }

    private void closeStaleTask(FlowApprovalTask task, FlowExecutionState state) {
        // What closed it: its holder's own decision in this action when they made one — approving a step the next one
        // then auto-approves ends the action on the next step's entry, which says nothing of theirs — else the latest
        // entry of the approval (another approver's vote, a withdrawal, a termination).
        ApprovalActionAuditEntry latestAudit = ownDecision(task, state);
        if (latestAudit == null) {
            latestAudit = lastAudit(state);
        }
        task.setStatus(resolveTerminalStatus(task, state, latestAudit));
        task.setAction(latestAudit == null ? task.getAction() : latestAudit.getAction());
        task.setComment(latestAudit == null ? task.getComment() : latestAudit.getComment());
        task.setClosedByActorId(latestAudit == null ? task.getClosedByActorId() : latestAudit.getActorId());
        if (task.getEndTime() == null) {
            task.setEndTime(latestAudit == null || latestAudit.getEventTime() == null
                    ? LocalDateTime.now()
                    : latestAudit.getEventTime());
        }
    }

    /**
     * The task holder's latest decision on its step among the entries the current action recorded, or null.
     * Only this action's: an earlier decision of theirs on the step — before a transfer came back to them — did not
     * close this task.
     */
    private static ApprovalActionAuditEntry ownDecision(FlowApprovalTask task, FlowExecutionState state) {
        List<ApprovalActionAuditEntry> delta = state.getApprovalAuditDelta();
        if (delta == null) {
            return null;
        }
        for (int i = delta.size() - 1; i >= 0; i--) {
            ApprovalActionAuditEntry entry = delta.get(i);
            if (HOLDER_DECISIONS.contains(entry.getAction())
                    && Objects.equals(task.getNodeId(), entry.getNodeId())
                    && Objects.equals(task.getActorId(), entry.getActorId())) {
                return entry;
            }
        }
        return null;
    }

    private ApprovalActionAuditEntry lastAudit(FlowExecutionState state) {
        List<ApprovalActionAuditEntry> history = auditReader.fullHistory(state);
        return history.isEmpty() ? null : history.getLast();
    }

    private static FlowApprovalTaskStatus resolveTerminalStatus(FlowApprovalTask task,
                                                                FlowExecutionState state,
                                                                ApprovalActionAuditEntry latestAudit) {
        if (FlowExecutionStatus.WITHDRAWN.equals(state.getStatus())) {
            return FlowApprovalTaskStatus.WITHDRAWN;
        }
        if (latestAudit == null) {
            return FlowExecutionStatus.RETURNED.equals(state.getStatus())
                    ? FlowApprovalTaskStatus.RETURNED
                    : FlowApprovalTaskStatus.CANCELED;
        }
        if (FlowExecutionStatus.REJECTED.equals(state.getStatus())
                && ApprovalActionType.REJECT.equals(latestAudit.getAction())
                && Objects.equals(task.getNodeId(), latestAudit.getNodeId())
                && Objects.equals(task.getActorId(), latestAudit.getActorId())) {
            return FlowApprovalTaskStatus.REJECTED;
        }
        if (ApprovalActionType.APPROVE.equals(latestAudit.getAction())
                && Objects.equals(task.getNodeId(), latestAudit.getNodeId())
                && Objects.equals(task.getActorId(), latestAudit.getActorId())) {
            return FlowApprovalTaskStatus.APPROVED;
        }
        if (ApprovalActionType.RETURN.equals(latestAudit.getAction())
                && Objects.equals(task.getNodeId(), latestAudit.getNodeId())
                && Objects.equals(task.getActorId(), latestAudit.getActorId())) {
            return FlowApprovalTaskStatus.RETURNED;
        }
        if (ApprovalActionType.TRANSFER.equals(latestAudit.getAction())
                && Objects.equals(task.getNodeId(), latestAudit.getNodeId())
                && Objects.equals(task.getActorId(), latestAudit.getActorId())) {
            return FlowApprovalTaskStatus.TRANSFERRED;
        }
        if (ApprovalActionType.DELEGATE.equals(latestAudit.getAction())
                && Objects.equals(task.getNodeId(), latestAudit.getNodeId())
                && Objects.equals(task.getActorId(), latestAudit.getActorId())) {
            return FlowApprovalTaskStatus.DELEGATED;
        }
        if (ApprovalActionType.WITHDRAW.equals(latestAudit.getAction())) {
            return FlowApprovalTaskStatus.WITHDRAWN;
        }
        return FlowApprovalTaskStatus.CANCELED;
    }

    private static String keyOf(String nodeId, String actorId, FlowApprovalTaskType taskType) {
        return nodeId + "::" + actorId + "::" + taskType;
    }

    /**
     * Projection sync entry — annotated HERE (the proxy-visible interface method)
     * rather than on the internal {@code syncFromState}, where a this-call would
     * bypass Spring's transactional proxy. Callers inside the engine already run
     * in {@code FlowRuntimeFacade}'s transaction; this joins it (REQUIRED).
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onStateChanged(FlowExecutionState state) {
        syncFromState(state);
    }
}
