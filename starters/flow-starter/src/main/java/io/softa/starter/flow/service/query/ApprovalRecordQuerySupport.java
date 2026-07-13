package io.softa.starter.flow.service.query;

import java.time.LocalDateTime;
import java.util.Comparator;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.entity.FlowApprovalRecord;

/**
 * Shared approval record query sorting rule and actor-id guard.
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

    public static void requireActorId(String actorId) {
        if (!StringUtils.hasText(actorId)) {
            throw new IllegalArgumentException("actorId is required for history queries");
        }
    }
}

