package io.softa.starter.flow.service.query;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.dto.FlowSentCcView;
import io.softa.starter.flow.entity.FlowApprovalRecord;
import io.softa.starter.flow.runtime.state.ApprovalActionType;

/**
 * Composes sender-side CC history views from CC and READ records.
 */
public final class SentCcHistoryComposer {

    private SentCcHistoryComposer() {
    }

    public static List<FlowSentCcView> compose(List<FlowApprovalRecord> records,
                                                      String actorId,
                                                      Boolean read,
                                                      String flowCode,
                                                      String instanceId,
                                                      String nodeId) {
        Map<SentCcKey, FlowApprovalRecord> latestReadByKey = new LinkedHashMap<>();
        for (FlowApprovalRecord record : records) {
            if (!ApprovalActionType.READ.equals(record.getAction())
                    || !Objects.equals(actorId, record.getTargetActorId())) {
                continue;
            }
            SentCcKey key = keyOf(record.getInstanceId(), record.getNodeId(), record.getCycleNumber(),
                    record.getActorId(), record.getTargetActorId());
            latestReadByKey.merge(key, record, SentCcHistoryComposer::latestRecord);
        }

        return records.stream()
                .filter(record -> ApprovalActionType.CC.equals(record.getAction()))
                .filter(record -> Objects.equals(actorId, record.getActorId()))
                .filter(record -> !StringUtils.hasText(flowCode) || Objects.equals(flowCode, record.getFlowCode()))
                .filter(record -> !StringUtils.hasText(instanceId) || Objects.equals(instanceId, record.getInstanceId()))
                .filter(record -> !StringUtils.hasText(nodeId) || Objects.equals(nodeId, record.getNodeId()))
                .map(record -> toView(record, latestReadByKey.get(keyOf(
                        record.getInstanceId(),
                        record.getNodeId(),
                        record.getCycleNumber(),
                        record.getTargetActorId(),
                        record.getActorId()))))
                .filter(view -> read == null || Objects.equals(read, view.getRead()))
                .sorted(Comparator.comparing(FlowSentCcView::getSentAt,
                        Comparator.nullsLast(LocalDateTime::compareTo)).reversed()
                        .thenComparing(FlowSentCcView::getRecipientActorId,
                                Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private static FlowSentCcView toView(FlowApprovalRecord ccRecord,
                                                FlowApprovalRecord readRecord) {
        return FlowSentCcView.builder()
                .instanceId(ccRecord.getInstanceId())
                .flowCode(ccRecord.getFlowCode())
                .flowRevision(ccRecord.getFlowRevision())
                .nodeId(ccRecord.getNodeId())
                .nodeLabel(ccRecord.getNodeLabel())
                .cycleNumber(ccRecord.getCycleNumber())
                .senderActorId(ccRecord.getActorId())
                .recipientActorId(ccRecord.getTargetActorId())
                .sentComment(ccRecord.getComment())
                .sentAt(ccRecord.getEventTime())
                .read(readRecord != null)
                .readAt(readRecord == null ? null : readRecord.getEventTime())
                .readComment(readRecord == null ? null : readRecord.getComment())
                .build();
    }

    private static FlowApprovalRecord latestRecord(FlowApprovalRecord left,
                                                          FlowApprovalRecord right) {
        return ApprovalRecordQuerySupport.historyComparator().compare(left, right) <= 0 ? left : right;
    }

    private static SentCcKey keyOf(String instanceId,
                                   String nodeId,
                                   Integer cycleNumber,
                                   String recipientActorId,
                                   String senderActorId) {
        return new SentCcKey(instanceId, nodeId, cycleNumber, recipientActorId, senderActorId);
    }

    private record SentCcKey(String instanceId,
                             String nodeId,
                             Integer cycleNumber,
                             String recipientActorId,
                             String senderActorId) {
    }
}

