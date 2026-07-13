package io.softa.starter.flow.service.support.view;

import java.util.List;

import io.softa.starter.flow.dto.FlowApprovalRecordView;
import io.softa.starter.flow.entity.FlowApprovalRecord;

/**
 * Maps approval record entities into API views.
 */
public final class FlowApprovalRecordViewMapper {

    private FlowApprovalRecordViewMapper() {
    }

    public static FlowApprovalRecordView toView(FlowApprovalRecord record) {
        if (record == null) {
            return null;
        }
        return FlowApprovalRecordView.builder()
                .id(record.getId())
                .instanceId(record.getInstanceId())
                .flowCode(record.getFlowCode())
                .flowRevision(record.getFlowRevision())
                .nodeId(record.getNodeId())
                .nodeLabel(record.getNodeLabel())
                .cycleNumber(record.getCycleNumber())
                .taskId(record.getTaskId())
                .sequence(record.getSequence())
                .action(record.getAction())
                .actorId(record.getActorId())
                .targetActorId(record.getTargetActorId())
                .addSignPosition(record.getAddSignPosition())
                .targetNodeId(record.getTargetNodeId())
                .targetNodeLabel(record.getTargetNodeLabel())
                .comment(record.getComment())
                .statusBefore(record.getStatusBefore())
                .statusAfter(record.getStatusAfter())
                .approvedActors(record.getApprovedActors())
                .rejectedActors(record.getRejectedActors())
                .variableKeys(record.getVariableKeys())
                .eventTime(record.getEventTime())
                .build();
    }

    public static List<FlowApprovalRecordView> toViews(List<FlowApprovalRecord> records) {
        return records.stream().map(FlowApprovalRecordViewMapper::toView).toList();
    }
}

