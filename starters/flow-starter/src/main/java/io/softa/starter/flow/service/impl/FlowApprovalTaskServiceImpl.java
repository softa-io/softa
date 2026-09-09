package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
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
    public List<FlowApprovalTaskView> getPendingTasks(String actorId, String flowCode, String instanceId, String nodeId) {
        return pagePendingTasks(actorId, flowCode, instanceId, nodeId, 1, 50).getRows();
    }

    @Override
    public Page<FlowApprovalTaskView> pagePendingTasks(String actorId,
                                                       String flowCode,
                                                       String instanceId,
                                                       String nodeId,
                                                       Integer pageNumber,
                                                       Integer pageSize) {
        ApprovalTaskQuerySupport.requireActorId(actorId);
        Filters filters = ApprovalTaskQuerySupport.pendingApprovalFilters(actorId);
        applyOptionalFilters(filters, flowCode, instanceId, nodeId);
        FlexQuery query = new FlexQuery(filters, Orders.ofAsc(FlowApprovalTask::getStartTime)
                .addAsc(FlowApprovalTask::getId));
        return mapPage(this.searchPage(query, Page.of(pageNumber, pageSize)), pageNumber, pageSize);
    }

    @Override
    public List<FlowApprovalTaskView> getCompletedTasks(String actorId, String flowCode, String instanceId, String nodeId) {
        return pageCompletedTasks(actorId, flowCode, instanceId, nodeId, 1, 50).getRows();
    }

    @Override
    public Page<FlowApprovalTaskView> pageCompletedTasks(String actorId,
                                                         String flowCode,
                                                         String instanceId,
                                                         String nodeId,
                                                         Integer pageNumber,
                                                         Integer pageSize) {
        ApprovalTaskQuerySupport.requireActorId(actorId);
        Filters filters = ApprovalTaskQuerySupport.completedApprovalFilters(actorId);
        applyOptionalFilters(filters, flowCode, instanceId, nodeId);
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowApprovalTask::getEndTime)
                .addDesc(FlowApprovalTask::getId));
        return mapPage(this.searchPage(query, Page.of(pageNumber, pageSize)), pageNumber, pageSize);
    }

    @Override
    public List<FlowApprovalTaskView> getCcTasks(String actorId, Boolean read, String flowCode, String instanceId, String nodeId) {
        return pageCcTasks(actorId, read, flowCode, instanceId, nodeId, 1, 50).getRows();
    }

    @Override
    public Page<FlowApprovalTaskView> pageCcTasks(String actorId,
                                                  Boolean read,
                                                  String flowCode,
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
        applyOptionalFilters(filters, flowCode, instanceId, nodeId);
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

    /**
     * Cross-actor read, exempt from row scope: the view's whole point is showing the initiator
     * every approver's task, and a per-row rule ({@code actorId = caller}) can only ever surface
     * the caller's own rows — it would silently shrink this list to one entry. Authorization is
     * the in-method guard instead: {@code requireInstanceViewer} admits participants and the
     * initiator and throws for everyone else, and the exemption flag also covers the guard's own
     * initiator lookup, which a scoped read would otherwise blank for the very caller it exists
     * to admit.
     */
    @Override
    @SkipPermissionCheck
    public List<FlowApprovalTaskView> getTasksByInstanceId(String instanceId, String requesterId) {
        List<FlowApprovalTask> tasks = getTaskEntitiesByInstanceId(instanceId);
        boolean participant = tasks.stream()
                .anyMatch(task -> requesterId != null && requesterId.equals(task.getActorId()));
        accessGuard.requireInstanceViewer(instanceId, requesterId, participant);
        List<FlowApprovalTaskView> views = FlowApprovalTaskViewMapper.toViews(tasks);
        instanceContextEnricher.enrich(views);
        return views;
    }

    private List<FlowApprovalTask> getTaskEntitiesByInstanceId(String instanceId) {
        Filters filters = new Filters().eq(FlowApprovalTask::getInstanceId, instanceId);
        return this.searchList(filters).stream()
                .sorted(ApprovalTaskQuerySupport.pendingTaskComparator())
                .toList();
    }

    private static void applyOptionalFilters(Filters filters, String flowCode, String instanceId, String nodeId) {
        if (StringUtils.hasText(flowCode)) {
            filters.eq(FlowApprovalTask::getFlowCode, flowCode);
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

    private static boolean isOpen(FlowApprovalTask task) {
        return task.getEndTime() == null && FlowApprovalTaskStatus.PENDING.equals(task.getStatus());
    }

    private static boolean sameState(FlowApprovalTask current, FlowApprovalTask desired) {
        return Objects.equals(current.getStatus(), desired.getStatus())
                && Objects.equals(current.getTaskType(), desired.getTaskType())
                && Objects.equals(current.getAction(), desired.getAction())
                && Objects.equals(current.getApprovedActors(), desired.getApprovedActors())
                && Objects.equals(current.getRejectedActors(), desired.getRejectedActors())
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
        // Write-once: the snapshot is "the form as this approver saw it", so a re-projection must
        // not refresh it from the (possibly since-edited) business row. Backfill only when absent —
        // an open task created before its design gained form fields picks the snapshot up here.
        if (target.getFormSnapshot() == null) {
            target.setFormSnapshot(source.getFormSnapshot());
        }
    }

    private void closeStaleTask(FlowApprovalTask task, FlowExecutionState state) {
        ApprovalActionAuditEntry latestAudit = lastAudit(state);
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
     *
     * <p>{@code @SkipPermissionCheck} rides along for the same structural reason and lands on the
     * same method: {@code flow_approval_task} is engine bookkeeping, so the post-write scope re-read
     * in {@code ModelServiceImpl} can only fail for a non-admin caller — see
     * {@code FlowInstanceServiceImpl.saveInstance}. The {@code this.createList} / {@code this.updateList}
     * inside {@code syncFromState} are self-invocations, so the annotation has to sit out here.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @SkipPermissionCheck
    public void onStateChanged(FlowExecutionState state) {
        syncFromState(state);
    }
}
