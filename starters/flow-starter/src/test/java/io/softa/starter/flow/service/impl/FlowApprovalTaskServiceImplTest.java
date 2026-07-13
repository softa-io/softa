package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowApprovalTaskServiceImplTest {

    @Test
    void shouldFilterAndSortPendingInboxOldestFirst() {
        var oldest = task(2L, "actor-1", "leave", "inst-1", "approvalA",
                FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(3), null);
        var newest = task(1L, "actor-1", "leave", "inst-2", "approvalB",
                FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(1), null);
        var unreadCc = task(5L, "actor-1", "leave", "inst-3", "approvalCc",
                FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(2), null);
        var completed = task(3L, "actor-1", "leave", "inst-1", "approvalA",
                FlowApprovalTaskStatus.APPROVED,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(30));
        var otherActor = task(4L, "actor-2", "leave", "inst-1", "approvalA",
                FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(4), null);

        var result = FlowApprovalTaskServiceImpl.filterAndSortPendingTasks(
                List.of(newest, completed, oldest, otherActor, unreadCc),
                "actor-1", "leave", null, null);

        assertEquals(List.of(oldest, unreadCc, newest), result);
    }

    @Test
    void shouldFilterAndSortCompletedInboxNewestClosedFirst() {
        var older = task(1L, "actor-1", "leave", "inst-1", "approvalA",
                FlowApprovalTaskStatus.APPROVED,
                LocalDateTime.now().minusHours(3), LocalDateTime.now().minusHours(2));
        var newer = task(2L, "actor-1", "leave", "inst-1", "approvalB",
                FlowApprovalTaskStatus.WITHDRAWN,
                LocalDateTime.now().minusHours(4), LocalDateTime.now().minusMinutes(10));
        var readCcTask = task(5L, "actor-1", "leave", "inst-3", "approvalCc",
                FlowApprovalTaskStatus.READ,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(3));
        var pending = task(3L, "actor-1", "leave", "inst-1", "approvalC",
                FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(1), null);
        var otherFlow = task(4L, "actor-1", "expense", "inst-9", "approvalD",
                FlowApprovalTaskStatus.REJECTED,
                LocalDateTime.now().minusHours(5), LocalDateTime.now().minusMinutes(5));

        var result = FlowApprovalTaskServiceImpl.filterAndSortCompletedTasks(
                List.of(older, pending, newer, otherFlow, readCcTask),
                "actor-1", "leave", null, null);

        assertEquals(List.of(readCcTask, newer, older), result);
    }

    @Test
    void shouldFilterUnreadCcTasksOnly() {
        var oldestUnreadCc = ccTask(11L, "actor-1", "leave", "inst-1", "approvalA",
                FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(4), null);
        var newestUnreadCc = ccTask(12L, "actor-1", "leave", "inst-2", "approvalB",
                FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(1), null);
        var readCc = ccTask(13L, "actor-1", "leave", "inst-3", "approvalC",
                FlowApprovalTaskStatus.READ,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(5));
        var approvalTask = approvalTask(14L, "actor-1", "leave", "inst-4", "approvalD",
                FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(3), null);

        var result = FlowApprovalTaskServiceImpl.filterAndSortCcTasks(
                List.of(newestUnreadCc, readCc, oldestUnreadCc, approvalTask),
                "actor-1", false, "leave", null, null);

        assertEquals(List.of(oldestUnreadCc, newestUnreadCc), result);
    }

    @Test
    void shouldFilterReadCcTasksOnly() {
        var olderReadCc = ccTask(21L, "actor-1", "leave", "inst-1", "approvalA",
                FlowApprovalTaskStatus.READ,
                LocalDateTime.now().minusHours(4), LocalDateTime.now().minusHours(2));
        var newerReadCc = ccTask(22L, "actor-1", "leave", "inst-2", "approvalB",
                FlowApprovalTaskStatus.READ,
                LocalDateTime.now().minusHours(3), LocalDateTime.now().minusMinutes(2));
        var unreadCc = ccTask(23L, "actor-1", "leave", "inst-3", "approvalC",
                FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(1), null);

        var result = FlowApprovalTaskServiceImpl.filterAndSortCcTasks(
                List.of(olderReadCc, unreadCc, newerReadCc),
                "actor-1", true, "leave", null, null);

        assertEquals(List.of(newerReadCc, olderReadCc), result);
    }

    @Test
    void shouldListUnreadCcBeforeReadCcWhenReadFilterIsOmitted() {
        var unreadCc = ccTask(31L, "actor-1", "leave", "inst-1", "approvalA",
                FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(2), null);
        var olderReadCc = ccTask(32L, "actor-1", "leave", "inst-2", "approvalB",
                FlowApprovalTaskStatus.READ,
                LocalDateTime.now().minusHours(4), LocalDateTime.now().minusHours(1));
        var newerReadCc = ccTask(33L, "actor-1", "leave", "inst-3", "approvalC",
                FlowApprovalTaskStatus.READ,
                LocalDateTime.now().minusHours(3), LocalDateTime.now().minusMinutes(5));

        var result = FlowApprovalTaskServiceImpl.filterAndSortCcTasks(
                List.of(olderReadCc, unreadCc, newerReadCc),
                "actor-1", null, "leave", null, null);

        assertEquals(List.of(unreadCc, newerReadCc, olderReadCc), result);
    }

    private FlowApprovalTask task(Long id,
                                         String actorId,
                                         String flowCode,
                                         String instanceId,
                                         String nodeId,
                                         FlowApprovalTaskStatus status,
                                         LocalDateTime startTime,
                                         LocalDateTime endTime) {
        var task = new FlowApprovalTask();
        task.setId(id);
        task.setActorId(actorId);
        task.setFlowCode(flowCode);
        task.setInstanceId(instanceId);
        task.setNodeId(nodeId);
        task.setStatus(status);
        task.setTaskType(FlowApprovalTaskType.APPROVAL);
        task.setStartTime(startTime);
        task.setEndTime(endTime);
        return task;
    }

    private FlowApprovalTask approvalTask(Long id,
                                                 String actorId,
                                                 String flowCode,
                                                 String instanceId,
                                                 String nodeId,
                                                 FlowApprovalTaskStatus status,
                                                 LocalDateTime startTime,
                                                 LocalDateTime endTime) {
        return task(id, actorId, flowCode, instanceId, nodeId, status, startTime, endTime);
    }

    private FlowApprovalTask ccTask(Long id,
                                           String actorId,
                                           String flowCode,
                                           String instanceId,
                                           String nodeId,
                                           FlowApprovalTaskStatus status,
                                           LocalDateTime startTime,
                                           LocalDateTime endTime) {
        var task = task(id, actorId, flowCode, instanceId, nodeId, status, startTime, endTime);
        task.setTaskType(FlowApprovalTaskType.CC);
        return task;
    }
}

