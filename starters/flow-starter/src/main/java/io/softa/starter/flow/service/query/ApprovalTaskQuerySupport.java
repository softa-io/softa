package io.softa.starter.flow.service.query;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;

/**
 * Shared approval task query filtering and sorting rules. The DB-level filter
 * factories below are the single source for both the paged inbox queries and
 * the badge counts — keep them in lockstep so a count always matches its list.
 */
public final class ApprovalTaskQuerySupport {

    /** Pending-approval work queue: actor's own PENDING rows of type APPROVAL. */
    public static Filters pendingApprovalFilters(String actorId) {
        return new Filters()
                .eq(FlowApprovalTask::getActorId, actorId)
                .eq(FlowApprovalTask::getStatus, FlowApprovalTaskStatus.PENDING)
                .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.APPROVAL);
    }

    /** Unread CC copies: actor's own PENDING rows of type CC. */
    public static Filters unreadCcFilters(String actorId) {
        return new Filters()
                .eq(FlowApprovalTask::getActorId, actorId)
                .eq(FlowApprovalTask::getStatus, FlowApprovalTaskStatus.PENDING)
                .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.CC);
    }

    /** Completed work queue: actor's own APPROVAL rows in any closed status. */
    public static Filters completedApprovalFilters(String actorId) {
        return new Filters()
                .eq(FlowApprovalTask::getActorId, actorId)
                .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.APPROVAL)
                .in(FlowApprovalTask::getStatus, COMPLETED_STATUSES);
    }

    /** All CC copies: actor's own CC rows, read or unread. */
    public static Filters ccFilters(String actorId) {
        return new Filters()
                .eq(FlowApprovalTask::getActorId, actorId)
                .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.CC);
    }

    /** Read CC copies: actor's own CC rows already acknowledged. */
    public static Filters readCcFilters(String actorId) {
        return new Filters()
                .eq(FlowApprovalTask::getActorId, actorId)
                .eq(FlowApprovalTask::getTaskType, FlowApprovalTaskType.CC)
                .in(FlowApprovalTask::getStatus,
                        List.of(FlowApprovalTaskStatus.READ, FlowApprovalTaskStatus.CC));
    }

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

    private ApprovalTaskQuerySupport() {
    }

    public static List<FlowApprovalTask> filterAndSortPendingTasks(List<FlowApprovalTask> tasks,
                                                                          String actorId,
                                                                          String flowId,
                                                                          String instanceId,
                                                                          String nodeId) {
        return filterTasks(tasks, actorId, flowId, instanceId, nodeId).stream()
                .filter(task -> FlowApprovalTaskStatus.PENDING.equals(task.getStatus()))
                .sorted(pendingTaskComparator())
                .toList();
    }

    public static List<FlowApprovalTask> filterAndSortCompletedTasks(List<FlowApprovalTask> tasks,
                                                                            String actorId,
                                                                            String flowId,
                                                                            String instanceId,
                                                                            String nodeId) {
        return filterTasks(tasks, actorId, flowId, instanceId, nodeId).stream()
                .filter(task -> COMPLETED_STATUSES.contains(task.getStatus()))
                .sorted(completedTaskComparator())
                .toList();
    }

    public static List<FlowApprovalTask> filterAndSortCcTasks(List<FlowApprovalTask> tasks,
                                                                     String actorId,
                                                                     Boolean read,
                                                                     String flowId,
                                                                     String instanceId,
                                                                     String nodeId) {
        return filterTasks(tasks, actorId, flowId, instanceId, nodeId).stream()
                .filter(ApprovalTaskQuerySupport::isCcTask)
                .filter(task -> read == null
                        || (read && isReadCcTask(task))
                        || (!read && isUnreadCcTask(task)))
                .sorted(ccTaskComparator(read))
                .toList();
    }

    public static void requireActorId(String actorId) {
        if (!StringUtils.hasText(actorId)) {
            throw new IllegalArgumentException("actorId is required for inbox queries");
        }
    }

    private static List<FlowApprovalTask> filterTasks(List<FlowApprovalTask> tasks,
                                                             String actorId,
                                                             String flowId,
                                                             String instanceId,
                                                             String nodeId) {
        return tasks.stream()
                .filter(task -> Objects.equals(actorId, task.getActorId()))
                .filter(task -> !StringUtils.hasText(flowId) || Objects.equals(flowId, task.getFlowId()))
                .filter(task -> !StringUtils.hasText(instanceId) || Objects.equals(instanceId, task.getInstanceId()))
                .filter(task -> !StringUtils.hasText(nodeId) || Objects.equals(nodeId, task.getNodeId()))
                .toList();
    }

    /** Oldest-first work-queue order — public so DB-backed reads sort the same way. */
    public static Comparator<FlowApprovalTask> pendingTaskComparator() {
        return Comparator.comparing(FlowApprovalTask::getStartTime, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(FlowApprovalTask::getId, Comparator.nullsLast(Long::compareTo));
    }

    private static Comparator<FlowApprovalTask> completedTaskComparator() {
        return Comparator.comparing(FlowApprovalTask::getEndTime, Comparator.nullsLast(LocalDateTime::compareTo)).reversed()
                .thenComparing(FlowApprovalTask::getId, Comparator.nullsLast(Long::compareTo).reversed());
    }

    private static Comparator<FlowApprovalTask> ccTaskComparator(Boolean read) {
        if (Boolean.TRUE.equals(read)) {
            return completedTaskComparator();
        }
        if (Boolean.FALSE.equals(read)) {
            return pendingTaskComparator();
        }
        return (left, right) -> {
            boolean leftUnread = isUnreadCcTask(left);
            boolean rightUnread = isUnreadCcTask(right);
            if (leftUnread != rightUnread) {
                return leftUnread ? -1 : 1;
            }
            if (leftUnread) {
                int byStartTime = Comparator.comparing(FlowApprovalTask::getStartTime,
                        Comparator.nullsLast(LocalDateTime::compareTo)).compare(left, right);
                if (byStartTime != 0) {
                    return byStartTime;
                }
                return Comparator.comparing(FlowApprovalTask::getId,
                        Comparator.nullsLast(Long::compareTo)).compare(left, right);
            }
            int byEndTime = Comparator.comparing(FlowApprovalTask::getEndTime,
                    Comparator.nullsLast(LocalDateTime::compareTo)).reversed().compare(left, right);
            if (byEndTime != 0) {
                return byEndTime;
            }
            return Comparator.comparing(FlowApprovalTask::getId,
                    Comparator.nullsLast(Long::compareTo).reversed()).compare(left, right);
        };
    }

    private static boolean isCcTask(FlowApprovalTask task) {
        return FlowApprovalTaskType.CC.equals(task.getTaskType());
    }

    private static boolean isUnreadCcTask(FlowApprovalTask task) {
        return isCcTask(task) && FlowApprovalTaskStatus.PENDING.equals(task.getStatus());
    }

    private static boolean isReadCcTask(FlowApprovalTask task) {
        return isCcTask(task) && (FlowApprovalTaskStatus.READ.equals(task.getStatus())
                || FlowApprovalTaskStatus.CC.equals(task.getStatus()));
    }
}

