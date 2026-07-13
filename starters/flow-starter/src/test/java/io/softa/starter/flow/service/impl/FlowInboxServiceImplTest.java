package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxView;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.service.FlowApprovalTaskQueryService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowInboxServiceImplTest {

    @Test
    void shouldAggregateApprovalAndCcBucketsWithoutCompletedApprovalsByDefault() {
        FlowApprovalTaskQueryService taskService = Mockito.mock(FlowApprovalTaskQueryService.class);
        FlowInboxServiceImpl service = new FlowInboxServiceImpl(taskService);
        FlowApprovalTaskView approvalPending = task("actor-1", "leave", "inst-1", "approvalA",
                FlowApprovalTaskType.APPROVAL, FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(2), null);
        FlowApprovalTaskView strayCcInPending = task("actor-1", "leave", "inst-2", "approvalB",
                FlowApprovalTaskType.CC, FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusHours(1), null);
        FlowApprovalTaskView ccUnread = task("actor-1", "leave", "inst-3", "approvalC",
                FlowApprovalTaskType.CC, FlowApprovalTaskStatus.PENDING,
                LocalDateTime.now().minusMinutes(30), null);
        FlowApprovalTaskView ccRead = task("actor-1", "leave", "inst-4", "approvalD",
                FlowApprovalTaskType.CC, FlowApprovalTaskStatus.READ,
                LocalDateTime.now().minusHours(3), LocalDateTime.now().minusMinutes(10));

        when(taskService.pagePendingTasks("actor-1", "leave", null, null, 1, 50))
                .thenReturn(pageOf(approvalPending, strayCcInPending));
        when(taskService.pageCcTasks("actor-1", false, "leave", null, null, 1, 50))
                .thenReturn(pageOf(ccUnread));
        when(taskService.pageCcTasks("actor-1", true, "leave", null, null, 1, 50))
                .thenReturn(pageOf(ccRead));

        FlowInboxView response = service.getInbox("actor-1", "leave", null, null, null);

        assertEquals(List.of(approvalPending), response.getApprovalPendingTasks());
        assertEquals(List.of(ccUnread), response.getCcUnreadTasks());
        assertEquals(List.of(ccRead), response.getCcReadTasks());
        assertEquals(List.of(), response.getApprovalCompletedTasks());
        verify(taskService).pagePendingTasks("actor-1", "leave", null, null, 1, 50);
        verify(taskService).pageCcTasks("actor-1", false, "leave", null, null, 1, 50);
        verify(taskService).pageCcTasks("actor-1", true, "leave", null, null, 1, 50);
    }

    @Test
    void shouldIncludeCompletedApprovalTasksWhenExplicitlyRequested() {
        FlowApprovalTaskQueryService taskService = Mockito.mock(FlowApprovalTaskQueryService.class);
        FlowInboxServiceImpl service = new FlowInboxServiceImpl(taskService);
        FlowApprovalTaskView completedApproval = task("actor-1", "leave", "inst-1", "approvalA",
                FlowApprovalTaskType.APPROVAL, FlowApprovalTaskStatus.APPROVED,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(5));
        FlowApprovalTaskView readCc = task("actor-1", "leave", "inst-2", "approvalB",
                FlowApprovalTaskType.CC, FlowApprovalTaskStatus.READ,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().minusMinutes(1));

        when(taskService.pagePendingTasks("actor-1", "leave", "inst-1", null, 1, 50)).thenReturn(pageOf());
        when(taskService.pageCcTasks("actor-1", false, "leave", "inst-1", null, 1, 50)).thenReturn(pageOf());
        when(taskService.pageCcTasks("actor-1", true, "leave", "inst-1", null, 1, 50)).thenReturn(pageOf(readCc));
        when(taskService.pageCompletedTasks("actor-1", "leave", "inst-1", null, 1, 50))
                .thenReturn(pageOf(readCc, completedApproval));

        FlowInboxView response = service.getInbox("actor-1", "leave", "inst-1", null, true);

        assertEquals(List.of(), response.getApprovalPendingTasks());
        assertEquals(List.of(), response.getCcUnreadTasks());
        assertEquals(List.of(readCc), response.getCcReadTasks());
        assertEquals(List.of(completedApproval), response.getApprovalCompletedTasks());
        verify(taskService).pageCompletedTasks("actor-1", "leave", "inst-1", null, 1, 50);
    }

    private FlowApprovalTaskView task(String actorId,
                                             String flowCode,
                                             String instanceId,
                                             String nodeId,
                                             FlowApprovalTaskType taskType,
                                             FlowApprovalTaskStatus status,
                                             LocalDateTime startTime,
                                             LocalDateTime endTime) {
        return FlowApprovalTaskView.builder()
                .actorId(actorId)
                .flowCode(flowCode)
                .instanceId(instanceId)
                .nodeId(nodeId)
                .taskType(taskType)
                .status(status)
                .startTime(startTime)
                .endTime(endTime)
                .build();
    }

    private static Page<FlowApprovalTaskView> pageOf(FlowApprovalTaskView... tasks) {
        Page<FlowApprovalTaskView> page = Page.of(1, 50);
        page.setTotalCount(tasks.length);
        page.setRows(List.of(tasks));
        return page;
    }
}
