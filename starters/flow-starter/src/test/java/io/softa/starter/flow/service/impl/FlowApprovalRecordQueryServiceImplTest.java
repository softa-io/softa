package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.dto.FlowSentCcView;
import io.softa.starter.flow.entity.FlowApprovalRecord;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowApprovalRecordQueryServiceImplTest {

    @Test
    void anInstancesHistoryNamesNoAdministratorWhoOnlyUrgedOrTerminatedItAParticipant() {
        FlowApprovalRecordServiceImpl records = mock(FlowApprovalRecordServiceImpl.class);
        FlowInstanceAccessGuard guard = mock(FlowInstanceAccessGuard.class);
        FlowApprovalRecordQueryServiceImpl service =
                new FlowApprovalRecordQueryServiceImpl(records, guard, mock(FlowUserNames.class));
        LocalDateTime now = LocalDateTime.now();
        when(records.searchList(any(Filters.class))).thenReturn(List.of(
                record(60L, 1, "approver-1", "leave", "inst-1", "approvalA",
                        ApprovalActionType.APPROVE, now.minusMinutes(9), null, 1, "ok"),
                record(61L, 2, "admin-1", "leave", "inst-1", "approvalB",
                        ApprovalActionType.URGE, now.minusMinutes(5), null, 1, "please act on it"),
                record(62L, 3, "admin-2", "leave", "inst-1", null,
                        ApprovalActionType.TERMINATE, now.minusMinutes(1), null, null, "raised twice")));

        service.getByInstanceId("inst-1", "approver-1");
        service.getByInstanceId("inst-1", "admin-1");
        service.getByInstanceId("inst-1", "admin-2");

        // The approver's own action names them; the administrators are left to the guard's other grants.
        verify(guard).requireInstanceViewer("inst-1", "approver-1", true);
        verify(guard).requireInstanceViewer("inst-1", "admin-1", false);
        verify(guard).requireInstanceViewer("inst-1", "admin-2", false);
    }

    @Test
    void shouldComposeSentCcHistoryWithMatchedReadStatus() {
        LocalDateTime sentAt = LocalDateTime.now().minusMinutes(5);
        LocalDateTime readAt = LocalDateTime.now().minusMinutes(1);
        var sentCc = record(10L, 1, "sender-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.CC, sentAt, "recipient-1", 1, "fyi");
        var read = record(11L, 2, "recipient-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.READ, readAt, "sender-1", 1, "seen");

        var result = FlowApprovalRecordQueryServiceImpl.composeSentCcHistory(
                List.of(sentCc, read), "sender-1", null, "leave", "inst-1", null);

        assertEquals(1, result.size());
        FlowSentCcView response = result.getFirst();
        assertEquals("sender-1", response.getSenderActorId());
        assertEquals("recipient-1", response.getRecipientActorId());
        assertEquals(Boolean.TRUE, response.getRead());
        assertEquals(readAt, response.getReadAt());
        assertEquals("seen", response.getReadComment());
    }

    @Test
    void shouldKeepSentCcUnreadWhenNoMatchingReadExists() {
        LocalDateTime sentAt = LocalDateTime.now().minusMinutes(3);
        var sentCc = record(20L, 1, "sender-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.CC, sentAt, "recipient-1", 1, "fyi");

        var result = FlowApprovalRecordQueryServiceImpl.composeSentCcHistory(
                List.of(sentCc), "sender-1", false, "leave", "inst-1", null);

        assertEquals(1, result.size());
        assertEquals(Boolean.FALSE, result.getFirst().getRead());
        assertEquals(null, result.getFirst().getReadAt());
    }

    @Test
    void shouldIgnoreMismatchedReadEntriesWhenComposingSentCcHistory() {
        LocalDateTime sentAt = LocalDateTime.now().minusMinutes(10);
        var sentCc = record(30L, 1, "sender-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.CC, sentAt, "recipient-1", 1, "fyi");
        var wrongCycleRead = record(31L, 2, "recipient-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.READ, LocalDateTime.now().minusMinutes(8), "sender-1", 2, "seen");
        var wrongNodeRead = record(32L, 3, "recipient-1", "leave", "inst-1", "approvalB",
                ApprovalActionType.READ, LocalDateTime.now().minusMinutes(7), "sender-1", 1, "seen");
        var wrongSenderRead = record(33L, 4, "recipient-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.READ, LocalDateTime.now().minusMinutes(6), "sender-2", 1, "seen");

        var result = FlowApprovalRecordQueryServiceImpl.composeSentCcHistory(
                List.of(sentCc, wrongCycleRead, wrongNodeRead, wrongSenderRead),
                "sender-1", null, "leave", "inst-1", null);

        assertEquals(1, result.size());
        assertEquals(Boolean.FALSE, result.getFirst().getRead());
    }

    @Test
    void shouldUseLatestMatchingReadWhenComposingSentCcHistory() {
        LocalDateTime sentAt = LocalDateTime.now().minusMinutes(15);
        LocalDateTime firstReadAt = LocalDateTime.now().minusMinutes(8);
        LocalDateTime latestReadAt = LocalDateTime.now().minusMinutes(2);
        var sentCc = record(40L, 1, "sender-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.CC, sentAt, "recipient-1", 1, "please review");
        var earlierRead = record(41L, 2, "recipient-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.READ, firstReadAt, "sender-1", 1, "opened");
        var latestRead = record(42L, 3, "recipient-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.READ, latestReadAt, "sender-1", 1, "acknowledged");

        var result = FlowApprovalRecordQueryServiceImpl.composeSentCcHistory(
                List.of(sentCc, earlierRead, latestRead), "sender-1", null, "leave", "inst-1", "approvalA");

        assertEquals(1, result.size());
        assertEquals(Boolean.TRUE, result.getFirst().getRead());
        assertEquals(latestReadAt, result.getFirst().getReadAt());
        assertEquals("acknowledged", result.getFirst().getReadComment());
    }

    @Test
    void shouldFilterSentCcHistoryByReadFlag() {
        LocalDateTime readSentAt = LocalDateTime.now().minusMinutes(12);
        LocalDateTime unreadSentAt = LocalDateTime.now().minusMinutes(3);
        var readCc = record(50L, 1, "sender-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.CC, readSentAt, "recipient-1", 1, "fyi");
        var readAck = record(51L, 2, "recipient-1", "leave", "inst-1", "approvalA",
                ApprovalActionType.READ, LocalDateTime.now().minusMinutes(1), "sender-1", 1, "seen");
        var unreadCc = record(52L, 3, "sender-1", "leave", "inst-1", "approvalB",
                ApprovalActionType.CC, unreadSentAt, "recipient-2", 1, "for reference");

        var readOnly = FlowApprovalRecordQueryServiceImpl.composeSentCcHistory(
                List.of(readCc, readAck, unreadCc), "sender-1", true, "leave", "inst-1", null);
        var unreadOnly = FlowApprovalRecordQueryServiceImpl.composeSentCcHistory(
                List.of(readCc, readAck, unreadCc), "sender-1", false, "leave", "inst-1", null);

        assertEquals(1, readOnly.size());
        assertEquals("approvalA", readOnly.getFirst().getNodeId());
        assertEquals(Boolean.TRUE, readOnly.getFirst().getRead());

        assertEquals(1, unreadOnly.size());
        assertEquals("approvalB", unreadOnly.getFirst().getNodeId());
        assertEquals(Boolean.FALSE, unreadOnly.getFirst().getRead());
    }

    private FlowApprovalRecord record(Long id,
                                             Integer sequence,
                                             String actorId,
                                             String flowId,
                                             String instanceId,
                                             String nodeId,
                                             ApprovalActionType action,
                                             LocalDateTime eventTime,
                                             String targetActorId,
                                             Integer cycleNumber,
                                             String comment) {
        var record = new FlowApprovalRecord();
        record.setId(id);
        record.setSequence(sequence);
        record.setActorId(actorId);
        record.setFlowId(flowId);
        record.setInstanceId(instanceId);
        record.setNodeId(nodeId);
        record.setAction(action);
        record.setTargetActorId(targetActorId);
        record.setCycleNumber(cycleNumber);
        record.setComment(comment);
        record.setEventTime(eventTime);
        return record;
    }
}

