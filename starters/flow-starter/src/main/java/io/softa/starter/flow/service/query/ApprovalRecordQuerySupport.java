package io.softa.starter.flow.service.query;

import java.time.LocalDateTime;
import java.util.Comparator;

import io.softa.starter.flow.entity.FlowApprovalRecord;

/**
 * Shared approval record query sorting rule.
 */
public final class ApprovalRecordQuerySupport {

    private ApprovalRecordQuerySupport() {
    }

    public static Comparator<FlowApprovalRecord> historyComparator() {
        return Comparator.comparing(FlowApprovalRecord::getEventTime,
                        Comparator.nullsLast(LocalDateTime::compareTo)).reversed()
                .thenComparing(FlowApprovalRecord::getSequence,
                        Comparator.nullsLast(Integer::compareTo).reversed())
                .thenComparing(FlowApprovalRecord::getId,
                        Comparator.nullsLast(Long::compareTo).reversed());
    }
}
