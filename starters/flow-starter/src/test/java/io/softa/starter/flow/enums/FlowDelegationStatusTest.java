package io.softa.starter.flow.enums;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.entity.FlowDelegation;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A rule's status follows its flag and its period alone, both ends of the period within it — the one answer the
 * delegation lookup and the lists share, so a rule that routes nothing never reads as active.
 */
class FlowDelegationStatusTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    @Test
    void aRuleWhoseStartIsAheadIsScheduled() {
        assertEquals(FlowDelegationStatus.SCHEDULED, status(true, NOW.plusMinutes(1), null));
        assertEquals(FlowDelegationStatus.SCHEDULED, status(true, NOW.plusDays(1), NOW.plusDays(5)));
    }

    @Test
    void aRuleIsActiveWithinItsPeriodBothEndsIncluded() {
        assertEquals(FlowDelegationStatus.ACTIVE, status(true, null, null), "no period: from its creation on");
        assertEquals(FlowDelegationStatus.ACTIVE, status(true, NOW, null), "from its start time");
        assertEquals(FlowDelegationStatus.ACTIVE, status(true, null, NOW), "until its end time");
        assertEquals(FlowDelegationStatus.ACTIVE, status(true, NOW.minusDays(1), NOW.plusDays(1)));
    }

    @Test
    void aRuleWhoseEndHasPassedHasEnded() {
        assertEquals(FlowDelegationStatus.ENDED, status(true, null, NOW.minusMinutes(1)));
        assertEquals(FlowDelegationStatus.ENDED, status(true, NOW.minusDays(5), NOW.minusDays(1)));
    }

    @Test
    void aCancelledRuleIsCancelledWhateverItsPeriod() {
        assertEquals(FlowDelegationStatus.CANCELLED, status(false, null, null));
        assertEquals(FlowDelegationStatus.CANCELLED, status(false, NOW.plusDays(1), null));
        assertEquals(FlowDelegationStatus.CANCELLED, status(false, null, NOW.minusDays(1)));
        assertEquals(FlowDelegationStatus.CANCELLED, status(null, null, null), "stored without the flag");
    }

    private static FlowDelegationStatus status(Boolean active, LocalDateTime start, LocalDateTime end) {
        FlowDelegation delegation = new FlowDelegation();
        delegation.setActive(active);
        delegation.setStartTime(start);
        delegation.setEndTime(end);
        return FlowDelegationStatus.of(delegation, NOW);
    }
}
