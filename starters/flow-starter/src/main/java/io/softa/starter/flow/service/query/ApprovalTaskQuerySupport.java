package io.softa.starter.flow.service.query;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;

/**
 * Shared approval task query rules. The DB-level filter factories below are the single source for both the paged
 * inbox queries and the badge counts — keep them in lockstep so a count always matches its list.
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

    public static void requireActorId(String actorId) {
        if (!StringUtils.hasText(actorId)) {
            throw new IllegalArgumentException("actorId is required for inbox queries");
        }
    }

    /** Oldest-first work-queue order — public so DB-backed reads sort the same way. */
    public static Comparator<FlowApprovalTask> pendingTaskComparator() {
        return Comparator.comparing(FlowApprovalTask::getStartTime, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(FlowApprovalTask::getId, Comparator.nullsLast(Long::compareTo));
    }
}
