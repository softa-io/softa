package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.entity.FlowApprovalRecord;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;

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
                        ApprovalActionType.APPROVE, now.minusMinutes(9), null, "ok"),
                record(61L, 2, "admin-1", "leave", "inst-1", "approvalB",
                        ApprovalActionType.URGE, now.minusMinutes(5), null, "please act on it"),
                record(62L, 3, "admin-2", "leave", "inst-1", null,
                        ApprovalActionType.TERMINATE, now.minusMinutes(1), null, "raised twice")));

        service.getByInstanceId("inst-1", "approver-1");
        service.getByInstanceId("inst-1", "admin-1");
        service.getByInstanceId("inst-1", "admin-2");

        // The approver's own action names them; the administrators are left to the guard's other grants.
        verify(guard).requireInstanceViewer("inst-1", "approver-1", true);
        verify(guard).requireInstanceViewer("inst-1", "admin-1", false);
        verify(guard).requireInstanceViewer("inst-1", "admin-2", false);
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
        record.setComment(comment);
        record.setEventTime(eventTime);
        return record;
    }
}
