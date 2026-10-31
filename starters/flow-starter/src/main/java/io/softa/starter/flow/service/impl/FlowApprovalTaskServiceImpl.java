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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxCountView;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.engine.FlowStateChangeListener;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;
import io.softa.starter.flow.service.query.ApprovalTaskQuerySupport;
import io.softa.starter.flow.service.query.TaskInstanceContextEnricher;
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

    public FlowApprovalTaskServiceImpl(FlowApprovalTaskProjector projector,
                                       FlowInstanceAccessGuard accessGuard,
                                       ApprovalAuditReader auditReader,
                                       TaskInstanceContextEnricher instanceContextEnricher) {
        this.projector = projector;
        this.accessGuard = accessGuard;
        this.auditReader = auditReader;
        this.instanceContextEnricher = instanceContextEnricher;
    }

    @Override
    public List<FlowApprovalTaskView> getPendingTasks(String actorId, String flowId, String instanceId, String nodeId) {
        return pagePendingTasks(actorId, flowId, instanceId, nodeId, 1, 50).getRows();
    }

    @Override
    public Page<FlowApprovalTaskView> pagePendingTasks(String actorId,
                                                       String flowId,
                                                       String instanceId,
                                                       String nodeId,
                                                       Integer pageNumber,
                                                       Integer pageSize) {
        ApprovalTaskQuerySupport.requireActorId(actorId);
        Filters filters = ApprovalTaskQuerySupport.pendingApprovalFilters(actorId);
        applyOptionalFilters(filters, flowId, instanceId, nodeId);
        // Newest first: what reached the approver last is what they look for.
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowApprovalTask::getStartTime)
                .addDesc(FlowApprovalTask::getId));
        return mapPage(this.searchPage(query, Page.of(pageNumber, pageSize)), pageNumber, pageSize);
    }

    @Override
    public List<FlowApprovalTaskView> getCompletedTasks(String actorId, String flowId, String instanceId, String nodeId) {
        return pageCompletedTasks(actorId, flowId, instanceId, nodeId, 1, 50).getRows();
    }

    @Override
    public Page<FlowApprovalTaskView> pageCompletedTasks(String actorId,
                                                         String flowId,
                                                         String instanceId,
                                                         String nodeId,
                                                         Integer pageNumber,
                                                         Integer pageSize) {
        ApprovalTaskQuerySupport.requireActorId(actorId);
        Filters filters = ApprovalTaskQuerySupport.completedApprovalFilters(actorId);
        applyOptionalFilters(filters, flowId, instanceId, nodeId);
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowApprovalTask::getEndTime)
                .addDesc(FlowApprovalTask::getId));
        return mapPage(this.searchPage(query, Page.of(pageNumber, pageSize)), pageNumber, pageSize);
    }

    @Override
    public List<FlowApprovalTaskView> getCcTasks(String actorId, Boolean read, String flowId, String instanceId, String nodeId) {
        return pageCcTasks(actorId, read, flowId, instanceId, nodeId, 1, 50).getRows();
    }

    @Override
    public Page<FlowApprovalTaskView> pageCcTasks(String actorId,
                                                  Boolean read,
                                                  String flowId,
                                                  String instanceId,
                                                  String nodeId,
                                                  Integer pageNumber,
                                                  Integer pageSize) {
        ApprovalTaskQuerySupport.requireActorId(actorId);
        // read=false shares the unread-CC badge-count definition; read=true its mirror.
        Filters filters;
        if (Boolean.FALSE.equals(read)) {
            filters = ApprovalTaskQuerySupport.unreadCcFilters(actorId);
        } else if (Boolean.TRUE.equals(read)) {
            filters = ApprovalTaskQuerySupport.readCcFilters(actorId);
        } else {
            filters = new Filters()
                    .eq(FlowApprovalTask::getActorId, actorId)
                    .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.CC);
        }
        applyOptionalFilters(filters, flowId, instanceId, nodeId);
        Orders orders = Boolean.TRUE.equals(read)
                ? Orders.ofDesc(FlowApprovalTask::getEndTime).addDesc(FlowApprovalTask::getId)
                : Orders.ofAsc(FlowApprovalTask::getStartTime).addAsc(FlowApprovalTask::getId);
        return mapPage(this.searchPage(new FlexQuery(filters, orders), Page.of(pageNumber, pageSize)), pageNumber, pageSize);
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
        List<FlowApprovalTaskView> views = FlowApprovalTaskViewMapper.toViews(tasks);
        instanceContextEnricher.enrich(views);
        return views;
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

    private static void applyOptionalFilters(Filters filters, String flowId, String instanceId, String nodeId) {
        if (StringUtils.hasText(flowId)) {
            filters.eq(FlowApprovalTask::getFlowId, flowId);
        }
        if (StringUtils.hasText(instanceId)) {
            filters.eq(FlowApprovalTask::getInstanceId, instanceId);
        }
        if (StringUtils.hasText(nodeId)) {
            filters.eq(FlowApprovalTask::getNodeId, nodeId);
        }
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
            String key = keyOf(task.getNodeId(), task.getCycleNumber(), task.getActorId(), task.getTaskType());
            latestTasksByKey.merge(key, task, FlowApprovalTaskServiceImpl::latestTask);
            if (isOpen(task)) {
                openTasksByKey.putIfAbsent(key, task);
            }
        }

        // collect into batches instead of issuing one SQL per task.
        List<FlowApprovalTask> toCreate = new ArrayList<>();
        List<FlowApprovalTask> toUpdate = new ArrayList<>();

        for (FlowApprovalTask desired : desiredTasks) {
            String key = keyOf(desired.getNodeId(), desired.getCycleNumber(), desired.getActorId(), desired.getTaskType());
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

        if (!toCreate.isEmpty()) {
            this.createList(toCreate);
        }
        if (!toUpdate.isEmpty()) {
            this.updateList(toUpdate);
        }
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
                && Objects.equals(current.getBlocked(), desired.getBlocked())
                && Objects.equals(current.getBlockedByActorId(), desired.getBlockedByActorId())
                && Objects.equals(current.getComment(), desired.getComment());
    }

    private static void copyMutableFields(FlowApprovalTask target, FlowApprovalTask source) {
        target.setStatus(source.getStatus());
        target.setTaskType(source.getTaskType());
        target.setAction(source.getAction());
        target.setComment(source.getComment());
        target.setDynamicApprovers(source.getDynamicApprovers());
        target.setApprovalMode(source.getApprovalMode());
        target.setRequiredApprovalCount(source.getRequiredApprovalCount());
        target.setTotalApproverCount(source.getTotalApproverCount());
        target.setRejectMode(source.getRejectMode());
        target.setRequiredRejectCount(source.getRequiredRejectCount());
        target.setCandidateActors(source.getCandidateActors());
        target.setApprovedActors(source.getApprovedActors());
        target.setRejectedActors(source.getRejectedActors());
        target.setClosedByActorId(source.getClosedByActorId());
        target.setBlocked(source.getBlocked());
        target.setBlockedByActorId(source.getBlockedByActorId());
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
     * The task holder's latest decision on its step and cycle among the entries the current action recorded, or null.
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
                    && Objects.equals(task.getCycleNumber(), entry.getCycleNumber())
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

    private static String keyOf(String nodeId,
                                Integer cycleNumber,
                                String actorId,
                                FlowApprovalTaskType taskType) {
        return nodeId + "::" + cycleNumber + "::" + actorId + "::" + taskType;
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
