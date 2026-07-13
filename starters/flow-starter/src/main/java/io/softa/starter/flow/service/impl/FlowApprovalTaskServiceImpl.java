package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
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
import io.softa.starter.flow.service.support.FlowApprovalTaskProjector;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.view.FlowApprovalTaskViewMapper;

/**
 * ORM-backed approval task query service and runtime projection synchronizer.
 */
@Service
public class FlowApprovalTaskServiceImpl extends EntityServiceImpl<FlowApprovalTask, Long>
        implements FlowApprovalTaskQueryService, FlowStateChangeListener {

    private static final List<FlowApprovalTaskStatus> COMPLETED_STATUSES = List.of(
            FlowApprovalTaskStatus.APPROVED,
            FlowApprovalTaskStatus.REJECTED,
            FlowApprovalTaskStatus.RETURNED,
            FlowApprovalTaskStatus.TRANSFERRED,
            FlowApprovalTaskStatus.DELEGATED,
            FlowApprovalTaskStatus.CANCELED,
            FlowApprovalTaskStatus.WITHDRAWN,
            FlowApprovalTaskStatus.CC,
            FlowApprovalTaskStatus.READ
    );

    private final FlowApprovalTaskProjector projector;
    private final FlowInstanceAccessGuard accessGuard;
    private final ApprovalAuditReader auditReader;

    public FlowApprovalTaskServiceImpl(FlowApprovalTaskProjector projector,
                                       FlowInstanceAccessGuard accessGuard,
                                       ApprovalAuditReader auditReader) {
        this.projector = projector;
        this.accessGuard = accessGuard;
        this.auditReader = auditReader;
    }

    @Override
    public List<FlowApprovalTaskView> getPendingTasks(String actorId) {
        return getPendingTasks(actorId, null, null, null);
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
        Filters filters = new Filters()
                .eq(FlowApprovalTask::getActorId, actorId)
                .eq(FlowApprovalTask::getStatus, FlowApprovalTaskStatus.PENDING)
                .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.APPROVAL);
        applyOptionalFilters(filters, flowCode, instanceId, nodeId);
        FlexQuery query = new FlexQuery(filters, Orders.ofAsc(FlowApprovalTask::getStartTime)
                .addAsc(FlowApprovalTask::getId));
        return mapPage(this.searchPage(query, Page.of(pageNumber, pageSize)), pageNumber, pageSize);
    }

    @Override
    public List<FlowApprovalTaskView> getCompletedTasks(String actorId) {
        return getCompletedTasks(actorId, null, null, null);
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
        Filters filters = new Filters()
                .eq(FlowApprovalTask::getActorId, actorId)
                .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.APPROVAL)
                .in(FlowApprovalTask::getStatus, COMPLETED_STATUSES);
        applyOptionalFilters(filters, flowCode, instanceId, nodeId);
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowApprovalTask::getEndTime)
                .addDesc(FlowApprovalTask::getId));
        return mapPage(this.searchPage(query, Page.of(pageNumber, pageSize)), pageNumber, pageSize);
    }

    @Override
    public List<FlowApprovalTaskView> getCcTasks(String actorId, Boolean read) {
        return getCcTasks(actorId, read, null, null, null);
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
        Filters filters = new Filters()
                .eq(FlowApprovalTask::getActorId, actorId)
                .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.CC);
        if (Boolean.TRUE.equals(read)) {
            filters.in(FlowApprovalTask::getStatus,
                    List.of(FlowApprovalTaskStatus.READ, FlowApprovalTaskStatus.CC));
        } else if (Boolean.FALSE.equals(read)) {
            filters.eq(FlowApprovalTask::getStatus, FlowApprovalTaskStatus.PENDING);
        }
        applyOptionalFilters(filters, flowCode, instanceId, nodeId);
        Orders orders = Boolean.TRUE.equals(read)
                ? Orders.ofDesc(FlowApprovalTask::getEndTime).addDesc(FlowApprovalTask::getId)
                : Orders.ofAsc(FlowApprovalTask::getStartTime).addAsc(FlowApprovalTask::getId);
        return mapPage(this.searchPage(new FlexQuery(filters, orders), Page.of(pageNumber, pageSize)), pageNumber, pageSize);
    }

    @Override
    public List<FlowApprovalTaskView> getTasksByInstanceId(String instanceId, String requesterId) {
        List<FlowApprovalTask> tasks = getTaskEntitiesByInstanceId(instanceId);
        boolean participant = tasks.stream()
                .anyMatch(task -> requesterId != null && requesterId.equals(task.getActorId()));
        accessGuard.requireInstanceViewer(instanceId, requesterId, participant);
        return FlowApprovalTaskViewMapper.toViews(tasks);
    }

    private List<FlowApprovalTask> getTaskEntitiesByInstanceId(String instanceId) {
        Filters filters = new Filters().eq(FlowApprovalTask::getInstanceId, instanceId);
        return this.searchList(filters).stream()
                .sorted(pendingTaskComparator())
                .toList();
    }

    static List<FlowApprovalTask> filterAndSortPendingTasks(List<FlowApprovalTask> tasks,
                                                                   String actorId,
                                                                   String flowCode,
                                                                   String instanceId,
                                                                   String nodeId) {
        return ApprovalTaskQuerySupport.filterAndSortPendingTasks(tasks, actorId, flowCode, instanceId, nodeId);
    }

    static List<FlowApprovalTask> filterAndSortCompletedTasks(List<FlowApprovalTask> tasks,
                                                                     String actorId,
                                                                     String flowCode,
                                                                     String instanceId,
                                                                     String nodeId) {
        return ApprovalTaskQuerySupport.filterAndSortCompletedTasks(tasks, actorId, flowCode, instanceId, nodeId);
    }

    static List<FlowApprovalTask> filterAndSortCcTasks(List<FlowApprovalTask> tasks,
                                                              String actorId,
                                                              Boolean read,
                                                              String flowCode,
                                                              String instanceId,
                                                              String nodeId) {
        return ApprovalTaskQuerySupport.filterAndSortCcTasks(tasks, actorId, read, flowCode, instanceId, nodeId);
    }

    private static Comparator<FlowApprovalTask> pendingTaskComparator() {
        return Comparator.comparing(FlowApprovalTask::getStartTime, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(FlowApprovalTask::getId, Comparator.nullsLast(Long::compareTo));
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

    private static Page<FlowApprovalTaskView> mapPage(Page<FlowApprovalTask> source,
                                                      Integer pageNumber,
                                                      Integer pageSize) {
        Page<FlowApprovalTaskView> target = Page.of(pageNumber, pageSize);
        target.setTotalCount(source.getTotalCount());
        target.setRows(FlowApprovalTaskViewMapper.toViews(source.getRows()));
        return target;
    }

    @Transactional(rollbackFor = Exception.class)
    public void syncFromState(FlowExecutionState state) {
        if (state == null || state.getInstanceId() == null) {
            return;
        }
        List<FlowApprovalTask> existingTasks = new ArrayList<>(getTaskEntitiesByInstanceId(state.getInstanceId()));
        List<FlowApprovalTask> desiredTasks = projector.project(state);

        Map<String, FlowApprovalTask> openTasksByKey = new LinkedHashMap<>();
        Map<String, FlowApprovalTask> latestTasksByKey = new LinkedHashMap<>();
        for (FlowApprovalTask task : existingTasks) {
            String key = keyOf(task.getNodeId(), task.getCycleNumber(), task.getActorId(), task.getTaskType());
            latestTasksByKey.merge(key, task, this::latestTask);
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

    private FlowApprovalTask latestTask(FlowApprovalTask left, FlowApprovalTask right) {
        return Comparator.comparing(FlowApprovalTask::getId,
                        Comparator.nullsLast(Long::compareTo))
                .compare(left, right) >= 0 ? left : right;
    }

    private boolean isOpen(FlowApprovalTask task) {
        return task.getEndTime() == null && FlowApprovalTaskStatus.PENDING.equals(task.getStatus());
    }

    private boolean sameState(FlowApprovalTask current, FlowApprovalTask desired) {
        return Objects.equals(current.getStatus(), desired.getStatus())
                && Objects.equals(current.getTaskType(), desired.getTaskType())
                && Objects.equals(current.getAction(), desired.getAction())
                && Objects.equals(current.getApprovedActors(), desired.getApprovedActors())
                && Objects.equals(current.getRejectedActors(), desired.getRejectedActors())
                && Objects.equals(current.getBlocked(), desired.getBlocked())
                && Objects.equals(current.getBlockedByActorId(), desired.getBlockedByActorId())
                && Objects.equals(current.getComment(), desired.getComment());
    }

    private void copyMutableFields(FlowApprovalTask target, FlowApprovalTask source) {
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

    private FlowApprovalTaskStatus resolveTerminalStatus(FlowApprovalTask task,
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

    private String keyOf(String nodeId,
                         Integer cycleNumber,
                         String actorId,
                         FlowApprovalTaskType taskType) {
        return nodeId + "::" + cycleNumber + "::" + actorId + "::" + taskType;
    }

    @Override
    public void onStateChanged(FlowExecutionState state) {
        syncFromState(state);
    }
}
