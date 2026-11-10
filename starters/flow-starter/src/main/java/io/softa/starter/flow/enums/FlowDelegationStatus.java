package io.softa.starter.flow.enums;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.entity.FlowDelegation;

/**
 * Where a delegation rule stands at a moment. Not stored: the rule's {@code active} flag only says whether it was
 * cancelled, and its period ({@code startTime} / {@code endTime}, either end open) whether it applies yet, or still.
 * The delegation lookup routes a task by a rule that is {@link #ACTIVE} when the task arrives, and the lists show the
 * same status, so a rule never reads as in force while it routes nothing.
 */
@Getter
@AllArgsConstructor
public enum FlowDelegationStatus {

    /** Not cancelled, its start time still ahead: it routes nothing yet. */
    SCHEDULED("Scheduled"),

    /** Not cancelled and within its period: the delegator's tasks arriving now go to the delegate. */
    ACTIVE("Active"),

    /** Not cancelled, its end time passed: it routes nothing any more. */
    ENDED("Ended"),

    /** Cancelled, by its delegator or an administrator. */
    CANCELLED("Cancelled");

    @JsonValue
    private final String type;

    /** The rule's status at {@code now}; its start and end times themselves are within its period. */
    public static FlowDelegationStatus of(FlowDelegation delegation, LocalDateTime now) {
        if (!Boolean.TRUE.equals(delegation.getActive())) {
            return CANCELLED;
        }
        if (delegation.getStartTime() != null && now.isBefore(delegation.getStartTime())) {
            return SCHEDULED;
        }
        if (delegation.getEndTime() != null && now.isAfter(delegation.getEndTime())) {
            return ENDED;
        }
        return ACTIVE;
    }

    /**
     * The rules in this status at {@code now}, as a condition on their stored fields — decided as {@link #of} decides,
     * so a list narrowed to a status holds exactly the rules showing it.
     */
    public Filters condition(LocalDateTime now) {
        if (this == CANCELLED) {
            return Filters.or(new Filters().ne(FlowDelegation::getActive, true),
                    new Filters().isNotSet(FlowDelegation::getActive));
        }
        Filters notCancelled = new Filters().eq(FlowDelegation::getActive, true);
        if (this == SCHEDULED) {
            return notCancelled.gt(FlowDelegation::getStartTime, now);
        }
        Filters started = Filters.or(new Filters().isNotSet(FlowDelegation::getStartTime),
                new Filters().le(FlowDelegation::getStartTime, now));
        Filters ended = new Filters().lt(FlowDelegation::getEndTime, now);
        if (this == ENDED) {
            return notCancelled.and(started).and(ended);
        }
        return notCancelled.and(started).and(Filters.or(new Filters().isNotSet(FlowDelegation::getEndTime),
                new Filters().ge(FlowDelegation::getEndTime, now)));
    }
}
