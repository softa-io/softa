package io.softa.starter.flow.service.query;

import java.time.LocalDateTime;
import java.util.Comparator;

import io.softa.starter.flow.entity.FlowApprovalAction;

/**
 * Shared approval record query sorting rule.
 */
public final class ApprovalActionQuerySupport {

    private ApprovalActionQuerySupport() {
    }

    public static Comparator<FlowApprovalAction> historyComparator() {
        return Comparator.comparing(FlowApprovalAction::getEventTime,
                        Comparator.nullsLast(LocalDateTime::compareTo)).reversed()
                .thenComparing(FlowApprovalAction::getSequence,
                        Comparator.nullsLast(Integer::compareTo).reversed())
                .thenComparing(FlowApprovalAction::getId,
                        Comparator.nullsLast(Long::compareTo).reversed());
    }
}
